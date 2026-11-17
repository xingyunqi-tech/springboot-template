package tech.xingyunqi.template.integrations.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.common.redis.LockService;
import tech.xingyunqi.template.core.auth.SmsCodeVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 短信验证码：缓存 {@code sms:code:<type>:<phone>} = {@code {code, attempts, createdAt}}；重发间隔内拒绝再次发送（检查 + 锁
 * {@code sms:send:<type>:<phone>}）；校验最多 {@code SMS_MAX_ATTEMPTS} 次，成功或超限即删除。
 */
@Component
public class SmsCodes implements SmsCodeVerifier {

    /** 发送结果；{@code retryAfter} 为距可重发的秒数，仅重发间隔内被拒时有值。 */
    public record SendCodeResult(
            boolean success, String message, @Nullable Integer retryAfter) {}

    static final String RESEND_MESSAGE = "Please wait before requesting a new code";
    private static final Logger LOG = LoggerFactory.getLogger(SmsCodes.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Cache cache;
    private final Clock clock;
    private final LockService locks;
    private final AliyunSmsProvider provider;
    private final SmsProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> verifyLocks = new ConcurrentHashMap<>();

    SmsCodes(Cache cache, Clock clock, LockService locks, AliyunSmsProvider provider, SmsProperties properties) {
        this.cache = cache;
        this.clock = clock;
        this.locks = locks;
        this.provider = provider;
        this.properties = properties;
    }

    static String key(String phone, String type) {
        return "sms:code:" + type + ":" + phone;
    }

    public SendCodeResult send(String phone, SmsCodeType type) {
        String key = key(phone, type.value());
        long interval = properties.resendInterval();
        JsonNode existing = cache.get(key);
        if (existing instanceof ObjectNode entry && !entry.isEmpty()) {
            double elapsed = (clock.millis() - entry.path("createdAt").asLong()) / 1000.0;
            if (elapsed < interval) {
                return new SendCodeResult(false, RESEND_MESSAGE, (int) Math.ceil(interval - elapsed));
            }
        }
        String lockKey = "sms:send:" + type.value() + ":" + phone;
        String token = locks.tryAcquire(lockKey, interval * 1000);
        if (token == null) {
            return new SendCodeResult(false, RESEND_MESSAGE, (int) interval);
        }
        String code = generateCode();
        try {
            AliyunSmsProvider.SendSmsResult result = provider.sendSms(phone, Map.of("code", code));
            if (!result.success()) {
                locks.release(lockKey, token);
                LOG.error("Failed to send SMS to {}: {}", PhoneMask.mask(phone), result.message());
                String message = result.message();
                return new SendCodeResult(false, message == null ? "Failed to send verification code" : message, null);
            }
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("code", code);
            stored.put("attempts", 0);
            stored.put("createdAt", clock.millis());
            cache.set(key, stored, properties.codeExpires() * 1000L);
        } catch (RuntimeException e) {
            locks.release(lockKey, token);
            throw e;
        }
        LOG.info("Verification code sent to {} for {}", PhoneMask.mask(phone), type.value());
        return new SendCodeResult(true, "Verification code sent successfully", null);
    }

    @Override
    public boolean verifyIdentity(String phone, String code) {
        return verify(phone, code, SmsCodeType.VERIFY_IDENTITY);
    }

    public boolean verify(String phone, String code, SmsCodeType type) {
        String key = key(phone, type.value());
        ReentrantLock lock = verifyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return verifyUnlocked(key, phone, code, type);
        } finally {
            lock.unlock();
            verifyLocks.remove(key, lock);
        }
    }

    private boolean verifyUnlocked(String key, String phone, String code, SmsCodeType type) {
        JsonNode stored = cache.get(key);
        if (!(stored instanceof ObjectNode found) || found.isEmpty()) {
            return false;
        }
        ObjectNode entry = found.deepCopy();
        if (entry.path("attempts").asInt() >= properties.maxAttempts()) {
            cache.delete(key);
            return false;
        }
        String expected = entry.path("code").isString()
                ? entry.path("code").asString()
                : entry.path("code").toString();
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            entry.put("attempts", entry.path("attempts").asInt() + 1);
            long remaining = Math.max(
                    0,
                    properties.codeExpires() * 1000L
                            - (clock.millis() - entry.path("createdAt").asLong()));
            if (remaining > 0) {
                cache.set(key, entry, remaining);
            }
            return false;
        }
        cache.delete(key);
        LOG.info("Verification code verified for {} ({})", PhoneMask.mask(phone), type.value());
        return true;
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < properties.codeLength(); i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }
}
