package tech.xingyunqi.template.modules.notifications;

import java.util.List;
import java.util.Optional;
import tech.xingyunqi.template.modules.notifications.domain.NotificationSetting;
import tech.xingyunqi.template.modules.notifications.domain.SettingFlags;

public interface NotificationSettingRepository {

    Optional<NotificationSetting> find(int userId, String category);

    List<NotificationSetting> findAll(int userId);

    NotificationSetting create(int userId, String category, SettingFlags flags);

    NotificationSetting update(int id, SettingFlags flags);

    /** 为每个分类插入全部开启的设置，已存在的分类不变。 */
    void initializeDefaults(int userId, List<String> categories);

    void removeByUser(int userId);
}
