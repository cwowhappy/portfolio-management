package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.bdd.CucumberSpringConfig;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * AG-UI 对话步骤：MockMvc 确定性模式断言 SSE 事件流
 * （perform → asyncStarted → getAsyncResult(30s) → asyncDispatch，再对响应体做子串断言）。
 */
public class AguiSteps {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ScenarioContext ctx;

    @当("该用户发送消息 {string}")
    public void 发送消息(String text) throws Exception {
        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(ctx.getUserSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(text)))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 阻塞到 SSE 流结束（emitter.complete() 释放异步锁），再收尾读取完整事件流
        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        ctx.setAguiStreamBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @那么("应收到完整的流式对话回复")
    public void 收到完整流式回复() {
        String body = ctx.getAguiStreamBody();
        // AG-UI 生命周期事件齐全
        assertThat(body).contains("RUN_STARTED");
        assertThat(body).contains("TEXT_MESSAGE_START");
        assertThat(body).contains("TEXT_MESSAGE_CONTENT");
        assertThat(body).contains("TEXT_MESSAGE_END");
        assertThat(body).contains("RUN_FINISHED");
        // 假 Model 的固定回复进入事件流，证明 Model→Agent→AG-UI 适配链路走通
        assertThat(body).contains(CucumberSpringConfig.FixedReplyModel.REPLY);
    }

    private static String runRequest(String text) {
        // AG-UI RunAgentInput 线格式（与 CopilotKit HttpAgent 发送的一致）
        return """
                {"threadId":"%s","runId":"%s","state":{},"messages":[{"id":"%s","role":"user","content":"%s"}],"tools":[],"context":[],"forwardedProps":{}}
                """
                .formatted("bdd-thread-" + UUID.randomUUID(), "bdd-run-" + UUID.randomUUID(), UUID.randomUUID(), text);
    }
}
