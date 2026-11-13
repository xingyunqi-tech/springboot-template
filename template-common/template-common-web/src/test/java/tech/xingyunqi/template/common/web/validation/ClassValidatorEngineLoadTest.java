package tech.xingyunqi.template.common.web.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClassValidatorEngineLoadTest {

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
    }

    private static String singleRule(String rule) {
        return "{'pipe': {'whitelist': true, 'forbidUnknownValues': false, 'errorHttpStatusCode': 422},"
                + " 'classes': {'Dto': {'initialValues': {}, 'properties': {'name': {'designType': 'String',"
                + " 'transforms': [], 'type': null, 'validations': [" + rule + "]}}}}}";
    }

    @Test
    void loadsEveryConstraintAndFunctionInValidationJson() throws IOException {
        ClassValidatorEngine engine;
        try (InputStream in = Files.newInputStream(RepoFiles.VALIDATION_JSON)) {
            engine = ClassValidatorEngine.load(in);
        }
        JsonNode raw = JsonMapper.builder().build().readTree(RepoFiles.VALIDATION_JSON.toFile());
        assertThat(raw.required("classes").propertyNames()).hasSize(136).allMatch(engine::hasDto);
    }

    @Test
    void rejectsUnknownConstraint() {
        String rule = "{'type': 'customValidation', 'key': 'isHexColor', 'constraints': [], 'message': null,"
                + " 'defaultMessage': '$property must be a hexadecimal color', 'each': false}";
        assertThatThrownBy(() -> ClassValidatorEngine.load(json(singleRule(rule))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("isHexColor");
    }

    @Test
    void rejectsUnknownCondition() {
        String rule = "{'type': 'conditionalValidation', 'key': 'conditionalValidation',"
                + " 'constraints': [{'function': null, 'source': '(dto) => dto.other === 1'}],"
                + " 'message': null, 'defaultMessage': null, 'each': false}";
        assertThatThrownBy(() -> ClassValidatorEngine.load(json(singleRule(rule))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dto.other");
    }

    @Test
    void rejectsUnknownTransform() {
        String text = singleRule("")
                .replace(
                        "'transforms': []", "'transforms': [{'function': null, 'source': '({ value }) => value * 2'}]");
        assertThatThrownBy(() -> ClassValidatorEngine.load(json(text)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("value * 2");
    }

    @Test
    void reportsObjectExpectedUnderQueryKey() {
        String rule = "{'type': 'customValidation', 'key': 'isString', 'constraints': [], 'message': null,"
                + " 'defaultMessage': '$property must be a string', 'each': false},"
                + " {'type': 'conditionalValidation', 'key': 'conditionalValidation', 'constraints': [{'function': null,"
                + " 'source': '(_, value) => value !== undefined'}], 'message': null, 'defaultMessage': null,"
                + " 'each': false}";
        ClassValidatorEngine engine = ClassValidatorEngine.load(json(singleRule(rule)));
        JsonNode payload = JsonMapper.builder().build().readTree("[]");
        assertThatThrownBy(() -> engine.run("Dto", payload, ParamType.QUERY))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.errors().required("query").stringValue())
                            .isEqualTo("Validation failed (object is expected)");
                });
    }
}
