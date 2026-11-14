package tech.xingyunqi.template.integrations.mail;

/** 邮件发送；调用方在事务提交后调用。 */
public interface Mailer {

    void send(MailMessage message);
}
