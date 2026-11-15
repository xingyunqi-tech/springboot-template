package tech.xingyunqi.template.infrastructure.audit;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** nestjs {@code summarizeBody}：用摘要替换记录的请求体；入参为校验后的请求体与路由参数。 */
public interface AuditBodySummarizer {

    @Nullable
    Map<String, ?> summarize(@Nullable JsonNode body, Map<String, String> params);

    /** 未指定摘要函数。 */
    final class None implements AuditBodySummarizer {
        @Override
        public @Nullable Map<String, ?> summarize(@Nullable JsonNode body, Map<String, String> params) {
            throw new UnsupportedOperationException();
        }
    }
}
