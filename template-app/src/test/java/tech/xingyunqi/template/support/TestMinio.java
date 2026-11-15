package tech.xingyunqi.template.support;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/** 测试共用的 MinIO 容器；每个测试类使用独立的桶。 */
public final class TestMinio {

    public static final String IMAGE = "chainguard/minio:latest";
    public static final String REGION = "us-east-1";

    private static @Nullable MinIOContainer container;
    private static @Nullable S3Client client;

    private TestMinio() {}

    public static synchronized MinIOContainer container() {
        if (container == null) {
            MinIOContainer started =
                    new MinIOContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("minio/minio"));
            started.start();
            container = started;
        }
        return container;
    }

    public static String endpoint() {
        MinIOContainer minio = container();
        return "http://127.0.0.1:" + minio.getMappedPort(9000);
    }

    public static synchronized S3Client client() {
        if (client == null) {
            MinIOContainer minio = container();
            client = S3Client.builder()
                    .region(Region.of(REGION))
                    .endpointOverride(URI.create(endpoint()))
                    .forcePathStyle(true)
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                    .build();
        }
        return client;
    }

    public static void createBucket(String bucket) {
        client().createBucket(request -> request.bucket(bucket));
    }

    /** {@code FILE_DRIVER} 与指向该桶的 S3 连接参数。 */
    public static void register(DynamicPropertyRegistry registry, String driver, String bucket) {
        MinIOContainer minio = container();
        createBucket(bucket);
        registry.add("app.file.driver", () -> driver);
        registry.add("app.file.access-key-id", minio::getUserName);
        registry.add("app.file.secret-access-key", minio::getPassword);
        registry.add("app.file.bucket", () -> bucket);
        registry.add("app.file.region", () -> REGION);
        registry.add("app.file.endpoint", TestMinio::endpoint);
    }

    public static @Nullable byte[] object(String bucket, String key) {
        try {
            return client().getObject(request -> request.bucket(bucket).key(key), ResponseTransformer.toBytes())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    public static Set<String> keys(String bucket) {
        return client().listObjectsV2(request -> request.bucket(bucket)).contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toSet());
    }
}
