package tech.xingyunqi.template.core.auth;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.core.permissions.RoleIds;
import tech.xingyunqi.template.core.permissions.StatusIds;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.CreatedRelations;
import tech.xingyunqi.template.core.users.domain.Patch;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.core.users.domain.UserInput;
import tech.xingyunqi.template.integrations.alipay.AlipayClient;
import tech.xingyunqi.template.integrations.sms.PhoneMask;
import tech.xingyunqi.template.integrations.wechat.WechatClient;

/** 微信（小程序 / APP）、微信手机号快捷登录与支付宝小程序登录；首次登录自动建号，外呼期间不占用数据库事务。 */
@Service
public class SocialAuthService {

    /** 微信登录方式。 */
    public enum WechatLoginType {
        MINI_APP,
        APP
    }

    private record Identity(
            @Nullable String openId, @Nullable String unionId, String phone) {}

    private static final Logger LOG = LoggerFactory.getLogger(SocialAuthService.class);

    private final AuthService auth;
    private final UsersService users;
    private final WechatClient wechat;
    private final AlipayClient alipay;

    public SocialAuthService(AuthService auth, UsersService users, WechatClient wechat, AlipayClient alipay) {
        this.auth = auth;
        this.users = users;
        this.wechat = wechat;
        this.alipay = alipay;
    }

    public AuthService.Login wechatLogin(
            String code, WechatLoginType type, @Nullable String nickname, AuthService.Client client) {
        LOG.info("WeChat login attempt with type: {}", type == WechatLoginType.MINI_APP ? "mini_app" : "app");
        WechatClient.UserInfo info =
                type == WechatLoginType.MINI_APP ? wechat.miniAppLogin(code) : wechat.appLogin(code);
        User user = users.findByWechatOpenId(info.openId()).orElse(null);
        if (user != null) {
            PhoneAuthService.ensureActive(user, "wechat");
            LOG.info("WeChat login successful for user: {}", user.id());
            return auth.login(user, null, client);
        }
        UsersService.Created created;
        try {
            created = createWechatUser(
                    info.openId(), info.unionId(), null, nickname != null ? nickname : info.nickname(), info.gender());
        } catch (RuntimeException e) {
            if (!isUniqueViolationOf(e, "user_wechatOpenId_key")) {
                throw e;
            }
            User existing = users.findByWechatOpenId(info.openId()).orElseThrow(() -> e);
            LOG.info("Concurrent WeChat registration resolved to user: {}", existing.id());
            PhoneAuthService.ensureActive(existing, "wechat");
            return auth.login(existing, null, client);
        }
        LOG.info("New WeChat user created: {}", created.user().id());
        return auth.login(created.user(), created.relations(), client);
    }

    public AuthService.Login wechatPhoneLogin(String loginCode, String phoneCode, AuthService.Client client) {
        Both<WechatClient.UserInfo, WechatClient.PhoneInfo> results =
                both(() -> wechat.miniAppLogin(loginCode), () -> wechat.getPhoneNumber(phoneCode));
        WechatClient.UserInfo info = results.first();
        WechatClient.PhoneInfo phoneInfo = results.second();
        if (!"86".equals(phoneInfo.countryCode())) {
            LOG.warn("WeChat phone login rejected - unsupported country code: {}", phoneInfo.countryCode());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", 422);
            body.put("error", "phoneRegionNotSupported");
            body.put("errors", Map.of("phone", "unsupportedRegion"));
            throw HttpStatusException.unprocessable(body);
        }
        Identity identity = new Identity(info.openId(), info.unionId(), phoneInfo.purePhoneNumber());
        LOG.info("WeChat phone login attempt for: {}", PhoneMask.mask(identity.phone()));
        Resolved resolved = resolveWechatPhoneUser(identity, true);
        if (resolved == null) {
            resolved = resolveWechatPhoneUser(identity, false);
        }
        if (resolved == null) {
            throw HttpStatusException.validationError("user", "notFound");
        }
        PhoneAuthService.ensureActive(resolved.user(), "wechat");
        LOG.info("WeChat phone login successful for user: {}", resolved.user().id());
        return auth.login(resolved.user(), resolved.created(), client);
    }

    public AuthService.Login alipayLogin(String authCode, AuthService.Client client) {
        String openId = alipay.exchangeAuthCode(authCode);
        User user = users.findByAlipayOpenId(openId).orElse(null);
        if (user == null) {
            try {
                UsersService.Created created = users.create(baseInput(
                        Patch.of("支付宝用户"), Patch.absent(), "alipay", Patch.absent(), Patch.absent(), Patch.of(openId)));
                auth.initializeNotificationSettings(created.user().id());
                LOG.info("New Alipay user created: {}", created.user().id());
                PhoneAuthService.ensureActive(created.user(), "alipay");
                return auth.login(created.user(), created.relations(), client);
            } catch (RuntimeException e) {
                if (!isUniqueViolationOf(e, "user_alipayOpenId_key")) {
                    throw e;
                }
                user = users.findByAlipayOpenId(openId).orElseThrow(() -> e);
            }
        }
        PhoneAuthService.ensureActive(user, "alipay");
        LOG.info("Alipay login successful for user: {}", user.id());
        return auth.login(user, null, client);
    }

    private record Resolved(User user, @Nullable CreatedRelations created) {}

    /** 按 openId 与手机号查找、绑定或创建用户；{@code allowRetry} 时与并发请求冲突返回 null。 */
    private @Nullable Resolved resolveWechatPhoneUser(Identity identity, boolean allowRetry) {
        User byOpenId = users.findByWechatOpenId(identity.openId()).orElse(null);
        User byPhone = users.findByPhone(identity.phone()).orElse(null);
        if (byOpenId != null && byPhone != null) {
            if (byOpenId.id() != byPhone.id()) {
                throw phoneBoundToOtherWechat(identity.phone());
            }
            return new Resolved(byOpenId, null);
        }
        if (byOpenId != null) {
            if (byOpenId.phone() != null && !byOpenId.phone().isEmpty()) {
                return new Resolved(byOpenId, null);
            }
            PhoneAuthService.ensureActive(byOpenId, "wechat");
            try {
                if (!users.bindPhoneIfEmpty(byOpenId.id(), identity.phone())) {
                    return retryOrThrow(
                            allowRetry, new IllegalStateException("Failed to bind phone to user " + byOpenId.id()));
                }
            } catch (RuntimeException e) {
                return retryOrThrow(allowRetry, e);
            }
            return users.findById(byOpenId.id())
                    .map(user -> new Resolved(user, null))
                    .orElse(null);
        }
        if (byPhone != null) {
            if (byPhone.wechatOpenId() != null && !byPhone.wechatOpenId().isEmpty()) {
                throw phoneBoundToOtherWechat(identity.phone());
            }
            PhoneAuthService.ensureActive(byPhone, "wechat");
            try {
                String openId = identity.openId();
                if (openId == null || !users.bindWechatIfEmpty(byPhone.id(), openId, identity.unionId())) {
                    return retryOrThrow(
                            allowRetry, new IllegalStateException("Failed to bind WeChat to user " + byPhone.id()));
                }
            } catch (RuntimeException e) {
                return retryOrThrow(allowRetry, e);
            }
            return users.findById(byPhone.id())
                    .map(user -> new Resolved(user, null))
                    .orElse(null);
        }
        try {
            UsersService.Created created =
                    createWechatUser(identity.openId(), identity.unionId(), identity.phone(), null, null);
            LOG.info("New WeChat phone user created: {}", created.user().id());
            return new Resolved(created.user(), created.relations());
        } catch (RuntimeException e) {
            return retryOrThrow(allowRetry, e);
        }
    }

    private UsersService.Created createWechatUser(
            @Nullable String openId,
            @Nullable String unionId,
            @Nullable String phone,
            @Nullable String nickname,
            @Nullable Integer gender) {
        UserInput input = baseInput(
                Patch.of(nickname != null ? nickname : "微信用户"),
                Patch.<Object>of(gender != null ? gender : 0),
                "wechat",
                Patch.of(openId),
                Patch.of(unionId),
                Patch.absent());
        if (phone != null) {
            input = new UserInput(
                    input.email(),
                    input.password(),
                    input.provider(),
                    input.firstName(),
                    input.lastName(),
                    Patch.of(phone),
                    input.nickname(),
                    input.gender(),
                    input.birthday(),
                    input.photo(),
                    input.role(),
                    input.status(),
                    input.wechatOpenId(),
                    input.wechatUnionId(),
                    input.alipayOpenId());
        }
        UsersService.Created created = users.create(input);
        auth.initializeNotificationSettings(created.user().id());
        return created;
    }

    private static UserInput baseInput(
            Patch<String> nickname,
            Patch<Object> gender,
            String provider,
            Patch<String> wechatOpenId,
            Patch<String> wechatUnionId,
            Patch<String> alipayOpenId) {
        return new UserInput(
                Patch.absent(),
                Patch.absent(),
                Patch.of(provider),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                nickname,
                gender,
                Patch.absent(),
                Patch.absent(),
                Patch.of(new UserInput.Ref(RoleIds.USER)),
                Patch.of(new UserInput.Ref(StatusIds.ACTIVE)),
                wechatOpenId,
                wechatUnionId,
                alipayOpenId);
    }

    private static HttpStatusException phoneBoundToOtherWechat(String phone) {
        LOG.warn("WeChat phone login rejected - phone bound to another user: {}", PhoneMask.mask(phone));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 422);
        body.put("error", "phoneBoundToOtherWechat");
        body.put("errors", Map.of("phone", "boundToOtherWechat"));
        return HttpStatusException.unprocessable(body);
    }

    private static @Nullable Resolved retryOrThrow(boolean allowRetry, RuntimeException error) {
        if (allowRetry) {
            return null;
        }
        throw error;
    }

    record Both<A, B>(A first, B second) {}

    /** 两路并行执行（带调用方 MDC），任一路失败立即抛出最先失败者的异常并中断另一路（{@code Promise.all}）。 */
    static <A, B> Both<A, B> both(Supplier<A> first, Supplier<B> second) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        CompletableFuture<Void> firstFailure = new CompletableFuture<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<A> a = CompletableFuture.supplyAsync(task(mdc, first, firstFailure), executor);
            CompletableFuture<B> b = CompletableFuture.supplyAsync(task(mdc, second, firstFailure), executor);
            CompletableFuture.anyOf(CompletableFuture.allOf(a, b), firstFailure).join();
            return new Both<>(a.join(), b.join());
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Supplier<T> task(
            @Nullable Map<String, String> mdc, Supplier<T> work, CompletableFuture<Void> firstFailure) {
        return () -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                return work.get();
            } catch (RuntimeException e) {
                firstFailure.completeExceptionally(e);
                throw e;
            } finally {
                MDC.clear();
            }
        };
    }

    /** PostgreSQL 23505 且消息中带该约束名。 */
    static boolean isUniqueViolationOf(Throwable error, String constraint) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && seen.add(current)) {
            if (current instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains("\"" + constraint + "\"")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
