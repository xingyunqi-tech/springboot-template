package tech.xingyunqi.template.common.observability.metrics;

import java.math.BigDecimal;
import java.util.List;

/** prom-client 的文本输出规则：数值按 JavaScript {@code String(number)}，标签值与 HELP 的转义。 */
final class PromText {

    private PromText() {}

    static String number(double value) {
        if (Double.isNaN(value)) {
            return "Nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        double magnitude = Math.abs(value);
        if (magnitude == 0 || (magnitude >= 1e-6 && magnitude < 1e21)) {
            return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
        }
        String text = Double.toString(value);
        int exponent = text.indexOf('E');
        String mantissa = text.substring(0, exponent);
        if (mantissa.endsWith(".0")) {
            mantissa = mantissa.substring(0, mantissa.length() - 2);
        }
        String power = text.substring(exponent + 1);
        return mantissa + "e" + (power.startsWith("-") ? power : "+" + power);
    }

    static String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    static String escapeHelp(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n");
    }

    static void labels(StringBuilder out, List<String> names, List<String> values) {
        if (names.isEmpty()) {
            return;
        }
        out.append('{');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(names.get(i))
                    .append("=\"")
                    .append(escapeLabelValue(values.get(i)))
                    .append('"');
        }
        out.append('}');
    }
}
