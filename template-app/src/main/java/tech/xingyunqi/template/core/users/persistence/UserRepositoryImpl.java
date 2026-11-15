package tech.xingyunqi.template.core.users.persistence;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.core.users.UserRepository;
import tech.xingyunqi.template.core.users.domain.NamedRef;
import tech.xingyunqi.template.core.users.domain.Photo;
import tech.xingyunqi.template.core.users.domain.SortItem;
import tech.xingyunqi.template.core.users.domain.User;

@Repository
class UserRepositoryImpl implements UserRepository {

    private static final Set<String> SORTABLE = sortable();
    private static final Set<String> WRITABLE = writable();

    private final UserMapper mapper;

    UserRepositoryImpl(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findById(int id) {
        return Optional.ofNullable(mapper.findById(id)).map(UserRepositoryImpl::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(mapper.findByEmail(email)).map(UserRepositoryImpl::toDomain);
    }

    @Override
    public Optional<User> findByPhone(String phone) {
        return Optional.ofNullable(mapper.findByPhone(phone)).map(UserRepositoryImpl::toDomain);
    }

    @Override
    public List<User> findMany(@Nullable List<Integer> roleIds, @Nullable List<SortItem> sort, long page, int limit) {
        List<UserMapper.Order> orders = new ArrayList<>();
        boolean orderedById = false;
        for (SortItem item : sort == null ? List.<SortItem>of() : sort) {
            if (!SORTABLE.contains(item.orderBy())) {
                throw new IllegalArgumentException(
                        "Property \"" + item.orderBy() + "\" was not found in \"UserEntity\"");
            }
            orderedById |= "id".equals(item.orderBy());
            orders.add(new UserMapper.Order(item.orderBy(), "DESC".equals(item.order())));
        }
        if (!orderedById) {
            orders.add(new UserMapper.Order("id", false));
        }
        return mapper.findMany(roleIds, orders, (page - 1) * limit, limit).stream()
                .map(UserRepositoryImpl::toDomain)
                .toList();
    }

    @Override
    public int create(Map<String, @Nullable Object> values) {
        checkColumns(values);
        return mapper.insertReturningId(values);
    }

    @Override
    public int update(int id, Map<String, @Nullable Object> changes) {
        checkColumns(changes);
        return changes.isEmpty() ? 1 : mapper.update(id, changes);
    }

    @Override
    public void upgradePasswordHash(int id, String current, String next) {
        mapper.upgradePasswordHash(id, current, next);
    }

    @Override
    public boolean replacePassword(int id, @Nullable String current, String next) {
        return mapper.replacePassword(id, current, next) > 0;
    }

    @Override
    public void remove(int id) {
        mapper.clearUniqueFields(id);
        mapper.softDelete(id);
    }

    @Override
    public boolean isDeleted(int id) {
        return mapper.existsDeleted(id);
    }

    @Override
    public void detachPhoto(int id) {
        mapper.detachPhoto(id);
    }

    private static void checkColumns(Map<String, @Nullable Object> values) {
        for (String column : values.keySet()) {
            if (!WRITABLE.contains(column)) {
                throw new IllegalArgumentException("unknown user column: " + column);
            }
        }
    }

    private static Set<String> sortable() {
        List<String> columns = new ArrayList<>(UPDATABLE);
        columns.addAll(List.of("id", "createdAt", "updatedAt", "deletedAt"));
        return Set.copyOf(columns);
    }

    private static Set<String> writable() {
        List<String> columns = new ArrayList<>(UPDATABLE);
        columns.addAll(List.of("photoId", "roleId", "statusId"));
        return Set.copyOf(columns);
    }

    static User toDomain(UserMapper.Row row) {
        Photo photo = row.photoId() == null || row.photoPath() == null
                ? null
                : new Photo(
                        row.photoId(),
                        row.photoPath(),
                        row.photoOwnerUserId(),
                        row.photoVisibility() == null ? "PUBLIC" : row.photoVisibility(),
                        row.photoMimeType(),
                        row.photoSize());
        return new User(
                row.id(),
                row.email(),
                row.password(),
                row.provider(),
                row.firstName(),
                row.lastName(),
                row.phone(),
                row.nickname(),
                row.gender(),
                row.birthday(),
                photo,
                row.roleId(),
                row.roleId() == null || row.roleName() == null ? null : new NamedRef(row.roleId(), row.roleName()),
                row.statusId(),
                row.statusId() == null || row.statusName() == null
                        ? null
                        : new NamedRef(row.statusId(), row.statusName()),
                utc(row.createdAt()),
                utc(row.updatedAt()),
                row.deletedAt() == null ? null : utc(row.deletedAt()),
                row.wechatOpenId(),
                row.wechatUnionId(),
                row.alipayOpenId());
    }

    private static java.time.Instant utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
