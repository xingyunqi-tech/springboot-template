package tech.xingyunqi.template.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

/** Flyway 迁移后的表结构与 nestjs 快照（{@code db/nestjs-schema.sql}，scripts/schema_snapshot.py 的格式）比对。 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaSnapshotIT {

    static final Set<String> TABLES = Set.of(
            "user",
            "role",
            "status",
            "session",
            "permission",
            "role_permission",
            "file",
            "audit_logs",
            "config",
            "region",
            "notification",
            "notification_setting",
            "address",
            "favorite",
            "feedback",
            "user_consent",
            "account_cleanup",
            "admin_alert");
    static final Set<String> EXPECTED_MISSING = Set.of(
            "constraint feedback.FK_feedback_errata FOREIGN KEY (\"errataId\") REFERENCES errata(id) ON DELETE SET NULL",
            "constraint feedback.FK_feedback_question FOREIGN KEY (\"questionId\") REFERENCES question(id)"
                    + " ON DELETE SET NULL");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("迁移后的结构与 nestjs 快照一致，只缺引用本期不做的表的两个外键")
    void migratedSchemaMatchesNestjs() throws Exception {
        Set<String> expected = new HashSet<>(
                Files.readAllLines(RepoRoot.path().resolve("db/nestjs-schema.sql"), StandardCharsets.UTF_8));
        Set<String> actual = new HashSet<>(snapshot());

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        assertThat(missing).isEqualTo(new TreeSet<>(EXPECTED_MISSING));
        assertThat(extra).isEmpty();
    }

    List<String> snapshot() {
        List<String> lines = new ArrayList<>();
        jdbc.query("""
                select c.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                       pg_get_expr(d.adbin, d.adrelid), col_description(c.oid, a.attnum)
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                join pg_namespace n on n.oid = c.relnamespace
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where n.nspname = 'public' and c.relkind = 'r' and a.attnum > 0 and not a.attisdropped
                """, rs -> {
            if (TABLES.contains(rs.getString(1))) {
                String def = rs.getString(5);
                String comment = rs.getString(6);
                lines.add("column " + rs.getString(1) + "." + rs.getString(2) + " " + rs.getString(3)
                        + (rs.getBoolean(4) ? " NOT NULL" : " NULL")
                        + (def == null || def.isEmpty() ? "" : " DEFAULT " + def)
                        + (comment == null || comment.isEmpty() ? "" : " COMMENT " + pythonRepr(comment)));
            }
        });
        jdbc.query("""
                select c.relname, con.conname, pg_get_constraintdef(con.oid)
                from pg_constraint con
                join pg_class c on c.oid = con.conrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and con.contype <> 'n'
                """, rs -> {
            if (TABLES.contains(rs.getString(1))) {
                lines.add("constraint " + rs.getString(1) + "." + rs.getString(2) + " " + rs.getString(3));
            }
        });
        jdbc.query("""
                select tablename, indexname, indexdef from pg_indexes
                where schemaname = 'public' and indexname not in (select conname from pg_constraint)
                """, rs -> {
            if (TABLES.contains(rs.getString(1))) {
                lines.add("index " + rs.getString(1) + "." + rs.getString(2) + " " + rs.getString(3));
            }
        });
        jdbc.query("""
                select c.relname, obj_description(c.oid, 'pg_class')
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind = 'r'
                """, rs -> {
            String comment = rs.getString(2);
            if (TABLES.contains(rs.getString(1)) && comment != null && !comment.isEmpty()) {
                lines.add("comment " + rs.getString(1) + " " + pythonRepr(comment));
            }
        });
        return lines;
    }

    /** Python {@code repr(str)}：默认单引号，含单引号且不含双引号时用双引号。 */
    static String pythonRepr(String value) {
        char quote = value.contains("'") && !value.contains("\"") ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(quote);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c == quote) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        return out.append(quote).toString();
    }
}
