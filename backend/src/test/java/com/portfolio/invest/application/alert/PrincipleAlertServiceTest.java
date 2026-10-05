package com.portfolio.invest.application.alert;

import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrincipleAlertServiceTest {

    private final PrincipleRuleRepository ruleRepo = mock(PrincipleRuleRepository.class);
    private final PortfolioRepository portfolioRepo = mock(PortfolioRepository.class);
    private final ValuationRepository valuationRepo = mock(ValuationRepository.class);
    private final ValuationDailyPort valDaily = mock(ValuationDailyPort.class);
    private final AlertNotifier notifier = mock(AlertNotifier.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final PrincipleAlertEvaluator evaluator = new PrincipleAlertEvaluator();
    private InvestProperties props;

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        props.getIm().setAppId("cli_x");
        props.getIm().setAppSecret("s");
        props.getIm().setChatId("oc_x");
        when(valuationRepo.findAllIndustryMappings()).thenReturn(List.of());
    }

    private PrincipleAlertService service(Clock clock) {
        return new PrincipleAlertService(ruleRepo, portfolioRepo, valuationRepo, valDaily, notifier,
                mailSender, userRepo, evaluator, props, clock);
    }

    private void givenOwnerAndPortfolio() {
        props.getIm().setOwnerUsername("admin");
        User user = mock(User.class);
        when(user.id()).thenReturn(7L);
        when(userRepo.findByUsername("admin")).thenReturn(Optional.of(user));
        when(portfolioRepo.findPortfolioByUserId(7L)).thenReturn(Optional.of(mock(Portfolio.class)));
        when(portfolioRepo.findPositionsByPortfolioId(any())).thenReturn(List.of());
    }

    /** 单笔有效持仓（quantity > 0）：覆盖"无有效持仓提前返回"之后的分支（快照门禁/评估/吞错）。 */
    private void givenOnePosition() {
        Position p1 = mock(Position.class);
        when(p1.stockCode()).thenReturn("600519");
        when(p1.stockName()).thenReturn("贵州茅台");
        when(p1.quantity()).thenReturn(new BigDecimal("100"));
        when(portfolioRepo.findPositionsByPortfolioId(any())).thenReturn(List.of(p1));
    }

    @Test
    @DisplayName("给定飞书配置缺失，when巡检，then直接跳过且不触达任何仓储")
    void given配置缺失_when巡检_then跳过() {
        props.getIm().setAppId("");
        service(fixedClock()).patrol();
        verify(userRepo, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("给定owner不存在，when巡检，then跳过")
    void given无owner_when巡检_then跳过() {
        props.getIm().setOwnerUsername("admin");
        when(userRepo.findByUsername("admin")).thenReturn(Optional.empty());
        service(fixedClock()).patrol();
        verify(portfolioRepo, never()).findPortfolioByUserId(any());
    }

    @Test
    @DisplayName("给定最新快照非今日（节假日/未就绪），when巡检，then不评估不推送")
    void given快照非今日_when巡检_then跳过() {
        givenOwnerAndPortfolio();
        givenOnePosition();
        LocalDate today = LocalDate.parse("2026-09-25");
        when(valDaily.latestTradingDay()).thenReturn(Optional.of(today.minusDays(1)));
        service(fixedClock(today)).patrol();
        verify(valDaily, never()).snapshots(any(), anyCollection());
        verify(notifier, never()).send(anyString(), anyString(), anyList());
    }

    /** 双持仓超限夹具（单票上限 0.20 → 违规行含 600519 与阈值 0.20）+ 当日快照就绪。 */
    private void givenViolatingPositions(LocalDate today) {
        givenOwnerAndPortfolio();
        Position p1 = mock(Position.class);
        when(p1.stockCode()).thenReturn("600519");
        when(p1.stockName()).thenReturn("贵州茅台");
        when(p1.quantity()).thenReturn(new BigDecimal("100"));
        Position p2 = mock(Position.class);
        when(p2.stockCode()).thenReturn("000858");
        when(p2.stockName()).thenReturn("五粮液");
        when(p2.quantity()).thenReturn(new BigDecimal("100"));
        when(portfolioRepo.findPositionsByPortfolioId(any())).thenReturn(List.of(p1, p2));
        when(valDaily.latestTradingDay()).thenReturn(Optional.of(today));
        when(valDaily.snapshots(any(), anyCollection())).thenReturn(Map.of(
                "600519", new StockMetric("600519", "贵州茅台", new BigDecimal("2500"), null, null),
                "000858", new StockMetric("000858", "五粮液", new BigDecimal("7500"), null, null)));
        when(ruleRepo.findByUserId(7L)).thenReturn(List.of(
                PrincipleRule.create(7L, PrincipleMetric.SINGLE_POSITION_RATIO, new BigDecimal("0.20"),
                        true, "单票上限", Instant.now())));
    }

    @Test
    @DisplayName("给定超限持仓，when巡检，then组装卡片行并推送")
    void given超限持仓_when巡检_then推送卡片() {
        LocalDate today = LocalDate.parse("2026-09-25");
        givenViolatingPositions(today);
        service(fixedClock(today)).patrol();
        ArgumentCaptor<List<String>> lines = ArgumentCaptor.forClass(List.class);
        verify(notifier).send(anyString(), anyString(), lines.capture());
        assertThat(String.join("\n", lines.getValue())).contains("600519").contains("0.2");
    }

    @Test
    @DisplayName("给定飞书失败且邮件可用+两个收件人，when巡检，then告警邮件降级各发一封（含标题与违规摘要）")
    void given飞书失败且邮件可用_when巡检_then告警邮件降级() {
        LocalDate today = LocalDate.parse("2026-09-25");
        givenViolatingPositions(today);
        props.getMail().setAlertMailTo(List.of("ops@x.com", "dev@x.com"));
        when(mailSender.enabled()).thenReturn(true);
        when(notifier.send(anyString(), anyString(), anyList())).thenReturn(false);

        service(fixedClock(today)).patrol();

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(2)).send(to.capture(), subject.capture(), text.capture());
        assertThat(to.getAllValues()).containsExactly("ops@x.com", "dev@x.com");
        assertThat(subject.getAllValues()).allSatisfy(s -> assertThat(s).contains("投资原则预警"));
        assertThat(text.getAllValues()).allSatisfy(t -> assertThat(t).contains("600519").contains("0.2"));
    }

    @Test
    @DisplayName("给定飞书推送成功，when巡检，then不触发邮件降级")
    void given飞书成功_when巡检_then不发邮件() {
        LocalDate today = LocalDate.parse("2026-09-25");
        givenViolatingPositions(today);
        props.getMail().setAlertMailTo(List.of("ops@x.com"));
        when(notifier.send(anyString(), anyString(), anyList())).thenReturn(true);

        service(fixedClock(today)).patrol();

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("给定收件人未配置，when飞书失败，then维持 WARN 不发邮件")
    void given收件人未配置_when飞书失败_then不发邮件() {
        LocalDate today = LocalDate.parse("2026-09-25");
        givenViolatingPositions(today);
        when(mailSender.enabled()).thenReturn(true);
        when(notifier.send(anyString(), anyString(), anyList())).thenReturn(false);

        service(fixedClock(today)).patrol();

        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定 SMTP 未启用，when飞书失败，then维持 WARN 不降级发邮件")
    void given邮件未启用_when飞书失败_then不发邮件() {
        LocalDate today = LocalDate.parse("2026-09-25");
        givenViolatingPositions(today);
        props.getMail().setAlertMailTo(List.of("ops@x.com"));
        when(mailSender.enabled()).thenReturn(false);
        when(notifier.send(anyString(), anyString(), anyList())).thenReturn(false);

        service(fixedClock(today)).patrol();

        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定首个收件人发信抛异常，when降级，then隔离该收件人继续发其余且绝不抛")
    void given首个收件人抛异常_when降级_then其余仍发且不抛() {
        LocalDate today = LocalDate.parse("2026-09-25");
        givenViolatingPositions(today);
        props.getMail().setAlertMailTo(List.of("bad@x.com", "ops@x.com"));
        when(mailSender.enabled()).thenReturn(true);
        when(notifier.send(anyString(), anyString(), anyList())).thenReturn(false);
        doThrow(new IllegalStateException("smtp refused"))
                .when(mailSender).send(eq("bad@x.com"), anyString(), anyString());

        assertThatCode(() -> service(fixedClock(today)).patrol()).doesNotThrowAnyException();
        verify(mailSender).send(eq("ops@x.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("给定全部健康，when巡检，then不推送")
    void given无违规_when巡检_then不推送() {
        givenOwnerAndPortfolio();
        givenOnePosition();
        LocalDate today = LocalDate.parse("2026-09-25");
        when(valDaily.latestTradingDay()).thenReturn(Optional.of(today));
        when(valDaily.snapshots(any(), anyCollection())).thenReturn(Map.of());
        when(ruleRepo.findByUserId(7L)).thenReturn(List.of());
        service(fixedClock(today)).patrol();
        verify(notifier, never()).send(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定取数抛异常，when巡检，then吞掉不抛（调度保护）")
    void given取数异常_when巡检_then吞掉() {
        givenOwnerAndPortfolio();
        givenOnePosition();
        when(valDaily.latestTradingDay()).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> service(fixedClock()).patrol()).doesNotThrowAnyException();
    }

    private Clock fixedClock(LocalDate date) {
        return Clock.fixed(Instant.parse(date + "T10:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private Clock fixedClock() {
        return fixedClock(LocalDate.parse("2026-09-25"));
    }
}
