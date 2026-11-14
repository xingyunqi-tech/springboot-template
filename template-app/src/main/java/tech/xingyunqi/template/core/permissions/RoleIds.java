package tech.xingyunqi.template.core.permissions;

import java.util.Set;

/** 角色 id（与 nestjs RoleEnum 一致）。 */
public final class RoleIds {

    public static final int ADMIN = 1;
    public static final int USER = 2;
    public static final int EDITOR = 3;
    public static final int REVIEWER = 4;
    public static final Set<Integer> ALL = Set.of(ADMIN, USER, EDITOR, REVIEWER);

    private RoleIds() {}
}
