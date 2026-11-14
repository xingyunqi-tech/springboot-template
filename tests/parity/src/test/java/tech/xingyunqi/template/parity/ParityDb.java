package tech.xingyunqi.template.parity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 通过 {@code docker exec ... psql} 访问两侧数据库；容器名由系统属性覆盖。 */
final class ParityDb {

    static final String JAVA = System.getProperty("PARITY_JAVA_PG_CONTAINER", "springboot-template-infra-postgres-1");
    static final String NESTJS =
            System.getProperty("PARITY_NESTJS_PG_CONTAINER", "springboot-template-nestjs-postgres-1");

    private ParityDb() {}

    static String psql(String container, String sql) {
        try {
            Process process = new ProcessBuilder(
                            "docker", "exec", container, "psql", "-U", "root", "-d", "api", "-qAtc", sql)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("psql failed: " + output);
            }
            return output.strip();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static List<String> both(String sql) {
        return List.of(psql(JAVA, sql), psql(NESTJS, sql));
    }

    /** 从两边库删除该邮箱用户及其关联数据。 */
    static void dropUser(String email) {
        String owned = "SELECT id FROM \"user\" WHERE email = '" + email + "'";
        String sql = "DELETE FROM feedback WHERE \"userId\" IN (" + owned + ");"
                + "DELETE FROM session WHERE \"userId\" IN (" + owned + ");"
                + "DELETE FROM \"user\" WHERE id IN (" + owned + ")";
        both(sql);
    }
}
