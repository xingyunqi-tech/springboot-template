package tech.xingyunqi.template.integrations.mail;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.mail.MailTemplates;
import tech.xingyunqi.template.common.mail.OutgoingMail;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.common.web.i18n.I18n;

/**
 * nestjs {@code MailService}：主题与按钮文字为 {@code common.<titleKey>} 译文，正文取各模板命名空间的 {@code text1…n}，链接为
 * {@code FRONTEND_DOMAIN} 第一个域名 + 路径 + {@code URLSearchParams} 编码的查询串，纯文本为 {@code <url> <title>}。
 */
@Component
public class MailComposer {

    record Spec(String titleKey, String urlPath, List<String> textKeys) {}

    static final Map<String, Spec> TEMPLATES = Map.of(
            "activation",
            new Spec(
                    "confirmEmail",
                    "/confirm-email",
                    List.of("confirm-email.text1", "confirm-email.text2", "confirm-email.text3")),
            "confirm-new-email",
            new Spec(
                    "confirmEmail",
                    "/confirm-new-email",
                    List.of("confirm-new-email.text1", "confirm-new-email.text2", "confirm-new-email.text3")),
            "reset-password",
            new Spec(
                    "resetPassword",
                    "/password-change",
                    List.of(
                            "reset-password.text1",
                            "reset-password.text2",
                            "reset-password.text3",
                            "reset-password.text4")));

    private final MailTemplates templates;
    private final I18n i18n;
    private final AppProperties app;

    public MailComposer(MailTemplates templates, I18n i18n, AppProperties app) {
        this.templates = templates;
        this.i18n = i18n;
        this.app = app;
    }

    public OutgoingMail compose(String template, String to, Map<String, String> data, @Nullable String lang) {
        Spec spec = TEMPLATES.get(template);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown mail template " + template);
        }
        String title = translate("common." + spec.titleKey(), lang);
        String url = url(spec.urlPath(), params(template, data));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("title", title);
        context.put("url", url);
        context.put("actionTitle", title);
        context.put("app_name", app.name());
        for (int i = 0; i < spec.textKeys().size(); i++) {
            context.put("text" + (i + 1), translate(spec.textKeys().get(i), lang));
        }
        return new OutgoingMail(to, title, url + " " + title, templates.render(template, context));
    }

    private String translate(String key, @Nullable String lang) {
        String text = i18n.translate(key, lang);
        return text == null ? key : text;
    }

    private static Map<String, String> params(String template, Map<String, String> data) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("hash", required(data, "hash"));
        if ("reset-password".equals(template)) {
            params.put("expires", required(data, "tokenExpires"));
        }
        return params;
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Mail data is missing " + key);
        }
        return value;
    }

    private String url(String path, Map<String, String> params) {
        String domains = app.frontendDomain();
        if (domains == null || domains.isBlank()) {
            throw new IllegalStateException("FRONTEND_DOMAIN is required to build mail links");
        }
        StringJoiner query = new StringJoiner("&");
        params.forEach((key, value) -> query.add(encode(key) + "=" + encode(value)));
        return domains.split(",", -1)[0].strip() + path + "?" + query;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
