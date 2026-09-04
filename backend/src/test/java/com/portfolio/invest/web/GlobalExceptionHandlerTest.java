package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.valuation.ValuationErrorCode;
import com.portfolio.invest.domain.valuation.ValuationException;
import io.agentscope.core.agui.AguiException;
import java.util.List;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** 全局异常 → HTTP 状态映射。 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @DisplayName("参数类错误映射400")
    @Test
    void givenParameterErrors_whenHandlingMarketException_thenReturn400() {
        assertStatus(new MarketDataException(MarketDataErrorCode.INVALID_CODE, "无效代码"), HttpStatus.BAD_REQUEST);
        assertStatus(new MarketDataException(MarketDataErrorCode.INVALID_PERIOD, "无效周期"), HttpStatus.BAD_REQUEST);
        assertStatus(new MarketDataException(MarketDataErrorCode.INVALID_QUERY, "无效关键词"), HttpStatus.BAD_REQUEST);
    }

    @DisplayName("限流映射429")
    @Test
    void givenRateLimitedMarketException_whenHandlingMarketException_thenReturn429() {
        assertStatus(new MarketDataException(MarketDataErrorCode.RATE_LIMITED, "太频繁"), HttpStatus.TOO_MANY_REQUESTS);
    }

    @DisplayName("其他市场异常映射502")
    @Test
    void givenOtherMarketException_whenHandlingMarketException_thenReturn502() {
        ResponseEntity<ApiError> res = assertStatus(
                new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "上游挂了"), HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody()).isEqualTo(new ApiError(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "上游挂了"));
    }

    @DisplayName("用户异常FORBIDDEN映射403")
    @Test
    void givenUserForbiddenError_whenHandlingUserException_thenReturn403() {
        ResponseEntity<ApiError> res = handler.user(
                new UserException(UserErrorCode.FORBIDDEN, "不能对管理员账号执行此操作"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isEqualTo(new ApiError(UserErrorCode.FORBIDDEN, "不能对管理员账号执行此操作"));
    }

    @DisplayName("用户异常USER_NOT_FOUND映射404")
    @Test
    void givenUserNotFoundError_whenHandlingUserException_thenReturn404() {
        ResponseEntity<ApiError> res = handler.user(new UserException(UserErrorCode.USER_NOT_FOUND, "用户不存在"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isEqualTo(new ApiError(UserErrorCode.USER_NOT_FOUND, "用户不存在"));
    }

    @DisplayName("估值异常NOT_FOUND映射404")
    @Test
    void givenValuationNotFound_whenHandlingValuationException_thenReturn404() {
        ResponseEntity<ApiError> res = handler.valuation(
                new ValuationException(ValuationErrorCode.NOT_FOUND, "无估值快照"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isEqualTo(new ApiError(ValuationErrorCode.NOT_FOUND, "无估值快照"));
    }

    @DisplayName("估值异常INVALID_INPUT映射400")
    @Test
    void givenValuationInvalidInput_whenHandlingValuationException_thenReturn400() {
        ResponseEntity<ApiError> res = handler.valuation(
                new ValuationException(ValuationErrorCode.INVALID_INPUT, "非法周期"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo(new ApiError(ValuationErrorCode.INVALID_INPUT, "非法周期"));
    }

    @DisplayName("会话异常INVALID_MESSAGE映射400")
    @Test
    void givenConversationInvalidMessage_whenHandlingConversationException_thenReturn400() {
        ResponseEntity<ApiError> res = handler.conversation(
                new com.portfolio.invest.domain.conversation.ConversationException(ConversationErrorCode.INVALID_MESSAGE, "消息内容超长（上限100KB）"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo(new ApiError(ConversationErrorCode.INVALID_MESSAGE, "消息内容超长（上限100KB）"));
    }

    @DisplayName("数据约束违例兜底映射400")
    @Test
    void givenDataIntegrityViolation_whenHandlingDataIntegrityException_thenReturn400() {
        ResponseEntity<ApiError> res = handler.dataIntegrity(
                new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo(new ApiError("INVALID_DATA", "数据不符合存储约束"));
    }

    @DisplayName("乐观锁冲突映射409")
    @Test
    void givenOptimisticLockConflict_whenHandlingOptimisticLockException_thenReturn409() {
        ResponseEntity<ApiError> res = handler.optimisticLock(
                new org.springframework.dao.OptimisticLockingFailureException("conflict"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isEqualTo(new ApiError("CONFLICT", "数据已被他人修改，请刷新后重试"));
    }

    @DisplayName("Object乐观锁异常同样映射409")
    @Test
    void givenObjectOptimisticLockFailure_whenHandlingOptimisticLockException_thenReturn409() {
        ResponseEntity<ApiError> res = handler.optimisticLock(
                new org.springframework.orm.ObjectOptimisticLockingFailureException(String.class, "k"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().code()).isEqualTo("CONFLICT");
    }

    @DisplayName("agent未注册映射503与友好文案")
    @Test
    void givenAgentNotFound_whenHandlingAgentNotFoundException_thenReturn503WithFriendlyMessage() {
        ResponseEntity<ApiError> res = handler.agentNotFound(new AguiException.AgentNotFoundException("invest"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).isEqualTo(new ApiError(
                "AGENT_NOT_CONFIGURED",
                "AI 模型尚未配置：请在后端环境变量中设置 DEEPSEEK_API_KEY 后重启服务"));
    }

    @DisplayName("agui异常映射400")
    @Test
    void givenAguiException_whenHandlingAguiException_thenReturn400() {
        ResponseEntity<ApiError> res = handler.agui(new AguiException("协议错误"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo(new ApiError("AGUI_ERROR", "协议错误"));
    }

    @DisplayName("客户端断开被静默忽略")
    @Test
    void givenClientDisconnected_whenHandlingClientDisconnect_thenSilentlyIgnored() {
        handler.clientDisconnected(mock(AsyncRequestNotUsableException.class));
        ClientAbortException abort = mock(ClientAbortException.class);
        when(abort.getMessage()).thenReturn("broken pipe");
        handler.clientDisconnected(abort);
    }

    @DisplayName("未知异常映射500")
    @Test
    void givenUnknownException_whenHandlingGeneric_thenReturn500() {
        ResponseEntity<ApiError> res = handler.generic(new RuntimeException("boom"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).isEqualTo(new ApiError("INTERNAL_ERROR", "服务器内部错误"));
    }

    @DisplayName("BeanValidation失败映射400并取首条字段错误消息")
    @Test
    void givenBeanValidationWithFieldErrors_whenHandlingValidation_thenReturn400WithFirstFieldError() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors())
                .thenReturn(List.of(new FieldError("loginRequest", "username", "用户名不能为空")));

        ResponseEntity<ApiError> res = handler.validation(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo(new ApiError("INVALID_REQUEST", "用户名不能为空"));
    }

    @DisplayName("BeanValidation无字段错误时用兜底消息")
    @Test
    void givenBeanValidationWithoutFieldErrors_whenHandlingValidation_thenUseFallbackMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());

        ResponseEntity<ApiError> res = handler.validation(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo(new ApiError("INVALID_REQUEST", "请求参数不合法"));
    }

    private ResponseEntity<ApiError> assertStatus(MarketDataException e, HttpStatus expected) {
        ResponseEntity<ApiError> res = handler.market(e);
        assertThat(res.getStatusCode()).isEqualTo(expected);
        assertThat(res.getBody().code()).isEqualTo(e.getCode());
        return res;
    }
}
