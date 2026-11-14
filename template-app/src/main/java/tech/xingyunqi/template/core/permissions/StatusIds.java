package tech.xingyunqi.template.core.permissions;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/** 用户状态 id（与 nestjs StatusEnum 一致）。 */
public final class StatusIds {

    public static final int ACTIVE = 1;
    public static final int INACTIVE = 2;
    public static final Set<Integer> ALL = Set.of(ACTIVE, INACTIVE);

    private StatusIds() {}

    /** nestjs {@code validateUserStatusForAuth}：active 与 inactive 可登录。 */
    public static boolean allowedForAuth(@Nullable Integer statusId) {
        return statusId != null && ALL.contains(statusId);
    }
}
