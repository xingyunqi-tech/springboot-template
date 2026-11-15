package tech.xingyunqi.template.common.mail;

public interface MailTransport {

    void send(OutgoingMail mail);

    /** 收件人脱敏：保留本地部分首字符与域名。 */
    static String mask(String address) {
        int at = address.indexOf('@');
        if (at < 0) {
            return "***";
        }
        return (at == 0 ? "" : address.substring(0, 1)) + "***" + address.substring(at);
    }
}
