package tech.xingyunqi.template.modules.config.web;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.PermissionsGuarded;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.common.web.route.RequirePermissions;
import tech.xingyunqi.template.contract.api.ConfigApi;
import tech.xingyunqi.template.contract.model.Config;
import tech.xingyunqi.template.contract.model.UpsertConfigDto;
import tech.xingyunqi.template.modules.config.AppConfigService;
import tech.xingyunqi.template.modules.config.ConfigEntry;
import tools.jackson.databind.JsonNode;

@RestController
class ConfigController implements ConfigApi {

    private final AppConfigService configs;

    ConfigController(AppConfigService configs) {
        this.configs = configs;
    }

    @Override
    @PublicEndpoint
    public Config getConfig(String key, @Nullable Object acceptLanguage) {
        return view(configs.findPublicByKey(key));
    }

    @Override
    @AuthenticatedOnly
    @PermissionsGuarded
    @RequirePermissions("system:config")
    public Config getConfigAsAdmin(String key, @Nullable Object acceptLanguage) {
        return view(configs.findByKey(key));
    }

    @Override
    @AuthenticatedOnly
    @PermissionsGuarded
    @RequirePermissions("system:config")
    public Config upsertConfig(String key, UpsertConfigDto upsertConfigDto, @Nullable Object acceptLanguage) {
        JsonNode body = ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get());
        if (body == null) {
            throw HttpStatusException.badRequest(null);
        }
        JsonNode isPublic = body.get("isPublic");
        return view(configs.upsertGeneric(
                key, body.path("value"), isPublic == null || isPublic.isNull() ? null : isPublic.asBoolean()));
    }

    static Config view(ConfigEntry config) {
        return new Config(config.key(), config.value(), config.isPublic(), config.createdAt(), config.updatedAt());
    }
}
