package com.portfolio.invest.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI 线格式装配（#25）：启动期把全局 JsonCodec 换为 AguiEventNonNullCodec。
 * encoder 每次编码都经 JsonUtils.getJsonCodec() 动态获取，本配置只需早于第一个 /agui/run
 * 请求生效（Spring 上下文刷新期构造，天然满足）。
 */
@Configuration
class AguiWireJsonConfig {

    AguiWireJsonConfig() {
        JacksonJsonCodec fallback = new JacksonJsonCodec();
        JsonUtils.setJsonCodec(new AguiEventNonNullCodec(
                fallback,
                new JacksonJsonCodec(fallback.getObjectMapper()
                        .copy()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL))));
    }
}
