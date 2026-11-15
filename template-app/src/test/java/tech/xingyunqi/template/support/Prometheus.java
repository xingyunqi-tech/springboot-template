package tech.xingyunqi.template.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Prometheus 文本格式的解析：{@code # TYPE} 声明与按标签查找样本值。 */
public final class Prometheus {

    private static final Pattern TYPE = Pattern.compile("(?m)^# TYPE (\\S+) (\\S+)$");
    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private Prometheus() {}

    public static Map<String, String> types(String text) {
        Map<String, String> types = new LinkedHashMap<>();
        Matcher matcher = TYPE.matcher(text);
        while (matcher.find()) {
            types.put(matcher.group(1), matcher.group(2));
        }
        return types;
    }

    /** 名称为 {@code name} 且包含全部 {@code labels}（键值交替）的第一个样本值。 */
    public static @Nullable Double sample(String text, String name, String... labels) {
        for (String line : text.split("\n")) {
            if (!line.startsWith(name + "{")) {
                continue;
            }
            Map<String, String> found = new LinkedHashMap<>();
            Matcher matcher = LABEL.matcher(line.substring(0, line.lastIndexOf('}')));
            while (matcher.find()) {
                found.put(matcher.group(1), matcher.group(2));
            }
            boolean matches = true;
            for (int i = 0; i < labels.length; i += 2) {
                matches &= labels[i + 1].equals(found.get(labels[i]));
            }
            if (matches) {
                return Double.valueOf(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return null;
    }
}
