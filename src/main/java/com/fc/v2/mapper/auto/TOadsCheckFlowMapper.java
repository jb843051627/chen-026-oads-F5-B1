package com.fc.v2.mapper.auto;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TOadsCheckFlow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 集中更换核验流水数据层（append-only：只 insert）。
 *
 * @author fuce
 * @date 2026-10-06
 */
public interface TOadsCheckFlowMapper extends BaseMapper<TOadsCheckFlow> {

    /** 这一批全部流水，按落账时刻、主键排。 */
    @Select("SELECT * FROM t_oads_check_flow WHERE batch_no = #{batchNo} ORDER BY acted_at, id")
    List<TOadsCheckFlow> listByBatch(@Param("batchNo") String batchNo);
}
