package tech.xingyunqi.template.core.users.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** 用户及已加载的角色、状态、头像；时间为 UTC。 */
public record User(
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
        @Nullable Photo photo,
        @Nullable Integer roleId,
        @Nullable NamedRef role,
        @Nullable Integer statusId,
        @Nullable NamedRef status,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt,
        @Nullable String wechatOpenId,
        @Nullable String wechatUnionId,
        @Nullable String alipayOpenId) {}
