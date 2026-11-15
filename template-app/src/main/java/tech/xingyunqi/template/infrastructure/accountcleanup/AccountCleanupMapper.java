package tech.xingyunqi.template.infrastructure.accountcleanup;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountCleanupMapper {

    @Insert("insert into account_cleanup (\"userId\", \"completedAt\") values (#{userId}, now())"
            + " on conflict (\"userId\") do update set \"completedAt\" = excluded.\"completedAt\"")
    void markCompleted(int userId);

    /** 软删除早于 {@code deletedBefore} 且没有完成记录的用户，按 id 升序。 */
    @Select("select u.id from \"user\" u where u.\"deletedAt\" is not null"
            + " and u.\"deletedAt\" < (cast(#{deletedBefore} as timestamptz) at time zone current_setting('TimeZone'))"
            + " and not exists (select 1 from account_cleanup c where c.\"userId\" = u.id)"
            + " order by u.id limit #{limit}")
    List<Integer> findPendingUserIds(@Param("deletedBefore") OffsetDateTime deletedBefore, @Param("limit") int limit);
}
