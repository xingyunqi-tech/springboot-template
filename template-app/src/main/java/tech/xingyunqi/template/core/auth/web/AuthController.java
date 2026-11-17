package tech.xingyunqi.template.core.auth.web;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.common.web.http.ClientInfo;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.common.web.route.RefreshTokenAuth;
import tech.xingyunqi.template.common.web.route.Throttle;
import tech.xingyunqi.template.contract.api.AuthApi;
import tech.xingyunqi.template.contract.model.AuthAlipayLoginDto;
import tech.xingyunqi.template.contract.model.AuthChangePasswordDto;
import tech.xingyunqi.template.contract.model.AuthConfirmEmailDto;
import tech.xingyunqi.template.contract.model.AuthDeleteAccountDto;
import tech.xingyunqi.template.contract.model.AuthEmailLoginDto;
import tech.xingyunqi.template.contract.model.AuthForgotPasswordDto;
import tech.xingyunqi.template.contract.model.AuthPhoneLoginDto;
import tech.xingyunqi.template.contract.model.AuthPhoneRegisterDto;
import tech.xingyunqi.template.contract.model.AuthPhoneResetPasswordDto;
import tech.xingyunqi.template.contract.model.AuthPhoneSmsLoginDto;
import tech.xingyunqi.template.contract.model.AuthRegisterLoginDto;
import tech.xingyunqi.template.contract.model.AuthResetPasswordDto;
import tech.xingyunqi.template.contract.model.AuthSendCodeDto;
import tech.xingyunqi.template.contract.model.AuthUpdateDto;
import tech.xingyunqi.template.contract.model.AuthWechatLoginDto;
import tech.xingyunqi.template.contract.model.AuthWechatPhoneLoginDto;
import tech.xingyunqi.template.contract.model.RefreshResponseDto;
import tech.xingyunqi.template.contract.model.SessionResponseDto;
import tech.xingyunqi.template.core.auth.AuthService;
import tech.xingyunqi.template.core.auth.PhoneAuthService;
import tech.xingyunqi.template.core.auth.SocialAuthService;
import tech.xingyunqi.template.core.users.web.UserInputs;
import tech.xingyunqi.template.core.users.web.UserViews;
import tech.xingyunqi.template.integrations.sms.SmsCodeType;
import tech.xingyunqi.template.views.LoginResponseView;
import tech.xingyunqi.template.views.UserView;
import tools.jackson.databind.JsonNode;

@RestController
class AuthController implements AuthApi {

    private final AuthService auth;
    private final PhoneAuthService phone;
    private final SocialAuthService social;
    private final UserViews views;
    private final AppProperties app;

    AuthController(
            AuthService auth, PhoneAuthService phone, SocialAuthService social, UserViews views, AppProperties app) {
        this.auth = auth;
        this.phone = phone;
        this.social = social;
        this.views = views;
        this.app = app;
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 5, ttl = 60_000)
    public LoginResponseView loginWithEmail(AuthEmailLoginDto body, @Nullable Object acceptLanguage) {
        return view(auth.loginWithEmail(required(body.email()), required(body.password()), client()));
    }

    @Override
    @PublicEndpoint
    public void registerWithEmail(AuthRegisterLoginDto body, @Nullable Object acceptLanguage) {
        auth.register(UserInputs.from(validatedBody()));
    }

    @Override
    @PublicEndpoint
    public void confirmEmail(AuthConfirmEmailDto body, @Nullable Object acceptLanguage) {
        auth.confirmEmail(required(body.hash()));
    }

    @Override
    @PublicEndpoint
    public void confirmNewEmail(AuthConfirmEmailDto body, @Nullable Object acceptLanguage) {
        auth.confirmNewEmail(required(body.hash()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 3, ttl = 300_000)
    public void forgotPassword(AuthForgotPasswordDto body, @Nullable Object acceptLanguage) {
        auth.forgotPassword(required(body.email()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 5, ttl = 300_000)
    public void resetPassword(AuthResetPasswordDto body, @Nullable Object acceptLanguage) {
        auth.resetPassword(required(body.hash()), required(body.password()));
    }

    @Override
    @AuthenticatedOnly
    public @Nullable UserView getCurrentUser(@Nullable Object acceptLanguage) {
        return auth.me(AuthContext.current())
                .map(user -> views.of(user, UserViews.Group.ME))
                .orElse(null);
    }

    @Override
    @AuthenticatedOnly
    @Throttle(limit = 10, ttl = 60_000)
    public @Nullable UserView updateCurrentUser(AuthUpdateDto body, @Nullable Object acceptLanguage) {
        JsonNode node = validatedBody();
        return auth.update(
                        AuthContext.current(),
                        UserInputs.from(node),
                        UserInputs.string(node, "oldPassword"),
                        UserInputs.string(node, "smsCode"))
                .map(user -> views.of(user, UserViews.Group.ME))
                .orElse(null);
    }

    @Override
    @AuthenticatedOnly
    public void deleteCurrentUser(AuthDeleteAccountDto body, @Nullable Object acceptLanguage) {
        JsonNode node = validatedBody();
        auth.deleteAccount(
                AuthContext.current(), UserInputs.string(node, "oldPassword"), UserInputs.string(node, "smsCode"));
    }

    @Override
    @RefreshTokenAuth
    public RefreshResponseDto refreshToken(@Nullable Object acceptLanguage) {
        AuthService.Tokens tokens = auth.refresh(AuthContext.refresh());
        return new RefreshResponseDto(tokens.token(), tokens.refreshToken(), BigDecimal.valueOf(tokens.tokenExpires()));
    }

    @Override
    @AuthenticatedOnly
    public void logout(@Nullable Object acceptLanguage) {
        auth.logout(AuthContext.current());
    }

    @Override
    @AuthenticatedOnly
    @Throttle(limit = 3, ttl = 60_000)
    public void changePassword(AuthChangePasswordDto body, @Nullable Object acceptLanguage) {
        auth.changePassword(AuthContext.current(), required(body.oldPassword()), required(body.newPassword()));
    }

    @Override
    @AuthenticatedOnly
    public List<SessionResponseDto> getMySessions(@Nullable Object acceptLanguage) {
        int current = AuthContext.current().sessionId();
        return auth.listSessions(AuthContext.current()).stream()
                .map(session -> new SessionResponseDto(
                        BigDecimal.valueOf(session.id()),
                        session.userAgent(),
                        session.ip(),
                        session.createdAt(),
                        session.lastActiveAt(),
                        session.id() == current))
                .toList();
    }

    @Override
    @AuthenticatedOnly
    public void revokeMySession(BigDecimal id, @Nullable Object acceptLanguage) {
        auth.revokeSession(AuthContext.current(), id.intValueExact());
    }

    @Override
    @AuthenticatedOnly
    public void revokeOtherSessions(@Nullable Object acceptLanguage) {
        auth.revokeOtherSessions(AuthContext.current());
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 3, ttl = 60_000)
    public void sendSmsCode(AuthSendCodeDto body, @Nullable Object acceptLanguage) {
        AuthSendCodeDto.TypeEnum type = body.type();
        phone.sendCode(
                required(body.phone()), type == null ? SmsCodeType.LOGIN : SmsCodeType.fromValue(type.getValue()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 5, ttl = 60_000)
    public LoginResponseView loginWithPhone(AuthPhoneLoginDto body, @Nullable Object acceptLanguage) {
        return view(phone.loginWithPassword(required(body.phone()), required(body.password()), client()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 5, ttl = 60_000)
    public LoginResponseView loginWithSms(AuthPhoneSmsLoginDto body, @Nullable Object acceptLanguage) {
        return view(phone.loginWithSms(required(body.phone()), required(body.code()), client()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 3, ttl = 60_000)
    public LoginResponseView registerWithPhone(AuthPhoneRegisterDto body, @Nullable Object acceptLanguage) {
        return view(phone.register(
                required(body.phone()), required(body.code()), required(body.password()), body.nickname(), client()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 5, ttl = 300_000)
    public void resetPasswordByPhone(AuthPhoneResetPasswordDto body, @Nullable Object acceptLanguage) {
        phone.resetPassword(required(body.phone()), required(body.code()), required(body.password()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 10, ttl = 60_000)
    public LoginResponseView loginWithWechat(AuthWechatLoginDto body, @Nullable Object acceptLanguage) {
        SocialAuthService.WechatLoginType type = body.type() == AuthWechatLoginDto.TypeEnum.MINI_APP
                ? SocialAuthService.WechatLoginType.MINI_APP
                : SocialAuthService.WechatLoginType.APP;
        return view(social.wechatLogin(required(body.code()), type, body.nickname(), client()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 10, ttl = 60_000)
    public LoginResponseView loginWithWechatPhone(AuthWechatPhoneLoginDto body, @Nullable Object acceptLanguage) {
        return view(social.wechatPhoneLogin(required(body.loginCode()), required(body.phoneCode()), client()));
    }

    @Override
    @PublicEndpoint
    @Throttle(limit = 10, ttl = 60_000)
    public LoginResponseView loginWithAlipay(AuthAlipayLoginDto body, @Nullable Object acceptLanguage) {
        return view(social.alipayLogin(required(body.authCode()), client()));
    }

    private AuthService.Client client() {
        ClientInfo client = ClientInfo.of(CurrentRequest.get(), app.trustProxy());
        return new AuthService.Client(client.ip(), client.userAgent());
    }

    private LoginResponseView view(AuthService.Login login) {
        return new LoginResponseView(
                login.tokens().token(),
                login.tokens().refreshToken(),
                login.tokens().tokenExpires(),
                views.of(login.user(), UserViews.Group.ME, login.created()));
    }

    private JsonNode validatedBody() {
        JsonNode node = ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get());
        if (node == null) {
            throw new IllegalStateException("request body was not validated");
        }
        return node;
    }

    private static String required(@Nullable String value) {
        if (value == null) {
            throw new IllegalStateException("validated field is missing");
        }
        return value;
    }
}
