package com.portfolio.invest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.conversation.ConversationErrorCode;
import com.portfolio.invest.domain.conversation.ConversationException;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局异常映射切片：用真实 MockMvc 请求触发 {@link GlobalExceptionHandler} 中
 * 现有单测未覆盖的分支（用户/会话/持仓域的其余错误码），断言状态码与错误体 code。
 */
@WebMvcTest(controllers = GlobalExceptionHandlerSliceTest.ThrowingController.class)
// 嵌套类不参与组件扫描，需显式 @Import 才会注册为控制器 bean
@Import(GlobalExceptionHandlerSliceTest.ThrowingController.class)
@WithMockUser
class GlobalExceptionHandlerSliceTest {

    @Autowired
    private MockMvc mvc;

    /** 仅用于触发目标异常的测试控制器。 */
    @RestController
    @RequestMapping("/test-throw")
    static class ThrowingController {

        @GetMapping("/user-taken")
        void userTaken() {
            throw new UserException(UserErrorCode.USERNAME_TAKEN, "用户名已被占用");
        }

        @GetMapping("/user-invalid-state")
        void userInvalidState() {
            throw new UserException(UserErrorCode.INVALID_STATE, "状态非待审核，仅待审核用户可通过");
        }

        @GetMapping("/conversation-not-found")
        void conversationNotFound() {
            throw new ConversationException(ConversationErrorCode.NOT_FOUND, "会话不存在");
        }

        @GetMapping("/conversation-invalid-id")
        void conversationInvalidId() {
            throw new ConversationException(ConversationErrorCode.INVALID_ID, "会话ID非法");
        }

        @GetMapping("/portfolio-not-found")
        void portfolioNotFound() {
            throw new PortfolioException(PortfolioErrorCode.NOT_FOUND, "持仓不存在");
        }

        @GetMapping("/portfolio-sell-exceeds")
        void portfolioSellExceeds() {
            throw new PortfolioException(PortfolioErrorCode.SELL_EXCEEDS_QUANTITY, "卖出数量超过持仓");
        }

        @GetMapping("/missing-param")
        void missingParam(@RequestParam String q) {}

        @PostMapping("/not-readable")
        void notReadable(@RequestBody Map<String, Object> body) {}
    }

    @DisplayName("用户名已占用映射400")
    @Test
    void givenUsernameTaken_whenRequestThrows_thenReturn400() throws Exception {
        mvc.perform(get("/test-throw/user-taken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USERNAME_TAKEN))
                .andExpect(jsonPath("$.message").value("用户名已被占用"));
    }

    @DisplayName("用户非法状态落入默认分支映射400")
    @Test
    void givenUserInvalidState_whenRequestThrows_thenReturn400() throws Exception {
        mvc.perform(get("/test-throw/user-invalid-state"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.INVALID_STATE));
    }

    @DisplayName("会话不存在映射404")
    @Test
    void givenConversationNotFound_whenRequestThrows_thenReturn404() throws Exception {
        mvc.perform(get("/test-throw/conversation-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConversationErrorCode.NOT_FOUND))
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @DisplayName("会话ID非法映射400")
    @Test
    void givenConversationInvalidId_whenRequestThrows_thenReturn400() throws Exception {
        mvc.perform(get("/test-throw/conversation-invalid-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ConversationErrorCode.INVALID_ID));
    }

    @DisplayName("持仓不存在映射404")
    @Test
    void givenPortfolioNotFound_whenRequestThrows_thenReturn404() throws Exception {
        mvc.perform(get("/test-throw/portfolio-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(PortfolioErrorCode.NOT_FOUND))
                .andExpect(jsonPath("$.message").value("持仓不存在"));
    }

    @DisplayName("持仓业务错误落入默认分支映射400")
    @Test
    void givenPortfolioSellExceeds_whenRequestThrows_thenReturn400() throws Exception {
        mvc.perform(get("/test-throw/portfolio-sell-exceeds"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PortfolioErrorCode.SELL_EXCEEDS_QUANTITY))
                .andExpect(jsonPath("$.message").value("卖出数量超过持仓"));
    }

    @DisplayName("缺少必填query参数映射400")
    @Test
    void givenMissingRequiredQueryParam_whenRequestThrows_thenReturn400() throws Exception {
        mvc.perform(get("/test-throw/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("缺少必填参数：q"));
    }

    @DisplayName("非法JSON请求体映射400")
    @Test
    void givenMalformedJsonBody_whenRequestThrows_thenReturn400() throws Exception {
        mvc.perform(post("/test-throw/not-readable")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求体格式不合法"));
    }
}
