package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 集中更换核验行对象 t_oads_check_row
 *
 * @author fuce
 * @date 2026-09-12
 */
@TableName("t_oads_check_row")
@ApiModel(value = "TOadsCheckRow", description = "集中更换核验行")
public class TOadsCheckRow implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 集中更换批次码 */
    @TableField("batch_no")
    @ApiModelProperty(value = "集中更换批次码")
    private String batchNo;

    /** 原报行次 */
    @TableField("row_no")
    @ApiModelProperty(value = "原报行次")
    private Integer rowNo;

    /** 被对上的点位码 */
    @TableField("item_code")
    @ApiModelProperty(value = "被对上的点位码")
    private String itemCode;

    /** 本行应更换处数 */
    @TableField("qty")
    @ApiModelProperty(value = "本行应更换处数")
    private BigDecimal qty;

    /** 本行核验合格处数 */
    @TableField("chk_num")
    @ApiModelProperty(value = "本行核验合格处数")
    private BigDecimal chkNum;

    /** 来路（区级报上来／市局自核两条来路） */
    @TableField("put_kind")
    @ApiModelProperty(value = "来路（区级报上来／市局自核两条来路）")
    private String putKind;

    /** 行落地情形 0未勾 1已收下 2已退回 */
    @TableField("status")
    @ApiModelProperty(value = "行落地情形 0未勾 1已收下 2已退回")
    private Integer status;

    /** 收费年度（冻结自批账：按批首回收讫那一刻所在年度，整批一个年度；落去向直接读本行） */
    @TableField("fee_year")
    @ApiModelProperty(value = "收费年度（冻结自批账，整批同一年度）")
    private Integer feeYear;

    /**
     * 判定来源：本行结论依的是哪一套规矩——"新建/校验第N条"或"修改/校验第N条"。
     * 核查时必须指得出是哪一段、依哪套标准判的；两个入口各写各的校验，来源不许混。
     */
    @TableField("judge_source")
    @ApiModelProperty(value = "判定来源（新建/修改 各第几条）")
    private String judgeSource;

    /** 退回/收下的逐句原因原文（多条分号隔，按原报行次逐行可回查；收下行记合并等交代） */
    @TableField("reason")
    @ApiModelProperty(value = "逐句原因原文")
    private String reason;

    /** 本批内同编码并入本行的其余原报行次（逗号隔；为空表示无合并，第一条本身不重复记） */
    @TableField("merged_row_nos")
    @ApiModelProperty(value = "批内同码合并进来的其余原报行次")
    private String mergedRowNos;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public Integer getRowNo() {
        return rowNo;
    }

    public void setRowNo(Integer rowNo) {
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

    public String getPutKind() {
        return putKind;
    }

    public void setPutKind(String putKind) {
        this.putKind = putKind;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getFeeYear() {
        return feeYear;
    }

    public void setFeeYear(Integer feeYear) {
        this.feeYear = feeYear;
    }

    public String getJudgeSource() {
        return judgeSource;
    }

    public void setJudgeSource(String judgeSource) {
        this.judgeSource = judgeSource;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getMergedRowNos() {
        return mergedRowNos;
    }

    public void setMergedRowNos(String mergedRowNos) {
        this.mergedRowNos = mergedRowNos;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
