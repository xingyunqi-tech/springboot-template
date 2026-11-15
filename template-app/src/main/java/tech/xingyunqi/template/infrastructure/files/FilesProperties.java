package tech.xingyunqi.template.infrastructure.files;

import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 各上传用途的大小上限（{@code FILE_*_MAX_SIZE}，字节，≥1 的整数；未设置或空串取默认值）。 */
@ConfigurationProperties("app.file.max-size")
public record FilesProperties(
        @Nullable String image,
        @Nullable String privateImage,
        @Nullable String contentImage,
        @Nullable String audio,
        @Nullable String importFile,
        @Nullable String export) {

    static final long MB = 1024 * 1024;

    public Map<FilePurpose, Long> maxSizes() {
        Map<FilePurpose, Long> sizes = new EnumMap<>(FilePurpose.class);
        sizes.put(FilePurpose.IMAGE, parse(image, "FILE_IMAGE_MAX_SIZE", 5 * MB));
        sizes.put(FilePurpose.PRIVATE_IMAGE, parse(privateImage, "FILE_PRIVATE_IMAGE_MAX_SIZE", 10 * MB));
        sizes.put(FilePurpose.CONTENT_IMAGE, parse(contentImage, "FILE_CONTENT_IMAGE_MAX_SIZE", 5 * MB));
        sizes.put(FilePurpose.AUDIO, parse(audio, "FILE_AUDIO_MAX_SIZE", 20 * MB));
        sizes.put(FilePurpose.IMPORT, parse(importFile, "FILE_IMPORT_MAX_SIZE", 50 * MB));
        sizes.put(FilePurpose.EXPORT, parse(export, "FILE_EXPORT_MAX_SIZE", 20 * MB));
        return sizes;
    }

    static long parse(@Nullable String value, String name, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be an integer >= 1; got \"" + value + "\"", e);
        }
        if (parsed < 1) {
            throw new IllegalStateException(name + " must be an integer >= 1; got \"" + value + "\"");
        }
        return parsed;
    }
}
