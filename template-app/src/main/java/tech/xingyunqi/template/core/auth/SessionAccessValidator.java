package tech.xingyunqi.template.core.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.common.security.route.AccessTokenValidator;
import tech.xingyunqi.template.common.security.route.AuthPrincipal;
import tech.xingyunqi.template.common.security.route.Claims;
import tech.xingyunqi.template.core.permissions.StatusIds;
import tech.xingyunqi.template.core.session.SessionService;
import tech.xingyunqi.template.core.session.domain.Session;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.User;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** nestjs JwtStrategy.validate：会话存在且属于该用户、状态允许登录；结果缓存 30 秒，未命中时节流更新 {@code lastActiveAt}。 */
@Component
class SessionAccessValidator implements AccessTokenValidator {

    static final long CACHE_TTL_MS = 30_000;
    private static final Logger LOG = LoggerFactory.getLogger(SessionAccessValidator.class);

    private final SessionService sessions;
    private final UsersService users;
    private final Cache cache;

    SessionAccessValidator(SessionService sessions, UsersService users, Cache cache) {
        this.sessions = sessions;
        this.users = users;
        this.cache = cache;
    }

    @Override
    public AuthPrincipal validate(ObjectNode payload) {
        JsonNode userClaim = payload.get("id");
        JsonNode sessionClaim = payload.get("sessionId");
        if (!truthy(userClaim) || !truthy(sessionClaim)) {
            throw HttpStatusException.unauthorized(null);
        }
        Integer userId = Claims.positiveInt(userClaim);
        Integer sessionId = Claims.positiveInt(sessionClaim);
        if (userId == null || sessionId == null) {
            throw HttpStatusException.unauthorized("Session expired");
        }
        String key = SessionService.authCacheKey(sessionId);
        JsonNode cached = cache.get(key);
        if (cached != null && !cached.isEmpty()) {
            if (!StatusIds.allowedForAuth(intOrNull(cached.get("statusId")))) {
                throw HttpStatusException.unauthorized("User is not active");
            }
            return new AuthPrincipal(userId, sessionId, intOrNull(cached.get("roleId")), payload);
        }
        Optional<Session> session = sessions.findActive(sessionId);
        Integer owner = session.map(Session::userId).orElse(null);
        User user = owner == null ? null : users.findById(owner).orElse(null);
        if (user == null || !String.valueOf(user.id()).equals(Claims.jsString(userClaim))) {
            throw HttpStatusException.unauthorized("Session expired");
        }
        if (!StatusIds.allowedForAuth(user.statusId())) {
            throw HttpStatusException.unauthorized("User is not active");
        }
        try {
            sessions.touch(sessionId);
        } catch (RuntimeException e) {
            LOG.warn("Failed to update lastActiveAt of session {}: {}", sessionId, e.toString());
        }
        Map<String, @Nullable Object> value = new LinkedHashMap<>();
        value.put("statusId", user.statusId());
        value.put("roleId", user.roleId());
        cache.set(key, value, CACHE_TTL_MS);
        return new AuthPrincipal(userId, sessionId, user.roleId(), payload);
    }

    private static boolean truthy(@Nullable JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isNumber()) {
            return value.asDouble() != 0 && !Double.isNaN(value.asDouble());
        }
        if (value.isString()) {
            return !value.asString().isEmpty();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return true;
    }

    private static @Nullable Integer intOrNull(@Nullable JsonNode value) {
        return value == null || !value.isNumber() ? null : value.asInt();
    }
}
