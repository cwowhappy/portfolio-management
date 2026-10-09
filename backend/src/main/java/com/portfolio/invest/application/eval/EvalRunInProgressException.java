package com.portfolio.invest.application.eval;

/**
 * 评测运行互斥冲突（MS-30 B4，设计规格 §2.2.1/§2.5）：单实例 {@code AtomicBoolean} 互斥下，
 * 进行中再触发（手动或定时）即抛本异常——手动触发端映射 409 {@code EVAL_RUN_IN_PROGRESS}
 * （异常→HTTP 状态映射归 web 层 GlobalExceptionHandler，沿 intelligence 域范式）。
 */
public class EvalRunInProgressException extends RuntimeException {

    public EvalRunInProgressException(String message) {
        super(message);
    }
}
