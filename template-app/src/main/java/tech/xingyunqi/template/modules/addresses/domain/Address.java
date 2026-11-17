package tech.xingyunqi.template.modules.addresses.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record Address(
        int id,
        int userId,
        String name,
        String phone,
        @Nullable String provinceCode,
        @Nullable String cityCode,
        @Nullable String districtCode,
        String province,
        String city,
        String district,
        String address,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt) {

    public Address withDefault() {
        return new Address(
                id,
                userId,
                name,
                phone,
                provinceCode,
                cityCode,
                districtCode,
                province,
                city,
                district,
                address,
                true,
                createdAt,
                updatedAt,
                deletedAt);
    }
}
