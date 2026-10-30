package dev.interviewcoach.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Rechecks session facts while holding the account lock shared with password reset. */
@Component
@Profile("!bootstrap")
public final class AccountWriteGuard {

    private final JdbcAccountRepository accounts;

    AccountWriteGuard(JdbcAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Call only inside the caller's write transaction, before any business mutation. */
    public Actor requireCurrent(Actor observed) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Account write verification requires a transaction.");
        }
        accounts.lockById(observed.accountId());
        var current = accounts.findById(observed.accountId())
                .orElseThrow(() -> new CredentialsExpiredException("Session account no longer exists."));
        if (current.credentialVersion() != observed.credentialVersion()
                || !current.authorities().equals(observed.authorities())) {
            throw new CredentialsExpiredException("Session account facts changed.");
        }
        return current.actor();
    }
}
