package tech.xingyunqi.template.modules.regions.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record RegionTree(
        int id,
        String code,
        String name,
        int level,
        @Nullable String parentCode,
        int sort,
        Instant createdAt,
        Instant updatedAt,
        List<RegionTree> children) {

    public static RegionTree of(Region region, List<RegionTree> children) {
        return new RegionTree(
                region.id(),
                region.code(),
                region.name(),
                region.level(),
                region.parentCode(),
                region.sort(),
                region.createdAt(),
                region.updatedAt(),
                children);
    }
}
