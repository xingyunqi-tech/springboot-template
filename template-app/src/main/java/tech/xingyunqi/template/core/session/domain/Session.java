package tech.xingyunqi.template.core.session.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** 登录会话；时间为 UTC。 */
public record Session(
        int id,
        @Nullable Integer userId,
        String hash,
        @Nullable String userAgent,
        @Nullable String ip,
        Instant createdAt,
        @Nullable Instant lastActiveAt) {}
