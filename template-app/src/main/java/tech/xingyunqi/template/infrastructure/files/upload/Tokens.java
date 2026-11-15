package tech.xingyunqi.template.infrastructure.files.upload;

/** RFC 7230 token 与 quoted-string 的字符类（busboy {@code TOKEN} / {@code QDTEXT}）。 */
final class Tokens {

    private static final String TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~";

    private Tokens() {}

    static boolean isTokenChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || TOKEN_SYMBOLS.indexOf(c) >= 0;
    }

    static boolean isToken(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!isTokenChar(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isQuotedTextChar(char c) {
        return c == '\t' || (c >= 0x20 && c <= 0x7E) || (c >= 0x80 && c <= 0xFF);
    }
}
