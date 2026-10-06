package com.fc.v2.controller.admin;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.exception.checkrow.CheckRowRuleException;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TOadsCheckBatch;
import com.fc.v2.model.auto.TOadsCheckRow;
import com.fc.v2.model.custom.CheckRowBatchReceipt;
import com.fc.v2.model.custom.CheckRowFeeBook;
import com.fc.v2.service.ITOadsCheckRowService;
import com.fc.v2.service.checkrow.CheckRowConsts;
import com.fc.v2.shiro.util.ShiroUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * 集中更换核验行核收 Controller——区局核收这一档的唯一门面。
 *
 * <p>两个入口各写各的校验规矩：{@code submitNew} 新建批、{@code submitModify} 修改批，
 * 判定全部在 {@link ITOadsCheckRowService} 一处给出，每行结论带判定来源（依哪一套第几条）。
 * 同一批次码并着送两回幂等；列表、详情、收费册、回执对同一批数据同一个口径
 * （同一份行账、同一个状态谓词、同一个冻结收费年度）。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Api(value = "集中更换核验行核收")
@Controller
@RequestMapping("/oadsCheckRow")
public class OadsCheckRowController extends BaseController {

    private final String prefix = "admin/oadsCheckRow";

    @Autowired
    private ITOadsCheckRowService checkRowService;

    @ApiOperation(value = "核收页跳转", notes = "核收页跳转")
    @GetMapping("/view")
    @RequiresPermissions("oads:oadsCheckRow:view")
    public String view(ModelMap model) {
        model.put("hardRowLimit", CheckRowConsts.HARD_ROW_LIMIT);
        return prefix + "/list";
    }

    @Log(title = "集中更换核验行新建批核收", action = "submitNew")
    @ApiOperation(value = "新建批核收", notes = "新建入口一套校验；一批一次，同批重交幂等")
    @PostMapping("/submitNew")
    @RequiresPermissions("oads:oadsCheckRow:submit")
    @ResponseBody
    public AjaxResult submitNew(@RequestParam("batchNo") String batchNo,
                                @RequestParam(value = "putKind", required = false) String putKind,
                                @RequestBody List<TOadsCheckRow> rows) {
        int accepted = checkRowService.submitNewBatch(batchNo, rows, putKind, ShiroUtils.getLoginName());
        if (accepted < 0) {
            return AjaxResult.error("物理行越过防呆硬界 " + CheckRowConsts.HARD_ROW_LIMIT
                    + "，整批未入库；本系统没有行数上限，多多益善，硬界只防误传");
        }
        try {
            // 出回执与核收出自同一次计算，这里照原批行账回库逐行重数一遍回原句。
            CheckRowBatchReceipt receipt = checkRowService.receipt(batchNo, ShiroUtils.getLoginName());
            return AjaxResult.successData(200, receipt).put("msg", receipt.getNote());
        } catch (CheckRowRuleException e) {
            // 空批兜底：没立批也没入库，不报错退出，给一句明白话。
            return AjaxResult.success("空批兜底：一行都没有，未立批、未入库，收下 0 行");
        }
    }

    @Log(title = "集中更换核验行修改批核收", action = "submitModify")
    @ApiOperation(value = "修改批核收", notes = "修改入口另一套校验；来路/年度以首回收讫版为准")
    @PostMapping("/submitModify")
    @RequiresPermissions("oads:oadsCheckRow:submit")
    @ResponseBody
    public AjaxResult submitModify(@RequestParam("batchNo") String batchNo,
                                   @RequestBody List<TOadsCheckRow> rows) {
        try {
            CheckRowBatchReceipt receipt = checkRowService.modifyBatch(batchNo, rows, ShiroUtils.getLoginName());
            return AjaxResult.successData(200, receipt).put("msg", receipt.getNote());
        } catch (CheckRowRuleException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @ApiOperation(value = "批账清单", notes = "按收费年度过滤，按收讫时间倒序")
    @GetMapping("/listBatches")
    @RequiresPermissions("oads:oadsCheckRow:list")
    @ResponseBody
    public AjaxResult listBatches(@RequestParam(value = "feeYear", required = false) Integer feeYear) {
        return AjaxResult.successData(200, checkRowService.listBatches(feeYear));
    }

    @ApiOperation(value = "核收回执", notes = "物理/入账/收下/退回四数回库逐行数，逐行带判定来源")
    @GetMapping("/receipt")
    @RequiresPermissions("oads:oadsCheckRow:view")
    @ResponseBody
    public AjaxResult receipt(@RequestParam("batchNo") String batchNo) {
        try {
            CheckRowBatchReceipt receipt = checkRowService.receipt(batchNo, ShiroUtils.getLoginName());
            return AjaxResult.successData(200, receipt).put("msg", receipt.getNote());
        } catch (CheckRowRuleException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @ApiOperation(value = "退回行明细", notes = "与详情/收费册同一状态谓词")
    @GetMapping("/listErrors")
    @RequiresPermissions("oads:oadsCheckRow:list")
    @ResponseBody
    public AjaxResult listErrors(@RequestParam("batchNo") String batchNo) {
        try {
            return AjaxResult.successData(200, checkRowService.listErrors(batchNo));
        } catch (CheckRowRuleException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @ApiOperation(value = "核验行详情", notes = "结论/逐句原因/判定来源/收费年度，与列表同口径")
    @GetMapping("/detail")
    @RequiresPermissions("oads:oadsCheckRow:list")
    @ResponseBody
    public AjaxResult detail(@RequestParam("batchNo") String batchNo,
                             @RequestParam("rowNo") int rowNo) {
        try {
            return AjaxResult.successData(200, checkRowService.rowDetail(batchNo, rowNo));
        } catch (CheckRowRuleException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @ApiOperation(value = "收费册年度归集", notes = "按冻结收费年度落去向，与整册核收同一年度")
    @GetMapping("/feeBook")
    @RequiresPermissions("oads:oadsCheckRow:list")
    @ResponseBody
    public AjaxResult feeBook(@RequestParam(value = "feeYear", required = false) Integer feeYear,
                              @RequestParam(value = "batchNo", required = false) String batchNo) {
        CheckRowFeeBook feeBook = checkRowService.feeBook(feeYear, batchNo, ShiroUtils.getLoginName());
        return AjaxResult.successData(200, feeBook).put("msg", feeBook.getNote());
    }
}
