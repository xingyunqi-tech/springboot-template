package tech.xingyunqi.template.modules.regions;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.regions.domain.Region;

public interface RegionRepository {

    Optional<Region> findByCode(String code);

    List<Region> findByLevel(int level);

    /** {@code parentCode} 为 null 时取顶级地区。 */
    List<Region> findByParentCode(@Nullable String parentCode);

    List<Region> findAll();

    /** 名称包含关键词（不区分大小写，{@code %} 与 {@code _} 按字面匹配），按级别、排序取前 {@code limit} 条。 */
    List<Region> searchByName(String keyword, int limit);
}
