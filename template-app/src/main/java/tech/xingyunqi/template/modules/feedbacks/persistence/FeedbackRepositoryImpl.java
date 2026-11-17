package tech.xingyunqi.template.modules.feedbacks.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.common.json.JsNumbers;
import tech.xingyunqi.template.modules.feedbacks.FeedbackRepository;
import tech.xingyunqi.template.modules.feedbacks.domain.Feedback;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class FeedbackRepositoryImpl implements FeedbackRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, @Nullable Object>> OBJECT = new TypeReference<>() {};

    private final FeedbackMapper mapper;
    private final JsonMapper json;

    FeedbackRepositoryImpl(FeedbackMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Feedback create(NewFeedback data) {
        String images = data.images() == null ? null : json.writeValueAsString(data.images());
        return toDomain(mapper.insert(data.userId(), data.type(), data.content(), images, data.contact()));
    }

    @Override
    public Optional<Feedback> findById(int id) {
        return Optional.ofNullable(mapper.findById(id)).map(this::toDomain);
    }

    @Override
    public Page findPage(int userId, List<String> types, @Nullable String status, double page, double limit) {
        double skip = (page - 1) * limit;
        List<Feedback> data = mapper.findMany(userId, types, status, pgBigint(skip), pgBigint(limit)).stream()
                .map(this::toDomain)
                .toList();
        Double lazy = lazyTotal(data.size(), skip, limit);
        double total = lazy != null ? lazy : mapper.count(userId, types, status);
        return new Page(data, total);
    }

    @Override
    public void anonymizeByUser(int userId) {
        mapper.anonymizeByUser(userId);
    }

    /** 按 PostgreSQL 转 bigint 的规则四舍五入取整。 */
    static long pgBigint(double value) {
        return new BigDecimal(JsNumbers.toString(value))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** TypeORM {@code findAndCount} 免计数的总数（skip + 本页行数）；须执行计数查询时为 null。 */
    static @Nullable Double lazyTotal(int found, double skip, double limit) {
        if (found == limit || (found == 0 && skip > 0)) {
            return null;
        }
        return found + (skip > 0 ? skip : 0);
    }

    private Feedback toDomain(FeedbackMapper.Row row) {
        List<String> images = row.images() == null ? null : json.readValue(row.images(), STRINGS);
        Map<String, @Nullable Object> snapshot =
                row.messageSnapshot() == null ? null : json.readValue(row.messageSnapshot(), OBJECT);
        return new Feedback(
                row.id(),
                row.userId(),
                row.type(),
                row.content(),
                images,
                row.contact(),
                row.status(),
                row.reply(),
                row.questionId(),
                row.questionVersion(),
                row.reason(),
                row.errataId(),
                row.messageId(),
                snapshot,
                row.createdAt().toInstant(ZoneOffset.UTC),
                row.updatedAt().toInstant(ZoneOffset.UTC));
    }
}
