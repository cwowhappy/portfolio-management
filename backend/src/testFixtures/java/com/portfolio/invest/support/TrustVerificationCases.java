package com.portfolio.invest.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * MS-29 B2 验收集 loader：classpath {@code trust/verification-cases/*.json} 全量加载
 * （需求决策 #14：约 30 例纯确定性 fixture，90%/5% 验收门槛锚定于此）。
 *
 * <p><strong>fixture JSON 契约（B5/B10 消费方契约，结构一经落地勿轻改）：</strong>
 * <pre>{@code
 * {
 *   "id": "rounding-one-decimal",          // 用例名（唯一，诊断输出用）
 *   "category": "rounding",                // 分类标签（报表用，不参与判定）
 *   "text": "现价约15.2元",                 // 被校验的 assistant 文本
 *   "pool": [                              // 工具真值池（按时间序，后者为最近一次）
 *     {"value": "15.23", "kind": "price",  // value=工具返回中的数字原文；kind=量纲后缀
 *      "tool": "get_quote",                // 可选，默认 get_quote
 *      "mcp": false,                       // 可选，true → asOfKind=CALL（MCP，只配源不比对）
 *      "failed": false}                    // 可选，true → 失败调用（不产生可比真值）
 *   ],
 *   "expectedAnchors": [                   // 期望锚定（仅 dataLike 数字；顺序无关，按 snippet+occ 匹配）
 *     {"snippet": "15.2元", "occ": 1, "state": "verified",
 *      "wrong": false,                     // 可选，true = 直接引用错值（检出率分母）
 *      "expectedRaw": "15.23元"}           // 可选，断言命中真值原值
 *   ],
 *   "expectedCorrections": [               // 期望修正（correct() 产物）
 *     {"snippet": "10.6元", "occ": 1, "degraded": false}
 *   ],
 *   "expectedNotes": ["原文误述 10.6元"],   // 期望 AnchorBatch.correctionNotes（全量相等断言）
 *   "expectedText": "现价10元\n> ⚠ 校验修正：原文误述 10.6元"  // 可选，缺省 = 期望原文不变
 * }
 * }</pre>
 *
 * <p>kind 量纲后缀映射：{@code plain}=无后缀、{@code price}=「元」、{@code percent}=「%」、
 * {@code wan}=「万」、{@code yi}=「亿」、{@code wanyi}=「万亿」。loader 将 pool 条目展开为
 * {@code ToolInvocation(resultText = value + 后缀)} 合成真值池。
 */
public final class TrustVerificationCases {

    private static final String RESOURCE_DIR = "trust/verification-cases";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /** 单条验收集用例。 */
    public record Case(
            String id,
            String category,
            String text,
            List<PoolEntry> pool,
            List<ExpectedAnchor> expectedAnchors,
            List<ExpectedCorrection> expectedCorrections,
            List<String> expectedNotes,
            String expectedText) {

        /** 缺省 expectedText = 原文不变（无修正期望）。 */
        public String expectedTextOrOriginal() {
            return expectedText == null ? text : expectedText;
        }
    }

    /** 真值池条目：value（数字原文）+ kind（量纲后缀）+ 可选 tool/mcp/failed。 */
    public record PoolEntry(String value, String kind, String tool, Boolean mcp, Boolean failed) {

        /** 量纲后缀（kind 缺省 plain）。 */
        public String suffix() {
            String k = kind == null ? "plain" : kind;
            return switch (k) {
                case "price" -> "元";
                case "percent" -> "%";
                case "wan" -> "万";
                case "yi" -> "亿";
                case "wanyi" -> "万亿";
                case "plain" -> "";
                default -> throw new IllegalArgumentException("未知 kind: " + k);
            };
        }

        public boolean isMcp() {
            return Boolean.TRUE.equals(mcp);
        }

        public boolean isFailed() {
            return Boolean.TRUE.equals(failed);
        }

        public String toolName() {
            return tool == null ? (isMcp() ? "mcp_stub" : "get_quote") : tool;
        }
    }

    /** 期望锚定：snippet+occ 定位，state 三态；wrong 标记直接引用错值（检出率口径）。 */
    public record ExpectedAnchor(String snippet, int occ, String state, Boolean wrong, String expectedRaw) {

        public boolean isWrong() {
            return Boolean.TRUE.equals(wrong);
        }
    }

    /** 期望修正：snippet+occ 定位，degraded 标记重试耗尽降级（原文保留）。 */
    public record ExpectedCorrection(String snippet, int occ, Boolean degraded) {

        public boolean isDegraded() {
            return Boolean.TRUE.equals(degraded);
        }
    }

    private TrustVerificationCases() {
    }

    /** 加载全部验收集用例（classpath 枚举，按资源名排序保证稳定输出；兼容目录与 jar 两种资源形态）。 */
    public static List<Case> load() {
        try {
            List<Case> cases = new ArrayList<>();
            Enumeration<URL> urls = TrustVerificationCases.class.getClassLoader().getResources(RESOURCE_DIR);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                cases.addAll(switch (url.getProtocol()) {
                    case "file" -> loadFromDirectory(url);
                    case "jar" -> loadFromJar(url);
                    default -> throw new IllegalStateException("不支持的 classpath 资源形态: " + url);
                });
            }
            if (cases.isEmpty()) {
                throw new IllegalStateException("验收集为空: classpath " + RESOURCE_DIR + " 无 *.json");
            }
            return Collections.unmodifiableList(cases);
        } catch (IOException e) {
            throw new IllegalStateException("验收集加载失败: " + RESOURCE_DIR, e);
        }
    }

    /** IDE/ exploded classpath：目录直读（testFixtures 资源在 test 运行时为 jar 形态，此分支兜底）。 */
    private static List<Case> loadFromDirectory(URL url) throws IOException {
        List<Path> files;
        try (var stream = Files.list(Path.of(url.toURI()))) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("非法 classpath 资源 URI: " + url, e);
        }
        List<Case> cases = new ArrayList<>();
        for (Path file : files) {
            cases.add(read(file.toString(), Files.readString(file)));
        }
        return cases;
    }

    /** Gradle test 运行时：testFixtures 打包为 jar，按 jar 条目读取。 */
    private static List<Case> loadFromJar(URL url) throws IOException {
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        List<String> entryNames = new ArrayList<>();
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(RESOURCE_DIR + "/") && name.endsWith(".json")) {
                    entryNames.add(name);
                }
            }
            Collections.sort(entryNames);
            List<Case> cases = new ArrayList<>();
            for (String name : entryNames) {
                try (InputStream in = jar.getInputStream(jar.getEntry(name))) {
                    cases.add(read(name, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            return cases;
        }
    }

    private static Case read(String source, String json) {
        try {
            Case c = MAPPER.readValue(json, Case.class);
            if (c.id == null || c.text == null || c.pool == null || c.expectedAnchors == null) {
                throw new IllegalStateException("fixture 必填字段缺失: " + source);
            }
            return c;
        } catch (IOException e) {
            throw new IllegalStateException("fixture 解析失败: " + source, e);
        }
    }
}
