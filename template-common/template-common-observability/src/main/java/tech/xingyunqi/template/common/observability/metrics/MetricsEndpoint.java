package tech.xingyunqi.template.common.observability.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import tech.xingyunqi.template.common.web.envelope.RawResponse;
import tech.xingyunqi.template.common.web.http.ExpressContentType;
import tech.xingyunqi.template.common.web.route.MonitoringToken;

/** {@code METRICS_PATH}：不带 API 前缀、不包信封、不进文档；默认 JVM 指标在前（{@code METRICS_ENABLED=false} 时省略），自定义指标在后。 */
@RawResponse
@MonitoringToken
public class MetricsEndpoint {

    private final AppMetrics metrics;
    private final @Nullable PrometheusMeterRegistry defaults;

    public MetricsEndpoint(AppMetrics metrics, @Nullable PrometheusMeterRegistry defaults) {
        this.metrics = metrics;
        this.defaults = defaults;
    }

    public ResponseEntity<byte[]> getMetrics() {
        String body = defaults == null ? metrics.render() : defaults.scrape() + "\n" + metrics.render();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, ExpressContentType.PROMETHEUS)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
