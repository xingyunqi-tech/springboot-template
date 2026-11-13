package tech.xingyunqi.template.common.core.exception;

import java.util.Map;

/** 对应 @nestjs/throttler 的 {@code ThrottlerException}。 */
public class TooManyRequestsException extends HttpStatusException {

    public TooManyRequestsException(Map<String, String> headers) {
        super(429, "ThrottlerException: Too Many Requests", headers);
    }
}
