package tech.xingyunqi.template.integrations.mail;

/** 模板邮件投递；调用方在事务提交后调用，渲染时使用调用线程的请求语言。 */
public interface Mailer {

    void send(MailMessage message);
}
