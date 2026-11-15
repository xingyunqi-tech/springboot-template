package tech.xingyunqi.template.common.mail;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 设置了 {@code MAIL_HOST} 时经 SMTP 发送；生产必须设置 {@code MAIL_HOST}、{@code MAIL_DEFAULT_EMAIL}、{@code MAIL_DEFAULT_NAME}。 */
@AutoConfiguration
@EnableConfigurationProperties(MailProperties.class)
public class MailAutoConfiguration {

    @Bean
    MailTemplates mailTemplates() {
        return new MailTemplates();
    }

    @Bean
    MailTransport mailTransport(MailProperties properties, Environment environment) {
        if ("production".equalsIgnoreCase(environment.getProperty("app.env"))) {
            requireText(properties.host(), "MAIL_HOST");
            requireText(properties.defaultEmail(), "MAIL_DEFAULT_EMAIL");
            requireText(properties.defaultName(), "MAIL_DEFAULT_NAME");
        }
        return properties.configured() ? new SmtpMailTransport(properties) : new LoggingMailTransport();
    }

    private static void requireText(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set in production");
        }
    }
}
