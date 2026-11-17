package tech.xingyunqi.template.parity;

import static tech.xingyunqi.template.parity.ModuleParity.assertSame;
import static tech.xingyunqi.template.parity.ModuleParity.ids;
import static tech.xingyunqi.template.parity.ModuleParity.itemUrls;
import static tech.xingyunqi.template.parity.ModuleParity.map;

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

/** 与 fastapi-temp {@code tests/parity/test_favorites_parity.py} 一一对应。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FavoritesParityIT {

    static final String URL = "/api/v1/favorites";
    static final String UUID = "00000000-0000-4000-8000-00000000000A";

    private final ModuleParity parity = new ModuleParity(Parity.fromSystemProperties());
    private final AuthParityIT auth = new AuthParityIT();
    private AuthParityIT.TempUser user;

    @BeforeAll
    void createUser() {
        user = auth.tempUser("favorites");
        ModuleParity.purge(user, "favorite");
    }

    @AfterAll
    void dropUser() {
        if (user != null) {
            ModuleParity.purge(user, "favorite");
            ParityDb.dropUser(user.email());
        }
    }

    static Stream<Arguments> unauthenticated() {
        return Stream.of(
                Arguments.of("GET", ""),
                Arguments.of("POST", ""),
                Arguments.of("POST", "/check"),
                Arguments.of("POST", "/toggle"),
                Arguments.of("DELETE", "/1"));
    }

    @ParameterizedTest
    @MethodSource("unauthenticated")
    void requiresAuthentication(String method, String path) {
        assertSame(parity.send(method, URL + path, null, map()));
    }

    static Stream<Arguments> bodies() {
        List<Map<String, Object>> bodies = List.of(
                map(),
                map("targetType", "movie", "targetId", "1"),
                map("targetType", "product", "targetId", "x".repeat(101)),
                map("targetType", "product", "targetId", 1, "title", "t".repeat(201), "image", 2, "extra", "e".repeat(2001)));
        return Stream.of("en", "zh")
                .flatMap(lang -> Stream.of("", "/check", "/toggle")
                        .flatMap(path -> bodies.stream().map(body -> Arguments.of(lang, path, body))));
    }

    @ParameterizedTest
    @MethodSource("bodies")
    void bodyValidation(String lang, String path, Map<String, Object> body) {
        assertSame(parity.send("POST", URL + path, user.tokens(), Map.of("Accept-Language", lang), body));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "?targetType=bad&subjectId=x&page=0",
                "?page=1.5&limit=0",
                "?subjectId=" + UUID,
                "?targetType=question",
                "?limit=100"
            })
    void listQueries(String query) {
        assertSame(parity.send("GET", URL + query, user.tokens(), null));
    }

    static Stream<Arguments> examTargets() {
        List<Map<String, Object>> bodies = List.of(
                map("targetType", "question", "targetId", "q1"),
                map("targetType", "question", "targetId", UUID),
                map("targetType", "paper", "targetId", "p1"),
                map("targetType", "paper", "targetId", UUID));
        return Stream.of("en", "zh").flatMap(lang -> bodies.stream().map(body -> Arguments.of(lang, body)));
    }

    @ParameterizedTest
    @MethodSource("examTargets")
    void examTargetsAreNotFound(String lang, Map<String, Object> body) {
        for (String path : List.of("", "/toggle")) {
            assertSame(parity.send("POST", URL + path, user.tokens(), Map.of("Accept-Language", lang), body));
        }
        assertSame(parity.send("POST", URL + "/check", user.tokens(), body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abc", "/0", "/999999", "/check"})
    void deleteUnknownOrInvalidId(String path) {
        assertSame(parity.send("DELETE", URL + path, user.tokens(), null));
    }

    @Test
    void favoriteLifecycle() {
        List<String> tokens = user.tokens();
        Map<String, Object> product = map(
                "targetType",
                "product",
                "targetId",
                "p1",
                "title",
                " <b>Phone</b> ",
                "image",
                "https://example.com/a.png",
                "extra",
                "{}",
                "unknown",
                1);
        List<Integer> first = ids(assertSame(parity.send("POST", URL, tokens, product)));
        assertSame(parity.send("POST", URL, tokens, map("targetType", "article", "targetId", "a1")));
        for (String lang : List.of("en", "zh")) {
            assertSame(parity.send("POST", URL, tokens, Map.of("Accept-Language", lang), product));
        }
        assertSame(parity.send("POST", URL + "/check", tokens, product));
        assertSame(parity.send("POST", URL + "/check", tokens, map("targetType", "store", "targetId", "s1")));
        Map<String, Object> store = map("targetType", "store", "targetId", "s1", "title", "");
        assertSame(parity.send("POST", URL + "/toggle", tokens, store));
        assertSame(parity.send("GET", URL, tokens, null));
        assertSame(parity.send("GET", URL + "?targetType=store&limit=1", tokens, null));
        assertSame(parity.send("GET", URL + "?page=2&limit=2", tokens, null));
        assertSame(parity.send("POST", URL + "/toggle", tokens, store));
        assertSame(parity.send("DELETE", itemUrls(URL, first), tokens, Map.of(), null));
        assertSame(parity.send("DELETE", itemUrls(URL, first), tokens, Map.of(), null));
        assertSame(parity.send("GET", URL, tokens, null));
    }
}
