package tech.xingyunqi.template.modules.consents;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.json.JsStrings;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.modules.config.AppConfigService;
import tech.xingyunqi.template.modules.consents.domain.ConsentStatus;
import tech.xingyunqi.template.modules.consents.domain.ConsentType;
import tech.xingyunqi.template.modules.consents.domain.UserConsent;
import tools.jackson.databind.JsonNode;

/** 协议同意记录：当前版本取自配置项，未配置当前版本的协议不需要同意。 */
@Service
public class ConsentsService {

    private final UserConsentRepository repository;
    private final AppConfigService configs;

    public ConsentsService(UserConsentRepository repository, AppConfigService configs) {
        this.repository = repository;
        this.configs = configs;
    }

    public List<ConsentStatus> findStatus(int userId) {
        Map<ConsentType, UserConsent> latest = new EnumMap<>(ConsentType.class);
        repository.findLatestByUser(userId).forEach(consent -> latest.put(consent.type(), consent));
        List<ConsentStatus> statuses = new ArrayList<>();
        for (ConsentType type : ConsentType.values()) {
            String current = currentVersion(type);
            UserConsent agreed = latest.get(type);
            statuses.add(status(type, current, agreed, agreedCurrent(userId, type, current, agreed)));
        }
        return statuses;
    }

    /** 只能同意当前版本；重复同意同一版本保留首次记录。 */
    @WriteTransaction
    public ConsentStatus agree(int userId, ConsentType type, String version, @Nullable String ip) {
        String current = currentVersion(type);
        if (current == null || !version.equals(current)) {
            throw HttpStatusException.validationError("version", "consentVersionNotCurrent");
        }
        repository.insertIfAbsent(userId, type, version, ip);
        return status(type, current, repository.findOne(userId, type, version).orElse(null), true);
    }

    /** 已同意该协议的当前版本；未配置当前版本时为 false。 */
    public boolean hasAgreedCurrent(int userId, ConsentType type) {
        String current = currentVersion(type);
        return current != null && repository.exists(userId, type, current);
    }

    public boolean hasAgreedAiService(int userId) {
        return hasAgreedCurrent(userId, ConsentType.AI_SERVICE);
    }

    private @Nullable String currentVersion(ConsentType type) {
        JsonNode value = configs.getValue(type.versionKey());
        return value != null
                        && value.isString()
                        && !JsStrings.trim(value.asString()).isEmpty()
                ? value.asString()
                : null;
    }

    private boolean agreedCurrent(
            int userId, ConsentType type, @Nullable String current, @Nullable UserConsent latest) {
        if (current == null || latest == null) {
            return false;
        }
        return latest.version().equals(current) || repository.exists(userId, type, current);
    }

    private static ConsentStatus status(
            ConsentType type, @Nullable String current, @Nullable UserConsent latest, boolean agreed) {
        return new ConsentStatus(
                type,
                current,
                latest == null ? null : latest.version(),
                latest == null ? null : latest.agreedAt(),
                agreed,
                current != null && !agreed);
    }
}
