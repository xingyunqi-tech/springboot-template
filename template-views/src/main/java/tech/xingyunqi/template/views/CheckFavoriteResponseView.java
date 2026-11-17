package tech.xingyunqi.template.views;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;

/** nestjs {@code CheckFavoriteResponseDto}：{@code favoriteId} 为收藏的数字 id，未收藏时不输出。 */
@JsonPropertyOrder({"isFavorited", "favoriteId"})
public record CheckFavoriteResponseView(
        boolean isFavorited,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Integer favoriteId) {}
