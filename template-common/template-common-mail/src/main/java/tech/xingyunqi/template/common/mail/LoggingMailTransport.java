package tech.xingyunqi.template.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 非生产且未设置 {@code MAIL_HOST} 时只记录日志。 */
public final class LoggingMailTransport implements MailTransport {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingMailTransport.class);

    @Override
    public void send(OutgoingMail mail) {
        LOG.info(
                "MAIL_HOST is not set, mail \"{}\" to {} is only logged",
                mail.subject(),
                MailTransport.mask(mail.to()));
    }
}
