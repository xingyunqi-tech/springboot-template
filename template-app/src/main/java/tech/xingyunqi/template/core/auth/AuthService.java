package tech.xingyunqi.template.core.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.mybatis.AfterCommit;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.common.mybatis.WriteTransactions;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.security.jwt.Ms;
import tech.xingyunqi.template.common.security.route.AuthPrincipal;
import tech.xingyunqi.template.common.security.route.AuthProperties;
import tech.xingyunqi.template.common.security.route.Claims;
import tech.xingyunqi.template.common.security.route.RefreshPrincipal;
import tech.xingyunqi.template.core.permissions.RoleIds;
import tech.xingyunqi.template.core.permissions.StatusIds;
import tech.xingyunqi.template.core.session.SessionService;
import tech.xingyunqi.template.core.session.domain.Session;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.CreatedRelations;
import tech.xingyunqi.template.core.users.domain.Patch;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.core.users.domain.UserInput;
import tech.xingyunqi.template.integrations.mail.MailMessage;
import tech.xingyunqi.template.integrations.mail.Mailer;
import tech.xingyunqi.template.modules.notifications.NotificationsService;
import tools.jackson.databind.node.ObjectNode;

/** 邮箱登录 / 注册、邮箱确认、找回密码、令牌刷新、个人资料、改密、注销与会话管理。 */
@Service
public class AuthService {

    /** 签发的令牌，键顺序同 nestjs。 */
    public record Tokens(String refreshToken, String token, long tokenExpires) {}

    /** 登录结果；{@code created} 为本次新建用户时响应中应输出的关联。 */
    public record Login(Tokens tokens, User user, @Nullable CreatedRelations created) {}

    /** 请求方的 IP 与 User-Agent。 */
    public record Client(@Nullable String ip, @Nullable String userAgent) {}

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthProperties properties;
    private final Jwt jwt;
    private final Clock clock;
    private final UsersService users;
    private final SessionService sessions;
    private final Mailer mailer;
    private final SmsCodeVerifier sms;
    private final WriteTransactions transactions;
    private final NotificationsService notifications;

    public AuthService(
            AuthProperties properties,
            Jwt jwt,
            Clock clock,
            UsersService users,
            SessionService sessions,
            Mailer mailer,
            SmsCodeVerifier sms,
            WriteTransactions transactions,
            NotificationsService notifications) {
        this.properties = properties;
        this.jwt = jwt;
        this.clock = clock;
        this.users = users;
        this.sessions = sessions;
        this.mailer = mailer;
        this.sms = sms;
        this.transactions = transactions;
        this.notifications = notifications;
    }

    public Login loginWithEmail(String email, String password, Client client) {
        User user =
                users.findByEmail(email).orElseThrow(() -> HttpStatusException.validationError("email", "notFound"));
        if (!users.verifyPassword(user, password)) {
            throw HttpStatusException.validationError("password", "incorrectPassword");
        }
        if (!StatusIds.allowedForAuth(user.statusId())) {
            throw HttpStatusException.validationError("email", "userNotActive");
        }
        return login(user, null, client);
    }

    /** 创建会话并签发令牌；新建用户时 access 令牌的 role 只含 id。 */
    Login login(User user, @Nullable CreatedRelations created, Client client) {
        String hash = sessionHash();
        int sessionId = sessions.create(user.id(), hash, client.userAgent(), client.ip());
        Object role =
                created == null ? rolePayload(user) : created.roleId() == null ? null : Map.of("id", created.roleId());
        return new Login(tokens(user.id(), role, sessionId, hash), user, created);
    }

    public void register(UserInput data) {
        UserInput input = data.withRole(Patch.of(new UserInput.Ref(RoleIds.USER)))
                .withStatus(Patch.of(new UserInput.Ref(StatusIds.INACTIVE)));
        User user = users.create(input).user();
        initializeNotificationSettings(user.id());
        Map<String, Object> claims = Map.of("confirmEmailUserId", user.id());
        String hash = jwt.sign(claims, properties.confirmEmailSecret(), properties.confirmEmailExpires());
        String to = data.email().value();
        if (to != null) {
            sendAfterCommit(new MailMessage("activation", to, Map.of("hash", hash)));
        }
    }

    /** 初始化默认通知设置；失败只记日志，不影响注册。 */
    void initializeNotificationSettings(int userId) {
        try {
            notifications.initializeDefaultSettings(userId);
        } catch (RuntimeException e) {
            LOG.warn("Failed to initialize notification settings for user {}", userId, e);
        }
    }

    @WriteTransaction
    public void confirmEmail(String hash) {
        ObjectNode payload = decode(hash, properties.confirmEmailSecret());
        User user = users.findById(intClaim(payload, "confirmEmailUserId"))
                .orElseThrow(() -> HttpStatusException.notFoundError("notFound"));
        if (user.statusId() == null || user.statusId() != StatusIds.INACTIVE) {
            throw HttpStatusException.validationError("hash", "alreadyConfirmed");
        }
        users.update(user.id(), UserInput.empty().withStatus(Patch.of(new UserInput.Ref(StatusIds.ACTIVE))));
    }

    @WriteTransaction
    public void confirmNewEmail(String hash) {
        ObjectNode payload = decode(hash, properties.confirmEmailSecret());
        User user = users.findById(intClaim(payload, "confirmEmailUserId"))
                .orElseThrow(() -> HttpStatusException.notFoundError("notFound"));
        UserInput data = UserInput.empty();
        if (payload.has("newEmail")) {
            data = data.withEmail(
                    Patch.of(payload.get("newEmail").isNull() ? null : Claims.jsString(payload.get("newEmail"))));
        }
        if (user.statusId() != null && user.statusId() == StatusIds.INACTIVE) {
            data = data.withStatus(Patch.of(new UserInput.Ref(StatusIds.ACTIVE)));
        }
        users.update(user.id(), data);
    }

    @WriteTransaction
    public void forgotPassword(String email) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> HttpStatusException.validationError("email", "emailNotExists"));
        long tokenExpires = clock.millis() + (long) Ms.parse(properties.forgotExpires());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("forgotUserId", user.id());
        claims.put("pwd", passwordFingerprint(user.password()));
        String hash = jwt.sign(claims, properties.forgotSecret(), properties.forgotExpires());
        sendAfterCommit(new MailMessage("reset-password", email, Map.of("hash", hash, "tokenExpires", tokenExpires)));
    }

    public void resetPassword(String hash, String password) {
        ObjectNode payload = decode(hash, properties.forgotSecret());
        User user = users.findById(intClaim(payload, "forgotUserId"))
                .orElseThrow(() -> HttpStatusException.validationError("hash", "notFound"));
        if (!passwordFingerprint(user.password()).equals(Claims.jsString(payload.get("pwd")))) {
            throw HttpStatusException.validationError("hash", "invalidHash");
        }
        if (!users.resetPassword(user, password)) {
            throw HttpStatusException.validationError("hash", "invalidHash");
        }
    }

    public Optional<User> me(AuthPrincipal current) {
        return users.findById(current.id());
    }

    public Optional<User> update(
            AuthPrincipal current, UserInput data, @Nullable String oldPassword, @Nullable String smsCode) {
        User user = users.findById(current.id())
                .orElseThrow(() -> HttpStatusException.validationError("user", "userNotFound"));
        boolean changingPassword = UsersService.truthy(data.password());
        String email = data.email().value() == null ? "" : data.email().required();
        boolean changingEmail = !email.isEmpty() && !email.equals(user.email());
        boolean oldPasswordValid = (changingPassword || changingEmail) && oldPasswordMatches(user, oldPassword);
        String passwordHash = users.passwordHashFor(user.id(), data);
        transactions.run(() -> {
            UserInput input = data;
            if (changingPassword) {
                if (oldPassword == null || oldPassword.isEmpty()) {
                    throw HttpStatusException.validationError("oldPassword", "missingOldPassword");
                }
                if (!oldPasswordValid) {
                    throw HttpStatusException.validationError("oldPassword", "incorrectOldPassword");
                }
                sessions.deleteByUser(user.id(), current.sessionId());
            }
            if (changingEmail) {
                Optional<User> existing = users.findByEmail(email);
                if (existing.isPresent() && existing.get().id() != user.id()) {
                    throw HttpStatusException.validationError("email", "emailExists");
                }
                verifyIdentity(user, oldPassword, oldPasswordValid, smsCode);
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("confirmEmailUserId", user.id());
                claims.put("newEmail", email);
                String hash = jwt.sign(claims, properties.confirmEmailSecret(), properties.confirmEmailExpires());
                sendAfterCommit(new MailMessage("confirm-new-email", email, Map.of("hash", hash)));
                input = input.withEmail(Patch.absent());
            }
            users.updateHashed(current.id(), input, passwordHash);
        });
        return users.findById(current.id());
    }

    @WriteTransaction
    public Tokens refresh(RefreshPrincipal current) {
        Session session = sessions.findActive(current.sessionId()).orElse(null);
        Integer ownerId = session == null ? null : session.userId();
        User owner = ownerId == null ? null : users.findById(ownerId).orElse(null);
        if (session == null || owner == null) {
            throw HttpStatusException.unauthorized(null);
        }
        if (!session.hash().equals(current.hash())) {
            if (!sessions.wasRecentlyRotatedFrom(session.id(), current.hash())) {
                sessions.delete(session.id());
            }
            throw HttpStatusException.unauthorized(null);
        }
        if (owner.role() == null) {
            throw HttpStatusException.unauthorized(null);
        }
        if (!StatusIds.allowedForAuth(owner.statusId())) {
            throw HttpStatusException.validationError("refresh", "userNotActive");
        }
        String hash = sessionHash();
        Tokens tokens = tokens(owner.id(), Map.of("id", owner.role().id()), session.id(), hash);
        if (!sessions.rotateHash(session.id(), current.hash(), hash)) {
            throw HttpStatusException.unauthorized(null);
        }
        return tokens;
    }

    public void deleteAccount(AuthPrincipal current, @Nullable String oldPassword, @Nullable String smsCode) {
        User user = users.findById(current.id())
                .orElseThrow(() -> HttpStatusException.validationError("user", "userNotFound"));
        verifyIdentity(user, oldPassword, oldPasswordMatches(user, oldPassword), smsCode);
        users.remove(current.id());
    }

    public void changePassword(AuthPrincipal current, String oldPassword, String newPassword) {
        User user = users.findById(current.id())
                .orElseThrow(() -> HttpStatusException.validationError("user", "userNotFound"));
        if (!users.verifyPassword(user, oldPassword)) {
            throw HttpStatusException.validationError("oldPassword", "incorrectOldPassword");
        }
        UserInput input = UserInput.empty().withPassword(Patch.of(newPassword));
        String passwordHash = users.passwordHashFor(current.id(), input);
        transactions.run(() -> {
            users.updateHashed(current.id(), input, passwordHash);
            sessions.deleteByUser(current.id(), current.sessionId());
        });
    }

    public List<Session> listSessions(AuthPrincipal current) {
        return sessions.findActiveByUser(current.id());
    }

    @WriteTransaction
    public void revokeSession(AuthPrincipal current, int sessionId) {
        if (!sessions.revokeForUser(sessionId, current.id())) {
            throw HttpStatusException.notFoundError("sessionNotFound");
        }
    }

    @WriteTransaction
    public void revokeOtherSessions(AuthPrincipal current) {
        sessions.deleteByUser(current.id(), current.sessionId());
    }

    @WriteTransaction
    public void logout(AuthPrincipal current) {
        sessions.delete(current.sessionId());
    }

    /** 有密码的用户提供了旧密码且校验通过；在事务外调用（哈希校验耗时）。 */
    private boolean oldPasswordMatches(User user, @Nullable String oldPassword) {
        return oldPassword != null && !oldPassword.isEmpty() && users.verifyPassword(user, oldPassword);
    }

    private void verifyIdentity(
            User user, @Nullable String oldPassword, boolean oldPasswordValid, @Nullable String smsCode) {
        if (user.password() != null && !user.password().isEmpty()) {
            if (oldPassword == null || oldPassword.isEmpty()) {
                throw HttpStatusException.validationError("oldPassword", "missingOldPassword");
            }
            if (!oldPasswordValid) {
                throw HttpStatusException.validationError("oldPassword", "incorrectOldPassword");
            }
            return;
        }
        if (user.phone() == null || user.phone().isEmpty()) {
            throw HttpStatusException.validationError("phone", "phoneRequiredForVerification");
        }
        if (smsCode == null || smsCode.isEmpty()) {
            throw HttpStatusException.validationError("smsCode", "missingSmsCode");
        }
        if (!sms.verifyIdentity(user.phone(), smsCode)) {
            LOG.warn("SMS verification failed for user {}", user.id());
            throw HttpStatusException.validationError("code", "invalidCode");
        }
    }

    private Tokens tokens(int userId, @Nullable Object role, int sessionId, String hash) {
        Map<String, @Nullable Object> access = new LinkedHashMap<>();
        access.put("id", userId);
        access.put("role", role);
        access.put("sessionId", sessionId);
        String token = jwt.sign(access, properties.secret(), properties.expires());
        Map<String, Object> refresh = new LinkedHashMap<>();
        refresh.put("sessionId", sessionId);
        refresh.put("hash", hash);
        String refreshToken = jwt.sign(refresh, properties.refreshSecret(), properties.refreshExpires());
        return new Tokens(refreshToken, token, clock.millis() + (long) Ms.parse(properties.expires()));
    }

    private ObjectNode decode(String hash, String secret) {
        try {
            return jwt.verify(hash, secret);
        } catch (Jwt.TokenExpiredException e) {
            throw HttpStatusException.validationError("hash", "tokenExpired");
        } catch (Jwt.InvalidTokenException e) {
            throw HttpStatusException.validationError("hash", "invalidHash");
        }
    }

    private void sendAfterCommit(MailMessage message) {
        AfterCommit.run(() -> mailer.send(message));
    }

    private static @Nullable Map<String, Object> rolePayload(User user) {
        if (user.role() == null) {
            return null;
        }
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("id", user.role().id());
        role.put("name", user.role().name());
        role.put("__entity", "RoleEntity");
        return role;
    }

    private static int intClaim(ObjectNode payload, String name) {
        Integer value = Claims.positiveInt(payload.get(name));
        return value == null ? 0 : value;
    }

    static String sessionHash() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(sha256(random));
    }

    static String passwordFingerprint(@Nullable String passwordHash) {
        byte[] digest =
                sha256((passwordHash == null ? "" : passwordHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
