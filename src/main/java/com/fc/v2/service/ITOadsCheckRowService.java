package com.fc.v2.service;

import java.util.List;

import com.fc.v2.model.auto.TOadsCheckBatch;
import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.model.custom.CheckRowBatchReceipt;
import com.fc.v2.model.custom.CheckRowFeeBook;
import com.fc.v2.model.custom.SpotBatchBringOutcome;
import com.fc.v2.model.custom.SpotForm;

/**
 * 集中更换核验行 Service（batch-process 形状：整批提交，无单条增删改）。
 *
 * <p>区局核收这一档的唯一裁决处。两个入口各写各的校验规矩，互不共用一份：
 * <ul>
 *   <li>{@link #submitNewBatch} 新建入口：核验行首次交来，走"新建/校验第N条"那一套；</li>
 *   <li>{@link #modifyBatch} 修改入口：对已收讫的批交修改版，走"修改/校验第N条"那一套，
 *       只改行内容；批次身份、来路、收费年度以首回收讫版为准，不动。</li>
 * </ul>
 *
 * <p>同一批次码并着送两回（不管走区级报上来还是市局自核）幂等：不另立批、行不重复入，
 * dup_count 加一、落 RESEND 流水，回执照原批行账重出。一行有错只退那一行，按原报行次定位，
 * 不拦同批别行；批内同编码明细先合并再判，不会两条都入库。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITOadsCheckRowService {

    /** 按主键回查单行 */
    TOadsCheckRow selectTOadsCheckRowById(Long id);

    /** 批账清单（feeYear 传 null 给全部年度；按收讫时间倒序）。 */
    List<TOadsCheckBatch> listBatches(Integer feeYear);

    /**
     * 新建入口整批核收。空批兜底返回 0（不报错退出、不入库）；
     * 物理行越防呆硬界返回 -1 且整批不入库；同一批次码重交幂等，返回收下行数、行不重复入。
     *
     * @return 收下行数；空批 0；越界 -1
     */
    int submitNewBatch(String batchNo, List<TOadsCheckRow> rows, String putKind, String operator);

    /**
     * 修改入口整批改版：逐行按原报行次覆盖已收讫行并重裁（"修改/校验第N条"），
     * 未提交的行保留；来路、收费年度、批次身份、dup_count 冻结不动；落 MODIFY 流水。
     * 批从未新建收讫的抛口径违例（新建请走新建入口，不借修改塞新批）。
     */
    CheckRowBatchReceipt modifyBatch(String batchNo, List<TOadsCheckRow> rows, String operator);

    /** 出回执：回库逐行数四个数，逐行带判定来源与收费年度；同次落一笔 RECEIPT 流水。 */
    CheckRowBatchReceipt receipt(String batchNo, String operator);

    /** 该批退回行明细（按原报行次升序；与详情/收费册同一状态谓词） */
    List<TOadsCheckRow> listErrors(String batchNo);

    /** 单行详情（结论/逐句原因/判定来源/收费年度；与列表同一份行账同一谓词）。 */
    TOadsCheckRow rowDetail(String batchNo, int rowNo);

    /**
     * 向收费册落去向的年度归集：feeYear 传 null 给全部年度、batchNo 传 null 给全部批。
     * 年度读行上冻结的 fee_year（整册核收与逐行落去向同一个值），退回行不落收费册。
     */
    CheckRowFeeBook feeBook(Integer feeYear, String batchNo, String operator);

    /**
     * @deprecated 旧整批提交入口，保留收什么回什么的形状，内部已改走新建入口唯一裁决处
     *     （区级报上来来路）。新代码用 {@link #submitNewBatch}。
     */
    @Deprecated
    int submitBatch(String batchNo, List<TOadsCheckRow> rows);

    /**
     * 建行第二通道：集中更换业务在核验完成后，把核验行带出成名册行。
     * 逐行交点位名册唯一裁决处按 BATCH 通道走同一套五个检查点与同一套数量逻辑；
     * 某行不过只带回该检查点自己的提示语，不合并成"数据有误"，也不拖累同批别行。
     */
    SpotBatchBringOutcome bringOutSpots(String batchNo, List<SpotForm> forms, String operator);
}
