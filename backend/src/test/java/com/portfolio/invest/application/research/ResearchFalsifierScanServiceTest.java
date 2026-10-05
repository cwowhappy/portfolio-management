package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierHit;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P3-T5 证伪日终扫描切片（mock 仓储/组装器/通知端口）：
 * 只扫 ACTIVE+POSITION、仅 PREDICATE 命中落表（Ruling-18）、未评审命中去重不重复落/推
 * （Review Focus 5）、通知文案不含数字（D15）、单项目隔离 + 调度保护。
 */
class ResearchFalsifierScanServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    private final ResearchProjectRepository repository = mock(ResearchProjectRepository.class);
    private final ResearchCheckRepository checkRepository = mock(ResearchCheckRepository.class);
    private final MarketSnapshotAssembler snapshotAssembler = mock(MarketSnapshotAssembler.class);
    private final ResearchFalsifierNotifier notifier = mock(ResearchFalsifierNotifier.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final InvestProperties props = new InvestProperties();
    private ResearchFalsifierScanService service;

    @BeforeEach
    void setUp() {
        service = new ResearchFalsifierScanService(repository, checkRepository,
                snapshotAssembler, notifier, mailSender, props);
        when(notifier.notify(anyLong(), any(), anyList())).thenReturn(true);
    }

    private static ResearchProject project(Long id, Long userId, String stockCode) {
        return ResearchProject.reconstitute(id, userId, stockCode, "贵州茅台", "801120",
                "茅台扩产研究", ResearchStage.POSITION, ProjectStatus.ACTIVE, true, 0L, NOW, NOW);
    }

    private static StrategyDoc strategy(Long projectId) {
        return StrategyDoc.reconstitute(7L, projectId, StrategyState.FINALIZED, "扩产逻辑",
                new BigDecimal("12"), new BigDecimal("16"), "两成仓", "回踩买入",
                "需求不及预期", NOW, 0L, NOW, NOW);
    }

    private static Falsifier predicateFalsifier(Long id, FalsifierPredicate predicate, String threshold) {
        return Falsifier.reconstitute(id, 7L, FalsifierKind.PREDICATE, predicate,
                new BigDecimal(threshold), false, "跌破下限", true, null, NOW, NOW);
    }

    private static Falsifier eventFalsifier(Long id) {
        return Falsifier.reconstitute(id, 7L, FalsifierKind.EVENT, null, null,
                false, "央行加息", true, null, NOW, NOW);
    }

    /** 单项目 + 单 PRICE_BELOW 条件（收盘 12.34 &lt; 下限 13.00 命中）默认场景。 */
    private void givenHitProject() {
        when(repository.findAllActiveByStage(ResearchStage.POSITION))
                .thenReturn(List.of(project(5L, 1L, "600519")));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(5L)));
        when(repository.findFalsifiers(7L))
                .thenReturn(List.of(predicateFalsifier(21L, FalsifierPredicate.PRICE_BELOW, "13.00")));
        when(snapshotAssembler.assemble("600519"))
                .thenReturn(new MarketSnapshot(new BigDecimal("12.34"), null, null, "东财收盘 2026-09-28"));
        when(checkRepository.findUnreviewedHitFalsifierIds(5L)).thenReturn(List.of());
    }

    @Test
    @DisplayName("给定ACTIVE+POSITION项目谓词命中，when扫描，then按阶段口径取数、落一条hit并推最小文案")
    void givenPredicateHit_whenScan_thenInsertHitAndNotifyMinimalCopy() {
        givenHitProject();

        service.scan();

        // 只扫 ACTIVE+POSITION（仓库口径即扫描口径，不走 findByUserId 全量）
        verify(repository).findAllActiveByStage(ResearchStage.POSITION);
        verify(repository, never()).findByUserId(any(), any());

        ArgumentCaptor<FalsifierHit> hitCaptor = ArgumentCaptor.forClass(FalsifierHit.class);
        verify(checkRepository).insertHit(hitCaptor.capture());
        FalsifierHit hit = hitCaptor.getValue();
        assertThat(hit.projectId()).isEqualTo(5L);
        assertThat(hit.falsifierId()).isEqualTo(21L);
        assertThat(hit.basis()).contains("12.34").contains("13.00"); // 可解释 basis 含数字留痕站内

        ArgumentCaptor<Long> userCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
        verify(notifier).notify(userCaptor.capture(), titleCaptor.capture(), namesCaptor.capture());
        assertThat(userCaptor.getValue()).isEqualTo(1L);
        assertThat(titleCaptor.getValue()).isEqualTo("茅台扩产研究");
        // D15：条件名不含价格/阈值数字
        assertThat(String.join("、", namesCaptor.getValue()))
                .contains("价格跌破")
                .doesNotContain("13.00")
                .doesNotContain("13")
                .doesNotContain("12.34");
    }

    @Test
    @DisplayName("给定同条件已存在未评审hit行，when扫描，then不重复落库也不重复推送")
    void givenUnreviewedHitRow_whenScan_thenNoReinsertNoRepush() {
        givenHitProject();
        when(checkRepository.findUnreviewedHitFalsifierIds(5L)).thenReturn(List.of(21L));

        service.scan();

        verify(checkRepository, never()).insertHit(any(FalsifierHit.class));
        verify(notifier, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("给定事件待勾选与缺数据条件并存，when扫描，then仅PREDICATE命中行落表（Ruling-18）")
    void givenEventAndNoData_whenScan_thenOnlyPredicateHitRecorded() {
        when(repository.findAllActiveByStage(ResearchStage.POSITION))
                .thenReturn(List.of(project(5L, 1L, "600519")));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(5L)));
        when(repository.findFalsifiers(7L)).thenReturn(List.of(
                predicateFalsifier(21L, FalsifierPredicate.PRICE_BELOW, "13.00"), // 命中
                eventFalsifier(22L),                                             // EVENT 待勾选不落表
                predicateFalsifier(23L, FalsifierPredicate.PE_ABOVE, "30.00"))); // PE 缺数据跳过
        when(snapshotAssembler.assemble("600519"))
                .thenReturn(new MarketSnapshot(new BigDecimal("12.34"), null, null, null));
        when(checkRepository.findUnreviewedHitFalsifierIds(5L)).thenReturn(List.of());

        service.scan();

        ArgumentCaptor<FalsifierHit> hitCaptor = ArgumentCaptor.forClass(FalsifierHit.class);
        verify(checkRepository, times(1)).insertHit(hitCaptor.capture());
        assertThat(hitCaptor.getValue().falsifierId()).isEqualTo(21L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
        verify(notifier).notify(any(), any(), namesCaptor.capture());
        assertThat(namesCaptor.getValue()).containsExactly("价格跌破");
    }

    @Test
    @DisplayName("给定全部条件未命中，when扫描，then不落库不推送")
    void givenNoHit_whenScan_thenNoInsertNoNotify() {
        givenHitProject();
        when(snapshotAssembler.assemble("600519"))
                .thenReturn(new MarketSnapshot(new BigDecimal("14.00"), null, null, null)); // 高于下限未命中

        service.scan();

        verify(checkRepository, never()).insertHit(any(FalsifierHit.class));
        verify(notifier, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("给定项目无策略文档，when扫描，then不组快照不推送")
    void givenNoStrategy_whenScan_thenSkipped() {
        when(repository.findAllActiveByStage(ResearchStage.POSITION))
                .thenReturn(List.of(project(5L, 1L, "600519")));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());

        service.scan();

        verify(snapshotAssembler, never()).assemble(any());
        verify(notifier, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("给定首个项目扫描异常，when扫描，then隔离异常继续扫其余项目（单项目隔离）")
    void givenFirstProjectBlowsUp_whenScan_thenOthersStillScanned() {
        when(repository.findAllActiveByStage(ResearchStage.POSITION)).thenReturn(List.of(
                project(5L, 1L, "600519"), project(6L, 2L, "000858")));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(5L)));
        when(repository.findFalsifiers(7L))
                .thenReturn(List.of(predicateFalsifier(21L, FalsifierPredicate.PRICE_BELOW, "13.00")));
        when(snapshotAssembler.assemble("600519")).thenThrow(new IllegalStateException("行情源炸了"));
        when(repository.findStrategy(6L)).thenReturn(Optional.of(strategy(6L)));
        // 两项目同策略 id 7L：统一 stub 两个可命中条件
        when(repository.findFalsifiers(7L)).thenReturn(List.of(
                predicateFalsifier(24L, FalsifierPredicate.PRICE_BELOW, "13.00"),
                predicateFalsifier(25L, FalsifierPredicate.PB_ABOVE, "8.00")));
        when(snapshotAssembler.assemble("000858"))
                .thenReturn(new MarketSnapshot(new BigDecimal("12.34"), null, new BigDecimal("9.00"), null));
        when(checkRepository.findUnreviewedHitFalsifierIds(anyLong())).thenReturn(List.of());

        assertThatCode(() -> service.scan()).doesNotThrowAnyException();

        verify(checkRepository, times(2)).insertHit(any(FalsifierHit.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
        verify(notifier).notify(any(), any(), namesCaptor.capture());
        assertThat(namesCaptor.getValue()).containsExactly("价格跌破", "PB 高于");
    }

    @Test
    @DisplayName("给定推送返回失败，when扫描，then吞掉不抛且hit已留痕")
    void givenNotifyFails_whenScan_thenSwallowedAndHitPersisted() {
        givenHitProject();
        when(notifier.notify(anyLong(), any(), anyList())).thenReturn(false);

        assertThatCode(() -> service.scan()).doesNotThrowAnyException();
        verify(checkRepository).insertHit(any(FalsifierHit.class));
    }

    @Test
    @DisplayName("给定飞书失败且邮件可用+两个收件人，when扫描，then告警邮件降级各发一封（含项目名与条件摘要）")
    void given飞书失败且邮件可用_when扫描_then告警邮件降级() {
        givenHitProject();
        when(notifier.notify(anyLong(), any(), anyList())).thenReturn(false);
        props.getMail().setAlertMailTo(List.of("ops@x.com", "dev@x.com"));
        when(mailSender.enabled()).thenReturn(true);

        service.scan();

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(2)).send(to.capture(), subject.capture(), text.capture());
        assertThat(to.getAllValues()).containsExactly("ops@x.com", "dev@x.com");
        assertThat(subject.getAllValues())
                .allSatisfy(s -> assertThat(s).contains("证伪").contains("茅台扩产研究"));
        assertThat(text.getAllValues())
                .allSatisfy(t -> assertThat(t).contains("茅台扩产研究").contains("价格跌破"));
    }

    @Test
    @DisplayName("给定飞书推送成功，when扫描，then不触发邮件降级")
    void given飞书成功_when扫描_then不发邮件() {
        givenHitProject(); // setUp 已 stub notify=true
        props.getMail().setAlertMailTo(List.of("ops@x.com"));

        service.scan();

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("给定收件人未配置，when飞书失败，then维持 WARN 不发邮件")
    void given收件人未配置_when飞书失败_then不发邮件() {
        givenHitProject();
        when(notifier.notify(anyLong(), any(), anyList())).thenReturn(false);
        when(mailSender.enabled()).thenReturn(true);

        service.scan();

        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定 SMTP 未启用，when飞书失败，then维持 WARN 不降级发邮件")
    void given邮件未启用_when飞书失败_then不发邮件() {
        givenHitProject();
        when(notifier.notify(anyLong(), any(), anyList())).thenReturn(false);
        props.getMail().setAlertMailTo(List.of("ops@x.com"));
        when(mailSender.enabled()).thenReturn(false);

        service.scan();

        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定首个收件人发信抛异常，when降级，then隔离该收件人继续发其余且绝不抛")
    void given首个收件人抛异常_when降级_then其余仍发且不抛() {
        givenHitProject();
        when(notifier.notify(anyLong(), any(), anyList())).thenReturn(false);
        props.getMail().setAlertMailTo(List.of("bad@x.com", "ops@x.com"));
        when(mailSender.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("smtp refused"))
                .when(mailSender).send(eq("bad@x.com"), anyString(), anyString());

        assertThatCode(() -> service.scan()).doesNotThrowAnyException();
        verify(mailSender).send(eq("ops@x.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("给定取数抛异常，when扫描，then吞掉不抛（调度保护）")
    void givenRepositoryBlowsUp_whenScan_thenSwallowed() {
        when(repository.findAllActiveByStage(ResearchStage.POSITION))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.scan()).doesNotThrowAnyException();
        verify(notifier, never()).notify(any(), any(), any());
    }
}
