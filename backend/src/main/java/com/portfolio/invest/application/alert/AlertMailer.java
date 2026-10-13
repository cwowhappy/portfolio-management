package com.portfolio.invest.application.alert;

import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 告警邮件横切组件（2026-10-12 审查 B1）：承载「飞书告警失败的邮件降级」完整关注点——
 * SMTP 可用且配置了收件人才发；主题统一 {@code "[invest 告警] "} 前缀；正文 = 标题 + 换行
 * 拼接正文行；单个收件人失败不阻断其余，降级自身尽力而为绝不抛（调度巡检绝不能被邮件
 * 反拖垮）。归一 ResearchFalsifierScanService / MacroHealthService / PrincipleAlertService /
 * EvalHarvester 四处誊写（B13 邮件降级引入时的逐处复制）。
 */
@Component
public class AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);

    private final MailSender mailSender;
    private final InvestProperties props;

    public AlertMailer(MailSender mailSender, InvestProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    /** 飞书告警失败的邮件降级：SMTP 可用且配置了收件人才发；降级自身尽力而为绝不抛。 */
    public void sendAlertMail(String title, List<String> bodyLines) {
        List<String> tos = props.getMail().getAlertMailTo();
        if (!mailSender.enabled() || tos.isEmpty()) {
            return;
        }
        String subject = "[invest 告警] " + title;
        String text = title + "\n" + String.join("\n", bodyLines);
        for (String to : tos) {
            try {
                mailSender.send(to, subject, text);
            } catch (Exception e) { // 单个收件人失败不阻断其余，也绝不反向拖垮调用方主流程
                log.error("告警邮件降级失败 to={}", to, e);
            }
        }
    }
}
