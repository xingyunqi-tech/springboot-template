package tech.xingyunqi.template.modules.feedbacks;

import org.springframework.stereotype.Component;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;

/** 反馈保留，解除用户归属并清空联系方式与图片。 */
@Component
class FeedbacksAccountCleaner implements AccountDataCleaner {

    static final int ORDER = 90;

    private final FeedbacksService feedbacks;

    FeedbacksAccountCleaner(FeedbacksService feedbacks) {
        this.feedbacks = feedbacks;
    }

    @Override
    public String name() {
        return "feedbacks";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void clean(int userId) {
        feedbacks.anonymizeByUser(userId);
    }
}
