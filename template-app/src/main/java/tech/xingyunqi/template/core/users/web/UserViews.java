package tech.xingyunqi.template.core.users.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.core.users.domain.CreatedRelations;
import tech.xingyunqi.template.core.users.domain.NamedRef;
import tech.xingyunqi.template.core.users.domain.Photo;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.infrastructure.files.FileRecord;
import tech.xingyunqi.template.infrastructure.files.FileUrls;
import tech.xingyunqi.template.views.UserView;

/** 与 nestjs ClassSerializerInterceptor 输出一致的用户序列化（键顺序、分组、{@code __entity}）。 */
@Component
public class UserViews {

    /** nestjs {@code @SerializeOptions({groups})}。 */
    public enum Group {
        ME,
        ADMIN,
        PUBLIC
    }

    private static final Set<String> GROUPED =
            Set.of("email", "provider", "phone", "wechatOpenId", "wechatUnionId", "alipayOpenId");

    private final FileUrls urls;

    public UserViews(FileUrls urls) {
        this.urls = urls;
    }

    public UserView of(User user, Group group) {
        return of(user, group, null);
    }

    public UserView of(User user, Group group, @Nullable CreatedRelations created) {
        Photo photo = created == null ? user.photo() : created.photo();
        Map<String, @Nullable Object> body = new LinkedHashMap<>();
        body.put("id", user.id());
        body.put("email", user.email());
        body.put("provider", user.provider());
        body.put("firstName", user.firstName());
        body.put("lastName", user.lastName());
        body.put("phone", user.phone());
        body.put("nickname", user.nickname());
        body.put("gender", user.gender());
        body.put("birthday", user.birthday() == null ? null : user.birthday().toString());
        body.put("photo", photo == null ? null : photo(photo));
        if (created == null) {
            body.put("role", relation(user.role(), "RoleEntity"));
            body.put("status", relation(user.status(), "StatusEntity"));
        } else {
            if (created.roleId() != null) {
                body.put("role", Map.of("id", created.roleId()));
            }
            if (created.statusId() != null) {
                body.put("status", Map.of("id", created.statusId()));
            }
        }
        body.put("createdAt", user.createdAt());
        body.put("updatedAt", user.updatedAt());
        body.put("deletedAt", user.deletedAt());
        body.put("wechatOpenId", user.wechatOpenId());
        body.put("wechatUnionId", user.wechatUnionId());
        body.put("alipayOpenId", user.alipayOpenId());
        if (group == Group.PUBLIC) {
            body.keySet().removeAll(GROUPED);
        }
        return new UserView(body);
    }

    private Map<String, @Nullable Object> photo(Photo photo) {
        Map<String, @Nullable Object> file = new LinkedHashMap<>();
        file.put("id", photo.id());
        file.put(
                "path",
                urls.build(new FileRecord(
                        photo.id(),
                        photo.path(),
                        photo.ownerUserId(),
                        photo.visibility(),
                        photo.mimeType(),
                        photo.size())));
        file.put("visibility", photo.visibility());
        file.put("mimeType", photo.mimeType());
        file.put("size", photo.size());
        return file;
    }

    private static @Nullable Map<String, Object> relation(@Nullable NamedRef ref, String entity) {
        if (ref == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", ref.id());
        value.put("name", ref.name());
        value.put("__entity", entity);
        return value;
    }
}
