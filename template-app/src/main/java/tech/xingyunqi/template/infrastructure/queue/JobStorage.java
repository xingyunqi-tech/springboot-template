package tech.xingyunqi.template.infrastructure.queue;

import javax.sql.DataSource;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.common.DatabaseCreator;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** JobRunr 的表放在独立 schema {@value #SCHEMA}。 */
public final class JobStorage {

    public static final String SCHEMA = "jobrunr";
    public static final String TABLE_PREFIX = SCHEMA + ".";

    private JobStorage() {}

    /** 建 schema 并执行 JobRunr 迁移（{@code APP_ROLE=migrate} 与测试模板库）。 */
    public static void migrate(DataSource dataSource) {
        new JdbcTemplate(dataSource).execute("create schema if not exists " + SCHEMA);
        new DatabaseCreator(dataSource, TABLE_PREFIX, PostgresStorageProvider.class).runMigrations();
    }

    static PostgresStorageProvider open(DataSource dataSource, boolean skipCreate) {
        if (!skipCreate) {
            migrate(dataSource);
        }
        return new PostgresStorageProvider(dataSource, TABLE_PREFIX, DatabaseOptions.NO_VALIDATE);
    }
}
