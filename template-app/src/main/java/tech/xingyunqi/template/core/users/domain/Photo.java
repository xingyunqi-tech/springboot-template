package tech.xingyunqi.template.core.users.domain;

import org.jspecify.annotations.Nullable;

/** 头像文件。 */
public record Photo(
        String id,
        String path,
        @Nullable Integer ownerUserId,
        String visibility,
        @Nullable String mimeType,
        @Nullable Integer size) {}
