import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.common.exception.spot.SpotRuleException;
import com.fc.v2.mapper.auto.TOadsCheckRowMapper;
import com.fc.v2.model.auto.TOadsCheckRow;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集中更换核验行整批提交口径核对（mapper Mock，内存账台，不落真库）：
 * 空批兜底不报错、没有行数上限、行号照物理行次（定位失败行不错位）、一行错不连坐、
 * 校验只有一把尺且毛病带核验点标识、批内同码重复明细合并不双入、
 * 同批交两回幂等（数量不多一倍）、交回数与回查同口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TOadsCheckRowSubmitBatchTest {

    @Mock private TOadsCheckRowMapper checkRowMapper;

    private TOadsCheckRowServiceImpl svc;

    private final Map<Long, TOadsCheckRow> table = new LinkedHashMap<Long, TOadsCheckRow>();
    private long seq = 0;

    /** 取出 wrapper 里的全部参数值（MP 3.4：paramNameValuePairs）。 */
    private static List<Object> params(Wrapper<?> w) {
        AbstractWrapper<?, ?, ?> aw = (AbstractWrapper<?, ?, ?>) w;
        aw.getSqlSegment();
        return new ArrayList<Object>(aw.getParamNameValuePairs().values());
    }

    private static String stringParam(Wrapper<?> w) {
        for (Object v : params(w)) {
            if (v instanceof String) {
                return (String) v;
            }
        }
        return null;
    }

    @BeforeEach
    public void wire() {
        svc = new TOadsCheckRowServiceImpl();
        ReflectionTestUtils.setField(svc, "oadsCheckRowMapper", checkRowMapper);

        when(checkRowMapper.insert(any(TOadsCheckRow.class))).thenAnswer(inv -> {
            TOadsCheckRow r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(++seq);
            }
            table.put(r.getId(), r);
            return 1;
        });
        when(checkRowMapper.selectList(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            String batchNo = stringParam(w);
            boolean wantsFail = w.getSqlSegment().contains("status");
            List<TOadsCheckRow> out = new ArrayList<TOadsCheckRow>();
            for (TOadsCheckRow r : table.values()) {
                if (batchNo != null && !batchNo.equals(r.getBatchNo())) {
                    continue;
                }
                if (r.getDelFlag() != null && r.getDelFlag() != 0) {
                    continue;
                }
                if (wantsFail && (r.getStatus() == null || r.getStatus() != 2)) {
                    continue;
                }
                out.add(r);
            }
            Collections.sort(out, new Comparator<TOadsCheckRow>() {
                @Override
                public int compare(TOadsCheckRow a, TOadsCheckRow b) {
                    int an = a.getRowNo() == null ? 0 : a.getRowNo();
                    int bn = b.getRowNo() == null ? 0 : b.getRowNo();
                    return Integer.compare(an, bn);
                }
            });
            return out;
        });
    }

    private TOadsCheckRow row(String code, String qty, String chk) {
        TOadsCheckRow r = new TOadsCheckRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        r.setChkNum(chk == null ? null : new BigDecimal(chk));
        return r;
    }

    @Test
    public void emptyAndNullBatchReturnZeroWithoutTouchingDb() {
        assertEquals(0, svc.submitBatch("B0", new ArrayList<TOadsCheckRow>()));
        assertEquals(0, svc.submitBatch("B0", null));
        assertTrue(table.isEmpty());
        verify(checkRowMapper, never()).insert(any());
    }

    @Test
    public void missingBatchNoRejected() {
        SpotRuleException e = assertThrows(SpotRuleException.class,
                () -> svc.submitBatch("  ", java.util.Collections.singletonList(row("SP0001", "1", "1"))));
        assertTrue(e.getMessage().contains("批次码"));
    }

    @Test
    public void oneBadRowDoesNotBlockOthersAndKeepsPhysicalRowNo() {
        List<TOadsCheckRow> rows = new ArrayList<TOadsCheckRow>();
        rows.add(row("SP0001", "10", "8"));        // 第1行 好
        rows.add(row("SP0002", null, "8"));        // 第2行 应更换缺填 → 退
        rows.add(row("SP0003", "5", "5"));         // 第3行 好
        int accepted = svc.submitBatch("B1", rows);
        assertEquals(2, accepted, "一行错只退那行，别行照常收下");

        List<TOadsCheckRow> errors = svc.listErrors("B1");
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).getRowNo().intValue(), "按物理行号定位到第2行，不错位");
        assertEquals("SP0002", errors.get(0).getItemCode());
        assertNotNull(errors.get(0).getRemark());
        assertTrue(errors.get(0).getRemark().startsWith("核验点2数量"),
                "毛病带核验点标识，回查指得出依哪一套标准：" + errors.get(0).getRemark());
    }

    @Test
    public void reasonsAccumulateAndRejectReasonsSameAsSubmit() {
        // 编码缺 + qty 负 + chk>qty：逐句攒齐，回查与提交同一把尺。
        TOadsCheckRow r = row("  ", "-3", "9");
        int accepted = svc.submitBatch("B2", java.util.Collections.singletonList(r));
        assertEquals(0, accepted);
        TOadsCheckRow err = svc.listErrors("B2").get(0);
        assertEquals(1, err.getRowNo().intValue());
        assertTrue(err.getRemark().contains("核验点1身份"));
        assertTrue(err.getRemark().contains("核验点2数量"));
        assertTrue(err.getRemark().contains("核验点3数量"));
        assertFalse(err.getRemark().contains("数据有误"));
    }

    @Test
    public void duplicateRowsWithinBatchMergedNotInsertedTwice() {
        List<TOadsCheckRow> rows = new ArrayList<TOadsCheckRow>();
        rows.add(row("SP0010", "10", "4"));   // 第1行
        rows.add(row("SP0010", "5", "1"));    // 第2行 同点位重复
        rows.add(row("SP0011", "2", "2"));    // 第3行
        int accepted = svc.submitBatch("B3", rows);

        assertEquals(2, table.size(), "批内重复明细合并，不是三行都入");
        assertEquals(2, accepted);
        TOadsCheckRow merged = table.values().stream()
                .filter(x -> "SP0010".equals(x.getItemCode())).findFirst().orElse(null);
        assertNotNull(merged);
        assertEquals(1, merged.getRowNo().intValue(), "合并行保留首次行号");
        assertEquals(new BigDecimal("15"), merged.getQty(), "应更换处数相加");
        assertEquals(new BigDecimal("5"), merged.getChkNum(), "核验合格处数相加");
        assertEquals(1, merged.getStatus().intValue());
        assertTrue(merged.getRemark().contains("已并为一条"));
    }

    @Test
    public void resubmitSameBatchIsIdempotentCountDoesNotDouble() {
        List<TOadsCheckRow> rows = new ArrayList<TOadsCheckRow>();
        rows.add(row("SP0020", "7", "7"));
        rows.add(row("SP0021", "3", "0"));
        int first = svc.submitBatch("B4", rows);
        int afterFirst = table.size();
        assertEquals(2, first);

        // 同一批并着送两回：第二回一条不另插，交回数还是 2，不会凭空多一倍。
        int second = svc.submitBatch("B4", new ArrayList<TOadsCheckRow>(rows));
        assertEquals(first, second);
        assertEquals(afterFirst, table.size(), "第二回没有新增任何行");
    }

    @Test
    public void checkedAboveReplaceableIsRejected() {
        int accepted = svc.submitBatch("B5",
                java.util.Collections.singletonList(row("SP0030", "5", "6")));
        assertEquals(0, accepted);
        assertTrue(svc.listErrors("B5").get(0).getRemark().contains("核验合格处数(6)高于本行应更换处数(5)"));
    }

    @Test
    public void noRowLimitLargeBatchAllAccepted() {
        // 没有行数上限这回事：600 行也照收（旧实现根本没有上限判断，带出通道 500 的旧上限也已取消）。
        List<TOadsCheckRow> big = new ArrayList<TOadsCheckRow>();
        for (int i = 0; i < 600; i++) {
            big.add(row(String.format("SP%06d", i + 1), "2", "1"));
        }
        int accepted = svc.submitBatch("B6", big);
        assertEquals(600, accepted);
        assertEquals(600, table.size());
        assertEquals(600, table.get(600L).getRowNo().intValue(), "第600行物理行号不错位");
        assertTrue(svc.listErrors("B6").isEmpty());
    }
}
