package tech.xingyunqi.template.common.web.envelope;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tech.xingyunqi.template.common.web.http.JsonResponses;
import tools.jackson.databind.json.JsonMapper;

/** nestjs {@code ResponseInterceptor}：有返回值的接口包装为 {@code {code: 200, message: "success", data}}。 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final MediaType JSON_UTF8 = MediaType.parseMediaType(JsonResponses.CONTENT_TYPE);

    private final JsonMapper mapper;

    public ApiResponseAdvice(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return (JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType)
                        || StringHttpMessageConverter.class.isAssignableFrom(converterType))
                && !isRaw(returnType);
    }

    @Override
    public @Nullable Object beforeBodyWrite(
            @Nullable Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        response.getHeaders().setContentType(JSON_UTF8);
        Object envelope = body instanceof ApiResponse<?> ? body : ApiResponse.success(body);
        if (StringHttpMessageConverter.class.isAssignableFrom(selectedConverterType)) {
            return mapper.writeValueAsString(envelope);
        }
        return envelope;
    }

    static boolean isRaw(MethodParameter returnType) {
        return returnType.hasMethodAnnotation(RawResponse.class)
                || returnType.getContainingClass().isAnnotationPresent(RawResponse.class);
    }
}
