package tech.xingyunqi.template.integrations.mail;

import java.util.Map;

/** 待发送的模板邮件：模板名（activation / confirm-new-email / reset-password）、收件人与模板数据。 */
public record MailMessage(String template, String to, Map<String, Object> data) {}
