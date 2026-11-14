package tech.xingyunqi.template.integrations.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 未配置 SMTP 时只记录日志。 */
@Component
class LoggingMailer implements Mailer {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void send(MailMessage message) {
        LOG.info(
                "mail {} to {}: {}",
                message.template(),
                message.to(),
                message.data().keySet());
    }
}
