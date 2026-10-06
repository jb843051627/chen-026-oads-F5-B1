package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TOadsSpotMapper;
import com.fc.v2.mapper.auto.TOadsAreaBookMapper;
import com.fc.v2.mapper.auto.TOadsSpotGradeLogMapper;
import com.fc.v2.model.auto.TOadsSpot;
import com.fc.v2.model.auto.TOadsAreaBook;
import com.fc.v2.model.auto.TOadsSpotGradeLog;
import com.fc.v2.model.custom.SafetyGradeResult;
import com.fc.v2.model.custom.SafetyRuleSnapshot;
import com.fc.v2.model.custom.SpotDangling;
import com.fc.v2.model.custom.SpotGradeQuery;
import com.fc.v2.model.custom.SpotGradeOutcome;
import com.fc.v2.service.ITOadsRuleLineService;
import com.fc.v2.service.ITOadsSpotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点位底册Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TOadsSpotServiceImpl extends ServiceImpl<TOadsSpotMapper, TOadsSpot> implements ITOadsSpotService {

    @Autowired
    private TOadsAreaBookMapper oadsAreaBookMapper;

    @Autowired
    private ITOadsRuleLineService oadsRuleLineService;

    @Autowired
    private TOadsSpotGradeLogMapper oadsSpotGradeLogMapper;

    @Override
    public TOadsSpot selectTOadsSpotById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TOadsSpot>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TOadsSpot> selectTOadsSpotList(Wrapper<TOadsSpot> queryWrapper) {
        // 列表与详情同一口径、与五检查点名册同一口径：正常在册（del_flag=0）就算，
        // 册面情形（新入册/已核齐/缺项）不影响在列，不另写一条 status=0 把别档行藏掉；
        // 也不内部写死分页（分页由调用方 startPage 统一给），传入条件照用，不另起空 wrapper 丢掉。
        QueryWrapper<TOadsSpot> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TOadsSpot>) queryWrapper : new QueryWrapper<TOadsSpot>();
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTOadsSpot(TOadsSpot record) {
        if (record == null) {
            return 0;
        }
        TOadsAreaBook refArch = resolveArea(record.getAreaId());
        String bad = validateForSave(record, null, refArch, true);
        if (bad != null) {
            return 0;
        }

        record.setCreateBy(record.getSpotNo());
        record.setAreaNo(refArch.getAreaNo());
        // 挂牌情形档/挂设档位只许由设置许可核准单封存同一次处理挪：主档新建一律未挂设、第0档
        record.setSetState(0);
        record.setSetVer(0);
        // 安全分档三列只许由分档固化那一个事务写：新建一律未分档、代号与起用日留空
        record.setSafeLevel(0);
        record.setSafeRuleCode(null);
        record.setSafeEffStart(null);
        record.setStatus(record.getStatus() == null ? 0 : record.getStatus());
        applyLack(record, null);
        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTOadsSpot(TOadsSpot record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TOadsSpot current = this.baseMapper.selectById(record.getId());
        if (current == null || (current.getDelFlag() != null && current.getDelFlag() != 0)) {
            return 0;
        }
        // 修改口没报编码即沿用现码（旧纸簿行可能空着），两入口随后走同一把尺。
        if (record.getSpotNo() == null || record.getSpotNo().trim().isEmpty()) {
            record.setSpotNo(current.getSpotNo());
        }
        // 修改口显式改挂才重查段册；没点新段册就沿用原挂靠，旧行原无挂靠的不拦修改。
        Integer areaId = record.getAreaId() != null ? record.getAreaId() : current.getAreaId();
        TOadsAreaBook refArch = areaId == null ? null : resolveArea(areaId);
        String bad = validateForSave(record, current, refArch, record.getAreaId() != null);
        if (bad != null) {
            return 0;
        }

        // 挂牌情形档/挂设档位的唯一挪法是核准单封存；通用修改入口报来的这两列一律不采信，压回现值。
        // 安全分档三列同理：只许 gradeAndFix 那一个事务固化，旁入口一律不采信。
        // 在走步序（flow_step）只由步序留痕重数落账，旁入口不采信报文自报的档。
        record.setSetState(current.getSetState());
        record.setSetVer(current.getSetVer());
        record.setSafeLevel(current.getSafeLevel());
        record.setSafeRuleCode(current.getSafeRuleCode());
        record.setSafeEffStart(current.getSafeEffStart());
        record.setFlowStep(current.getFlowStep());
        if (refArch != null) {
            record.setAreaNo(refArch.getAreaNo());
        } else {
            // 没点新段册：冗余代号压回现值，不采信报文光报一个 area_no 字符串改挂靠。
            record.setAreaNo(current.getAreaNo());
        }
        applyLack(record, current);

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TOadsSpot>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    /**
     * 新建与修改两个入口共用的唯一一把尺——不再各写各的：
     * 编码非空、同码不重复（修改排除自身）、数量不为负且已核验不高于核定。
     * 辖段挂接：新建必须在册未注销；修改只在显式改挂时重查在册未注销（旧行原挂靠不重裁）。
     * 任一不满足逐句返回毛病；返回 null 表示过得去。
     */
    private String validateForSave(TOadsSpot record, TOadsSpot current, TOadsAreaBook refArch, boolean areaExplicit) {
        boolean create = current == null;
        String spotNo = record.getSpotNo() == null ? null : record.getSpotNo().trim();
        if (spotNo == null || spotNo.isEmpty()) {
            // 新建必须自带编码；修改口没报编码前面已沿用现码，走到这还空着是旧行本就无码，兼容不拦。
            if (create) {
                return "点位编码(spot_no)缺填";
            }
        } else {
            record.setSpotNo(spotNo);
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TOadsSpot>()
                    .eq("spot_no", spotNo).eq("del_flag", 0)
                    .ne(!create, "id", record.getId()));
            if (dupCnt != null && dupCnt > 0) {
                return "点位编码(" + spotNo + ")已在册，同一编码不许再立一行";
            }
        }
        if (create || areaExplicit) {
            if (refArch == null) {
                return "挂接的辖段段册查无此行";
            }
            if (refArch.getStatus() != null && refArch.getStatus() == 1) {
                return "辖段(" + refArch.getAreaNo() + ")已注销，不再接新增/落册";
            }
        }
        Integer need = record.getNeedNum();
        Integer got = record.getGotNum();
        if (!create) {
            // 修改口没报数量即沿用现值，两入口裁的是同一套有效值。
            need = need != null ? need : current.getNeedNum();
            got = got != null ? got : current.getGotNum();
        }
        int n = need == null ? 0 : need;
        int g = got == null ? 0 : got;
        if (n < 0) {
            return "核定应设处数(need_num)为负(" + n + ")";
        }
        if (g < 0) {
            return "已核验落地处数(got_num)为负(" + g + ")";
        }
        if (g > n) {
            return "已核验落地处数(" + g + ")高于核定应设处数(" + n + ")";
        }
        return null;
    }

    /** 待补处数只有一套口径：核定应设-已核验；修改口缺值沿用现值。新建/修改同此一法。 */
    private void applyLack(TOadsSpot record, TOadsSpot current) {
        int need = record.getNeedNum() != null
                ? record.getNeedNum()
                : (current == null || current.getNeedNum() == null ? 0 : current.getNeedNum());
        int got = record.getGotNum() != null
                ? record.getGotNum()
                : (current == null || current.getGotNum() == null ? 0 : current.getGotNum());
        record.setNeedNum(need);
        record.setGotNum(got);
        record.setLackNum(need - got);
    }

    /** 按段册主键取在册行；取不到（含已删）返回 null。新建/修改两入口同一取法。 */
    private TOadsAreaBook resolveArea(Integer areaId) {
        if (areaId == null) {
            return null;
        }
        return oadsAreaBookMapper.selectOne(new QueryWrapper<TOadsAreaBook>()
                .eq("id", areaId).eq("del_flag", 0));
    }

    @Override
    public int deleteTOadsSpotByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTOadsSpotById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    // ---------------- 牌面安全分档（全市统一口径，服务端一处给出） ----------------

    @Override
    public SafetyGradeResult gradeSpot(Long spotId, BigDecimal score, Date at) {
        TOadsSpot spot = loadNormalSpot(spotId);
        if (spot == null) {
            return SafetyGradeResult.none(null, "点位不存在或已删除，不予判定");
        }
        if (at == null) {
            at = new Date();
        }
        // 点位挂着代号走按标识取值，空着走整表取优；都在同一份快照上，两问同档。
        SafetyRuleSnapshot snap = this.oadsRuleLineService.snapshot(at);
        return this.oadsRuleLineService.grade(snap, spot.getRuleCode(), score);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SafetyGradeResult gradeAndFix(Long spotId, BigDecimal score, Date at) {
        TOadsSpot spot = loadNormalSpot(spotId);
        if (spot == null) {
            return SafetyGradeResult.none(null, "点位不存在或已删除，不予判定");
        }
        if (at == null) {
            at = new Date();
        }
        // 先有依据后有结论：同一份快照、同一次比对先给出档位与采信的那一条，
        // 不许先定档位再回头挑一条规则来圆。
        SafetyRuleSnapshot snap = this.oadsRuleLineService.snapshot(at);
        SafetyGradeResult grade = this.oadsRuleLineService.grade(snap, spot.getRuleCode(), score);

        // 固化那一步与留痕那一步同成一个事务：点位行三列与留痕行一对，逐字相同。
        // 不分档（0）时判据线代号与起用那一一日一并留空——set 成 null，不借邻近、不抄上季。
        this.baseMapper.update(null, new UpdateWrapper<TOadsSpot>()
                .eq("id", spot.getId())
                .eq("del_flag", 0)
                .set("safe_level", grade.getLevel())
                .set("safe_rule_code", grade.getRuleCode())
                .set("safe_eff_start", grade.getEffStart())
                .set("update_time", at));

        TOadsSpotGradeLog log = new TOadsSpotGradeLog();
        log.setSpotId(spot.getId());
        log.setSpotNo(spot.getSpotNo());
        log.setScore(score);
        log.setScoreAligned(grade.getAlignedScore());
        log.setSafeLevel(grade.getLevel());
        log.setRuleCode(grade.getRuleCode());
        log.setRuleId(grade.getRuleId());
        log.setEffStart(grade.getEffStart());
        log.setAskAt(at);
        log.setGradeMsg(grade.getMessage());
        this.oadsSpotGradeLogMapper.insert(log);

        return grade;
    }

    @Override
    public List<SpotGradeOutcome> gradeBatch(List<SpotGradeQuery> queries, Date at) {
        List<SpotGradeOutcome> outcomes = new ArrayList<SpotGradeOutcome>();
        if (queries == null || queries.isEmpty()) {
            return outcomes;
        }
        if (at == null) {
            at = new Date();
        }
        // 一批只算一次：清单、条数、逐条判定出自同一份快照，不许各起一轮。
        SafetyRuleSnapshot snap = this.oadsRuleLineService.snapshot(at);
        for (SpotGradeQuery q : queries) {
            if (q == null || q.getSpotId() == null) {
                continue;
            }
            TOadsSpot spot = loadNormalSpot(q.getSpotId());
            if (spot == null) {
                outcomes.add(new SpotGradeOutcome(q.getSpotId(), null,
                        SafetyGradeResult.none(null, "点位不存在或已删除，不予判定")));
                continue;
            }
            SafetyGradeResult grade = this.oadsRuleLineService.grade(snap, spot.getRuleCode(), q.getScore());
            outcomes.add(new SpotGradeOutcome(spot.getId(), spot.getSpotNo(), grade));
        }
        return outcomes;
    }

    @Override
    public List<SpotDangling> listDangling(Date at) {
        if (at == null) {
            at = new Date();
        }
        SafetyRuleSnapshot snap = this.oadsRuleLineService.snapshot(at);
        List<SpotDangling> dangling = new ArrayList<SpotDangling>();
        // 只列挂着代号的；代号空着的点位走整表取优，不存在"取不到生效版本"一说。
        for (TOadsSpot spot : this.baseMapper.selectList(new QueryWrapper<TOadsSpot>()
                .eq("del_flag", 0).isNotNull("rule_code").ne("rule_code", ""))) {
            String code = spot.getRuleCode();
            if (code == null || code.trim().isEmpty()) {
                continue;
            }
            if (snap.findByCode(code) != null) {
                continue;
            }
            String reason;
            SafetyRuleSnapshot.Dirty dirty = snap.dirtyOf(code);
            if (dirty != null) {
                reason = "判据线代号[" + code + "]本轮不予采信：" + String.join("；", dirty.getNotes());
            } else {
                reason = "判据线代号[" + code + "]在" + com.fc.v2.util.OadsWorkdayUtil.ts(at)
                        + "取不到生效版本（已撤下／未到起用日／已交班／从不存在）";
            }
            dangling.add(new SpotDangling(spot.getId(), spot.getSpotNo(), code, reason));
        }
        return dangling;
    }

    private TOadsSpot loadNormalSpot(Long spotId) {
        if (spotId == null) {
            return null;
        }
        TOadsSpot spot = this.baseMapper.selectById(spotId);
        if (spot == null || (spot.getDelFlag() != null && spot.getDelFlag() != 0)) {
            return null;
        }
        return spot;
    }
}
