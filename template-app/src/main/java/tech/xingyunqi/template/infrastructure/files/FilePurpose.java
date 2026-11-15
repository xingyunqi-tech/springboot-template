package tech.xingyunqi.template.infrastructure.files;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** 上传用途规则（nestjs {@code file-purposes.ts}）：允许的扩展名与 mime、可见性、所需权限；内容素材无归属用户。 */
@SuppressWarnings("ImmutableEnumChecker")
public enum FilePurpose {
    IMAGE("image", Images.ALL, FileRecord.PUBLIC, null, false),
    PRIVATE_IMAGE("private-image", Images.PRIVATE, FileRecord.PRIVATE, null, false),
    CONTENT_IMAGE("content-image", Images.ALL, FileRecord.PUBLIC, Images.CONTENT_MANAGE, true),
    AUDIO("audio", Map.of("mp3", "audio/mpeg", "m4a", "audio/mp4"), FileRecord.PUBLIC, Images.CONTENT_MANAGE, true),
    IMPORT(
            "import",
            Map.of(
                    "zip", "application/zip",
                    "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "json", "application/json"),
            FileRecord.PRIVATE,
            Images.CONTENT_MANAGE,
            false),
    EXPORT("export", Map.of("pdf", "application/pdf"), FileRecord.PRIVATE, Images.CONTENT_MANAGE, false);

    private final String value;
    private final Map<String, String> mimeTypes;
    private final String visibility;
    private final @Nullable String permission;
    private final boolean contentAsset;

    FilePurpose(
            String value,
            Map<String, String> mimeTypes,
            String visibility,
            @Nullable String permission,
            boolean contentAsset) {
        this.value = value;
        this.mimeTypes = mimeTypes;
        this.visibility = visibility;
        this.permission = permission;
        this.contentAsset = contentAsset;
    }

    public String value() {
        return value;
    }

    public String visibility() {
        return visibility;
    }

    public @Nullable String permission() {
        return permission;
    }

    /** 未传或空串按 {@link #IMAGE} 处理；数组或无法识别的值为 null。 */
    public static @Nullable FilePurpose parse(@Nullable Object value) {
        if (value == null || "".equals(value)) {
            return IMAGE;
        }
        for (FilePurpose purpose : values()) {
            if (purpose.value.equals(value)) {
                return purpose;
            }
        }
        return null;
    }

    /** 最后一个 {@code .} 之后的部分，转小写；没有 {@code .} 时为空串。 */
    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public @Nullable String mimeType(String fileName) {
        return mimeTypes.get(extension(fileName));
    }

    public @Nullable Integer owner(int uploaderId) {
        return contentAsset ? null : uploaderId;
    }

    private static final class Images {

        static final String CONTENT_MANAGE = "content:manage";
        static final Map<String, String> ALL = Map.of(
                "jpg", "image/jpeg",
                "jpeg", "image/jpeg",
                "png", "image/png",
                "gif", "image/gif",
                "webp", "image/webp");
        static final Map<String, String> PRIVATE = Map.of(
                "jpg", "image/jpeg",
                "jpeg", "image/jpeg",
                "png", "image/png",
                "webp", "image/webp");

        private Images() {}
    }
}
