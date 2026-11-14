package tech.xingyunqi.template.core.users.domain;

import org.jspecify.annotations.Nullable;

/** 新建用户时响应中的关联只带调用方传入的 id（同 nestjs {@code save()} 返回值），未传时整个键缺省。 */
public record CreatedRelations(
        @Nullable Integer roleId,
        @Nullable Integer statusId,
        @Nullable Photo photo) {}
