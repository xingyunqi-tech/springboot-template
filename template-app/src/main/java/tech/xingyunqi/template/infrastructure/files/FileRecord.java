package tech.xingyunqi.template.infrastructure.files;

import org.jspecify.annotations.Nullable;

/** {@code file} 表的一行；{@code path} 为库中存储值（local 驱动为 API 路径，s3 为对象键）。 */
public record FileRecord(
        String id,
        String path,
        @Nullable Integer ownerUserId,
        String visibility,
        @Nullable String mimeType,
        @Nullable Integer size) {

    public static final String PUBLIC = "PUBLIC";
    public static final String PRIVATE = "PRIVATE";

    public boolean accessibleBy(@Nullable Integer userId) {
        return !PRIVATE.equals(visibility) || (userId != null && userId.equals(ownerUserId));
    }
}
