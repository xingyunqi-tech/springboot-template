package tech.xingyunqi.template.modules.notifications.web;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.bind.ValidatedQuery;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.contract.api.NotificationsApi;
import tech.xingyunqi.template.contract.model.NotificationSetting;
import tech.xingyunqi.template.contract.model.PaginationNotificationResponseDto;
import tech.xingyunqi.template.contract.model.UpdateNotificationSettingsDto;
import tech.xingyunqi.template.modules.notifications.NotificationRepository;
import tech.xingyunqi.template.modules.notifications.NotificationsService;
import tech.xingyunqi.template.modules.notifications.domain.Notification;
import tech.xingyunqi.template.modules.notifications.domain.SettingFlags;
import tech.xingyunqi.template.views.NotificationView;
import tools.jackson.databind.JsonNode;

@RestController
@AuthenticatedOnly
class NotificationsController implements NotificationsApi {

    static final int MAX_LIMIT = 50;

    private final NotificationsService notifications;
    private final ValidatedQuery query;

    NotificationsController(NotificationsService notifications, ValidatedQuery query) {
        this.notifications = notifications;
        this.query = query;
    }

    @Override
    public PaginationNotificationResponseDto getNotifications(
            @Nullable String page,
            @Nullable String limit,
            @Nullable String category,
            @Nullable String isRead,
            @Nullable Object acceptLanguage) {
        JsonNode params = query.validate("QueryNotificationDto", CurrentRequest.get());
        long pageValue = params.hasNonNull("page") ? params.get("page").asLong() : 1;
        long limitValue =
                Math.min(params.hasNonNull("limit") ? params.get("limit").asLong() : 10, MAX_LIMIT);
        JsonNode categoryNode = params.path("category");
        JsonNode isReadNode = params.path("isRead");
        NotificationRepository.Filter filter = new NotificationRepository.Filter(
                categoryNode.isString() && !categoryNode.stringValue().isEmpty() ? categoryNode.stringValue() : null,
                isReadNode.isBoolean() ? isReadNode.booleanValue() : null);
        NotificationsService.Page result = notifications.findPage(userId(), filter, pageValue, limitValue);
        return new PaginationNotificationResponseDto(
                result.data().stream().map(NotificationsController::view).toList(),
                BigDecimal.valueOf(result.total()),
                BigDecimal.valueOf(pageValue),
                BigDecimal.valueOf(limitValue),
                BigDecimal.valueOf((result.total() + limitValue - 1) / limitValue));
    }

    @Override
    public BigDecimal getUnreadCount(@Nullable Object acceptLanguage) {
        return BigDecimal.valueOf(notifications.getUnreadCount(userId()));
    }

    @Override
    public List<NotificationSetting> getNotificationSettings(@Nullable Object acceptLanguage) {
        return notifications.getSettings(userId()).stream()
                .map(NotificationsController::view)
                .toList();
    }

    @Override
    public NotificationView getNotification(BigDecimal id, @Nullable Object acceptLanguage) {
        return view(notifications.findOne(id.intValueExact(), userId()));
    }

    @Override
    public void markAllNotificationsAsRead(@Nullable Object acceptLanguage) {
        notifications.markAllAsRead(userId());
    }

    @Override
    public NotificationSetting updateNotificationSettings(
            UpdateNotificationSettingsDto updateNotificationSettingsDto, @Nullable Object acceptLanguage) {
        JsonNode body = ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get());
        if (body == null) {
            throw HttpStatusException.badRequest(null);
        }
        SettingFlags flags = new SettingFlags(
                body.path("enableInApp").asBoolean(),
                body.path("enableEmail").asBoolean(),
                body.path("enableSms").asBoolean(),
                body.path("enablePush").asBoolean());
        return view(notifications.updateSettings(userId(), body.path("category").asString(""), flags));
    }

    @Override
    public NotificationView markNotificationAsRead(BigDecimal id, @Nullable Object acceptLanguage) {
        return view(notifications.markAsRead(id.intValueExact(), userId()));
    }

    @Override
    public void deleteNotification(BigDecimal id, @Nullable Object acceptLanguage) {
        notifications.remove(id.intValueExact(), userId());
    }

    private static int userId() {
        return AuthContext.current().id();
    }

    static NotificationView view(Notification notification) {
        return new NotificationView(
                notification.id(),
                notification.userId(),
                notification.type(),
                notification.category(),
                notification.title(),
                notification.content(),
                notification.metadata(),
                notification.channels(),
                notification.sentChannels(),
                notification.isRead(),
                notification.readAt(),
                notification.createdAt(),
                notification.updatedAt(),
                notification.deletedAt());
    }

    static NotificationSetting view(tech.xingyunqi.template.modules.notifications.domain.NotificationSetting setting) {
        return new NotificationSetting(
                BigDecimal.valueOf(setting.id()),
                BigDecimal.valueOf(setting.userId()),
                NotificationSetting.CategoryEnum.fromValue(setting.category()),
                setting.enableInApp(),
                setting.enableEmail(),
                setting.enableSms(),
                setting.enablePush(),
                setting.createdAt(),
                setting.updatedAt());
    }
}
