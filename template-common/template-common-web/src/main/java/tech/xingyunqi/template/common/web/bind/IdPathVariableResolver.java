package tech.xingyunqi.template.common.web.bind;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;
import tech.xingyunqi.template.common.web.error.InvalidId;

/** 数值类型的 {@code :id} 路径参数按 nestjs {@code ParseIdPipe} 解析，非法值 400 {@code {id: invalidId}}。 */
public class IdPathVariableResolver implements HandlerMethodArgumentResolver {

    private static final Set<Class<?>> NUMERIC =
            Set.of(int.class, Integer.class, long.class, Long.class, BigDecimal.class, double.class, Double.class);

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        PathVariable annotation = parameter.getParameterAnnotation(PathVariable.class);
        return annotation != null && NUMERIC.contains(parameter.getParameterType()) && "id".equals(name(parameter));
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object variables =
                request == null ? null : request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object raw = variables instanceof Map<?, ?> map ? map.get("id") : null;
        if (!(raw instanceof String text)) {
            throw InvalidId.exception();
        }
        int id = InvalidId.parse(text);
        Class<?> type = parameter.getParameterType();
        if (type == long.class || type == Long.class) {
            return (long) id;
        }
        if (type == BigDecimal.class) {
            return BigDecimal.valueOf(id);
        }
        if (type == double.class || type == Double.class) {
            return (double) id;
        }
        return id;
    }

    private static String name(MethodParameter parameter) {
        PathVariable annotation = parameter.getParameterAnnotation(PathVariable.class);
        String declared = annotation == null ? "" : annotation.name();
        if (declared.isEmpty() && annotation != null) {
            declared = annotation.value();
        }
        if (declared.isEmpty()) {
            String parameterName = parameter.getParameterName();
            declared = parameterName == null ? "" : parameterName;
        }
        return declared;
    }
}
