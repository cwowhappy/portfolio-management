package com.portfolio.invest.infrastructure.mail;

import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** SMTP 发信（阿里云企业邮箱 465 SSL）。手工构造 JavaMailSenderImpl——opt-in 语义由 invest.mail.* 自持，不用 spring.mail 自动配置。
 * e2e/联调固定码（test-fixed-code 非空 + test-mode 开，生产必须留空）：视为已配置并短路真实发信——验证码恒为约定值，客户端（e2e）已知，无需 SMTP。 */
@Component
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final InvestProperties.Mail props;
    private final JavaMailSender sender;

    @Autowired
    public SmtpMailSender(InvestProperties props) {
        this(props.getMail(), buildSender(props.getMail()));
        // 固定码生产误配守卫（对照 REMEMBER_ME_KEY 启动强校验先例）：test-fixed-code 泄入部署 env
        // 而未显式开 MAIL_TEST_MODE 时拒绝启动——否则任意人可用已知码重置任意 USER 密码
        if (!props.getMail().getTestFixedCode().isBlank() && !props.getMail().isTestMode()) {
            throw new IllegalStateException(
                    "MAIL_TEST_FIXED_CODE 已设置但未开 MAIL_TEST_MODE——固定码仅限 e2e（scripts/e2e-backend.sh 自动设置）；生产环境严禁，任意码可重置任意账号密码");
        }
    }

    /** 测试构造器：注入 mock JavaMailSender。 */
    SmtpMailSender(InvestProperties.Mail props, JavaMailSender sender) {
        this.props = props;
        this.sender = sender;
    }

    private static JavaMailSender buildSender(InvestProperties.Mail m) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(m.getSmtpHost());
        impl.setPort(m.getSmtpPort());
        impl.setUsername(m.getSmtpUsername());
        impl.setPassword(m.getSmtpPassword());
        impl.setDefaultEncoding("UTF-8");
        Properties java = new Properties();
        java.put("mail.smtp.auth", "true");
        java.put("mail.smtp.ssl.enable", "true");
        java.put("mail.smtp.connectiontimeout", "5000");
        java.put("mail.smtp.timeout", "10000");
        impl.setJavaMailProperties(java);
        return impl;
    }

    @Override
    public boolean enabled() {
        // e2e/联调固定码模式：无 SMTP 四要素也可发码（send 短路，不触达真实 SMTP）
        return props.configured() || !props.getTestFixedCode().isBlank();
    }

    @Override
    public void send(String to, String subject, String text) {
        if (!props.getTestFixedCode().isBlank()) {
            log.info("[mail] 固定码模式跳过真实发信：to={} subject={}", to, subject);
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.getFrom());
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text);
        sender.send(msg);
    }
}
