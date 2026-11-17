package tech.xingyunqi.template.modules.feedbacks.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;

@Mapper
public interface FeedbackMapper {

    record Row(
            int id,
            @Nullable Integer userId,
            String type,
            String content,
            @Nullable String images,
            @Nullable String contact,
            String status,
            @Nullable String reply,
            @Nullable String questionId,
            @Nullable Integer questionVersion,
            @Nullable String reason,
            @Nullable String errataId,
            @Nullable String messageId,
            @Nullable String messageSnapshot,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    String COLUMNS = "select id, \"userId\", type, content, images::text as images, contact, status, reply,"
            + " \"questionId\"::text as \"questionId\", \"questionVersion\", reason, \"errataId\"::text as \"errataId\","
            + " \"messageId\"::text as \"messageId\", \"messageSnapshot\"::text as \"messageSnapshot\", \"createdAt\","
            + " \"updatedAt\"";

    String WHERE = " where \"userId\" = #{userId} and type in"
            + "<foreach collection='types' item='t' open='(' separator=',' close=')'>#{t}</foreach>"
            + "<if test='status != null'> and status = #{status}</if>";

    @Select("with inserted as (insert into feedback (\"userId\", type, content, images, contact, status)"
            + " values (#{userId}, #{type}, #{content}, cast(#{images} as jsonb), #{contact}, 'pending') returning *) "
            + COLUMNS + " from inserted")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row insert(
            @Param("userId") int userId,
            @Param("type") String type,
            @Param("content") String content,
            @Param("images") @Nullable String images,
            @Param("contact") @Nullable String contact);

    @Select(COLUMNS + " from feedback where id = #{id}")
    @Nullable
    Row findById(int id);

    @Select("<script>" + COLUMNS + " from feedback" + WHERE
            + " order by \"createdAt\" desc, id desc offset #{offset} limit #{limit}</script>")
    List<Row> findMany(
            @Param("userId") int userId,
            @Param("types") List<String> types,
            @Param("status") @Nullable String status,
            @Param("offset") long offset,
            @Param("limit") long limit);

    @Select("<script>select count(*) from feedback" + WHERE + "</script>")
    long count(
            @Param("userId") int userId, @Param("types") List<String> types, @Param("status") @Nullable String status);

    @Update("update feedback set \"userId\" = null, contact = null, images = null, \"updatedAt\" = now()"
            + " where \"userId\" = #{userId}")
    int anonymizeByUser(int userId);
}
