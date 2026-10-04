package com.portfolio.invest.agent;

import com.portfolio.invest.application.mcp.McpTokenRotatedEvent;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端池：按 endpoint.id 缓存 {@link McpClientWrapper}（key 维持 endpoint.id）。
 *
 * <p>生命周期三件套（B3）：① 管理员轮换 token 后经 {@link McpTokenRotatedEvent} 按 provider
 * 驱逐全部相关缓存客户端并 close，下一次 acquire 以新 token 重建——即时生效无需重启；
 * ② {@code initialize().block(toolTimeout)} 与装配侧 {@code listTools().block(toolTimeout)}
 * 把 invest.mcp.toolTimeout 从死配置接线为真实操作超时兜底；③ {@code @PreDestroy} 关闭
 * 全部 wrapper。
 *
 * <p>并发：构建发生在 {@code get}/{@code putIfAbsent} 之外——不在 ConcurrentHashMap 映射函数
 * （持 bin 锁）内做网络 I/O；并发构建竞争以 putIfAbsent 裁决，败者关闭自己刚建的实例。
 */
@Component
public class McpClientPool {
    private static final Logger log = LoggerFactory.getLogger(McpClientPool.class);

    private final Map<Long, McpClientWrapper> clients = new ConcurrentHashMap<>();
    /** 反向索引：endpointId → providerId，token 轮换事件按 provider 驱逐用。 */
    private final Map<Long, Long> endpointToProvider = new ConcurrentHashMap<>();
    private final Duration connectTimeout;
    private final Duration toolTimeout;

    public McpClientPool(InvestProperties props) {
        this.connectTimeout = props.getMcp().getConnectTimeout();
        this.toolTimeout = props.getMcp().getToolTimeout();
    }

    public McpClientWrapper acquire(McpProvider provider, McpEndpoint endpoint, String token) {
        McpClientWrapper cached = clients.get(endpoint.id());
        if (cached != null) {
            return cached;
        }
        McpClientWrapper built = build(provider, endpoint, token);
        McpClientWrapper raced = clients.putIfAbsent(endpoint.id(), built);
        if (raced != null) {
            closeQuietly(built); // 并发竞争败者：弃用自己刚建的实例
            return raced;
        }
        endpointToProvider.put(endpoint.id(), provider.id());
        return built;
    }

    /** token 轮换即时生效：驱逐该 provider 全部 endpoint 的缓存客户端并 close（弱一致遍历，驱逐自身键安全）。 */
    @EventListener
    public void onTokenRotated(McpTokenRotatedEvent event) {
        endpointToProvider.forEach((endpointId, providerId) -> {
            if (providerId.equals(event.providerId())) {
                endpointToProvider.remove(endpointId);
                McpClientWrapper removed = clients.remove(endpointId);
                if (removed != null) {
                    log.info("MCP token 轮换：驱逐 endpoint {} 的缓存客户端", endpointId);
                    closeQuietly(removed);
                }
            }
        });
    }

    /** 应用关停：关闭全部缓存客户端（单个失败不阻断其余）。 */
    @PreDestroy
    public void shutdown() {
        clients.values().forEach(this::closeQuietly);
        clients.clear();
        endpointToProvider.clear();
    }

    private McpClientWrapper build(McpProvider provider, McpEndpoint endpoint, String token) {
        McpClientBuilder builder = McpClientBuilder.create(provider.code())
                .streamableHttpTransport(endpoint.url()).timeout(connectTimeout);
        if (provider.authType() == AuthType.HEADER) {
            builder.header(provider.authHeader(), token);
        } else if (provider.authType() == AuthType.BEARER) {
            builder.header("Authorization", "Bearer " + token);
        }
        McpClientWrapper client = builder.buildSync();
        client.initialize().block(toolTimeout);
        return client;
    }

    private void closeQuietly(McpClientWrapper client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("MCP 客户端关闭失败：{}", e.getMessage());
        }
    }
}
