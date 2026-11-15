package tech.xingyunqi.template.infrastructure.files;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 各用途的大小上限；multipart 单文件上限取其最大值。 */
@Component
@EnableConfigurationProperties(FilesProperties.class)
public class UploadLimits {

    private final Map<FilePurpose, Long> maxSizes;
    private final long maxFileSize;

    public UploadLimits(FilesProperties properties) {
        this.maxSizes = properties.maxSizes();
        this.maxFileSize = Collections.max(maxSizes.values());
    }

    public long maxSize(FilePurpose purpose) {
        return Objects.requireNonNull(maxSizes.get(purpose));
    }

    public long maxFileSize() {
        return maxFileSize;
    }
}
