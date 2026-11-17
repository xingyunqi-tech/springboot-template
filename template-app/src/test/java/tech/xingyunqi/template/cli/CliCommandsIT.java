package tech.xingyunqi.template.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest(properties = {"REDIS_ENABLED=true", "DB_SCHEMA_OWNER=external"})
@Import(TestcontainersConfiguration.class)
class CliCommandsIT {

    static final String CACHE_KEY = "cache::permissions:v2:role-map";

    @Autowired
    CliCommands commands;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordHasher passwords;

    @Autowired
    StringRedisTemplate redis;

    static ByteArrayInputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("create-superuser 新建启用状态的管理员，邮箱转小写，密码按 MD5 后哈希；邮箱已存在时退出码 1")
    void createSuperuser() {
        assertThat(commands.createSuperuser(List.of("--email", " Root@Example.com "), stdin("Passw0rd\r\nignored\n")))
                .isEqualTo(CliCommands.EXIT_OK);

        Map<String, Object> row = jdbc.queryForMap(
                "select \"firstName\", \"lastName\", \"roleId\", \"statusId\", provider, password from \"user\""
                        + " where email = 'root@example.com'");
        assertThat(row)
                .containsEntry("firstName", "Super")
                .containsEntry("lastName", "Admin")
                .containsEntry("roleId", 1)
                .containsEntry("statusId", 1)
                .containsEntry("provider", "email");
        assertThat(passwords
                        .verifyMd5(Seeder.md5("Passw0rd"), (String) row.get("password"))
                        .valid())
                .isTrue();

        assertThat(commands.createSuperuser(List.of("--email=root@example.com"), stdin("Passw0rd\n")))
                .isEqualTo(CliCommands.EXIT_ERROR);
        assertThat(jdbc.queryForObject("select count(*) from \"user\" where email = 'root@example.com'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("permissions-clear-cache 删除 Redis 中的角色权限缓存")
    void clearPermissionCache() {
        redis.opsForValue().set(CACHE_KEY, "{\"value\":{},\"expires\":0}");
        assertThat(commands.clearPermissionCache()).isEqualTo(CliCommands.EXIT_OK);
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
    }

    @Test
    @DisplayName("DB_SCHEMA_OWNER=external 时 api 启动校验通过，缺列时报告缺少的列")
    void externalSchemaCheck() {
        assertThat(SchemaCheck.missingColumns(jdbc)).isEmpty();
        jdbc.execute("alter table address drop column \"cityCode\"");
        try {
            assertThat(SchemaCheck.missingColumns(jdbc)).containsExactly("address.cityCode");
        } finally {
            jdbc.execute("alter table address add column \"cityCode\" character varying(12)");
        }
    }
}
