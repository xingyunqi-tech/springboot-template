package tech.xingyunqi.template.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 与 fastapi-temp {@code tests/parity/test_regions_parity.py} 一一对应。 */
class RegionsParityIT {

    static final String URL = "/api/v1/regions";

    private final Parity parity = Parity.fromSystemProperties();

    static Stream<Arguments> cases() {
        List<String> paths = List.of(
                "/provinces",
                "/cities/110000000000",
                "/cities/440000000000",
                "/cities/999999999999",
                "/cities/11",
                "/cities/abcdefghijkl",
                "/districts/110100000000",
                "/districts/440300000000",
                "/districts/1101000000000",
                "/code/110101000000",
                "/code/440305000000",
                "/code/999999999998",
                "/code/x",
                "/tree",
                "",
                "?keyword=%E5%8C%97%E4%BA%AC",
                "?keyword=%E5%8D%97",
                "?keyword=%25",
                "?keyword=_",
                "?keyword=" + "x".repeat(51),
                "?parentCode=110000000000",
                "?parentCode=",
                "?parentCode=1",
                "?level=1",
                "?level=3&parentCode=110100000000",
                "?level=4",
                "?level=abc",
                "?level=1.5",
                "?level=",
                "?level=0",
                "?keyword=",
                "?keyword=%E5%8C%97&level=1&parentCode=110000000000");
        List<Arguments> cases = new ArrayList<>();
        for (int repeat : List.of(1, 2)) {
            for (String path : paths) {
                cases.add(Arguments.of(path, repeat));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void regions(String path, int repeat) {
        ConfigParityIT.assertSame(
                parity.request("GET", URL + path, Map.of("Accept-Language", "zh")),
                Set.of("createdAt", "updatedAt"),
                true);
    }
}
