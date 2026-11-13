package tech.xingyunqi.template.common.web.envelope;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import tech.xingyunqi.template.common.web.http.JsonResponses;
import tools.jackson.databind.json.JsonMapper;

/** {@code @RestController} 中返回 void 的方法输出 {@code {"code":200,"message":"success"}}（不含 data）。 */
public class VoidEnvelopeReturnValueHandler implements HandlerMethodReturnValueHandler {

    private final JsonMapper mapper;

    public VoidEnvelopeReturnValueHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        Class<?> type = returnType.getParameterType();
        return (type == void.class || type == Void.class)
                && returnType.getContainingClass().isAnnotationPresent(RestController.class)
                && !ApiResponseAdvice.isRaw(returnType);
    }

    @Override
    public void handleReturnValue(
            @Nullable Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest)
            throws Exception {
        mavContainer.setRequestHandled(true);
        HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
        if (response == null || response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", ApiResponse.SUCCESS);
        int status = response.getStatus() == 0 ? 200 : response.getStatus();
        JsonResponses.write(response, status, body, mapper);
    }
}
