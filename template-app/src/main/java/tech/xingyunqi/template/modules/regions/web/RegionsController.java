package tech.xingyunqi.template.modules.regions.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.bind.ValidatedQuery;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.common.web.validation.ClassValidatorEngine;
import tech.xingyunqi.template.common.web.validation.ParamType;
import tech.xingyunqi.template.contract.api.RegionsApi;
import tech.xingyunqi.template.contract.model.Region;
import tech.xingyunqi.template.contract.model.RegionTreeDto;
import tech.xingyunqi.template.modules.regions.RegionsService;
import tech.xingyunqi.template.modules.regions.domain.RegionTree;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@RestController
@PublicEndpoint
class RegionsController implements RegionsApi {

    private final RegionsService regions;
    private final ClassValidatorEngine engine;
    private final ValidatedQuery query;

    RegionsController(RegionsService regions, ClassValidatorEngine engine, ValidatedQuery query) {
        this.regions = regions;
        this.engine = engine;
        this.query = query;
    }

    @Override
    public List<Region> getProvinces(@Nullable Object acceptLanguage) {
        return views(regions.getProvinces());
    }

    @Override
    public List<Region> getCities(String provinceCode, @Nullable Object acceptLanguage) {
        return views(regions.getCities(param("ProvinceCodeParam", "provinceCode", provinceCode)));
    }

    @Override
    public List<Region> getDistricts(String cityCode, @Nullable Object acceptLanguage) {
        return views(regions.getDistricts(param("CityCodeParam", "cityCode", cityCode)));
    }

    @Override
    public Region getRegionByCode(String code, @Nullable Object acceptLanguage) {
        String validated = param("RegionCodeParam", "code", code);
        return regions.getByCode(validated)
                .map(RegionsController::view)
                .orElseThrow(() -> HttpStatusException.notFound(
                        Map.of("error", "regionNotFound", "message", "Region with code " + validated + " not found")));
    }

    @Override
    public List<RegionTreeDto> getRegionTree(@Nullable Object acceptLanguage) {
        return regions.getTree().stream().map(RegionsController::tree).toList();
    }

    @Override
    public List<Region> queryRegions(
            @Nullable String level,
            @Nullable String parentCode,
            @Nullable String keyword,
            @Nullable Object acceptLanguage) {
        JsonNode params = query.validate("QueryRegionDto", CurrentRequest.get());
        return views(regions.query(new RegionsService.Query(
                params.hasNonNull("level") ? params.get("level").asInt() : null,
                params.hasNonNull("parentCode") ? params.get("parentCode").asString() : null,
                params.hasNonNull("keyword") ? params.get("keyword").asString() : null)));
    }

    private String param(String dto, String name, String value) {
        JsonNode params = engine.run(dto, JsonNodeFactory.instance.objectNode().put(name, value), ParamType.QUERY);
        return params.path(name).asString();
    }

    private static List<Region> views(List<tech.xingyunqi.template.modules.regions.domain.Region> found) {
        return found.stream().map(RegionsController::view).toList();
    }

    static Region view(tech.xingyunqi.template.modules.regions.domain.Region region) {
        return new Region(
                BigDecimal.valueOf(region.id()),
                region.code(),
                region.name(),
                BigDecimal.valueOf(region.level()),
                region.parentCode(),
                BigDecimal.valueOf(region.sort()),
                region.createdAt(),
                region.updatedAt());
    }

    static RegionTreeDto tree(RegionTree node) {
        return new RegionTreeDto(
                BigDecimal.valueOf(node.id()),
                node.code(),
                node.name(),
                BigDecimal.valueOf(node.level()),
                node.parentCode(),
                BigDecimal.valueOf(node.sort()),
                node.createdAt(),
                node.updatedAt(),
                node.children().stream().map(RegionsController::tree).toList());
    }
}
