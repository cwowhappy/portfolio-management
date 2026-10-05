package com.portfolio.invest.application.research;

import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierEvaluator;
import com.portfolio.invest.domain.research.FalsifierHit;
import com.portfolio.invest.domain.research.FalsifierHitResult;
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 证伪命中日终巡检（D21/D15，P3-T5）：交易日 18:43（与原则预警 18:33 错峰）扫描
 * status=ACTIVE 且 currentStage=POSITION 的项目 → {@link FalsifierEvaluator} 求值
 * （快照经 {@link MarketSnapshotAssembler}，与页面实时判定同口径）→ PREDICATE 命中行
 * append-only 落 hit 表（Ruling-18：EVENT 待人工勾选不落表）→ 新增命中聚合推送
 * {@link ResearchFalsifierNotifier}（文案不含数字，D15）。
 *
 * <p>去重（Review Focus 5）：同 falsifier 已存在未评审（review_id IS NULL）hit 行则
 * 不重复落、不重复推。journal 不写事件——扫描留痕在 hit 表自身，避免时间线噪音。
 * 尽力而为：单项目异常隔离不中断其余项目，顶层异常吞掉记 ERROR（照 PrincipleAlertService
 * 调度保护模式）。
 */
@Service
public class ResearchFalsifierScanService {

    private static final Logger log = LoggerFactory.getLogger(ResearchFalsifierScanService.class);

    private final ResearchProjectRepository repository;
    private final ResearchCheckRepository checkRepository;
    private final MarketSnapshotAssembler snapshotAssembler;
    private final ResearchFalsifierNotifier notifier;
    private final MailSender mailSender;
    private final InvestProperties props;

    public ResearchFalsifierScanService(ResearchProjectRepository repository,
                                        ResearchCheckRepository checkRepository,
                                        MarketSnapshotAssembler snapshotAssembler,
                                        ResearchFalsifierNotifier notifier,
                                        MailSender mailSender, InvestProperties props) {
        this.repository = repository;
        this.checkRepository = checkRepository;
        this.snapshotAssembler = snapshotAssembler;
        this.notifier = notifier;
        this.mailSender = mailSender;
        this.props = props;
    }

    @Scheduled(cron = "0 43 18 * * MON-FRI", zone = "Asia/Shanghai")
    public void scan() {
        try {
            doScan();
        } catch (Exception e) { // 调度保护：巡检绝不能炸调度线程
            log.error("证伪日终扫描异常", e);
        }
    }

    void doScan() {
        for (ResearchProject project : repository.findAllActiveByStage(ResearchStage.POSITION)) {
            try {
                scanProject(project);
            } catch (Exception e) { // 单项目隔离：一个项目失败不拖垮其余项目
                log.error("证伪扫描单项目异常（projectId={}）", project.id(), e);
            }
        }
    }

    private void scanProject(ResearchProject project) {
        List<Falsifier> falsifiers = repository.findStrategy(project.id())
                .map(doc -> repository.findFalsifiers(doc.id()))
                .orElse(List.of());
        if (falsifiers.isEmpty()) {
            return;
        }
        MarketSnapshot snapshot = snapshotAssembler.assemble(project.stockCode());
        Set<Long> unreviewed = new HashSet<>(checkRepository.findUnreviewedHitFalsifierIds(project.id()));
        List<String> conditionNames = new ArrayList<>();
        for (FalsifierHitResult result : FalsifierEvaluator.evaluate(falsifiers, snapshot)) {
            // 仅 PREDICATE 命中行落表（Ruling-18：EVENT hit 恒 false）；未评审命中不重复落/推
            if (!result.hit() || unreviewed.contains(result.falsifier().id())) {
                continue;
            }
            checkRepository.insertHit(new FalsifierHit(null, project.id(),
                    result.falsifier().id(), result.basis(), Instant.now()));
            conditionNames.add(conditionName(result.falsifier()));
        }
        if (conditionNames.isEmpty()) {
            return;
        }
        List<String> distinct = conditionNames.stream().distinct().toList();
        boolean ok = notifier.notify(project.userId(), project.title(), distinct);
        if (!ok) {
            log.warn("证伪提醒推送失败（projectId={}，{} 条新增命中）", project.id(), conditionNames.size());
            sendAlertMail("证伪命中提醒：" + project.title(), distinct);
        }
    }

    /** 飞书告警失败的邮件降级（B13）：SMTP 可用且配置了收件人才发；降级自身尽力而为绝不抛。
     * 文案沿用 D15 口径——仅项目名与条件名，不含数字。 */
    private void sendAlertMail(String title, List<String> bodyLines) {
        List<String> tos = props.getMail().getAlertMailTo();
        if (!mailSender.enabled() || tos.isEmpty()) {
            return;
        }
        String subject = "[invest 告警] " + title;
        String text = title + "\n" + String.join("\n", bodyLines);
        for (String to : tos) {
            try {
                mailSender.send(to, subject, text);
            } catch (Exception e) { // 单个收件人失败不阻断其余，也绝不反向拖垮扫描
                log.error("告警邮件降级失败 to={}", to, e);
            }
        }
    }

    /** 条件名取谓词维度文案（与前端 FALSIFIER_PREDICATE_LABELS 同词表），保证不含数字（D15）。 */
    private static String conditionName(Falsifier falsifier) {
        return switch (falsifier.predicate()) {
            case PRICE_BELOW -> "价格跌破";
            case PRICE_ABOVE -> "价格升破";
            case PE_ABOVE -> "PE 高于";
            case PB_ABOVE -> "PB 高于";
        };
    }
}
