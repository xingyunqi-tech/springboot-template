package tech.xingyunqi.template.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.e2e.E2e.bearer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountCleanupIT {

    private static final byte[] PNG = "\u0089PNG\r\n\u001a\ne2e-cleanup".getBytes(StandardCharsets.ISO_8859_1);

    private final E2e api = new E2e();

    @Test
    void deletedAccountFilesAreRemovedByWorker() {
        String email = E2e.uniqueEmail("cleanup");
        api.registerConfirmed(email);
        String token =
                api.login(email, E2e.PASSWORD, "e2e-cleanup").path("token").asString();

        E2e.Response uploaded = api.upload("image", token, "cleanup.png", "image/png", PNG);
        assertThat(uploaded.status()).as(uploaded.text()).isEqualTo(201);
        String path = uploaded.data().path("file").path("path").asString();
        String download = "/api/v1/files/" + path.substring(path.lastIndexOf('/') + 1);
        assertThat(api.get(download).status()).isEqualTo(200);

        E2e.Response deleted =
                api.json("DELETE", "/api/v1/auth/me", bearer(token), Map.of("oldPassword", E2e.PASSWORD));
        assertThat(deleted.status()).as(deleted.text()).isEqualTo(200);
        assertThat(api.get("/api/v1/auth/me", bearer(token)).status()).isEqualTo(401);

        E2e.poll("file cleanup of " + email, () -> api.get(download).status() == 404);
    }
}
