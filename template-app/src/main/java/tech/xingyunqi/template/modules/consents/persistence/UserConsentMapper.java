package tech.xingyunqi.template.modules.consents.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

@Mapper
public interface UserConsentMapper {

    record Row(
            String id,
            int userId,
            String type,
            String version,
            LocalDateTime agreedAt,
            @Nullable String ip) {}

    String COLUMNS = "id::text as id, \"userId\", type, version, \"agreedAt\", ip";

    @Insert("insert into user_consent (\"userId\", type, version, ip) values (#{userId}, #{type}, #{version}, #{ip})"
            + " on conflict (\"userId\", type, version) do nothing")
    int insertIfAbsent(
            @Param("userId") int userId,
            @Param("type") String type,
            @Param("version") String version,
            @Param("ip") @Nullable String ip);

    @Select("select distinct on (type) " + COLUMNS + " from user_consent where \"userId\" = #{userId}"
            + " order by type, \"agreedAt\" desc, user_consent.id desc")
    List<Row> findLatestByUser(@Param("userId") int userId);

    @Select("select exists(select 1 from user_consent where \"userId\" = #{userId} and type = #{type}"
            + " and version = #{version})")
    boolean exists(@Param("userId") int userId, @Param("type") String type, @Param("version") String version);

    @Select("select " + COLUMNS + " from user_consent where \"userId\" = #{userId} and type = #{type}"
            + " and version = #{version} limit 1")
    @Nullable
    Row findOne(@Param("userId") int userId, @Param("type") String type, @Param("version") String version);
}
