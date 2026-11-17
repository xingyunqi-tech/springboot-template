package tech.xingyunqi.template.modules.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.modules.regions.domain.Region;
import tech.xingyunqi.template.modules.regions.domain.RegionTree;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 行政区划查询（nestjs RegionsService）：列表、树与按编码查询缓存 7 天，不存在的编码缓存 60 秒。 */
@Service
public class RegionsService {

    static final String CACHE_KEY_PREFIX = "region:";
    static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    static final long MISS_CACHE_TTL_MS = 60 * 1000;
    static final int SEARCH_LIMIT = 20;

    private static final Logger LOG = LoggerFactory.getLogger(RegionsService.class);

    private final RegionRepository repository;
    private final Cache cache;
    private final JsonMapper json;

    public RegionsService(RegionRepository repository, Cache cache, JsonMapper json) {
        this.repository = repository;
        this.cache = cache;
        this.json = json;
    }

    /** 查询条件：{@code keyword} 优先，其次 {@code parentCode}，再次 {@code level}，都没有时返回全部。 */
    public record Query(
            @Nullable Integer level,
            @Nullable String parentCode,
            @Nullable String keyword) {}

    public List<Region> getProvinces() {
        return cachedList("provinces", () -> {
            LOG.info("Fetching all provinces from database");
            return repository.findByLevel(1);
        });
    }

    public List<Region> getCities(String provinceCode) {
        return cachedList("cities:" + provinceCode, () -> {
            LOG.info("Fetching cities for province: {} from database", provinceCode);
            return repository.findByParentCode(provinceCode);
        });
    }

    public List<Region> getDistricts(String cityCode) {
        return cachedList("districts:" + cityCode, () -> {
            LOG.info("Fetching districts for city: {} from database", cityCode);
            return repository.findByParentCode(cityCode);
        });
    }

    public List<Region> query(Query query) {
        String keyword = query.keyword();
        if (keyword != null && !keyword.isEmpty()) {
            LOG.info("Searching regions by keyword: {}", keyword);
            return repository.searchByName(keyword, SEARCH_LIMIT);
        }
        String parentCode = query.parentCode();
        if (parentCode != null) {
            LOG.info("Fetching regions by parent code: {}", parentCode);
            return repository.findByParentCode(parentCode);
        }
        Integer level = query.level();
        if (level != null && level != 0) {
            LOG.info("Fetching regions by level: {}", level);
            return repository.findByLevel(level);
        }
        LOG.info("Fetching all regions");
        return repository.findAll();
    }

    public List<RegionTree> getTree() {
        String key = CACHE_KEY_PREFIX + "tree";
        JsonNode cached = cache.get(key);
        if (cached != null && cached.isArray() && !cached.isEmpty()) {
            return Arrays.asList(json.treeToValue(cached, RegionTree[].class));
        }
        LOG.info("Building region tree from database");
        List<Region> regions = repository.findAll();
        Map<String, List<RegionTree>> children = new HashMap<>();
        List<RegionTree> nodes = new ArrayList<>();
        for (Region region : regions) {
            List<RegionTree> own = new ArrayList<>();
            children.put(region.code(), own);
            nodes.add(RegionTree.of(region, own));
        }
        List<RegionTree> tree = new ArrayList<>();
        for (RegionTree node : nodes) {
            String parentCode = node.parentCode();
            List<RegionTree> siblings = parentCode == null ? tree : children.get(parentCode);
            if (siblings != null) {
                siblings.add(node);
            }
        }
        if (!tree.isEmpty()) {
            cache.set(key, tree, CACHE_TTL_MS);
        }
        return tree;
    }

    public Optional<Region> getByCode(String code) {
        String key = CACHE_KEY_PREFIX + "code:" + code;
        String missKey = CACHE_KEY_PREFIX + "code-miss:" + code;
        JsonNode cached = cache.get(key);
        if (cached != null && cached.isObject()) {
            return Optional.of(json.treeToValue(cached, Region.class));
        }
        JsonNode miss = cache.get(missKey);
        if (miss != null && miss.asBoolean()) {
            return Optional.empty();
        }
        LOG.info("Fetching region by code: {} from database", code);
        Optional<Region> region = repository.findByCode(code);
        if (region.isPresent()) {
            cache.set(key, region.get(), CACHE_TTL_MS);
        } else {
            cache.set(missKey, true, MISS_CACHE_TTL_MS);
        }
        return region;
    }

    /** 省市区编码逐级匹配（level 1 / 2 / 3 且 parentCode 对应）时返回三者，否则为空。 */
    public Optional<List<Region>> validateRegionCombination(String provinceCode, String cityCode, String districtCode) {
        Optional<Region> province = getByCode(provinceCode);
        Optional<Region> city = getByCode(cityCode);
        Optional<Region> district = getByCode(districtCode);
        if (province.isEmpty() || city.isEmpty() || district.isEmpty()) {
            return Optional.empty();
        }
        if (province.get().level() != 1
                || city.get().level() != 2
                || district.get().level() != 3) {
            return Optional.empty();
        }
        if (!provinceCode.equals(city.get().parentCode())
                || !cityCode.equals(district.get().parentCode())) {
            return Optional.empty();
        }
        return Optional.of(List.of(province.get(), city.get(), district.get()));
    }

    private List<Region> cachedList(String suffix, Supplier<List<Region>> load) {
        String key = CACHE_KEY_PREFIX + suffix;
        JsonNode cached = cache.get(key);
        if (cached != null && cached.isArray() && !cached.isEmpty()) {
            return Arrays.asList(json.treeToValue(cached, Region[].class));
        }
        List<Region> regions = load.get();
        if (!regions.isEmpty()) {
            cache.set(key, regions, CACHE_TTL_MS);
        }
        return regions;
    }
}
