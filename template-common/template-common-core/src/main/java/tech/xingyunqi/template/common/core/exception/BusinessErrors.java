package tech.xingyunqi.template.common.core.exception;

import java.util.Map;

/** nestjs {@code business-error-keys.ts} 的错误键与 HTTP 状态码。 */
public final class BusinessErrors {

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry("sessionTakenOver", 409),
            Map.entry("sessionClosed", 409),
            Map.entry("questionInMockInProgress", 409),
            Map.entry("answerHidden", 403),
            Map.entry("selfAssessmentNotAllowed", 409),
            Map.entry("questionNotAccessible", 403),
            Map.entry("noQuestionsAvailable", 422),
            Map.entry("generateLimitExceeded", 429),
            Map.entry("mockSessionBusy", 409),
            Map.entry("paperRequired", 422),
            Map.entry("reportNotAvailable", 409),
            Map.entry("dailySessionCannotFinish", 409),
            Map.entry("dailySessionCannotAbandon", 409),
            Map.entry("aiUnavailable", 503),
            Map.entry("aiQuotaExceeded", 429),
            Map.entry("aiRateLimited", 429),
            Map.entry("aiConsentRequired", 403),
            Map.entry("phoneBindingRequired", 403),
            Map.entry("contentBlocked", 422),
            Map.entry("aiConversationOutdated", 409),
            Map.entry("errataInProgress", 409),
            Map.entry("answerChangeNotAllowed", 422));

    private BusinessErrors() {}

    public static int status(String errorKey) {
        Integer status = STATUS.get(errorKey);
        if (status == null) {
            throw new IllegalArgumentException("未登记的业务错误键：" + errorKey);
        }
        return status;
    }
}
