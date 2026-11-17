package tech.xingyunqi.template.integrations.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.common.redis.LockService;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SmsCodesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PHONE = "13800000000";

    static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    static final class RecordingProvider extends AliyunSmsProvider {
        final List<Map<String, String>> sent = new ArrayList<>();
        AliyunSmsProvider.SendSmsResult next = new SendSmsResult(true, "OK", "ok", null, null);
        RuntimeException failure;

        RecordingProvider() {
            super(new SmsProperties(null, null, null, null, 300, 6, null, "true", 5, 60), JSON, Clock.systemUTC());
        }

        @Override
        public SendSmsResult sendSms(String phone, Map<String, String> templateParam) {
            if (failure != null) {
                throw failure;
            }
            sent.add(templateParam);
            return next;
        }
    }

    final MutableClock clock = new MutableClock();
    final Cache cache = new MemoryCache(JSON, clock);
    final LockService locks = LockService.local();
    final RecordingProvider provider = new RecordingProvider();
    final SmsCodes codes = new SmsCodes(
            cache, clock, locks, provider, new SmsProperties(null, null, null, null, 300, 6, null, "true", 5, 60));

    String storedCode(SmsCodeType type) {
        JsonNode stored = cache.get(SmsCodes.key(PHONE, type.value()));
        assertThat(stored).isNotNull();
        return stored.path("code").asString();
    }

    @Test
    @DisplayName("发送成功后缓存 6 位数字验证码，attempts 为 0")
    void sendStoresCode() {
        SmsCodes.SendCodeResult result = codes.send(PHONE, SmsCodeType.LOGIN);

        assertThat(result).isEqualTo(new SmsCodes.SendCodeResult(true, "Verification code sent successfully", null));
        JsonNode stored = cache.get("sms:code:login:" + PHONE);
        assertThat(stored.path("code").asString()).matches("\\d{6}");
        assertThat(stored.path("attempts").asInt()).isZero();
        assertThat(stored.path("createdAt").asLong()).isEqualTo(clock.millis());
        assertThat(provider.sent)
                .containsExactly(Map.of("code", stored.path("code").asString()));
    }

    @Test
    @DisplayName("重发间隔内再次发送返回剩余秒数")
    void resendWithinInterval() {
        codes.send(PHONE, SmsCodeType.REGISTER);
        clock.millis.addAndGet(10_500);

        SmsCodes.SendCodeResult result = codes.send(PHONE, SmsCodeType.REGISTER);

        assertThat(result).isEqualTo(new SmsCodes.SendCodeResult(false, SmsCodes.RESEND_MESSAGE, 50));
        assertThat(provider.sent).hasSize(1);
    }

    @Test
    @DisplayName("发送锁被占用时 retryAfter 为完整重发间隔")
    void lockHeld() {
        locks.tryAcquire("sms:send:login:" + PHONE, 60_000);

        assertThat(codes.send(PHONE, SmsCodeType.LOGIN))
                .isEqualTo(new SmsCodes.SendCodeResult(false, SmsCodes.RESEND_MESSAGE, 60));
        assertThat(provider.sent).isEmpty();
    }

    @Test
    @DisplayName("短信发送失败时释放锁且不缓存验证码")
    void providerFailure() {
        provider.next = new AliyunSmsProvider.SendSmsResult(false, "X", "boom", null, null);
        assertThat(codes.send(PHONE, SmsCodeType.LOGIN)).isEqualTo(new SmsCodes.SendCodeResult(false, "boom", null));
        provider.next = new AliyunSmsProvider.SendSmsResult(false, "X", null, null, null);
        assertThat(codes.send(PHONE, SmsCodeType.LOGIN).message()).isEqualTo("Failed to send verification code");
        assertThat(cache.get(SmsCodes.key(PHONE, "login"))).isNull();
        assertThat(locks.tryAcquire("sms:send:login:" + PHONE, 1000)).isNotNull();
    }

    @Test
    @DisplayName("发送抛异常时释放锁并继续抛出")
    void providerThrows() {
        provider.failure = new IllegalStateException("down");
        assertThatThrownBy(() -> codes.send(PHONE, SmsCodeType.LOGIN)).hasMessage("down");
        assertThat(locks.tryAcquire("sms:send:login:" + PHONE, 1000)).isNotNull();
    }

    @Test
    @DisplayName("校验成功后删除验证码，再次校验失败")
    void verifySuccess() {
        codes.send(PHONE, SmsCodeType.LOGIN);
        String code = storedCode(SmsCodeType.LOGIN);

        assertThat(codes.verify(PHONE, code, SmsCodeType.REGISTER)).isFalse();
        assertThat(codes.verify(PHONE, code, SmsCodeType.LOGIN)).isTrue();
        assertThat(codes.verify(PHONE, code, SmsCodeType.LOGIN)).isFalse();
    }

    @Test
    @DisplayName("错误验证码累计 attempts，达到上限后删除")
    void verifyAttempts() {
        codes.send(PHONE, SmsCodeType.VERIFY_IDENTITY);
        String code = storedCode(SmsCodeType.VERIFY_IDENTITY);
        for (int i = 1; i <= 5; i++) {
            assertThat(codes.verifyIdentity(PHONE, "wrong")).isFalse();
            assertThat(cache.get(SmsCodes.key(PHONE, "verify_identity"))
                            .path("attempts")
                            .asInt())
                    .isEqualTo(i);
        }
        assertThat(codes.verifyIdentity(PHONE, code)).isFalse();
        assertThat(cache.get(SmsCodes.key(PHONE, "verify_identity"))).isNull();
    }

    @Test
    @DisplayName("验证码过期后的错误尝试不写回缓存")
    void verifyAfterExpiry() {
        codes.send(PHONE, SmsCodeType.LOGIN);
        JsonNode stored = cache.get(SmsCodes.key(PHONE, "login"));
        clock.millis.addAndGet(300_000);
        cache.set(SmsCodes.key(PHONE, "login"), stored, 1000);

        assertThat(codes.verify(PHONE, "wrong", SmsCodeType.LOGIN)).isFalse();
        assertThat(cache.get(SmsCodes.key(PHONE, "login")).path("attempts").asInt())
                .isZero();
    }

    @Test
    @DisplayName("验证码类型按取值解析")
    void codeTypes() {
        assertThat(SmsCodeType.fromValue("reset_password")).isEqualTo(SmsCodeType.RESET_PASSWORD);
        assertThatThrownBy(() -> SmsCodeType.fromValue("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
