package tech.xingyunqi.template.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.common.web.validation.js.ValidatorJs;
import tech.xingyunqi.template.core.permissions.PermissionService;

/** 一次性命令 {@code create-superuser}、{@code permissions-clear-cache}；返回进程退出码。 */
public final class CliCommands {

    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_USAGE = 2;
    static final int MIN_PASSWORD_LENGTH = 6;
    static final String USAGE =
            "usage: APP_ROLE=create-superuser java -jar app.jar --email=<email> (password on stdin)";

    private static final Logger LOG = LoggerFactory.getLogger(CliCommands.class);

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final PermissionService permissions;
    private final boolean redisEnabled;

    public CliCommands(
            JdbcTemplate jdbc, PasswordHasher passwords, PermissionService permissions, boolean redisEnabled) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.permissions = permissions;
        this.redisEnabled = redisEnabled;
    }

    /** {@code --email <email>}（也接受 {@code -email}、{@code =} 形式），密码取标准输入第一行。 */
    public int createSuperuser(List<String> args, InputStream stdin) {
        String email = emailArgument(args);
        if (email == null) {
            LOG.error(USAGE);
            return EXIT_USAGE;
        }
        email = email.strip().toLowerCase(Locale.ROOT);
        if (!ValidatorJs.isEmail(email, null)) {
            LOG.error("create-superuser: --email must be an email address");
            return EXIT_USAGE;
        }
        String password;
        try {
            password = firstLine(stdin);
        } catch (IOException e) {
            LOG.error("create-superuser: cannot read the password from stdin", e);
            return EXIT_ERROR;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            LOG.error("create-superuser: the password on stdin must have at least {} characters", MIN_PASSWORD_LENGTH);
            return EXIT_USAGE;
        }
        try {
            String hash = passwords.hash(Seeder.md5(password));
            jdbc.update(
                    "insert into \"user\" (\"firstName\", \"lastName\", email, password, \"roleId\", \"statusId\")"
                            + " values ('Super', 'Admin', ?, ?, 1, 1)",
                    email,
                    hash);
        } catch (DuplicateKeyException e) {
            LOG.error("create-superuser: a user with this email already exists");
            return EXIT_ERROR;
        } catch (RuntimeException e) {
            LOG.error("command failed: create-superuser", e);
            return EXIT_ERROR;
        }
        LOG.info("superuser created: {}", email);
        return EXIT_OK;
    }

    /** 删除角色权限缓存；{@code REDIS_ENABLED=false} 时各进程只在内存缓存，只输出提示。 */
    public int clearPermissionCache() {
        if (!redisEnabled) {
            LOG.warn("REDIS_ENABLED=false: each process caches role permissions in memory for up to 5 minutes");
            return EXIT_OK;
        }
        try {
            permissions.refreshCache();
        } catch (RuntimeException e) {
            LOG.error("command failed: permissions-clear-cache", e);
            return EXIT_ERROR;
        }
        LOG.info("permission cache cleared");
        return EXIT_OK;
    }

    static @Nullable String emailArgument(List<String> args) {
        String email = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String name = arg.startsWith("--") ? arg.substring(2) : arg.startsWith("-") ? arg.substring(1) : null;
            if (name == null) {
                return null;
            }
            int eq = name.indexOf('=');
            if (eq >= 0) {
                if (!"email".equals(name.substring(0, eq))) {
                    return null;
                }
                email = name.substring(eq + 1);
            } else if ("email".equals(name) && i + 1 < args.size()) {
                email = args.get(++i);
            } else {
                return null;
            }
        }
        return email;
    }

    private static String firstLine(InputStream stdin) throws IOException {
        String line = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)).readLine();
        return line == null ? "" : line;
    }
}
