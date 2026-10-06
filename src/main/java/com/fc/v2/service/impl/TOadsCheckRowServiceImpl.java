package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.checkrow.CheckRowRuleException;
import com.fc.v2.mapper.auto.TOadsCheckBatchMapper;
import com.fc.v2.mapper.auto.TOadsCheckFlowMapper;
import com.fc.v2.mapper.auto.TOadsCheckRowMapper;
import com.fc.v2.model.auto.TOadsCheckBatch;
import com.fc.v2.model.auto.TOadsCheckFlow;
import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.model.custom.CheckRowBatchReceipt;
import com.fc.v2.model.custom.CheckRowFeeBook;
import com.fc.v2.model.custom.SpotBatchBringOutcome;
import com.fc.v2.model.custom.SpotForm;
import com.fc.v2.service.ITOadsCheckRowService;
import com.fc.v2.service.ITOadsSpotRegisterService;
import com.fc.v2.service.checkrow.CheckRowConsts;
import com.fc.v2.service.spot.SpotFlowStep;
import com.fc.v2.common.exception.spot.SpotRuleException;

/**
 * 集中更换核验行核收唯一裁决处实现。
 *
 * <p>以前这一档手工绕过两回，病根都在旧 submitBatch：失败行先插一遍、成功行又插一遍（同批交两回
 * 数量翻倍）；行号只在遇错时自增（定位失败行定位到别的行）；有错整批 return 0（一行填错整批拦下）；
 * 空批 rows.get(0) 直接抛错；批内同码两条都入库；状态/年度各算各的。本类按牌匾收讫那套范式根治：
 * <ul>
 *   <li>新建、修改两个入口，校验规矩各写各的（{@link #createRule} / {@link #modifyRule}），
 *       每行结论落判定来源，依的是哪一套第几条一查便知；</li>
 *   <li>单遍裁决：报文原报行次就是行号，合并后一组只落一条、只裁一次、只 insert 一次，
 *       一行有错只退那行，不连坐；</li>
 *   <li>批内同编码明细先合并再裁（{@link #mergeGroups}），不两条都入库；</li>
 *   <li>批次码幂等：重交只加 dup_count、落 RESEND，行不重复入；修改按原报行次覆盖，本就幂等；</li>
 *   <li>空批兜底返 0、越防呆硬界返 -1，都不抛错退出；</li>
 *   <li>收费年度批级冻结（{@link CheckRowConsts#feeYearOf}），行上冗余，整册核收与逐行落去向同读它。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-10-06
 */
@org.springframework.stereotype.Service
public class TOadsCheckRowServiceImpl implements ITOadsCheckRowService {

    private static final int DEL_OK = 0;
    private static final int DEL_DELETED = 1;
    private static final int BATCH_RECEIVED = 0;

    @javax.annotation.Resource
    private TOadsCheckRowMapper oadsCheckRowMapper;
    @javax.annotation.Resource
    private TOadsCheckBatchMapper batchMapper;
    @javax.annotation.Resource
    private TOadsCheckFlowMapper flowMapper;

    @Autowired
    private ITOadsSpotRegisterService spotRegisterService;

    // ================================================================ 新建入口

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitNewBatch(String batchNo, List<TOadsCheckRow> rows, String putKind, String operator) {
        String no = requireBatchNo(batchNo);
        List<TOadsCheckRow> forms = rows == null ? new ArrayList<TOadsCheckRow>() : rows;
        // 空批兜底：零行不成批，返 0，不报错退出、不立批、不入库。
        if (forms.isEmpty()) {
            return 0;
        }
        // 没有行数上限这回事，多多益善；只防越过硬界的误传，整批不入库返 -1。
        if (forms.size() > CheckRowConsts.HARD_ROW_LIMIT) {
            return -1;
        }
        String channel = normalizePut(putKind);
        Date now = new Date();

        TOadsCheckBatch existed = findBatch(no);
        if (existed != null) {
            // 同批并着送两回（不管哪条来路）：幂等。不另立批、行不重复入，只加回数落 RESEND。
            existed.setDupCount((existed.getDupCount() == null ? 0 : existed.getDupCount()) + 1);
            existed.setUpdateBy(operator);
            existed.setUpdateTime(now);
            batchMapper.updateById(existed);
            String sameOrOther = channel.equals(existed.getPutKind()) ? "同一条" : "另一条";
            appendFlow(no, CheckRowConsts.FLOW_RESEND, CheckRowConsts.ENTRY_CREATE, channel,
                    existed.getCountedRows(), existed.getLandedRows(), existed.getAcceptedRows(),
                    existed.getReturnedRows(),
                    "批次码 " + no + " 第" + (existed.getDupCount() + 1) + "回交来，走" + CheckRowConsts.putName(channel)
                            + "（头一回走" + CheckRowConsts.putName(existed.getPutKind()) + "，系" + sameOrOther
                            + "来路）：批不另立、行不重复入，数量不翻倍，回执照原批行账回库逐行重数",
                    operator, now);
            return existed.getAcceptedRows() == null ? 0 : existed.getAcceptedRows();
        }

        int feeYear = CheckRowConsts.feeYearOf(now);
        TOadsCheckBatch batch = new TOadsCheckBatch();
        batch.setBatchNo(no);
        batch.setPutKind(channel);
        batch.setCountedRows(forms.size());
        batch.setDupCount(0);
        batch.setFeeYear(feeYear);
        batch.setFeeYearBasis("收费年度按本批首回收讫那一刻（" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(now) + "）所在年度冻结为 " + feeYear
                + "；整批一个年度，整册核收与逐行落去向都读本值，不按核验年/报送年另算");
        batch.setReceivedAt(now);
        batch.setStatus(BATCH_RECEIVED);
        batch.setDelFlag(DEL_OK);
        batch.setCreateBy(operator);
        batch.setCreateTime(now);

        List<Merged> groups = mergeGroups(no, forms);
        List<TOadsCheckRow> landed = new ArrayList<TOadsCheckRow>();
        int accepted = 0;
        int returned = 0;
        for (Merged g : groups) {
            TOadsCheckRow row = adjudicate(g, channel, feeYear, operator, now,
                    CheckRowConsts.SOURCE_CREATE, true);
            oadsCheckRowMapper.insert(row);
            landed.add(row);
            if (CheckRowConsts.isReturned(row.getStatus())) {
                returned++;
            } else {
                accepted++;
            }
        }

        int mergedCount = forms.size() - groups.size();
        batch.setLandedRows(groups.size());
        batch.setMergedRows(mergedCount);
        batch.setAcceptedRows(accepted);
        batch.setReturnedRows(returned);
        batch.setRemark("新建入口核收：进门物理行 " + forms.size() + "（合并前），同码合并掉 " + mergedCount
                + " 条，入账 " + groups.size() + "，收下 " + accepted + "，退回 " + returned
                + "；来路与收费年度按首回冻结。没有行数上限，多多益善");
        batchMapper.insert(batch);

        appendFlow(no, CheckRowConsts.FLOW_CREATE, CheckRowConsts.ENTRY_CREATE, channel,
                forms.size(), groups.size(), accepted, returned,
                "新建收讫：物理行 " + forms.size() + "，合并后入账 " + groups.size() + "（收下 " + accepted
                        + "＋退回 " + returned + "＝" + groups.size() + "），来路" + CheckRowConsts.putName(channel)
                        + "，收费年度冻结 " + feeYear,
                operator, now);
        logMerges(no, CheckRowConsts.ENTRY_CREATE, channel, groups, operator, now);
        return accepted;
    }

    // ================================================================ 修改入口（另一套校验）

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckRowBatchReceipt modifyBatch(String batchNo, List<TOadsCheckRow> rows, String operator) {
        String no = requireBatchNo(batchNo);
        TOadsCheckBatch batch = requireBatch(no);
        List<TOadsCheckRow> forms = rows == null ? new ArrayList<TOadsCheckRow>() : rows;
        if (forms.isEmpty()) {
            // 修改入口交空：没东西可改，不报错退出，回当前回执。
            return buildReceipt(batch, CheckRowConsts.ENTRY_MODIFY, new ArrayList<CheckRowBatchReceipt.Mismatch>(),
                    operator, false);
        }
        if (forms.size() > CheckRowConsts.HARD_ROW_LIMIT) {
            throw new CheckRowRuleException("修改批 " + no + " 一次交来 " + forms.size()
                    + " 行，越防呆硬界 " + CheckRowConsts.HARD_ROW_LIMIT + "，整批改版不入库");
        }
        Date now = new Date();

        // 来路、收费年度、批次身份以首回收讫版为准：修改版不重定、不重算。
        String channel = batch.getPutKind();
        int feeYear = batch.getFeeYear() == null ? CheckRowConsts.feeYearOf(batch.getReceivedAt())
                : batch.getFeeYear().intValue();

        List<TOadsCheckRow> existing = listRowsOfBatch(no);
        Map<Integer, TOadsCheckRow> byRowNo = new LinkedHashMap<Integer, TOadsCheckRow>();
        for (TOadsCheckRow r : existing) {
            byRowNo.put(r.getRowNo(), r);
        }

        // 修改版内部先按编码合并（与新建同一套合并手法，但判定走修改那套规矩）。
        List<Merged> groups = mergeGroups(no, forms);
        List<CheckRowBatchReceipt.Mismatch> extraMismatch = new ArrayList<CheckRowBatchReceipt.Mismatch>();
        List<Merged> applied = new ArrayList<Merged>();
        List<Integer> swallowRowNos = new ArrayList<Integer>();

        for (Merged g : groups) {
            Integer leadRowNo = g.leadRowNo;
            TOadsCheckRow lead = byRowNo.get(leadRowNo);
            if (lead == null) {
                // 对不上原报行次：不借修改塞新行。这一改不落地，逐行点名（不拦别行），按原报行次定位。
                extraMismatch.add(new CheckRowBatchReceipt.Mismatch(leadRowNo, g.itemCode,
                        "修改/校验第6条：原报行次 " + leadRowNo + " 不在批 " + no
                                + " 已收讫行内，修改只许就原报行次改版，不许借修改塞新行，本行不改"));
                continue;
            }
            // 组内其余行次：账上若有独立旧行，合并进主行后软删，不留第二条。
            for (Integer other : g.otherRowNos) {
                TOadsCheckRow extra = byRowNo.get(other);
                if (extra != null) {
                    extra.setDelFlag(DEL_DELETED);
                    extra.setUpdateBy(operator);
                    extra.setUpdateTime(now);
                    oadsCheckRowMapper.updateById(extra);
                    swallowRowNos.add(other);
                } else {
                    extraMismatch.add(new CheckRowBatchReceipt.Mismatch(other, g.itemCode,
                            "修改/校验第6条：原报行次 " + other + " 不在批 " + no
                                    + " 已收讫行内，该明细不另立行，已并入第 " + leadRowNo + " 行一起改"));
                }
            }
            // 就着原行改版：行次/批次/来路/年度不动，内容与结论以修改版重裁。
            g.leadRowNo = lead.getRowNo();
            TOadsCheckRow adjudicated = adjudicate(g, channel, feeYear, operator, now,
                    CheckRowConsts.SOURCE_MODIFY, false);
            adjudicated.setId(lead.getId());
            adjudicated.setCreateBy(lead.getCreateBy());
            adjudicated.setCreateTime(lead.getCreateTime());
            adjudicated.setUpdateBy(operator);
            adjudicated.setUpdateTime(now);
            oadsCheckRowMapper.updateById(adjudicated);
            applied.add(g);
        }

        // 未提交的行保留（改版只动交来的行次）。销账三数回库逐行重数回写，与行账同口径。
        List<TOadsCheckRow> after = listRowsOfBatch(no);
        int accepted = 0;
        int returned = 0;
        for (TOadsCheckRow r : after) {
            if (CheckRowConsts.isReturned(r.getStatus())) {
                returned++;
            } else {
                accepted++;
            }
        }
        int landed = after.size();
        batch.setLandedRows(landed);
        // counted/merged 记的是首回核收那一刻的进门点数与合并数，修改落版不回改（版本基准以首回为准）；
        // 结构变化由 MODIFY/MERGE 流水逐句交代。
        batch.setAcceptedRows(accepted);
        batch.setReturnedRows(returned);
        batch.setUpdateBy(operator);
        batch.setUpdateTime(now);
        batchMapper.updateById(batch);

        appendFlow(no, CheckRowConsts.FLOW_MODIFY, CheckRowConsts.ENTRY_MODIFY, channel,
                forms.size(), landed, accepted, returned,
                "修改落版：交来 " + forms.size() + " 条，就原报行次覆盖 " + applied.size()
                        + " 组并按修改那套规矩重裁；来路仍" + CheckRowConsts.putName(channel)
                        + "、收费年度仍 " + feeYear + "（以首回收讫版为准，不重定不重算）；未交来的行保留",
                operator, now);
        if (!swallowRowNos.isEmpty()) {
            logMerges(no, CheckRowConsts.ENTRY_MODIFY, channel, applied, operator, now);
        }
        return buildReceipt(batch, CheckRowConsts.ENTRY_MODIFY, extraMismatch, operator, false);
    }

    // ================================================================ 回执（一律回库逐行数）

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckRowBatchReceipt receipt(String batchNo, String operator) {
        TOadsCheckBatch batch = requireBatch(requireBatchNo(batchNo));
        return buildReceipt(batch, null, new ArrayList<CheckRowBatchReceipt.Mismatch>(), operator, true);
    }

    private CheckRowBatchReceipt buildReceipt(TOadsCheckBatch batch, String entryKind,
                                              List<CheckRowBatchReceipt.Mismatch> extraMismatch,
                                              String operator, boolean logFlow) {
        List<TOadsCheckRow> rows = listRowsOfBatch(batch.getBatchNo());
        int landed = rows.size();
        int accepted = 0;
        int returned = 0;

        CheckRowBatchReceipt rc = new CheckRowBatchReceipt();
        rc.setBatchNo(batch.getBatchNo());
        rc.setPutKind(batch.getPutKind());
        rc.setEntryKind(entryKind);
        rc.setReceivedAt(batch.getReceivedAt());
        rc.setFeeYear(batch.getFeeYear());
        rc.setFeeYearBasis(batch.getFeeYearBasis());
        rc.setCountedRows(batch.getCountedRows() == null ? 0 : batch.getCountedRows());
        rc.setMergedRows(batch.getMergedRows() == null ? 0 : batch.getMergedRows());
        rc.setDupCount(batch.getDupCount() == null ? 0 : batch.getDupCount());
        rc.setDuplicate(rc.getDupCount() > 0);

        for (TOadsCheckRow r : rows) {
            CheckRowBatchReceipt.RowItem item = new CheckRowBatchReceipt.RowItem();
            item.setRowNo(r.getRowNo() == null ? 0 : r.getRowNo());
            item.setItemCode(r.getItemCode());
            item.setQty(r.getQty());
            item.setChkNum(r.getChkNum());
            item.setStatus(r.getStatus());
            item.setStatusName(CheckRowConsts.statusName(r.getStatus()));
            item.setReason(r.getReason());
            item.setJudgeSource(r.getJudgeSource());
            item.setFeeYear(r.getFeeYear());
            item.setMergedRowNos(parseRowNos(r.getMergedRowNos()));
            boolean rowReturned = CheckRowConsts.isReturned(r.getStatus());
            item.setReturned(rowReturned);
            if (rowReturned) {
                returned++;
            } else if (CheckRowConsts.isAccepted(r.getStatus())) {
                accepted++;
            } else {
                // 第四种状态不并句、不"基本相符"，逐行点名。
                extraMismatch.add(new CheckRowBatchReceipt.Mismatch(item.getRowNo(), r.getItemCode(),
                        "本行状态是「" + r.getStatus() + "」，不是已收下/已退回两值之一，无法计入"));
            }
            rc.getRows().add(item);
        }

        boolean balanced = accepted + returned == landed;
        if (batch.getLandedRows() != null && batch.getLandedRows() != landed) {
            extraMismatch.add(new CheckRowBatchReceipt.Mismatch(0, null,
                    "批账入账数 " + batch.getLandedRows() + " 与回库逐行数得 " + landed + " 不一致"));
            balanced = false;
        }
        if (batch.getAcceptedRows() != null && batch.getAcceptedRows() != accepted) {
            extraMismatch.add(new CheckRowBatchReceipt.Mismatch(0, null,
                    "批账收下数 " + batch.getAcceptedRows() + " 与回库逐行数得 " + accepted + " 不一致"));
            balanced = false;
        }
        if (batch.getReturnedRows() != null && batch.getReturnedRows() != returned) {
            extraMismatch.add(new CheckRowBatchReceipt.Mismatch(0, null,
                    "批账退回数 " + batch.getReturnedRows() + " 与回库逐行数得 " + returned + " 不一致"));
            balanced = false;
        }

        rc.setLandedRows(landed);
        rc.setAcceptedRows(accepted);
        rc.setReturnedRows(returned);
        rc.getMismatches().addAll(extraMismatch);
        rc.setBalanced(balanced && extraMismatch.isEmpty());

        StringBuilder note = new StringBuilder();
        note.append("四数均回库逐行数出：物理行 ").append(rc.getCountedRows()).append("（合并前），入账 ")
                .append(landed).append("＝收下 ").append(accepted).append("＋退回 ").append(returned)
                .append("；同码合并掉 ").append(rc.getMergedRows()).append(" 条，没有第二条入账。");
        note.append("收费年度冻结为 ").append(batch.getFeeYear())
                .append("（按首回收讫时刻，整册核收与逐行落去向同读此值）。");
        if (rc.getDupCount() > 0) {
            note.append("本批次码累计重交 ").append(rc.getDupCount())
                    .append(" 回，重交不另立批、行不重复入、数量不翻倍。");
        }
        if (!rc.getMismatches().isEmpty()) {
            note.append("对不齐 ").append(rc.getMismatches().size()).append(" 笔，已逐行点名，不认「基本相符」。");
        }
        rc.setNote(note.toString());

        if (logFlow) {
            appendFlow(batch.getBatchNo(), CheckRowConsts.FLOW_RECEIPT, null, null,
                    rc.getCountedRows(), landed, accepted, returned,
                    "出回执：回库逐行数得 入账" + landed + " 收下" + accepted + " 退回" + returned
                            + "（收费年度 " + batch.getFeeYear() + "）"
                            + (rc.isBalanced() ? "，互印平齐" : "，有" + rc.getMismatches().size() + "笔对不上已点名"),
                    operator, new Date());
        }
        return rc;
    }

    // ================================================================ 退回明细 / 详情 / 收费册

    @Override
    public List<TOadsCheckRow> listErrors(String batchNo) {
        // 与详情/收费册/回执同一个状态谓词，不另写 "status==2"。
        List<TOadsCheckRow> out = new ArrayList<TOadsCheckRow>();
        for (TOadsCheckRow r : listRowsOfBatch(requireBatchNo(batchNo))) {
            if (CheckRowConsts.isReturned(r.getStatus())) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public TOadsCheckRow rowDetail(String batchNo, int rowNo) {
        for (TOadsCheckRow r : listRowsOfBatch(requireBatchNo(batchNo))) {
            if (r.getRowNo() != null && r.getRowNo() == rowNo) {
                return r;
            }
        }
        throw new CheckRowRuleException("批 " + batchNo + " 里没有第 " + rowNo + " 行：按原报行次点，不游移到别批");
    }

    @Override
    public CheckRowFeeBook feeBook(Integer feeYear, String batchNo, String operator) {
        // 一份行账、一个状态谓词、一个年度字段：列表/详情怎么判，这里就怎么归集。
        List<TOadsCheckBatch> batches = batchMapper.selectList(new QueryWrapper<TOadsCheckBatch>()
                .eq("del_flag", DEL_OK));
        Collections.sort(batches, new Comparator<TOadsCheckBatch>() {
            @Override
            public int compare(TOadsCheckBatch a, TOadsCheckBatch b) {
                return Long.compare(a.getId() == null ? 0L : a.getId(), b.getId() == null ? 0L : b.getId());
            }
        });
        Map<Integer, CheckRowFeeBook.YearBucket> bucketMap = new TreeMap<Integer, CheckRowFeeBook.YearBucket>();
        int scanned = 0;
        for (TOadsCheckBatch b : batches) {
            if (batchNo != null && !batchNo.trim().isEmpty() && !batchNo.trim().equals(b.getBatchNo())) {
                continue;
            }
            scanned++;
            for (TOadsCheckRow r : listRowsOfBatch(b.getBatchNo())) {
                // 年度只认行上冻结值；理论上不会空，空了回批账补，绝不当场按今天重算。
                Integer year = r.getFeeYear() == null ? b.getFeeYear() : r.getFeeYear();
                if (feeYear != null && feeYear.intValue() != year.intValue()) {
                    continue;
                }
                CheckRowFeeBook.YearBucket bucket = bucketMap.get(year);
                if (bucket == null) {
                    bucket = new CheckRowFeeBook.YearBucket();
                    bucket.setFeeYear(year);
                    bucketMap.put(year, bucket);
                }
                CheckRowFeeBook.RowLine line = new CheckRowFeeBook.RowLine();
                line.setBatchNo(b.getBatchNo());
                line.setRowNo(r.getRowNo() == null ? 0 : r.getRowNo());
                line.setItemCode(r.getItemCode());
                line.setQty(r.getQty());
                line.setChkNum(r.getChkNum());
                line.setStatus(r.getStatus());
                line.setStatusName(CheckRowConsts.statusName(r.getStatus()));
                line.setReason(r.getReason());
                line.setJudgeSource(r.getJudgeSource());
                line.setFeeYear(year);
                boolean returned = CheckRowConsts.isReturned(r.getStatus());
                line.setReturned(returned);
                bucket.getLines().add(line);
                if (returned) {
                    bucket.setReturnedRows(bucket.getReturnedRows() + 1);
                } else {
                    bucket.setAcceptedRows(bucket.getAcceptedRows() + 1);
                    if (r.getQty() != null) {
                        bucket.setAcceptedQty(bucket.getAcceptedQty().add(r.getQty()));
                    }
                    if (r.getChkNum() != null) {
                        bucket.setAcceptedChkNum(bucket.getAcceptedChkNum().add(r.getChkNum()));
                    }
                }
            }
        }
        CheckRowFeeBook out = new CheckRowFeeBook();
        out.setFeeYear(feeYear);
        out.setBatchNo(batchNo);
        out.setYears(new ArrayList<CheckRowFeeBook.YearBucket>(bucketMap.values()));
        out.setNote("收费册归集扫了 " + scanned + " 批；年度一律读行上冻结的收费年度（首回收讫时刻所在年），"
                + "退回行不落收费册；状态与列表/详情/回执同一个谓词");
        return out;
    }

    // ================================================================ 旧入口与带出通道（保留）

    /**
     * @deprecated 旧形状保留；内部走新建入口唯一裁决处（区级报上来来路），
     *     旧调用方因此一并得到幂等、合并、逐行退回、空批兜底。
     */
    @Override
    @Deprecated
    public int submitBatch(String batchNo, List<TOadsCheckRow> rows) {
        String no = batchNo == null || batchNo.trim().isEmpty()
                ? (rows == null || rows.isEmpty() ? null : rows.get(0).getBatchNo())
                : batchNo.trim();
        // 旧调用方交空批（连批次码都没有）：兜底返 0，不再像旧实现那样 rows.get(0) 抛错退出。
        if (no == null || no.isEmpty()) {
            return 0;
        }
        return submitNewBatch(no, rows, CheckRowConsts.PUT_DISTRICT, "核验岗");
    }

    @Override
    public TOadsCheckRow selectTOadsCheckRowById(Long id) {
        return this.oadsCheckRowMapper.selectById(id);
    }

    @Override
    public List<TOadsCheckBatch> listBatches(Integer feeYear) {
        List<TOadsCheckBatch> list = batchMapper.selectList(new QueryWrapper<TOadsCheckBatch>()
                .eq("del_flag", DEL_OK));
        List<TOadsCheckBatch> out = new ArrayList<TOadsCheckBatch>();
        for (TOadsCheckBatch b : list) {
            if (feeYear == null || feeYear.equals(b.getFeeYear())) {
                out.add(b);
            }
        }
        Collections.sort(out, new Comparator<TOadsCheckBatch>() {
            @Override
            public int compare(TOadsCheckBatch a, TOadsCheckBatch b) {
                Date da = a.getReceivedAt();
                Date db = b.getReceivedAt();
                int byTime = 0;
                if (da != null && db != null) {
                    byTime = db.compareTo(da);
                } else if (da != null) {
                    byTime = -1;
                } else if (db != null) {
                    byTime = 1;
                }
                if (byTime != 0) {
                    return byTime;
                }
                return Long.compare(b.getId() == null ? 0L : b.getId(), a.getId() == null ? 0L : a.getId());
            }
        });
        return out;
    }

    /**
     * 建行第二通道：核验完成后带出。逐行交名册唯一裁决处，复用同一套五检查点与同一套数量逻辑；
     * 一行不过带回那一个检查点自己的提示语，不拖累同批别行，也不合并成"数据有误"。
     */
    @Override
    public SpotBatchBringOutcome bringOutSpots(String batchNo, List<SpotForm> forms, String operator) {
        SpotBatchBringOutcome outcome = new SpotBatchBringOutcome();
        outcome.setBatchNo(batchNo);
        if (forms == null || forms.isEmpty()) {
            // 空批兜底：不带出任何行，回空回执，不报错退出。
            return outcome;
        }
        if (forms.size() > CheckRowConsts.HARD_ROW_LIMIT) {
            throw new SpotRuleException("集中更换单批带出" + forms.size() + "行，越过防呆硬界 "
                    + CheckRowConsts.HARD_ROW_LIMIT + "，整批不带出");
        }
        int ok = 0;
        for (SpotForm form : forms) {
            String spotNo = form == null || form.getSpotNo() == null ? null : form.getSpotNo().trim();
            try {
                com.fc.v2.model.custom.SpotBuildResult r =
                        spotRegisterService.build(form, SpotFlowStep.BUILD_BATCH, operator);
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

    // ================================================================ 批内合并 + 两套校验

    /** 合并中间结构：同编码一组；leadRowNo=组内最小原报行次（失败行按它定位）。 */
    private static class Merged {
        private String batchNo;
        private String itemCode;
        private int leadRowNo;
        private List<Integer> otherRowNos = new ArrayList<Integer>();
        private BigDecimal qty = BigDecimal.ZERO;
        private BigDecimal chkNum = BigDecimal.ZERO;
        private boolean qtyPresent;
        private boolean chkPresent;
        private boolean codeMissing;
        private final List<String> constituents = new ArrayList<String>();
    }

    /**
     * 批内合并：同一处点位编码的重复明细并成一条（处数合计、行次取组内最小），
     * 发生在裁决之前——不会有同批两条都入库。编码缺填的行各自成组，不互相并。
     */
    private List<Merged> mergeGroups(String batchNo, List<TOadsCheckRow> forms) {
        Map<String, Merged> byCode = new LinkedHashMap<String, Merged>();
        List<Merged> ordered = new ArrayList<Merged>();
        int index = 0;
        for (TOadsCheckRow r : forms) {
            index++;
            int rowNo = r.getRowNo() == null ? index : r.getRowNo();
            r.setBatchNo(batchNo);
            String code = r.getItemCode() == null ? null : r.getItemCode().trim();
            boolean codeMissing = code == null || code.isEmpty();
            String key = codeMissing ? ("missing:" + index) : code;
            Merged g = byCode.get(key);
            if (g == null) {
                g = new Merged();
                g.batchNo = batchNo;
                g.itemCode = codeMissing ? null : code;
                g.leadRowNo = rowNo;
                g.codeMissing = codeMissing;
                byCode.put(key, g);
                ordered.add(g);
            } else {
                // 主行次取组内最小原报行次（报文乱序也要对）；被比下去的原主行次并入"其余行次"。
                if (rowNo < g.leadRowNo) {
                    g.otherRowNos.add(g.leadRowNo);
                    g.leadRowNo = rowNo;
                } else {
                    g.otherRowNos.add(rowNo);
                }
            }
            if (r.getQty() != null) {
                g.qty = g.qty.add(r.getQty());
                g.qtyPresent = true;
            }
            if (r.getChkNum() != null) {
                g.chkNum = g.chkNum.add(r.getChkNum());
                g.chkPresent = true;
            }
            g.constituents.add("第" + rowNo + "行(qty=" + r.getQty() + ",chk=" + r.getChkNum() + ")");
        }
        for (Merged g : ordered) {
            Collections.sort(g.otherRowNos);
        }
        return ordered;
    }

    /**
     * 一行裁决。两个入口各写各的校验，规则号各从 1 排起；结论只落 已收下/已退回，
     * 退回逐句攒齐并写判定来源（哪一套第几条）。
     */
    private TOadsCheckRow adjudicate(Merged g, String putKind, int feeYear, String operator, Date now,
                                     String source, boolean isCreate) {
        TOadsCheckRow row = new TOadsCheckRow();
        row.setBatchNo(g.batchNo);
        row.setRowNo(g.leadRowNo);
        row.setItemCode(g.itemCode);
        row.setQty(g.qtyPresent ? g.qty : null);
        row.setChkNum(g.chkPresent ? g.chkNum : null);
        row.setPutKind(putKind);
        row.setFeeYear(feeYear);
        row.setDelFlag(DEL_OK);
        row.setCreateBy(operator);
        row.setCreateTime(now);
        if (!g.otherRowNos.isEmpty()) {
            row.setMergedRowNos(joinInts(g.otherRowNos, ","));
        }

        List<String> reasons = isCreate
                ? createRules(g)
                : modifyRules(g);

        if (reasons.isEmpty()) {
            row.setStatus(CheckRowConsts.STATUS_ACCEPTED);
            row.setJudgeSource(source + "/校验第1-5条全过");
            if (!g.otherRowNos.isEmpty()) {
                row.setReason("批内同码 " + g.otherRowNos.size() + " 条已并入本行（原报行次 "
                        + joinInts(g.otherRowNos, "、") + "），处数按明细合计，只此一条入账");
            }
        } else {
            row.setStatus(CheckRowConsts.STATUS_RETURNED);
            row.setJudgeSource(source + "/校验第" + firstRuleNo(reasons) + "条");
            row.setReason(join(reasons, "；"));
            if (!g.otherRowNos.isEmpty()) {
                row.setReason(row.getReason() + "；本组合并了原报行次 " + joinInts(g.otherRowNos, "、")
                        + "，同进同退只落这一条");
            }
        }
        return row;
    }

    // ---- 新建入口的一套（独立编号，独立措辞） ----
    private List<String> createRules(Merged g) {
        List<String> reasons = new ArrayList<String>();
        if (g.codeMissing) {
            reasons.add("新建/校验第1条：点位编码缺填（身份只认编码，不按名称近似去配）");
        }
        if (!g.qtyPresent) {
            reasons.add("新建/校验第2条：应更换处数缺填，本行不收");
        } else {
            if (g.qty.compareTo(BigDecimal.ZERO) == 0) {
                reasons.add("新建/校验第3条：应更换处数为零");
            } else if (g.qty.compareTo(BigDecimal.ZERO) < 0) {
                reasons.add("新建/校验第3条：应更换处数为负数(" + g.qty.stripTrailingZeros().toPlainString() + ")");
            }
        }
        if (g.chkPresent && g.chkNum.compareTo(BigDecimal.ZERO) < 0) {
            reasons.add("新建/校验第4条：核验合格处数为负数(" + g.chkNum.stripTrailingZeros().toPlainString() + ")");
        }
        if (g.qtyPresent && g.chkPresent && g.chkNum.compareTo(g.qty) > 0) {
            reasons.add("新建/校验第5条：核验合格处数(" + g.chkNum.stripTrailingZeros().toPlainString()
                    + ")高于应更换处数(" + g.qty.stripTrailingZeros().toPlainString() + ")");
        }
        return reasons;
    }

    // ---- 修改入口的另一套（措辞、侧重与新建不同；编号独立） ----
    private List<String> modifyRules(Merged g) {
        List<String> reasons = new ArrayList<String>();
        if (g.codeMissing) {
            reasons.add("修改/校验第1条：改版行点位编码缺填，修改不允许把编码改空");
        }
        if (!g.qtyPresent) {
            reasons.add("修改/校验第2条：改版未给应更换处数，改版不得把处数改没");
        } else if (g.qty.compareTo(BigDecimal.ZERO) <= 0) {
            reasons.add("修改/校验第3条：改版应更换处数须为正数(" + g.qty.stripTrailingZeros().toPlainString() + ")");
        }
        if (g.chkPresent && g.chkNum.compareTo(BigDecimal.ZERO) < 0) {
            reasons.add("修改/校验第4条：改版核验合格处数为负(" + g.chkNum.stripTrailingZeros().toPlainString() + ")");
        }
        if (g.qtyPresent && g.chkPresent && g.chkNum.compareTo(g.qty) > 0) {
            reasons.add("修改/校验第5条：改版核验合格处数(" + g.chkNum.stripTrailingZeros().toPlainString()
                    + ")高于应更换处数(" + g.qty.stripTrailingZeros().toPlainString() + ")，改版不成立");
        }
        return reasons;
    }

    private String firstRuleNo(List<String> reasons) {
        for (String s : reasons) {
            int i = s.indexOf("第");
            int j = s.indexOf("条");
            if (i >= 0 && j > i) {
                return s.substring(i + 1, j);
            }
        }
        return "?";
    }

    private void logMerges(String batchNo, String entryKind, String putKind, List<Merged> groups,
                           String operator, Date now) {
        List<String> parts = new ArrayList<String>();
        int mergedGroups = 0;
        for (Merged g : groups) {
            if (g.otherRowNos.isEmpty()) {
                continue;
            }
            mergedGroups++;
            if (mergedGroups <= CheckRowConsts.MERGE_NOTE_ROWS) {
                parts.add("编码 " + g.itemCode + "：原报第" + joinInts(g.otherRowNos, "、") + "行并入第"
                        + g.leadRowNo + "行，合并明细[" + join(g.constituents, "；") + "]");
            }
        }
        if (mergedGroups == 0) {
            return;
        }
        String tail = mergedGroups > CheckRowConsts.MERGE_NOTE_ROWS
                ? "；另有 " + (mergedGroups - CheckRowConsts.MERGE_NOTE_ROWS) + " 组同码合并未逐组列" : "";
        appendFlow(batchNo, CheckRowConsts.FLOW_MERGE, entryKind, putKind, null, null, null, null,
                "批内同码明细合并 " + mergedGroups + " 组（先并后裁，不两条都入库）：" + join(parts, "；") + tail,
                operator, now);
    }

    // ================================================================ 取数 / 工具

    private List<TOadsCheckRow> listRowsOfBatch(String batchNo) {
        List<TOadsCheckRow> rows = oadsCheckRowMapper.listByBatch(batchNo);
        return rows == null ? new ArrayList<TOadsCheckRow>() : rows;
    }

    private TOadsCheckBatch findBatch(String batchNo) {
        List<TOadsCheckBatch> list = batchMapper.selectList(new QueryWrapper<TOadsCheckBatch>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_OK));
        return list.isEmpty() ? null : list.get(0);
    }

    private TOadsCheckBatch requireBatch(String batchNo) {
        TOadsCheckBatch b = findBatch(batchNo);
        if (b == null) {
            throw new CheckRowRuleException("批次码 " + batchNo + " 从未在本环节核收，账上无此批");
        }
        return b;
    }

    private String requireBatchNo(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            throw new CheckRowRuleException("批次码缺填：一次核收为一批，批次码是批的身份，没它落不了账");
        }
        return batchNo.trim();
    }

    private String normalizePut(String putKind) {
        return CheckRowConsts.PUT_CITY.equals(putKind) ? CheckRowConsts.PUT_CITY : CheckRowConsts.PUT_DISTRICT;
    }

    private void appendFlow(String batchNo, String action, String entryKind, String putKind,
                            Integer total, Integer landed, Integer accepted, Integer returned,
                            String content, String operator, Date at) {
        TOadsCheckFlow f = new TOadsCheckFlow();
        f.setBatchNo(batchNo);
        f.setAction(action);
        f.setEntryKind(entryKind);
        f.setPutKind(putKind);
        f.setTotalRows(total);
        f.setLandedRows(landed);
        f.setAcceptedRows(accepted);
        f.setReturnedRows(returned);
        f.setContent(clip(content));
        f.setOperator(operator);
        f.setActedAt(at);
        f.setCreateBy(operator);
        f.setCreateTime(at);
        flowMapper.insert(f);
    }

    private static List<Integer> parseRowNos(String text) {
        List<Integer> out = new ArrayList<Integer>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        for (String p : text.split(",")) {
            try {
                out.add(Integer.valueOf(p.trim()));
            } catch (NumberFormatException ignore) {
                // 落账格式由本类写，读不出来的片段不当场炸，跳过仍给出其余行次。
            }
        }
        return out;
    }

    private static String joinInts(List<?> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
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

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
