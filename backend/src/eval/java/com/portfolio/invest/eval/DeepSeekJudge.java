package com.portfolio.invest.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM-judge：直连 DeepSeek chat completions（java.net.http.HttpClient + Jackson，
 * temperature=0），rubric 从 {@code src/eval/resources/rubric/} 读入，judge 提示词模板
 * {@code rubric/judge-prompt-template.md}。断言器先跑（结构分），judge 只评主观细项。
 *
 * <p>模型名与版本记录进报告：requestedModel 是我方请求名，respondedModel 是 DeepSeek
 * 响应体回带的 model 字段（含服务端版本后缀时以它为准）。
 */
public final class DeepSeekJudge {

    /** judge 结论：解析自模型输出 JSON（pass/score/reasoning）；error 非空表示 judge 自身失败。 */
    public record Verdict(String rubric, boolean pass, int score, String reasoning,
                          String requestedModel, String respondedModel, String error) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final long timeoutMs;

    public DeepSeekJudge(String baseUrl, String model, String apiKey, long timeoutMs) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 评一次：rubricText=rubric 文件内容，templateText=judge 提示词模板，
     * substitutions=模板占位符（{{question}}/{{answer}}/{{tools}}，多轮题的 question 为逐轮拼接）。
     */
    public Verdict judge(String rubricId, String rubricText, String templateText,
                         java.util.Map<String, String> substitutions) {
        try {
            String userPrompt = templateText;
            for (var entry : substitutions.entrySet()) {
                userPrompt = userPrompt.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            userPrompt = userPrompt.replace("{{rubric}}", rubricText);

            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            body.putArray("messages")
                    .addObject().put("role", "system")
                    .put("content", "你是投研助手回答质量的评审员。严格按评分标准输出要求的 JSON，不要输出其他内容。");
            body.withArray("messages")
                    .addObject().put("role", "user").put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            // sendAsync + 限时 get：JDK HttpClient 的请求超时只覆盖"响应头到达"，头到齐后正文无限
            // 滴流（上游降级时的 keep-alive 空流）会让同步 send() 永久挂死，必须整体限时兜底
            HttpResponse<String> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                return new Verdict(rubricId, false, 0, "", model, null,
                        "judge HTTP " + response.statusCode() + ": " + snippet(response.body()));
            }
            JsonNode json = MAPPER.readTree(response.body());
            String respondedModel = json.path("model").asText(null);
            String content = json.path("choices").path(0).path("message").path("content").asText("");
            return parseVerdict(rubricId, content, model, respondedModel);
        } catch (Exception e) {
            return new Verdict(rubricId, false, 0, "", model, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 运行前探活（方案批次 4 concerns ③）：同客户端配置对端点发一次最小 chat（总限 10s，
     * max_tokens=1 只验通不通）。返回 null=端点可用；非空=失败原因——runner 收到非空即打印
     * 指引并以退出码 1 结束（同缺 key 语义：框架无法诊断时不白起 Testcontainers + 全上下文）。
     */
    public String probe() {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 1);
            body.putArray("messages")
                    .addObject().put("role", "user").put("content", "ping");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (response.statusCode() == 200) return null;
            return "HTTP " + response.statusCode() + ": " + snippet(response.body());
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 响应体片段（空白压平、截 300 字），诊断输出用。 */
    private static String snippet(String body) {
        String text = body == null ? "" : body;
        String flat = text.replaceAll("\\s+", " ");
        return flat.substring(0, Math.min(300, flat.length()));
    }

    /** 宽松解析：容忍代码围栏/前后缀文本，截取首个 {...} 块。 */
    static Verdict parseVerdict(String rubricId, String content, String requestedModel, String respondedModel) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new Verdict(rubricId, false, 0, content, requestedModel, respondedModel,
                        "judge 输出未含 JSON 块");
            }
            JsonNode verdict = MAPPER.readTree(content.substring(start, end + 1));
            boolean pass = verdict.path("pass").asBoolean(false);
            int score = verdict.path("score").asInt(0);
            String reasoning = verdict.path("reasoning").asText("");
            return new Verdict(rubricId, pass, score, reasoning, requestedModel, respondedModel, null);
        } catch (Exception e) {
            return new Verdict(rubricId, false, 0, content, requestedModel, respondedModel,
                    "judge 输出解析失败: " + e.getMessage());
        }
    }
}
