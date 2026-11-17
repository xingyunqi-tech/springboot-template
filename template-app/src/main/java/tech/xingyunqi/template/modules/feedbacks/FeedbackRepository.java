package tech.xingyunqi.template.modules.feedbacks;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.feedbacks.domain.Feedback;

public interface FeedbackRepository {

    record NewFeedback(
            int userId,
            String type,
            String content,
            @Nullable List<String> images,
            @Nullable String contact) {}

    /** 一页数据与总数（TypeORM {@code findAndCount}：本页未满时总数为 skip + 本页行数，不执行计数）。 */
    record Page(List<Feedback> data, double total) {}

    Feedback create(NewFeedback data);

    Optional<Feedback> findById(int id);

    /** 按创建时间、id 倒序；{@code page}、{@code limit} 可为小数。 */
    Page findPage(int userId, List<String> types, @Nullable String status, double page, double limit);

    /** 解除该用户的反馈归属并清空联系方式与图片。 */
    void anonymizeByUser(int userId);
}
