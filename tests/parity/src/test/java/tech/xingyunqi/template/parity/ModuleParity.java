package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/** 与 fastapi-temp {@code tests/parity/test_addresses_parity.py} 中的公共函数对应，供通用模块的 parity 用例共用。 */
final class ModuleParity {

    static final Set<String> TYPED_KEYS = Set.of("favoriteId", "userId", "deletedAt");

    private final Parity parity;

    ModuleParity(Parity parity) {
        this.parity = parity;
    }

    /** 删除临时用户在两个后端数据库中的指定表数据。 */
    static void purge(AuthParityIT.TempUser user, String... tables) {
        List<String> containers = List.of(ParityDb.JAVA, ParityDb.NESTJS);
        for (int side = 0; side < 2; side++) {
            List<String> statements = new ArrayList<>();
            for (String table : tables) {
                statements.add("DELETE FROM \"" + table + "\" WHERE \"userId\" = " + user.ids().get(side));
            }
            ParityDb.psql(containers.get(side), String.join(";", statements));
        }
    }

    static JsonNode comparable(JsonNode value, @Nullable String key) {
        if (value instanceof ObjectNode object) {
            ObjectNode copy = Parity.JSON.createObjectNode();
            object.properties().forEach(e -> copy.set(e.getKey(), comparable(e.getValue(), e.getKey())));
            return copy;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode copy = Parity.JSON.createArrayNode();
            array.forEach(item -> copy.add(comparable(item, null)));
            return copy;
        }
        if (key != null && TYPED_KEYS.contains(key) && !value.isNull()) {
            return StringNode.valueOf("<" + (value.isNumber() ? "int" : value.isString() ? "str" : "other") + ">");
        }
        return AuthParityIT.normalize(value, key);
    }

    /** 状态码、Content-Type 与归一化后的 body 一致；返回 (java, nestjs) 的 body。 */
    static List<JsonNode> assertSame(Parity.Pair pair) {
        String detail = new String(pair.java().body(), StandardCharsets.UTF_8) + "\n"
                + new String(pair.nestjs().body(), StandardCharsets.UTF_8);
        assertThat(pair.java().status()).as(detail).isEqualTo(pair.nestjs().status());
        assertThat(pair.java().header("content-type")).isEqualTo(pair.nestjs().header("content-type"));
        JsonNode java = pair.java().json();
        JsonNode nestjs = pair.nestjs().json();
        Parity.assertSameJson(comparable(java, null), comparable(nestjs, null), detail);
        return List.of(java, nestjs);
    }

    /** 两侧各自的 URL（路径中的 id 不同）与令牌发出同一请求；{@code tokens} 为 null 时不带令牌。 */
    Parity.Pair send(
            String method,
            List<String> urls,
            @Nullable List<String> tokens,
            Map<String, String> headers,
            @Nullable Object body) {
        List<Map<String, String>> sides = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            Map<String, String> all = new LinkedHashMap<>(headers);
            if (tokens != null) {
                all.put("Authorization", "Bearer " + tokens.get(side));
            }
            if (body != null) {
                all.put("Content-Type", "application/json");
            }
            sides.add(all);
        }
        byte[] payload = body == null ? new byte[0] : AuthParityIT.json(body);
        return parity.requestEach(method, urls.get(0), sides.get(0), urls.get(1), sides.get(1), payload);
    }

    Parity.Pair send(String method, String url, @Nullable List<String> tokens, @Nullable Object body) {
        return send(method, List.of(url, url), tokens, Map.of(), body);
    }

    Parity.Pair send(
            String method, String url, @Nullable List<String> tokens, Map<String, String> headers, @Nullable Object body) {
        return send(method, List.of(url, url), tokens, headers, body);
    }

    static List<Integer> ids(List<JsonNode> results) {
        return List.of(
                results.get(0).path("data").path("id").asInt(),
                results.get(1).path("data").path("id").asInt());
    }

    static List<String> itemUrls(String base, List<Integer> ids, String suffix) {
        return List.of(base + "/" + ids.get(0) + suffix, base + "/" + ids.get(1) + suffix);
    }

    static List<String> itemUrls(String base, List<Integer> ids) {
        return itemUrls(base, ids, "");
    }

    static Map<String, Object> map(Object... pairs) {
        return AuthParityIT.map(pairs);
    }
}
