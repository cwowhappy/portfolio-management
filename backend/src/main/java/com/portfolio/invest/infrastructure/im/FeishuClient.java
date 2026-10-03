package com.portfolio.invest.infrastructure.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.config.InvestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 飞书自建应用客户端（feishu-messaging P1）：tenant_access_token 管理（80% TTL 提前刷新）
 * + im/v1/messages 卡片发送。尽力而为：一切失败仅记 WARN 返回 false，绝不抛出（NFR-2）。
 * 飞书契约：HTTP 200 + body code≠0 仍是失败；token 失效码 99991663/99991664 → 失效重取后重试一次。
 */
@Component
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    private final InvestProperties props;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<CachedToken> token = new AtomicReference<>();

    private record CachedToken(String value, long refreshAtMs) {}

    @Autowired
    public FeishuClient(InvestProperties props) {
        this.props = props;
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(props.getIm().getApiBase()).requestFactory(factory).build();
    }

    /** 测试构造器：直接提供 RestClient（MockRestServiceServer）。 */
    FeishuClient(InvestProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    public boolean sendCard(String chatId, String title, String template, List<String> bodyLines) {
        InvestProperties.Im im = props.getIm();
        if (im.getAppId() == null || im.getAppId().isBlank() || im.getAppSecret() == null
                || im.getAppSecret().isBlank() || chatId == null || chatId.isBlank()) {
            log.warn("飞书未配置（appId/appSecret/chatId 缺失），跳过发送：{}", title);
            return false;
        }
        try {
            return postMessage("/open-apis/im/v1/messages?receive_id_type=chat_id",
                    Map.of("receive_id", chatId, "msg_type", "interactive",
                            "content", buildCardJson(title, template, bodyLines)), title);
        } catch (Exception e) {
            log.warn("飞书卡片构造失败（title={}）：{}", title, e.getMessage());
            return false;
        }
    }

    /**
     * 单发卡片（intelligence D10/P4 订阅单发）：receive_id_type=open_id，门禁仅
     * appId/appSecret + openId——**不要求群 chatId 配置**（未建群也可用单聊推送）。
     */
    public boolean sendCardByOpenId(String openId, String title, String template, List<String> bodyLines) {
        InvestProperties.Im im = props.getIm();
        if (im.getAppId() == null || im.getAppId().isBlank() || im.getAppSecret() == null
                || im.getAppSecret().isBlank() || openId == null || openId.isBlank()) {
            log.warn("飞书未配置（appId/appSecret/openId 缺失），跳过单发：{}", title);
            return false;
        }
        try {
            return postMessage("/open-apis/im/v1/messages?receive_id_type=open_id",
                    Map.of("receive_id", openId, "msg_type", "interactive",
                            "content", buildCardJson(title, template, bodyLines)), title);
        } catch (Exception e) {
            log.warn("飞书卡片构造失败（title={}）：{}", title, e.getMessage());
            return false;
        }
    }

    /** 交互卡片 JSON（config/header/单 div 正文）：群推与单发共用同一 schema。 */
    private String buildCardJson(String title, String template, List<String> bodyLines)
            throws JsonProcessingException {
        return mapper.writeValueAsString(Map.of(
                "config", Map.of("wide_screen_mode", true),
                "header", Map.of("title", Map.of("tag", "plain_text", "content", title),
                        "template", template),
                "elements", List.of(Map.of("tag", "div",
                        "text", Map.of("tag", "lark_md", "content", String.join("\n", bodyLines))))));
    }

    /** P2 对话回复：reply 到指定消息，纯文本。 */
    public boolean sendReply(String messageId, String text) {
        InvestProperties.Im im = props.getIm();
        if (im.getAppId() == null || im.getAppId().isBlank() || im.getAppSecret() == null
                || im.getAppSecret().isBlank() || messageId == null || messageId.isBlank()) {
            log.warn("飞书未配置（appId/appSecret 缺失），跳过回复");
            return false;
        }
        try {
            String content = mapper.writeValueAsString(Map.of("text", text));
            return postMessage("/open-apis/im/v1/messages/" + messageId + "/reply",
                    Map.of("msg_type", "text", "content", content), "reply:" + messageId);
        } catch (Exception e) {
            log.warn("飞书回复构造失败（messageId={}）：{}", messageId, e.getMessage());
            return false;
        }
    }

    /** 公共发送：token 获取 + 2 次尝试（token 失效码 99991663/99991664 重取一次）+ 200/body-code 契约。 */
    private boolean postMessage(String path, Map<String, Object> body, String logTag) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            String tok = token();
            if (tok == null) {
                return false;
            }
            try {
                JsonNode resp = restClient.post().uri(path)
                        .header("Authorization", "Bearer " + tok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                int code = resp == null ? -1 : resp.path("code").asInt(-1);
                if (code == 0) {
                    return true;
                }
                if (code == 99991663 || code == 99991664) {
                    token.set(null);
                    continue;
                }
                log.warn("飞书发送失败 code={} msg={}（{}）", code,
                        resp == null ? "-" : resp.path("msg").asText(), logTag);
                return false;
            } catch (Exception e) {
                log.warn("飞书发送异常（第 {} 次，{}）：{}", attempt, logTag, e.getMessage());
            }
        }
        return false;
    }

    private String token() {
        CachedToken cached = token.get();
        long now = System.currentTimeMillis(); // infrastructure 层（CodingConventions 仅限 domain/application）
        if (cached != null && now < cached.refreshAtMs()) {
            return cached.value();
        }
        try {
            JsonNode resp = restClient.post().uri("/open-apis/auth/v3/tenant_access_token/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("app_id", props.getIm().getAppId(),
                            "app_secret", props.getIm().getAppSecret()))
                    .retrieve().body(JsonNode.class);
            if (resp == null || resp.path("code").asInt(-1) != 0) {
                log.warn("飞书 token 获取失败：{}", resp == null ? "空响应" : resp.toString());
                return null;
            }
            long expireMs = resp.path("expire").asLong(7200) * 1000;
            token.set(new CachedToken(resp.path("tenant_access_token").asText(),
                    now + (long) (expireMs * 0.8)));
            return token.get().value();
        } catch (Exception e) {
            log.warn("飞书 token 获取异常：{}", e.getMessage());
            return null;
        }
    }
}
