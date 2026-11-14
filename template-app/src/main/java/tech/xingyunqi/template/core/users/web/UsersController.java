package tech.xingyunqi.template.core.users.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.bind.ValidatedQuery;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.PermissionsGuarded;
import tech.xingyunqi.template.common.web.route.RequirePermissions;
import tech.xingyunqi.template.contract.api.UsersApi;
import tech.xingyunqi.template.contract.model.CreateUserDto;
import tech.xingyunqi.template.contract.model.InfinityPaginationUserResponseDto;
import tech.xingyunqi.template.contract.model.UpdateUserDto;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.SortItem;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.views.UserView;
import tools.jackson.databind.JsonNode;

@RestController
@AuthenticatedOnly
@PermissionsGuarded
class UsersController implements UsersApi {

    static final int MAX_LIMIT = 50;

    private final UsersService users;
    private final UserViews views;
    private final ValidatedQuery query;

    UsersController(UsersService users, UserViews views, ValidatedQuery query) {
        this.users = users;
        this.views = views;
        this.query = query;
    }

    @Override
    @RequirePermissions("user:create")
    public UserView createUser(CreateUserDto createUserDto, @Nullable Object acceptLanguage) {
        UsersService.Created created = users.create(UserInputs.from(body()));
        return views.of(created.user(), UserViews.Group.ADMIN, created.relations());
    }

    @Override
    @RequirePermissions("user:list")
    public InfinityPaginationUserResponseDto getUsers(
            @Nullable String page,
            @Nullable String limit,
            @Nullable String filters,
            @Nullable String sort,
            @Nullable Object acceptLanguage) {
        JsonNode params = query.validate("QueryUserDto", CurrentRequest.get());
        long pageValue = params.hasNonNull("page") ? params.get("page").asLong() : 1;
        int limitValue =
                (int) Math.min(params.hasNonNull("limit") ? params.get("limit").asLong() : 10, MAX_LIMIT);
        List<Integer> roleIds = null;
        JsonNode roles = params.path("filters").path("roles");
        if (roles.isArray() && !roles.isEmpty()) {
            roleIds = new ArrayList<>();
            for (JsonNode role : roles) {
                roleIds.add(role.path("id").asInt());
            }
        }
        List<SortItem> sortItems = null;
        JsonNode sortNode = params.path("sort");
        if (sortNode.isArray() && !sortNode.isEmpty()) {
            sortItems = new ArrayList<>();
            for (JsonNode item : sortNode) {
                sortItems.add(new SortItem(
                        item.path("orderBy").asString(""), item.path("order").asString("")));
            }
        }
        List<User> found = users.findMany(roleIds, sortItems, pageValue, limitValue);
        List<UserView> data = found.stream()
                .map(user -> views.of(user, UserViews.Group.ADMIN))
                .toList();
        return new InfinityPaginationUserResponseDto(data, found.size() == limitValue);
    }

    @Override
    @RequirePermissions("user:read")
    public @Nullable UserView getUser(BigDecimal id, @Nullable Object acceptLanguage) {
        return users.findById(id.intValueExact())
                .map(user -> views.of(user, UserViews.Group.ADMIN))
                .orElse(null);
    }

    @Override
    @RequirePermissions("user:update")
    public UserView updateUser(BigDecimal id, UpdateUserDto updateUserDto, @Nullable Object acceptLanguage) {
        return views.of(users.updateByAdmin(id.intValueExact(), UserInputs.from(body())), UserViews.Group.ADMIN);
    }

    @Override
    @RequirePermissions("user:delete")
    public void deleteUser(BigDecimal id, @Nullable Object acceptLanguage) {
        users.remove(id.intValueExact());
    }

    private JsonNode body() {
        JsonNode body = ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get());
        if (body == null) {
            throw HttpStatusException.badRequest(null);
        }
        return body;
    }
}
