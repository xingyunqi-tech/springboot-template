package tech.xingyunqi.template.common.web.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Tag("contract")
class ValidationCasesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ClassValidatorEngine ENGINE = loadEngine();

    private static ClassValidatorEngine loadEngine() {
        try (InputStream in = Files.newInputStream(RepoFiles.VALIDATION_JSON)) {
            return ClassValidatorEngine.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Arguments> cases() throws IOException {
        JsonNode cases = MAPPER.readTree(RepoFiles.VALIDATION_CASES.toFile());
        return cases.values().stream().map(c -> Arguments.of(c.required("id").stringValue(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesNestjsValidationPipe(String id, JsonNode testCase) {
        JsonNode expected = testCase.required("expected");
        JsonNode actual = run(testCase);
        assertThat(sameJson(expected, actual))
                .as("%s%n expected: %s%n   actual: %s", id, expected, actual)
                .isTrue();
    }

    private static JsonNode run(JsonNode testCase) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        try {
            JsonNode value =
                    ENGINE.run(testCase.required("dto").stringValue(), testCase.required("payload"), ParamType.BODY);
            result.put("ok", true);
            result.set("value", value);
        } catch (ValidationFailedException e) {
            result.put("ok", false);
            result.put("status", e.status());
            result.set("errors", e.errors());
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("status", 500);
            result.putNull("errors");
        }
        return result;
    }

    static boolean sameJson(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() || actual.isNumber()) {
            return expected.isNumber()
                    && actual.isNumber()
                    && expected.isIntegralNumber() == actual.isIntegralNumber()
                    && expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return false;
        }
        if (expected.isObject()) {
            if (!Set.copyOf(expected.propertyNames()).equals(Set.copyOf(actual.propertyNames()))) {
                return false;
            }
            for (Map.Entry<String, JsonNode> entry : expected.properties()) {
                if (!sameJson(entry.getValue(), actual.required(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            Iterator<JsonNode> left = expected.values().iterator();
            Iterator<JsonNode> right = actual.values().iterator();
            while (left.hasNext()) {
                if (!sameJson(left.next(), right.next())) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }
}
