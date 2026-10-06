package com.fc.v2.mapper.auto;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TOadsCheckRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 集中更换核验行数据层。
 *
 * <p>列表、详情、收费册归集、回执三数四处用的是同一份清单：{@link #listByBatch} 按批取、
 * 按原报行次排；状态怎么判由 {@code CheckRowConsts} 一个谓词说了算，本接口不掺第二套筛选。
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface TOadsCheckRowMapper extends BaseMapper<TOadsCheckRow> {

    /** 这一批全部行账，按原报行次、主键排；列表/详情/收费册/回执都从这份清单取。 */
    @Select("SELECT * FROM t_oads_check_row WHERE batch_no = #{batchNo} AND del_flag = 0 ORDER BY row_no, id")
    List<TOadsCheckRow> listByBatch(@Param("batchNo") String batchNo);
}
