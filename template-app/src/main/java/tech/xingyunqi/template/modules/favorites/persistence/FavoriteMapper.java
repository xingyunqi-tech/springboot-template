package tech.xingyunqi.template.modules.favorites.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

@Mapper
public interface FavoriteMapper {

    record Row(
            int id,
            int userId,
            String targetType,
            String targetId,
            @Nullable String title,
            @Nullable String image,
            @Nullable String extra,
            @Nullable String subjectId,
            LocalDateTime createdAt) {}

    String COLUMNS = "select id, \"userId\", \"targetType\", \"targetId\", title, image, extra,"
            + " \"subjectId\"::text as \"subjectId\", \"createdAt\"";

    String SELECT = COLUMNS + " from favorite";

    String FILTER = "<if test='targetType != null'> and \"targetType\" = #{targetType}</if>"
            + "<if test='subjectId != null'> and \"subjectId\" = #{subjectId}::uuid</if>";

    @Select("with inserted as (insert into favorite (\"userId\", \"targetType\", \"targetId\", title, image, extra,"
            + " \"subjectId\") values (#{userId}, #{targetType}, #{targetId}, #{title}, #{image}, #{extra},"
            + " #{subjectId}::uuid) returning *) " + COLUMNS + " from inserted")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row insert(
            @Param("userId") int userId,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("title") @Nullable String title,
            @Param("image") @Nullable String image,
            @Param("extra") @Nullable String extra,
            @Param("subjectId") @Nullable String subjectId);

    @Select("<script>" + SELECT + " where \"userId\" = #{userId}" + FILTER
            + " order by \"createdAt\" desc offset #{offset} limit #{limit}</script>")
    List<Row> findMany(
            @Param("userId") int userId,
            @Param("targetType") @Nullable String targetType,
            @Param("subjectId") @Nullable String subjectId,
            @Param("offset") long offset,
            @Param("limit") long limit);

    @Select("<script>select count(*) from favorite where \"userId\" = #{userId}" + FILTER + "</script>")
    long count(
            @Param("userId") int userId,
            @Param("targetType") @Nullable String targetType,
            @Param("subjectId") @Nullable String subjectId);

    @Select(SELECT + " where id = #{id}")
    @Nullable
    Row findById(int id);

    @Select(SELECT + " where \"userId\" = #{userId} and \"targetType\" = #{targetType} and \"targetId\" = #{targetId}"
            + " limit 1")
    @Nullable
    Row findByUserAndTarget(
            @Param("userId") int userId, @Param("targetType") String targetType, @Param("targetId") String targetId);

    @Delete("delete from favorite where id = #{id}")
    int delete(int id);

    @Delete("delete from favorite where \"userId\" = #{userId}")
    int deleteByUser(int userId);
}
