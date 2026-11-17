package tech.xingyunqi.template.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 当前 schema 须包含 nestjs 表结构快照（{@code db/nestjs-schema.sql}）中的每一列。 */
public final class SchemaCheck {

    static final String SNAPSHOT = "db/nestjs-schema.sql";

    private SchemaCheck() {}

    public static void verify(JdbcTemplate jdbc) {
        List<String> missing = missingColumns(jdbc);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("database schema is missing: " + String.join(", ", missing));
        }
    }

    static List<String> missingColumns(JdbcTemplate jdbc) {
        Set<String> existing = new HashSet<>(jdbc.queryForList(
                "select table_name || '.' || column_name from information_schema.columns"
                        + " where table_schema = current_schema()",
                String.class));
        return requiredColumns().stream()
                .filter(column -> !existing.contains(column))
                .sorted()
                .toList();
    }

    static List<String> requiredColumns() {
        try (InputStream in = SchemaCheck.class.getClassLoader().getResourceAsStream(SNAPSHOT)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + SNAPSHOT);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> line.startsWith("column "))
                    .map(line -> line.substring("column ".length()).split(" ", 2)[0])
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
