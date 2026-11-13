package tech.xingyunqi.template.common.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApiPrefixHandlerMappingTest {

    @ParameterizedTest
    @CsvSource({
        "api, /api/v1/users, /api/v1/users",
        "v2api, /api/v1/users, /v2api/v1/users",
        "/custom/, /api/health, /custom/health",
        "/, /api/health, /health",
        "/, /api, /",
        "custom, /, /",
        "custom, /docs-json, /docs-json",
        "custom, /apis, /apis"
    })
    @DisplayName("只替换生成路径中的 /api 前缀段")
    void rewritesApiPrefix(String prefix, String path, String expected) {
        assertThat(new ApiPrefixHandlerMapping(prefix).rewrite(path)).isEqualTo(expected);
    }
}
