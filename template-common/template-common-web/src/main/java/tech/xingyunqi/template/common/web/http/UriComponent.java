package tech.xingyunqi.template.common.web.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** JS {@code decodeURIComponent}：非法转义或非法 UTF-8 抛 {@link IllegalArgumentException}。 */
public final class UriComponent {

    private UriComponent() {}

    public static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != '%') {
                flush(bytes, out);
                out.append(c);
                i++;
                continue;
            }
            if (i + 2 >= value.length()) {
                throw new IllegalArgumentException(value);
            }
            int high = Character.digit(value.charAt(i + 1), 16);
            int low = Character.digit(value.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException(value);
            }
            bytes.write(high * 16 + low);
            i += 3;
        }
        flush(bytes, out);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) {
            return;
        }
        try {
            out.append(StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(e);
        }
        bytes.reset();
    }
}
