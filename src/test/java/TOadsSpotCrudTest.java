import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TOadsAreaBookMapper;
import com.fc.v2.mapper.auto.TOadsSpotMapper;
import com.fc.v2.model.auto.TOadsAreaBook;
import com.fc.v2.model.auto.TOadsSpot;
import com.fc.v2.service.impl.TOadsSpotServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 点位底册旧通用入口口径核对：新建与修改共用同一套校验（不再各写各的）、
 * 待补只有 need-got 一套算法、列表与详情同一在册口径（只认 del_flag=0，不另加 status=0）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TOadsSpotCrudTest {

    @Mock private TOadsSpotMapper spotMapper;
    @Mock private TOadsAreaBookMapper areaBookMapper;

    private TOadsSpotServiceImpl svc;

    private final Map<Long, TOadsSpot> spots = new LinkedHashMap<Long, TOadsSpot>();
    private final Map<Integer, TOadsAreaBook> areas = new LinkedHashMap<Integer, TOadsAreaBook>();
    private String lastListSql = "";

    private static List<Object> paramsOf(Wrapper<?> w) {
        AbstractWrapper<?, ?, ?> aw = (AbstractWrapper<?, ?, ?>) w;
        aw.getSqlSegment();
        return new ArrayList<Object>(aw.getParamNameValuePairs().values());
    }

    private TOadsAreaBook area(int id, String no, int status) {
        TOadsAreaBook a = new TOadsAreaBook();
        a.setId((long) id);
        a.setAreaNo(no);
        a.setAreaName(no + "名");
        a.setStatus(status);
        a.setDelFlag(0);
        areas.put(id, a);
        return a;
    }

    private TOadsSpot existing(long id, String no) {
        TOadsSpot s = new TOadsSpot();
        s.setId(id);
        s.setSpotNo(no);
        s.setAreaId(0);
        s.setAreaNo("DU00");
        s.setNeedNum(30);
        s.setGotNum(12);
        s.setStatus(1);          // 已核齐：列表口径不该把它藏掉
        s.setDelFlag(0);
        spots.put(id, s);
        return s;
    }

    @BeforeEach
    public void wire() {
        area(0, "DU00", 0);
        area(1, "DU01", 1);     // 已注销

        svc = new TOadsSpotServiceImpl();
        ReflectionTestUtils.setField(svc, "baseMapper", spotMapper);
        ReflectionTestUtils.setField(svc, "oadsAreaBookMapper", areaBookMapper);

        when(spotMapper.selectById(any())).thenAnswer(i -> spots.get(i.getArgument(0)));
        when(spotMapper.insert(any(TOadsSpot.class))).thenAnswer(i -> {
            TOadsSpot s = i.getArgument(0);
            long id = spots.size() + 100L;
            s.setId(id);
            spots.put(id, s);
            return 1;
        });
        // 同码计数：按 wrapper 里的 spot_no / id 参数判重，del_flag 口径内置。
        when(spotMapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            List<Object> ps = paramsOf(w);
            String code = null;
            Long excludeId = null;
            for (Object v : ps) {
                if (v instanceof String) {
                    code = (String) v;
                } else if (v instanceof Long) {
                    excludeId = (Long) v;
                }
            }
            int n = 0;
            for (TOadsSpot s : spots.values()) {
                if (s.getDelFlag() != null && s.getDelFlag() != 0) {
                    continue;
                }
                if (code != null && code.equals(s.getSpotNo())
                        && (excludeId == null || !excludeId.equals(s.getId()))) {
                    n++;
                }
            }
            return n;
        });
        when(areaBookMapper.selectOne(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(0);
            w.getSqlSegment();
            // SQL 段为 id=? AND del_flag=?，参数名 MPGENVAL1/2 按序对应；排序后第一个即段册主键。
            List<String> keys = new ArrayList<String>(w.getParamNameValuePairs().keySet());
            java.util.Collections.sort(keys);
            Object first = keys.isEmpty() ? null : w.getParamNameValuePairs().get(keys.get(0));
            if (first instanceof Number) {
                return areas.get(((Number) first).intValue());
            }
            return null;
        });
        when(spotMapper.selectOne(any())).thenAnswer(inv -> {
            // selectTOadsSpotById：wrapper 带 id 与 del_flag=0
            for (Object v : paramsOf(inv.getArgument(0))) {
                if (v instanceof Long) {
                    TOadsSpot s = spots.get(v);
                    return s != null && (s.getDelFlag() == null || s.getDelFlag() == 0) ? s : null;
                }
            }
            return null;
        });
        when(spotMapper.update(any(), any())).thenReturn(1);
        when(spotMapper.selectList(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            lastListSql = w.getSqlSegment() == null ? "" : w.getSqlSegment();
            return new ArrayList<TOadsSpot>(spots.values());
        });
    }

    private TOadsSpot draft(String no, Integer areaId, Integer need, Integer got) {
        TOadsSpot r = new TOadsSpot();
        r.setSpotNo(no);
        r.setAreaId(areaId);
        r.setNeedNum(need);
        r.setGotNum(got);
        return r;
    }

    @Test
    public void createRequiresCodeAreaAndSoundQuantities() {
        assertEquals(0, svc.insertTOadsSpot(draft("  ", 0, 3, 1)), "编码缺填不收");
        assertEquals(0, svc.insertTOadsSpot(draft("SP-NEW", null, 3, 1)), "无辖段不收");
        assertEquals(0, svc.insertTOadsSpot(draft("SP-NEW", 99, 3, 1)), "段册查无不收");
        assertEquals(0, svc.insertTOadsSpot(draft("SP-NEW", 1, 3, 1)), "已注销辖段不收");
        assertEquals(0, svc.insertTOadsSpot(draft("SP-NEW", 0, 2, 3)), "已核验高于核定不收");
        assertEquals(0, svc.insertTOadsSpot(draft("SP-NEW", 0, -1, 0)), "负数不收");

        int before = spots.size();
        assertEquals(1, svc.insertTOadsSpot(draft("SP-NEW", 0, 30, 12)));
        assertEquals(before + 1, spots.size());
        TOadsSpot saved = spots.get((long) (before + 100));
        assertEquals(Integer.valueOf(18), saved.getLackNum(), "待补按 need-got 轧：30-12=18");
        assertEquals("DU00", saved.getAreaNo());
        assertEquals(Integer.valueOf(0), saved.getSafeLevel());
        assertEquals(Integer.valueOf(0), saved.getDelFlag());
    }

    @Test
    public void createRejectsDuplicateCodeSameRuleAsEdit() {
        existing(5L, "SP-DUP");
        assertEquals(0, svc.insertTOadsSpot(draft("SP-DUP", 0, 1, 0)), "新建同码不收");

        // 修改另一条同码行同样不收——两入口同一把尺。
        TOadsSpot other = existing(6L, "SP-OTHER");
        other.setNeedNum(5);
        other.setGotNum(0);
        TOadsSpot edit = new TOadsSpot();
        edit.setId(6L);
        edit.setSpotNo("SP-DUP");
        edit.setAreaId(0);
        edit.setNeedNum(5);
        edit.setGotNum(0);
        assertEquals(0, svc.updateTOadsSpot(edit), "修改改成已存在的码也不收");
    }

    @Test
    public void editValidatesSameRuleAndRecountsLack() {
        existing(7L, "SP-EDIT");
        TOadsSpot edit = new TOadsSpot();
        edit.setId(7L);
        edit.setSpotNo("SP-EDIT");
        edit.setAreaId(0);
        edit.setNeedNum(40);
        edit.setGotNum(35);
        assertEquals(1, svc.updateTOadsSpot(edit));
        assertEquals(Integer.valueOf(5), edit.getLackNum(), "修改后待补同一套算法重算");

        TOadsSpot bad = new TOadsSpot();
        bad.setId(7L);
        bad.setSpotNo("SP-EDIT");
        bad.setAreaId(0);
        bad.setNeedNum(10);
        bad.setGotNum(11);
        assertEquals(0, svc.updateTOadsSpot(bad), "已核验高于核定，修改同样不收");
    }

    @Test
    public void editMissingRowReturnsZero() {
        TOadsSpot edit = new TOadsSpot();
        edit.setId(999L);
        edit.setSpotNo("X");
        assertEquals(0, svc.updateTOadsSpot(edit));
    }

    @Test
    public void listUsesSameNormalScopeAsDetailNoStatusGate() {
        existing(8L, "SP-A");
        List<TOadsSpot> all = svc.selectTOadsSpotList(new QueryWrapper<TOadsSpot>().eq("spot_no", "SP-A"));
        // 已核齐（status=1）的行仍在列：列表不另加 status=0 的门。
        assertTrue(all.stream().anyMatch(s -> "SP-A".equals(s.getSpotNo())));
        assertTrue(lastListSql.contains("del_flag"), "列表只补统一在册口径 del_flag=0");
        assertFalse(lastListSql.contains("status"), "列表不再写死 status=0 把别档行藏掉");

        // 详情同口径：del_flag=0 才取得到。
        assertNotNull(svc.selectTOadsSpotById(8L));
    }
}
