package tech.xingyunqi.template.modules.regions.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

@Mapper
public interface RegionMapper {

    record Row(
            int id,
            String code,
            String name,
            int level,
            @Nullable String parentCode,
            int sort,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    String SELECT = "select id, code, name, level, \"parentCode\", sort, \"createdAt\", \"updatedAt\" from region";

    @Select(SELECT + " where code = #{code} limit 1")
    @Nullable
    Row findByCode(@Param("code") String code);

    @Select(SELECT + " where level = #{level} order by sort, code")
    List<Row> findByLevel(@Param("level") int level);

    @Select("<script>" + SELECT
            + "<choose><when test='parentCode == null'> where \"parentCode\" is null</when>"
            + "<otherwise> where \"parentCode\" = #{parentCode}</otherwise></choose> order by sort, code</script>")
    List<Row> findByParentCode(@Param("parentCode") @Nullable String parentCode);

    @Select(SELECT + " order by level, sort, code")
    List<Row> findAll();

    @Select(SELECT + " where name ilike #{pattern} order by level, sort limit #{limit}")
    List<Row> searchByName(@Param("pattern") String pattern, @Param("limit") int limit);
}
