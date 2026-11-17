package tech.xingyunqi.template.modules.consents.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** {@code needsConsent}：已配置当前版本且尚未同意。 */
public record ConsentStatus(
        ConsentType type,
        @Nullable String currentVersion,
        @Nullable String agreedVersion,
        @Nullable Instant agreedAt,
        boolean agreed,
        boolean needsConsent) {}
