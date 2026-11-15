package tech.xingyunqi.template.common.mail;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code MAIL_*}：三个布尔只认字符串 {@code true}。 */
@ConfigurationProperties("app.mail")
public record MailProperties(
        @Nullable String host,
        int port,
        @Nullable String user,
        @Nullable String password,
        @Nullable String defaultEmail,
        @Nullable String defaultName,
        @Nullable String ignoreTls,
        @Nullable String secure,
        @Nullable String requireTls) {

    public boolean configured() {
        return host != null && !host.isBlank();
    }

    public boolean isIgnoreTls() {
        return "true".equals(ignoreTls);
    }

    public boolean isSecure() {
        return "true".equals(secure);
    }

    public boolean isRequireTls() {
        return "true".equals(requireTls);
    }

    @Override
    public String toString() {
        return "MailProperties[host=" + host + ", port=" + port + ", user=" + user + "]";
    }
}
