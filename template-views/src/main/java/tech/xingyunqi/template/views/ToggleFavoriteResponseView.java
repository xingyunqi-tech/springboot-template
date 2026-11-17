package tech.xingyunqi.template.views;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;

/** {@code toggleFavorite} 的响应：取消收藏时只有 {@code isFavorited}。 */
@JsonPropertyOrder({"isFavorited", "favorite"})
public record ToggleFavoriteResponseView(
        boolean isFavorited,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Object favorite) {}
