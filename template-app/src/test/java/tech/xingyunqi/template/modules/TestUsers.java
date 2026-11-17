package tech.xingyunqi.template.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import tech.xingyunqi.template.auth.AuthApi;

/** 通过注册接口创建的独立用户。 */
public final class TestUsers {

    public record TestUser(int id, String email, String token) {}

    private TestUsers() {}

    public static TestUser register(AuthApi api, String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        AuthApi.Response registered = api.call(
                "POST",
                "/api/v1/auth/email/register",
                Map.of("email", email, "password", AuthApi.PASSWORD, "firstName", "T", "lastName", "U"),
                null);
        assertThat(registered.status()).as(registered.body().toString()).isEqualTo(200);
        String token = api.loginData(email).path("token").asString();
        return new TestUser(AuthApi.payload(token).path("id").asInt(), email, token);
    }
}
