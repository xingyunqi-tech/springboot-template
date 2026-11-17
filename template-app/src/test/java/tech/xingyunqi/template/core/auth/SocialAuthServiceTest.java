package tech.xingyunqi.template.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.CreatedRelations;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.integrations.alipay.AlipayClient;
import tech.xingyunqi.template.integrations.wechat.WechatClient;

class SocialAuthServiceTest {

    final AuthService auth = mock(AuthService.class);
    final UsersService users = mock(UsersService.class);
    final WechatClient wechat = mock(WechatClient.class);
    final AlipayClient alipay = mock(AlipayClient.class);
    final SocialAuthService service = new SocialAuthService(auth, users, wechat, alipay);
    final AuthService.Client client = new AuthService.Client("1.1.1.1", "ua");

    static User user(int id, String phone, String wechatOpenId) {
        Instant now = Instant.EPOCH;
        return new User(
                id,
                null,
                null,
                "wechat",
                null,
                null,
                phone,
                null,
                0,
                null,
                null,
                2,
                null,
                1,
                null,
                now,
                now,
                null,
                wechatOpenId,
                null,
                null);
    }

    static DuplicateKeyException duplicate(String constraint) {
        return new DuplicateKeyException(
                "insert failed",
                new SQLException(
                        "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", "23505"));
    }

    @Test
    @DisplayName("按 SQLState 23505 与约束名识别唯一约束冲突")
    void detectsUniqueViolation() {
        assertThat(SocialAuthService.isUniqueViolationOf(duplicate("user_wechatOpenId_key"), "user_wechatOpenId_key"))
                .isTrue();
        assertThat(SocialAuthService.isUniqueViolationOf(duplicate("user_phone_key"), "user_wechatOpenId_key"))
                .isFalse();
        assertThat(SocialAuthService.isUniqueViolationOf(
                        new RuntimeException(new SQLException("x", "23503")), "user_phone_key"))
                .isFalse();
        assertThat(SocialAuthService.isUniqueViolationOf(new IllegalStateException(), "user_phone_key"))
                .isFalse();
    }

    @Test
    @DisplayName("微信首次登录并发建号冲突时改用已存在的用户登录")
    void wechatConcurrentCreate() {
        when(wechat.miniAppLogin("c")).thenReturn(new WechatClient.UserInfo("o1", null, null, null, null, null));
        User existing = user(7, null, "o1");
        when(users.findByWechatOpenId("o1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(users.create(any())).thenThrow(duplicate("user_wechatOpenId_key"));

        service.wechatLogin("c", SocialAuthService.WechatLoginType.MINI_APP, null, client);

        verify(auth).login(eq(existing), isNull(), eq(client));
    }

    @Test
    @DisplayName("微信建号遇到其它异常或冲突后仍查不到用户时抛出原异常")
    void wechatCreateErrors() {
        when(wechat.appLogin("c")).thenReturn(new WechatClient.UserInfo("o2", null, null, "n", null, 1));
        when(users.findByWechatOpenId("o2")).thenReturn(Optional.empty());
        DuplicateKeyException conflict = duplicate("user_wechatOpenId_key");
        when(users.create(any())).thenThrow(conflict);
        assertThatThrownBy(() -> service.wechatLogin("c", SocialAuthService.WechatLoginType.APP, null, client))
                .isSameAs(conflict);

        IllegalStateException other = new IllegalStateException("db down");
        doThrow(other).when(users).create(any());
        assertThatThrownBy(() -> service.wechatLogin("c", SocialAuthService.WechatLoginType.APP, null, client))
                .isSameAs(other);
    }

    @Test
    @DisplayName("支付宝首次登录并发建号冲突时改用已存在的用户登录")
    void alipayConcurrentCreate() {
        when(alipay.exchangeAuthCode("a")).thenReturn("ali-1");
        User existing = user(8, null, null);
        when(users.findByAlipayOpenId("ali-1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(users.create(any())).thenThrow(duplicate("user_alipayOpenId_key"));

        service.alipayLogin("a", client);

        verify(auth).login(eq(existing), isNull(), eq(client));
    }

    @Test
    @DisplayName("支付宝建号遇到其它约束冲突时抛出原异常")
    void alipayOtherConflict() {
        when(alipay.exchangeAuthCode("a")).thenReturn("ali-2");
        when(users.findByAlipayOpenId("ali-2")).thenReturn(Optional.empty());
        DuplicateKeyException conflict = duplicate("user_phone_key");
        when(users.create(any())).thenThrow(conflict);

        assertThatThrownBy(() -> service.alipayLogin("a", client)).isSameAs(conflict);
    }

    @Test
    @DisplayName("微信手机号登录绑定被并发抢先时重新查询一次，第二次仍失败则抛出")
    void wechatPhoneRetry() {
        when(wechat.miniAppLogin("l")).thenReturn(new WechatClient.UserInfo("o3", "u3", null, null, null, null));
        when(wechat.getPhoneNumber("p")).thenReturn(new WechatClient.PhoneInfo("13800000000", "86"));
        User withoutPhone = user(9, null, "o3");
        User bound = user(9, "13800000000", "o3");
        when(users.findByWechatOpenId("o3")).thenReturn(Optional.of(withoutPhone), Optional.of(bound));
        when(users.findByPhone("13800000000")).thenReturn(Optional.empty(), Optional.of(bound));
        when(users.bindPhoneIfEmpty(9, "13800000000")).thenReturn(false);

        service.wechatPhoneLogin("l", "p", client);
        verify(auth).login(eq(bound), isNull(), eq(client));

        when(users.findByWechatOpenId("o3")).thenReturn(Optional.of(withoutPhone));
        when(users.findByPhone("13800000000")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.wechatPhoneLogin("l", "p", client))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to bind phone to user 9");
    }

    @Test
    @DisplayName("微信手机号登录建号失败时重试，第二次查到用户后登录")
    void wechatPhoneCreateRetry() {
        when(wechat.miniAppLogin("l")).thenReturn(new WechatClient.UserInfo("o4", null, null, null, null, null));
        when(wechat.getPhoneNumber("p")).thenReturn(new WechatClient.PhoneInfo("13900000000", "86"));
        User created = user(10, "13900000000", "o4");
        when(users.findByWechatOpenId("o4")).thenReturn(Optional.empty(), Optional.of(created));
        when(users.findByPhone("13900000000")).thenReturn(Optional.empty(), Optional.of(created));
        when(users.create(any())).thenThrow(duplicate("user_wechatOpenId_key"));

        service.wechatPhoneLogin("l", "p", client);

        verify(auth).login(eq(created), isNull(), eq(client));
    }

    static Object failAfter(long millis, RuntimeException error) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw error;
    }

    @Test
    @DisplayName("微信手机号登录：两路外呼都失败时抛出先失败的一路的错误")
    void wechatPhoneLoginFirstFailureWins() {
        HttpStatusException loginError = HttpStatusException.validationError("code", "invalidCode");
        HttpStatusException phoneError = HttpStatusException.validationError("wechat", "requestFailed");

        when(wechat.miniAppLogin("l")).thenAnswer(invocation -> failAfter(300, loginError));
        when(wechat.getPhoneNumber("p")).thenThrow(phoneError);
        assertThatThrownBy(() -> service.wechatPhoneLogin("l", "p", client)).isSameAs(phoneError);

        doThrow(loginError).when(wechat).miniAppLogin("l");
        doAnswer(invocation -> failAfter(300, phoneError)).when(wechat).getPhoneNumber("p");
        assertThatThrownBy(() -> service.wechatPhoneLogin("l", "p", client)).isSameAs(loginError);
    }

    @Test
    @DisplayName("微信手机号登录：一路失败时立即返回，不等待另一路")
    void wechatPhoneLoginFailsFast() {
        HttpStatusException phoneError = HttpStatusException.validationError("wechat", "requestFailed");
        when(wechat.miniAppLogin("l")).thenAnswer(invocation -> failAfter(10_000, new IllegalStateException("slow")));
        when(wechat.getPhoneNumber("p")).thenThrow(phoneError);

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.wechatPhoneLogin("l", "p", client)).isSameAs(phoneError);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("并行外呼线程带调用方的 MDC，结束后清空")
    void bothPropagatesMdc() {
        MDC.put("request_id", "rid-1");
        try {
            SocialAuthService.Both<String, String> result =
                    SocialAuthService.both(() -> MDC.get("request_id"), () -> String.valueOf(MDC.get("request_id")));
            assertThat(result).isEqualTo(new SocialAuthService.Both<>("rid-1", "rid-1"));
        } finally {
            MDC.clear();
        }
        SocialAuthService.Both<String, String> empty =
                SocialAuthService.both(() -> String.valueOf(MDC.get("request_id")), () -> "x");
        assertThat(empty.first()).isEqualTo("null");
    }

    @Test
    @DisplayName("新建用户时把新建关联交给会话签发")
    void createdRelationsPassedToLogin() {
        when(alipay.exchangeAuthCode("a")).thenReturn("ali-3");
        when(users.findByAlipayOpenId("ali-3")).thenReturn(Optional.empty());
        User created = user(11, null, null);
        CreatedRelations relations = new CreatedRelations(2, 1, null);
        when(users.create(any())).thenReturn(new UsersService.Created(created, relations));

        service.alipayLogin("a", client);

        verify(auth).login(created, relations, client);
    }
}
