package tech.xingyunqi.template.integrations.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.core.auth.SmsCodeVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 短信验证码校验：缓存 {@code sms:code:<type>:<phone>} = {@code {code, attempts, createdAt}}，最多 5 次尝试，成功或超限即删除。 */
@Component
class SmsCodes implements SmsCodeVerifier {

    static final int MAX_ATTEMPTS = 5;
    static final long CODE_EXPIRES_MS = 300_000;
    private static final Logger LOG = LoggerFactory.getLogger(SmsCodes.class);

    private final Cache cache;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    SmsCodes(Cache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    static String key(String phone, String type) {
        return "sms:code:" + type + ":" + phone;
    }

    @Override
    public boolean verifyIdentity(String phone, String code) {
        return verify(phone, code, "verify_identity");
    }

    boolean verify(String phone, String code, String type) {
        String key = key(phone, type);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return verifyUnlocked(key, code, type);
        } finally {
            lock.unlock();
            locks.remove(key, lock);
        }
    }

    private boolean verifyUnlocked(String key, String code, String type) {
        JsonNode stored = cache.get(key);
        if (!(stored instanceof ObjectNode entry) || entry.isEmpty()) {
            return false;
        }
        if (entry.path("attempts").asInt() >= MAX_ATTEMPTS) {
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
                    CODE_EXPIRES_MS - (clock.millis() - entry.path("createdAt").asLong()));
            if (remaining > 0) {
                cache.set(key, entry, remaining);
            }
            return false;
        }
        cache.delete(key);
        LOG.info("Verification code verified ({})", type);
        return true;
    }
}
