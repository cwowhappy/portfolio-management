package com.portfolio.invest.web;

import com.portfolio.invest.domain.market.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常 → 结构化错误。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<ApiError> market(MarketDataException e) {
        HttpStatus status = switch (e.getCode()) {
            case "INVALID_CODE", "INVALID_PERIOD", "INVALID_QUERY" -> HttpStatus.BAD_REQUEST;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> {
                log.warn("未识别的行情错误码 {}，按 502 处理: {}", e.getCode(), e.getMessage());
                yield HttpStatus.BAD_GATEWAY;
            }
        };
        return ResponseEntity.status(status).body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(com.portfolio.invest.domain.user.UserException.class)
    public ResponseEntity<ApiError> user(com.portfolio.invest.domain.user.UserException e) {
        HttpStatus status = switch (e.getCode()) {
            case "USERNAME_TAKEN", "INVALID_USERNAME", "WEAK_PASSWORD" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.getCode(), e.getMessage()));
    }

    /** Agent 未注册（未配置 DEEPSEEK_API_KEY 时）→ 友好提示。 */
    @ExceptionHandler(io.agentscope.core.agui.AguiException.AgentNotFoundException.class)
    public ResponseEntity<ApiError> agentNotFound(
            io.agentscope.core.agui.AguiException.AgentNotFoundException e) {
        log.warn("AG-UI 请求到达但 Agent 未注册: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(
                        "AGENT_NOT_CONFIGURED",
                        "AI 模型尚未配置：请在后端环境变量中设置 DEEPSEEK_API_KEY 后重启服务"));
    }

    @ExceptionHandler(io.agentscope.core.agui.AguiException.class)
    public ResponseEntity<ApiError> agui(io.agentscope.core.agui.AguiException e) {
        log.warn("AG-UI 异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("AGUI_ERROR", e.getMessage()));
    }

    /**
     * 客户端在 SSE 流传输中主动断开（用户停止/关页）——这是正常行为，
     * 不记 ERROR、不尝试向已关闭的流写错误体（否则触发无转换器的二次异常）。
     * 只精确匹配「客户端断开」两类信号，避免吞掉业务层的真实 IOException。
     */
    @ExceptionHandler({
        org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
        org.apache.catalina.connector.ClientAbortException.class,
    })
    public void clientDisconnected(Exception e) {
        log.debug("SSE 客户端断开，忽略: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务器内部错误"));
    }
}
