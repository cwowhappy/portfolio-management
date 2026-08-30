package com.portfolio.invest.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.auth.UserView;
import com.portfolio.invest.application.portfolio.PositionView;
import com.portfolio.invest.application.useradmin.UserAdminView;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.web.ApiError;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * web 层主要响应/请求 DTO 的 wire 契约断言：字段名、枚举序列化为名称字符串、
 * null 字段默认序列化为 null、缺省字段反序列化行为（项目用 Jackson 2，
 * 直接用 {@code @JsonTest} 自动配置的 Jackson 2 {@link ObjectMapper} 断言）。
 */
@JsonTest
class DtoJsonContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 登录响应序列化字段名与枚举名称() throws Exception {
        var view = new UserView(1L, "alice", UserRole.USER, UserStatus.APPROVED, true,
                Instant.parse("2026-08-01T02:03:04Z"));

        JsonNode content = objectMapper.readTree(objectMapper.writeValueAsString(view));

        assertThat(content).isEqualTo(objectMapper.readTree("""
                {"id":1,"username":"alice","role":"USER","status":"APPROVED",
                 "enabled":true,"createdAt":"2026-08-01T02:03:04Z"}"""));
    }

    @Test
    void 错误体仅含code与message() throws Exception {
        JsonNode content = objectMapper.readTree(
                objectMapper.writeValueAsString(new ApiError("INVALID_REQUEST", "用户名不能为空")));

        assertThat(content).isEqualTo(objectMapper.readTree(
                "{\"code\":\"INVALID_REQUEST\",\"message\":\"用户名不能为空\"}"));
    }

    @Test
    void 持仓视图空值字段序列化为null且保留字段名() throws Exception {
        var view = new PositionView(5L, 1L, "600519", "贵州茅台",
                new BigDecimal("100"), new BigDecimal("1500.05"), null,
                null, null, null,
                new BigDecimal("0"), new BigDecimal("150005"), new BigDecimal("0"));

        JsonNode content = objectMapper.readTree(objectMapper.writeValueAsString(view));

        // 无行情时 price/marketValue/floatingPnl/pnlRatio 为 null，默认 inclusion 下字段保留
        assertThat(content.has("price")).isTrue();
        assertThat(content.get("price").isNull()).isTrue();
        assertThat(content.get("marketValue").isNull()).isTrue();
        assertThat(content.get("floatingPnl").isNull()).isTrue();
        assertThat(content.get("pnlRatio").isNull()).isTrue();
        assertThat(content.get("stockCode").asText()).isEqualTo("600519");
        assertThat(content.get("quantity").asInt()).isEqualTo(100);
    }

    @Test
    void 用户管理视图角色与状态序列化为字符串() throws Exception {
        JsonNode content = objectMapper.readTree(objectMapper.writeValueAsString(
                new UserAdminView(2L, "bob", "USER", "PENDING", true)));

        assertThat(content).isEqualTo(objectMapper.readTree("""
                {"id":2,"username":"bob","role":"USER","status":"PENDING","enabled":true}"""));
    }

    @Test
    void 登录请求缺省rememberMe反序列化为false() throws Exception {
        LoginRequest req = objectMapper.readValue(
                "{\"username\":\"u\",\"password\":\"p\"}", LoginRequest.class);

        assertThat(req.rememberMe()).isFalse();
    }

    @Test
    void 登录请求rememberMe为true时生效() throws Exception {
        LoginRequest req = objectMapper.readValue(
                "{\"username\":\"u\",\"password\":\"p\",\"rememberMe\":true}", LoginRequest.class);

        assertThat(req.rememberMe()).isTrue();
    }
}
