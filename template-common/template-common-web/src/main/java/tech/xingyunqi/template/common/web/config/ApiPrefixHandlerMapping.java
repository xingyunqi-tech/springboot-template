package tech.xingyunqi.template.common.web.config;

import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 生成接口中的 {@code /api} 前缀按 {@code API_PREFIX} 替换；{@code /}、指标与文档路径不受影响。 */
public class ApiPrefixHandlerMapping extends RequestMappingHandlerMapping {

    static final String DEFAULT_PREFIX = "/api";

    private final String prefix;

    public ApiPrefixHandlerMapping(String apiPrefix) {
        String trimmed = apiPrefix.replaceAll("^/+|/+$", "");
        this.prefix = trimmed.isEmpty() ? "" : "/" + trimmed;
    }

    @Override
    protected @Nullable RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
        RequestMappingInfo info = super.getMappingForMethod(method, handlerType);
        if (info == null || prefix.equals(DEFAULT_PREFIX)) {
            return info;
        }
        String[] paths = info.getPatternValues().stream().map(this::rewrite).toArray(String[]::new);
        return info.mutate().paths(paths).options(getBuilderConfiguration()).build();
    }

    String rewrite(String path) {
        if (path.equals(DEFAULT_PREFIX) || path.startsWith(DEFAULT_PREFIX + "/")) {
            String rewritten = prefix + path.substring(DEFAULT_PREFIX.length());
            return rewritten.isEmpty() ? "/" : rewritten;
        }
        return path;
    }
}
