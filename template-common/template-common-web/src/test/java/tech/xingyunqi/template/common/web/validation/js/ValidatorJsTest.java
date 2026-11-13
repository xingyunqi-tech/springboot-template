package tech.xingyunqi.template.common.web.validation.js;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValidatorJsTest {

    private static final Map<String, BiFunction<Object, Object, Boolean>> FUNCTIONS = Map.of(
            "isEmail", ValidatorJs::isEmail,
            "isURL", ValidatorJs::isURL,
            "isUUID", ValidatorJs::isUUID,
            "isISO8601", ValidatorJs::isISO8601,
            "isLength", ValidatorJs::isLength,
            "isByteLength", ValidatorJs::isByteLength,
            "isFQDN", ValidatorJs::isFQDN,
            "isIP", ValidatorJs::isIP);

    private static final String GRIN = "😀";
    private static final String FAMILY = "👨‍👩‍👧";
    private static final String UUID_V4 = "A987FBC9-4BED-4078-8F07-9141BA07C9F3";
    private static final String UUID_V3 = "A987FBC9-4BED-3078-CF07-9141BA07C9F3";

    private static Arguments c(String name, String value, Object options, boolean expected) {
        return Arguments.of(name, value, options, expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                c("isEmail", "foo@bar.com", null, true),
                c("isEmail", "foo+bar@bar.com", null, true),
                c("isEmail", "hans.m端ller@test.com", null, true),
                c("isEmail", "\"foobar\"@example.com", null, true),
                c("isEmail", "\"foo\\@bar\"@example.com", null, true),
                c("isEmail", "\"@x.com", null, true),
                c("isEmail", "invalidemail@", null, false),
                c("isEmail", "@invalid.com", null, false),
                c("isEmail", "foo@bar.com.", null, false),
                c("isEmail", "foo@_bar.com", null, false),
                c("isEmail", "test@email..com", null, false),
                c("isEmail", "a@b.com\n", null, false),
                c("isEmail", "test1@invalid.co m", null, false),
                c("isEmail", "somename@ｇｍａｉｌ.com", null, false),
                c("isEmail", "a@[127.0.0.1]", null, false),
                c("isEmail", "a@127.0.0.1", null, false),
                c("isEmail", GRIN + "@x.com", null, false),
                c("isEmail", "a@" + GRIN + ".com", null, true),
                c("isEmail", "a@x.c" + GRIN, null, false),
                c("isEmail", "a@例子.中国", null, true),
                c("isEmail", "a@xn--fiqs8s.xn--fiqz9s", null, true),
                c("isEmail", "K@x.com", null, true),
                c("isEmail", "a".repeat(64) + "@x.com", null, true),
                c("isEmail", "a".repeat(65) + "@x.com", null, false),
                c("isEmail", "a@" + "b".repeat(63) + ".com", null, true),
                c("isEmail", "a@" + "b".repeat(64) + ".com", null, false),
                c("isEmail", "Some Name <foo@bar.com>", null, false),
                c("isEmail", "Some Name <foo@bar.com>", Map.of("allow_display_name", true), true),
                c("isEmail", "Some.Name <foo@bar.com>", Map.of("allow_display_name", true), false),
                c("isEmail", "\"Some.Name\" <foo@bar.com>", Map.of("allow_display_name", true), true),
                c("isEmail", "foo@bar.com", Map.of("require_display_name", true), false),
                c("isEmail", "a@[127.0.0.1]", Map.of("allow_ip_domain", true), true),
                c("isEmail", "üser@x.com", Map.of("allow_utf8_local_part", false), false),
                c("isEmail", "a@localhost", Map.of("require_tld", false), true),
                c("isEmail", "te.st@gmail.com", Map.of("domain_specific_validation", true), false),
                c("isEmail", "test12@gmail.com", Map.of("domain_specific_validation", true), true),
                c("isEmail", "abc@x.com", Map.of("blacklisted_chars", "b"), false),
                c("isEmail", "a@gmail.com", Map.of("host_blacklist", List.of("gmail.com")), false),
                c("isEmail", "a@x.com", Map.of("host_whitelist", List.of("gmail.com")), false),
                c("isURL", "foobar.com", null, true),
                c("isURL", "http://www.foobar.com/", null, true),
                c("isURL", "http://127.0.0.1/", null, true),
                c("isURL", "http://[::1]:80", null, true),
                c("isURL", "http://[1080::8:800:200C:417A]/foo", null, true),
                c("isURL", "http://user:pass@www.foobar.com/", null, true),
                c("isURL", "http://:@foobar.com", null, false),
                c("isURL", "http://@foobar.com", null, false),
                c("isURL", "http://www.foobar.com:0/", null, false),
                c("isURL", "http://example.com:0080", null, true),
                c("isURL", "http://www.foobar.com:70000/", null, false),
                c("isURL", "http://foobar/ lol/", null, false),
                c("isURL", "http://exa mple.com", null, false),
                c("isURL", "mailto:foo@bar.com", null, false),
                c("isURL", "javascript:alert(1)", null, false),
                c("isURL", "javascript:alert(1)@example.com", null, false),
                c("isURL", "user:pass@example.com", null, true),
                c("isURL", "localhost:3000", null, false),
                c("isURL", "//foobar.com", null, false),
                c("isURL", "//foobar.com", Map.of("allow_protocol_relative_urls", true), true),
                c("isURL", "rtmp://foobar.com", null, false),
                c("isURL", "rtmp://foobar.com", Map.of("protocols", List.of("rtmp")), true),
                c("isURL", "foobar.com", Map.of("require_protocol", true), false),
                c("isURL", "http://localhost/", Map.of("require_tld", false), true),
                c("isURL", "http://www.foo_bar.com/", null, false),
                c("isURL", "http://www.foo_bar.com/", Map.of("allow_underscores", true), true),
                c("isURL", "http://кулік.укр", null, true),
                c("isURL", "http://" + GRIN + ".com", null, true),
                c("isURL", "http://a.com/?q=1", Map.of("allow_query_components", false), false),
                c("isURL", "http://a.com/#f", Map.of("allow_fragments", false), false),
                c("isURL", "http://example.com", Map.of("host_whitelist", List.of("example.com")), true),
                c("isURL", "http://[::1]", Map.of("host_whitelist", List.of()), false),
                c("isURL", "http://" + "a".repeat(2080) + ".com", null, false),
                c("isUUID", UUID_V3, null, false),
                c("isUUID", UUID_V4, null, true),
                c("isUUID", UUID_V4, 4, true),
                c("isUUID", UUID_V4, "3", false),
                c("isUUID", UUID_V3, "loose", true),
                c("isUUID", "00000000-0000-0000-0000-" + "0".repeat(12), null, true),
                c("isUUID", "00000000-0000-0000-0000-" + "0".repeat(12), "nil", true),
                c("isUUID", "FFFFFFFF-FFFF-FFFF-FFFF-" + "F".repeat(12), "max", true),
                c("isUUID", "a987fbc9-4bed-7078-8f07-9141ba07c9f3", "7", true),
                c("isUUID", UUID_V4 + "\n", null, false),
                c("isUUID", UUID_V4, "v4", false),
                c("isUUID", UUID_V4, 9, false),
                c("isISO8601", "2009-05-19", null, true),
                c("isISO8601", "20090519", null, true),
                c("isISO8601", "2009-123", null, true),
                c("isISO8601", "2009-W21-2", null, true),
                c("isISO8601", "2009-05-19 14:39:22-06:00", null, true),
                c("isISO8601", "2010-02-18T16:23:48,444", null, true),
                c("isISO8601", "2007-04-05T24:00", null, true),
                c("isISO8601", "2009-05-19T240030", null, true),
                c("isISO8601", "2009-05-19\n", null, true),
                c("isISO8601", "2009-05-19　14:39", null, true),
                c("isISO8601", "2009-05-19 14:39", Map.of("strictSeparator", true), false),
                c("isISO8601", "2009-05-19T14:39", Map.of("strictSeparator", true), true),
                c("isISO8601", "2009-0519", null, false),
                c("isISO8601", "2009-02-29", null, true),
                c("isISO8601", "2009-02-29", Map.of("strict", true), false),
                c("isISO8601", "2008-02-29", Map.of("strict", true), true),
                c("isISO8601", "2009-366", Map.of("strict", true), false),
                c("isISO8601", "2020-366", Map.of("strict", true), true),
                c("isISO8601", "0099-01-01", Map.of("strict", true), false),
                c("isISO8601", "2021-366\t10", Map.of("strict", true), false),
                c("isISO8601", "٢٠٠٩-05-19", null, false),
                c("isLength", "abc", Map.of("min", 3, "max", 3), true),
                c("isLength", GRIN, Map.of("max", 1), true),
                c("isLength", "❤️", Map.of("max", 1), true),
                c("isLength", "️️", Map.of("max", 1), false),
                c("isLength", FAMILY, Map.of("max", 4), false),
                c("isLength", FAMILY, Map.of("max", 5), true),
                c("isLength", "\ud800", Map.of("min", 1, "max", 1), true),
                c("isLength", "abc", Map.of("min", 1, "max", 6, "discreteLengths", List.of(1, 3, 5)), true),
                c("isLength", "ab", Map.of("min", 1, "max", 6, "discreteLengths", List.of(1, 3, 5)), false),
                c("isLength", "", null, true),
                c("isByteLength", "abc", Map.of("min", 3, "max", 3), true),
                c("isByteLength", "中", Map.of("max", 2), false),
                c("isByteLength", "中", Map.of("min", 3, "max", 3), true),
                c("isByteLength", GRIN, Map.of("min", 4, "max", 4), true),
                c("isByteLength", "abc", null, false),
                c("isFQDN", "example.com", null, true),
                c("isFQDN", "localhost", null, false),
                c("isFQDN", "localhost", Map.of("require_tld", false), true),
                c("isFQDN", "example.com.", Map.of("allow_trailing_dot", true), true),
                c("isFQDN", "*.example.com", Map.of("allow_wildcard", true), true),
                c("isFQDN", "example.123", null, false),
                c("isFQDN", "example.123", Map.of("allow_numeric_tld", true), true),
                c("isFQDN", "-a.com", null, false),
                c("isIP", "127.0.0.1", null, true),
                c("isIP", "256.0.0.1", null, false),
                c("isIP", "::1", null, true),
                c("isIP", "fe80::1234%eth0", null, true),
                c("isIP", "::ffff:127.0.0.1", Map.of("version", 6), true),
                c("isIP", "127.0.0.1", 6, false),
                c("isIP", "127.0.0.1\n", null, false));
    }

    @ParameterizedTest(name = "{0}({1}, {2}) = {3}")
    @MethodSource("cases")
    void matchesValidatorJs(String name, String value, Object options, boolean expected) {
        assertThat(FUNCTIONS.get(name).apply(value, options)).isEqualTo(expected);
    }

    static Stream<Arguments> throwingCases() {
        return Stream.of(
                Arguments.of("isEmail", "a\ud800@x.com", null),
                Arguments.of("isUUID", UUID_V4, "toString"),
                Arguments.of("isByteLength", "\ud800", Map.of()));
    }

    @ParameterizedTest(name = "{0}({1}, {2}) throws")
    @MethodSource("throwingCases")
    void throwsWhereValidatorJsThrows(String name, String value, Object options) {
        assertThatThrownBy(() -> FUNCTIONS.get(name).apply(value, options)).isInstanceOf(JsError.class);
    }

    static Stream<String> functionNames() {
        return FUNCTIONS.keySet().stream().sorted();
    }

    @ParameterizedTest
    @MethodSource("functionNames")
    void rejectsNonStringInput(String name) {
        assertThatThrownBy(() -> FUNCTIONS.get(name).apply(123, null)).isInstanceOf(JsError.class);
    }
}
