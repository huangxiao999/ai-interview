package dev.interviewcoach.identity;

import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHashesTest {

    @Test
    void hashesUseVersionedAlgorithmAndIndependentSalt() {
        var hashes = new PasswordHashes();
        String first = hashes.encode("synthetic-password-for-test");
        String second = hashes.encode("synthetic-password-for-test");

        assertThat(first).startsWith("{pbkdf2-sha256-v1}");
        assertThat(first).isNotEqualTo(second);
        assertThat(hashes.matches("synthetic-password-for-test", first)).isTrue();
        assertThat(hashes.matches("different-synthetic-password", first)).isFalse();
        assertThatThrownBy(() -> hashes.matches("synthetic-password-for-test", "{unknown}value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actorCopiesAuthoritiesAndDoesNotImplyTrialUserFromMaintenanceRole() {
        var authorities = EnumSet.of(AccountAuthority.KNOWLEDGE_MAINTAINER);
        var actor = new Actor(UUID.randomUUID(), authorities, 1);
        authorities.add(AccountAuthority.TRIAL_USER);

        assertThat(actor.hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER)).isTrue();
        assertThat(actor.hasAuthority(AccountAuthority.TRIAL_USER)).isFalse();
        assertThatThrownBy(() -> actor.authorities().add(AccountAuthority.TRIAL_USER))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new Actor(UUID.randomUUID(), EnumSet.of(AccountAuthority.TRIAL_USER), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
