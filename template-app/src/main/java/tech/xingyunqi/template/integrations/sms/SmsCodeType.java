package tech.xingyunqi.template.integrations.sms;

/** 验证码用途，取值即缓存键中的类型段。 */
public enum SmsCodeType {
    LOGIN("login"),
    REGISTER("register"),
    RESET_PASSWORD("reset_password"),
    CHANGE_PHONE("change_phone"),
    VERIFY_IDENTITY("verify_identity");

    private final String value;

    SmsCodeType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SmsCodeType fromValue(String value) {
        for (SmsCodeType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
}
