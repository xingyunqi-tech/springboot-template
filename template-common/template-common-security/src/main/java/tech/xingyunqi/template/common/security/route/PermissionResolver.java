package tech.xingyunqi.template.common.security.route;

import java.util.Collection;

/** 角色拥有的权限。 */
public interface PermissionResolver {

    Collection<String> permissionsForRole(int roleId);
}
