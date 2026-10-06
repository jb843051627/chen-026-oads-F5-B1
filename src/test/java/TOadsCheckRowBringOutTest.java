import com.fc.v2.common.exception.spot.SpotRuleException;
import com.fc.v2.mapper.auto.TOadsCheckRowMapper;
import com.fc.v2.model.custom.SpotBatchBringOutcome;
import com.fc.v2.model.custom.SpotBuildResult;
import com.fc.v2.model.custom.SpotForm;
import com.fc.v2.service.ITOadsSpotRegisterService;
import com.fc.v2.service.impl.TOadsCheckRowServiceImpl;
import com.fc.v2.service.spot.SpotFlowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集中更换带出（第二建行通道）：bringOutSpots 逐行交名册裁决处，同一套五检查点与数量逻辑；
 * 某行不过只带回该行检查点自己的提示语，不并句、不拖累别行。
 * 没有行数上限这回事——501 行也照带；空批兜底；批次季度在批次级定一次，逐行不落两个年度。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TOadsCheckRowBringOutTest {

    @Mock private TOadsCheckRowMapper checkRowMapper;
    @Mock private ITOadsSpotRegisterService registerService;

    private TOadsCheckRowServiceImpl svc;

    @BeforeEach
    public void wire() {
        svc = new TOadsCheckRowServiceImpl();
        ReflectionTestUtils.setField(svc, "oadsCheckRowMapper", checkRowMapper);
        ReflectionTestUtils.setField(svc, "spotRegisterService", registerService);
    }

    private SpotForm form(String no, String need, String got) {
        SpotForm f = new SpotForm();
        f.setSpotNo(no);
        f.setAreaNo("DU00");
        f.setNeedText(need);
        f.setGotText(got);
        return f;
    }

    @Test
    public void bringOutDelegatesSameJudgeAndKeepsPerRowMessages() {
        SpotBuildResult ok1 = SpotBuildResult.created();
        ok1.setSpotNo("B1");
        ok1.setLackNum(18);
        when(registerService.build(any(), eq(SpotFlowStep.BUILD_BATCH), eq("核验岗")))
                .thenReturn(ok1)
                .thenThrow(new SpotRuleException("检查点2空间挂靠：点位 B2 的坐标在辖段 DU00 的围栏之外，不予收录"))
                .thenThrow(new SpotRuleException("检查点3数量：核定应设处数缺填，本行进不了库"));

        List<SpotForm> forms = new ArrayList<SpotForm>(Arrays.asList(
                form("B1", "30", "12"),
                form("B2", "5", "1"),
                form("B3", null, "1")));

        SpotBatchBringOutcome o = svc.bringOutSpots("BN-1", forms, "核验岗");
        assertEquals("BN-1", o.getBatchNo());
        assertEquals(3, o.getTotal());
        assertEquals(1, o.getSuccessCount());
        assertEquals(2, o.getFailureCount());

        assertTrue(o.getItems().get(0).isSuccess());
        assertEquals(18, o.getItems().get(0).getResult().getLackNum().intValue());
        assertFalse(o.getItems().get(1).isSuccess());
        assertTrue(o.getItems().get(1).getError().startsWith("检查点2空间挂靠"));
        assertFalse(o.getItems().get(1).getError().contains("数据有误"));
        assertFalse(o.getItems().get(2).isSuccess());
        assertTrue(o.getItems().get(2).getError().contains("核定应设处数缺填"));
    }

    @Test
    public void emptyBatchBringsNothing() {
        SpotBatchBringOutcome o = svc.bringOutSpots("BN-2", new ArrayList<SpotForm>(), "岗");
        assertEquals(0, o.getTotal());
        assertTrue(o.getItems().isEmpty());
    }

    @Test
    public void noRowLimitFiveHundredAndOneRowsAllBrought() {
        // 没有行数上限这回事：旧实现 501 行整批抛错拦回，现在来多少带多少。
        when(registerService.build(any(), eq(SpotFlowStep.BUILD_BATCH), eq("岗")))
                .thenAnswer(inv -> {
                    SpotBuildResult r = SpotBuildResult.created();
                    r.setSpotNo(inv.getArgument(0, SpotForm.class).getSpotNo());
                    return r;
                });
        List<SpotForm> big = new ArrayList<SpotForm>();
        for (int i = 0; i < 501; i++) {
            big.add(form("X" + i, "1", "0"));
        }
        SpotBatchBringOutcome o = svc.bringOutSpots("BN-3", big, "岗");
        assertEquals(501, o.getTotal());
        assertEquals(501, o.getSuccessCount());
        assertEquals(0, o.getFailureCount());
        verify(registerService, times(501)).build(any(), eq(SpotFlowStep.BUILD_BATCH), eq("岗"));
    }

    @Test
    public void batchQuarterResolvedOnceAndStampedOnEveryRow() {
        // 整册核收认定当季：报文没带季度码的行统一补同一季，逐行落去向不会一年之差。
        when(registerService.currentQuarter()).thenReturn("2026Q4");
        when(registerService.build(any(), eq(SpotFlowStep.BUILD_BATCH), eq("岗")))
                .thenAnswer(inv -> {
                    SpotForm got = inv.getArgument(0);
                    assertEquals("2026Q4", got.getQuarterCode(), "批次季度只定一次，每行一致");
                    SpotBuildResult r = SpotBuildResult.created();
                    r.setSpotNo(got.getSpotNo());
                    return r;
                });
        List<SpotForm> forms = Arrays.asList(form("Q1", "3", "1"), form("Q2", "2", "2"));
        SpotBatchBringOutcome o = svc.bringOutSpots("BN-4", forms, "岗");
        assertEquals(2, o.getSuccessCount());
        verify(registerService, times(1)).currentQuarter();
    }

    @Test
    public void reportedQuarterKeptAndSharedToBlankRows() {
        // 报文头一行带了季度（如 2025Q4 跨季补送）：全批以这一版为准，空季行补同季，不替它改年度。
        when(registerService.build(any(), eq(SpotFlowStep.BUILD_BATCH), eq("岗")))
                .thenAnswer(inv -> {
                    SpotForm got = inv.getArgument(0);
                    assertEquals("2025Q4", got.getQuarterCode());
                    return SpotBuildResult.created();
                });
        SpotForm first = form("Y1", "3", "1");
        first.setQuarterCode("2025Q4");
        SpotForm second = form("Y2", "3", "1");
        svc.bringOutSpots("BN-5", Arrays.asList(first, second), "岗");
        assertEquals("2025Q4", second.getQuarterCode());
        verify(registerService, times(0)).currentQuarter();
    }
}
