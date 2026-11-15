package tech.xingyunqi.template.infrastructure.audit;

/** 审计动作（nestjs {@code AuditAction}）。 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    LOGIN,
    LOGOUT,
    PASSWORD_CHANGE,
    PASSWORD_RESET,
    PERMISSION_CHANGE,
    SUBMIT,
    PUBLISH,
    OFFLINE,
    REJECT,
    COPY,
    MOVE
}
