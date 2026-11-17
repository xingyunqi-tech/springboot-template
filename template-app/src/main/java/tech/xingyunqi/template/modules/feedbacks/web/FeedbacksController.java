package tech.xingyunqi.template.modules.feedbacks.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.json.JsNumbers;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.bind.ValidatedQuery;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.contract.api.FeedbacksApi;
import tech.xingyunqi.template.contract.model.CreateFeedbackDto;
import tech.xingyunqi.template.contract.model.PaginationFeedbackResponseDto;
import tech.xingyunqi.template.modules.feedbacks.FeedbackRepository;
import tech.xingyunqi.template.modules.feedbacks.FeedbacksService;
import tech.xingyunqi.template.modules.feedbacks.domain.Feedback;
import tech.xingyunqi.template.views.FeedbackView;
import tools.jackson.databind.JsonNode;

@RestController
@AuthenticatedOnly
class FeedbacksController implements FeedbacksApi {

    static final int MAX_LIMIT = 50;

    private final FeedbacksService feedbacks;
    private final ValidatedQuery query;

    FeedbacksController(FeedbacksService feedbacks, ValidatedQuery query) {
        this.feedbacks = feedbacks;
        this.query = query;
    }

    @Override
    public FeedbackView createFeedback(CreateFeedbackDto createFeedbackDto, @Nullable Object acceptLanguage) {
        JsonNode body = ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get());
        if (body == null) {
            throw HttpStatusException.badRequest(null);
        }
        List<String> images = null;
        JsonNode imagesNode = body.path("images");
        if (imagesNode.isArray()) {
            images = new ArrayList<>();
            for (JsonNode image : imagesNode) {
                images.add(image.asString(""));
            }
        }
        JsonNode contact = body.path("contact");
        return view(feedbacks.create(
                userId(),
                body.path("type").asString(""),
                body.path("content").asString(""),
                images,
                contact.isString() ? contact.stringValue() : null));
    }

    @Override
    public PaginationFeedbackResponseDto getFeedbacks(
            @Nullable String type,
            @Nullable String status,
            @Nullable String page,
            @Nullable String limit,
            @Nullable Object acceptLanguage) {
        JsonNode params = query.validate("QueryFeedbackDto", CurrentRequest.get());
        double pageValue = params.hasNonNull("page") ? params.get("page").asDouble() : 1;
        double limitValue = params.hasNonNull("limit") ? params.get("limit").asDouble() : 10;
        if (limitValue > MAX_LIMIT) {
            limitValue = MAX_LIMIT;
        }
        FeedbackRepository.Page result =
                feedbacks.findPage(userId(), truthy(params, "type"), truthy(params, "status"), pageValue, limitValue);
        return new PaginationFeedbackResponseDto(
                result.data().stream().map(FeedbacksController::view).toList(),
                number(result.total()),
                number(pageValue),
                number(limitValue),
                number(Math.ceil(result.total() / limitValue)));
    }

    @Override
    public FeedbackView getFeedback(BigDecimal id, @Nullable Object acceptLanguage) {
        return view(feedbacks.findOwn(id.intValueExact(), userId()));
    }

    private static int userId() {
        return AuthContext.current().id();
    }

    private static @Nullable String truthy(JsonNode params, String key) {
        JsonNode value = params.path(key);
        return value.isString() && !value.stringValue().isEmpty() ? value.stringValue() : null;
    }

    /** 与 JS 数字输出一致的 BigDecimal（整数不带小数位）。 */
    static BigDecimal number(double value) {
        return new BigDecimal(JsNumbers.toString(value));
    }

    static FeedbackView view(Feedback feedback) {
        return new FeedbackView(
                feedback.id(),
                feedback.userId(),
                feedback.type(),
                feedback.content(),
                feedback.images(),
                feedback.contact(),
                feedback.status(),
                feedback.reply(),
                feedback.questionId(),
                feedback.questionVersion(),
                feedback.reason(),
                feedback.errataId(),
                feedback.messageId(),
                feedback.messageSnapshot(),
                feedback.createdAt(),
                feedback.updatedAt());
    }
}
