package tech.xingyunqi.template.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.auth.AuthApi.JOHN;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.PNG;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.file;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.form;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.stored;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.upload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest(
        properties = {
            "FILE_IMAGE_MAX_SIZE=10",
            "FILE_PRIVATE_IMAGE_MAX_SIZE=20",
            "FILE_CONTENT_IMAGE_MAX_SIZE=10",
            "FILE_AUDIO_MAX_SIZE=10",
            "FILE_IMPORT_MAX_SIZE=10",
            "FILE_EXPORT_MAX_SIZE=10"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FileSizeLimitsIT {

    static final Path ROOT = FileUploadBodies.tempRoot("files-limits-it");
    static final String TOO_LARGE_EN =
            "{\"code\":413,\"message\":\"PAYLOAD_TOO_LARGE\",\"data\":{\"file\":\"File is too large\"}}";

    @DynamicPropertySource
    static void localRoot(DynamicPropertyRegistry registry) {
        registry.add("app.file.local-root", ROOT::toString);
    }

    @Autowired
    MockMvc mvc;

    String john;

    @BeforeEach
    void setUp() throws Exception {
        FileUploadBodies.clear(ROOT);
        john = new AuthApi(mvc).loginData(JOHN).path("token").asString();
    }

    String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Content-Length 超过用途上限 + 16KiB 时读取前 413（message 不翻译、data 翻译）")
    void contentLengthOverLimit() throws Exception {
        String response = body(upload(null, john, form(file("a.png", new byte[20 * 1024])))
                .header("Accept-Language", "zh"));

        assertThat(response).isEqualTo("{\"code\":413,\"message\":\"PAYLOAD_TOO_LARGE\",\"data\":{\"file\":\"文件过大\"}}");
        assertThat(stored(ROOT)).isEmpty();
    }

    @Test
    @DisplayName("文件超过用途上限时 413 并删除已写入的文件")
    void fileOverPurposeLimit() throws Exception {
        assertThat(body(upload(null, john, form(file("a.png", PNG))))).isEqualTo(TOO_LARGE_EN);
        assertThat(stored(ROOT)).isEmpty();
    }
}
