package tech.xingyunqi.template.parity;

import static tech.xingyunqi.template.parity.ModuleParity.assertSame;
import static tech.xingyunqi.template.parity.ModuleParity.ids;
import static tech.xingyunqi.template.parity.ModuleParity.itemUrls;
import static tech.xingyunqi.template.parity.ModuleParity.map;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 与 fastapi-temp {@code tests/parity/test_addresses_parity.py} 一一对应。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AddressesParityIT {

    static final String URL = "/api/v1/addresses";
    static final Map<String, Object> BEIJING =
            map("provinceCode", "110000000000", "cityCode", "110100000000", "districtCode", "110101000000");

    private final ModuleParity parity = new ModuleParity(Parity.fromSystemProperties());
    private final AuthParityIT auth = new AuthParityIT();
    private AuthParityIT.TempUser user;

    @BeforeAll
    void createUser() {
        user = auth.tempUser("addresses");
        ModuleParity.purge(user, "address");
    }

    @AfterAll
    void dropUser() {
        if (user != null) {
            ModuleParity.purge(user, "address");
            ParityDb.dropUser(user.email());
        }
    }

    static Map<String, Object> with(Map<String, Object> base, Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        result.putAll(map(pairs));
        return result;
    }

    static Map<String, Object> beijing(Object... pairs) {
        Map<String, Object> result = map(pairs);
        result.putAll(BEIJING);
        return result;
    }

    static Stream<Arguments> unauthenticated() {
        return Stream.of(
                Arguments.of("GET", ""),
                Arguments.of("POST", ""),
                Arguments.of("GET", "/default"),
                Arguments.of("GET", "/1"),
                Arguments.of("PUT", "/1"),
                Arguments.of("DELETE", "/1"),
                Arguments.of("PUT", "/1/default"));
    }

    @ParameterizedTest
    @MethodSource("unauthenticated")
    void requiresAuthentication(String method, String path) {
        assertSame(parity.send(method, URL + path, null, map()));
    }

    static Stream<Arguments> createBodies() {
        List<Map<String, Object>> bodies = List.of(
                map(),
                map("name", "A", "phone", "1", "address", "x"),
                map("name", "A", "phone", "13800138000", "provinceCode", "110000000000", "address", "x"),
                with(beijing("name", "A", "phone", "13800138000"), "provinceCode", "1100", "address", "x"),
                map("name", 1, "phone", 2, "province", "", "city", "c".repeat(51), "district", "d", "address", ""),
                with(beijing("name", "A", "phone", "13800138000"), "address", "x", "isDefault", "yes"));
        return Stream.of("en", "zh")
                .flatMap(lang -> bodies.stream().map(body -> Arguments.of(lang, body)));
    }

    @ParameterizedTest
    @MethodSource("createBodies")
    void createValidation(String lang, Map<String, Object> body) {
        assertSame(parity.send("POST", URL, user.tokens(), Map.of("Accept-Language", lang), body));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "?page=0&limit=abc",
                "?filters=%7Bbad",
                "?filters=%7B%22provinceCode%22%3A%22x%22%7D",
                "?sort=%5B%7B%22orderBy%22%3A%22x%22%2C%22order%22%3A%22up%22%7D%5D",
                "?sort=%7B%7D",
                "?page=1.5"
            })
    void listValidation(String query) {
        assertSame(parity.send("GET", URL + query, user.tokens(), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abc", "/0", "/2147483648", "/999999", "/999999/default"})
    void unknownOrInvalidId(String path) {
        for (String method : List.of("GET", "PUT", "DELETE")) {
            if (path.endsWith("/default") && !method.equals("PUT")) {
                continue;
            }
            assertSame(parity.send(method, URL + path, user.tokens(), map()));
        }
    }

    @Test
    void addressLifecycle() {
        List<String> tokens = user.tokens();
        assertSame(parity.send("GET", URL, tokens, null));
        assertSame(parity.send("GET", URL + "/default", tokens, null));

        List<Integer> first = ids(assertSame(parity.send(
                "POST",
                URL,
                tokens,
                map(
                        "name",
                        " <b>Ann</b> ",
                        "phone",
                        "13800138000",
                        "province",
                        "P",
                        "city",
                        "C",
                        "district",
                        "D",
                        "address",
                        "<i>road</i> 1",
                        "unknown",
                        true))));
        List<Integer> second = ids(assertSame(parity.send(
                "POST",
                URL,
                tokens,
                with(
                        beijing("name", "Bob", "phone", "13900139000"),
                        "province",
                        "ignored",
                        "address",
                        "x",
                        "isDefault",
                        true))));
        List<Integer> third = ids(assertSame(parity.send(
                "POST", URL, tokens, with(beijing("name", "Cat", "phone", "13700137000"), "address", "y"))));
        assertSame(parity.send(
                "POST",
                URL,
                tokens,
                with(beijing("name", "Bad", "phone", "13700137000"), "cityCode", "120100000000", "address", "z")));
        assertSame(parity.send("GET", URL, tokens, null));
        assertSame(parity.send("GET", URL + "/default", tokens, null));
        assertSame(parity.send("GET", itemUrls(URL, first), tokens, Map.of(), null));

        for (String query : List.of(
                "?filters=%7B%22provinceCode%22%3A%22110000000000%22%7D",
                "?filters=%7B%22province%22%3A%22P%22%2C%22city%22%3A%22%22%7D",
                "?sort=%5B%7B%22orderBy%22%3A%22name%22%2C%22order%22%3A%22desc%22%7D%5D",
                "?sort=%5B%7B%22orderBy%22%3A%22id%22%2C%22order%22%3A%22ASC%22%7D%5D&page=2&limit=2",
                "?limit=60")) {
            assertSame(parity.send("GET", URL + query, tokens, null));
        }

        for (Map<String, Object> body : List.of(
                map("name", "Ann2", "isDefault", true),
                map("cityCode", "110100000000"),
                with(BEIJING, "cityCode", "120100000000"),
                with(BEIJING, "province", "kept?"),
                map("province", "NewP", "isDefault", false),
                map("phone", "123"),
                map())) {
            assertSame(parity.send("PUT", itemUrls(URL, first), tokens, Map.of(), body));
        }

        assertSame(parity.send("PUT", itemUrls(URL, third, "/default"), tokens, Map.of(), null));
        assertSame(parity.send("GET", URL, tokens, null));
        assertSame(parity.send("DELETE", itemUrls(URL, third), tokens, Map.of(), null));
        assertSame(parity.send("GET", URL + "/default", tokens, null));
        assertSame(parity.send("GET", itemUrls(URL, third), tokens, Map.of(), null));
        assertSame(parity.send("DELETE", itemUrls(URL, third), tokens, Map.of(), null));
        assertSame(parity.send("DELETE", itemUrls(URL, first), tokens, Map.of(), null));
        assertSame(parity.send("DELETE", itemUrls(URL, second), tokens, Map.of(), null));
        assertSame(parity.send("GET", URL, tokens, null));
        assertSame(parity.send("GET", URL + "/default", tokens, null));
    }

    @Test
    void addressLimit() {
        ModuleParity.purge(user, "address");
        Map<String, Object> body = map("name", "N", "phone", "13800138000", "province", "P", "city", "C", "district", "D");
        for (int index = 0; index < 20; index++) {
            assertSame(parity.send("POST", URL, user.tokens(), with(body, "address", String.valueOf(index))));
        }
        for (String lang : List.of("en", "zh")) {
            assertSame(parity.send(
                    "POST", URL, user.tokens(), Map.of("Accept-Language", lang), with(body, "address", "x")));
        }
        assertSame(parity.send("GET", URL + "?limit=5&page=3", user.tokens(), null));
        ModuleParity.purge(user, "address");
    }
}
