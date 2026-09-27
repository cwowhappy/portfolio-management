package com.portfolio.invest.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailSenderTest {

    private InvestProperties.Mail fullMail() {
        InvestProperties.Mail m = new InvestProperties.Mail();
        m.setSmtpHost("smtp.qiye.aliyun.com");
        m.setSmtpPort(465);
        m.setSmtpUsername("noreply@x.com");
        m.setSmtpPassword("secret");
        m.setFrom("noreply@x.com");
        return m;
    }

    @DisplayName("四要素齐备才视为已配置")
    @Test
    void givenMissingAny_whenEnabled_thenFalse() {
        assertThat(new SmtpMailSender(fullMail(), mock(JavaMailSender.class)).enabled()).isTrue();
        InvestProperties.Mail noHost = fullMail();
        noHost.setSmtpHost("");
        assertThat(new SmtpMailSender(noHost, mock(JavaMailSender.class)).enabled()).isFalse();
    }

    @DisplayName("发送携带发件人与中文主题正文")
    @Test
    void whenSend_thenFromSubjectTextSet() {
        JavaMailSender javaSender = mock(JavaMailSender.class);
        SmtpMailSender sender = new SmtpMailSender(fullMail(), javaSender);

        sender.send("to@x.com", "九和验证码", "您的验证码 123456");

        verify(javaSender).send(any(SimpleMailMessage.class));
        org.mockito.ArgumentCaptor<SimpleMailMessage> captor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("noreply@x.com");
        assertThat(captor.getValue().getTo()).containsExactly("to@x.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("九和验证码");
        assertThat(captor.getValue().getText()).isEqualTo("您的验证码 123456");
    }
}
