package com.portfolio.invest.web;

import com.portfolio.invest.domain.conversation.ConversationErrorCode;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.user.UserErrorCode;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常 → 结构化错误。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<ApiError> market(MarketDataException e) {
        HttpStatus status = switch (e.getCode()) {
            case MarketDataErrorCode.INVALID_CODE, MarketDataErrorCode.INVALID_PERIOD, MarketDataErrorCode.INVALID_QUERY -> HttpStatus.BAD_REQUEST;
            case MarketDataErrorCode.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
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
            case UserErrorCode.USERNAME_TAKEN, UserErrorCode.INVALID_USERNAME, UserErrorCode.WEAK_PASSWORD -> HttpStatus.BAD_REQUEST;
            case UserErrorCode.USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UserErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(com.portfolio.invest.domain.conversation.ConversationException.class)
    public ResponseEntity<ApiError> conversation(com.portfolio.invest.domain.conversation.ConversationException e) {
        HttpStatus status = switch (e.getCode()) {
            case ConversationErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ConversationErrorCode.INVALID_ID, ConversationErrorCode.INVALID_MESSAGE -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(com.portfolio.invest.domain.portfolio.PortfolioException.class)
    public ResponseEntity<ApiError> portfolio(com.portfolio.invest.domain.portfolio.PortfolioException e) {
        HttpStatus status = switch (e.code()) {
            case com.portfolio.invest.domain.portfolio.PortfolioErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
    }

    @ExceptionHandler(com.portfolio.invest.domain.allocation.AllocationException.class)
    public ResponseEntity<ApiError> allocation(com.portfolio.invest.domain.allocation.AllocationException e) {
        HttpStatus status = switch (e.code()) {
            case com.portfolio.invest.domain.allocation.AllocationErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
    }

    @ExceptionHandler(com.portfolio.invest.domain.screening.ScreeningException.class)
    public ResponseEntity<ApiError> screening(com.portfolio.invest.domain.screening.ScreeningException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(e.code(), e.getMessage()));
    }

    /** Bean Validation 结构性校验失败（@Valid wire DTO）→ 400，错误体保持 ApiError。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("INVALID_REQUEST", message));
    }

    /** 缺必填 query 参数 → 400（兜底 Exception 处理器会抢在 Spring 默认解析前映射成 500，需显式映射）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "缺少必填参数：" + e.getParameterName()));
    }

    /** 请求体非法 JSON → 400（同上，避免落入 500 兜底）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> notReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "请求体格式不合法"));
    }

    /** 数据库约束兜底：唯一键/长度等约束违例映射 400，避免冒泡成 500。 */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("数据约束违例: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_DATA", "数据不符合存储约束"));
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
