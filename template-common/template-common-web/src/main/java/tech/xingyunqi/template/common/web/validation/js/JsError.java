package tech.xingyunqi.template.common.web.validation.js;

/** 复刻的 JS 代码在运行时抛出的错误，例如 TypeError 或 URIError。 */
public final class JsError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsError(String message) {
        super(message);
    }
}
