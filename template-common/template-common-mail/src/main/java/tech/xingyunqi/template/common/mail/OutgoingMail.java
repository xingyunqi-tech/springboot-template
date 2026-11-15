package tech.xingyunqi.template.common.mail;

/** 渲染完成、待发送的邮件。 */
public record OutgoingMail(String to, String subject, String text, String html) {}
