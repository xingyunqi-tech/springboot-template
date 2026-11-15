package tech.xingyunqi.template.common.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tech.xingyunqi.template.common.core.alert.AlertReporter;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.common.redis.throttle.RateLimiter;
import tools.jackson.databind.json.JsonMapper;

/** {@code REDIS_ENABLED} 只认字符串 {@code true}：开启时用 Redis，否则用进程内实现；生产环境必须开启。 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
public class RedisAutoConfiguration {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    public static boolean redisEnabled(Environment environment) {
        return "true".equals(environment.getProperty("REDIS_ENABLED"));
    }

    @Bean
    Cache cache(
            Environment environment,
            ObjectProvider<StringRedisTemplate> redis,
            JsonMapper mapper,
            Clock clock,
            AppMetrics metrics) {
        if (redisEnabled(environment)) {
            return new MeteredCache(new RedisCache(redis.getObject(), mapper, clock), metrics);
        }
        if ("production".equalsIgnoreCase(environment.getProperty("app.env"))) {
            throw new IllegalStateException("REDIS_ENABLED must be true in production");
        }
        return new MeteredCache(new MemoryCache(mapper, clock), metrics);
    }

    @Bean
    LockService lockService(Environment environment, ObjectProvider<DataRedisConnectionDetails> details) {
        if (!redisEnabled(environment)) {
            return LockService.local();
        }
        LettuceConnectionFactory factory = connectionFactory(details.getObject(), LockService.COMMAND_TIMEOUT);
        return new LockService(new StringRedisTemplate(factory), factory::destroy);
    }

    @Bean
    RateLimiter rateLimiter(
            Environment environment,
            ObjectProvider<DataRedisConnectionDetails> details,
            AppMetrics metrics,
            ObjectProvider<AlertReporter> alerts,
            Clock clock) {
        if (!redisEnabled(environment)) {
            return new RateLimiter(null, null, metrics, alerts::getIfAvailable, clock);
        }
        LettuceConnectionFactory factory = connectionFactory(details.getObject(), RateLimiter.COMMAND_TIMEOUT);
        return new RateLimiter(
                new StringRedisTemplate(factory), factory::destroy, metrics, alerts::getIfAvailable, clock);
    }

    /** 与主连接同一 Redis，单条命令超时为 {@code commandTimeout}；断线期间命令立即失败而不排队。 */
    public static LettuceConnectionFactory connectionFactory(
            DataRedisConnectionDetails details, Duration commandTimeout) {
        DataRedisConnectionDetails.Standalone standalone = details.getStandalone();
        if (standalone == null) {
            throw new IllegalStateException("only standalone Redis is supported");
        }
        RedisStandaloneConfiguration server =
                new RedisStandaloneConfiguration(standalone.getHost(), standalone.getPort());
        server.setDatabase(standalone.getDatabase());
        server.setUsername(details.getUsername());
        server.setPassword(RedisPassword.of(details.getPassword()));
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(CONNECT_TIMEOUT)
                                .build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
