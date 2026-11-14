package tech.xingyunqi.template.core.session;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.core.session.domain.Session;

public interface SessionRepository {

    Optional<Session> findActive(int id);

    int create(int userId, String hash, @Nullable String userAgent, @Nullable String ip);

    boolean rotateHash(int id, String current, String next);

    List<Session> findActiveByUser(int userId);

    void touch(int id, int staleAfterSeconds);

    boolean softDeleteForUser(int id, int userId);

    void softDelete(int id);

    List<Integer> softDeleteByUser(int userId, @Nullable Integer exclude);
}
