package tech.xingyunqi.template.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.core.permissions.PermissionService;

class CliCommandsTest {

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PermissionService permissions = mock(PermissionService.class);

    CliCommands commands(boolean redisEnabled) {
        return new CliCommands(jdbc, new PasswordHasher(PasswordHasher.Algorithm.BCRYPT, 1), permissions, redisEnabled);
    }

    static ByteArrayInputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--email=a@b.co", "-email=a@b.co", "--email a@b.co", "-email a@b.co"})
    @DisplayName("--email 接受 -email、--email 与 = 或空格分隔的写法")
    void emailArgumentForms(String args) {
        assertThat(CliCommands.emailArgument(List.of(args.split(" ")))).isEqualTo("a@b.co");
    }

    @Test
    @DisplayName("缺少 --email、未知参数或 --email 无值时没有邮箱")
    void emailArgumentRejectsOtherArgs() {
        assertThat(CliCommands.emailArgument(List.of())).isNull();
        assertThat(CliCommands.emailArgument(List.of("--name=x"))).isNull();
        assertThat(CliCommands.emailArgument(List.of("a@b.co"))).isNull();
        assertThat(CliCommands.emailArgument(List.of("--email"))).isNull();
    }

    @Test
    @DisplayName("邮箱缺失或不合法、密码少于 6 个字符时退出码 2 且不写库")
    void usageErrors() {
        assertThat(commands(false).createSuperuser(List.of(), stdin("secret00\n")))
                .isEqualTo(CliCommands.EXIT_USAGE);
        assertThat(commands(false).createSuperuser(List.of("--email=root@localhost"), stdin("secret00\n")))
                .isEqualTo(CliCommands.EXIT_USAGE);
        assertThat(commands(false).createSuperuser(List.of("--email=root@example.com"), stdin("12345\n")))
                .isEqualTo(CliCommands.EXIT_USAGE);
        assertThat(commands(false).createSuperuser(List.of("--email=root@example.com"), stdin("")))
                .isEqualTo(CliCommands.EXIT_USAGE);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("REDIS_ENABLED=false 时清除权限缓存只输出提示，退出码 0")
    void clearCacheWithoutRedis() {
        assertThat(commands(false).clearPermissionCache()).isEqualTo(CliCommands.EXIT_OK);
        verifyNoInteractions(permissions);
    }

    @Test
    @DisplayName("Redis 不可达时清除权限缓存退出码 1")
    void clearCacheRedisUnreachable() {
        doThrow(new RedisConnectionFailureException("down")).when(permissions).refreshCache();
        assertThat(commands(true).clearPermissionCache()).isEqualTo(CliCommands.EXIT_ERROR);
        verify(permissions).refreshCache();
    }
}
