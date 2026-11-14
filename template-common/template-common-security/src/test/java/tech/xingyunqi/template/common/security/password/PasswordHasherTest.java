package tech.xingyunqi.template.common.security.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHasherTest {

    static final String MD5 = "5f4dcc3b5aa765d61d8327deb882cf99";

    final PasswordHasher argon2 = new PasswordHasher(PasswordHasher.Algorithm.ARGON2ID, 2);
    final PasswordHasher bcrypt = new PasswordHasher(PasswordHasher.Algorithm.BCRYPT, 2);

    @Test
    @DisplayName("argon2id 使用 m=19456、t=2、p=1，验证通过时不需要重哈希")
    void argon2Parameters() {
        String hash = argon2.hash(MD5);
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(argon2.verifyMd5(MD5, hash)).isEqualTo(new PasswordHasher.VerifyResult(true, null));
    }

    @Test
    @DisplayName("以大写 MD5 存储的哈希可用小写 MD5 验证通过")
    void acceptsHashOfUppercaseMd5() {
        assertThat(argon2.verifyMd5(MD5, argon2.hash(MD5.toUpperCase(Locale.ROOT)))
                        .valid())
                .isTrue();
    }

    @Test
    @DisplayName("bcrypt 哈希在 argon2id 配置下验证通过并返回新的 argon2id 哈希")
    void upgradesBcryptToArgon2() {
        String legacy = new BCryptPasswordEncoder(10).encode(MD5);
        PasswordHasher.VerifyResult result = argon2.verifyMd5(MD5, legacy);
        assertThat(result.valid()).isTrue();
        assertThat(result.updatedHash()).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    }

    @Test
    @DisplayName("argon2id 参数与当前配置不同时返回新哈希")
    void rehashesDifferentArgon2Parameters() {
        String weaker = new Argon2PasswordEncoder(16, 32, 1, 4096, 3).encode(MD5);
        assertThat(argon2.verifyMd5(MD5, weaker).updatedHash()).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    }

    @Test
    @DisplayName("bcrypt 配置下 argon2id 哈希验证通过并改写为 bcrypt；轮数不同也重哈希")
    void bcryptPreferred() {
        assertThat(bcrypt.verifyMd5(MD5, argon2.hash(MD5)).updatedHash()).startsWith("$2a$10$");
        String rounds12 = new BCryptPasswordEncoder(12).encode(MD5);
        assertThat(bcrypt.verifyMd5(MD5, rounds12).updatedHash()).startsWith("$2a$10$");
        String nestjs = new BCryptPasswordEncoder(10).encode(MD5).replaceFirst("^\\$2a\\$", "\\$2b\\$");
        assertThat(bcrypt.verifyMd5(MD5, nestjs)).isEqualTo(new PasswordHasher.VerifyResult(true, null));
    }

    @Test
    @DisplayName("密码错误或哈希格式无法识别时验证失败")
    void rejects() {
        assertThat(argon2.verifyMd5("0".repeat(32), argon2.hash(MD5)).valid()).isFalse();
        assertThat(argon2.verifyMd5(MD5, "plain").valid()).isFalse();
        assertThat(argon2.verifyMd5(MD5, "$argon2id$broken").valid()).isFalse();
    }
}
