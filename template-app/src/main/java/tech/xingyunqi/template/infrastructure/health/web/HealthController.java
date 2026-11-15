package tech.xingyunqi.template.infrastructure.health.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.route.MonitoringToken;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.contract.api.HealthApi;
import tech.xingyunqi.template.contract.model.HealthCheck200Response;
import tech.xingyunqi.template.infrastructure.health.HealthIndicators;

/**
 * @nestjs/terminus 形状：全部 up 时 200 并带 no-cache 头；任一项 down 时按 terminus 抛出 503（异常体无 message，信封为
 *     {@code SERVICE_UNAVAILABLE}）。
 */
@RestController
public class HealthController implements HealthApi {

    static final String NO_CACHE = "no-cache, no-store, must-revalidate";

    private final HealthIndicators indicators;

    public HealthController(HealthIndicators indicators) {
        this.indicators = indicators;
    }

    @Override
    @MonitoringToken
    public HealthCheck200Response healthCheck(@Nullable Object acceptLanguage) {
        return respond(indicators.full());
    }

    @Override
    @PublicEndpoint
    public HealthCheck200Response livenessProbe(@Nullable Object acceptLanguage) {
        return respond(indicators.liveness());
    }

    @Override
    @PublicEndpoint
    public HealthCheck200Response readinessProbe(@Nullable Object acceptLanguage) {
        return respond(indicators.readiness());
    }

    private static HealthCheck200Response respond(HealthIndicators.Result result) {
        if (!result.ok()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("info", result.info());
            body.put("error", result.error());
            body.put("details", result.details());
            throw new HttpStatusException(HttpServletResponse.SC_SERVICE_UNAVAILABLE, body);
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletResponse response = attributes.getResponse();
            if (response != null) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, NO_CACHE);
            }
        }
        return new HealthCheck200Response("ok", result.info(), result.error(), result.details());
    }
}
