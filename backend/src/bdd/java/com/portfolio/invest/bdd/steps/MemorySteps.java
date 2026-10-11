package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.bdd.CucumberSpringConfig;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.UserRepository;
import io.agentscope.core.message.Msg;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 记忆管理与画像个性化步骤（MS-31 BDD）：管理链走真实 HTTP（MockMvc + 登录会话，复用
 * ConversationSteps 手法）；记忆文件以 fs 直种工作区（{@code <workspace>/<userId>/}，与
 * E1 e2e 造数同口径）；画像注入断言读 {@link CucumberSpringConfig.FixedReplyModel#RECORDED_CALLS}
 * 录制的模型输入（只录不改，守卫口径同 AguiStreamIntegrationTest）。
 */
public class MemorySteps {

    /** 策展层偏好行（全角逗号）；日账本变体行仅差半角逗号——归一化（全角→半角）后同源。 */
    private static final String PREFERENCE_LINE = "- 偏好白酒股，重点跟踪贵州茅台";
    private static final String VARIANT_LINE = "- 偏好白酒股,重点跟踪贵州茅台";
    /** 无关行：行体 ≥12 字符但与被删行无子串关系，两层同步不应清除。 */
    private static final String UNRELATED_LINE = "- 定期跟踪沪深300指数估值分位";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ScenarioContext ctx;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InvestProperties investProperties;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** 本场景 fs 直种的用户工作区目录（@After 清理用；未直种场景为 null，钩子空转）。 */
    private Path seededUserDir;

    private int purgedLedgerLines = -1;

    @Before
    public void resetModelRecording() {
        CucumberSpringConfig.FixedReplyModel.RECORDED_CALLS.clear();
    }

    @After
    public void cleanupSeededWorkspace() {
        if (seededUserDir == null) {
            return;
        }
        // 场景自清理：删除本场景直种的用户目录（best effort，失败不影响结果判读）
        try (Stream<Path> paths = Files.walk(seededUserDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // 清理失败仅残留 gitignore 目录，无害
                }
            });
        } catch (java.io.IOException ignored) {
            // 同上
        }
        seededUserDir = null;
    }

    @假如("该用户工作区已落盘记忆：策展层含偏好行，日账本含同义变体行与无关行")
    public void 落盘记忆文件() throws Exception {
        Long userId = currentUserId();
        seededUserDir = Paths.get(investProperties.getMcp().getHarness().getWorkspace())
                .resolve(String.valueOf(userId));
        Files.createDirectories(seededUserDir.resolve("memory"));
        Files.writeString(seededUserDir.resolve("MEMORY.md"), """
                # MEMORY.md — Curated Long-Term Memory

                ## 偏好
                %s
                """.formatted(PREFERENCE_LINE), StandardCharsets.UTF_8);
        // 变体行落在 flush 节（半角逗号变体）；无关行落在外部事件节
        Files.writeString(seededUserDir.resolve("memory").resolve("2026-10-10.md"), """
                ## Memory Flush — 2026-10-09T13:58:45Z
                %s

                ## 外部事件摘要 — 2026-10-10（2026-10-09T21:07 ~ 2026-10-10T21:07）
                %s
                """.formatted(VARIANT_LINE, UNRELATED_LINE), StandardCharsets.UTF_8);
        // 造数后视图可见：偏好行在策展层、变体行与无关行在日账本
        JsonNode view = memoryView();
        assertThat(linesOf(view.path("curated").path("lines"))).contains(PREFERENCE_LINE);
        assertThat(allLedgerLines(view)).contains(VARIANT_LINE, UNRELATED_LINE);
    }

    @当("该用户删除策展层偏好行")
    public void 删除策展层偏好行() throws Exception {
        JsonNode view = memoryView();
        List<String> lines = linesOf(view.path("curated").path("lines"));
        int lineIndex = lines.indexOf(PREFERENCE_LINE);
        assertThat(lineIndex).as("策展层应含偏好行供删除，实际：%s", lines).isGreaterThanOrEqualTo(0);
        String baseHash = view.path("curated").path("hash").asText();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("lineIndex", lineIndex);
        body.put("expectLine", PREFERENCE_LINE);
        body.put("baseHash", baseHash);
        MvcResult result = mockMvc.perform(delete("/api/memory/curated")
                        .session(ctx.getUserSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        purgedLedgerLines = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("purgedLedgerLines").asInt();
    }

    @那么("删除回执报告日账本同步清除 {int} 行")
    public void 删除回执报告清除行数(int expected) {
        assertThat(purgedLedgerLines).isEqualTo(expected);
    }

    @那么("重新加载记忆视图时偏好行与变体行不再出现，无关行保留")
    public void 重读视图删除不复活() throws Exception {
        JsonNode view = memoryView();
        // 策展层：偏好行不复活（标题与节头保留）
        assertThat(linesOf(view.path("curated").path("lines")))
                .doesNotContain(PREFERENCE_LINE)
                .contains("# MEMORY.md — Curated Long-Term Memory", "## 偏好");
        // 日账本：同源变体行被两层同步清除，无关行保留
        assertThat(allLedgerLines(view)).doesNotContain(VARIANT_LINE).contains(UNRELATED_LINE);
    }

    @假如("个股基本面快照有个股 {string} {string}")
    public void 种子个股快照(String stockCode, String stockName) {
        // 自选添加的存在性校验读股票快照——本场景专用个股（与 screening 场景的 600519/601398
        // 错开，避免跨场景重复种子同一 (trading_day, stock_code) 行破坏其 containsExactly 断言）
        jdbcTemplate.update(
                "INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, "
                        + "dividend_yield, total_mv, circ_mv, turnover_rate) VALUES (?,?,?,?,?,?,?,?,?)",
                java.sql.Date.valueOf("2026-08-27"), stockCode, stockName, new java.math.BigDecimal("22.5"),
                new java.math.BigDecimal("7.8"), new java.math.BigDecimal("2.1"),
                new java.math.BigDecimal("2100000000000"), new java.math.BigDecimal("2100000000000"),
                new java.math.BigDecimal("0.35"));
    }

    @假如("该用户自选股加入 {string}")
    public void 自选股加入(String stockCode) throws Exception {
        mockMvc.perform(post("/api/watchlist").session(ctx.getUserSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"" + stockCode + "\"}"))
                .andExpect(status().isNoContent());
    }

    @当("该用户保存画像授权（仅自选股段开启）")
    public void 保存画像授权仅自选股() throws Exception {
        mockMvc.perform(put("/api/memory/profile-config").session(ctx.getUserSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"style\":false,\"industry\":false,\"watchlist\":true,\"holdings\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchlist.enabled").value(true))
                .andExpect(jsonPath("$.style.enabled").value(false));
    }

    @那么("模型上下文含已授权的画像自选股段 {string}")
    public void 模型上下文含画像自选股段(String stockCode) {
        // 推理轮的模型输入：剔除 harness 记忆归档调用（tools=null 直呼或提示词含
        // "Extract NEW memories"），守卫口径同 AguiStreamIntegrationTest
        String inferenceInput = CucumberSpringConfig.FixedReplyModel.RECORDED_CALLS.stream()
                .filter(call -> call.tools() != null)
                .filter(call -> !containsText(call.messages(), "Extract NEW memories"))
                .flatMap(call -> call.messages().stream())
                .map(Msg::getTextContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        // 已授权段注入系统提示（含画像包裹标签与自选代码）；未授权段（风格）不注入
        assertThat(inferenceInput).contains("<user_profile>").contains("<watchlist>").contains(stockCode);
        assertThat(inferenceInput).doesNotContain("<risk_profile>");
    }

    // ———— 辅助 ————

    private JsonNode memoryView() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/memory").session(ctx.getUserSession()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<String> linesOf(JsonNode linesNode) {
        List<String> lines = new ArrayList<>();
        linesNode.forEach(l -> lines.add(l.asText()));
        return lines;
    }

    /** 全部日账本节的行文本摊平（节头不入两层同步清除口径，只校 bullet 行）。 */
    private List<String> allLedgerLines(JsonNode view) {
        List<String> lines = new ArrayList<>();
        for (JsonNode ledger : view.path("ledgers")) {
            for (JsonNode section : ledger.path("sections")) {
                section.path("lines").forEach(l -> lines.add(l.asText()));
            }
        }
        return lines;
    }

    private static boolean containsText(List<Msg> messages, String needle) {
        return messages.stream().anyMatch(m -> {
            String text = m.getTextContent();
            return text != null && text.contains(needle);
        });
    }

    private Long currentUserId() {
        return userRepository.findByUsername(ctx.getUsername()).orElseThrow().id();
    }
}
