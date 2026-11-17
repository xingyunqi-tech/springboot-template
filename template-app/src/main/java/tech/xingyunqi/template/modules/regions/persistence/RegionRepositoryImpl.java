package tech.xingyunqi.template.modules.regions.persistence;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.modules.regions.RegionRepository;
import tech.xingyunqi.template.modules.regions.domain.Region;

@Repository
class RegionRepositoryImpl implements RegionRepository {

    private final RegionMapper mapper;

    RegionRepositoryImpl(RegionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Region> findByCode(String code) {
        return Optional.ofNullable(mapper.findByCode(code)).map(RegionRepositoryImpl::region);
    }

    @Override
    public List<Region> findByLevel(int level) {
        return regions(mapper.findByLevel(level));
    }

    @Override
    public List<Region> findByParentCode(@Nullable String parentCode) {
        return regions(mapper.findByParentCode(parentCode));
    }

    @Override
    public List<Region> findAll() {
        return regions(mapper.findAll());
    }

    @Override
    public List<Region> searchByName(String keyword, int limit) {
        String escaped = keyword.replace("%", "\\%").replace("_", "\\_");
        return regions(mapper.searchByName("%" + escaped + "%", limit));
    }

    private static List<Region> regions(List<RegionMapper.Row> rows) {
        return rows.stream().map(RegionRepositoryImpl::region).toList();
    }

    private static Region region(RegionMapper.Row row) {
        return new Region(
                row.id(),
                row.code(),
                row.name(),
                row.level(),
                row.parentCode(),
                row.sort(),
                row.createdAt().toInstant(ZoneOffset.UTC),
                row.updatedAt().toInstant(ZoneOffset.UTC));
    }
}
