package tech.xingyunqi.template.core.permissions;

/** 权限枚举，值与 nestjs {@code Permission} 相同。 */
public enum Permission {
    USER_READ("user:read"),
    USER_CREATE("user:create"),
    USER_UPDATE("user:update"),
    USER_DELETE("user:delete"),
    USER_LIST("user:list"),
    ADDRESS_READ("address:read"),
    ADDRESS_CREATE("address:create"),
    ADDRESS_UPDATE("address:update"),
    ADDRESS_DELETE("address:delete"),
    FAVORITE_READ("favorite:read"),
    FAVORITE_CREATE("favorite:create"),
    FAVORITE_DELETE("favorite:delete"),
    FEEDBACK_READ("feedback:read"),
    FEEDBACK_CREATE("feedback:create"),
    FEEDBACK_LIST("feedback:list"),
    FILE_UPLOAD("file:upload"),
    FILE_READ("file:read"),
    FILE_DELETE("file:delete"),
    SESSION_READ("session:read"),
    SESSION_DELETE("session:delete"),
    SYSTEM_CONFIG("system:config"),
    SYSTEM_METRICS("system:metrics"),
    PAYMENT_REFUND("payment:refund"),
    AUDIT_READ("audit:read"),
    AUDIT_LIST("audit:list"),
    PRACTICE_WRITE("practice:write"),
    AI_CHAT("ai:chat"),
    AI_GRADING("ai:grading"),
    CONTENT_MANAGE("content:manage"),
    CONTENT_REVIEW("content:review"),
    AI_GENERATE("ai:generate"),
    AI_ADMIN("ai:admin");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
