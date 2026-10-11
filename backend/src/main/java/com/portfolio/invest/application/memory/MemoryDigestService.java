package com.portfolio.invest.application.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.application.journal.TimelineEventView;
import com.portfolio.invest.domain.memory.MemoryDigestCursor;
import com.portfolio.invest.domain.memory.MemoryDigestCursorRepository;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * F03 每日记忆摘要链（MS-31 B6，设计规格 §4.2）：窗口内（{@code windowStart} → 批时刻）
 * 逐可登录用户拉 {@link JournalApplicationService#timeline} 八类事件 → 非空者经
 * {@link IntelligenceChatPort}（DeepSeek，system = 版本资产 {@code digest.memory} 即
 * {@link DigestPrompts#SYSTEM}）摘要为 ≤5 条 bullet → 追加外部事件节到当日日账本
 * （{@link WorkspaceMemoryPort#appendExternalEvent}，决议 #9 写入格式）。
 *
 * <p><b>游标推进 = 批执行完成而非全成功</b>（Review Focus #3 / 决议 #8「失败顺延」落点）：
 * 单用户失败（取数/序列化/LLM/写盘任一环节）WARN 跳过不挡批、批完成仍把全局游标推进到
 * 批时刻；只有批级顶层异常（findAll 抛出 / 游标单行缺失）才让游标不动——此时窗口未消费，
 * 次日窗口自动扩大重跑。注意与 LLM 返回 empty 的分工：端口 empty = 未配置/调用失败，
 * 按单用户静默跳过处理（调度器在批前另有 DEEPSEEK_API_KEY 缺席整批快退，防「全 empty
 * 假成功」推进游标造成永久漏摘要）。
 *
 * <p>窗口粒度：timeline 收 LocalDate（库按 event_date 索引下推），Instant→上海时区日期
 * 两端各自转换——窗口两端日期边界（上海 00:00）由测试钉住；游标推进与节头/提示词里的
 * 窗口止共用同一 {@code now}（批首取一次，保证 at-least-once：批期间新到事件落入下窗）。
 */
@Service
public class MemoryDigestService {

    private static final Logger log = LoggerFactory.getLogger(MemoryDigestService.class);

    /** 市场时区（与调度 zone 一致，窗口 Instant→LocalDate 转换口径）。 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 单事件 JSON 截断阈值（字符）：超长事件截断入 prompt，防单条巨幅描述撑爆上下文。 */
    static final int MAX_EVENT_JSON_CHARS = 4000;

    /** 截断标记（截断在 JSON 中途发生，LLM 侧按残缺对象理解，标记提示截断事实）。 */
    static final String TRUNCATION_MARKER = "…（超长已截断）";

    /** 节头前缀（与 B2 kind 判定前缀逐字一致，EXTERNAL_EVENT 徽标依赖）。 */
    static final String SECTION_HEADER_PREFIX = "## 外部事件摘要";

    /**
     * bullet 行归一：剥至多一个行首列表标记（{@code "-"}+空白 或 后非数字的无空格短横、
     * {@code •}、{@code *}、{@code 数字./、/)/）}）后统一回填 {@code "- "}。{@code "-5%"}
     * 类负数开头正文（短横后紧跟数字）不匹配标记、原样保留（只加 bullet 前缀，内容不损）。
     */
    private static final Pattern LIST_MARKER =
            Pattern.compile("^(?:-\\s+|-(?!\\d)|•\\s*|\\*\\s*|\\d+[.、)）]\\s*)");

    private final JournalApplicationService journal;
    private final IntelligenceChatPort chatPort;
    private final WorkspaceMemoryPort memoryPort;
    private final UserRepository userRepository;
    private final MemoryDigestCursorRepository cursorRepository;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()                       // LocalDate（jsr310）
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // ISO "yyyy-MM-dd" 非数组

    @Autowired
    public MemoryDigestService(JournalApplicationService journal, IntelligenceChatPort chatPort,
                               WorkspaceMemoryPort memoryPort, UserRepository userRepository,
                               MemoryDigestCursorRepository cursorRepository) {
        this(journal, chatPort, memoryPort, userRepository, cursorRepository, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（钉批时刻）。 */
    MemoryDigestService(JournalApplicationService journal, IntelligenceChatPort chatPort,
                        WorkspaceMemoryPort memoryPort, UserRepository userRepository,
                        MemoryDigestCursorRepository cursorRepository, Clock clock) {
        this.journal = journal;
        this.chatPort = chatPort;
        this.memoryPort = memoryPort;
        this.userRepository = userRepository;
        this.cursorRepository = cursorRepository;
        this.clock = clock;
    }

    /**
     * 摘要一批（调度入口也可直调）：窗口 = windowStart → 批时刻；逐可登录用户（APPROVED
     * 且 enabled，沿批任务先例）处理；批完成即推进全局游标到批时刻（部分失败不挡推进，
     * 见类注释）。
     */
    public void digestAll(Instant windowStart) {
        Instant now = Instant.now(clock);
        LocalDate from = LocalDate.ofInstant(windowStart, ZONE);
        LocalDate to = LocalDate.ofInstant(now, ZONE);
        List<User> users = userRepository.findAll().stream()
                .filter(User::canLogin)
                .toList();
        for (User user : users) {
            try {
                digestUser(user.id(), windowStart, now, from, to);
            } catch (Exception e) { // 单用户隔离：失败 WARN 跳过，不挡批、不回滚游标
                log.warn("记忆摘要单用户失败，跳过不挡批（userId={}）：{}",
                        user.id(), e.getMessage(), e);
            }
        }
        cursorRepository.save(cursorRepository.find()
                .orElseThrow(() -> new IllegalStateException(
                        "memory_digest_cursor 全局单行缺失（V7 seed 保证存在），游标未推进"))
                .advanceTo(now));
        log.info("记忆摘要批完成：users={} 窗口={} ~ {}，游标推进至批时刻", users.size(), from, to);
    }

    private void digestUser(Long userId, Instant windowStart, Instant now,
                            LocalDate from, LocalDate to) {
        List<TimelineEventView> events = journal.timeline(userId, from, to);
        if (events.isEmpty()) { // 空事件零 LLM（成本纪律）
            return;
        }
        Optional<IntelligenceChatPort.ChatOutcome> outcome =
                chatPort.complete(DigestPrompts.SYSTEM, userPrompt(windowStart, now, events));
        if (outcome.isEmpty()) { // LLM 未配置/调用失败：端口契约静默跳批，本用户不写
            log.info("记忆摘要 LLM 返回 empty，本用户跳过（userId={}；批仍完成推进游标）", userId);
            return;
        }
        List<String> bullets = bulletLines(outcome.get().text());
        if (bullets.isEmpty()) { // present("") 已知边界（全非文本块）：不写空节
            log.info("记忆摘要 LLM 返回全空白文本，本用户不写（userId={}）", userId);
            return;
        }
        memoryPort.appendExternalEvent(userId, to, section(to, windowStart, now, bullets));
    }

    /** 决议 #9 写入格式逐字：{@code ## 外部事件摘要 — <当日>（<窗口起> ~ <窗口止>）} + bullet 行。 */
    static String section(LocalDate date, Instant windowStart, Instant windowEnd, List<String> bullets) {
        return SECTION_HEADER_PREFIX + " — " + date
                + "（" + shanghaiMinutes(windowStart) + " ~ " + shanghaiMinutes(windowEnd) + "）"
                + "\n" + String.join("\n", bullets);
    }

    /** user prompt：窗口行（与节头同源分钟精度）+ 事件 JSON 数组（逐事件超长截断）。 */
    String userPrompt(Instant windowStart, Instant now, List<TimelineEventView> events) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("时间窗口：")
                .append(shanghaiMinutes(windowStart)).append(" ~ ").append(shanghaiMinutes(now))
                .append('\n')
                .append("投资事件流（JSON 数组，按日期倒序）：\n[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                prompt.append(',');
            }
            prompt.append(truncateEvent(json(events.get(i))));
        }
        prompt.append(']');
        return prompt.toString();
    }

    /** 单事件 JSON 序列化（Jackson 2：枚举名/ISO 日期/含 null 字段）。 */
    private String json(TimelineEventView event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("timeline 事件 JSON 序列化失败", e);
        }
    }

    /** 单事件超长截断（阈值 {@link #MAX_EVENT_JSON_CHARS}，附截断标记）。 */
    static String truncateEvent(String json) {
        return json.length() <= MAX_EVENT_JSON_CHARS ? json
                : json.substring(0, MAX_EVENT_JSON_CHARS) + TRUNCATION_MARKER;
    }

    /** LLM 文本 → 归一 bullet 行：剥行首列表标记统一 {@code "- "}，空行剔除；全空白 → 空表。 */
    static List<String> bulletLines(String llmText) {
        if (llmText == null || llmText.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : llmText.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            lines.add("- " + LIST_MARKER.matcher(line).replaceFirst(""));
        }
        return lines;
    }

    /** 上海时区分（秒以下归零）ISO 文本：{@code 2026-10-09T21:07}（节头与提示词窗口同源）。 */
    private static String shanghaiMinutes(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE).truncatedTo(ChronoUnit.MINUTES).toString();
    }
}
