package tech.xingyunqi.template.modules.addresses;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.addresses.domain.Address;
import tech.xingyunqi.template.modules.addresses.domain.AddressFilter;
import tech.xingyunqi.template.modules.addresses.domain.RegionData;

public interface AddressRepository {

    /** 可写列（nestjs 实体字段名）。 */
    List<String> UPDATABLE = List.of(
            "name",
            "phone",
            "provinceCode",
            "cityCode",
            "districtCode",
            "province",
            "city",
            "district",
            "address",
            "isDefault");

    /** 可排序字段。 */
    List<String> SORTABLE = List.of("id", "name", "isDefault", "createdAt", "updatedAt");

    record NewAddress(
            int userId,
            @Nullable String name,
            @Nullable String phone,
            RegionData region,
            @Nullable String address) {}

    record Created(Address address, long previousCount) {}

    record Order(String field, boolean descending) {}

    /** 同一用户串行创建；已有 {@code limit} 个地址时返回 null。须在事务内调用。 */
    @Nullable
    Created createWithinLimit(NewAddress data, int limit);

    List<Address> findMany(int userId, AddressFilter filter, List<Order> orders, long offset, long limit);

    long count(int userId, AddressFilter filter);

    Optional<Address> findByIdAndUser(int id, int userId);

    Optional<Address> findDefault(int userId);

    /** 键为 {@link #UPDATABLE} 中的字段；返回更新后未删除的地址。 */
    Optional<Address> update(int id, Map<String, @Nullable Object> changes);

    /** 取该用户地址的事务级 advisory 锁。须在事务内调用。 */
    void lockUser(int userId);

    /** 先清除该用户其它默认地址再设置目标；目标不存在或已删除时不变。须在事务内调用。 */
    void markDefault(int id, int userId);

    void softDelete(int id);

    void removeByUser(int userId);
}
