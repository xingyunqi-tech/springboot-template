package tech.xingyunqi.template.modules.feedbacks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.modules.feedbacks.domain.Feedback;

/** 用户反馈：提交、列表（默认只含通用类型）与详情（仅本人且为可查询类型）。 */
@Service
public class FeedbacksService {

    private static final Logger LOG = LoggerFactory.getLogger(FeedbacksService.class);

    private final FeedbackRepository repository;

    public FeedbacksService(FeedbackRepository repository) {
        this.repository = repository;
    }

    @WriteTransaction
    public Feedback create(
            int userId, String type, String content, @Nullable List<String> images, @Nullable String contact) {
        LOG.info("Creating feedback for user: {}, type: {}", userId, type);
        Feedback feedback =
                repository.create(new FeedbackRepository.NewFeedback(userId, type, content, images, contact));
        LOG.info("Feedback created: {}", feedback.id());
        return feedback;
    }

    public FeedbackRepository.Page findPage(
            int userId, @Nullable String type, @Nullable String status, double page, double limit) {
        return repository.findPage(userId, type != null ? List.of(type) : Feedback.GENERAL_TYPES, status, page, limit);
    }

    public Feedback findOwn(int id, int userId) {
        Optional<Feedback> feedback = repository.findById(id);
        if (feedback.isEmpty()
                || !Integer.valueOf(userId).equals(feedback.get().userId())
                || !Feedback.USER_QUERYABLE_TYPES.contains(feedback.get().type())) {
            throw HttpStatusException.notFound(Map.of("error", "feedbackNotFound"));
        }
        return feedback.get();
    }

    @WriteTransaction
    public void anonymizeByUser(int userId) {
        repository.anonymizeByUser(userId);
    }
}
