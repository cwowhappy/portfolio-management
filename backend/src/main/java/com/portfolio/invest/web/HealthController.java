package com.portfolio.invest.web;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.market.MarketDataService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查：LLM 配置状态 + 行情源连通性。 */
@RestController
@RequestMapping("/api/agent")
public class HealthController {

    private final MarketDataService market;
    private final InvestProperties props;
    private final Environment env;

    public HealthController(MarketDataService market, InvestProperties props, Environment env) {
        this.market = market;
        this.props = props;
        this.env = env;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();

        String apiKey = env.getProperty("DEEPSEEK_API_KEY");
        boolean keyConfigured = apiKey != null && !apiKey.isBlank();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider", props.getLlm().getProvider());
        llm.put("model", props.getLlm().getModel());
        llm.put("baseUrl", props.getLlm().getBaseUrl());
        llm.put("keyConfigured", keyConfigured);
        body.put("llm", llm);

        Map<String, Object> marketStatus = new LinkedHashMap<>();
        try {
            long latency = market.probeQuoteLatencyMs();
            marketStatus.put("ok", true);
            marketStatus.put("latencyMs", latency);
        } catch (MarketDataException e) {
            marketStatus.put("ok", false);
            marketStatus.put("message", e.getMessage());
        }
        body.put("market", marketStatus);
        body.put("status", keyConfigured && (boolean) marketStatus.get("ok") ? "up" : "degraded");
        return body;
    }
}
