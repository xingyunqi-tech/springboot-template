package tech.xingyunqi.template.common.web.pagination;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** nestjs {@code pagination} / {@code infinityPagination}。 */
public final class Pagination {

    private Pagination() {}

    @JsonPropertyOrder({"list", "total", "page", "limit", "totalPages"})
    public record Page<T>(List<T> list, long total, int page, int limit, long totalPages) {}

    @JsonPropertyOrder({"data", "hasNextPage"})
    public record Infinity<T>(List<T> data, boolean hasNextPage) {}

    public static <T> Page<T> page(List<T> data, long total, int page, int limit) {
        return new Page<>(data, total, page, limit, (total + limit - 1) / limit);
    }

    public static <T> Infinity<T> infinity(List<T> data, int limit) {
        return new Infinity<>(data, data.size() == limit);
    }
}
