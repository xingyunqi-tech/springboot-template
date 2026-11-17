package tech.xingyunqi.template.modules.notifications.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.notifications.NotificationRepository;

@Mapper
public interface NotificationMapper {

    record Row(
            int id,
            int userId,
            String type,
            String category,
            String title,
            String content,
            @Nullable String metadata,
            String channels,
            String sentChannels,
            boolean isRead,
            @Nullable LocalDateTime readAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            @Nullable LocalDateTime deletedAt) {}

    String COLUMNS = "select id, \"userId\", type, category, title, content, metadata::text as metadata,"
            + " channels::text as channels, \"sentChannels\"::text as \"sentChannels\", \"isRead\", \"readAt\","
            + " \"createdAt\", \"updatedAt\", \"deletedAt\"";

    String VISIBLE = " where \"userId\" = #{userId} and channels @> '[\"IN_APP\"]'::jsonb and \"deletedAt\" is null";

    String FILTER = "<if test='filter.category != null'> and category = #{filter.category}</if>"
            + "<if test='filter.isRead != null'> and \"isRead\" = #{filter.isRead}</if>";

    @Select(
            "with inserted as (insert into notification (\"userId\", type, category, title, content, metadata, channels,"
                    + " \"sentChannels\", \"isRead\") values (#{userId}, #{type}, #{category}, #{title}, #{content},"
                    + " cast(#{metadata} as jsonb), cast(#{channels} as jsonb), '{}'::jsonb, false) returning *) "
                    + COLUMNS
                    + " from inserted")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row insert(
            @Param("userId") int userId,
            @Param("type") String type,
            @Param("category") String category,
            @Param("title") String title,
            @Param("content") String content,
            @Param("metadata") @Nullable String metadata,
            @Param("channels") String channels);

    @Select("<script>" + COLUMNS + " from notification" + VISIBLE + FILTER
            + " order by \"createdAt\" desc offset #{offset} limit #{limit}</script>")
    List<Row> findMany(
            @Param("userId") int userId,
            @Param("filter") NotificationRepository.Filter filter,
            @Param("offset") long offset,
            @Param("limit") long limit);

    @Select("<script>select count(*) from notification" + VISIBLE + FILTER + "</script>")
    long count(@Param("userId") int userId, @Param("filter") NotificationRepository.Filter filter);

    @Select(COLUMNS + " from notification" + VISIBLE + " and id = #{id}")
    @Nullable
    Row findByIdAndUser(@Param("id") int id, @Param("userId") int userId);

    @Select("with updated as (update notification set \"isRead\" = true, \"readAt\" = #{readAt}, \"updatedAt\" = now()"
            + " where id = #{id} returning *) " + COLUMNS + " from updated")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row markAsRead(@Param("id") int id, @Param("readAt") LocalDateTime readAt);

    @Update("update notification set \"isRead\" = true, \"readAt\" = #{readAt}, \"updatedAt\" = now()"
            + " where \"userId\" = #{userId} and \"isRead\" = false and channels @> '[\"IN_APP\"]'::jsonb")
    int markAllAsRead(@Param("userId") int userId, @Param("readAt") LocalDateTime readAt);

    @Update("update notification set \"deletedAt\" = now(), \"updatedAt\" = now() where id = #{id}"
            + " and \"deletedAt\" is null")
    int softDelete(int id);

    @Update("update notification set \"sentChannels\" = \"sentChannels\" || cast(#{patch} as jsonb),"
            + " \"updatedAt\" = now() where id = #{id} and \"deletedAt\" is null")
    int recordChannelResult(@Param("id") int id, @Param("patch") String patch);

    @Delete("delete from notification where \"userId\" = #{userId}")
    int deleteByUser(int userId);
}
