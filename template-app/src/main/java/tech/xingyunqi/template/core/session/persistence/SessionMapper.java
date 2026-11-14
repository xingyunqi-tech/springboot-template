package tech.xingyunqi.template.core.session.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;

@Mapper
public interface SessionMapper {

    /** {@code session} 表的一行。 */
    record Row(
            int id,
            @Nullable Integer userId,
            String hash,
            @Nullable String userAgent,
            @Nullable String ip,
            LocalDateTime createdAt,
            @Nullable LocalDateTime lastActiveAt) {}

    /** 插入参数，{@code id} 由数据库回填。 */
    final class NewSession {
        public @Nullable Integer id;
        public final int userId;
        public final String hash;
        public final @Nullable String userAgent;
        public final @Nullable String ip;

        public NewSession(int userId, String hash, @Nullable String userAgent, @Nullable String ip) {
            this.userId = userId;
            this.hash = hash;
            this.userAgent = userAgent;
            this.ip = ip;
        }
    }

    String COLUMNS = "id, \"userId\", hash, \"userAgent\", ip, \"createdAt\", \"lastActiveAt\"";

    @Select("select " + COLUMNS + " from session where id = #{id} and \"deletedAt\" is null")
    @Nullable
    Row findActive(int id);

    @Insert("insert into session (\"userId\", hash, \"userAgent\", ip, \"lastActiveAt\")"
            + " values (#{userId}, #{hash}, #{userAgent}, #{ip}, now())")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(NewSession session);

    @Update("update session set hash = #{next}, \"lastActiveAt\" = now(), \"updatedAt\" = now()"
            + " where id = #{id} and hash = #{current} and \"deletedAt\" is null")
    int rotateHash(@Param("id") int id, @Param("current") String current, @Param("next") String next);

    @Select("select " + COLUMNS + " from session where \"userId\" = #{userId} and \"deletedAt\" is null"
            + " order by \"lastActiveAt\" desc nulls last, id desc")
    List<Row> findActiveByUser(int userId);

    @Update("update session set \"lastActiveAt\" = now(), \"updatedAt\" = now() where id = #{id}"
            + " and (\"lastActiveAt\" is null or \"lastActiveAt\" < now() - make_interval(secs => #{stale}))")
    int touch(@Param("id") int id, @Param("stale") int staleAfterSeconds);

    @Update("update session set \"deletedAt\" = now(), \"updatedAt\" = now()"
            + " where id = #{id} and \"userId\" = #{userId} and \"deletedAt\" is null")
    int softDeleteForUser(@Param("id") int id, @Param("userId") int userId);

    @Update(
            "update session set \"deletedAt\" = now(), \"updatedAt\" = now() where id = #{id} and \"deletedAt\" is null")
    int softDelete(int id);

    @Select("<script>update session set \"deletedAt\" = now(), \"updatedAt\" = now()"
            + " where \"userId\" = #{userId} and \"deletedAt\" is null"
            + "<if test='exclude != null'> and id != #{exclude}</if> returning id</script>")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<Integer> softDeleteByUser(@Param("userId") int userId, @Param("exclude") @Nullable Integer exclude);
}
