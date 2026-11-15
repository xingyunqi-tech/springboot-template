package tech.xingyunqi.template.infrastructure.throttler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ThrottlerPropertiesTest {

    @ParameterizedTest(name = "THROTTLE_ENABLED=\"{0}\" -> {1}")
    @CsvSource(
            delimiter = '|',
            emptyValue = "",
            value = {"true|true", "''|true", "FALSE|true", "0|true", "false|false"})
    void onlyLiteralFalseDisables(String value, boolean enabled) {
        assertThat(new ThrottlerProperties(value, 60, 100).isEnabled()).isEqualTo(enabled);
    }
}
