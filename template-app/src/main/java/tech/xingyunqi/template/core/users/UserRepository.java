package tech.xingyunqi.template.core.users;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.core.users.domain.SortItem;
import tech.xingyunqi.template.core.users.domain.User;

public interface UserRepository {

    /** 可写列（nestjs 实体字段名）。 */
    List<String> UPDATABLE = List.of(
            "email",
            "password",
            "provider",
            "firstName",
            "lastName",
            "phone",
            "nickname",
            "gender",
            "birthday",
            "wechatOpenId",
            "wechatUnionId",
            "alipayOpenId");

    Optional<User> findById(int id);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    List<User> findMany(@Nullable List<Integer> roleIds, @Nullable List<SortItem> sort, long page, int limit);

    /** 键为 {@link #UPDATABLE} 中的字段，另可含 {@code photoId}、{@code roleId}、{@code statusId}。 */
    int create(Map<String, @Nullable Object> values);

    /** 返回受影响行数；{@code changes} 为空时不写库。 */
    int update(int id, Map<String, @Nullable Object> changes);

    /** 登录验证通过后升级哈希格式，不改 {@code updatedAt}。 */
    void upgradePasswordHash(int id, String current, String next);

    /** {@code current} 未变时写入新密码哈希。 */
    boolean replacePassword(int id, @Nullable String current, String next);

    void remove(int id);
}
