package tech.xingyunqi.template.common.web.error;

import java.util.Map;
import java.util.regex.Pattern;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** nestjs {@code ParseIdPipe}：{@code ^\d+$} 且 1 ≤ id ≤ 2147483647，否则 400 {@code {id: invalidId}}。 */
public final class InvalidId {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final long MAX_ID = Integer.MAX_VALUE;

    private InvalidId() {}

    public static int parse(String value) {
        if (!DIGITS.matcher(value).matches()) {
            throw exception();
        }
        String trimmed = value.replaceFirst("^0+(?=\\d)", "");
        if (trimmed.length() > 10) {
            throw exception();
        }
        long id = Long.parseLong(trimmed);
        if (id < 1 || id > MAX_ID) {
            throw exception();
        }
        return (int) id;
    }

    public static HttpStatusException exception() {
        return HttpStatusException.badRequest(Map.of("status", 400, "errors", Map.of("id", "invalidId")));
    }
}
