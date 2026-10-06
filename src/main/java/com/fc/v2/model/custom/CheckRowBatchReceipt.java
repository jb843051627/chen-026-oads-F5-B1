package com.fc.v2.model.custom;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 集中更换核验批回执。
 *
 * <p>四个数全部回库逐行数出来：物理行数（进门点，合并前）、入账行数（批内同码合并后）、
 * 收下行数、退回行数；收下+退回=入账。每行都带：
 * <ul>
 *   <li>原报行次——失败行按它定位（合并组取组内最小原报行次），不按处理循环下标；</li>
 *   <li>判定来源——依"新建/校验第N条"还是"修改/校验第N条"判的，核查指得出哪一段、哪套标准；</li>
 *   <li>收费年度——冻结自批账，与整册核收、逐行落去向、收费册归集同一个值。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CheckRowBatchReceipt {

    /** 逐行条目（一行对应合并后的一处点位） */
    public static class RowItem {
        private int rowNo;
        private String itemCode;
        private java.math.BigDecimal qty;
        private java.math.BigDecimal chkNum;
        /** 行落地情形 1已收下 2已退回（唯一一套状态） */
        private Integer status;
        private String statusName;
        private boolean returned;
        /** 逐句原因原文（退回逐条；收下也可能带合并交代） */
        private String reason;
        /** 判定来源：新建/校验第N条 或 修改/校验第N条 */
        private String judgeSource;
        /** 收费年度（冻结自批账） */
        private Integer feeYear;
        /** 本批内同码并入本行的其余原报行次 */
        private List<Integer> mergedRowNos = new ArrayList<Integer>();

        public int getRowNo() {
            return rowNo;
        }

        public void setRowNo(int rowNo) {
            this.rowNo = rowNo;
        }

        public String getItemCode() {
            return itemCode;
        }

        public void setItemCode(String itemCode) {
            this.itemCode = itemCode;
        }

        public java.math.BigDecimal getQty() {
            return qty;
        }

        public void setQty(java.math.BigDecimal qty) {
            this.qty = qty;
        }

        public java.math.BigDecimal getChkNum() {
            return chkNum;
        }

        public void setChkNum(java.math.BigDecimal chkNum) {
            this.chkNum = chkNum;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public String getStatusName() {
            return statusName;
        }

        public void setStatusName(String statusName) {
            this.statusName = statusName;
        }

        public boolean isReturned() {
            return returned;
        }

        public void setReturned(boolean returned) {
            this.returned = returned;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getJudgeSource() {
            return judgeSource;
        }

        public void setJudgeSource(String judgeSource) {
            this.judgeSource = judgeSource;
        }

        public Integer getFeeYear() {
            return feeYear;
        }

        public void setFeeYear(Integer feeYear) {
            this.feeYear = feeYear;
        }

        public List<Integer> getMergedRowNos() {
            return mergedRowNos;
        }

        public void setMergedRowNos(List<Integer> mergedRowNos) {
            this.mergedRowNos = mergedRowNos;
        }
    }

    /** 对不齐时逐笔点名 */
    public static class Mismatch {
        private int rowNo;
        private String itemCode;
        private String detail;

        public Mismatch() {
        }

        public Mismatch(int rowNo, String itemCode, String detail) {
            this.rowNo = rowNo;
            this.itemCode = itemCode;
            this.detail = detail;
        }

        public int getRowNo() {
            return rowNo;
        }

        public void setRowNo(int rowNo) {
            this.rowNo = rowNo;
        }

        public String getItemCode() {
            return itemCode;
        }

        public void setItemCode(String itemCode) {
            this.itemCode = itemCode;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }

    private String batchNo;
    /** 来路（冻结自头一回） */
    private String putKind;
    /** 本次走的入口 CREATE/MODIFY */
    private String entryKind;
    private Date receivedAt;

    /** 收费年度（整批一个，冻结） */
    private Integer feeYear;
    private String feeYearBasis;

    /** 进门物理行数（合并前） */
    private int countedRows;
    /** 入账行数（合并后） */
    private int landedRows;
    /** 合并掉的明细条数 */
    private int mergedRows;
    private int acceptedRows;
    private int returnedRows;
    private boolean balanced;

    /** 本次是否重复交（第二回起不另立批、行不重复入） */
    private boolean duplicate;
    private int dupCount;

    private List<RowItem> rows = new ArrayList<RowItem>();
    private List<Mismatch> mismatches = new ArrayList<Mismatch>();
    private String note;

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getPutKind() {
        return putKind;
    }

    public void setPutKind(String putKind) {
        this.putKind = putKind;
    }

    public String getEntryKind() {
        return entryKind;
    }

    public void setEntryKind(String entryKind) {
        this.entryKind = entryKind;
    }

    public Date getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Date receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Integer getFeeYear() {
        return feeYear;
    }

    public void setFeeYear(Integer feeYear) {
        this.feeYear = feeYear;
    }

    public String getFeeYearBasis() {
        return feeYearBasis;
    }

    public void setFeeYearBasis(String feeYearBasis) {
        this.feeYearBasis = feeYearBasis;
    }

    public int getCountedRows() {
        return countedRows;
    }

    public void setCountedRows(int countedRows) {
        this.countedRows = countedRows;
    }

    public int getLandedRows() {
        return landedRows;
    }

    public void setLandedRows(int landedRows) {
        this.landedRows = landedRows;
    }

    public int getMergedRows() {
        return mergedRows;
    }

    public void setMergedRows(int mergedRows) {
        this.mergedRows = mergedRows;
    }

    public int getAcceptedRows() {
        return acceptedRows;
    }

    public void setAcceptedRows(int acceptedRows) {
        this.acceptedRows = acceptedRows;
    }

    public int getReturnedRows() {
        return returnedRows;
    }

    public void setReturnedRows(int returnedRows) {
        this.returnedRows = returnedRows;
    }

    public boolean isBalanced() {
        return balanced;
    }

    public void setBalanced(boolean balanced) {
        this.balanced = balanced;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    public int getDupCount() {
        return dupCount;
    }

    public void setDupCount(int dupCount) {
        this.dupCount = dupCount;
    }

    public List<RowItem> getRows() {
        return rows;
    }

    public void setRows(List<RowItem> rows) {
        this.rows = rows;
    }

    public List<Mismatch> getMismatches() {
        return mismatches;
    }

    public void setMismatches(List<Mismatch> mismatches) {
        this.mismatches = mismatches;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
