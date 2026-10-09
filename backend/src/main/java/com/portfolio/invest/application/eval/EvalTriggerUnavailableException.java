package com.portfolio.invest.application.eval;

/**
 * eval 触发不可用（MS-30 B5 审查 I2）：调度器 {@code triggerNow} 的前置失败（未启用
 * invest.eval.enabled / 缺 DEEPSEEK_API_KEY / 数据目录、evalBootJar 派生或子进程启动失败）
 * 抛 {@code IllegalStateException}——文案本就面向运维/管理员，但落入 generic 500 会被吞成
 * 「服务器内部错误」。用例层包本异常透出原文案，web 层映射 503
 * {@code EVAL_TRIGGER_UNAVAILABLE}（沿 AgentNotFoundException→503 AGENT_NOT_CONFIGURED
 * 先例：服务端配置缺失类失败）。进行中冲突（{@link EvalRunInProgressException}）不受影响。
 */
public class EvalTriggerUnavailableException extends RuntimeException {

    public EvalTriggerUnavailableException(String message) {
        super(message);
    }

    public EvalTriggerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
