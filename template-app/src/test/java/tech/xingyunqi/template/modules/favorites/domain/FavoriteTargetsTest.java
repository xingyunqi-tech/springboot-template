package tech.xingyunqi.template.modules.favorites.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FavoriteTargetsTest {

    @Test
    void examTargetUuidsAreLowercased() {
        String id = "00000000-0000-4000-8000-00000000000A";
        assertThat(FavoriteTargets.normalizeTargetId("question", id)).isEqualTo(id.toLowerCase());
        assertThat(FavoriteTargets.normalizeTargetId("paper", id)).isEqualTo(id.toLowerCase());
        assertThat(FavoriteTargets.normalizeTargetId("product", id)).isEqualTo(id);
        assertThat(FavoriteTargets.normalizeTargetId("paper", "P1")).isEqualTo("P1");
    }

    @Test
    void uuidFollowsValidatorAllVersions() {
        assertThat(FavoriteTargets.isUuid("00000000-0000-0000-0000-000000000000"))
                .isTrue();
        assertThat(FavoriteTargets.isUuid("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"))
                .isTrue();
        assertThat(FavoriteTargets.isUuid("00000000-0000-9000-8000-000000000000"))
                .isFalse();
        assertThat(FavoriteTargets.isUuid("00000000-0000-4000-c000-000000000000"))
                .isFalse();
    }
}
