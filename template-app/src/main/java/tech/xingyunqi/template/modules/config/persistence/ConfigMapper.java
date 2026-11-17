package tech.xingyunqi.template.modules.config.persistence;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

@Mapper
public interface ConfigMapper {

    record Row(String key, String value, boolean isPublic, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    String COLUMNS = "key, value::text as value, \"isPublic\", \"createdAt\", \"updatedAt\"";

    @Select("select " + COLUMNS + " from config where key = #{key}")
    @Nullable
    Row findByKey(@Param("key") String key);

    @Select("insert into config (key, value, \"isPublic\") values (#{key}, #{value}::jsonb, #{isPublic}) returning "
            + COLUMNS)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row insert(@Param("key") String key, @Param("value") String value, @Param("isPublic") boolean isPublic);

    @Select("update config set value = #{value}::jsonb, \"isPublic\" = #{isPublic}, \"updatedAt\" = now()"
            + " where key = #{key} returning " + COLUMNS)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row update(@Param("key") String key, @Param("value") String value, @Param("isPublic") boolean isPublic);
}
