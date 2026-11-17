package tech.xingyunqi.template.modules.consents.persistence;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.modules.consents.UserConsentRepository;
import tech.xingyunqi.template.modules.consents.domain.ConsentType;
import tech.xingyunqi.template.modules.consents.domain.UserConsent;

@Repository
class UserConsentRepositoryImpl implements UserConsentRepository {

    private final UserConsentMapper mapper;

    UserConsentRepositoryImpl(UserConsentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertIfAbsent(int userId, ConsentType type, String version, @Nullable String ip) {
        mapper.insertIfAbsent(userId, type.name(), version, ip);
    }

    @Override
    public List<UserConsent> findLatestByUser(int userId) {
        return mapper.findLatestByUser(userId).stream()
                .map(UserConsentRepositoryImpl::consent)
                .toList();
    }

    @Override
    public boolean exists(int userId, ConsentType type, String version) {
        return mapper.exists(userId, type.name(), version);
    }

    @Override
    public Optional<UserConsent> findOne(int userId, ConsentType type, String version) {
        return Optional.ofNullable(mapper.findOne(userId, type.name(), version))
                .map(UserConsentRepositoryImpl::consent);
    }

    private static UserConsent consent(UserConsentMapper.Row row) {
        return new UserConsent(
                row.id(),
                row.userId(),
                ConsentType.valueOf(row.type()),
                row.version(),
                row.agreedAt().toInstant(ZoneOffset.UTC),
                row.ip());
    }
}
