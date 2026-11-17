package tech.xingyunqi.template.modules.addresses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.modules.addresses.domain.Address;
import tech.xingyunqi.template.modules.addresses.domain.AddressFilter;
import tech.xingyunqi.template.modules.addresses.domain.RegionData;
import tech.xingyunqi.template.modules.regions.RegionsService;
import tech.xingyunqi.template.modules.regions.domain.Region;

/** 收货地址：区划代码校验与名称回填、每用户数量上限、唯一默认地址；写默认地址的事务先取用户级 advisory 锁再写行。 */
@Service
public class AddressesService {

    static final int MAX_ADDRESSES_PER_USER = 20;
    static final List<String> CODE_KEYS = List.of("provinceCode", "cityCode", "districtCode");
    static final List<String> NAME_KEYS = List.of("province", "city", "district");
    static final List<AddressRepository.Order> DEFAULT_ORDER =
            List.of(new AddressRepository.Order("isDefault", true), new AddressRepository.Order("createdAt", true));

    private static final Logger LOG = LoggerFactory.getLogger(AddressesService.class);

    /** 列表页与总数。 */
    public record Page(List<Address> data, long total) {}

    private final AddressRepository repository;
    private final RegionsService regions;

    public AddressesService(AddressRepository repository, RegionsService regions) {
        this.repository = repository;
        this.regions = regions;
    }

    /** {@code body} 只含请求中出现的字段（含显式 null）。 */
    @WriteTransaction
    public Address create(int userId, Map<String, @Nullable Object> body) {
        LOG.info("Creating address for user: {}", userId);
        RegionData region = regionData(body);
        AddressRepository.Created created = repository.createWithinLimit(
                new AddressRepository.NewAddress(
                        userId,
                        string(body.get("name")),
                        string(body.get("phone")),
                        region,
                        string(body.get("address"))),
                MAX_ADDRESSES_PER_USER);
        if (created == null) {
            LOG.warn("User {} has reached max addresses limit", userId);
            throw HttpStatusException.forbidden(errorBody(
                    "maxAddressesReached", "Maximum " + MAX_ADDRESSES_PER_USER + " addresses allowed per user"));
        }
        Address address = created.address();
        Object isDefault = body.get("isDefault");
        if (isDefault != null ? Boolean.TRUE.equals(isDefault) : created.previousCount() == 0) {
            repository.markDefault(address.id(), userId);
            address = address.withDefault();
        }
        LOG.info("Address created successfully: {}", address.id());
        return address;
    }

    public Page findPage(int userId, AddressFilter filter, List<AddressRepository.Order> sort, long page, long limit) {
        List<Address> data = repository.findMany(userId, filter, order(sort), (page - 1) * limit, limit);
        return new Page(data, repository.count(userId, filter));
    }

    public Address findById(int id, int userId) {
        return repository.findByIdAndUser(id, userId).orElseThrow(AddressesService::notFound);
    }

    public Optional<Address> findDefault(int userId) {
        return repository.findDefault(userId);
    }

    /** {@code body} 只含请求中出现的字段（含显式 null）。 */
    @WriteTransaction
    public Address update(int id, int userId, Map<String, @Nullable Object> body) {
        LOG.info("Updating address: {} for user: {}", id, userId);
        Address address = owned(id, userId);
        Map<String, @Nullable Object> changes = new LinkedHashMap<>(body);
        changes.remove("isDefault");
        Object isDefault = body.get("isDefault");
        if (Boolean.FALSE.equals(isDefault)) {
            changes.put("isDefault", false);
        }
        if (CODE_KEYS.stream().anyMatch(body::containsKey)) {
            Map<String, @Nullable Object> merged = new LinkedHashMap<>();
            putMerged(merged, body, "provinceCode", address.provinceCode());
            putMerged(merged, body, "cityCode", address.cityCode());
            putMerged(merged, body, "districtCode", address.districtCode());
            putMerged(merged, body, "province", address.province());
            putMerged(merged, body, "city", address.city());
            putMerged(merged, body, "district", address.district());
            RegionData region = regionData(merged);
            changes.put("provinceCode", region.provinceCode());
            changes.put("cityCode", region.cityCode());
            changes.put("districtCode", region.districtCode());
            changes.put("province", region.province());
            changes.put("city", region.city());
            changes.put("district", region.district());
        }
        if (Boolean.TRUE.equals(isDefault)) {
            repository.lockUser(userId);
        }
        Address updated = repository.update(id, changes).orElseThrow(AddressesService::notFound);
        if (Boolean.TRUE.equals(isDefault)) {
            repository.markDefault(id, userId);
            updated = updated.withDefault();
        }
        LOG.info("Address updated successfully: {}", id);
        return updated;
    }

    @WriteTransaction
    public Address setDefault(int id, int userId) {
        LOG.info("Setting address {} as default for user: {}", id, userId);
        owned(id, userId);
        repository.markDefault(id, userId);
        Address address = repository.findByIdAndUser(id, userId).orElseThrow(AddressesService::notFound);
        LOG.info("Address {} set as default", id);
        return address;
    }

    @WriteTransaction
    public void remove(int id, int userId) {
        LOG.info("Removing address: {} for user: {}", id, userId);
        Address address = owned(id, userId);
        repository.lockUser(userId);
        repository.softDelete(id);
        if (address.isDefault()) {
            List<Address> remaining = repository.findMany(userId, AddressFilter.none(), DEFAULT_ORDER, 0, 1);
            if (!remaining.isEmpty()) {
                repository.markDefault(remaining.getFirst().id(), userId);
            }
        }
        LOG.info("Address removed successfully: {}", id);
    }

    @WriteTransaction
    public void removeByUser(int userId) {
        repository.removeByUser(userId);
    }

    private Address owned(int id, int userId) {
        Optional<Address> address = repository.findByIdAndUser(id, userId);
        if (address.isEmpty()) {
            LOG.warn("Address {} not found or not owned by user {}", id, userId);
            throw notFound();
        }
        return address.get();
    }

    /** 三个区划代码须同时提供或都不提供；提供时按代码回填名称，否则名称必填。键出现即视为提供。 */
    RegionData regionData(Map<String, @Nullable Object> values) {
        String provinceCode = string(values.get("provinceCode"));
        String cityCode = string(values.get("cityCode"));
        String districtCode = string(values.get("districtCode"));
        boolean hasAll = truthy(provinceCode) && truthy(cityCode) && truthy(districtCode);
        if (CODE_KEYS.stream().anyMatch(values::containsKey) && !hasAll) {
            LOG.warn("Incomplete region codes provided");
            throw HttpStatusException.badRequest(errorBody(
                    "incompleteRegionCodes",
                    "Must provide all three region codes (province, city, district) or none at all"));
        }
        if (provinceCode == null || cityCode == null || districtCode == null || !hasAll) {
            String province = string(values.get("province"));
            String city = string(values.get("city"));
            String district = string(values.get("district"));
            if (province == null
                    || city == null
                    || district == null
                    || !truthy(province)
                    || !truthy(city)
                    || !truthy(district)) {
                throw HttpStatusException.badRequest(errorBody(
                        "missingRegionNames",
                        "Province, city, and district names are required when region codes are not provided"));
            }
            return new RegionData(null, null, null, province, city, district);
        }
        List<Region> found = regions.validateRegionCombination(provinceCode, cityCode, districtCode)
                .orElse(null);
        if (found == null) {
            LOG.warn("Invalid region combination: {}, {}, {}", provinceCode, cityCode, districtCode);
            throw HttpStatusException.badRequest(
                    errorBody("invalidRegionCombination", "Invalid province/city/district combination"));
        }
        return new RegionData(
                provinceCode,
                cityCode,
                districtCode,
                found.get(0).name(),
                found.get(1).name(),
                found.get(2).name());
    }

    /** 请求的排序项在前，默认排序（isDefault、createdAt 倒序）补在未出现的字段上；同一字段以最后一次的方向为准。 */
    static List<AddressRepository.Order> order(List<AddressRepository.Order> sort) {
        if (sort.isEmpty()) {
            return DEFAULT_ORDER;
        }
        Map<String, Boolean> order = new LinkedHashMap<>();
        for (AddressRepository.Order item : sort) {
            order.put(item.field(), item.descending());
        }
        for (AddressRepository.Order item : DEFAULT_ORDER) {
            order.putIfAbsent(item.field(), item.descending());
        }
        List<AddressRepository.Order> result = new ArrayList<>();
        order.forEach((field, descending) -> result.add(new AddressRepository.Order(field, descending)));
        return result;
    }

    private static void putMerged(
            Map<String, @Nullable Object> merged,
            Map<String, @Nullable Object> body,
            String key,
            @Nullable String current) {
        Object value = body.get(key) != null ? body.get(key) : current;
        if (value != null) {
            merged.put(key, value);
        }
    }

    private static boolean truthy(@Nullable String value) {
        return value != null && !value.isEmpty();
    }

    private static @Nullable String string(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, String> errorBody(String error, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }

    private static HttpStatusException notFound() {
        return HttpStatusException.notFound(Map.of("error", "addressNotFound"));
    }
}
