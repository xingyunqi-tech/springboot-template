package tech.xingyunqi.template.modules.regions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.json.JsCompatibleModule;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tech.xingyunqi.template.modules.regions.domain.Region;
import tech.xingyunqi.template.modules.regions.domain.RegionTree;
import tools.jackson.databind.json.JsonMapper;

class RegionsServiceTest {

    static final JsonMapper JSON =
            JsonMapper.builder().addModule(new JsCompatibleModule()).build();
    static final Instant NOW = Instant.parse("2026-11-16T08:00:00.123Z");

    static Region region(int id, String code, int level, @Nullable String parentCode) {
        return new Region(id, code, "R" + id, level, parentCode, id, NOW, NOW);
    }

    /** 内存仓储，记录调用。 */
    static final class FakeRepository implements RegionRepository {
        final List<Region> regions = new ArrayList<>(List.of(
                region(1, "110000000000", 1, null),
                region(2, "110100000000", 2, "110000000000"),
                region(3, "110101000000", 3, "110100000000"),
                region(4, "120000000000", 1, null),
                region(5, "990100000000", 2, "990000000000")));
        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<Region> findByCode(String code) {
            calls.add("code:" + code);
            return regions.stream().filter(r -> r.code().equals(code)).findFirst();
        }

        @Override
        public List<Region> findByLevel(int level) {
            calls.add("level:" + level);
            return regions.stream().filter(r -> r.level() == level).toList();
        }

        @Override
        public List<Region> findByParentCode(@Nullable String parentCode) {
            calls.add("parent:" + parentCode);
            return regions.stream()
                    .filter(r -> parentCode == null ? r.parentCode() == null : parentCode.equals(r.parentCode()))
                    .toList();
        }

        @Override
        public List<Region> findAll() {
            calls.add("all");
            return regions;
        }

        @Override
        public List<Region> searchByName(String keyword, int limit) {
            calls.add("search:" + keyword + ":" + limit);
            return List.of();
        }
    }

    final FakeRepository repository = new FakeRepository();
    final RegionsService service = new RegionsService(repository, new MemoryCache(JSON, Clock.systemUTC()), JSON);

    @Test
    @DisplayName("省、市、区列表缓存非空结果，空结果每次查库")
    void listsAreCached() {
        assertThat(service.getProvinces()).extracting(Region::code).containsExactly("110000000000", "120000000000");
        assertThat(service.getProvinces()).isEqualTo(repository.findByLevel(1));
        assertThat(service.getCities("110000000000")).hasSize(1);
        assertThat(service.getCities("110000000000")).hasSize(1);
        assertThat(service.getDistricts("110100000000")).hasSize(1);
        assertThat(service.getDistricts("999999999999")).isEmpty();
        assertThat(service.getDistricts("999999999999")).isEmpty();

        assertThat(repository.calls)
                .containsExactly(
                        "level:1",
                        "level:1",
                        "parent:110000000000",
                        "parent:110100000000",
                        "parent:999999999999",
                        "parent:999999999999");
    }

    @Test
    @DisplayName("查询优先级：keyword > parentCode > level > 全部；空 keyword 与 level 0 视为未提供")
    void queryPriority() {
        service.query(new RegionsService.Query(1, "110000000000", "北"));
        service.query(new RegionsService.Query(1, "", ""));
        service.query(new RegionsService.Query(2, null, null));
        service.query(new RegionsService.Query(0, null, null));
        service.query(new RegionsService.Query(null, null, null));

        assertThat(repository.calls).containsExactly("search:北:20", "parent:", "level:2", "all", "all");
    }

    @Test
    @DisplayName("树按 parentCode 组装，父级不存在的节点丢弃，结果缓存")
    void tree() {
        List<RegionTree> tree = service.getTree();

        assertThat(tree).extracting(RegionTree::code).containsExactly("110000000000", "120000000000");
        assertThat(tree.getFirst().children().getFirst().children().getFirst().code())
                .isEqualTo("110101000000");
        assertThat(tree.get(1).children()).isEmpty();
        assertThat(service.getTree()).isEqualTo(tree);
        assertThat(repository.calls).containsExactly("all");
    }

    @Test
    @DisplayName("按编码查询：命中缓存 7 天，未命中缓存 miss 标记")
    void byCode() {
        assertThat(service.getByCode("110101000000")).hasValue(repository.regions.get(2));
        assertThat(service.getByCode("110101000000")).hasValue(repository.regions.get(2));
        assertThat(service.getByCode("999999999998")).isEmpty();
        assertThat(service.getByCode("999999999998")).isEmpty();

        assertThat(repository.calls).containsExactly("code:110101000000", "code:999999999998");
    }

    @Test
    @DisplayName("省市区组合须逐级匹配")
    void combination() {
        assertThat(service.validateRegionCombination("110000000000", "110100000000", "110101000000"))
                .hasValueSatisfying(regions -> assertThat(regions).hasSize(3));
        assertThat(service.validateRegionCombination("120000000000", "110100000000", "110101000000"))
                .isEmpty();
        assertThat(service.validateRegionCombination("110100000000", "110100000000", "110101000000"))
                .isEmpty();
        assertThat(service.validateRegionCombination("110000000000", "110100000000", "000000000000"))
                .isEmpty();
    }
}
