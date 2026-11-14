package tech.xingyunqi.template.common.security.password;

import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 客户端传 MD5 hex，服务端存 argon2id（默认）或 bcrypt；两种格式都可验证，格式或参数与当前配置不同时返回新哈希。哈希与验证受进程内并发上限约束。 */
public final class PasswordHasher {

    public static final int BCRYPT_ROUNDS = 10;
    public static final int ARGON2_ITERATIONS = 2;
    public static final int ARGON2_MEMORY_KIB = 19_456;
    public static final int ARGON2_PARALLELISM = 1;

    private static final Pattern ARGON2_PARAMS =
            Pattern.compile("^\\$argon2id\\$v=\\d+\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$");
    private static final Pattern BCRYPT_ROUNDS_PATTERN = Pattern.compile("^\\$2[aby]?\\$(\\d{2})\\$");

    /** 存储格式。 */
    public enum Algorithm {
        ARGON2ID,
        BCRYPT;

        public static Algorithm parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** 验证结果；{@code updatedHash} 非空时调用方应写回。 */
    public record VerifyResult(boolean valid, @Nullable String updatedHash) {}

    private final Algorithm preferred;
    private final Semaphore permits;
    private final Argon2PasswordEncoder argon2 =
            new Argon2PasswordEncoder(16, 32, ARGON2_PARALLELISM, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS);
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCRYPT_ROUNDS);

    public PasswordHasher(Algorithm preferred, int concurrency) {
        this.preferred = preferred;
        this.permits = new Semaphore(concurrency, true);
    }

    public String hash(String md5Password) {
        return limited(() -> encode(md5Password));
    }

    public VerifyResult verifyMd5(String md5Password, String passwordHash) {
        return limited(() -> {
            String upper = md5Password.toUpperCase(Locale.ROOT);
            for (String candidate :
                    upper.equals(md5Password) ? new String[] {md5Password} : new String[] {md5Password, upper}) {
                if (matches(candidate, passwordHash)) {
                    return new VerifyResult(true, needsRehash(passwordHash) ? encode(candidate) : null);
                }
            }
            return new VerifyResult(false, null);
        });
    }

    private String encode(String raw) {
        String encoded = preferred == Algorithm.ARGON2ID ? argon2.encode(raw) : bcrypt.encode(raw);
        if (encoded == null) {
            throw new IllegalStateException("password encoder returned null");
        }
        return encoded;
    }

    private boolean matches(String raw, String hash) {
        try {
            if (hash.startsWith("$argon2id$")) {
                return argon2.matches(raw, hash);
            }
            if (BCRYPT_ROUNDS_PATTERN.matcher(hash).find()) {
                return bcrypt.matches(raw, hash);
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean needsRehash(String hash) {
        if (preferred == Algorithm.ARGON2ID) {
            Matcher params = ARGON2_PARAMS.matcher(hash);
            return !params.find()
                    || Integer.parseInt(params.group(1)) != ARGON2_MEMORY_KIB
                    || Integer.parseInt(params.group(2)) != ARGON2_ITERATIONS
                    || Integer.parseInt(params.group(3)) != ARGON2_PARALLELISM;
        }
        Matcher rounds = BCRYPT_ROUNDS_PATTERN.matcher(hash);
        return !rounds.find() || Integer.parseInt(rounds.group(1)) != BCRYPT_ROUNDS;
    }

    private <T> T limited(Supplier<T> task) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for password hashing", e);
        }
        try {
            return task.get();
        } finally {
            permits.release();
        }
    }
}
