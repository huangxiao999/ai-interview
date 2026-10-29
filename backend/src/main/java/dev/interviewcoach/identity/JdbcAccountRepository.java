package dev.interviewcoach.identity;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!bootstrap")
class JdbcAccountRepository {

    private final JdbcTemplate jdbc;

    JdbcAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredAccount> findByLoginName(String loginName) {
        var rows = jdbc.query("""
                SELECT id, login_name, password_hash, credential_version
                FROM identity_account WHERE login_name = ?
                """, (result, rowNumber) -> new AccountRow(
                result.getObject("id", UUID.class), result.getString("login_name"),
                result.getString("password_hash"), result.getLong("credential_version")), loginName);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        AccountRow row = rows.getFirst();
        return Optional.of(new StoredAccount(row.id(), row.loginName(), row.passwordHash(),
                row.credentialVersion(), findAuthorities(row.id())));
    }

    Optional<StoredAccount> findById(UUID accountId) {
        var rows = jdbc.query("""
                SELECT id, login_name, password_hash, credential_version
                FROM identity_account WHERE id = ?
                """, (result, rowNumber) -> new AccountRow(
                result.getObject("id", UUID.class), result.getString("login_name"),
                result.getString("password_hash"), result.getLong("credential_version")), accountId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        AccountRow row = rows.getFirst();
        return Optional.of(new StoredAccount(row.id(), row.loginName(), row.passwordHash(),
                row.credentialVersion(), findAuthorities(row.id())));
    }

    void lockById(UUID accountId) {
        jdbc.queryForList("SELECT id FROM identity_account WHERE id = ? FOR UPDATE", UUID.class, accountId);
    }

    void insert(UUID accountId, String loginName, String passwordHash, Set<AccountAuthority> authorities) {
        jdbc.update("INSERT INTO identity_account(id, login_name, password_hash) VALUES (?, ?, ?)",
                accountId, loginName, passwordHash);
        for (AccountAuthority authority : authorities) {
            jdbc.update("INSERT INTO identity_account_authority(account_id, authority) VALUES (?, ?)",
                    accountId, authority.name());
        }
    }

    private Set<AccountAuthority> findAuthorities(UUID accountId) {
        var values = jdbc.queryForList("""
                SELECT authority FROM identity_account_authority WHERE account_id = ?
                """, String.class, accountId);
        var authorities = EnumSet.noneOf(AccountAuthority.class);
        for (String value : values) {
            authorities.add(AccountAuthority.valueOf(value));
        }
        return authorities;
    }

    private record AccountRow(UUID id, String loginName, String passwordHash, long credentialVersion) {
    }
}
