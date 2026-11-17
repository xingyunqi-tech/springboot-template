package tech.xingyunqi.template.modules.consents;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.consents.domain.ConsentType;
import tech.xingyunqi.template.modules.consents.domain.UserConsent;

public interface UserConsentRepository {

    /** 同一用户、协议、版本已有记录时保留原记录。 */
    void insertIfAbsent(int userId, ConsentType type, String version, @Nullable String ip);

    /** 每种协议最近一次同意的记录。 */
    List<UserConsent> findLatestByUser(int userId);

    boolean exists(int userId, ConsentType type, String version);

    Optional<UserConsent> findOne(int userId, ConsentType type, String version);
}
