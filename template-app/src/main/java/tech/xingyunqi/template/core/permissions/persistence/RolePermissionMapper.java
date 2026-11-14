package tech.xingyunqi.template.core.permissions.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RolePermissionMapper {

    /** 角色与权限的对应行。 */
    record Row(int roleId, String permissionId) {}

    @Select("select role_id as \"roleId\", permission_id as \"permissionId\" from role_permission")
    List<Row> findAll();
}
