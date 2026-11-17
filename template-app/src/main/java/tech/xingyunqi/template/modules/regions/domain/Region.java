package tech.xingyunqi.template.modules.regions.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** 行政区划：{@code level} 1 省、2 市、3 区县，编码为 12 位。 */
public record Region(
        int id,
        String code,
        String name,
        int level,
        @Nullable String parentCode,
        int sort,
        Instant createdAt,
        Instant updatedAt) {}
