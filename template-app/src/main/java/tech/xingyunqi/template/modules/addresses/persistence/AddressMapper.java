package tech.xingyunqi.template.modules.addresses.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.addresses.domain.AddressFilter;

@Mapper
public interface AddressMapper {

    record Row(
            int id,
            int userId,
            String name,
            String phone,
            @Nullable String provinceCode,
            @Nullable String cityCode,
            @Nullable String districtCode,
            String province,
            String city,
            String district,
            String address,
            boolean isDefault,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            @Nullable LocalDateTime deletedAt) {}

    /** 排序项：列名来自白名单。 */
    record Order(String column, boolean descending) {}

    String SELECT = "select id, \"userId\", name, phone, \"provinceCode\", \"cityCode\", \"districtCode\", province,"
            + " city, district, address, \"isDefault\", \"createdAt\", \"updatedAt\", \"deletedAt\" from address";

    String FILTER = "<if test='filter.province != null'> and province = #{filter.province}</if>"
            + "<if test='filter.city != null'> and city = #{filter.city}</if>"
            + "<if test='filter.provinceCode != null'> and \"provinceCode\" = #{filter.provinceCode}</if>"
            + "<if test='filter.cityCode != null'> and \"cityCode\" = #{filter.cityCode}</if>"
            + "<if test='filter.districtCode != null'> and \"districtCode\" = #{filter.districtCode}</if>";

    @Select("select 1 from (select pg_advisory_xact_lock(hashtext('address'), #{userId})) locked")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int lockUser(int userId);

    @Select("<script>select count(*) from address where \"userId\" = #{userId} and \"deletedAt\" is null" + FILTER
            + "</script>")
    long count(@Param("userId") int userId, @Param("filter") AddressFilter filter);

    @Select("insert into address (\"userId\", name, phone, \"provinceCode\", \"cityCode\", \"districtCode\", province,"
            + " city, district, address, \"isDefault\") values (#{userId}, #{name}, #{phone}, #{provinceCode},"
            + " #{cityCode}, #{districtCode}, #{province}, #{city}, #{district}, #{address}, false) returning id")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int insert(
            @Param("userId") int userId,
            @Param("name") @Nullable String name,
            @Param("phone") @Nullable String phone,
            @Param("provinceCode") @Nullable String provinceCode,
            @Param("cityCode") @Nullable String cityCode,
            @Param("districtCode") @Nullable String districtCode,
            @Param("province") String province,
            @Param("city") String city,
            @Param("district") String district,
            @Param("address") @Nullable String address);

    @Select("<script>" + SELECT + " where \"userId\" = #{userId} and \"deletedAt\" is null" + FILTER
            + " order by <foreach collection='orders' item='o' separator=','>\"${o.column}\""
            + "<if test='o.descending'> desc</if></foreach> offset #{offset} limit #{limit}</script>")
    List<Row> findMany(
            @Param("userId") int userId,
            @Param("filter") AddressFilter filter,
            @Param("orders") List<Order> orders,
            @Param("offset") long offset,
            @Param("limit") long limit);

    @Select(SELECT + " where id = #{id} and \"deletedAt\" is null")
    @Nullable
    Row findById(int id);

    @Select(SELECT + " where id = #{id} and \"userId\" = #{userId} and \"deletedAt\" is null")
    @Nullable
    Row findByIdAndUser(@Param("id") int id, @Param("userId") int userId);

    @Select(SELECT + " where \"userId\" = #{userId} and \"isDefault\" and \"deletedAt\" is null limit 1")
    @Nullable
    Row findDefault(int userId);

    @Update("<script>update address set <foreach collection='changes' index='k' item='v' separator=','>"
            + "\"${k}\" = #{v}</foreach>, \"updatedAt\" = now() where id = #{id} and \"deletedAt\" is null</script>")
    int update(@Param("id") int id, @Param("changes") Map<String, @Nullable Object> changes);

    @Select("select exists(select 1 from address where id = #{id} and \"userId\" = #{userId}"
            + " and \"deletedAt\" is null)")
    boolean existsActive(@Param("id") int id, @Param("userId") int userId);

    @Update("update address set \"isDefault\" = false where \"userId\" = #{userId} and \"isDefault\""
            + " and id <> #{id} and \"deletedAt\" is null")
    int clearOtherDefaults(@Param("id") int id, @Param("userId") int userId);

    @Update("update address set \"isDefault\" = true where id = #{id} and \"userId\" = #{userId}"
            + " and \"deletedAt\" is null")
    int setDefault(@Param("id") int id, @Param("userId") int userId);

    @Update(
            "update address set \"deletedAt\" = now(), \"updatedAt\" = now() where id = #{id} and \"deletedAt\" is null")
    int softDelete(int id);

    @Delete("delete from address where \"userId\" = #{userId}")
    int deleteByUser(int userId);
}
