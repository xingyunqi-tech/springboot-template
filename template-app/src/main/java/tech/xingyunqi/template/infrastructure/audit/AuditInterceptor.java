package tech.xingyunqi.template.infrastructure.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.security.route.AuthPrincipal;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** nestjs {@code AuditInterceptor}：带 {@link Audit} 的 handler 成功返回后记录审计日志，抛出异常时不记录。 */
public class AuditInterceptor implements MethodInterceptor {

    static final String UNKNOWN = "unknown";

    private static final Logger LOG = LoggerFactory.getLogger(AuditInterceptor.class);

    private final ObjectProvider<AuditService> audit;
    private final ObjectProvider<JsonMapper> json;
    private final Map<Class<? extends AuditBodySummarizer>, AuditBodySummarizer> summarizers =
            new ConcurrentHashMap<>();

    public AuditInterceptor(ObjectProvider<AuditService> audit, ObjectProvider<JsonMapper> json) {
        this.audit = audit;
        this.json = json;
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        Object result = invocation.proceed();
        Audit options = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), Audit.class);
        if (options != null) {
            try {
                record(options, result);
            } catch (RuntimeException e) {
                LOG.error("Failed to create audit log", e);
            }
        }
        return result;
    }

    private void record(Audit options, @Nullable Object result) {
        HttpServletRequest request = currentRequest();
        Map<String, String> params = pathParameters(request);
        String paramId = paramId(params, options.idParam());
        JsonNode response = result == null ? null : unwrap(json.getObject().valueToTree(result));
        String responseId = responseId(response);
        String entityId = options.entityIdFrom() == Audit.EntityIdFrom.RESPONSE
                ? firstNonNull(responseId, paramId)
                : firstNonNull(paramId, responseId);
        JsonNode newValue = options.includeResponse() && response != null && response.isContainer()
                ? response
                : recordedBody(options, request, params);
        AuthPrincipal principal = request == null ? null : AuthContext.optional();
        audit.getObject()
                .log(
                        new AuditService.Entry(
                                options.action(),
                                options.entityType(),
                                entityId,
                                principal == null ? null : principal.id(),
                                null,
                                newValue),
                        request);
    }

    private @Nullable JsonNode recordedBody(
            Audit options, @Nullable HttpServletRequest request, Map<String, String> params) {
        boolean summarized = options.summarizeBody() != AuditBodySummarizer.None.class;
        if (!options.includeBody() && !summarized) {
            return null;
        }
        JsonNode body = request == null ? null : ValidatedBodyArgumentResolver.validatedBody(request);
        if (summarized) {
            AuditBodySummarizer summarizer =
                    summarizers.computeIfAbsent(options.summarizeBody(), BeanUtils::instantiateClass);
            Map<String, ?> summary = summarizer.summarize(body, params);
            return summary == null ? null : json.getObject().valueToTree(summary);
        }
        return body != null && body.isContainer() ? body : null;
    }

    /** {@code {code: <number>, message, data}} 信封取 {@code data}。 */
    static JsonNode unwrap(JsonNode response) {
        if (response.isObject()
                && response.path("code").isNumber()
                && response.has("message")
                && response.has("data")) {
            return response.get("data");
        }
        return response;
    }

    static @Nullable String responseId(@Nullable JsonNode response) {
        JsonNode id = response != null && response.isObject() ? response.get("id") : null;
        return id != null && (id.isString() || id.isNumber()) ? id.asString() : null;
    }

    static @Nullable String paramId(Map<String, String> params, String[] names) {
        List<String> values = new ArrayList<>();
        for (String name : names) {
            String value = params.get(name);
            if (value == null || value.isEmpty()) {
                return null;
            }
            values.add(value);
        }
        return String.join(":", values);
    }

    private static String firstNonNull(@Nullable String first, @Nullable String second) {
        if (first != null) {
            return first;
        }
        return second != null ? second : UNKNOWN;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathParameters(@Nullable HttpServletRequest request) {
        Object variables =
                request == null ? null : request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static @Nullable HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
