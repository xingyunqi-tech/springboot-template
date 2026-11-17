package tech.xingyunqi.template.modules.notifications.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.modules.notifications.NotificationRepository;
import tech.xingyunqi.template.modules.notifications.domain.CreateNotification;
import tech.xingyunqi.template.modules.notifications.domain.Notification;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class NotificationRepositoryImpl implements NotificationRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, @Nullable Object>> OBJECT = new TypeReference<>() {};

    private final NotificationMapper mapper;
    private final JsonMapper json;

    NotificationRepositoryImpl(NotificationMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Notification create(CreateNotification data, List<String> channels) {
        String metadata = data.metadata() == null ? null : json.writeValueAsString(data.metadata());
        return toDomain(mapper.insert(
                data.userId(),
                data.type(),
                data.category(),
                data.title(),
                data.content(),
                metadata,
                json.writeValueAsString(channels)));
    }

    @Override
    public List<Notification> findMany(int userId, Filter filter, long offset, long limit) {
        return mapper.findMany(userId, filter, offset, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long count(int userId, Filter filter) {
        return mapper.count(userId, filter);
    }

    @Override
    public Optional<Notification> findByIdAndUser(int id, int userId) {
        return Optional.ofNullable(mapper.findByIdAndUser(id, userId)).map(this::toDomain);
    }

    @Override
    public Notification markAsRead(int id, Instant readAt) {
        return toDomain(mapper.markAsRead(id, local(readAt)));
    }

    @Override
    public void markAllAsRead(int userId, Instant readAt) {
        mapper.markAllAsRead(userId, local(readAt));
    }

    @Override
    public void softDelete(int id) {
        mapper.softDelete(id);
    }

    @Override
    public void recordChannelResult(int id, String channel, Map<String, Object> result) {
        mapper.recordChannelResult(id, json.writeValueAsString(Map.of(channel, result)));
    }

    @Override
    public void removeByUser(int userId) {
        mapper.deleteByUser(userId);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private Notification toDomain(NotificationMapper.Row row) {
        return new Notification(
                row.id(),
                row.userId(),
                row.type(),
                row.category(),
                row.title(),
                row.content(),
                row.metadata() == null ? null : json.readValue(row.metadata(), OBJECT),
                json.readValue(row.channels(), STRINGS),
                json.readValue(row.sentChannels(), OBJECT),
                row.isRead(),
                row.readAt() == null ? null : utc(row.readAt()),
                utc(row.createdAt()),
                utc(row.updatedAt()),
                row.deletedAt() == null ? null : utc(row.deletedAt()));
    }
}
