package com.fc.v2.service.checkrow;

/**
 * 集中更换核验行链路的唯一取号、唯一口径处（与点位名册五检查点、牌匾整册收讫各是一套，互不串用）。
 *
 * <p>这一处把以前"一个地方一个说法"的东西全部定死：
 * <ul>
 *   <li>行落地情形只有 {@link #STATUS_ACCEPTED}收下 / {@link #STATUS_RETURNED}退回 两值，
 *       列表、详情、收费册、回执四处对它的判断只许走 {@link #isReturned} 这一个谓词；</li>
 *   <li>来路只有 {@link #PUT_DISTRICT}区级报上来 / {@link #PUT_CITY}市局自核 两条，
 *       "新建"与"修改"是两个入口，各写各的校验，不共用一份规矩；</li>
 *   <li>没有行数上限这回事——多多益善，{@link #HARD_ROW_LIMIT} 只是防呆硬界，
 *       不是业务上限；越过硬界整批不入库返回 -1；</li>
 *   <li>同一点位行算哪一年度的收费册，只认 {@code feeYear} 这一个函数：
 *       整批一个年度，按首回收讫那一刻所在年度冻结在批账上。整册核收与逐行落去向
 *       都回批账取这一个值，不允许一处按收讫年、一处按核验年各算各的；</li>
 *   <li>版本取舍：批次码是批的唯一身份，批次身份、来路、收费年度一律以<b>首回收讫版</b>为准，
 *       同批再交两回（不管走哪条来路、哪个入口）幂等——不另立批、行不重复入，只加 dup_count、
 *       落 RESEND 流水，回执照原批行账重出；行内容的修改以修改版为准（MODIFY 流水留痕）。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-10-06
 */
public final class CheckRowConsts {

    private CheckRowConsts() {
    }

    // ---------------------------------------------------------------- 行落地情形（唯一一套状态判定）
    /** 行落地情形：已收下（入库） */
    public static final int STATUS_UNSET = 0;
    /** 行落地情形：已收下（入库） */
    public static final int STATUS_ACCEPTED = 1;
    /** 行落地情形：已退回 */
    public static final int STATUS_RETURNED = 2;

    // ---------------------------------------------------------------- 来路（两条）
    /** 来路：区级报上来 */
    public static final String PUT_DISTRICT = "DISTRICT";
    /** 来路：市局自核 */
    public static final String PUT_CITY = "CITY";

    // ---------------------------------------------------------------- 入口（两个，各写各的校验）
    /** 入口：新建批（核验行首次交来） */
    public static final String ENTRY_CREATE = "CREATE";
    /** 入口：修改批（对已收讫批次交修改版） */
    public static final String ENTRY_MODIFY = "MODIFY";

    // ---------------------------------------------------------------- 流水动作（append-only）
    /** 流水动作：新建收讫（头一回，批账与三数的准快照） */
    public static final String FLOW_CREATE = "CREATE";
    /** 流水动作：修改落版（行内容以修改版为准，批次身份/来路/年度不动） */
    public static final String FLOW_MODIFY = "MODIFY";
    /** 流水动作：同批重复交（第二回起每回一笔，不另立批不行重复入） */
    public static final String FLOW_RESEND = "RESEND";
    /** 流水动作：出回执（回库逐行数出的三数留一笔） */
    public static final String FLOW_RECEIPT = "RECEIPT";
    /** 流水动作：批内同码明细合并（哪几条并成一条，逐句留痕） */
    public static final String FLOW_MERGE = "MERGE";

    // ---------------------------------------------------------------- 判定来源（核查必须指得出依哪套标准）
    /** 判定来源：新建入口校验规矩（{@code 新建/校验第N条}） */
    public static final String SOURCE_CREATE = "新建";
    /** 判定来源：修改入口校验规矩（{@code 修改/校验第N条}） */
    public static final String SOURCE_MODIFY = "修改";

    /**
     * 防呆硬界：不是业务上限。本系统没有行数上限这回事，多多益善；
     * 仅当报文字节数出来的物理行多到越过硬界（明显误传/刷屏）时整批退回返 -1。
     */
    public static final int HARD_ROW_LIMIT = 100000;

    /** 数量解析允许的小数位：核验处数按整数处记，超出即按本入口规矩退回该行。 */
    public static final int QTY_MAX_SCALE = 2;

    /** 合并明细上限：同一编码并成一条时，交代里逐句列原报行次，再多只记条数（交代栏长度所限）。 */
    public static final int MERGE_NOTE_ROWS = 20;

    /** 状态谓词唯一处：列表、详情、收费册、回执判断"退回"都走它，不许各写各的。 */
    public static boolean isReturned(Integer status) {
        return status != null && status == STATUS_RETURNED;
    }

    /** 状态谓词唯一处：非退回即收下（含 0 未勾这种异常存量时，不当成退回，由回执点名）。 */
    public static boolean isAccepted(Integer status) {
        return status != null && status == STATUS_ACCEPTED;
    }

    public static String statusName(Integer status) {
        if (status == null) {
            return "未判";
        }
        switch (status.intValue()) {
            case STATUS_ACCEPTED:
                return "已收下";
            case STATUS_RETURNED:
                return "已退回";
            case STATUS_UNSET:
                return "未勾";
            default:
                return "第四种状态(" + status + ")";
        }
    }

    public static String putName(String putKind) {
        return PUT_CITY.equals(putKind) ? "市局自核" : "区级报上来";
    }

    public static String entryName(String entryKind) {
        return ENTRY_MODIFY.equals(entryKind) ? "修改" : "新建";
    }

    /**
     * 收费年度唯一算法：整批一个年度，按首回收讫那一刻所在年度冻结。
     * 收讫之后任何一步（整册核收、逐行落去向、收费册归集、列表/详情）要用年度，
     * 都回批账取 fee_year，不允许再按别的时刻重算——同一点位不会一边算本年、一边算上年。
     */
    public static int feeYearOf(java.util.Date receivedAt) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(receivedAt);
        return c.get(java.util.Calendar.YEAR);
    }
}
