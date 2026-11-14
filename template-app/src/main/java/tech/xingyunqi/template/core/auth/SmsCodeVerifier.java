package tech.xingyunqi.template.core.auth;

/** 短信验证码校验（身份验证场景）；成功时消费验证码。 */
public interface SmsCodeVerifier {

    boolean verifyIdentity(String phone, String code);
}
