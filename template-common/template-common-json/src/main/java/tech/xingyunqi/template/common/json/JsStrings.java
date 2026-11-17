package tech.xingyunqi.template.common.json;

/** ECMAScript 字符串空白（WhiteSpace 与 LineTerminator）与 {@code String.prototype.trim}。 */
public final class JsStrings {

    public static final String WHITESPACE = "\t\n\u000B\f\r          " + "        　﻿";

    private JsStrings() {}

    public static boolean isWhitespace(char c) {
        return WHITESPACE.indexOf(c) >= 0;
    }

    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }
}
