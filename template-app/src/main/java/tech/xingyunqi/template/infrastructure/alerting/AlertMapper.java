package tech.xingyunqi.template.infrastructure.alerting;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

@Mapper
public interface AlertMapper {

    /** 同一 dedupKey 已有未确认告警（部分唯一索引 {@code UQ_admin_alert_dedupKey_open}）时不插入，返回 null。 */
    @Select("insert into admin_alert (type, level, message, payload, \"dedupKey\")"
            + " values (#{type}, #{level}, #{message}, cast(#{payload} as jsonb), #{dedupKey})"
            + " on conflict do nothing returning id")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Nullable
    Integer insertIfNoOpen(
            @Param("type") String type,
            @Param("level") String level,
            @Param("message") String message,
            @Param("payload") @Nullable String payload,
            @Param("dedupKey") String dedupKey);

    @Select("select id from \"user\" where \"roleId\" = #{roleId} and \"deletedAt\" is null order by id limit #{limit}")
    List<Integer> findUserIdsByRole(@Param("roleId") int roleId, @Param("limit") int limit);

    /** 用户该分类的通知设置；无设置时为 null。 */
    @Select(
            "select \"enableInApp\" as \"inApp\", \"enableEmail\" as email, \"enableSms\" as sms, \"enablePush\" as push"
                    + " from notification_setting where \"userId\" = #{userId} and category = #{category}")
    @Nullable
    ChannelSetting findSetting(@Param("userId") int userId, @Param("category") String category);

    @Insert(
            "insert into notification (\"userId\", type, category, title, content, metadata, channels, \"sentChannels\","
                    + " \"isRead\") values (#{userId}, #{type}, #{category}, #{title}, #{content}, cast(#{metadata} as jsonb),"
                    + " cast(#{channels} as jsonb), '{}'::jsonb, false)")
    void insertNotification(
            @Param("userId") int userId,
            @Param("type") String type,
            @Param("category") String category,
            @Param("title") String title,
            @Param("content") String content,
            @Param("metadata") @Nullable String metadata,
            @Param("channels") String channels);

    /** {@code notification_setting} 的渠道开关。 */
    record ChannelSetting(boolean inApp, boolean email, boolean sms, boolean push) {}
}
