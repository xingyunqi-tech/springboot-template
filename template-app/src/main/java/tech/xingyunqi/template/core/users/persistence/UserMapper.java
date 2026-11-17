package tech.xingyunqi.template.core.users.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;

@Mapper
public interface UserMapper {

    /** 用户行及左连接的角色、状态、头像。 */
    record Row(
            int id,
            @Nullable String email,
            @Nullable String password,
            String provider,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable String phone,
            @Nullable String nickname,
            @Nullable Integer gender,
            @Nullable LocalDate birthday,
            @Nullable String photoId,
            @Nullable Integer roleId,
            @Nullable Integer statusId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            @Nullable LocalDateTime deletedAt,
            @Nullable String wechatOpenId,
            @Nullable String wechatUnionId,
            @Nullable String alipayOpenId,
            @Nullable String roleName,
            @Nullable String statusName,
            @Nullable String photoPath,
            @Nullable Integer photoOwnerUserId,
            @Nullable String photoVisibility,
            @Nullable String photoMimeType,
            @Nullable Integer photoSize) {}

    /** 排序项：列名来自白名单。 */
    record Order(String column, boolean descending) {}

    String SELECT = "select u.id, u.email, u.password, u.provider, u.\"firstName\", u.\"lastName\", u.phone,"
            + " u.nickname, u.gender, u.birthday, u.\"photoId\"::text as \"photoId\", u.\"roleId\", u.\"statusId\","
            + " u.\"createdAt\", u.\"updatedAt\", u.\"deletedAt\", u.\"wechatOpenId\", u.\"wechatUnionId\","
            + " u.\"alipayOpenId\", r.name as \"roleName\", s.name as \"statusName\", f.path as \"photoPath\","
            + " f.\"ownerUserId\" as \"photoOwnerUserId\", f.visibility as \"photoVisibility\","
            + " f.\"mimeType\" as \"photoMimeType\", f.size as \"photoSize\""
            + " from \"user\" u left join role r on r.id = u.\"roleId\" left join status s on s.id = u.\"statusId\""
            + " left join file f on f.id = u.\"photoId\""
            + " where u.\"deletedAt\" is null";

    @Select(SELECT + " and u.id = #{id}")
    @Nullable
    Row findById(int id);

    @Select(SELECT + " and u.email = #{email}")
    @Nullable
    Row findByEmail(String email);

    @Select(SELECT + " and u.phone = #{phone}")
    @Nullable
    Row findByPhone(String phone);

    @Select(SELECT + " and u.\"wechatOpenId\" = #{openId}")
    @Nullable
    Row findByWechatOpenId(String openId);

    @Select(SELECT + " and u.\"alipayOpenId\" = #{openId}")
    @Nullable
    Row findByAlipayOpenId(String openId);

    @Update("update \"user\" set phone = #{phone}, \"updatedAt\" = now()"
            + " where id = #{id} and phone is null and \"deletedAt\" is null")
    int bindPhoneIfEmpty(@Param("id") int id, @Param("phone") String phone);

    @Update(
            "<script>update \"user\" set \"wechatOpenId\" = #{openId}"
                    + "<if test='unionId != null and unionId != \"\"'>, \"wechatUnionId\" = #{unionId}</if>"
                    + ", \"updatedAt\" = now() where id = #{id} and \"wechatOpenId\" is null and \"deletedAt\" is null</script>")
    int bindWechatIfEmpty(
            @Param("id") int id, @Param("openId") String openId, @Param("unionId") @Nullable String unionId);

    @Select("<script>" + SELECT
            + "<if test='roleIds != null and !roleIds.isEmpty()'> and u.\"roleId\" in"
            + "<foreach collection='roleIds' item='r' open='(' separator=',' close=')'>#{r}</foreach></if>"
            + " order by <foreach collection='orders' item='o' separator=','>u.\"${o.column}\""
            + "<if test='o.descending'> desc</if></foreach>"
            + " offset #{offset} limit #{limit}</script>")
    List<Row> findMany(
            @Param("roleIds") @Nullable List<Integer> roleIds,
            @Param("orders") List<Order> orders,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("<script>insert into \"user\" (<foreach collection='values' index='k' separator=','>\"${k}\"</foreach>)"
            + " values (<foreach collection='values' index='k' item='v' separator=','>"
            + "<choose><when test='k == \"photoId\"'>#{v}::uuid</when><otherwise>#{v}</otherwise></choose>"
            + "</foreach>) returning id</script>")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int insertReturningId(@Param("values") Map<String, @Nullable Object> values);

    @Update("<script>update \"user\" set <foreach collection='changes' index='k' item='v' separator=','>"
            + "\"${k}\" = <choose><when test='k == \"photoId\"'>#{v}::uuid</when><otherwise>#{v}</otherwise></choose>"
            + "</foreach>, \"updatedAt\" = now() where id = #{id} and \"deletedAt\" is null</script>")
    int update(@Param("id") int id, @Param("changes") Map<String, @Nullable Object> changes);

    @Update("<script>update \"user\" set password = #{next}, \"updatedAt\" = now()"
            + " where id = #{id} and \"deletedAt\" is null and"
            + "<choose><when test='current == null'> password is null</when>"
            + "<otherwise> password = #{current}</otherwise></choose></script>")
    int replacePassword(@Param("id") int id, @Param("current") @Nullable String current, @Param("next") String next);

    @Update("update \"user\" set password = #{next} where id = #{id} and password = #{current}")
    int upgradePasswordHash(@Param("id") int id, @Param("current") String current, @Param("next") String next);

    @Update("update \"user\" set email = null, phone = null, \"wechatOpenId\" = null, \"wechatUnionId\" = null,"
            + " \"alipayOpenId\" = null, \"updatedAt\" = now() where id = #{id}")
    int clearUniqueFields(int id);

    @Update(
            "update \"user\" set \"deletedAt\" = now(), \"updatedAt\" = now() where id = #{id} and \"deletedAt\" is null")
    int softDelete(int id);

    @Select("select exists(select 1 from \"user\" where id = #{id} and \"deletedAt\" is not null)")
    boolean existsDeleted(int id);

    @Update("update \"user\" set \"photoId\" = null, \"updatedAt\" = now() where id = #{id}")
    int detachPhoto(int id);
}
