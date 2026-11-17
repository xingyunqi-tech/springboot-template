package tech.xingyunqi.template.modules.addresses.persistence;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.modules.addresses.AddressRepository;
import tech.xingyunqi.template.modules.addresses.domain.Address;
import tech.xingyunqi.template.modules.addresses.domain.AddressFilter;
import tech.xingyunqi.template.modules.addresses.domain.RegionData;

@Repository
class AddressRepositoryImpl implements AddressRepository {

    private final AddressMapper mapper;

    AddressRepositoryImpl(AddressMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public @Nullable Created createWithinLimit(NewAddress data, int limit) {
        mapper.lockUser(data.userId());
        long previous = mapper.count(data.userId(), AddressFilter.none());
        if (previous >= limit) {
            return null;
        }
        RegionData region = data.region();
        int id = mapper.insert(
                data.userId(),
                data.name(),
                data.phone(),
                region.provinceCode(),
                region.cityCode(),
                region.districtCode(),
                region.province(),
                region.city(),
                region.district(),
                data.address());
        AddressMapper.Row row = mapper.findById(id);
        if (row == null) {
            throw new IllegalStateException("inserted address " + id + " not found");
        }
        return new Created(toDomain(row), previous);
    }

    @Override
    public List<Address> findMany(int userId, AddressFilter filter, List<Order> orders, long offset, long limit) {
        List<AddressMapper.Order> columns = orders.stream()
                .map(order -> {
                    if (!SORTABLE.contains(order.field())) {
                        throw new IllegalArgumentException(
                                "Property \"" + order.field() + "\" was not found in \"AddressEntity\"");
                    }
                    return new AddressMapper.Order(order.field(), order.descending());
                })
                .toList();
        return mapper.findMany(userId, filter, columns, offset, limit).stream()
                .map(AddressRepositoryImpl::toDomain)
                .toList();
    }

    @Override
    public long count(int userId, AddressFilter filter) {
        return mapper.count(userId, filter);
    }

    @Override
    public Optional<Address> findByIdAndUser(int id, int userId) {
        return Optional.ofNullable(mapper.findByIdAndUser(id, userId)).map(AddressRepositoryImpl::toDomain);
    }

    @Override
    public Optional<Address> findDefault(int userId) {
        return Optional.ofNullable(mapper.findDefault(userId)).map(AddressRepositoryImpl::toDomain);
    }

    @Override
    public Optional<Address> update(int id, Map<String, @Nullable Object> changes) {
        for (String column : changes.keySet()) {
            if (!UPDATABLE.contains(column)) {
                throw new IllegalArgumentException("unknown address column: " + column);
            }
        }
        if (!changes.isEmpty()) {
            mapper.update(id, changes);
        }
        return Optional.ofNullable(mapper.findById(id)).map(AddressRepositoryImpl::toDomain);
    }

    @Override
    public void lockUser(int userId) {
        mapper.lockUser(userId);
    }

    @Override
    public void markDefault(int id, int userId) {
        mapper.lockUser(userId);
        if (!mapper.existsActive(id, userId)) {
            return;
        }
        mapper.clearOtherDefaults(id, userId);
        mapper.setDefault(id, userId);
    }

    @Override
    public void softDelete(int id) {
        mapper.softDelete(id);
    }

    @Override
    public void removeByUser(int userId) {
        mapper.deleteByUser(userId);
    }

    static Address toDomain(AddressMapper.Row row) {
        return new Address(
                row.id(),
                row.userId(),
                row.name(),
                row.phone(),
                row.provinceCode(),
                row.cityCode(),
                row.districtCode(),
                row.province(),
                row.city(),
                row.district(),
                row.address(),
                row.isDefault(),
                utc(row.createdAt()),
                utc(row.updatedAt()),
                row.deletedAt() == null ? null : utc(row.deletedAt()));
    }

    private static java.time.Instant utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
