package com.fc.v2.model.custom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 核验行向收费册落去向的年度归集视图。
 *
 * <p>年度口径只此一份：行的 {@code feeYear} 冻结自批账（按批首回收讫时刻所在年度），
 * 整册核收与逐行落去向读的是同一个值——同一处点位不会在一边算本年度、另一边算上一年度。
 * 状态判定与列表、详情、回执走同一个谓词（{@code CheckRowConsts.isReturned}），
 * 归集数与逐行清单同一次数出，不数第二遍。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CheckRowFeeBook {

    /** 一个年度一摞 */
    public static class YearBucket {
        private int feeYear;
        /** 落进收费册的收下行数（退回行不落收费册） */
        private int acceptedRows;
        private int returnedRows;
        /** 收下行的应更换处数、核验合格处数合计（退回行不计） */
        private BigDecimal acceptedQty = BigDecimal.ZERO;
        private BigDecimal acceptedChkNum = BigDecimal.ZERO;
        private List<RowLine> lines = new ArrayList<RowLine>();

        public int getFeeYear() {
            return feeYear;
        }

        public void setFeeYear(int feeYear) {
            this.feeYear = feeYear;
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

        public BigDecimal getAcceptedQty() {
            return acceptedQty;
        }

        public void setAcceptedQty(BigDecimal acceptedQty) {
            this.acceptedQty = acceptedQty;
        }

        public BigDecimal getAcceptedChkNum() {
            return acceptedChkNum;
        }

        public void setAcceptedChkNum(BigDecimal acceptedChkNum) {
            this.acceptedChkNum = acceptedChkNum;
        }

        public List<RowLine> getLines() {
            return lines;
        }

        public void setLines(List<RowLine> lines) {
            this.lines = lines;
        }
    }

    /** 逐行落账线（列表/详情/收费册同一行字段、同一状态口径） */
    public static class RowLine {
        private String batchNo;
        private int rowNo;
        private String itemCode;
        private BigDecimal qty;
        private BigDecimal chkNum;
        private Integer status;
        private String statusName;
        private boolean returned;
        private String reason;
        private String judgeSource;
        private Integer feeYear;

        public String getBatchNo() {
            return batchNo;
        }

        public void setBatchNo(String batchNo) {
            this.batchNo = batchNo;
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

        public BigDecimal getQty() {
            return qty;
        }

        public void setQty(BigDecimal qty) {
            this.qty = qty;
        }

        public BigDecimal getChkNum() {
            return chkNum;
        }

        public void setChkNum(BigDecimal chkNum) {
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
    }

    /** 查询限定年度；null=全部年度 */
    private Integer feeYear;
    /** 查询限定批次；null=全部批 */
    private String batchNo;
    private List<YearBucket> years = new ArrayList<YearBucket>();
    private String note;

    public Integer getFeeYear() {
        return feeYear;
    }

    public void setFeeYear(Integer feeYear) {
        this.feeYear = feeYear;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public List<YearBucket> getYears() {
        return years;
    }

    public void setYears(List<YearBucket> years) {
        this.years = years;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
