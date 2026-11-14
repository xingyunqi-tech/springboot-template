package tech.xingyunqi.template.core.session;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.mybatis.AfterCommit;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.core.session.domain.Session;
import tools.jackson.databind.JsonNode;

/** 会话：创建、刷新轮换（CAS + 60s 宽限）、{@code lastActiveAt} 节流、吊销与鉴权缓存失效。 */
@Service
public class SessionService {

    static final long ROTATED_HASH_GRACE_MS = 60_000;
    static final int LAST_ACTIVE_MIN_INTERVAL_SECONDS = 5 * 60;

    private final SessionRepository repository;
    private final Cache cache;

    public SessionService(SessionRepository repository, Cache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public static String authCacheKey(int sessionId) {
        return "jwt:session:" + sessionId;
    }

    static String rotatedHashKey(int sessionId) {
        return "session:rotated:" + sessionId;
    }

    public Optional<Session> findActive(int id) {
        return repository.findActive(id);
    }

    public int create(int userId, String hash, @Nullable String userAgent, @Nullable String ip) {
        return repository.create(userId, hash, userAgent, ip);
    }

    public List<Session> findActiveByUser(int userId) {
        return repository.findActiveByUser(userId);
    }

    public void touch(int id) {
        repository.touch(id, LAST_ACTIVE_MIN_INTERVAL_SECONDS);
    }

    public boolean rotateHash(int id, String current, String next) {
        boolean rotated = repository.rotateHash(id, current, next);
        if (rotated) {
            cache.set(rotatedHashKey(id), current, ROTATED_HASH_GRACE_MS);
        }
        return rotated;
    }

    public boolean wasRecentlyRotatedFrom(int id, String hash) {
        JsonNode previous = cache.get(rotatedHashKey(id));
        return previous != null && previous.isString() && previous.asString().equals(hash);
    }

    public boolean revokeForUser(int id, int userId) {
        boolean revoked = repository.softDeleteForUser(id, userId);
        if (revoked) {
            forget(List.of(id));
        }
        return revoked;
    }

    public void delete(int id) {
        repository.softDelete(id);
        forget(List.of(id));
    }

    public void deleteByUser(int userId, @Nullable Integer exclude) {
        forget(repository.softDeleteByUser(userId, exclude));
    }

    /** 立即清除鉴权缓存，并在事务提交后再清一次，避免并发请求在提交前回填旧值。 */
    void forget(Collection<Integer> sessionIds) {
        List<String> keys =
                sessionIds.stream().map(SessionService::authCacheKey).toList();
        Runnable clear = () -> keys.forEach(cache::delete);
        clear.run();
        AfterCommit.always(clear);
    }
}
