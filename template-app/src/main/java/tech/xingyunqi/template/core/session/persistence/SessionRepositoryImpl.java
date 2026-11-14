package tech.xingyunqi.template.core.session.persistence;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.core.session.SessionRepository;
import tech.xingyunqi.template.core.session.domain.Session;

@Repository
class SessionRepositoryImpl implements SessionRepository {

    private final SessionMapper mapper;

    SessionRepositoryImpl(SessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Session> findActive(int id) {
        return Optional.ofNullable(mapper.findActive(id)).map(SessionRepositoryImpl::toDomain);
    }

    @Override
    public int create(int userId, String hash, @Nullable String userAgent, @Nullable String ip) {
        SessionMapper.NewSession session = new SessionMapper.NewSession(userId, hash, userAgent, ip);
        mapper.insert(session);
        if (session.id == null) {
            throw new IllegalStateException("session id was not generated");
        }
        return session.id;
    }

    @Override
    public boolean rotateHash(int id, String current, String next) {
        return mapper.rotateHash(id, current, next) > 0;
    }

    @Override
    public List<Session> findActiveByUser(int userId) {
        return mapper.findActiveByUser(userId).stream()
                .map(SessionRepositoryImpl::toDomain)
                .toList();
    }

    @Override
    public void touch(int id, int staleAfterSeconds) {
        mapper.touch(id, staleAfterSeconds);
    }

    @Override
    public boolean softDeleteForUser(int id, int userId) {
        return mapper.softDeleteForUser(id, userId) > 0;
    }

    @Override
    public void softDelete(int id) {
        mapper.softDelete(id);
    }

    @Override
    public List<Integer> softDeleteByUser(int userId, @Nullable Integer exclude) {
        return mapper.softDeleteByUser(userId, exclude);
    }

    private static Session toDomain(SessionMapper.Row row) {
        return new Session(
                row.id(),
                row.userId(),
                row.hash(),
                row.userAgent(),
                row.ip(),
                utc(row.createdAt()),
                row.lastActiveAt() == null ? null : utc(row.lastActiveAt()));
    }

    private static java.time.Instant utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
