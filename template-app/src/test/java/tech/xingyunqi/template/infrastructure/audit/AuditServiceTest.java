package tech.xingyunqi.template.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuditServiceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    @DisplayName("键名包含敏感片段（忽略大小写）或等于 hash 时脱敏，嵌套对象与数组同样处理")
    void redactsSensitiveKeys() {
        JsonNode sanitized = AuditService.sanitize(
                json("{\"firstName\":\"Ann\",\"oldPassword\":\"x\",\"accessToken\":\"t\",\"hash\":\"h\",\"hashed\":1,"
                        + "\"nested\":{\"apiKey\":\"k\",\"list\":[{\"clientSecret\":\"s\",\"ok\":2}]}}"));
        assertThat(sanitized)
                .isEqualTo(json("{\"firstName\":\"Ann\",\"oldPassword\":\"[REDACTED]\",\"accessToken\":\"[REDACTED]\","
                        + "\"hash\":\"[REDACTED]\",\"hashed\":1,\"nested\":{\"apiKey\":\"[REDACTED]\","
                        + "\"list\":[{\"clientSecret\":\"[REDACTED]\",\"ok\":2}]}}"));
    }

    @Test
    @DisplayName("null 为 SQL NULL，顶层数组按下标转为对象，超过 10 层截断")
    void structuralRules() {
        assertThat(AuditService.sanitize(null)).isNull();
        assertThat(AuditService.sanitize(json("null"))).isNull();
        assertThat(AuditService.sanitize(json("[{\"token\":1},2]")))
                .isEqualTo(json("{\"0\":{\"token\":\"[REDACTED]\"},\"1\":2}"));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            deep.append("{\"a\":");
        }
        deep.append("1").append("}".repeat(12));
        String rendered = AuditService.sanitize(json(deep.toString())).toString();
        assertThat(rendered).startsWith("{\"a\":".repeat(11) + "{\"__truncated\":true}");
    }
}
