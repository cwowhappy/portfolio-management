package com.portfolio.invest.support;

import com.portfolio.invest.application.auth.MailSender;
import java.util.ArrayList;
import java.util.List;

/** 集成测试发信桩：记录全部外发邮件供断言（替代 GreenMail——本仓库不做真实 SMTP 线协议测试）。 */
public class RecordingMailSender implements MailSender {

    public record SentMail(String to, String subject, String text) {}

    public final List<SentMail> sent = new ArrayList<>();

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void send(String to, String subject, String text) {
        sent.add(new SentMail(to, subject, text));
    }
}
