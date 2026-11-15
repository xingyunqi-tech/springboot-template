package tech.xingyunqi.template.common.mail;

import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.HandlebarsException;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * classpath {@code mail-templates/<name>.hbs}（nestjs 原样模板）的 Handlebars 渲染：{@code {{x}}} 的转义与 handlebars.js 相同，变量缺失或为
 * null 时抛错（对应 nestjs 的 {@code strict: true}）。
 */
public final class MailTemplates {

    public static final String LOCATION = "/mail-templates";

    private final Handlebars handlebars;
    private final Map<String, Template> compiled = new ConcurrentHashMap<>();

    public MailTemplates() {
        ClassPathTemplateLoader loader = new ClassPathTemplateLoader(LOCATION, ".hbs");
        loader.setCharset(StandardCharsets.UTF_8);
        this.handlebars = new Handlebars(loader).with(EscapingStrategy.HTML_ENTITY);
        handlebars.registerHelperMissing((context, options) -> {
            throw new HandlebarsException(new IllegalArgumentException(
                    "\"" + options.helperName + "\" not defined in mail template context"));
        });
    }

    public String render(String name, Map<String, ?> context) {
        Template template = compiled.computeIfAbsent(name, this::compile);
        try {
            return template.apply(context);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Template compile(String name) {
        try {
            return handlebars.compile(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
