package com.fc.v2.common.exception.checkrow;

/**
 * 集中更换核验行口径违例：批次码缺填、空批、越过硬界、修改入口对不上已收讫的批等
 * "整批交不进来/改不下去"这一层的情形。
 *
 * <p>行上的毛病不抛本异常——哪一行因为哪一条退回，按原报行次逐行落账、在回执逐行点名，
 * 一行有错不拦同批别行。每条判定都带"判定来源"（依新建还是修改那一套、第几条），
 * 核查时指得出是哪一段、依哪套标准判的。
 *
 * @author fuce
 * @date 2026-10-06
 */
public class CheckRowRuleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CheckRowRuleException(String message) {
        super(message);
    }
}
