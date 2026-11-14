package tech.xingyunqi.template.common.web.bind;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tech.xingyunqi.template.common.web.filter.JsonBodyFilter;
import tech.xingyunqi.template.common.web.validation.ClassValidatorEngine;
import tech.xingyunqi.template.common.web.validation.ParamType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code @RequestBody}：按参数类型名（即 nestjs DTO 类名）复刻 ValidationPipe，再绑定为生成的 record。 */
public class ValidatedBodyArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String VALIDATED_BODY = ValidatedBodyArgumentResolver.class.getName() + ".body";

    private final ClassValidatorEngine engine;
    private final JsonMapper mapper;

    public ValidatedBodyArgumentResolver(ClassValidatorEngine engine, JsonMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(RequestBody.class);
    }

    @Override
    public @Nullable Object resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        JsonNode body = request == null ? null : JsonBodyFilter.parsedBody(request);
        Class<?> type = parameter.getParameterType();
        String dto = type.getSimpleName();
        JsonNode value = engine.hasDto(dto) ? engine.run(dto, body, ParamType.BODY) : body;
        if (request != null && value != null) {
            request.setAttribute(VALIDATED_BODY, value);
        }
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return mapper.treeToValue(value, type);
    }

    /** 当前请求经 ValidationPipe 转换后的请求体（区分未提供与显式 {@code null}）；没有请求体参数时为 null。 */
    public static @Nullable JsonNode validatedBody(HttpServletRequest request) {
        return (JsonNode) request.getAttribute(VALIDATED_BODY);
    }
}
