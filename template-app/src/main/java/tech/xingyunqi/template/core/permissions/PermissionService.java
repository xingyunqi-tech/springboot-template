package tech.xingyunqi.template.core.permissions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.common.security.route.PermissionResolver;
import tech.xingyunqi.template.core.permissions.persistence.RolePermissionMapper;
import tools.jackson.databind.JsonNode;

/** 角色权限：admin 拥有全部；其余取 {@code role_permission}，表为空时回落默认映射；缓存 5 分钟。 */
@Service
public class PermissionService implements PermissionResolver {

    static final String CACHE_KEY = "permissions:v2:role-map";
    static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private final RolePermissionMapper mapper;
    private final Cache cache;

    public PermissionService(RolePermissionMapper mapper, Cache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    @Override
    public Collection<String> permissionsForRole(int roleId) {
        if (roleId == RoleIds.ADMIN) {
            return all();
        }
        JsonNode granted = roleMap().get(String.valueOf(roleId));
        List<String> result = new ArrayList<>();
        if (granted != null) {
            granted.forEach(item -> result.add(item.asString()));
        }
        return result;
    }

    public void refreshCache() {
        cache.delete(CACHE_KEY);
    }

    private JsonNode roleMap() {
        JsonNode cached = cache.get(CACHE_KEY);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        Map<String, List<String>> stored = new LinkedHashMap<>();
        for (RolePermissionMapper.Row row : mapper.findAll()) {
            stored.computeIfAbsent(String.valueOf(row.roleId()), key -> new ArrayList<>())
                    .add(row.permissionId());
        }
        Map<String, List<String>> roleMap = stored.isEmpty() ? defaults() : stored;
        cache.set(CACHE_KEY, roleMap, CACHE_TTL_MS);
        JsonNode reloaded = cache.get(CACHE_KEY);
        if (reloaded == null) {
            throw new IllegalStateException("permission cache write was not readable");
        }
        return reloaded;
    }

    static List<String> all() {
        return Arrays.stream(Permission.values()).map(Permission::value).toList();
    }

    static Map<String, List<String>> defaults() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(String.valueOf(RoleIds.ADMIN), all());
        map.put(
                String.valueOf(RoleIds.USER),
                values(
                        Permission.ADDRESS_READ,
                        Permission.ADDRESS_CREATE,
                        Permission.ADDRESS_UPDATE,
                        Permission.ADDRESS_DELETE,
                        Permission.FAVORITE_READ,
                        Permission.FAVORITE_CREATE,
                        Permission.FAVORITE_DELETE,
                        Permission.FEEDBACK_READ,
                        Permission.FEEDBACK_CREATE,
                        Permission.FILE_UPLOAD,
                        Permission.FILE_READ,
                        Permission.SESSION_READ,
                        Permission.PRACTICE_WRITE,
                        Permission.AI_CHAT,
                        Permission.AI_GRADING));
        map.put(
                String.valueOf(RoleIds.EDITOR),
                values(
                        Permission.FILE_UPLOAD,
                        Permission.FILE_READ,
                        Permission.CONTENT_MANAGE,
                        Permission.AI_GENERATE));
        map.put(
                String.valueOf(RoleIds.REVIEWER),
                values(
                        Permission.FILE_UPLOAD,
                        Permission.FILE_READ,
                        Permission.CONTENT_MANAGE,
                        Permission.CONTENT_REVIEW));
        return map;
    }

    private static List<String> values(Permission... permissions) {
        return Arrays.stream(permissions).map(Permission::value).toList();
    }
}
