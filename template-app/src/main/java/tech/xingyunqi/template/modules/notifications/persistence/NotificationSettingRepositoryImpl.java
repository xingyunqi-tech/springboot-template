package tech.xingyunqi.template.modules.notifications.persistence;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.modules.notifications.NotificationSettingRepository;
import tech.xingyunqi.template.modules.notifications.domain.NotificationSetting;
import tech.xingyunqi.template.modules.notifications.domain.SettingFlags;

@Repository
class NotificationSettingRepositoryImpl implements NotificationSettingRepository {

    private final NotificationSettingMapper mapper;

    NotificationSettingRepositoryImpl(NotificationSettingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<NotificationSetting> find(int userId, String category) {
        return Optional.ofNullable(mapper.find(userId, category)).map(NotificationSettingRepositoryImpl::toDomain);
    }

    @Override
    public List<NotificationSetting> findAll(int userId) {
        return mapper.findAll(userId).stream()
                .map(NotificationSettingRepositoryImpl::toDomain)
                .toList();
    }

    @Override
    public NotificationSetting create(int userId, String category, SettingFlags flags) {
        return toDomain(mapper.insert(
                userId, category, flags.enableInApp(), flags.enableEmail(), flags.enableSms(), flags.enablePush()));
    }

    @Override
    public NotificationSetting update(int id, SettingFlags flags) {
        return toDomain(
                mapper.update(id, flags.enableInApp(), flags.enableEmail(), flags.enableSms(), flags.enablePush()));
    }

    @Override
    public void initializeDefaults(int userId, List<String> categories) {
        mapper.insertDefaults(userId, categories);
    }

    @Override
    public void removeByUser(int userId) {
        mapper.deleteByUser(userId);
    }

    static NotificationSetting toDomain(NotificationSettingMapper.Row row) {
        return new NotificationSetting(
                row.id(),
                row.userId(),
                row.category(),
                row.enableInApp(),
                row.enableEmail(),
                row.enableSms(),
                row.enablePush(),
                row.createdAt().toInstant(ZoneOffset.UTC),
                row.updatedAt().toInstant(ZoneOffset.UTC));
    }
}
