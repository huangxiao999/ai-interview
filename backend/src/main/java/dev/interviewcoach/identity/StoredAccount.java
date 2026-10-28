package dev.interviewcoach.identity;

import java.util.Set;
import java.util.UUID;

record StoredAccount(UUID id, String loginName, String passwordHash,
        long credentialVersion, Set<AccountAuthority> authorities) {

    StoredAccount {
        authorities = Set.copyOf(authorities);
    }

    Actor actor() {
        return new Actor(id, authorities, credentialVersion);
    }
}
