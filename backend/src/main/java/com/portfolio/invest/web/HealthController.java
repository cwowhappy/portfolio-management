package com.portfolio.invest.web;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.application.market.MarketDataService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康与状态检查。
 *
 * <p>/health 为纯 liveness（进程存活 + LLM 配置状态，零外呼），供 docker healthcheck 每 20s 调用；
 * /status 为完整状态（含行情源探活）。探活消耗上游限流配额，故结果带短 TTL 缓存防匿名刷量。
 */
@RestController
@RequestMapping("/api/agent")
public class HealthController {

    /** 行情探活结果缓存 TTL。 */
    private static final Duration PROBE_CACHE_TTL = Duration.ofSeconds(30);
    /** 探活缓存键：应用级共享缓存（ApplicationCache 端口），带前缀防键冲突。 */
    private static final String PROBE_CACHE_KEY = "health:market-probe";

    private final MarketDataService market;
    private final InvestProperties props;
    private final Environment env;
    private final ApplicationCache cache;

    public HealthController(MarketDataService market, InvestProperties props, Environment env,
                            ApplicationCache cache) {
        this.market = market;
        this.props = props;
        this.env = env;
        this.cache = cache;
    }

    /** Liveness：只报进程存活与 LLM key 是否配置，不调用任何外部服务。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> llm = llm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", (boolean) llm.get("keyConfigured") ? "up" : "degraded");
        body.put("llm", llm);
        return body;
    }

    /** 完整状态：LLM 配置 + 行情源连通性（探活结果 ~30s TTL 缓存）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> llm = llm();
        Map<String, Object> marketStatus = probeMarketCached();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("llm", llm);
        body.put("market", marketStatus);
        body.put("status", (boolean) llm.get("keyConfigured") && (boolean) marketStatus.get("ok") ? "up" : "degraded");
        return body;
    }

    private Map<String, Object> llm() {
        String apiKey = env.getProperty("DEEPSEEK_API_KEY");
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider", props.getLlm().getProvider());
        llm.put("model", props.getLlm().getModel());
        llm.put("baseUrl", props.getLlm().getBaseUrl());
        llm.put("keyConfigured", apiKey != null && !apiKey.isBlank());
        return llm;
    }

    private Map<String, Object> probeMarketCached() {
        Map<String, Object> hit = cache.get(PROBE_CACHE_KEY);
        if (hit != null) {
            return hit;
        }
        Map<String, Object> probed = probeMarket();
        cache.put(PROBE_CACHE_KEY, probed, PROBE_CACHE_TTL);
        return probed;
    }

    private Map<String, Object> probeMarket() {
        Map<String, Object> marketStatus = new LinkedHashMap<>();
        try {
            long latency = market.probeQuoteLatencyMs();
            marketStatus.put("ok", true);
            marketStatus.put("latencyMs", latency);
        } catch (MarketDataException e) {
            marketStatus.put("ok", false);
            marketStatus.put("message", e.getMessage());
        }
        return marketStatus;
    }
}
