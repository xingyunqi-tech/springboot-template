package tech.xingyunqi.template.modules.favorites.web;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.bind.ValidatedQuery;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.contract.api.FavoritesApi;
import tech.xingyunqi.template.contract.model.CheckFavoriteDto;
import tech.xingyunqi.template.contract.model.CreateFavoriteDto;
import tech.xingyunqi.template.contract.model.FavoriteItemDto;
import tech.xingyunqi.template.contract.model.PaginationFavoriteItemDtoResponseDto;
import tech.xingyunqi.template.modules.favorites.FavoritesService;
import tech.xingyunqi.template.modules.favorites.domain.Favorite;
import tech.xingyunqi.template.views.CheckFavoriteResponseView;
import tech.xingyunqi.template.views.ToggleFavoriteResponseView;
import tools.jackson.databind.JsonNode;

@RestController
@AuthenticatedOnly
class FavoritesController implements FavoritesApi {

    private final FavoritesService favorites;
    private final ValidatedQuery query;

    FavoritesController(FavoritesService favorites, ValidatedQuery query) {
        this.favorites = favorites;
        this.query = query;
    }

    @Override
    public tech.xingyunqi.template.contract.model.Favorite createFavorite(
            CreateFavoriteDto createFavoriteDto, @Nullable Object acceptLanguage) {
        return view(favorites.create(userId(), target(body())));
    }

    @Override
    public PaginationFavoriteItemDtoResponseDto getFavorites(
            @Nullable String targetType,
            @Nullable String subjectId,
            @Nullable String page,
            @Nullable String limit,
            @Nullable Object acceptLanguage) {
        JsonNode params = query.validate("QueryFavoriteDto", CurrentRequest.get());
        long pageValue = params.hasNonNull("page") ? params.get("page").asLong() : 1;
        long limitValue =
                Math.min(params.hasNonNull("limit") ? params.get("limit").asLong() : 10, 50);
        FavoritesService.Page result = favorites.findPage(
                userId(), truthy(params, "targetType"), truthy(params, "subjectId"), pageValue, limitValue);
        return new PaginationFavoriteItemDtoResponseDto(
                result.items().stream().map(FavoritesController::item).toList(),
                BigDecimal.valueOf(result.total()),
                BigDecimal.valueOf(pageValue),
                BigDecimal.valueOf(limitValue),
                BigDecimal.valueOf((result.total() + limitValue - 1) / limitValue));
    }

    @Override
    public CheckFavoriteResponseView checkFavorite(CheckFavoriteDto checkFavoriteDto, @Nullable Object acceptLanguage) {
        JsonNode body = body();
        Favorite favorite = favorites
                .findByTarget(userId(), text(body, "targetType"), text(body, "targetId"))
                .orElse(null);
        return new CheckFavoriteResponseView(favorite != null, favorite == null ? null : favorite.id());
    }

    @Override
    public ToggleFavoriteResponseView toggleFavorite(
            CreateFavoriteDto createFavoriteDto, @Nullable Object acceptLanguage) {
        FavoritesService.Toggled toggled = favorites.toggle(userId(), target(body()));
        Favorite favorite = toggled.favorite();
        return new ToggleFavoriteResponseView(toggled.favorited(), favorite == null ? null : view(favorite));
    }

    @Override
    public void deleteFavorite(BigDecimal id, @Nullable Object acceptLanguage) {
        favorites.remove(id.intValueExact(), userId());
    }

    private static int userId() {
        return AuthContext.current().id();
    }

    private static JsonNode body() {
        JsonNode body = ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get());
        if (body == null) {
            throw HttpStatusException.badRequest(null);
        }
        return body;
    }

    private static FavoritesService.Target target(JsonNode body) {
        return new FavoritesService.Target(
                text(body, "targetType"),
                text(body, "targetId"),
                optional(body, "title"),
                optional(body, "image"),
                optional(body, "extra"));
    }

    private static String text(JsonNode body, String key) {
        return body.path(key).asString("");
    }

    private static @Nullable String optional(JsonNode body, String key) {
        JsonNode value = body.path(key);
        return value.isString() ? value.stringValue() : null;
    }

    private static @Nullable String truthy(JsonNode params, String key) {
        JsonNode value = params.path(key);
        return value.isString() && !value.stringValue().isEmpty() ? value.stringValue() : null;
    }

    static tech.xingyunqi.template.contract.model.Favorite view(Favorite favorite) {
        return new tech.xingyunqi.template.contract.model.Favorite(
                BigDecimal.valueOf(favorite.id()),
                BigDecimal.valueOf(favorite.userId()),
                tech.xingyunqi.template.contract.model.Favorite.TargetTypeEnum.fromValue(favorite.targetType()),
                favorite.targetId(),
                favorite.title(),
                favorite.image(),
                favorite.extra(),
                favorite.subjectId() == null ? null : UUID.fromString(favorite.subjectId()),
                favorite.createdAt());
    }

    static FavoriteItemDto item(FavoritesService.Item item) {
        Favorite favorite = item.favorite();
        return new FavoriteItemDto(
                BigDecimal.valueOf(favorite.id()),
                BigDecimal.valueOf(favorite.userId()),
                FavoriteItemDto.TargetTypeEnum.fromValue(favorite.targetType()),
                favorite.targetId(),
                favorite.title(),
                favorite.image(),
                favorite.extra(),
                favorite.subjectId() == null ? null : UUID.fromString(favorite.subjectId()),
                favorite.createdAt(),
                item.targetOffline());
    }
}
