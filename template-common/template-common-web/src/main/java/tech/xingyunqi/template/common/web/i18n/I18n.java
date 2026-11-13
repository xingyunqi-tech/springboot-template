package tech.xingyunqi.template.common.web.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 读取 classpath {@code i18n/<lang>/<namespace>.json}（与 nestjs 相同的翻译文件），键形如 {@code common.notFound}。 */
public final class I18n {

    private static final Pattern RESOURCE = Pattern.compile(".*/i18n/([^/]+)/([^/]+)\\.json$");

    private final Map<String, Map<String, JsonNode>> translations;
    private final String fallbackLanguage;

    I18n(Map<String, Map<String, JsonNode>> translations, String fallbackLanguage) {
        this.translations = translations;
        this.fallbackLanguage = fallbackLanguage;
    }

    public static I18n load(String fallbackLanguage) {
        JsonMapper mapper = JsonMapper.builder().build();
        Map<String, Map<String, JsonNode>> translations = new TreeMap<>();
        try {
            for (Resource resource :
                    new PathMatchingResourcePatternResolver().getResources("classpath*:i18n/*/*.json")) {
                Matcher matcher = RESOURCE.matcher(String.valueOf(resource.getURL()));
                if (!matcher.matches()) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    translations
                            .computeIfAbsent(matcher.group(1), k -> new TreeMap<>())
                            .put(matcher.group(2), mapper.readTree(in));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!translations.containsKey(fallbackLanguage)) {
            throw new IllegalStateException("缺少回落语言的翻译文件：" + fallbackLanguage);
        }
        return new I18n(translations, fallbackLanguage);
    }

    public List<String> supportedLanguages() {
        return new ArrayList<>(translations.keySet());
    }

    public String fallbackLanguage() {
        return fallbackLanguage;
    }

    /** 先严格匹配再宽松匹配，均失败时回落默认语言。 */
    public String resolve(@Nullable String acceptLanguage) {
        List<String> supported = supportedLanguages();
        String picked = AcceptLanguage.pick(supported, acceptLanguage, false);
        if (picked == null) {
            picked = AcceptLanguage.pick(supported, acceptLanguage, true);
        }
        return picked == null ? fallbackLanguage : picked;
    }

    /** 当前语言缺失时回落默认语言；键不存在或译文为空都返回 null。 */
    public @Nullable String translate(String key, @Nullable String language) {
        Set<String> candidates = new LinkedHashSet<>();
        if (language != null) {
            candidates.add(language);
        }
        candidates.add(fallbackLanguage);
        for (String candidate : candidates) {
            String value = lookup(candidate, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public @Nullable String translate(String key) {
        return translate(key, RequestLanguage.current());
    }

    private @Nullable String lookup(String language, String key) {
        Map<String, JsonNode> namespaces = translations.get(language);
        if (namespaces == null) {
            return null;
        }
        String[] parts = key.split("\\.", -1);
        JsonNode node = namespaces.get(parts[0]);
        for (int i = 1; i < parts.length && node != null; i++) {
            node = node.isObject() ? node.get(parts[i]) : null;
        }
        if (node == null || !node.isString()) {
            return null;
        }
        String text = node.stringValue();
        return text.isEmpty() ? null : text;
    }
}
