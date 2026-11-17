package tech.xingyunqi.template.modules.favorites.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record Favorite(
        int id,
        int userId,
        String targetType,
        String targetId,
        @Nullable String title,
        @Nullable String image,
        @Nullable String extra,
        @Nullable String subjectId,
        Instant createdAt) {}
