package com.portfolio.invest.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报告写出：JSON（机读：每题每维度 pass/fail + 事件流原文 rawBody，失败 case 可回放）+
 * Markdown（人读：维度得分汇总、失败摘要、judge 摘录、可选 --compare 差异章节）。
 * 输出 backend/build/reports/eval-agent/eval-report.{json,md}。
 */
public final class ReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 运行元数据（报告头）。 */
    public record Meta(String mode, String subjectModel, String subjectBaseUrl, String judgeRequestedModel,
                       String judgeRespondedModel, int perTurnTimeoutMs) {}

    public record Written(Path json, Path md) {}

    private final Path outputDir;

    public ReportWriter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public Written write(List<QuestionOutcome> outcomes, Meta meta, Path compareWith) throws IOException {
        ObjectNode report = buildJson(outcomes, meta, compareWith);
        Files.createDirectories(outputDir);
        Path json = outputDir.resolve("eval-report.json");
        Path md = outputDir.resolve("eval-report.md");
        Files.writeString(json, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        Files.writeString(md, buildMarkdown(report, outcomes), StandardCharsets.UTF_8);
        return new Written(json, md);
    }

    // ———— JSON 装配 ————

    private ObjectNode buildJson(List<QuestionOutcome> outcomes, Meta meta, Path compareWith) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", "eval-agent-report/1");
        root.put("generatedAt", ZonedDateTime.now().toString());
        root.put("mode", meta.mode());
        ObjectNode subject = root.putObject("subjectModel");
        subject.put("provider", "deepseek");
        subject.put("model", meta.subjectModel());
        subject.put("baseUrl", meta.subjectBaseUrl());
        ObjectNode judge = root.putObject("judge");
        judge.put("requestedModel", meta.judgeRequestedModel());
        if (meta.judgeRespondedModel() != null) judge.put("respondedModel", meta.judgeRespondedModel());
        root.put("perTurnTimeoutMs", meta.perTurnTimeoutMs());

        root.set("summary", summaryNode(outcomes));
        ArrayNode questions = root.putArray("questions");
        for (QuestionOutcome o : outcomes) questions.add(questionNode(o));
        if (compareWith != null) root.set("compare", compareNode(outcomes, compareWith));
        return root;
    }

    private ObjectNode summaryNode(List<QuestionOutcome> outcomes) {
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("total", outcomes.size());
        for (QuestionOutcome.Status s : QuestionOutcome.Status.values()) {
            summary.put(s.name().toLowerCase(), outcomes.stream()
                    .filter(o -> o.status() == s).count());
        }
        // 维度汇总（跨题计数；judge 单列）
        Map<String, ObjectNode> byDimension = new LinkedHashMap<>();
        for (QuestionOutcome o : outcomes) {
            for (AssertionEngine.DimensionResult d : o.dimensions()) {
                ObjectNode node = byDimension.computeIfAbsent(d.name(), k -> {
                    ObjectNode n = MAPPER.createObjectNode();
                    n.put("pass", 0).put("fail", 0).put("skipped", 0);
                    return n;
                });
                node.put(d.status().name().toLowerCase(), node.path(d.status().name().toLowerCase()).asInt() + 1);
            }
        }
        ObjectNode dimensions = summary.putObject("dimensions");
        byDimension.forEach(dimensions::set);
        int judgePass = (int) outcomes.stream()
                .filter(o -> o.judge() != null && o.judge().error() == null && o.judge().pass()).count();
        int judgeRan = (int) outcomes.stream()
                .filter(o -> o.judge() != null && o.judge().error() == null).count();
        summary.put("judgePass", judgePass).put("judgeRan", judgeRan);
        return summary;
    }

    private ObjectNode questionNode(QuestionOutcome o) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", o.question().id());
        node.put("category", o.question().category());
        node.put("mode", o.question().mode());
        node.put("status", o.status().name());
        if (o.skipReason() != null) node.put("skipReason", o.skipReason());
        if (o.error() != null) node.put("error", o.error());
        if (o.username() != null) node.put("username", o.username());
        if (o.threadId() != null) node.put("threadId", o.threadId());
        node.put("durationMs", o.durationMs());
        node.put("eventCount", o.eventCount());
        ArrayNode turns = node.putArray("turns");
        for (AguiDriver.SseTurn turn : o.turns()) {
            ObjectNode t = turns.addObject();
            t.put("index", turn.index());
            t.put("durationMs", turn.durationMs());
            t.put("eventCount", turn.events().size());
            if (turn.error() != null) t.put("error", turn.error());
            // 事件流原文：失败回放的依据（含 keep-alive 注释行在内的完整响应体）
            t.put("rawBody", turn.rawBody());
        }
        ArrayNode dimensions = node.putArray("dimensions");
        for (AssertionEngine.DimensionResult d : o.dimensions()) {
            ObjectNode dn = dimensions.addObject();
            dn.put("name", d.name());
            dn.put("expected", d.expected());
            dn.put("actual", d.actual());
            dn.put("status", d.status().name());
            dn.put("detail", d.detail());
        }
        if (o.judge() != null) {
            ObjectNode j = node.putObject("judge");
            j.put("rubric", o.judge().rubric());
            j.put("pass", o.judge().pass());
            j.put("score", o.judge().score());
            j.put("reasoning", o.judge().reasoning());
            j.put("requestedModel", o.judge().requestedModel());
            if (o.judge().respondedModel() != null) j.put("respondedModel", o.judge().respondedModel());
            if (o.judge().error() != null) j.put("error", o.judge().error());
        }
        AguiEventExtractor.TokenUsage usage = o.tokenUsage();
        if (usage != null && usage.snapshots() > 0) {
            ObjectNode u = node.putObject("tokenUsage");
            if (usage.inputTokens() != null) u.put("inputTokens", usage.inputTokens());
            if (usage.outputTokens() != null) u.put("outputTokens", usage.outputTokens());
            if (usage.totalTokens() != null) u.put("totalTokens", usage.totalTokens());
            u.put("snapshots", usage.snapshots());
        }
        if (o.answerText() != null && !o.answerText().isBlank()) {
            node.put("answerText", o.answerText());
        }
        return node;
    }

    // ———— 与上次报告的对比（可选 --compare） ————

    private ObjectNode compareNode(List<QuestionOutcome> outcomes, Path previous) {
        ObjectNode compare = MAPPER.createObjectNode();
        compare.put("previousReport", previous.toString());
        JsonNode prev;
        try {
            prev = MAPPER.readTree(previous.toFile());
        } catch (IOException e) {
            compare.put("error", "上次报告读取失败: " + e.getMessage());
            return compare;
        }
        Map<String, JsonNode> prevQuestions = new LinkedHashMap<>();
        prev.path("questions").forEach(q -> prevQuestions.put(q.path("id").asText(), q));
        Set<String> currentIds = new LinkedHashSet<>();
        ArrayNode changes = compare.putArray("questionChanges");
        for (QuestionOutcome o : outcomes) {
            currentIds.add(o.question().id());
            JsonNode before = prevQuestions.get(o.question().id());
            if (before == null) {
                addChange(changes, o.question().id(), "-", o.status().name(), "新题");
                continue;
            }
            String prevStatus = before.path("status").asText("?");
            List<String> dimChanges = new ArrayList<>();
            Map<String, String> prevDims = new LinkedHashMap<>();
            before.path("dimensions").forEach(d ->
                    prevDims.put(d.path("name").asText(), d.path("status").asText()));
            for (AssertionEngine.DimensionResult d : o.dimensions()) {
                String was = prevDims.get(d.name());
                if (was != null && !was.equals(d.status().name())) {
                    dimChanges.add(d.name() + ": " + was + " -> " + d.status().name());
                }
            }
            int prevScore = before.path("judge").path("score").asInt(0);
            String judgeChange = o.judge() != null && prevScore != o.judge().score()
                    ? "judge score: " + prevScore + " -> " + o.judge().score() : null;
            String reason = String.join("; ", dimChanges);
            if (judgeChange != null) reason = reason.isEmpty() ? judgeChange : reason + "; " + judgeChange;
            if (!prevStatus.equals(o.status().name()) || !dimChanges.isEmpty() || judgeChange != null) {
                addChange(changes, o.question().id(), prevStatus, o.status().name(),
                        reason.isEmpty() ? "状态/维度无漂移" : reason);
            }
        }
        for (String id : prevQuestions.keySet()) {
            if (!currentIds.contains(id)) {
                addChange(changes, id, prevQuestions.get(id).path("status").asText("?"), "REMOVED", "题已移除");
            }
        }
        return compare;
    }

    private void addChange(ArrayNode changes, String id, String before, String after, String reason) {
        ObjectNode c = changes.addObject();
        c.put("id", id);
        c.put("before", before);
        c.put("after", after);
        c.put("reason", reason);
    }

    // ———— Markdown 装配 ————

    private String buildMarkdown(ObjectNode report, List<QuestionOutcome> outcomes) {
        StringBuilder md = new StringBuilder();
        JsonNode summary = report.path("summary");
        md.append("# Agent 效果评估报告\n\n");
        md.append("- 生成时间: ").append(TS.format(ZonedDateTime.now())).append('\n');
        md.append("- 模式: ").append(report.path("mode").asText()).append('\n');
        md.append("- 被评模型（真实 LLM）: deepseek / ")
                .append(report.path("subjectModel").path("model").asText())
                .append(" @ ").append(report.path("subjectModel").path("baseUrl").asText()).append('\n');
        md.append("- judge 模型: ").append(report.path("judge").path("requestedModel").asText());
        JsonNode responded = report.path("judge").path("respondedModel");
        if (!responded.isMissingNode()) md.append("（服务端回报 ").append(responded.asText()).append('）');
        md.append("，temperature=0\n");
        md.append("- 单轮超时: ").append(report.path("perTurnTimeoutMs").asText()).append(" ms\n\n");
        md.append("## 总览\n\n");
        md.append("| 题目 | 类别 | 状态 | 维度通过 | judge 评分 | 耗时 | token(out) |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (QuestionOutcome o : outcomes) {
            long passed = o.dimensions().stream()
                    .filter(d -> d.status() == AssertionEngine.Status.PASS).count();
            long evaluated = o.dimensions().stream()
                    .filter(d -> d.status() != AssertionEngine.Status.SKIPPED).count();
            String judgeCell = o.judge() == null ? "-"
                    : o.judge().score() + (o.judge().pass() ? " ✓" : " ✗");
            String tokenCell = o.tokenUsage() != null && o.tokenUsage().outputTokens() != null
                    ? String.valueOf(o.tokenUsage().outputTokens()) : "-";
            md.append("| ").append(o.question().id())
                    .append(" | ").append(o.question().category())
                    .append(" | ").append(o.status())
                    .append(" | ").append(passed).append('/').append(evaluated)
                    .append(" | ").append(judgeCell)
                    .append(" | ").append(o.durationMs() / 1000).append("s")
                    .append(" | ").append(tokenCell).append(" |\n");
        }
        md.append("\n汇总: total=").append(summary.path("total").asInt())
                .append(" pass=").append(summary.path("pass").asInt())
                .append(" fail=").append(summary.path("fail").asInt())
                .append(" skipped=").append(summary.path("skipped").asInt())
                .append(" error=").append(summary.path("error").asInt())
                .append("；judgePass=").append(summary.path("judgePass").asInt())
                .append('/').append(summary.path("judgeRan").asInt()).append('\n');

        md.append("\n## 维度汇总\n\n| 维度 | PASS | FAIL | SKIPPED |\n|---|---|---|---|\n");
        summary.path("dimensions").fields().forEachRemaining(e ->
                md.append("| ").append(e.getKey())
                        .append(" | ").append(e.getValue().path("pass").asInt())
                        .append(" | ").append(e.getValue().path("fail").asInt())
                        .append(" | ").append(e.getValue().path("skipped").asInt()).append(" |\n"));

        md.append("\n## 题目详情\n");
        for (QuestionOutcome o : outcomes) {
            md.append("\n### ").append(o.question().id())
                    .append("（").append(o.question().category()).append(" / ")
                    .append(o.question().mode()).append("）— ")
                    .append(o.status()).append('\n');
            if (o.skipReason() != null) md.append("- 跳过原因: ").append(o.skipReason()).append('\n');
            if (o.error() != null) md.append("- 异常: ").append(o.error()).append('\n');
            md.append("- 用户: ").append(o.username()).append("，threadId: ").append(o.threadId())
                    .append("，事件数: ").append(o.eventCount()).append('\n');
            if (!o.turns().isEmpty()) {
                md.append("- 轮次: ");
                for (AguiDriver.SseTurn t : o.turns()) {
                    md.append("#").append(t.index()).append(' ')
                            .append(t.durationMs() / 1000).append("s");
                    if (t.error() != null) md.append("（失败）");
                    md.append("，");
                }
                md.append('\n');
            }
            if (o.judge() != null) {
                md.append("- judge[").append(o.judge().rubric()).append("]: score=")
                        .append(o.judge().score())
                        .append(o.judge().pass() ? " ✓" : " ✗");
                if (o.judge().error() != null) md.append("（judge 异常: ").append(o.judge().error()).append('）');
                md.append('\n');
                if (!o.judge().reasoning().isBlank()) {
                    md.append("  - ").append(o.judge().reasoning().replace("\n", "\n  - ")).append('\n');
                }
            }
            md.append("\n| 维度 | 预期 | 实际 | 结果 |\n|---|---|---|---|\n");
            for (AssertionEngine.DimensionResult d : o.dimensions()) {
                md.append("| ").append(d.name())
                        .append(" | ").append(escapeCell(d.expected()))
                        .append(" | ").append(escapeCell(truncate(d.actual(), 200)))
                        .append(" | ").append(d.status()).append(" |\n");
            }
            // 失败摘要：FAIL 维度 + judge 失败置顶可见（事件流原文见 JSON 报告 turns[].rawBody）
            List<String> failures = o.dimensions().stream()
                    .filter(d -> d.status() == AssertionEngine.Status.FAIL)
                    .map(d -> d.name() + ": " + d.detail() + "（实际: " + truncate(d.actual(), 120) + "）")
                    .toList();
            if (!failures.isEmpty()) {
                md.append("\n失败维度:\n");
                failures.forEach(f -> md.append("- ").append(f).append('\n'));
            }
            if (o.judge() != null && !o.judge().pass() && o.judge().error() == null) {
                md.append("\njudge 未通过，摘录回答（前 500 字）:\n\n> ")
                        .append(truncate(o.answerText() == null ? "" : o.answerText(), 500)
                                .replace("\n", "\n> ")).append('\n');
            }
        }

        JsonNode compare = report.path("compare");
        if (!compare.isMissingNode()) {
            md.append("\n## 与上次报告对比\n\n");
            md.append("- 上次报告: ").append(compare.path("previousReport").asText()).append('\n');
            JsonNode changes = compare.path("questionChanges");
            if (changes.isEmpty()) {
                md.append("- 无状态/维度/judge 漂移\n");
            } else {
                for (JsonNode c : changes) {
                    md.append("- ").append(c.path("id").asText()).append(": ")
                            .append(c.path("before").asText()).append(" -> ")
                            .append(c.path("after").asText())
                            .append("（").append(c.path("reason").asText()).append("）\n");
                }
            }
        }

        md.append("\n---\n诊断仪报告：不设通过率门槛、不挂 CI；事件流原文与失败回放数据见同目录 eval-report.json。\n");
        return md.toString();
    }

    private static String escapeCell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
