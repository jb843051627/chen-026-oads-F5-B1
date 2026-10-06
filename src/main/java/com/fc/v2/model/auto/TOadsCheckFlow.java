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
 * 集中更换核验流水对象 t_oads_check_flow（append-only）。
 *
 * <p>新建收讫、修改落版、重复交、出回执、批内同码合并各落一行，只许追加，不改不删。
 * 判定必须指得出依哪一套标准——CREATE/MODIFY/RESEND/RECEIPT/MERGE 各记各的，
 * 核查时顺着流水就能翻出某一行当初是依新建还是修改那一套、第几条判的。
 *
 * @author fuce
 * @date 2026-10-06
 */
@TableName("t_oads_check_flow")
@ApiModel(value = "TOadsCheckFlow", description = "集中更换核验流水（append-only）")
public class TOadsCheckFlow implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 哪一批（批次码） */
    @TableField("batch_no")
    @ApiModelProperty(value = "批次码")
    private String batchNo;

    /** 动作 CREATE新建收讫 MODIFY修改落版 RESEND重复交 RECEIPT出回执 MERGE同码合并 */
    @TableField("action")
    @ApiModelProperty(value = "动作 CREATE/MODIFY/RESEND/RECEIPT/MERGE")
    private String action;

    /** 入口：CREATE新建 / MODIFY修改（RESEND 记第二回走的入口） */
    @TableField("entry_kind")
    @ApiModelProperty(value = "入口 CREATE/MODIFY")
    private String entryKind;

    /** 来路：DISTRICT区级报上来 / CITY市局自核 */
    @TableField("put_kind")
    @ApiModelProperty(value = "来路 DISTRICT/CITY")
    private String putKind;

    /** 本笔记的物理行数（合并前） */
    @TableField("total_rows")
    @ApiModelProperty(value = "物理行数（合并前）")
    private Integer totalRows;

    /** 本笔记的入账行数（合并后） */
    @TableField("landed_rows")
    @ApiModelProperty(value = "入账行数（合并后）")
    private Integer landedRows;

    /** 本笔记的收下行数 */
    @TableField("accepted_rows")
    @ApiModelProperty(value = "收下行数")
    private Integer acceptedRows;

    /** 本笔记的退回行数 */
    @TableField("returned_rows")
    @ApiModelProperty(value = "退回行数")
    private Integer returnedRows;

    /** 这一笔的交代（哪几条合并、重送不重复入、修改以哪版为准，逐句原文） */
    @TableField("content")
    @ApiModelProperty(value = "这一笔的交代")
    private String content;

    /** 经办岗 */
    @TableField("operator")
    @ApiModelProperty(value = "经办岗")
    private String operator;

    /** 落账时刻（毫秒） */
    @TableField("acted_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    @ApiModelProperty(value = "落账时刻")
    private Date actedAt;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntryKind() {
        return entryKind;
    }

    public void setEntryKind(String entryKind) {
        this.entryKind = entryKind;
    }

    public String getPutKind() {
        return putKind;
    }

    public void setPutKind(String putKind) {
        this.putKind = putKind;
    }

    public Integer getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(Integer totalRows) {
        this.totalRows = totalRows;
    }

    public Integer getLandedRows() {
        return landedRows;
    }

    public void setLandedRows(Integer landedRows) {
        this.landedRows = landedRows;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Date getActedAt() {
        return actedAt;
    }

    public void setActedAt(Date actedAt) {
        this.actedAt = actedAt;
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
}
