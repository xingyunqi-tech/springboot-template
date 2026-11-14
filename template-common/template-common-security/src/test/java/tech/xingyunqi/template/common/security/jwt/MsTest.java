package tech.xingyunqi.template.common.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MsTest {

    @ParameterizedTest
    @CsvSource({
        "15m, 900000",
        "3650d, 315360000000",
        "30m, 1800000",
        "1d, 86400000",
        "100, 100",
        "1.5h, 5400000",
        "-1s, -1000",
        "2 Weeks, 1209600000",
        "1y, 31557600000",
        "10 msecs, 10",
        "2 mins, 120000",
        ".5s, 500"
    })
    @DisplayName("按 npm ms 2.x 规则解析时长")
    void parses(String value, double expected) {
        assertThat(Ms.parse(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1x", "1 day ago"})
    @DisplayName("无法解析的时长抛 IllegalArgumentException")
    void rejects(String value) {
        assertThatThrownBy(() -> Ms.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {101})
    @DisplayName("超过 100 个字符的时长视为无效")
    void rejectsLongInput(int length) {
        assertThatThrownBy(() -> Ms.parse("1".repeat(length))).isInstanceOf(IllegalArgumentException.class);
    }
}
