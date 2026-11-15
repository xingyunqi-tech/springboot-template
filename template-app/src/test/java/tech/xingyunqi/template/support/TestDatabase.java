package tech.xingyunqi.template.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tech.xingyunqi.template.cli.Seeder;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.infrastructure.queue.JobStorage;
import tools.jackson.databind.json.JsonMapper;

/** 测试共用的 Postgres / Redis 容器；模板库迁移并 seed 一次，每个 Spring 上下文克隆独立数据库。 */
public final class TestDatabase {

    public static final String POSTGRES_IMAGE = "postgres:18.6-alpine";
    public static final String REDIS_IMAGE = "redis:8.10.3-alpine";
    static final String TEMPLATE = "template_seeded";

    private static final AtomicInteger CLONES = new AtomicInteger();
    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> redis;

    private TestDatabase() {}

    public static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(
                    DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
            postgres.start();
            buildTemplate();
        }
        return postgres;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
            redis.start();
        }
        return redis;
    }

    public static String jdbcUrl(String database) {
        PostgreSQLContainer container = postgres();
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + database;
    }

    /** 从模板库克隆一个新库并返回库名。 */
    public static synchronized String cloneDatabase() {
        postgres();
        String name = "ctx_" + CLONES.incrementAndGet();
        execute("create database " + name + " template " + TEMPLATE);
        return name;
    }

    private static void buildTemplate() {
        execute("create database " + TEMPLATE);
        String url = jdbcUrl(TEMPLATE);
        Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        new Seeder(
                        new JdbcTemplate(dataSource),
                        new PasswordHasher(PasswordHasher.Algorithm.ARGON2ID, 2),
                        JsonMapper.builder().build(),
                        false,
                        null,
                        null)
                .seed();
        JobStorage.migrate(dataSource);
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl(postgres.getDatabaseName()), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
