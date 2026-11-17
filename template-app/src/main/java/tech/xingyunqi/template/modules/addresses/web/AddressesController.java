package tech.xingyunqi.template.modules.addresses.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.bind.ValidatedQuery;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.contract.api.AddressesApi;
import tech.xingyunqi.template.contract.model.CreateAddressDto;
import tech.xingyunqi.template.contract.model.PaginationAddressResponseDto;
import tech.xingyunqi.template.contract.model.UpdateAddressDto;
import tech.xingyunqi.template.modules.addresses.AddressRepository;
import tech.xingyunqi.template.modules.addresses.AddressesService;
import tech.xingyunqi.template.modules.addresses.domain.Address;
import tech.xingyunqi.template.modules.addresses.domain.AddressFilter;
import tools.jackson.databind.JsonNode;

@RestController
@AuthenticatedOnly
class AddressesController implements AddressesApi {

    static final int MAX_LIMIT = 50;

    private final AddressesService addresses;
    private final ValidatedQuery query;

    AddressesController(AddressesService addresses, ValidatedQuery query) {
        this.addresses = addresses;
        this.query = query;
    }

    @Override
    public tech.xingyunqi.template.contract.model.Address createAddress(
            CreateAddressDto createAddressDto, @Nullable Object acceptLanguage) {
        return view(addresses.create(userId(), fields(body())));
    }

    @Override
    public PaginationAddressResponseDto getAddresses(
            @Nullable String page,
            @Nullable String limit,
            @Nullable String filters,
            @Nullable String sort,
            @Nullable Object acceptLanguage) {
        JsonNode params = query.validate("QueryAddressDto", CurrentRequest.get());
        long pageValue = params.hasNonNull("page") ? params.get("page").asLong() : 1;
        long limitValue =
                Math.min(params.hasNonNull("limit") ? params.get("limit").asLong() : 10, MAX_LIMIT);
        AddressesService.Page result = addresses.findPage(
                userId(), filter(params.path("filters")), sort(params.path("sort")), pageValue, limitValue);
        return new PaginationAddressResponseDto(
                result.data().stream().map(AddressesController::view).toList(),
                BigDecimal.valueOf(result.total()),
                BigDecimal.valueOf(pageValue),
                BigDecimal.valueOf(limitValue),
                BigDecimal.valueOf((result.total() + limitValue - 1) / limitValue));
    }

    @Override
    public tech.xingyunqi.template.contract.model.@Nullable Address getDefaultAddress(@Nullable Object acceptLanguage) {
        return addresses.findDefault(userId()).map(AddressesController::view).orElse(null);
    }

    @Override
    public tech.xingyunqi.template.contract.model.Address getAddress(BigDecimal id, @Nullable Object acceptLanguage) {
        return view(addresses.findById(id.intValueExact(), userId()));
    }

    @Override
    public tech.xingyunqi.template.contract.model.Address updateAddress(
            BigDecimal id, UpdateAddressDto updateAddressDto, @Nullable Object acceptLanguage) {
        return view(addresses.update(id.intValueExact(), userId(), fields(body())));
    }

    @Override
    public void deleteAddress(BigDecimal id, @Nullable Object acceptLanguage) {
        addresses.remove(id.intValueExact(), userId());
    }

    @Override
    public tech.xingyunqi.template.contract.model.Address setDefaultAddress(
            BigDecimal id, @Nullable Object acceptLanguage) {
        return view(addresses.setDefault(id.intValueExact(), userId()));
    }

    private static int userId() {
        return AuthContext.current().id();
    }

    private static JsonNode body() {
        JsonNode body = ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get());
        if (body == null) {
            throw HttpStatusException.badRequest(null);
        }
        return body;
    }

    /** 请求体中出现的字段（含显式 null）。 */
    static Map<String, @Nullable Object> fields(JsonNode body) {
        Map<String, @Nullable Object> fields = new LinkedHashMap<>();
        body.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            Object converted;
            if (value.isNull()) {
                converted = null;
            } else if (value.isBoolean()) {
                converted = value.booleanValue();
            } else if (value.isString()) {
                converted = value.stringValue();
            } else {
                converted = value.toString();
            }
            fields.put(entry.getKey(), converted);
        });
        return fields;
    }

    private static AddressFilter filter(JsonNode filters) {
        return new AddressFilter(
                truthy(filters, "province"),
                truthy(filters, "city"),
                truthy(filters, "provinceCode"),
                truthy(filters, "cityCode"),
                truthy(filters, "districtCode"));
    }

    private static @Nullable String truthy(JsonNode filters, String key) {
        JsonNode value = filters.path(key);
        return value.isString() && !value.stringValue().isEmpty() ? value.stringValue() : null;
    }

    private static List<AddressRepository.Order> sort(JsonNode sort) {
        List<AddressRepository.Order> orders = new ArrayList<>();
        if (sort.isArray()) {
            for (JsonNode item : sort) {
                orders.add(new AddressRepository.Order(
                        item.path("orderBy").asString(""),
                        "DESC".equals(item.path("order").asString(""))));
            }
        }
        return orders;
    }

    static tech.xingyunqi.template.contract.model.Address view(Address address) {
        return new tech.xingyunqi.template.contract.model.Address(
                BigDecimal.valueOf(address.id()),
                BigDecimal.valueOf(address.userId()),
                address.name(),
                address.phone(),
                address.provinceCode(),
                address.cityCode(),
                address.districtCode(),
                address.province(),
                address.city(),
                address.district(),
                address.address(),
                address.isDefault(),
                address.createdAt(),
                address.updatedAt(),
                address.deletedAt());
    }
}
