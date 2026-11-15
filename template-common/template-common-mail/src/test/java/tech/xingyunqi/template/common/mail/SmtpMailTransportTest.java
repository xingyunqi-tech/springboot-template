package tech.xingyunqi.template.common.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmtpMailTransportTest {

    GreenMail smtp;

    @BeforeEach
    void start() {
        smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP).dynamicPort());
        smtp.start();
    }

    @AfterEach
    void stop() {
        smtp.stop();
    }

    static MailProperties properties(int port, String ignoreTls, String secure, String requireTls) {
        return new MailProperties(
                "127.0.0.1", port, null, null, "noreply@example.com", "Api", ignoreTls, secure, requireTls);
    }

    @Test
    @DisplayName("发件人、收件人、主题与 text / html 两部分")
    void sendsMultipartAlternative() throws Exception {
        new SmtpMailTransport(properties(smtp.getSmtp().getPort(), "true", null, null))
                .send(new OutgoingMail("to@example.com", "确认电子邮件", "http://x 确认电子邮件", "<b>hi</b>"));

        MimeMessage[] messages = smtp.getReceivedMessages();
        assertThat(messages).hasSize(1);
        MimeMessage message = messages[0];
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("noreply@example.com");
        assertThat(from.getPersonal()).isEqualTo("Api");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("to@example.com");
        assertThat(message.getSubject()).isEqualTo("确认电子邮件");
        MimeMultipart content = (MimeMultipart) message.getContent();
        assertThat(content.getContentType()).startsWith("multipart/alternative");
        assertThat(content.getBodyPart(0).getContent()).isEqualTo("http://x 确认电子邮件");
        assertThat(content.getBodyPart(1).getContentType()).startsWith("text/html");
        assertThat(content.getBodyPart(1).getContent()).isEqualTo("<b>hi</b>");
    }

    @Test
    @DisplayName("TLS 语义同 nodemailer：secure 隐式 TLS，ignoreTLS 不升级，requireTLS 必须升级，默认按需升级")
    void tlsSettingsFollowNodemailer() {
        Properties secure = SmtpMailTransport.javaMailProperties(properties(465, null, "true", null));
        assertThat(secure.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        Properties ignore = SmtpMailTransport.javaMailProperties(properties(587, "true", null, "true"));
        assertThat(ignore.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        Properties require = SmtpMailTransport.javaMailProperties(properties(587, null, null, "true"));
        assertThat(require.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(require.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        Properties optional = SmtpMailTransport.javaMailProperties(properties(587, "TRUE", null, null));
        assertThat(optional.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(optional.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
        assertThat(optional.getProperty("mail.smtp.auth")).isEqualTo("false");
    }
}
