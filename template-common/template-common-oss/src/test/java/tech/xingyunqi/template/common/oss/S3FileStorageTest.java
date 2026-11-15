package tech.xingyunqi.template.common.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class S3FileStorageTest {

    static FileStorageProperties properties(@Nullable String endpoint, @Nullable String forcePathStyle) {
        return new FileStorageProperties("s3", null, "key", "secret", "bucket", "us-east-1", endpoint, forcePathStyle);
    }

    static String getUrl(FileStorageProperties properties) {
        try (S3FileStorage storage = new S3FileStorage(FileDriver.S3, properties)) {
            return storage.presignedGetUrl("a.png");
        }
    }

    @Test
    @DisplayName("未设置 endpoint 时为 AWS 虚拟主机式地址，空串视为未设置")
    void awsVirtualHost() {
        assertThat(getUrl(properties(null, null))).startsWith("https://bucket.s3.amazonaws.com/a.png?");
        assertThat(getUrl(properties("", "true"))).startsWith("https://bucket.s3.amazonaws.com/a.png?");
    }

    @Test
    @DisplayName("设置 endpoint 时按 AWS_S3_FORCE_PATH_STYLE 选择路径式或虚拟主机式")
    void endpointAddressing() {
        assertThat(getUrl(properties("http://minio:9000", null))).startsWith("http://minio:9000/bucket/a.png?");
        assertThat(getUrl(properties("https://oss-cn-hangzhou.aliyuncs.com", "false")))
                .startsWith("https://bucket.oss-cn-hangzhou.aliyuncs.com/a.png?");
    }

    @Test
    @DisplayName("GET / PUT 预签名有效期 3600 秒，PUT 签入 content-length")
    void signedUrls() {
        try (S3FileStorage storage =
                new S3FileStorage(FileDriver.S3_PRESIGNED, properties("http://minio:9000", null))) {
            assertThat(storage.presignedGetUrl("a.png"))
                    .contains("X-Amz-Expires=3600", "X-Amz-Algorithm=AWS4-HMAC-SHA256");
            assertThat(storage.presignedPutUrl("a.png", 11))
                    .contains("X-Amz-Expires=3600", "X-Amz-SignedHeaders=content-length%3Bhost");
        }
    }

    @Test
    @DisplayName("S3 驱动缺少连接参数、FILE_DRIVER 不合法时启动失败")
    void invalidConfiguration() {
        FileStorageProperties missing = new FileStorageProperties("s3", null, null, "secret", "b", "r", null, null);
        assertThatThrownBy(() -> new S3FileStorage(FileDriver.S3, missing)).hasMessageContaining("ACCESS_KEY_ID");
        assertThatThrownBy(() -> FileDriver.parse("ftp")).hasMessageContaining("FILE_DRIVER");
        assertThat(new FileStorageProperties(null, null, null, null, null, null, null, null).fileDriver())
                .isEqualTo(FileDriver.LOCAL);
    }
}
