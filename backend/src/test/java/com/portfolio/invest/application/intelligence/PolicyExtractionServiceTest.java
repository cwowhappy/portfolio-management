package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyExtractResult;
import com.portfolio.invest.domain.intelligence.PolicyRecord;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 政策抽取批单元切片（mock 仓库/LLM 端口/token 护栏/告警；PolicyExtractor 用真实纯组件）：
 * 照新闻/公告抽取「批量→逐条→失败隔离」骨架——每日 09:10 一批、3 日游标窗口、
 * 解析失败重试 1 次仍败标 FAILED 不阻塞批次、LLM 未配置整批静默跳过留 PENDING、
 * token 护栏当日停批 + 告警恰一次（D16）、isPolicy=false 兜底落库低置信标注（F12）、
 * 短文本（title+正文 &lt; 50 字符）跳过、空批快退、多批循环、单条异常隔离、调度顶层吞异常、
 * FAILED 批末告警按日去重（NFR-5）。
 */
class PolicyExtractionServiceTest {

    /** 上海 2026-10-03 09:10（UTC 01:10）——当日口径锚点（每日一批 09:10 调度对齐）。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T01:10:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    private static final String OK_JSON = """
            {"isPolicy":true,"direction":"EASING","strength":"HIGH",
            "affectedAreas":["利率","房地产"],"summary":"央行降准0.5个百分点","confidence":"HIGH"}""";
    private static final String NON_POLICY_JSON = """
            {"isPolicy":false,"direction":"NEUTRAL","strength":"LOW",
            "affectedAreas":[],"summary":"行长出席论坛并发表讲话","confidence":"LOW"}""";
    private static final String INVALID_JSON = "抱歉我无法以 JSON 输出{{{";

    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final IntelligenceChatPort chatPort = mock(IntelligenceChatPort.class);
    private final IntelligenceTokenBudget tokenBudget = mock(IntelligenceTokenBudget.class);
    private final AlertNotifier alertNotifier = mock(AlertNotifier.class);
    private final InvestProperties props = new InvestProperties();
    private PolicyExtractionService service;

    @BeforeEach
    void setUp() {
        service = new PolicyExtractionService(policyRepository, chatPort, tokenBudget,
                alertNotifier, props, new PolicyExtractor(), CLOCK);
        when(tokenBudget.exhausted()).thenReturn(false);
        when(tokenBudget.tryAcquire(anyLong())).thenReturn(true);
        when(alertNotifier.send(any(), any(), anyList())).thenReturn(true);
    }

    @Test
    @DisplayName("给定2条待抽取政策，when抽取批，then逐条SUCCESS落库全字段且计入护栏")
    void givenTwoPendingPolicies_whenExtractPending_thenBothUpsertedWithFullFields() {
        givenPending(policy(1L, "央行决定降准"), policy(2L, "国务院印发若干意见"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        ArgumentCaptor<PolicyExtractResult> captor = ArgumentCaptor.forClass(PolicyExtractResult.class);
        verify(policyRepository, times(2)).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getAllValues()).extracting(PolicyExtractResult::status)
                .containsExactly(ExtractStatus.SUCCESS, ExtractStatus.SUCCESS);

        PolicyExtractResult first = captor.getAllValues().getFirst();
        assertThat(first.direction()).isEqualTo(PolicyDirection.EASING);
        assertThat(first.strength()).isEqualTo(PolicyStrength.HIGH);
        assertThat(first.affectedAreas()).containsExactly("利率", "房地产");
        assertThat(first.summary()).isEqualTo("央行降准0.5个百分点");
        assertThat(first.confidence()).isEqualTo(PolicyConfidence.HIGH);
        assertThat(first.model()).isEqualTo(props.getLlm().getModel());
        assertThat(first.extractedAt()).isEqualTo(CLOCK.instant());
        verify(tokenBudget, times(2)).tryAcquire(anyLong());
    }

    @Test
    @DisplayName("给定isPolicy=false漏网条目（领导活动），when抽取批，then兜底落库低置信标注而非丢弃")
    void givenNonPolicyLeakage_whenExtractPending_thenFallbackRowWithLowConfidence() {
        givenPending(policy(1L, "行长出席论坛并讲话"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(NON_POLICY_JSON)));

        service.extractPending();

        // F12：误收录须标注为低置信——落库（非丢弃）+ 哨兵 summary + LOW confidence
        ArgumentCaptor<PolicyExtractResult> captor = ArgumentCaptor.forClass(PolicyExtractResult.class);
        verify(policyRepository).upsertExtract(eq(1L), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(captor.getValue().summary()).isEqualTo(PolicyEvent.NON_POLICY_SUMMARY);
        assertThat(captor.getValue().confidence()).isEqualTo(PolicyConfidence.LOW);
        assertThat(captor.getValue().direction()).isEqualTo(PolicyDirection.NEUTRAL);
        assertThat(captor.getValue().strength()).isEqualTo(PolicyStrength.LOW);
    }

    @Test
    @DisplayName("给定3条待抽取其中第1条两次非法JSON，when抽取批，then重试1次后1条FAILED+2条SUCCESS且互不阻塞")
    void givenThreePoliciesFirstAlwaysInvalid_whenExtractPending_thenOneFailedTwoSuccess() {
        givenPending(policy(1L, "坏政策"), policy(2L, "好政策甲"), policy(3L, "好政策乙"));
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(INVALID_JSON)), Optional.of(outcome(INVALID_JSON)),
                Optional.of(outcome(OK_JSON)), Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(chatPort, times(4)).complete(any(), any()); // 3 条 + 1 次重试
        ArgumentCaptor<PolicyExtractResult> captor = ArgumentCaptor.forClass(PolicyExtractResult.class);
        verify(policyRepository, times(3)).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getAllValues()).extracting(PolicyExtractResult::status)
                .containsExactly(ExtractStatus.FAILED, ExtractStatus.SUCCESS, ExtractStatus.SUCCESS);

        PolicyExtractResult failed = captor.getAllValues().getFirst();
        assertThat(failed.direction()).isNull();
        assertThat(failed.summary()).isNull();
        assertThat(failed.model()).isEqualTo(props.getLlm().getModel());
        assertThat(failed.extractedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    @DisplayName("给定LLM通道未配置，when抽取批，then首批即静默跳批不标FAILED不抛异常")
    void givenLlmUnconfigured_whenExtractPending_thenSkipSilentlyLeavePending() {
        givenPending(policy(1L, "甲"), policy(2L, "乙"), policy(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.extractPending()).doesNotThrowAnyException();

        // 0 次状态置换（条目留 PENDING 下批再试），且首条即跳批不再调后续条目
        verify(policyRepository, never()).upsertExtract(anyLong(), any());
        verify(chatPort, times(1)).complete(any(), any());
    }

    @Test
    @DisplayName("给定LLM批中途失效，when抽取批，then已完成条目落库其余留PENDING整批中止")
    void givenLlmDiesMidBatch_whenExtractPending_thenProcessedPersistedRestPending() {
        givenPending(policy(1L, "甲"), policy(2L, "乙"), policy(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(OK_JSON)), Optional.empty());

        service.extractPending();

        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(policyRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(1L); // 仅第 1 条置换，2/3 留 PENDING
        verify(chatPort, times(2)).complete(any(), any());
    }

    @Test
    @DisplayName("给定当日token累计超护栏，when抽取批，then当前条落库后停批剩余不动且告警恰1次")
    void givenGuardrailExceededMidBatch_whenExtractPending_thenStopWithSingleAlert() {
        givenPending(policy(1L, "甲"), policy(2L, "乙"), policy(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));
        when(tokenBudget.tryAcquire(anyLong())).thenReturn(false); // 第 1 条计入即超限

        service.extractPending();

        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(policyRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(1L);
        verify(chatPort, times(1)).complete(any(), any());
        verify(alertNotifier, times(1)).send(any(), any(), anyList());
    }

    @Test
    @DisplayName("给定护栏已超限，when再入批，then不取数不调LLM且当日不重复告警")
    void givenGuardrailAlreadyExhausted_whenExtractPendingAgain_thenFastExitNoDuplicateAlert() {
        when(tokenBudget.exhausted()).thenReturn(true);

        service.extractPending();
        service.extractPending();

        verify(policyRepository, never()).findPendingForExtraction(any(), anyInt(), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(alertNotifier, times(1)).send(any(), any(), anyList()); // 当日告警恰 1 次
    }

    @Test
    @DisplayName("给定当日无待抽取政策，when抽取批，then空批快退零LLM调用且3日窗传导")
    void givenNoPendingPolicies_whenExtractPending_thenFastReturn() {
        when(policyRepository.findPendingForExtraction(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.extractPending();

        // 游标窗口经服务常量传导：3 个自然日含当日（积压跨日续抽）
        verify(policyRepository, times(1)).findPendingForExtraction(eq(TODAY), eq(3), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(policyRepository, never()).upsertExtract(anyLong(), any());
    }

    @Test
    @DisplayName("给定待抽取超过单批容量，when抽取批，then按配置批大小循环取批直至清空")
    void givenMorePendingThanBatchSize_whenExtractPending_thenLoopUntilDrained() {
        props.getIntelligence().setExtractBatchSize(2);
        when(policyRepository.findPendingForExtraction(any(), anyInt(), anyInt())).thenReturn(
                List.of(policy(1L, "甲"), policy(2L, "乙")),
                List.of(policy(1L, "甲"), policy(2L, "乙"), policy(3L, "丙")), // 仓库侧仍返回全量
                List.of());
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // 已处理条目同轮不重复抽取（handled 过滤）
        verify(policyRepository, times(3)).findPendingForExtraction(any(), eq(3), eq(2));
        verify(policyRepository, times(3)).upsertExtract(anyLong(), any());
        verify(chatPort, times(3)).complete(any(), any());
    }

    @Test
    @DisplayName("给定合计不足50字符的短条目与正常条目，when抽取批，then短条目不送LLM不置换状态留PENDING")
    void givenShortTextPolicy_whenExtractPending_thenSkippedWithoutLlmOrUpsert() {
        PolicyRecord shortPolicy = policyWithText(1L, "短标题", "短正文"); // 3+3=6 字符 < 50（防御阈值）
        givenPending(shortPolicy, policy(2L, "国务院关于印发扎实稳住经济一揽子政策措施的通知"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // 短条目不送 LLM（仅正常条目 1 次调用）、不置换状态（仅正常条目 1 次落库）
        verify(chatPort, times(1)).complete(any(), any());
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(policyRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("给定首条落库抛异常，when抽取批，then单条隔离继续其余条目")
    void givenUpsertBlowsUpOnFirst_whenExtractPending_thenOthersStillProcessed() {
        givenPending(policy(1L, "甲"), policy(2L, "乙"), policy(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));
        doThrow(new IllegalStateException("db down")).doNothing().doNothing()
                .when(policyRepository).upsertExtract(anyLong(), any());

        assertThatCode(() -> service.extractPending()).doesNotThrowAnyException();

        verify(chatPort, times(3)).complete(any(), any()); // 异常条目不拖垮其余
        verify(policyRepository, times(3)).upsertExtract(anyLong(), any()); // 3 次尝试（1 失败 2 成功）
    }

    @Test
    @DisplayName("给定取数抛异常，when调度入口，then顶层吞异常不炸调度线程")
    void givenRepositoryBlowsUp_whenScheduled_thenSwallowed() {
        when(policyRepository.findPendingForExtraction(any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.extractPendingScheduled()).doesNotThrowAnyException();
        verify(chatPort, never()).complete(any(), any());
    }

    @Test
    @DisplayName("给定3条中1条解析失败，when抽取批，then批末告警恰一次且文案含条数")
    void givenOneParseFailedAmongThree_whenExtractPending_thenAlertOnceWithCount() {
        givenPending(policy(1L, "坏政策"), policy(2L, "好政策甲"), policy(3L, "好政策乙"));
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(INVALID_JSON)), Optional.of(outcome(INVALID_JSON)),
                Optional.of(outcome(OK_JSON)), Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // NFR-5：批末统计本轮 PARSE_FAILED 置换数——1 条 FAILED 告警恰一次，文案含条数
        verify(alertNotifier, times(1)).send(any(), eq("red"), argThat(lines ->
                lines != null && lines.stream().anyMatch(line -> line.contains("1 条"))));
    }

    @Test
    @DisplayName("给定全部抽取成功，when抽取批，then不发送解析失败告警")
    void givenAllParseSuccess_whenExtractPending_thenNoFailureAlert() {
        givenPending(policy(1L, "政策甲"), policy(2L, "政策乙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(policyRepository, times(2)).upsertExtract(anyLong(), any());
        verify(alertNotifier, never()).send(any(), any(), anyList());
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private void givenPending(PolicyRecord... policies) {
        when(policyRepository.findPendingForExtraction(any(), anyInt(), anyInt()))
                .thenReturn(List.of(policies)).thenReturn(List.of());
    }

    private static IntelligenceChatPort.ChatOutcome outcome(String text) {
        return new IntelligenceChatPort.ChatOutcome(text, 100);
    }

    /** 无抽取行的当日 PENDING raw（raw 侧字段齐备、抽取侧全 null）；正文为固定长文本（≥50 字符过短文本防御）。 */
    private static PolicyRecord policy(long id, String title) {
        return policyWithText(id, title,
                "为支持实体经济发展，进一步降低社会融资成本，人民银行决定实施相关政策措施，现将有关事项通知如下，请结合实际认真贯彻执行。");
    }

    /** 自定 title/contentText 的无抽取行 PENDING raw（短文本过滤测试用）。 */
    private static PolicyRecord policyWithText(long id, String title, String contentText) {
        return new PolicyRecord(id, "pboc", "ext-" + id, title,
                "https://www.pboc.gov.cn/" + id + ".html",
                Instant.parse("2026-10-03T00:30:00Z"), contentText, null);
    }
}
