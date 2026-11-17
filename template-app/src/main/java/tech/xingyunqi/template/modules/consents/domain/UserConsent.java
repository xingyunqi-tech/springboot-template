package tech.xingyunqi.template.modules.consents.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record UserConsent(
        String id,
        int userId,
        ConsentType type,
        String version,
        Instant agreedAt,
        @Nullable String ip) {}
