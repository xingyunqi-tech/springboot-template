package tech.xingyunqi.template.infrastructure.files;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileMapper {

    @Select("<script>select id::text as id, path, \"ownerUserId\", visibility, \"mimeType\", size from file"
            + " where id::text in <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<FileRecord> findByIds(@Param("ids") Collection<String> ids);
}
