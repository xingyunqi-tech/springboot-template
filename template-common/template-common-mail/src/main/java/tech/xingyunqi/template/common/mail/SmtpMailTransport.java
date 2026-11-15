package tech.xingyunqi.template.common.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * SMTP 发送，TLS 语义同 nodemailer：{@code MAIL_SECURE} 为隐式 TLS；否则服务器支持时升级 STARTTLS，{@code MAIL_IGNORE_TLS} 不升级，
 * {@code MAIL_REQUIRE_TLS} 必须升级。发件人为 {@code "<MAIL_DEFAULT_NAME>" <MAIL_DEFAULT_EMAIL>}。
 */
public final class SmtpMailTransport implements MailTransport {

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int IO_TIMEOUT_MS = 30_000;

    private final JavaMailSenderImpl sender;
    private final MailProperties properties;

    public SmtpMailTransport(MailProperties properties) {
        this.properties = properties;
        this.sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        String user = properties.user();
        if (user != null && !user.isEmpty()) {
            sender.setUsername(user);
            sender.setPassword(properties.password());
        }
        sender.setJavaMailProperties(javaMailProperties(properties));
    }

    static Properties javaMailProperties(MailProperties properties) {
        Properties result = new Properties();
        String user = properties.user();
        result.setProperty("mail.smtp.auth", String.valueOf(user != null && !user.isEmpty()));
        result.setProperty("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        result.setProperty("mail.smtp.timeout", String.valueOf(IO_TIMEOUT_MS));
        result.setProperty("mail.smtp.writetimeout", String.valueOf(IO_TIMEOUT_MS));
        if (properties.isSecure()) {
            result.setProperty("mail.smtp.ssl.enable", "true");
        } else if (properties.isIgnoreTls()) {
            result.setProperty("mail.smtp.starttls.enable", "false");
        } else {
            result.setProperty("mail.smtp.starttls.enable", "true");
            result.setProperty("mail.smtp.starttls.required", String.valueOf(properties.isRequireTls()));
        }
        return result;
    }

    @Override
    public void send(OutgoingMail mail) {
        MimeMessage message = sender.createMimeMessage();
        String charset = StandardCharsets.UTF_8.name();
        try {
            message.setFrom(new InternetAddress(
                    nonNull(properties.defaultEmail()), nonNull(properties.defaultName()), charset));
            message.setRecipients(MimeMessage.RecipientType.TO, InternetAddress.parse(mail.to()));
            message.setSubject(mail.subject(), charset);
            MimeBodyPart text = new MimeBodyPart();
            text.setText(mail.text(), charset);
            MimeBodyPart html = new MimeBodyPart();
            html.setText(mail.html(), charset, "html");
            message.setContent(new MimeMultipart("alternative", text, html));
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailSendException("Failed to build mail to " + MailTransport.mask(mail.to()), e);
        }
        sender.send(message);
    }

    private static String nonNull(@Nullable String value) {
        return value == null ? "" : value;
    }
}
