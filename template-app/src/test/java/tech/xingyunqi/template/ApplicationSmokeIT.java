package tech.xingyunqi.template;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationSmokeIT {

    @Test
    @DisplayName("应用上下文启动并连通 Postgres 与 Redis")
    void contextConnectsToPostgresAndRedis(@Autowired JdbcTemplate jdbc, @Autowired StringRedisTemplate redis) {
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int / 10000", Integer.class))
                .isEqualTo(18);

        redis.opsForValue().set("smoke", "ok");
        assertThat(redis.opsForValue().getAndDelete("smoke")).isEqualTo("ok");
    }
}
