package com.portfolio.invest.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * mcp 题的扩展源底座（McpHitlIntegrationTest 同款形态搬入评估源集）：内嵌 Tomcat + 官方 SDK
 * streamable HTTP 的桩 MCP server（懒启动、全库共享一个），seed mcp_provider/mcp_endpoint/
 * mcp_user_config 后由生产 UserToolkitFactory 经 McpClientPool 真实 HTTP 装配。
 *
 * <p>工具面（与 McpHitl 口径一致）：
 * <ul>
 *   <li>write_note——不标 readOnlyHint → 缺省视为写，触发 permission_confirm 审批中断（HITL 题）；
 *       写入 build/eval-agent/mcp-notes/（中断即停时不应有文件落盘）</li>
 *   <li>read_note——readOnlyHint=true → 只读放行直接执行，返回固定样例笔记</li>
 *   <li>get_research_report——readOnlyHint=true 的数据兜底工具（研报领域内置不覆盖，
 *       「扩展源兜底分工」题的观察点），按代码返回固定研报摘要</li>
 * </ul>
 *
 * <p>MockMvc 驱动 /agui/run 无真实 HTTP 栈，而 MCP 调用需真 socket——MCP servlet 单独起随机
 * 端口内嵌 Tomcat（同 McpHitl 的技术方案；请求体编码须显式 UTF-8，裸 Tomcat 默认 ISO-8859-1
 * 会毁掉中文参数）。
 */
public final class EvalMcpSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROVIDER_CODE = "eval-mcp-source";
    private static final Path NOTE_DIR = Path.of("build", "eval-agent", "mcp-notes");

    private static volatile Tomcat tomcat;
    private static volatile McpSyncServer mcpServer;

    private EvalMcpSupport() {}

    /** 懒启动（首个 mcp 题触发）；已启动则直接返回 /mcp 端点 URL。 */
    public static synchronized String serverUrl() {
        if (tomcat != null) return mcpUrl();
        try {
            Files.createDirectories(NOTE_DIR);

            HttpServletStreamableServerTransportProvider transport =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint("/mcp")
                            .jsonMapper(new JacksonMcpJsonMapper(MAPPER))
                            .build();
            mcpServer = McpServer.sync(transport)
                    .serverInfo("eval-mcp-source", "1.0")
                    .tool(writeNoteTool(), (exchange, args) -> {
                        // BiFunction#apply 不声明受检异常（javac 核对 0.17.2），IOException 只能包装抛出
                        try {
                            Path file = NOTE_DIR.resolve(String.valueOf(
                                    args.getOrDefault("file", "note.md")).replaceAll("[^a-zA-Z0-9._-]", "_"));
                            Files.writeString(file, String.valueOf(args.get("content")), StandardCharsets.UTF_8);
                            return new McpSchema.CallToolResult("written: " + file.getFileName(), false);
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .tool(readNoteTool(), (exchange, args) ->
                            new McpSchema.CallToolResult(
                                    "样例笔记：贵州茅台 2026-09-14 收盘 1735.86 元，涨 1.52%（评估桩固定内容）",
                                    false))
                    .tool(researchReportTool(), (exchange, args) ->
                            new McpSchema.CallToolResult(researchText(String.valueOf(args.get("code"))), false))
                    .build();

            tomcat = new Tomcat();
            tomcat.setBaseDir(Files.createTempDirectory("eval-mcp-tomcat").toString());
            tomcat.setPort(0); // 随机端口，避免与本机服务冲突
            tomcat.getConnector(); // 触发 connector 创建
            Context ctx = tomcat.addContext("", null);
            // SDK servlet 经 getReader() 读请求体且不 setCharacterEncoding；须显式 UTF-8（同 McpHitl）
            ctx.setRequestCharacterEncoding(StandardCharsets.UTF_8.name());
            Tomcat.addServlet(ctx, "mcp", transport).addMapping("/mcp");
            tomcat.start();
            Runtime.getRuntime().addShutdownHook(new Thread(EvalMcpSupport::shutdown));
            return mcpUrl();
        } catch (Exception e) {
            throw new IllegalStateException("评估内嵌 MCP server 启动失败", e);
        }
    }

    /** 停内嵌 server（幂等；runner finally 与 JVM shutdown hook 双保险）。 */
    public static synchronized void shutdown() {
        try {
            if (mcpServer != null) mcpServer.closeGracefully();
        } catch (Exception ignored) {
            // 关闭失败不影响评估收尾
        } finally {
            mcpServer = null;
        }
        try {
            if (tomcat != null) tomcat.stop();
        } catch (Exception ignored) {
            // 同上
        } finally {
            tomcat = null;
        }
    }

    /**
     * 为用户启用扩展源：幂等 seed provider(NONE)/endpoint(内嵌 Tomcat) + 用户配置
     * （disabled_tools 空）。须在用户注册后、run 前调用——UserToolkitFactory 每次运行
     * 都按当前配置现查现装配。
     */
    public static void enableForUser(JdbcTemplate jdbcTemplate, Long userId) {
        String url = serverUrl();
        jdbcTemplate.update(
                "DELETE FROM mcp_user_config WHERE provider_id IN (SELECT id FROM mcp_provider WHERE code = ?)",
                PROVIDER_CODE);
        jdbcTemplate.update(
                "DELETE FROM mcp_endpoint WHERE provider_id IN (SELECT id FROM mcp_provider WHERE code = ?)",
                PROVIDER_CODE);
        jdbcTemplate.update("DELETE FROM mcp_provider WHERE code = ?", PROVIDER_CODE);
        jdbcTemplate.update(
                "INSERT INTO mcp_provider (code, name, auth_type, auth_header, auth_secret_enc, enabled, remark)"
                        + " VALUES (?, ?, 'NONE', NULL, NULL, TRUE, '评估内嵌 MCP 桩 server')",
                PROVIDER_CODE, "评估扩展数据源");
        Long providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM mcp_provider WHERE code = ?", Long.class, PROVIDER_CODE);
        jdbcTemplate.update(
                "INSERT INTO mcp_endpoint (provider_id, domain, name, url) VALUES (?, NULL, '评估内嵌', ?)",
                providerId, url);
        jdbcTemplate.update(
                "INSERT INTO mcp_user_config (user_id, provider_id, enabled, disabled_tools) VALUES (?, ?, TRUE, '[]'::jsonb)",
                userId, providerId);
    }

    // ———— 工具定义 ————

    /** 写工具（McpHitl 同款）：不标 annotations → 缺省视为写，触发 permission_confirm。 */
    private static McpSchema.Tool writeNoteTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("file", Map.of("type", "string", "description", "笔记文件名"),
                        "content", Map.of("type", "string", "description", "写入内容")),
                List.of("content"),
                null, null, null);
        return McpSchema.Tool.builder()
                .name("write_note")
                .description("写入一条用户笔记（评估扩展源的写工具）")
                .inputSchema(schema)
                .build();
    }

    /** 读工具（McpHitl 同款）：readOnlyHint=true → 不弹审批直接执行。 */
    private static McpSchema.Tool readNoteTool() {
        return McpSchema.Tool.builder()
                .name("read_note")
                .description("读取用户笔记（评估扩展源的只读工具）")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, null, null, null))
                .annotations(new McpSchema.ToolAnnotations(null, true, null, null, null, null))
                .build();
    }

    /** 数据兜底工具：研报领域（内置工具不覆盖），只读放行——「扩展源兜底分工」题的观察点。 */
    private static McpSchema.Tool researchReportTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("code", Map.of("type", "string", "description", "6位A股代码，如 600519")),
                List.of("code"),
                null, null, null);
        return McpSchema.Tool.builder()
                .name("get_research_report")
                .description("获取个股最新卖方研报观点汇总（扩展数据源：研报/公告领域，内置工具不覆盖）")
                .inputSchema(schema)
                .annotations(new McpSchema.ToolAnnotations(null, true, null, null, null, null))
                .build();
    }

    private static String researchText(String code) {
        if (code != null && code.contains("300750")) {
            return "宁德时代（300750）近30日 6 篇研报：5 篇维持「买入」评级，1 篇「增持」；"
                    + "核心逻辑：神行Pro电池四季度放量、海外产能爬坡超预期；提示风险：原材料价格波动。";
        }
        if (code != null && code.contains("600519")) {
            return "贵州茅台（600519）近30日 5 篇研报：4 篇维持「买入」评级，1 篇「增持」；"
                    + "核心逻辑：批价企稳回升、品牌护城河稳固；提示风险：消费复苏不及预期。";
        }
        return "（" + (code == null ? "?" : code) + "）暂无收录研报。";
    }

    private static String mcpUrl() {
        return "http://localhost:" + tomcat.getConnector().getLocalPort() + "/mcp";
    }
}
