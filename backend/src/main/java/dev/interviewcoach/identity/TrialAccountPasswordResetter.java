package dev.interviewcoach.identity;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!bootstrap")
final class TrialAccountPasswordResetter {

    private final JdbcAccountRepository accounts;
    private final PasswordHashes hashes;
    private final TransactionTemplate transaction;

    TrialAccountPasswordResetter(JdbcAccountRepository accounts, PasswordHashes hashes,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.hashes = hashes;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    ResetTrialAccount reset(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID is required.");
        }
        String newPassword = RandomAccountPassword.generate();
        String newHash = hashes.encode(newPassword);
        // The account row is the serialization point shared with login and later user writes.
        long newVersion = transaction.execute(status -> {
            accounts.lockById(accountId);
            StoredAccount current = accounts.findById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found."));
            if (current.credentialVersion() == Long.MAX_VALUE) {
                throw new IllegalStateException("Credential version is exhausted.");
            }
            // Hash and revocation version must become visible together, or neither does.
            accounts.updatePasswordAndVersion(accountId, newHash);
            return current.credentialVersion() + 1;
        });
        return new ResetTrialAccount(accountId, newVersion, newPassword);
    }
}
