package tech.xingyunqi.template.cli;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code DB_SCHEMA_OWNER}：{@code flyway}（默认）由本服务迁移；{@code external} 时表结构归 nestjs，migrate 与 api 只校验所需列。 */
@ConfigurationProperties("app.schema")
public record SchemaProperties(String owner) {

    public static final String OWNER = "app.schema.owner";
    public static final String FLYWAY = "flyway";
    public static final String EXTERNAL = "external";

    public SchemaProperties {
        if (!FLYWAY.equals(owner) && !EXTERNAL.equals(owner)) {
            throw new IllegalArgumentException("DB_SCHEMA_OWNER must be one of flyway, external (got " + owner + ")");
        }
    }

    public boolean external() {
        return EXTERNAL.equals(owner);
    }
}
