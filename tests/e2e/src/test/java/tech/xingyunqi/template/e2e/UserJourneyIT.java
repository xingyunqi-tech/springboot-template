package tech.xingyunqi.template.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.e2e.E2e.bearer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class UserJourneyIT {

    private static final byte[] PNG = "\u0089PNG\r\n\u001a\ne2e-image".getBytes(StandardCharsets.ISO_8859_1);
    private static final String DEVICE_A = "e2e-device-a";
    private static final String DEVICE_B = "e2e-device-b";

    private final E2e api = new E2e();

    @Test
    void registerConfirmLoginRefreshSessionsPasswordUploadLogout() {
        String email = E2e.uniqueEmail("journey");
        api.register(email);

        JsonNode message = api.waitForMail(email);
        assertThat(message.path("From").path("Address").asString()).isEqualTo("noreply@example.com");
        String hash = E2e.confirmHash(message);
        assertThat(api.json("POST", "/api/v1/auth/email/confirm", Map.of(), Map.of("hash", hash))
                        .status())
                .isEqualTo(200);

        JsonNode deviceA = api.login(email, E2e.PASSWORD, DEVICE_A);
        JsonNode deviceB = api.login(email, E2e.PASSWORD, DEVICE_B);
        E2e.Response me =
                api.get("/api/v1/auth/me", bearer(deviceA.path("token").asString()));
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.data().path("email").asString()).isEqualTo(email);
        assertThat(me.data().path("status").path("id").asInt()).isEqualTo(1);

        String refreshToken = deviceA.path("refreshToken").asString();
        E2e.Response refreshed = api.post("/api/v1/auth/refresh", bearer(refreshToken));
        assertThat(refreshed.status()).as(refreshed.text()).isEqualTo(200);
        assertThat(refreshed.data().has("token")).isTrue();
        assertThat(refreshed.data().has("refreshToken")).isTrue();
        assertThat(refreshed.data().has("tokenExpires")).isTrue();
        assertThat(api.post("/api/v1/auth/refresh", bearer(refreshToken)).status())
                .isEqualTo(401);
        String tokenA = refreshed.data().path("token").asString();

        E2e.Response sessions = api.get("/api/v1/auth/sessions", bearer(tokenA));
        assertThat(sessions.status()).as(sessions.text()).isEqualTo(200);
        List<String> agents = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (JsonNode session : sessions.data()) {
            agents.add(session.path("userAgent").asString());
            if (session.path("current").asBoolean()) {
                current.add(session.path("userAgent").asString());
            }
        }
        assertThat(agents).containsExactlyInAnyOrder(DEVICE_A, DEVICE_B);
        assertThat(current).containsExactly(DEVICE_A);

        assertThat(api.post("/api/v1/auth/sessions/revoke-others", bearer(tokenA))
                        .status())
                .isEqualTo(200);
        assertThat(api.get("/api/v1/auth/me", bearer(deviceB.path("token").asString()))
                        .status())
                .isEqualTo(401);
        assertThat(api.get("/api/v1/auth/sessions", bearer(tokenA)).data()).hasSize(1);

        String newPassword = E2e.md5("Secret01");
        E2e.Response changed = api.json(
                "PUT",
                "/api/v1/auth/password",
                bearer(tokenA),
                Map.of("oldPassword", E2e.PASSWORD, "newPassword", newPassword));
        assertThat(changed.status()).as(changed.text()).isEqualTo(200);
        E2e.Response oldLogin = api.json(
                "POST", "/api/v1/auth/email/login", Map.of(), Map.of("email", email, "password", E2e.PASSWORD));
        assertThat(oldLogin.status()).isEqualTo(422);
        String token = api.login(email, newPassword, DEVICE_B).path("token").asString();

        E2e.Response uploaded = api.upload("image", token, "e2e.png", "image/png", PNG);
        assertThat(uploaded.status()).as(uploaded.text()).isEqualTo(201);
        String path = uploaded.data().path("file").path("path").asString();
        E2e.Response download = api.get("/api/v1/files/" + path.substring(path.lastIndexOf('/') + 1));
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.headers().first("Content-Type")).isEqualTo("image/png");
        assertThat(download.body()).isEqualTo(PNG);

        E2e.Response privateUpload = api.upload("private-image", token, "e2e.png", "image/png", PNG);
        assertThat(privateUpload.status()).as(privateUpload.text()).isEqualTo(201);
        String privateId = privateUpload.data().path("file").path("id").asString();
        assertThat(api.get("/api/v1/files/private/" + privateId).status()).isEqualTo(401);
        E2e.Response owned = api.get("/api/v1/files/private/" + privateId, bearer(token));
        assertThat(owned.status()).isEqualTo(200);
        assertThat(owned.body()).isEqualTo(PNG);

        assertThat(api.post("/api/v1/auth/logout", bearer(token)).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/auth/me", bearer(token)).status()).isEqualTo(401);
    }
}
