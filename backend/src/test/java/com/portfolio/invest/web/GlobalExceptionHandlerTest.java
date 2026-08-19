package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.market.MarketDataException;
import io.agentscope.core.agui.AguiException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** 全局异常 → HTTP 状态映射。 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void 参数类错误映射400() {
        assertStatus(new MarketDataException("INVALID_CODE", "无效代码"), HttpStatus.BAD_REQUEST);
        assertStatus(new MarketDataException("INVALID_PERIOD", "无效周期"), HttpStatus.BAD_REQUEST);
        assertStatus(new MarketDataException("INVALID_QUERY", "无效关键词"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void 限流映射429() {
        assertStatus(new MarketDataException("RATE_LIMITED", "太频繁"), HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void 其他市场异常映射502() {
        ResponseEntity<ApiError> res = assertStatus(
                new MarketDataException("UPSTREAM_UNAVAILABLE", "上游挂了"), HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody()).isEqualTo(new ApiError("UPSTREAM_UNAVAILABLE", "上游挂了"));
    }

    @Test
    void agent未注册映射503与友好文案() {
        ResponseEntity<ApiError> res = handler.agentNotFound(new AguiException.AgentNotFoundException("invest"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).isEqualTo(new ApiError(
                "AGENT_NOT_CONFIGURED",
                "AI 模型尚未配置：请在后端环境变量中设置 DEEPSEEK_API_KEY 后重启服务"));
    }

    @Test
    void agui异常映射400() {
        ResponseEntity<ApiError> res = handler.agui(new AguiException("协议错误"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo(new ApiError("AGUI_ERROR", "协议错误"));
    }

    @Test
    void 客户端断开被静默忽略() {
        handler.clientDisconnected(mock(AsyncRequestNotUsableException.class));
        ClientAbortException abort = mock(ClientAbortException.class);
        when(abort.getMessage()).thenReturn("broken pipe");
        handler.clientDisconnected(abort);
    }

    @Test
    void 未知异常映射500() {
        ResponseEntity<ApiError> res = handler.generic(new RuntimeException("boom"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).isEqualTo(new ApiError("INTERNAL_ERROR", "服务器内部错误"));
    }

    private ResponseEntity<ApiError> assertStatus(MarketDataException e, HttpStatus expected) {
        ResponseEntity<ApiError> res = handler.market(e);
        assertThat(res.getStatusCode()).isEqualTo(expected);
        assertThat(res.getBody().code()).isEqualTo(e.getCode());
        return res;
    }
}
