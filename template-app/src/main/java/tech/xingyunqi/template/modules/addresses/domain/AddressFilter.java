package tech.xingyunqi.template.modules.addresses.domain;

import org.jspecify.annotations.Nullable;

/** 列表筛选：为 null 的条件不参与查询。 */
public record AddressFilter(
        @Nullable String province,
        @Nullable String city,
        @Nullable String provinceCode,
        @Nullable String cityCode,
        @Nullable String districtCode) {

    public static AddressFilter none() {
        return new AddressFilter(null, null, null, null, null);
    }
}
