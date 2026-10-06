import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.common.exception.checkrow.CheckRowRuleException;
import com.fc.v2.mapper.auto.TOadsCheckBatchMapper;
import com.fc.v2.mapper.auto.TOadsCheckFlowMapper;
import com.fc.v2.mapper.auto.TOadsCheckRowMapper;
import com.fc.v2.model.auto.TOadsCheckBatch;
import com.fc.v2.model.auto.TOadsCheckFlow;
import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.model.custom.CheckRowBatchReceipt;
import com.fc.v2.model.custom.CheckRowFeeBook;
import com.fc.v2.service.checkrow.CheckRowConsts;
import com.fc.v2.service.impl.TOadsCheckRowServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 区局核收"集中更换核验行"这一档前两回手工绕过的病根，逐条回归（mapper 全 Mock，内存账台）：
 * 没有行数上限；新建/修改各一套校验且判定来源可指；失败行按原报行次定位；
 * 列表/详情/收费册/回执同一状态口径；同批交两回幂等不翻倍；收费年度批级冻结一处读；
 * 批内同码合并只入一条；一行错不连坐；空批兜底 0；越硬界 -1。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TOadsCheckRowSubmitBatchTest {

    @Mock private TOadsCheckRowMapper rowMapper;
    @Mock private TOadsCheckBatchMapper batchMapper;
    @Mock private TOadsCheckFlowMapper flowMapper;

    private TOadsCheckRowServiceImpl svc;

    private final Map<Long, TOadsCheckBatch> batches = new LinkedHashMap<Long, TOadsCheckBatch>();
    private final Map<Long, TOadsCheckRow> rows = new LinkedHashMap<Long, TOadsCheckRow>();
    private final List<TOadsCheckFlow> flows = new ArrayList<TOadsCheckFlow>();
    private long seq = 2000;

    private static String stringParam(Wrapper<?> w) {
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) w;
        aw.getSqlSegment();
        for (Object v : aw.getParamNameValuePairs().values()) {
            if (v instanceof String) {
                return (String) v;
            }
        }
        return null;
    }

    @BeforeEach
    public void wire() {
        svc = new TOadsCheckRowServiceImpl();
        ReflectionTestUtils.setField(svc, "oadsCheckRowMapper", rowMapper);
        ReflectionTestUtils.setField(svc, "batchMapper", batchMapper);
        ReflectionTestUtils.setField(svc, "flowMapper", flowMapper);

        when(batchMapper.selectList(any())).thenAnswer(i -> {
            String no = stringParam(i.getArgument(0));
            List<TOadsCheckBatch> l = new ArrayList<TOadsCheckBatch>();
            for (TOadsCheckBatch b : batches.values()) {
                if ((b.getDelFlag() == null || b.getDelFlag() == 0)
                        && (no == null || no.equals(b.getBatchNo()))) {
                    l.add(b);
                }
            }
            return l;
        });
        when(batchMapper.insert(any(TOadsCheckBatch.class))).thenAnswer(i -> {
            TOadsCheckBatch b = i.getArgument(0);
            b.setId(++seq);
            batches.put(b.getId(), b);
            return 1;
        });
        when(batchMapper.updateById(any(TOadsCheckBatch.class))).thenAnswer(i -> {
            TOadsCheckBatch b = i.getArgument(0);
            batches.put(b.getId(), b);
            return 1;
        });

        when(rowMapper.insert(any(TOadsCheckRow.class))).thenAnswer(i -> {
            TOadsCheckRow r = i.getArgument(0);
            r.setId(++seq);
            rows.put(r.getId(), r);
            return 1;
        });
        when(rowMapper.updateById(any(TOadsCheckRow.class))).thenAnswer(i -> {
            TOadsCheckRow r = i.getArgument(0);
            rows.put(r.getId(), r);
            return 1;
        });
        when(rowMapper.listByBatch(anyString())).thenAnswer(i -> {
            String no = i.getArgument(0);
            List<TOadsCheckRow> l = new ArrayList<TOadsCheckRow>();
            for (TOadsCheckRow r : rows.values()) {
                if (no.equals(r.getBatchNo()) && (r.getDelFlag() == null || r.getDelFlag() == 0)) {
                    l.add(r);
                }
            }
            l.sort((a, b) -> Integer.compare(
                    a.getRowNo() == null ? 0 : a.getRowNo(), b.getRowNo() == null ? 0 : b.getRowNo()));
            return l;
        });

        when(flowMapper.insert(any(TOadsCheckFlow.class))).thenAnswer(i -> {
            TOadsCheckFlow f = i.getArgument(0);
            f.setId(++seq);
            flows.add(f);
            return 1;
        });
    }

    private TOadsCheckRow cr(int rowNo, String code, String qty, String chk) {
        TOadsCheckRow r = new TOadsCheckRow();
        r.setRowNo(rowNo);
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        r.setChkNum(chk == null ? null : new BigDecimal(chk));
        return r;
    }

    private long countFlow(String batchNo, String action) {
        return flows.stream().filter(f -> batchNo.equals(f.getBatchNo()) && action.equals(f.getAction())).count();
    }

    private TOadsCheckBatch batch(String no) {
        for (TOadsCheckBatch b : batches.values()) {
            if (no.equals(b.getBatchNo())) {
                return b;
            }
        }
        return null;
    }

    private int thisYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    // ================================================================ 1) 没有行数上限

    @Test
    public void noRowLimit_manyRowsAllLanded() {
        List<TOadsCheckRow> many = new ArrayList<TOadsCheckRow>();
        for (int i = 1; i <= 201; i++) {
            many.add(cr(i, "SP" + i, "1", "1"));
        }
        int accepted = svc.submitNewBatch("B-MANY", many, CheckRowConsts.PUT_DISTRICT, "tester");
        assertEquals(201, accepted);
        assertEquals(201, batch("B-MANY").getLandedRows().intValue());
        assertEquals(0, batch("B-MANY").getReturnedRows().intValue());
    }

    @Test
    public void overHardGuardReturnsMinusOneAndNothingLands() {
        List<TOadsCheckRow> huge = new ArrayList<TOadsCheckRow>();
        for (int i = 0; i < CheckRowConsts.HARD_ROW_LIMIT + 1; i++) {
            huge.add(cr(i + 1, "SP" + i, "1", "1"));
        }
        int accepted = svc.submitNewBatch("B-HUGE", huge, CheckRowConsts.PUT_DISTRICT, "tester");
        assertEquals(-1, accepted);
        assertNull(batch("B-HUGE"));
        assertTrue(rows.values().stream().noneMatch(r -> "B-HUGE".equals(r.getBatchNo())));
    }

    // ================================================================ 2) 新建/修改两套校验

    @Test
    public void createAndModifyHaveSeparateRulesAndJudgeSource() {
        // 新建：零数量按"新建/校验第3条·为零"
        svc.submitNewBatch("B-RULES", java.util.Arrays.asList(cr(1, "SP1", "0", "0")),
                CheckRowConsts.PUT_DISTRICT, "tester");
        TOadsCheckRow created = svc.rowDetail("B-RULES", 1);
        assertEquals(CheckRowConsts.STATUS_RETURNED, created.getStatus().intValue());
        assertTrue(created.getReason().contains("新建/校验第3条"));
        assertTrue(created.getReason().contains("为零"));
        assertEquals("新建/校验第3条", created.getJudgeSource());

        // 修改同一行交改版，措辞与编号是另一套："修改/校验第3条·须为正数"
        CheckRowBatchReceipt rc = svc.modifyBatch("B-RULES",
                java.util.Arrays.asList(cr(1, "SP1", "0", "0")), "tester");
        TOadsCheckRow modified = svc.rowDetail("B-RULES", 1);
        assertEquals(CheckRowConsts.STATUS_RETURNED, modified.getStatus().intValue());
        assertTrue(modified.getReason().contains("修改/校验第3条"));
        assertTrue(modified.getReason().contains("须为正数"));
        assertEquals("修改/校验第3条", modified.getJudgeSource());
        assertEquals(1, countFlow("B-RULES", CheckRowConsts.FLOW_MODIFY));
        // 来路与年度以首回收讫版为准
        assertEquals(CheckRowConsts.PUT_DISTRICT, batch("B-RULES").getPutKind());
        assertEquals(thisYear(), rc.getFeeYear().intValue());
    }

    @Test
    public void modifyUnknownBatchThrows_modifyUnknownRowNamedNotLanded() {
        assertThrows(CheckRowRuleException.class,
                () -> svc.modifyBatch("B-NEVER", java.util.Arrays.asList(cr(1, "SP1", "1", "1")), "tester"));

        svc.submitNewBatch("B-MOD", java.util.Arrays.asList(cr(1, "SP1", "1", "1")),
                CheckRowConsts.PUT_DISTRICT, "tester");
        CheckRowBatchReceipt rc = svc.modifyBatch("B-MOD",
                java.util.Arrays.asList(cr(7, "SP9", "1", "1")), "tester"); // 第7行不存在
        assertFalse(rc.getMismatches().isEmpty());
        assertTrue(rc.getMismatches().get(0).getDetail().contains("修改/校验第6条"));
        // 原行保留未动，仍只 1 行
        assertEquals(1, svc.receipt("B-MOD", "tester").getLandedRows());
    }

    // ================================================================ 3) 按原报行次定位失败行

    @Test
    public void failedRowLocatedByReportedRowNo_notLoopIndex() {
        svc.submitNewBatch("B-LOC", java.util.Arrays.asList(
                cr(10, "A", "1", "1"),
                cr(20, "B", null, "1"),   // 缺应更换：退
                cr(30, "C", "1", "1")),
                CheckRowConsts.PUT_DISTRICT, "tester");
        List<TOadsCheckRow> errors = svc.listErrors("B-LOC");
        assertEquals(1, errors.size());
        assertEquals(20, errors.get(0).getRowNo().intValue()); // 不是循环下标 2
        assertEquals("B", errors.get(0).getItemCode());
        // 跳过显式行号、报文乱序时仍按原报行次
        CheckRowBatchReceipt rc = svc.receipt("B-LOC", "tester");
        assertEquals(20, rc.getRows().get(1).getRowNo());
    }

    // ================================================================ 4) 状态口径四处一致

    @Test
    public void sameStatusPredicate_receiptErrorsDetailFeeBook() {
        svc.submitNewBatch("B-STAT", java.util.Arrays.asList(
                cr(1, "A", "1", "1"),
                cr(2, "B", "1", "1"),
                cr(3, "C", "-2", "1")),
                CheckRowConsts.PUT_DISTRICT, "tester");
        CheckRowBatchReceipt rc = svc.receipt("B-STAT", "tester");
        List<TOadsCheckRow> errors = svc.listErrors("B-STAT");
        CheckRowFeeBook fb = svc.feeBook(null, null, "tester");

        // 回执退回数 == 退回明细条数
        assertEquals(rc.getReturnedRows(), errors.size());
        // 收费册里这批的退回行只数与上两处相同
        int fbReturned = 0;
        int fbAccepted = 0;
        for (CheckRowFeeBook.YearBucket y : fb.getYears()) {
            for (CheckRowFeeBook.RowLine l : y.getLines()) {
                if ("B-STAT".equals(l.getBatchNo())) {
                    if (l.isReturned()) {
                        fbReturned++;
                    } else {
                        fbAccepted++;
                    }
                }
            }
        }
        assertEquals(1, fbReturned);
        assertEquals(2, fbAccepted);
        // 详情那行与退回明细是同一份行账
        TOadsCheckRow detail = svc.rowDetail("B-STAT", 3);
        assertEquals(errors.get(0).getId(), detail.getId());
        assertEquals(CheckRowConsts.STATUS_RETURNED, detail.getStatus().intValue());
        // 每个值都在 {1,2} 内，没有第四种状态
        for (CheckRowBatchReceipt.RowItem it : rc.getRows()) {
            assertTrue(it.getStatus() == 1 || it.getStatus() == 2);
        }
    }

    // ================================================================ 5) 同批交两回幂等

    @Test
    public void sameBatchTwice_isIdempotentNoDoubling() {
        List<TOadsCheckRow> payload = java.util.Arrays.asList(
                cr(1, "A", "1", "1"), cr(2, "B", "1", "1"));
        int first = svc.submitNewBatch("B-DUP", payload, CheckRowConsts.PUT_DISTRICT, "tester");
        int second = svc.submitNewBatch("B-DUP", payload, CheckRowConsts.PUT_CITY, "tester");
        assertEquals(first, second);
        assertEquals(2, second);
        // 行没有第二份
        assertEquals(2, rowMapper.listByBatch("B-DUP").size());
        assertEquals(1, batch("B-DUP").getDupCount().intValue());
        assertEquals(CheckRowConsts.PUT_DISTRICT, batch("B-DUP").getPutKind()); // 来路只记头一回
        assertEquals(1, countFlow("B-DUP", CheckRowConsts.FLOW_RESEND));
        CheckRowBatchReceipt rc = svc.receipt("B-DUP", "tester");
        assertTrue(rc.isDuplicate());
        assertEquals(2, rc.getLandedRows());
        assertEquals(2, rc.getAcceptedRows());
    }

    // ================================================================ 6) 收费年度冻结，两边一致

    @Test
    public void feeYearFrozenOnce_wholeBookAndRowOutcomeAgree() {
        svc.submitNewBatch("B-YEAR", java.util.Arrays.asList(
                cr(1, "A", "3", "3"),
                cr(2, "B", "0", "0")),
                CheckRowConsts.PUT_CITY, "tester");
        TOadsCheckBatch b = batch("B-YEAR");
        int year = b.getFeeYear();
        assertEquals(thisYear(), year);
        // 每行冗余同一个年度
        for (TOadsCheckRow r : rowMapper.listByBatch("B-YEAR")) {
            assertEquals(year, r.getFeeYear().intValue());
        }
        // 收费册归集到同一个年度，不会一边本年一边上年
        CheckRowFeeBook fbAll = svc.feeBook(null, "B-YEAR", "tester");
        assertEquals(1, fbAll.getYears().size());
        assertEquals(year, fbAll.getYears().get(0).getFeeYear());
        CheckRowFeeBook fbThisYear = svc.feeBook(year, null, "tester");
        assertTrue(fbThisYear.getYears().stream().anyMatch(y -> y.getFeeYear() == year));
        CheckRowFeeBook fbOther = svc.feeBook(year - 1, null, "tester");
        assertTrue(fbOther.getYears().stream().noneMatch(y ->
                y.getLines().stream().anyMatch(l -> "B-YEAR".equals(l.getBatchNo()))));
        // 年度口径说法落账
        assertTrue(b.getFeeYearBasis().contains("首回收讫"));
    }

    // ================================================================ 7) 批内同码合并

    @Test
    public void duplicateCodeInBatchMergedIntoOneRow() {
        svc.submitNewBatch("B-MERGE", java.util.Arrays.asList(
                cr(1, "A", "3", "2"),
                cr(2, "A", "4", "1")),
                CheckRowConsts.PUT_DISTRICT, "tester");
        List<TOadsCheckRow> landed = rowMapper.listByBatch("B-MERGE");
        assertEquals(1, landed.size());
        TOadsCheckRow merged = landed.get(0);
        assertEquals(1, merged.getRowNo().intValue());
        assertEquals(new BigDecimal("7"), merged.getQty());
        assertEquals(new BigDecimal("3"), merged.getChkNum());
        assertEquals("2", merged.getMergedRowNos());
        assertEquals(CheckRowConsts.STATUS_ACCEPTED, merged.getStatus().intValue());
        TOadsCheckBatch b = batch("B-MERGE");
        assertEquals(2, b.getCountedRows().intValue());
        assertEquals(1, b.getLandedRows().intValue());
        assertEquals(1, b.getMergedRows().intValue());
        assertEquals(1, countFlow("B-MERGE", CheckRowConsts.FLOW_MERGE));
    }

    @Test
    public void duplicateCodeReportedOutOfOrder_leadIsSmallestRowNo() {
        // 同码两条且乱序交来：主行次仍取最小原报行次，失败定位不会跑偏
        svc.submitNewBatch("B-MERGE2", java.util.Arrays.asList(
                cr(8, "A", "3", "2"),
                cr(3, "A", null, "1")),
                CheckRowConsts.PUT_DISTRICT, "tester");
        List<TOadsCheckRow> landed = rowMapper.listByBatch("B-MERGE2");
        assertEquals(1, landed.size());
        assertEquals(3, landed.get(0).getRowNo().intValue());
        assertEquals("8", landed.get(0).getMergedRowNos());
        // 第二条数量缺填，合并后应更换仍到（3），核验合格到（1）→ 可收下；组同进同退只落一条
        assertEquals(CheckRowConsts.STATUS_ACCEPTED, landed.get(0).getStatus().intValue());
        assertEquals(new BigDecimal("3"), landed.get(0).getQty());
    }

    // ================================================================ 9) 一行错不连坐

    @Test
    public void oneBadRowDoesNotBlockTheBatch() {
        int accepted = svc.submitNewBatch("B-PART", java.util.Arrays.asList(
                cr(1, "A", "1", "1"),
                cr(2, "B", null, null),   // 缺数量：只退这一行
                cr(3, "C", "2", "1"),
                cr(4, "D", "5", "9")),    // 核验高于应更换：只退这一行
                CheckRowConsts.PUT_DISTRICT, "tester");
        assertEquals(2, accepted);
        CheckRowBatchReceipt rc = svc.receipt("B-PART", "tester");
        assertEquals(4, rc.getLandedRows());
        assertEquals(2, rc.getReturnedRows());
        assertEquals(2, rc.getAcceptedRows());
        assertTrue(rc.isBalanced());
        List<TOadsCheckRow> errors = svc.listErrors("B-PART");
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).getReason().contains("新建/校验第2条"));
        assertTrue(errors.get(1).getReason().contains("新建/校验第5条"));
    }

    // ================================================================ 11) 空批兜底

    @Test
    public void emptyBatchReturnsZeroNoThrowNoBatch() {
        assertEquals(0, svc.submitNewBatch("B-EMPTY", new ArrayList<TOadsCheckRow>(),
                CheckRowConsts.PUT_DISTRICT, "tester"));
        assertNull(batch("B-EMPTY"));
        assertEquals(0, svc.submitNewBatch("B-EMPTY2", null, CheckRowConsts.PUT_DISTRICT, "tester"));
        // 旧入口空批同样兜底（旧实现 rows.get(0) 直接抛错）
        assertEquals(0, svc.submitBatch(null, new ArrayList<TOadsCheckRow>()));
    }

    @Test
    public void missingBatchNoThrows() {
        assertThrows(CheckRowRuleException.class,
                () -> svc.submitNewBatch("  ", java.util.Arrays.asList(cr(1, "A", "1", "1")),
                        CheckRowConsts.PUT_DISTRICT, "tester"));
    }

    // ================================================================ 修改幂等 + 退回行改对后转收下

    @Test
    public void modifyFixesRowAndIsIdempotent_ledgerRecounts() {
        svc.submitNewBatch("B-FIX", java.util.Arrays.asList(
                cr(1, "A", "1", "1"),
                cr(2, "B", "0", "0")),
                CheckRowConsts.PUT_DISTRICT, "tester");
        assertEquals(1, batch("B-FIX").getAcceptedRows().intValue());
        assertEquals(1, batch("B-FIX").getReturnedRows().intValue());

        // 改对第2行
        svc.modifyBatch("B-FIX", java.util.Arrays.asList(cr(2, "B", "2", "2")), "tester");
        assertEquals(2, batch("B-FIX").getAcceptedRows().intValue());
        assertEquals(0, batch("B-FIX").getReturnedRows().intValue());
        // 再交一遍同样的修改版：状态不漂移、行不增加
        svc.modifyBatch("B-FIX", java.util.Arrays.asList(cr(2, "B", "2", "2")), "tester");
        assertEquals(2, rowMapper.listByBatch("B-FIX").size());
        assertEquals(2, batch("B-FIX").getAcceptedRows().intValue());
        // dup_count 不因修改而变（版本身份以首回为准）
        assertEquals(0, batch("B-FIX").getDupCount().intValue());
        assertEquals("修改/校验第1-5条全过", svc.rowDetail("B-FIX", 2).getJudgeSource());
        assertEquals(CheckRowConsts.STATUS_ACCEPTED, svc.rowDetail("B-FIX", 2).getStatus().intValue());
    }

    // ================================================================ 旧入口仍收什么回什么（形状）

    @Test
    public void legacySubmitBatchDelegatesToNewFlow() {
        int accepted = svc.submitBatch("B-LEGACY", java.util.Arrays.asList(
                cr(1, "A", "1", "1"), cr(2, "B", "1", "1")));
        assertEquals(2, accepted);
        assertEquals(CheckRowConsts.PUT_DISTRICT, batch("B-LEGACY").getPutKind());
        // 旧入口第二次交同批：同样幂等（旧实现是双倍插入）
        int again = svc.submitBatch("B-LEGACY", java.util.Arrays.asList(
                cr(1, "A", "1", "1"), cr(2, "B", "1", "1")));
        assertEquals(2, again);
        assertEquals(2, rowMapper.listByBatch("B-LEGACY").size());
    }
}
