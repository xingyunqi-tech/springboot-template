package tech.xingyunqi.template.integrations.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tech.xingyunqi.template.common.mail.MailTemplates;
import tech.xingyunqi.template.common.mail.OutgoingMail;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.common.web.i18n.I18n;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MailComposerTest {

    static final String HASH = "eyJhbGciOi.pay-load_x";
    static final I18n I18N = I18n.load("en");

    static MailComposer composer(String frontendDomain) {
        AppProperties app = new AppProperties(
                AppProperties.Environment.TEST,
                "Tom's <App> & Co",
                "api",
                frontendDomain,
                "http://localhost",
                "en",
                null,
                0);
        return new MailComposer(new MailTemplates(), I18N, app);
    }

    static JsonNode fixture(String name) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(dir.resolve("tests/fixtures/nestjs_mail"))) {
            dir = dir.getParent();
        }
        return JsonMapper.builder().build().readTree(dir.resolve("tests/fixtures/nestjs_mail/" + name + ".json"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "activation|en|http://localhost:3000, https://b.example|",
                "reset-password|zh|https://app.example.com|1762828800000",
                "confirm-new-email|zh|https://app.example.com|"
            })
    @DisplayName("主题、纯文本与 HTML 和 nestjs 渲染结果逐字节相同")
    void matchesNestjsRendering(String template, String lang, String frontend, String expires) throws Exception {
        Map<String, String> data =
                expires == null ? Map.of("hash", HASH) : Map.of("hash", HASH, "tokenExpires", expires);
        OutgoingMail mail = composer(frontend).compose(template, "a@b.c", data, lang);
        JsonNode expected = fixture(template + "." + lang);
        assertThat(mail.subject()).isEqualTo(expected.get("subject").asString());
        assertThat(mail.text()).isEqualTo(expected.get("text").asString());
        assertThat(mail.html()).isEqualTo(expected.get("html").asString());
    }

    @Test
    @DisplayName("未知语言回落默认语言")
    void unknownLanguageFallsBack() {
        OutgoingMail mail = composer("http://x").compose("activation", "a@b.c", Map.of("hash", "h"), null);
        assertThat(mail.subject()).isEqualTo("Confirm email");
        assertThat(mail.text()).isEqualTo("http://x/confirm-email?hash=h Confirm email");
    }

    @Test
    @DisplayName("查询串按 URLSearchParams 编码")
    void queryUsesFormEncoding() {
        OutgoingMail mail = composer("http://x").compose("activation", "a@b.c", Map.of("hash", "a b~c+d*_"), "en");
        assertThat(mail.text()).isEqualTo("http://x/confirm-email?hash=a+b%7Ec%2Bd*_ Confirm email");
    }
}
