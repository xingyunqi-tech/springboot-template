package tech.xingyunqi.template.common.redis;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** {@code REDIS_ENABLED} 只认字符串 {@code true}：开启时用 Redis，否则用进程内实现；生产环境必须开启。 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
public class RedisAutoConfiguration {

    public static boolean redisEnabled(Environment environment) {
        return "true".equals(environment.getProperty("REDIS_ENABLED"));
    }

    @Bean
    Cache cache(Environment environment, ObjectProvider<StringRedisTemplate> redis, JsonMapper mapper, Clock clock) {
        if (redisEnabled(environment)) {
            return new RedisCache(redis.getObject(), mapper, clock);
        }
        if ("production".equalsIgnoreCase(environment.getProperty("app.env"))) {
            throw new IllegalStateException("REDIS_ENABLED must be true in production");
        }
        return new MemoryCache(mapper, clock);
    }
}
