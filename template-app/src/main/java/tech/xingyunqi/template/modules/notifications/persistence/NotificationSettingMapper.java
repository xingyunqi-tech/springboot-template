package tech.xingyunqi.template.modules.notifications.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

@Mapper
public interface NotificationSettingMapper {

    record Row(
            int id,
            int userId,
            String category,
            boolean enableInApp,
            boolean enableEmail,
            boolean enableSms,
            boolean enablePush,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    String COLUMNS = "select id, \"userId\", category, \"enableInApp\", \"enableEmail\", \"enableSms\", \"enablePush\","
            + " \"createdAt\", \"updatedAt\"";

    @Select(COLUMNS + " from notification_setting where \"userId\" = #{userId} and category = #{category}")
    @Nullable
    Row find(@Param("userId") int userId, @Param("category") String category);

    @Select(COLUMNS + " from notification_setting where \"userId\" = #{userId}")
    List<Row> findAll(int userId);

    @Select(
            "with inserted as (insert into notification_setting (\"userId\", category, \"enableInApp\", \"enableEmail\","
                    + " \"enableSms\", \"enablePush\") values (#{userId}, #{category}, #{inApp}, #{email}, #{sms}, #{push})"
                    + " returning *) " + COLUMNS + " from inserted")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row insert(
            @Param("userId") int userId,
            @Param("category") String category,
            @Param("inApp") boolean inApp,
            @Param("email") boolean email,
            @Param("sms") boolean sms,
            @Param("push") boolean push);

    @Select("with updated as (update notification_setting set \"enableInApp\" = #{inApp}, \"enableEmail\" = #{email},"
            + " \"enableSms\" = #{sms}, \"enablePush\" = #{push}, \"updatedAt\" = now() where id = #{id} returning *) "
            + COLUMNS + " from updated")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row update(
            @Param("id") int id,
            @Param("inApp") boolean inApp,
            @Param("email") boolean email,
            @Param("sms") boolean sms,
            @Param("push") boolean push);

    @Insert("<script>insert into notification_setting (\"userId\", category) values"
            + "<foreach collection='categories' item='c' separator=','>(#{userId}, #{c})</foreach>"
            + " on conflict (\"userId\", category) do nothing</script>")
    int insertDefaults(@Param("userId") int userId, @Param("categories") List<String> categories);

    @Delete("delete from notification_setting where \"userId\" = #{userId}")
    int deleteByUser(int userId);
}
