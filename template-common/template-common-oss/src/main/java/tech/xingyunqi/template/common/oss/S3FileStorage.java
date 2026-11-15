package tech.xingyunqi.template.common.oss;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** S3 兼容存储；设置 {@code AWS_S3_ENDPOINT} 时按 {@code AWS_S3_FORCE_PATH_STYLE} 选择路径式或虚拟主机式寻址。 */
public final class S3FileStorage implements FileStorage, AutoCloseable {

    private static final Duration SIGNED_URL_TTL = Duration.ofSeconds(SIGNED_URL_EXPIRES_IN_SECONDS);

    private final FileDriver driver;
    private final String bucket;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3FileStorage(FileDriver driver, FileStorageProperties properties) {
        this.driver = driver;
        this.bucket = required(properties.bucket(), "AWS_DEFAULT_S3_BUCKET");
        Region region = Region.of(required(properties.region(), "AWS_S3_REGION"));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                required(properties.accessKeyId(), "ACCESS_KEY_ID"),
                required(properties.secretAccessKey(), "SECRET_ACCESS_KEY")));
        String endpoint = properties.endpointOrNull();
        boolean pathStyle = endpoint != null && properties.isForcePathStyle();
        S3Configuration configuration =
                S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();
        var clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        var presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration);
        if (endpoint != null) {
            clientBuilder.endpointOverride(URI.create(endpoint));
            presignerBuilder.endpointOverride(URI.create(endpoint));
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    static String required(@Nullable String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(name + " must be set when FILE_DRIVER is s3 or s3-presigned");
        }
        return value;
    }

    @Override
    public FileDriver driver() {
        return driver;
    }

    @Override
    public Path localRoot() {
        throw new UnsupportedOperationException("S3 driver has no local root");
    }

    @Override
    public Path localFile(String path) {
        throw new UnsupportedOperationException("S3 driver has no local files");
    }

    @Override
    public void put(String key, Path source, String contentType) throws IOException {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromFile(source));
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        try {
            client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(path).build());
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public String presignedGetUrl(String key) {
        return presigner
                .presignGetObject(request -> request.signatureDuration(SIGNED_URL_TTL)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build()))
                .url()
                .toString();
    }

    @Override
    public String presignedPutUrl(String key, long contentLength) {
        return presigner
                .presignPutObject(request -> request.signatureDuration(SIGNED_URL_TTL)
                        .putObjectRequest(PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentLength(contentLength)
                                .build()))
                .url()
                .toString();
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
