package tech.xingyunqi.template.core.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.core.permissions.RoleIds;
import tech.xingyunqi.template.core.permissions.StatusIds;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.Patch;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.core.users.domain.UserInput;
import tech.xingyunqi.template.integrations.sms.PhoneMask;
import tech.xingyunqi.template.integrations.sms.SmsCodeType;
import tech.xingyunqi.template.integrations.sms.SmsCodes;

/** 短信验证码发送、手机号密码 / 验证码登录、手机号注册与通过验证码重置密码。 */
@Service
public class PhoneAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneAuthService.class);

    private final AuthService auth;
    private final UsersService users;
    private final SmsCodes sms;

    public PhoneAuthService(AuthService auth, UsersService users, SmsCodes sms) {
        this.auth = auth;
        this.users = users;
        this.sms = sms;
    }

    public void sendCode(String phone, SmsCodeType type) {
        LOG.info("Send SMS code request for phone: {}, type: {}", PhoneMask.mask(phone), type.value());
        if (type == SmsCodeType.CHANGE_PHONE) {
            throw HttpStatusException.validationError("type", "unsupportedCodeType");
        }
        if (type == SmsCodeType.REGISTER && users.findByPhone(phone).isPresent()) {
            LOG.warn("Phone already registered: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("phone", "phoneAlreadyExists");
        }
        if ((type == SmsCodeType.LOGIN || type == SmsCodeType.RESET_PASSWORD || type == SmsCodeType.VERIFY_IDENTITY)
                && users.findByPhone(phone).isEmpty()) {
            LOG.warn("Phone not found: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("phone", "phoneNotFound");
        }
        SmsCodes.SendCodeResult result = sms.send(phone, type);
        if (!result.success()) {
            LOG.warn("Failed to send SMS code to {}: {}", PhoneMask.mask(phone), result.message());
            Integer retryAfter = result.retryAfter();
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("phone", retryAfter != null && retryAfter != 0 ? "rateLimited" : "sendFailed");
            if (retryAfter != null) {
                errors.put("retryAfter", retryAfter);
            }
            throw HttpStatusException.validationErrors(errors);
        }
        LOG.info("SMS code sent to: {}", PhoneMask.mask(phone));
    }

    public AuthService.Login loginWithPassword(String phone, String password, AuthService.Client client) {
        LOG.info("Phone login attempt for: {}", PhoneMask.mask(phone));
        User user = users.findByPhone(phone).orElse(null);
        if (user == null || user.password() == null || user.password().isEmpty()) {
            LOG.warn("Phone login failed - user not found or no password: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("credentials", "incorrectPhoneOrPassword");
        }
        if (!users.verifyPassword(user, password)) {
            LOG.warn("Phone login failed - incorrect password for user: {}", user.id());
            throw HttpStatusException.validationError("credentials", "incorrectPhoneOrPassword");
        }
        ensureActive(user, "phone");
        LOG.info("Phone login successful for user: {}", user.id());
        return auth.login(user, null, client);
    }

    public AuthService.Login loginWithSms(String phone, String code, AuthService.Client client) {
        LOG.info("Phone SMS login attempt for: {}", PhoneMask.mask(phone));
        verifyCode(phone, code, SmsCodeType.LOGIN);
        User user = users.findByPhone(phone).orElse(null);
        if (user == null) {
            LOG.warn("Phone SMS login failed - user not found: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("phone", "notFound");
        }
        ensureActive(user, "phone");
        LOG.info("Phone SMS login successful for user: {}", user.id());
        return auth.login(user, null, client);
    }

    public AuthService.Login register(
            String phone, String code, String password, @Nullable String nickname, AuthService.Client client) {
        LOG.info("Phone registration attempt for: {}", PhoneMask.mask(phone));
        verifyCode(phone, code, SmsCodeType.REGISTER);
        if (users.findByPhone(phone).isPresent()) {
            LOG.warn("Phone registration failed - phone already exists: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("phone", "phoneAlreadyExists");
        }
        UserInput input = new UserInput(
                Patch.absent(),
                Patch.of(password),
                Patch.of("phone"),
                Patch.absent(),
                Patch.absent(),
                Patch.of(phone),
                nickname == null ? Patch.absent() : Patch.of(nickname),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.of(new UserInput.Ref(RoleIds.USER)),
                Patch.of(new UserInput.Ref(StatusIds.ACTIVE)),
                Patch.absent(),
                Patch.absent(),
                Patch.absent());
        UsersService.Created created = users.create(input);
        LOG.info("User registered successfully via phone: {}", created.user().id());
        auth.initializeNotificationSettings(created.user().id());
        return auth.login(created.user(), created.relations(), client);
    }

    public void resetPassword(String phone, String code, String password) {
        LOG.info("Phone password reset attempt for: {}", PhoneMask.mask(phone));
        if (!sms.verify(phone, code, SmsCodeType.RESET_PASSWORD)) {
            LOG.warn("Phone password reset failed - invalid code for: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("code", "invalidCode");
        }
        User user = users.findByPhone(phone).orElse(null);
        if (user == null) {
            LOG.warn("Phone password reset failed - phone not found: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("phone", "phoneNotExists");
        }
        ensureActive(user, "phone");
        users.setPassword(user.id(), password);
        LOG.info("Phone password reset successful for user: {}", user.id());
    }

    private void verifyCode(String phone, String code, SmsCodeType type) {
        if (!sms.verify(phone, code, type)) {
            LOG.warn("SMS verification failed for: {}", PhoneMask.mask(phone));
            throw HttpStatusException.validationError("code", "invalidCode");
        }
    }

    static void ensureActive(User user, String field) {
        if (!StatusIds.allowedForAuth(user.statusId())) {
            LOG.warn("Auth failed - user status not allowed: {}, status: {}", user.id(), user.statusId());
            throw HttpStatusException.validationError(field, "userNotActive");
        }
    }
}
