package com.fc.v2.service;

import java.util.List;

import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.model.custom.SpotBatchBringOutcome;
import com.fc.v2.model.custom.SpotForm;

/**
 * 集中更换核验行 Service接口（batch-process 形状：整批提交，无单条增删改）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITOadsCheckRowService {

    /** 按主键回查单行 */
    TOadsCheckRow selectTOadsCheckRowById(Long id);

    /**
     * 整批提交：逐行独立校验（一行填错只退那一行，不连累同批别行），合法行入库（已收下）、
     * 非法行记入失败明细（保留原始物理行号与逐句毛病），返回本次收下行数。
     *
     * <p>口径一次定死，只有这一套：
     * 没有行数上限，来多少收多少；空批次是合法白卷，不查不插不报错，返回 0；
     * 批内同一处点位（item_code 相同）的重复明细先合并成一条（处数相加、保留首次行号）再裁，
     * 不允许两条都入库；同一批次码重复整批提交幂等——第二回起不另插一行，回库把已收下行数照数交回，
     * 数量不会凭空多一倍。
     */
    int submitBatch(String batchNo, List<TOadsCheckRow> rows);

    /** 该批次的失败行明细（按原始物理行号升序，逐句毛病在 remark 里，回查与提交同一判定处） */
    List<TOadsCheckRow> listErrors(String batchNo);

    /**
     * 建行第二通道：集中更换业务在核验完成后，把核验行带出成名册行。
     *
     * <p>这是与建档岗逐条建立等价的去处——不改动 {@link #submitBatch} 本体（收什么回什么维持原状），
     * 另写此处承接。逐行交点位名册唯一裁决处按 BATCH 通道走同一套五个检查点与同一套数量逻辑，
     * 界面上不长第二种样式；某行不过就带回该检查点自己的提示语，不合并成"数据有误"，也不拖累别行。
     */
    SpotBatchBringOutcome bringOutSpots(String batchNo, List<SpotForm> forms, String operator);
}
