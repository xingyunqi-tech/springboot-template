package tech.xingyunqi.template.common.oss;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code FILE_DRIVER} 与 S3 连接参数（{@code ACCESS_KEY_ID}、{@code SECRET_ACCESS_KEY}、{@code AWS_DEFAULT_S3_BUCKET}、
 * {@code AWS_S3_REGION}、{@code AWS_S3_ENDPOINT}、{@code AWS_S3_FORCE_PATH_STYLE}）；{@code localRoot} 为 local 驱动的存储目录。
 */
@ConfigurationProperties("app.file")
public record FileStorageProperties(
        @Nullable String driver,
        @Nullable String localRoot,
        @Nullable String accessKeyId,
        @Nullable String secretAccessKey,
        @Nullable String bucket,
        @Nullable String region,
        @Nullable String endpoint,
        @Nullable String forcePathStyle) {

    static final String DEFAULT_LOCAL_ROOT = "files";

    public FileDriver fileDriver() {
        return fileDriver(driver);
    }

    /** 未设置时为 local；空串与其它非法值启动失败。 */
    public static FileDriver fileDriver(@Nullable String driver) {
        return driver == null ? FileDriver.LOCAL : FileDriver.parse(driver);
    }

    public String localRootOrDefault() {
        return localRoot == null || localRoot.isEmpty() ? DEFAULT_LOCAL_ROOT : localRoot;
    }

    /** 空串视为未设置。 */
    public @Nullable String endpointOrNull() {
        return endpoint == null || endpoint.isEmpty() ? null : endpoint;
    }

    public boolean isForcePathStyle() {
        return !"false".equals(forcePathStyle);
    }

    @Override
    public String toString() {
        return "FileStorageProperties[driver=" + driver + ", bucket=" + bucket + ", region=" + region + ", endpoint="
                + endpoint + "]";
    }
}
