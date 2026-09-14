package com.portfolio.invest.agui;

import static com.portfolio.invest.support.AguiTestSupport.registerApproveAndLogin;
import static com.portfolio.invest.support.AguiTestSupport.run;
import static com.portfolio.invest.support.AguiTestSupport.runRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.model.Model;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HarnessAgent 落盘 state 的按用户/会话隔离（方案 4.2-B，缺口 #4 集成侧）：真实 HarnessAgent +
 * JsonFileAgentStateStore 落盘。两用例分别锁 state 键 (userId, sessionId) 的两个半边——两用户
 * 各自 threadId 各跑一轮断言用户维度互不重叠；同一用户两个 threadId 各跑一轮断言会话维度互不重叠，
 * 各自只含自己的消息文本。
 *
 * <p>落盘布局「先探后锁」：javap agentscope 2.0.3 {@code JsonFileAgentStateStore} 核实路径拼接为
 * {@code root/<safeSegment(userId)>/<safeSegment(sessionId)>/<key>.json|.jsonl|.hash}（userId 来自
 * {@code InvestAguiRuntimeContextResolver} 写入的 DB 用户 id 字符串，纯数字过 safeSegment 原样保留），
 * 实跑确认顶层目录即 userId 字符串。用户维度断言只锁「相对路径含 userId 路径段」；会话维度
 * 断言锁「路径段等于 threadId」（safeSegment 白名单 {@code ^[a-zA-Z0-9_\-.]+$} 对 threadId
 * 原样保留，实跑会话目录段即 threadId）；均不锁文件名/后缀风格（防上游改版脆断）。
 *
 * <p>技术方案说明：
 * <ul>
 *   <li>假 Model 复用 {@link AguiStreamIntegrationTest.FixedReplyModel}（同包静态嵌套类，bean 名
 *       investModel 整体替换，全程不打真实 LLM；其调用录制本用例不读）。</li>
 *   <li>skill 差异维度（方案可选增强）：A 启用 tushare_data、B 全不启用（经生产写入路径
 *       {@code SkillApplicationService.save} seed，非裸 SQL），断言两侧 agent 均构建跑完，
 *       不硬断 skill 内容。</li>
 *   <li>seed 的 skill_user_config 行 @AfterEach 按 username→userId 清理
 *       （PostgresTestSupport 容器 JVM 级单例，行会留存）。</li>
 *   <li>标记串带 UUID：state-root 跨次运行不清理也不影响断言（旧文件不含本轮标记，
 *       旧 userId 目录文件不会被「含本轮标记」断言误读）。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=test-dummy-key",
        "invest.mcp.harness.state-root=build/state-isolation-test/state"})
@AutoConfigureMockMvc
class HarnessAgentStateIsolationIntegrationTest extends PostgresTestSupport {

    /** 与 @SpringBootTest properties 一致，落盘断言在此根下收集文件。 */
    private static final Path STATE_ROOT = Path.of("build/state-isolation-test/state");

    private static final String USERNAME_A = "state_iso_alice";
    private static final String USERNAME_B = "state_iso_bob";

    /** 会话维度用例专用用户：注册非幂等（容器 JVM 级单例、行留存），不可复用 A/B。 */
    private static final String USERNAME_S = "state_iso_same";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SkillApplicationService skillApplicationService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** bean 名按字段名推断为 investModel，精确替换 AgentConfig#investModel（照抄 AguiStreamIntegrationTest）。 */
    @TestBean(methodName = "fixedReplyModel")
    Model investModel;

    static Model fixedReplyModel() {
        return new AguiStreamIntegrationTest.FixedReplyModel();
    }

    @DisplayName("两用户各自会话对话后state文件按用户维度互不重叠且各含各自消息")
    @Test
    void givenTwoUsers_whenRunEachInOwnSession_thenStateFilesIsolatedByUserAndSession() throws Exception {
        // 用户 A、B 各自注册/审批/登录（各自独立 MockHttpSession → 各自 DB userId）
        MockHttpSession sessionA = registerApproveAndLogin(mockMvc, userRepository, USERNAME_A);
        MockHttpSession sessionB = registerApproveAndLogin(mockMvc, userRepository, USERNAME_B);
        long userIdA = userIdOf(USERNAME_A);
        long userIdB = userIdOf(USERNAME_B);

        // skill 差异维度：A 启用 tushare_data，B 全不启用（classpath 真实 skill code）
        skillApplicationService.save(userIdA, List.of("tushare_data"));
        skillApplicationService.save(userIdB, List.of());

        // 各自 threadId 各发一轮，消息带可检索标记串（UUID 保证跨用例/跨运行唯一）
        String markerA = "A-用户-专属-消息-" + UUID.randomUUID();
        String markerB = "B-用户-专属-消息-" + UUID.randomUUID();
        String bodyA = run(mockMvc, sessionA, runRequest("iso-a-" + UUID.randomUUID(), "run-iso-a", markerA));
        String bodyB = run(mockMvc, sessionB, runRequest("iso-b-" + UUID.randomUUID(), "run-iso-b", markerB));

        // skill 差异下两侧 agent 均构建跑完（skillFilter 不炸），不断 skill 内容
        assertThat(bodyA).contains("RUN_FINISHED").doesNotContain("RUN_ERROR");
        assertThat(bodyB).contains("RUN_FINISHED").doesNotContain("RUN_ERROR");

        // state-root 已由 JsonFileAgentStateStore 创建（两侧跑完仍未建目录说明落盘路径串了）
        assertThat(Files.isDirectory(STATE_ROOT))
                .as("state-root 应已由 JsonFileAgentStateStore 创建").isTrue();

        // 递归收集落盘文件，按「相对路径含 userId 路径段」归组到各用户
        Set<Path> filesA;
        Set<Path> filesB;
        try (Stream<Path> walked = Files.walk(STATE_ROOT)) {
            List<Path> allFiles = walked.filter(Files::isRegularFile).toList();
            filesA = filesOfUser(allFiles, userIdA);
            filesB = filesOfUser(allFiles, userIdB);
        }

        // 两组都非空：两侧确实各自落盘（userId 串线丢失时会全落到 __anon__，在此失败）
        assertThat(filesA).as("用户 A 的 state 文件").isNotEmpty();
        assertThat(filesB).as("用户 B 的 state 文件").isNotEmpty();
        // 文件集合互不重叠：同一文件被两用户的 userId 路径段命中即串写
        Set<Path> overlap = new HashSet<>(filesA);
        overlap.retainAll(filesB);
        assertThat(overlap).as("两用户 state 文件交集").isEmpty();

        // 各自只含自己的消息：A 的文件含 A 标记且无 B 标记，反之亦然
        assertThat(joinedContent(filesA)).contains(markerA).doesNotContain(markerB);
        assertThat(joinedContent(filesB)).contains(markerB).doesNotContain(markerA);
    }

    @DisplayName("同一用户两会话对话后state文件按会话维度互不重叠且各含各自消息")
    @Test
    void givenSameUser_whenRunInTwoSessions_thenStateFilesSeparatedBySession() throws Exception {
        // 同一用户注册/审批/登录一次，两个不同 threadId 各跑一轮：锁 (userId, sessionId) 键的 sessionId 半边
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, USERNAME_S);
        long userId = userIdOf(USERNAME_S);

        // 两个会话各自 threadId 各发一轮，消息带可检索标记串（UUID 保证跨用例/跨运行唯一）
        String threadA = "iso-s-a-" + UUID.randomUUID();
        String threadB = "iso-s-b-" + UUID.randomUUID();
        String markerA = "S-A-会话-专属-消息-" + UUID.randomUUID();
        String markerB = "S-B-会话-专属-消息-" + UUID.randomUUID();
        String bodyA = run(mockMvc, session, runRequest(threadA, "run-iso-s-a", markerA));
        String bodyB = run(mockMvc, session, runRequest(threadB, "run-iso-s-b", markerB));

        // 两个会话均构建跑完
        assertThat(bodyA).contains("RUN_FINISHED").doesNotContain("RUN_ERROR");
        assertThat(bodyB).contains("RUN_FINISHED").doesNotContain("RUN_ERROR");

        // 先归组到该用户名下，再按 threadId 路径段归组到两个会话（实跑布局会话目录段即 threadId）
        Set<Path> sessionFilesA;
        Set<Path> sessionFilesB;
        try (Stream<Path> walked = Files.walk(STATE_ROOT)) {
            Set<Path> userFiles = filesOfUser(walked.filter(Files::isRegularFile).toList(), userId);
            sessionFilesA = filesOfSession(userFiles, threadA);
            sessionFilesB = filesOfSession(userFiles, threadB);
        }

        // 两组都非空：两会话确实各自落盘，且都在同一个 userId 目录段之下（先经 filesOfUser 过滤）
        assertThat(sessionFilesA).as("会话 A 的 state 文件").isNotEmpty();
        assertThat(sessionFilesB).as("会话 B 的 state 文件").isNotEmpty();
        // 同一 userId 下两会话路径互不重叠：同一文件被两个 threadId 路径段命中即串写
        Set<Path> overlap = new HashSet<>(sessionFilesA);
        overlap.retainAll(sessionFilesB);
        assertThat(overlap).as("同用户两会话 state 文件交集").isEmpty();

        // 各自只含自己的消息：A 会话文件含 A 标记且无 B 标记，反之亦然
        assertThat(joinedContent(sessionFilesA)).contains(markerA).doesNotContain(markerB);
        assertThat(joinedContent(sessionFilesB)).contains(markerB).doesNotContain(markerA);
    }

    @AfterEach
    void cleanupSeededSkillConfig() {
        // seed 行按 username→userId 清理（PostgresTestSupport 容器 JVM 级单例，行会留存）
        jdbcTemplate.update("""
                DELETE FROM skill_user_config WHERE user_id IN (
                    SELECT id FROM app_user WHERE username IN (?, ?))
                """, USERNAME_A, USERNAME_B);
    }

    // ———— 落盘断言辅助（布局先探后锁，只锁 userId 路径段） ————

    /** 归组：文件相对 stateRoot 的路径任一段等于 userId 字符串（实跑布局顶层目录即 userId，不锁更深层命名）。 */
    private static Set<Path> filesOfUser(List<Path> files, long userId) {
        String uid = String.valueOf(userId);
        return files.stream()
                .filter(f -> hasPathSegment(STATE_ROOT.relativize(f), uid))
                .collect(Collectors.toSet());
    }

    /**
     * 归组（会话维度）：文件相对 stateRoot 的路径任一段等于 threadId。threadId 仅含
     * {@code [a-zA-Z0-9-]}，过 safeSegment（白名单 {@code ^[a-zA-Z0-9_\-.]+$}）原样保留，
     * 实跑布局会话目录段即 threadId；不锁文件名/后缀风格（防上游改版脆断）。
     */
    private static Set<Path> filesOfSession(Set<Path> files, String threadId) {
        return files.stream()
                .filter(f -> hasPathSegment(STATE_ROOT.relativize(f), threadId))
                .collect(Collectors.toSet());
    }

    private static boolean hasPathSegment(Path path, String segment) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (path.getName(i).toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** 拼接一组 state 文件内容（UTF-8 JSON/JSONL），供子串断言。 */
    private static String joinedContent(Set<Path> files) throws IOException {
        StringBuilder joined = new StringBuilder();
        for (Path file : files) {
            joined.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
        }
        return joined.toString();
    }

    // ———— 其余辅助（三段式 run / 注册审批登录 / 请求体收敛至 testFixtures 的 AguiTestSupport） ————

    private long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().id();
    }
}
