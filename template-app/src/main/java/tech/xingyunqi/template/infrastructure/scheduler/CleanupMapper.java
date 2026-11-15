package tech.xingyunqi.template.infrastructure.scheduler;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CleanupMapper {

    @Delete("delete from session where \"deletedAt\" is not null and \"deletedAt\" < now() - interval '7 days'")
    int deleteOldSessions();

    @Update("update notification set \"deletedAt\" = now() where \"isRead\" = true and \"readAt\" is not null"
            + " and \"readAt\" < now() - interval '30 days' and \"deletedAt\" is null")
    int softDeleteReadNotifications();
}
