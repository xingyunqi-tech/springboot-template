package tech.xingyunqi.template.core.users;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.json.JsNumbers;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.common.mybatis.WriteTransactions;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.core.permissions.RoleIds;
import tech.xingyunqi.template.core.permissions.StatusIds;
import tech.xingyunqi.template.core.session.SessionService;
import tech.xingyunqi.template.core.users.domain.CreatedRelations;
import tech.xingyunqi.template.core.users.domain.Patch;
import tech.xingyunqi.template.core.users.domain.Photo;
import tech.xingyunqi.template.core.users.domain.SortItem;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.core.users.domain.UserInput;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupProducer;
import tech.xingyunqi.template.infrastructure.files.FileRecord;
import tech.xingyunqi.template.infrastructure.files.FileReferences;

/** 用户：创建、更新（含管理员）、软删除，以及邮箱 / 手机号唯一性、角色 / 状态 / 头像校验。 */
@Service
public class UsersService {

    /** 新建的用户与响应中应输出的关联。 */
    public record Created(User user, CreatedRelations relations) {}

    private final UserRepository repository;
    private final FileReferences files;
    private final SessionService sessions;
    private final PasswordHasher passwords;
    private final WriteTransactions transactions;
    private final AccountCleanupProducer cleanups;

    public UsersService(
            UserRepository repository,
            FileReferences files,
            SessionService sessions,
            PasswordHasher passwords,
            WriteTransactions transactions,
            AccountCleanupProducer cleanups) {
        this.repository = repository;
        this.files = files;
        this.sessions = sessions;
        this.passwords = passwords;
        this.transactions = transactions;
        this.cleanups = cleanups;
    }

    public Optional<User> findById(int id) {
        return repository.findById(id);
    }

    public Optional<User> findByEmail(@Nullable String email) {
        return email == null || email.isEmpty() ? Optional.empty() : repository.findByEmail(email);
    }

    public Optional<User> findByPhone(@Nullable String phone) {
        return phone == null || phone.isEmpty() ? Optional.empty() : repository.findByPhone(phone);
    }

    public List<User> findMany(@Nullable List<Integer> roleIds, @Nullable List<SortItem> sort, long page, int limit) {
        return repository.findMany(roleIds, sort, page, limit);
    }

    /** 校验 MD5 密码；通过且哈希需要升级时写回新哈希。 */
    public boolean verifyPassword(User user, String md5Password) {
        String current = user.password();
        if (current == null || current.isEmpty()) {
            return false;
        }
        PasswordHasher.VerifyResult result = passwords.verifyMd5(md5Password, current);
        if (result.valid() && result.updatedHash() != null) {
            repository.upgradePasswordHash(user.id(), current, result.updatedHash());
        }
        return result.valid();
    }

    public String hashPassword(String md5Password) {
        return passwords.hash(md5Password);
    }

    /** 密码未被他人修改时写入新密码并删除该用户全部会话；哈希在事务外完成。 */
    public boolean resetPassword(User user, String md5Password) {
        String hash = passwords.hash(md5Password);
        return transactions.run(() -> {
            boolean replaced = repository.replacePassword(user.id(), user.password(), hash);
            if (replaced) {
                sessions.deleteByUser(user.id(), null);
            }
            return replaced;
        });
    }

    /** 更新时应写入的新密码哈希；未提供或与库中值相同时为 null。在事务外调用。 */
    public @Nullable String passwordHashFor(int userId, UserInput data) {
        if (!truthy(data.password())) {
            return null;
        }
        Optional<User> current = repository.findById(userId);
        if (current.isEmpty() || data.password().required().equals(current.get().password())) {
            return null;
        }
        return passwords.hash(data.password().required());
    }

    public Created create(UserInput data) {
        String password =
                truthy(data.password()) ? passwords.hash(data.password().required()) : null;
        return transactions.run(() -> insert(data, password));
    }

    private Created insert(UserInput data, @Nullable String password) {
        String email = null;
        if (truthy(data.email())) {
            ensureEmailFree(data.email().required(), null);
            email = data.email().required();
        }
        String phone = null;
        if (truthy(data.phone())) {
            ensurePhoneFree(data.phone().required(), null);
            phone = data.phone().required();
        }
        Patch<Photo> photo = photo(data.photo(), null);
        Integer roleId = role(data.role());
        Integer statusId = status(data.status());
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("firstName", data.firstName().value());
        values.put("lastName", data.lastName().value());
        values.put("email", email);
        values.put("phone", phone);
        values.put("nickname", data.nickname().value());
        values.put("gender", data.gender().given() ? integer(data.gender().required()) : 0);
        values.put("birthday", birthday(data.birthday().value()));
        values.put("password", password);
        String provider = data.provider().value();
        values.put("provider", provider == null || provider.isEmpty() ? "email" : provider);
        values.put("wechatOpenId", data.wechatOpenId().value());
        values.put("wechatUnionId", data.wechatUnionId().value());
        values.put("alipayOpenId", data.alipayOpenId().value());
        Photo created = photo.present() ? photo.value() : null;
        values.put("photoId", created == null ? null : created.id());
        values.put("roleId", roleId);
        values.put("statusId", statusId);
        int id = repository.create(values);
        User user = repository.findById(id).orElseThrow();
        return new Created(user, new CreatedRelations(roleId, statusId, created));
    }

    public User update(int userId, UserInput data) {
        String password = passwordHashFor(userId, data);
        return transactions.run(() -> updateHashed(userId, data, password));
    }

    /** 在调用方的事务内更新；{@code passwordHash} 由 {@link #passwordHashFor} 事先算好。 */
    @WriteTransaction
    public User updateHashed(int userId, UserInput data, @Nullable String passwordHash) {
        Map<String, @Nullable Object> changes = new LinkedHashMap<>();
        if (passwordHash != null) {
            changes.put("password", passwordHash);
        }
        if (truthy(data.email())) {
            ensureEmailFree(data.email().required(), userId);
            changes.put("email", data.email().required());
        } else if (data.email().present() && data.email().value() == null) {
            changes.put("email", null);
        }
        if (truthy(data.phone())) {
            ensurePhoneFree(data.phone().required(), userId);
            changes.put("phone", data.phone().required());
        } else if (data.phone().present() && data.phone().value() == null) {
            changes.put("phone", null);
        }
        Patch<Photo> photo = photo(data.photo(), userId);
        Integer roleId = role(data.role());
        Integer statusId = status(data.status());
        putIfPresent(changes, "firstName", data.firstName());
        putIfPresent(changes, "lastName", data.lastName());
        putIfPresent(changes, "nickname", data.nickname());
        if (data.gender().present()) {
            Object gender = data.gender().value();
            changes.put("gender", gender == null ? null : integer(gender));
        }
        if (data.birthday().present()) {
            changes.put("birthday", birthday(data.birthday().value()));
        }
        putIfPresent(changes, "provider", data.provider());
        if (photo.present()) {
            Photo value = photo.value();
            changes.put("photoId", value == null ? null : value.id());
        }
        if (roleId != null) {
            changes.put("roleId", roleId);
        }
        if (statusId != null) {
            changes.put("statusId", statusId);
        }
        if (repository.update(userId, changes) == 0) {
            throw HttpStatusException.notFound("User not found");
        }
        return repository.findById(userId).orElseThrow(() -> HttpStatusException.notFound("User not found"));
    }

    public User updateByAdmin(int userId, UserInput data) {
        String password = passwordHashFor(userId, data);
        return transactions.run(() -> {
            User user = updateHashed(userId, data, password);
            if (truthy(data.password())) {
                sessions.deleteByUser(userId, null);
            }
            return user;
        });
    }

    /** 删除会话、软删除，提交后投递账号清理任务。 */
    @WriteTransaction
    public void remove(int userId) {
        sessions.deleteByUser(userId, null);
        repository.remove(userId);
        cleanups.enqueueAfterCommit(userId);
    }

    public boolean isDeleted(int userId) {
        return repository.isDeleted(userId);
    }

    public void detachPhoto(int userId) {
        repository.detachPhoto(userId);
    }

    private void ensureEmailFree(String email, @Nullable Integer current) {
        Optional<User> existing = repository.findByEmail(email);
        if (existing.isPresent() && !(current != null && existing.get().id() == current)) {
            throw HttpStatusException.validationError("email", "emailAlreadyExists");
        }
    }

    private void ensurePhoneFree(String phone, @Nullable Integer current) {
        Optional<User> existing = repository.findByPhone(phone);
        if (existing.isPresent() && !(current != null && existing.get().id() == current)) {
            throw HttpStatusException.validationError("phone", "phoneAlreadyExists");
        }
    }

    /** 显式 null 表示清空；id 为假值时视为未提供。 */
    private Patch<Photo> photo(Patch<UserInput.Ref> value, @Nullable Integer owner) {
        if (value.present() && value.value() == null) {
            return Patch.of(null);
        }
        Object id = value.present() ? value.required().id() : null;
        if (id == null || !jsTruthy(id)) {
            return Patch.absent();
        }
        FileRecord file;
        try {
            file = files.assertReferenceable(List.of(jsString(id)), owner).getFirst();
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                throw HttpStatusException.validationError("photo", "imageNotExists");
            }
            throw e;
        }
        return Patch.of(
                new Photo(file.id(), file.path(), file.ownerUserId(), file.visibility(), file.mimeType(), file.size()));
    }

    private static @Nullable Integer role(Patch<UserInput.Ref> value) {
        return enumId(value, RoleIds.ALL.stream().map(String::valueOf).toList(), "role", "roleNotExists");
    }

    private static @Nullable Integer status(Patch<UserInput.Ref> value) {
        return enumId(value, StatusIds.ALL.stream().map(String::valueOf).toList(), "status", "statusNotExists");
    }

    private static @Nullable Integer enumId(
            Patch<UserInput.Ref> value, List<String> allowed, String field, String error) {
        Object id = value.given() ? value.required().id() : null;
        if (id == null || !jsTruthy(id)) {
            return null;
        }
        String text = jsString(id);
        if (!allowed.contains(text)) {
            throw HttpStatusException.validationError(field, error);
        }
        return Integer.valueOf(text);
    }

    private static void putIfPresent(Map<String, @Nullable Object> changes, String column, Patch<String> value) {
        if (value.present()) {
            changes.put(column, value.value());
        }
    }

    public static boolean truthy(Patch<String> value) {
        return value.given() && !value.required().isEmpty();
    }

    private static boolean jsTruthy(@Nullable Object value) {
        return switch (value) {
            case null -> false;
            case String text -> !text.isEmpty();
            case Boolean bool -> bool;
            case Number number -> number.doubleValue() != 0 && !Double.isNaN(number.doubleValue());
            default -> true;
        };
    }

    private static String jsString(Object value) {
        if (value instanceof Number number) {
            return JsNumbers.toString(number.doubleValue());
        }
        return String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private static @Nullable LocalDate birthday(@Nullable String value) {
        if (value == null) {
            return null;
        }
        return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
    }
}
