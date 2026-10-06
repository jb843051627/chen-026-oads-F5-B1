package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.spot.SpotRuleException;
import com.fc.v2.mapper.auto.TOadsCheckRowMapper;
import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.model.custom.SpotBatchBringOutcome;
import com.fc.v2.model.custom.SpotBuildResult;
import com.fc.v2.model.custom.SpotForm;
import com.fc.v2.service.ITOadsCheckRowService;
import com.fc.v2.service.ITOadsSpotRegisterService;
import com.fc.v2.service.spot.SpotFlowStep;

/**
 * 集中更换核验行 Service业务层处理（batch-process 形状：整批提交）
 *
 * <p>核验行整批提交的口径全在 {@link #submitBatch} 一处，逐条毛病也只由
 * {@link #rejectReasons} 这一把尺量：提交落账与事后回查（{@link #listErrors}）
 * 同源同判，不存在两处各判一套、回查指不出依哪套标准的事。
 *
 * <p>定死的取舍：
 * <ul>
 *   <li>没有行数上限这回事——来多少物理行收多少，多多益善；</li>
 *   <li>空批次是合法白卷：一行都不查、不插、不报错，回 0；</li>
 *   <li>行号就是报文里的物理行次（从 1 逐行数），合法非法都照原行次落，按行号点失败行点不错；</li>
 *   <li>一行一裁：一行填错只退那一行，别行照常收下，不整批连坐；</li>
 *   <li>批内同一处点位（item_code 相同）的重复明细先合并成一条（处数相加、行号取头一条），
 *       两条不许都入库；</li>
 *   <li>同一批次码并着送两回要幂等：第二回起一条都不另插，回库把已收下行数照数交回。</li>
 * </ul>
 *
 * <p>{@link #bringOutSpots} 承接"集中更换业务在核验完成后带出建行"，不另起一套数量逻辑与
 * 判定，逐行走点位名册唯一裁决处（BATCH 通道），本体不动。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TOadsCheckRowServiceImpl implements ITOadsCheckRowService {

    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;
    private static final int DEL_OK = 0;

    @javax.annotation.Resource
    private TOadsCheckRowMapper oadsCheckRowMapper;

    @Autowired
    private ITOadsSpotRegisterService spotRegisterService;

    @Override
    public TOadsCheckRow selectTOadsCheckRowById(Long id) {
        return this.oadsCheckRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TOadsCheckRow> rows) {
        String no = batchNo == null ? null : batchNo.trim();
        if (no == null || no.isEmpty()) {
            throw new SpotRuleException("集中更换批次码缺填：整批核验行落不了账，批次码是这一批的身份");
        }
        // 空批次兜底：合法白卷，不查不插不报错，更不许 rows.get(0) 当场炸给人看。
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        // 同一批并着送两回要幂等：这一批号账上已有行，第二回起一条不另插，
        // 回库把已收下（非退回）行数照数交回——数量不会凭空多一倍。
        List<TOadsCheckRow> existed = listBatchRows(no);
        if (!existed.isEmpty()) {
            return countAccepted(existed);
        }

        // 批内同一处点位的重复明细先合并：key=点位编码，行号取头一条，处数相加。
        // 用 LinkedHashMap 保头一条的物理先后；编码缺填的行不并（各自各自裁）。
        Map<String, TOadsCheckRow> mergedByCode = new LinkedHashMap<String, TOadsCheckRow>();
        List<TOadsCheckRow> codeMissing = new ArrayList<TOadsCheckRow>();
        int physicalNo = 0;
        for (TOadsCheckRow r : rows) {
            physicalNo++;
            if (r == null) {
                r = new TOadsCheckRow();
            }
            r.setRowNo(physicalNo);
            String code = r.getItemCode() == null ? null : r.getItemCode().trim();
            if (code == null || code.isEmpty()) {
                codeMissing.add(r);
                continue;
            }
            r.setItemCode(code);
            TOadsCheckRow first = mergedByCode.get(code);
            if (first == null) {
                mergedByCode.put(code, r);
            } else {
                mergeInto(first, r);
            }
        }

        List<TOadsCheckRow> prepared = new ArrayList<TOadsCheckRow>();
        prepared.addAll(mergedByCode.values());
        prepared.addAll(codeMissing);

        // 一行一裁：合法收下、非法退回；行号照物理行次，毛病逐句带核验点标识落 remark。
        // 批内合并的交代即便本行通过也保留（事后翻得出这行是并出来的）。
        int accepted = 0;
        for (TOadsCheckRow r : prepared) {
            r.setBatchNo(no);
            r.setDelFlag(DEL_OK);
            String mergeNote = r.getRemark();
            List<String> reasons = rejectReasons(r);
            if (reasons.isEmpty()) {
                r.setStatus(STATUS_OK);
                r.setRemark(mergeNote);
                accepted++;
            } else {
                r.setStatus(STATUS_FAIL);
                r.setRemark(mergeNote == null || mergeNote.trim().isEmpty()
                        ? join(reasons, "；") : mergeNote + "；" + join(reasons, "；"));
            }
            this.oadsCheckRowMapper.insert(r);
        }

        // 交回的数以回库为准（与 listErrors 同一处判定、同一份状态口径），不抄内存里的计数器。
        return countAccepted(listBatchRows(no));
    }

    @Override
    public List<TOadsCheckRow> listErrors(String batchNo) {
        return this.oadsCheckRowMapper.selectList(new QueryWrapper<TOadsCheckRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", DEL_OK)
                .orderByAsc("row_no"));
    }

    /**
     * 核验行唯一一把尺：毛病逐句攒、各带核验点标识，提交落账与回查都只问这里，
     * 不并句成"数据有误"。与点位名册五检查点同口径：核验合格处数不得高于应更换处数。
     */
    private List<String> rejectReasons(TOadsCheckRow r) {
        List<String> reasons = new ArrayList<String>();
        String code = r.getItemCode() == null ? null : r.getItemCode().trim();
        if (code == null || code.isEmpty()) {
            reasons.add("核验点1身份：点位编码(item_code)缺填，本行对不上任何一处点位");
        }
        BigDecimal qty = r.getQty();
        if (qty == null) {
            reasons.add("核验点2数量：本行应更换处数(qty)缺填");
        } else if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            reasons.add("核验点2数量：本行应更换处数(qty)不是正数(" + qty.stripTrailingZeros().toPlainString() + ")");
        }
        BigDecimal chk = r.getChkNum();
        if (chk == null) {
            reasons.add("核验点3数量：本行核验合格处数(chk_num)缺填");
        } else if (chk.compareTo(BigDecimal.ZERO) < 0) {
            reasons.add("核验点3数量：本行核验合格处数(chk_num)为负("
                    + chk.stripTrailingZeros().toPlainString() + ")");
        }
        if (qty != null && chk != null && chk.compareTo(qty) > 0) {
            // 与名册"已核验不得高于核定应设"同口径：合格处数比该换的还多，这批数对不上。
            reasons.add("核验点3数量：核验合格处数(" + chk.stripTrailingZeros().toPlainString()
                    + ")高于本行应更换处数(" + qty.stripTrailingZeros().toPlainString() + ")");
        }
        return reasons;
    }

    /** 批内重复明细合并：处数相加（任一缺值则合后仍缺，交由唯一裁决处退）；行号取头一条。 */
    private void mergeInto(TOadsCheckRow first, TOadsCheckRow dup) {
        first.setQty(addNullable(first.getQty(), dup.getQty()));
        first.setChkNum(addNullable(first.getChkNum(), dup.getChkNum()));
        String note = "同批第" + dup.getRowNo() + "行与第" + first.getRowNo()
                + "行点位编码重复，已并为一条，处数相加、两条不重复入库";
        first.setRemark(first.getRemark() == null || first.getRemark().trim().isEmpty()
                ? note : first.getRemark() + "；" + note);
    }

    private static BigDecimal addNullable(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.add(b);
    }

    private List<TOadsCheckRow> listBatchRows(String batchNo) {
        List<TOadsCheckRow> list = this.oadsCheckRowMapper.selectList(new QueryWrapper<TOadsCheckRow>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_OK));
        return list == null ? new ArrayList<TOadsCheckRow>() : list;
    }

    /** 已收下行数：只认 status=1；退回（2）不计，第四种状态也不并进来。 */
    private int countAccepted(List<TOadsCheckRow> batchRows) {
        int n = 0;
        for (TOadsCheckRow r : batchRows) {
            if (r.getStatus() != null && r.getStatus() == STATUS_OK) {
                n++;
            }
        }
        return n;
    }

    private static String join(List<?> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * 建行第二通道：核验完成后带出。逐行交名册唯一裁决处，复用同一套五检查点与同一套数量逻辑；
     * 一行不过带回那一个检查点自己的提示语，不拖累同批别行，也不合并成"数据有误"。
     *
     * <p>不设单批行数上限，来多少带多少。季度口径在批次级只定一次：报文没带季度码的行
     * 统一补成同一册认定的当季（区局名册配置那一份），整册核收与逐行落去向不会一年之差；
     * 报文显式带了季度码的行尊重其原报，不替它改季。
     */
    @Override
    public SpotBatchBringOutcome bringOutSpots(String batchNo, List<SpotForm> forms, String operator) {
        SpotBatchBringOutcome outcome = new SpotBatchBringOutcome();
        outcome.setBatchNo(batchNo);
        if (forms == null || forms.isEmpty()) {
            return outcome;
        }
        // 批次级季度只认这一回：先取报文里第一个明确季度，全没有才向区局名册配置要当季。
        String batchQuarter = null;
        for (SpotForm f : forms) {
            if (f != null && f.getQuarterCode() != null && !f.getQuarterCode().trim().isEmpty()) {
                batchQuarter = f.getQuarterCode().trim();
                break;
            }
        }
        if (batchQuarter == null) {
            batchQuarter = spotRegisterService.currentQuarter();
        }
        for (SpotForm f : forms) {
            if (f != null && (f.getQuarterCode() == null || f.getQuarterCode().trim().isEmpty())) {
                f.setQuarterCode(batchQuarter);
            }
        }

        int ok = 0;
        for (SpotForm form : forms) {
            String spotNo = form == null || form.getSpotNo() == null ? null : form.getSpotNo().trim();
            try {
                SpotBuildResult r = spotRegisterService.build(form, SpotFlowStep.BUILD_BATCH, operator);
                outcome.getItems().add(SpotBatchBringOutcome.Item.ok(spotNo, r));
                ok++;
            } catch (SpotRuleException e) {
                // 每个检查点各有各的提示语，原样逐行带回，不许并句。
                outcome.getItems().add(SpotBatchBringOutcome.Item.fail(spotNo, e.getMessage()));
            }
        }
        outcome.setTotal(forms.size());
        outcome.setSuccessCount(ok);
        outcome.setFailureCount(forms.size() - ok);
        return outcome;
    }
}
