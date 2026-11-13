package tech.xingyunqi.template.common.web.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaginationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("标准分页输出 {list, total, page, limit, totalPages}，totalPages 向上取整")
    void paginationShape() {
        assertThat(mapper.writeValueAsString(Pagination.page(List.of(1, 2), 21, 2, 10)))
                .isEqualTo("{\"list\":[1,2],\"total\":21,\"page\":2,\"limit\":10,\"totalPages\":3}");
    }

    @Test
    @DisplayName("无限滚动在本页填满时 hasNextPage 为 true")
    void infinityHasNextPageWhenFull() {
        assertThat(Pagination.infinity(List.of(1, 2), 2).hasNextPage()).isTrue();
        assertThat(Pagination.infinity(List.of(1), 2).hasNextPage()).isFalse();
        assertThat(mapper.writeValueAsString(Pagination.infinity(List.of(1), 2)))
                .isEqualTo("{\"data\":[1],\"hasNextPage\":false}");
    }
}
