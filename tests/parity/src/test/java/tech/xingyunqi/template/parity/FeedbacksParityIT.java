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

/** 与 fastapi-temp {@code tests/parity/test_feedbacks_parity.py} 一一对应。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbacksParityIT {

    static final String URL = "/api/v1/feedbacks";
    static final String ADMIN = "admin@example.com";

    private final ModuleParity parity = new ModuleParity(Parity.fromSystemProperties());
    private final AuthParityIT auth = new AuthParityIT();
    private AuthParityIT.TempUser user;

    @BeforeAll
    void createUser() {
        user = auth.tempUser("feedbacks");
        ModuleParity.purge(user, "feedback");
    }

    @AfterAll
    void dropUser() {
        if (user != null) {
            ModuleParity.purge(user, "feedback");
            ParityDb.dropUser(user.email());
        }
    }

    static Stream<Arguments> unauthenticated() {
        return Stream.of(Arguments.of("GET", ""), Arguments.of("POST", ""), Arguments.of("GET", "/1"));
    }

    @ParameterizedTest
    @MethodSource("unauthenticated")
    void requiresAuthentication(String method, String path) {
        assertSame(parity.send(method, URL + path, null, map()));
    }

    static Stream<Arguments> createBodies() {
        List<Map<String, Object>> bodies = List.of(
                map(),
                map("type", "question_error", "content", ""),
                map("type", "bug", "content", "c".repeat(2001), "contact", 1),
                map("type", "bug", "content", "c", "images", List.of(1, "a", "b", "c", "d", "e")),
                map("type", "bug", "content", "c", "images", "a"),
                map("type", "bug", "content", "c", "images", List.of("x".repeat(1001))));
        return Stream.of("en", "zh").flatMap(lang -> bodies.stream().map(body -> Arguments.of(lang, body)));
    }

    @ParameterizedTest
    @MethodSource("createBodies")
    void createValidation(String lang, Map<String, Object> body) {
        assertSame(parity.send("POST", URL, user.tokens(), Map.of("Accept-Language", lang), body));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "?type=ai_content&status=x&page=0&limit=a",
                "?page=1e400",
                "?type=question_error",
                "?status=closed"
            })
    void listValidationAndFilters(String query) {
        assertSame(parity.send("GET", URL + query, user.tokens(), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abc", "/0", "/999999"})
    void unknownOrInvalidId(String path) {
        assertSame(parity.send("GET", URL + path, user.tokens(), null));
    }

    @Test
    void feedbackLifecycle() {
        List<String> tokens = user.tokens();
        List<Integer> first = ids(assertSame(parity.send("POST", URL, tokens, map("type", "bug", "content", "c"))));
        assertSame(parity.send(
                "POST",
                URL,
                tokens,
                map("type", "suggestion", "content", "<b>s</b>", "images", List.of("a", "b"), "contact", "x", "unknown", 1)));
        assertSame(parity.send("POST", URL, tokens, map("type", "other", "content", "o", "images", List.of())));
        assertSame(parity.send("GET", itemUrls(URL, first), tokens, Map.of(), null));
        for (String query : List.of("", "?type=bug", "?status=pending&limit=2", "?page=1.3&limit=1", "?page=2&limit=1.5", "?limit=100")) {
            assertSame(parity.send("GET", URL + query, tokens, null));
        }
        List<String> admin = auth.tokens(ADMIN);
        assertSame(parity.send("GET", itemUrls(URL, first), admin, Map.of(), null));
    }

    private List<Integer> insertFeedback(String type) {
        List<String> containers = List.of(ParityDb.JAVA, ParityDb.NESTJS);
        return List.of(0, 1).stream()
                .map(side -> Integer.valueOf(ParityDb.psql(
                                containers.get(side),
                                "INSERT INTO \"feedback\" (\"userId\", \"type\", \"content\", \"questionVersion\", \"reason\")"
                                        + " VALUES (" + user.ids().get(side) + ", '" + type
                                        + "', 'r', 3, 'other') RETURNING id")
                        .split("\\s+")[0]))
                .toList();
    }

    @Test
    void reportTypesVisibility() {
        List<Integer> questionError = insertFeedback("question_error");
        List<Integer> aiContent = insertFeedback("ai_content");
        assertSame(parity.send("GET", itemUrls(URL, questionError), user.tokens(), Map.of(), null));
        assertSame(parity.send("GET", itemUrls(URL, aiContent), user.tokens(), Map.of(), null));
        assertSame(parity.send("GET", URL, user.tokens(), null));
        assertSame(parity.send("GET", URL + "?type=question_error", user.tokens(), null));
    }
}
