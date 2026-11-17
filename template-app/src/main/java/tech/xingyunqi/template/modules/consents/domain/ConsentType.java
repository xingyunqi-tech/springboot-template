package tech.xingyunqi.template.modules.consents.domain;

public enum ConsentType {
    PRIVACY_POLICY("consent.privacyPolicyVersion"),
    USER_AGREEMENT("consent.userAgreementVersion"),
    AI_SERVICE("consent.aiServiceVersion");

    private final String versionKey;

    ConsentType(String versionKey) {
        this.versionKey = versionKey;
    }

    /** 记录该协议当前版本的配置项。 */
    public String versionKey() {
        return versionKey;
    }
}
