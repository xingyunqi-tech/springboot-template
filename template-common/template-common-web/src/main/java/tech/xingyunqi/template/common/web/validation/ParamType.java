package tech.xingyunqi.template.common.web.validation;

/** ValidationPipe 的参数来源，决定「object is expected」错误的键名。 */
public enum ParamType {
    BODY("body"),
    QUERY("query");

    private final String key;

    ParamType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
