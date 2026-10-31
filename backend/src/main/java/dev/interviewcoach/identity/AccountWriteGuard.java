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
        var current = lockCurrentAccount(observed.accountId());
        if (current.credentialVersion() != observed.credentialVersion()
                || !current.authorities().equals(observed.authorities())) {
            throw new CredentialsExpiredException("Session account facts changed.");
        }
        return current;
    }

    /** 已持久接受的后台工作使用当前账号事实；不能用此能力跳过用户写请求的凭证代次比较。 */
    public Actor lockCurrentAccount(java.util.UUID accountId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Account lock requires a transaction.");
        }
        accounts.lockById(accountId);
        return accounts.findById(accountId)
                .orElseThrow(() -> new CredentialsExpiredException("Account no longer exists.")).actor();
    }
}
