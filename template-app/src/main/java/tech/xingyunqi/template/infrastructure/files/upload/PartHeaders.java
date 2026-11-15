package tech.xingyunqi.template.infrastructure.files.upload;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.fileupload2.core.MultipartInput;

/**
 * busboy {@code HeaderParser}：逐字节读取分段头。名称为 token 且以 {@code :} 结束，值内的 CR 必须紧跟 LF，以空白开头的行续接上一行。 没有任何头、格式不合法或超过 16KiB
 * 时为畸形头。名称小写，同名取第一个，值按 latin1 解码并去掉首尾空白。
 */
final class PartHeaders {

    private static final int CR = '\r';
    private static final int LF = '\n';

    private enum State {
        LINE_START,
        NAME,
        VALUE,
        VALUE_CR,
        END_CR
    }

    private PartHeaders() {}

    static Map<String, String> read(MultipartInput input, int maxSize) {
        Map<String, String> headers = new LinkedHashMap<>();
        StringBuilder name = new StringBuilder();
        StringBuilder value = new StringBuilder();
        boolean any = false;
        State state = State.LINE_START;
        int size = 0;
        while (true) {
            int c = next(input);
            if (++size > maxSize) {
                throw MultipartReceiver.malformedHeader();
            }
            switch (state) {
                case LINE_START -> {
                    if (c == CR && any) {
                        state = State.END_CR;
                    } else if ((c == ' ' || c == '\t') && any) {
                        value.append(' ');
                        state = State.VALUE;
                    } else if (c < 0x80 && Tokens.isTokenChar((char) c)) {
                        store(headers, name, value);
                        name.append((char) c);
                        state = State.NAME;
                    } else {
                        throw MultipartReceiver.malformedHeader();
                    }
                }
                case NAME -> {
                    if (c == ':') {
                        any = true;
                        state = State.VALUE;
                    } else if (c < 0x80 && Tokens.isTokenChar((char) c)) {
                        name.append((char) c);
                    } else {
                        throw MultipartReceiver.malformedHeader();
                    }
                }
                case VALUE -> {
                    if (c == CR) {
                        state = State.VALUE_CR;
                    } else {
                        value.append((char) c);
                    }
                }
                case VALUE_CR -> {
                    if (c != LF) {
                        throw MultipartReceiver.malformedHeader();
                    }
                    state = State.LINE_START;
                }
                case END_CR -> {
                    if (c != LF) {
                        throw MultipartReceiver.malformedHeader();
                    }
                    store(headers, name, value);
                    return headers;
                }
            }
        }
    }

    private static void store(Map<String, String> headers, StringBuilder name, StringBuilder value) {
        if (!name.isEmpty()) {
            headers.putIfAbsent(
                    name.toString().toLowerCase(Locale.ROOT), value.toString().strip());
        }
        name.setLength(0);
        value.setLength(0);
    }

    private static int next(MultipartInput input) {
        try {
            return input.readByte() & 0xFF;
        } catch (IOException e) {
            throw MultipartReceiver.unexpectedEnd();
        }
    }
}
