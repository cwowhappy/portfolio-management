package com.portfolio.invest.infrastructure.mail;

import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.config.InvestProperties;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** SMTP 发信（阿里云企业邮箱 465 SSL）。手工构造 JavaMailSenderImpl——opt-in 语义由 invest.mail.* 自持，不用 spring.mail 自动配置。 */
@Component
public class SmtpMailSender implements MailSender {

    private final InvestProperties.Mail props;
    private final JavaMailSender sender;

    @Autowired
    public SmtpMailSender(InvestProperties props) {
        this(props.getMail(), buildSender(props.getMail()));
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
        return props.configured();
    }

    @Override
    public void send(String to, String subject, String text) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.getFrom());
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text);
        sender.send(msg);
    }
}
