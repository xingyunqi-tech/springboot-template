package tech.xingyunqi.template.modules.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class ConfigRepositoryImplTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"b\":1,\"a\":{\"y\":2,\"x\":1}} | {\"a\":{\"x\":1,\"y\":2},\"b\":1} | true",
                "{\"a\":1} | {\"a\":1.0} | true",
                "{\"a\":1} | {\"a\":\"1\"} | false",
                "{\"a\":1} | {\"a\":1,\"b\":null} | false",
                "{\"a\":null} | {\"b\":null} | false",
                "[1,2] | [1,2] | true",
                "[1,2] | [2,1] | false",
                "[1] | [1,1] | false",
                "{\"0\":1} | [1] | false",
                "true | true | true",
                "null | null | true"
            })
    void deepEqualsComparesLikeTypeorm(String left, String right, boolean expected) {
        assertThat(ConfigRepositoryImpl.deepEquals(JSON.readTree(left), JSON.readTree(right)))
                .isEqualTo(expected);
    }
}
