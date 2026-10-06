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
import java.util.Date;

/**
 * 集中更换核验批账对象 t_oads_check_batch（一批一行，批次码唯一）。
 *
 * <p>批次码是批的唯一身份。头一回收讫立批；同一批次码再交两回（区级报上来与市局自核、
 * 或新建入口与修改入口各交一回）都不另立批——重复交只加 {@code dup_count}、落 RESEND 流水，
 * 行不重复入。版本取舍定死：批次身份、来路、收费年度一律以<b>首回收讫版</b>为准冻结，
 * 修改版只改行内容（MODIFY 流水留痕），不重定来路、不重算年度。
 *
 * @author fuce
 * @date 2026-10-06
 */
@TableName("t_oads_check_batch")
@ApiModel(value = "TOadsCheckBatch", description = "集中更换核验批账（一批一行）")
public class TOadsCheckBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 集中更换批次码（批的唯一身份） */
    @TableField("batch_no")
    @ApiModelProperty(value = "批次码")
    private String batchNo;

    /** 来路（只记头一回那一条）：DISTRICT区级报上来 CITY市局自核 */
    @TableField("put_kind")
    @ApiModelProperty(value = "来路 DISTRICT/CITY（只记头一回）")
    private String putKind;

    /** 进门点数所得物理行数（报文几行记几行，合并前的原报条数） */
    @TableField("counted_rows")
    @ApiModelProperty(value = "进门点物理行数（合并前）")
    private Integer countedRows;

    /** 合并后入账行数（同编码明细合并后的行账条数） */
    @TableField("landed_rows")
    @ApiModelProperty(value = "合并后入账行数")
    private Integer landedRows;

    /** 批内同码合并掉的明细条数（counted - landed） */
    @TableField("merged_rows")
    @ApiModelProperty(value = "批内同码合并掉的明细条数")
    private Integer mergedRows;

    /** 收下行数（回库逐行数，不抄页面临值） */
    @TableField("accepted_rows")
    @ApiModelProperty(value = "收下行数")
    private Integer acceptedRows;

    /** 退回行数（回库逐行数）；收下+退回=入账行数 */
    @TableField("returned_rows")
    @ApiModelProperty(value = "退回行数")
    private Integer returnedRows;

    /**
     * 收费年度（整批一个年度）：按首回收讫那一刻所在年度冻结。
     * 整册核收、逐行落去向、收费册归集都回这里取，全链路只此一个年度口径。
     */
    @TableField("fee_year")
    @ApiModelProperty(value = "收费年度（按首回收讫时刻冻结，整批一个）")
    private Integer feeYear;

    /** 年度口径的说法原文（凭哪一刻、怎么定的，事后翻得出） */
    @TableField("fee_year_basis")
    @ApiModelProperty(value = "收费年度口径说法原文")
    private String feeYearBasis;

    /** 同一批次码重复交回数（第二回起每回加一，行不重复入） */
    @TableField("dup_count")
    @ApiModelProperty(value = "重复交回数")
    private Integer dupCount;

    /** 收讫那一刻（首回；冻结字段的版本基准） */
    @TableField("received_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    @ApiModelProperty(value = "首回收讫时刻")
    private Date receivedAt;

    /** 批情形 0已收讫 */
    @TableField("status")
    @ApiModelProperty(value = "批情形 0已收讫")
    private Integer status;

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

    public String getPutKind() {
        return putKind;
    }

    public void setPutKind(String putKind) {
        this.putKind = putKind;
    }

    public Integer getCountedRows() {
        return countedRows;
    }

    public void setCountedRows(Integer countedRows) {
        this.countedRows = countedRows;
    }

    public Integer getLandedRows() {
        return landedRows;
    }

    public void setLandedRows(Integer landedRows) {
        this.landedRows = landedRows;
    }

    public Integer getMergedRows() {
        return mergedRows;
    }

    public void setMergedRows(Integer mergedRows) {
        this.mergedRows = mergedRows;
    }

    public Integer getAcceptedRows() {
        return acceptedRows;
    }

    public void setAcceptedRows(Integer acceptedRows) {
        this.acceptedRows = acceptedRows;
    }

    public Integer getReturnedRows() {
        return returnedRows;
    }

    public void setReturnedRows(Integer returnedRows) {
        this.returnedRows = returnedRows;
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

    public Integer getDupCount() {
        return dupCount;
    }

    public void setDupCount(Integer dupCount) {
        this.dupCount = dupCount;
    }

    public Date getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Date receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
