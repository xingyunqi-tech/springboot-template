package tech.xingyunqi.template.modules.addresses.domain;

import org.jspecify.annotations.Nullable;

/** 地址的区划代码与名称：三个代码同时为 null，或同时为合法组合。 */
public record RegionData(
        @Nullable String provinceCode,
        @Nullable String cityCode,
        @Nullable String districtCode,
        String province,
        String city,
        String district) {}
