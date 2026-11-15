package tech.xingyunqi.template.infrastructure.files;

import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.xingyunqi.template.support.TestMinio;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest(properties = {"REDIS_ENABLED=true", "app.throttle.enabled=true", "app.trust-proxy=1"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FilesS3ThrottleKeysIT extends ThrottleKeysTestBase {

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        TestMinio.register(registry, "s3", "files-s3-throttle-it");
    }

    @Override
    Map<String, String> expectedHandlers() {
        return Map.of(
                "POST /api/v1/files/upload", "FilesS3Controller-uploadFile",
                "GET /api/v1/files/private/00000000-0000-0000-0000-000000000000", "FilesController-readPrivate");
    }
}
