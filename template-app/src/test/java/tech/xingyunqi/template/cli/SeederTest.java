package tech.xingyunqi.template.cli;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tools.jackson.databind.json.JsonMapper;

class SeederTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordHasher passwords = new PasswordHasher(PasswordHasher.Algorithm.BCRYPT, 1);

    private Seeder seeder(boolean production, String adminEmail, String adminPassword) {
        return new Seeder(jdbc, passwords, JsonMapper.builder().build(), production, adminEmail, adminPassword);
    }

    @Test
    @DisplayName("非生产环境为管理员与演示账号都计算默认密码的哈希，且不访问数据库")
    void hashesDemoPasswordsWithoutDatabase() {
        Seeder.HashedPasswords hashed = seeder(false, null, null).hashPasswords();

        assertThat(hashed.admin()).startsWith("$2");
        assertThat(hashed.demo()).startsWith("$2");
        assertThat(passwords
                        .verifyMd5(Seeder.md5(Seeder.DEFAULT_PASSWORD), requireNonNull(hashed.admin()))
                        .valid())
                .isTrue();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("生产环境只为 SEED_ADMIN_PASSWORD 计算哈希，不创建演示账号")
    void productionHashesOnlyAdminPassword() {
        Seeder.HashedPasswords hashed =
                seeder(true, "root@example.com", "Prod-Secret1").hashPasswords();

        assertThat(passwords
                        .verifyMd5(Seeder.md5("Prod-Secret1"), requireNonNull(hashed.admin()))
                        .valid())
                .isTrue();
        assertThat(hashed.demo()).isNull();
    }

    @Test
    @DisplayName("生产环境未设置 SEED_ADMIN_PASSWORD 时不计算任何哈希")
    void productionWithoutAdminPassword() {
        Seeder.HashedPasswords hashed = seeder(true, "root@example.com", "").hashPasswords();

        assertThat(hashed.admin()).isNull();
        assertThat(hashed.demo()).isNull();
    }
}
