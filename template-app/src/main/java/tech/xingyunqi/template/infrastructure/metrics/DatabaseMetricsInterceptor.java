package tech.xingyunqi.template.infrastructure.metrics;

import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;

/** MyBatis 语句执行成功后记录 {@code database_query_duration_seconds{operation, table}}，表名从 SQL 解析。 */
@Component
@Intercepts({
    @Signature(
            type = StatementHandler.class,
            method = "query",
            args = {Statement.class, ResultHandler.class}),
    @Signature(
            type = StatementHandler.class,
            method = "queryCursor",
            args = {Statement.class}),
    @Signature(
            type = StatementHandler.class,
            method = "update",
            args = {Statement.class}),
    @Signature(
            type = StatementHandler.class,
            method = "batch",
            args = {Statement.class})
})
public class DatabaseMetricsInterceptor implements Interceptor {

    static final String UNKNOWN = "unknown";
    private static final Pattern TABLE = Pattern.compile(
            "\\b(?:FROM|INTO|UPDATE|JOIN)\\s+((?:\"[^\"]+\"|[\\w$]+)(?:\\.(?:\"[^\"]+\"|[\\w$]+))?)",
            Pattern.CASE_INSENSITIVE);

    private final AppMetrics metrics;

    public DatabaseMetricsInterceptor(AppMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long started = System.nanoTime();
        Object result = invocation.proceed();
        String[] labels = classify(
                ((StatementHandler) invocation.getTarget()).getBoundSql().getSql());
        metrics.databaseQuery(labels[0], labels[1], (System.nanoTime() - started) / 1e9);
        return result;
    }

    /** 返回 {@code [operation, table]}：首个关键字大写；首个 FROM / INTO / UPDATE / JOIN 之后的表名（去掉 schema 与引号），没有则为 unknown。 */
    static String[] classify(String sql) {
        String trimmed = sql.replaceFirst("^[\\s(]+", "");
        String[] words = trimmed.split("\\s+", 2);
        String operation = words[0].isEmpty() ? "UNKNOWN" : words[0].toUpperCase(Locale.ROOT);
        Matcher matcher = TABLE.matcher(sql);
        String table = UNKNOWN;
        if (matcher.find()) {
            String name = matcher.group(1);
            int dot = name.lastIndexOf('.');
            String last = name.startsWith("\"") && name.endsWith("\"") && name.indexOf('"', 1) == name.length() - 1
                    ? name
                    : name.substring(dot + 1);
            table = last.replace("\"", "");
        }
        return new String[] {operation, table};
    }
}
