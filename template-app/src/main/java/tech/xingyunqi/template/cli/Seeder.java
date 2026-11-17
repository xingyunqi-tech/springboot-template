package tech.xingyunqi.template.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 种子数据，与 nestjs seeds 一致：已存在的数据不覆盖，可重复执行。 */
public final class Seeder {

    static final String DEFAULT_PASSWORD = "Secret00";
    private static final int REGION_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final JsonMapper mapper;
    private final boolean production;
    private final @Nullable String adminEmail;
    private final @Nullable String adminPassword;

    public Seeder(
            JdbcTemplate jdbc,
            PasswordHasher passwords,
            JsonMapper mapper,
            boolean production,
            @Nullable String adminEmail,
            @Nullable String adminPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.mapper = mapper;
        this.production = production;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    /** 种子账号的密码哈希（MD5 后按 {@code PASSWORD_HASHER} 哈希），为 null 表示不创建该账号。 */
    public record HashedPasswords(
            @Nullable String admin, @Nullable String demo) {}

    /** 计算种子账号的密码哈希，在写库事务之外调用。 */
    public HashedPasswords hashPasswords() {
        String admin = production ? adminPassword : DEFAULT_PASSWORD;
        return new HashedPasswords(
                admin == null || admin.isEmpty() ? null : passwords.hash(md5(admin)),
                production ? null : passwords.hash(md5(DEFAULT_PASSWORD)));
    }

    public void seed() {
        seed(hashPasswords());
    }

    public void seed(HashedPasswords hashed) {
        JsonNode data = load("seed/seed.json");
        for (JsonNode role : data.path("roles")) {
            jdbc.update(
                    "insert into role (id, name) values (?, ?) on conflict do nothing",
                    role.path("id").asInt(),
                    role.path("name").asString());
        }
        for (JsonNode status : data.path("statuses")) {
            jdbc.update(
                    "insert into status (id, name) values (?, ?) on conflict do nothing",
                    status.path("id").asInt(),
                    status.path("name").asString());
        }
        for (JsonNode permission : data.path("permissions")) {
            jdbc.update(
                    "insert into permission (id, name, description, resource, action) values (?, ?, ?, ?, ?)"
                            + " on conflict do nothing",
                    permission.path("id").asString(),
                    permission.path("name").asString(),
                    permission.path("description").isNull()
                            ? null
                            : permission.path("description").asString(),
                    permission.path("resource").asString(),
                    permission.path("action").asString());
        }
        for (JsonNode row : data.path("rolePermissions")) {
            jdbc.update(
                    "insert into role_permission (role_id, permission_id) values (?, ?) on conflict do nothing",
                    row.path("roleId").asInt(),
                    row.path("permissionId").asString());
        }
        Integer regions = jdbc.queryForObject("select count(*) from region", Integer.class);
        if (regions == null || regions == 0) {
            seedRegions();
        }
        seedUsers(hashed);
        for (JsonNode row : data.path("config")) {
            jdbc.update(
                    "insert into config (key, value, \"isPublic\") values (?, ?::jsonb, ?) on conflict do nothing",
                    row.path("key").asString(),
                    mapper.writeValueAsString(row.path("value")),
                    row.path("isPublic").asBoolean());
        }
    }

    private void seedRegions() {
        List<Object[]> rows = new ArrayList<>();
        int sort = 0;
        for (JsonNode province : load("seed/region-data.json")) {
            String provinceCode = code(province);
            rows.add(new Object[] {provinceCode, province.path("name").asString(), 1, null, sort++});
            for (JsonNode city : province.path("children")) {
                String cityCode = code(city);
                rows.add(new Object[] {cityCode, city.path("name").asString(), 2, provinceCode, sort++});
                for (JsonNode district : city.path("children")) {
                    rows.add(new Object[] {code(district), district.path("name").asString(), 3, cityCode, sort++});
                }
            }
        }
        for (int start = 0; start < rows.size(); start += REGION_BATCH) {
            jdbc.batchUpdate(
                    "insert into region (code, name, level, \"parentCode\", sort) values (?, ?, ?, ?, ?)",
                    rows.subList(start, Math.min(start + REGION_BATCH, rows.size())));
        }
    }

    private void seedUsers(HashedPasswords hashed) {
        String email = production
                ? (adminEmail == null || adminEmail.isBlank()
                        ? null
                        : adminEmail.strip().toLowerCase(Locale.ROOT))
                : "admin@example.com";
        String admin = hashed.admin();
        if (countRole(1) == 0 && email != null && admin != null) {
            insertUser("Super", "Admin", email, admin, 1);
        }
        String demo = hashed.demo();
        if (countRole(2) == 0 && demo != null) {
            insertUser("John", "Doe", "john.doe@example.com", demo, 2);
        }
    }

    private int countRole(int roleId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from \"user\" where \"roleId\" = ? and \"deletedAt\" is null", Integer.class, roleId);
        return count == null ? 0 : count;
    }

    private void insertUser(String firstName, String lastName, String email, String passwordHash, int roleId) {
        jdbc.update(
                "insert into \"user\" (\"firstName\", \"lastName\", email, password, \"roleId\", \"statusId\")"
                        + " values (?, ?, ?, ?, ?, 1)",
                firstName,
                lastName,
                email,
                passwordHash,
                roleId);
    }

    private static String code(JsonNode node) {
        String code = node.path("code").asString();
        return code.length() >= 12 ? code : code + "0".repeat(12 - code.length());
    }

    private JsonNode load(String resource) {
        try (InputStream in = Seeder.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String md5(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
