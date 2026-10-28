package dev.interviewcoach.identity;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Actor(UUID accountId, Set<AccountAuthority> authorities, long credentialVersion) {

    public Actor {
        Objects.requireNonNull(accountId, "accountId");
        authorities = Set.copyOf(Objects.requireNonNull(authorities, "authorities"));
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("credentialVersion must be positive");
        }
    }

    public boolean hasAuthority(AccountAuthority authority) {
        return authorities.contains(authority);
    }
}
