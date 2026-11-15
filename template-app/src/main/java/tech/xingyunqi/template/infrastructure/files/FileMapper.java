package tech.xingyunqi.template.infrastructure.files;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

@Mapper
public interface FileMapper {

    String COLUMNS = "id::text as id, path, \"ownerUserId\", visibility, \"mimeType\", size";

    @Select("<script>select " + COLUMNS + " from file"
            + " where id::text in <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<FileRecord> findByIds(@Param("ids") Collection<String> ids);

    @Select("select " + COLUMNS + " from file where id = #{id}::uuid")
    @Nullable
    FileRecord findById(@Param("id") String id);

    @Select("select " + COLUMNS + " from file where path = #{path} limit 1")
    @Nullable
    FileRecord findByPath(@Param("path") String path);

    /** 该用户上传的文件，按 id 升序取 {@code afterId} 之后的 {@code limit} 条。 */
    @Select("<script>select " + COLUMNS + " from file where \"ownerUserId\" = #{ownerUserId}"
            + "<if test='afterId != null'> and id &gt; #{afterId}::uuid</if> order by id limit #{limit}</script>")
    List<FileRecord> findByOwner(
            @Param("ownerUserId") int ownerUserId,
            @Param("afterId") @Nullable String afterId,
            @Param("limit") int limit);

    @Select("insert into file (path, \"ownerUserId\", visibility, \"mimeType\", size)"
            + " values (#{path}, #{ownerUserId}, #{visibility}, #{mimeType}, #{size}) returning " + COLUMNS)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    FileRecord insert(
            @Param("path") String path,
            @Param("ownerUserId") @Nullable Integer ownerUserId,
            @Param("visibility") String visibility,
            @Param("mimeType") @Nullable String mimeType,
            @Param("size") @Nullable Integer size);

    @Delete("delete from file where id = #{id}::uuid")
    int deleteById(@Param("id") String id);
}
