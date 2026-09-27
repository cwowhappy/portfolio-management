package com.portfolio.invest.application.auth;

/** 发信端口：application 只见语义，SMTP 细节在 infrastructure.mail。 */
public interface MailSender {
    boolean enabled();
    void send(String to, String subject, String text);
}
