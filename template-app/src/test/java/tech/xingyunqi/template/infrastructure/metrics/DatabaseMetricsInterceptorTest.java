package tech.xingyunqi.template.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatabaseMetricsInterceptorTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "SELECT \"user\".id FROM \"user\" WHERE id = $1|SELECT|user",
                "insert into session (hash) values (?)|INSERT|session",
                "UPDATE public.\"user\" SET x = 1|UPDATE|user",
                "DELETE FROM notification|DELETE|notification",
                "update session set \"updatedAt\" = now() where id = ?|UPDATE|session",
                "  (select 1)|SELECT|unknown",
                "SELECT 1|SELECT|unknown"
            })
    @DisplayName("SQL 解析出操作与表名")
    void classify(String sql, String operation, String table) {
        assertThat(DatabaseMetricsInterceptor.classify(sql)).containsExactly(operation, table);
    }
}
