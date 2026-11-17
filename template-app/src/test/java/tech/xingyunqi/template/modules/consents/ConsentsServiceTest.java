package tech.xingyunqi.template.modules.consents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.json.JsCompatibleModule;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tech.xingyunqi.template.modules.config.AppConfigService;
import tech.xingyunqi.template.modules.config.ConfigEntry;
import tech.xingyunqi.template.modules.config.InMemoryConfigRepository;
import tech.xingyunqi.template.modules.consents.domain.ConsentStatus;
import tech.xingyunqi.template.modules.consents.domain.ConsentType;
import tech.xingyunqi.template.modules.consents.domain.UserConsent;
import tools.jackson.databind.json.JsonMapper;

class ConsentsServiceTest {

    static final JsonMapper JSON =
            JsonMapper.builder().addModule(new JsCompatibleModule()).build();
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 内存同意记录。 */
    static final class FakeRepository implements UserConsentRepository {
        final List<UserConsent> rows = new ArrayList<>();

        @Override
        public void insertIfAbsent(int userId, ConsentType type, String version, @Nullable String ip) {
            if (findOne(userId, type, version).isEmpty()) {
                rows.add(new UserConsent("id" + rows.size(), userId, type, version, T0.plusSeconds(rows.size()), ip));
            }
        }

        @Override
        public List<UserConsent> findLatestByUser(int userId) {
            return rows.stream()
                    .filter(row -> row.userId() == userId)
                    .collect(Collectors.groupingBy(
                            UserConsent::type, Collectors.maxBy(Comparator.comparing(UserConsent::agreedAt))))
                    .values()
                    .stream()
                    .flatMap(Optional::stream)
                    .toList();
        }

        @Override
        public boolean exists(int userId, ConsentType type, String version) {
            return findOne(userId, type, version).isPresent();
        }

        @Override
        public Optional<UserConsent> findOne(int userId, ConsentType type, String version) {
            return rows.stream()
                    .filter(row -> row.userId() == userId
                            && row.type() == type
                            && row.version().equals(version))
                    .findFirst();
        }
    }

    final InMemoryConfigRepository configs = new InMemoryConfigRepository();
    final FakeRepository repository = new FakeRepository();

    ConsentsService service() {
        return new ConsentsService(repository, new AppConfigService(configs, new MemoryCache(JSON, Clock.systemUTC())));
    }

    void version(ConsentType type, String json) {
        configs.rows.put(
                type.versionKey(),
                new ConfigEntry(type.versionKey(), JSON.readTree("{\"value\":" + json + "}"), true, T0, T0));
    }

    @Test
    @DisplayName("未配置当前版本的协议不需要同意，同意时 422 consentVersionNotCurrent")
    void unconfigured() {
        version(ConsentType.USER_AGREEMENT, "\"  \"");
        version(ConsentType.AI_SERVICE, "1");
        ConsentsService service = service();

        assertThat(service.findStatus(7))
                .extracting(ConsentStatus::type, ConsentStatus::currentVersion, ConsentStatus::needsConsent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ConsentType.PRIVACY_POLICY, null, false),
                        org.assertj.core.groups.Tuple.tuple(ConsentType.USER_AGREEMENT, null, false),
                        org.assertj.core.groups.Tuple.tuple(ConsentType.AI_SERVICE, null, false));
        assertThatThrownBy(() -> service.agree(7, ConsentType.PRIVACY_POLICY, "v1", null))
                .isInstanceOfSatisfying(HttpStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.response())
                            .isEqualTo(Map.of("status", 422, "errors", Map.of("version", "consentVersionNotCurrent")));
                });
        assertThat(service.hasAgreedAiService(7)).isFalse();
    }

    @Test
    @DisplayName("同意当前版本；重复同意保留首次记录；版本升级后需要重新同意")
    void agreeAndUpgrade() {
        version(ConsentType.PRIVACY_POLICY, "\"v1\"");
        ConsentsService service = service();

        assertThatThrownBy(() -> service.agree(7, ConsentType.PRIVACY_POLICY, "v0", "1.2.3.4"))
                .isInstanceOf(HttpStatusException.class);
        ConsentStatus first = service.agree(7, ConsentType.PRIVACY_POLICY, "v1", "1.2.3.4");
        ConsentStatus again = service.agree(7, ConsentType.PRIVACY_POLICY, "v1", "5.6.7.8");
        assertThat(again).isEqualTo(first);
        assertThat(first.agreed()).isTrue();
        assertThat(first.needsConsent()).isFalse();
        assertThat(repository.rows)
                .singleElement()
                .satisfies(row -> assertThat(row.ip()).isEqualTo("1.2.3.4"));
        assertThat(service.hasAgreedCurrent(7, ConsentType.PRIVACY_POLICY)).isTrue();

        version(ConsentType.PRIVACY_POLICY, "\"v2\"");
        ConsentStatus upgraded = service().findStatus(7).getFirst();
        assertThat(upgraded.currentVersion()).isEqualTo("v2");
        assertThat(upgraded.agreedVersion()).isEqualTo("v1");
        assertThat(upgraded.agreed()).isFalse();
        assertThat(upgraded.needsConsent()).isTrue();
    }

    @Test
    @DisplayName("最近一次同意的不是当前版本但曾同意过当前版本时仍视为已同意")
    void agreedEarlierCurrentVersion() {
        repository.rows.add(new UserConsent("a", 7, ConsentType.AI_SERVICE, "v2", T0, null));
        repository.rows.add(new UserConsent("b", 7, ConsentType.AI_SERVICE, "v3", T0.plusSeconds(60), null));
        version(ConsentType.AI_SERVICE, "\"v2\"");

        ConsentStatus status = service().findStatus(7).get(2);

        assertThat(status.agreedVersion()).isEqualTo("v3");
        assertThat(status.agreed()).isTrue();
        assertThat(status.needsConsent()).isFalse();
        assertThat(service().findStatus(8).get(2).agreed()).isFalse();
    }

    @Test
    @DisplayName("当前版本按 JavaScript trim 判空：全角空格视为空白，控制字符不视为空白")
    void versionBlankUsesJsTrim() {
        version(ConsentType.PRIVACY_POLICY, "\"\u3000\"");
        version(ConsentType.USER_AGREEMENT, "\"\\u0001\"");
        List<ConsentStatus> statuses = service().findStatus(7);

        assertThat(statuses.get(0).currentVersion()).isNull();
        assertThat(statuses.get(0).needsConsent()).isFalse();
        assertThat(statuses.get(1).currentVersion()).isEqualTo("\u0001");
        assertThat(statuses.get(1).needsConsent()).isTrue();
    }
}
