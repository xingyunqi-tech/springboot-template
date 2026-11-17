package tech.xingyunqi.template.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProbesIT {

    private final E2e api = new E2e();

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/health/live", "/api/v1/health/ready"})
    void probesReportOk(String path) {
        E2e.Response response = api.get(path);

        assertThat(response.status()).as(response.text()).isEqualTo(200);
        assertThat(response.headers().first("Cache-Control")).isEqualTo("no-cache, no-store, must-revalidate");
        assertThat(response.data().path("status").asString()).isEqualTo("ok");
    }

    @Test
    void readinessIncludesDatabase() {
        E2e.Response response = api.get("/api/v1/health/ready");

        assertThat(response.data()
                        .path("details")
                        .path("database")
                        .path("status")
                        .asString())
                .isEqualTo("up");
    }

    @Test
    void simpleHealthCheckReportsOk() {
        E2e.Response response = api.get("/api/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).contains("\"status\":\"ok\"");
    }

    @Test
    void unknownRouteIsNestStyle404() {
        E2e.Response response = api.get("/api/v1/nope");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.text()).isEqualTo("{\"code\":404,\"message\":\"Cannot GET /api/v1/nope\",\"data\":null}");
    }

    @Test
    void docsJsonIsBaseline() throws IOException {
        Path baseline = Path.of(System.getProperty("repo.root", "../.."), "openapi", "nestjs-baseline.json");

        E2e.Response response = api.get("/docs-json");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(Files.readAllBytes(baseline));
    }
}
