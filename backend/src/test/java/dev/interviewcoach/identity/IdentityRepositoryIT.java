package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityRepositoryIT {

    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static JdbcAccountRepository accounts;

    @BeforeAll
    static void requireFreshIsolatedPostgresqlDatabase() {
        String expectedDatabase = System.getenv("DT02_TEST_DATABASE");
        if (expectedDatabase == null || !expectedDatabase.matches("dt02_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("Run scripts/Verify-Account.ps1; an isolated DT-02 database is required.");
        }
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        var administrativeSource = new DriverManagerDataSource(url, username, password);
        var administrativeJdbc = new JdbcTemplate(administrativeSource);
        assertThat(administrativeJdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(expectedDatabase);
        assertThat(administrativeJdbc.queryForObject("SHOW server_version_num", Integer.class))
                .isBetween(160000, 169999);
        assertThat(administrativeJdbc.queryForObject("""
                SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                """, Integer.class)).isZero();

        Flyway.configure().dataSource(url, username, password).defaultSchema("app").schemas("app")
                .createSchemas(true).locations("classpath:db/migration").load().migrate();
        source = new DriverManagerDataSource(url + "?currentSchema=app", username, password);
        jdbc = new JdbcTemplate(source);
        accounts = new JdbcAccountRepository(jdbc);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("app");
    }

    @Test
    void localApplicationWiresAccountRepositoryAgainstIsolatedDatabase() {
        try (var context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0")) {
            assertThat(context.getBean(JdbcAccountRepository.class)).isNotNull();
            assertThat(context.getBean(PasswordHashes.class)).isNotNull();
            assertThat(new JdbcTemplate(context.getBean(javax.sql.DataSource.class))
                    .queryForObject("SELECT current_database()", String.class))
                    .isEqualTo(System.getenv("DT02_TEST_DATABASE"));
        }
    }

    @Test
    void storesVersionedPasswordHashAndDistinctAuthorities() {
        var hashes = new PasswordHashes();
        UUID userId = UUID.randomUUID();
        UUID maintainerId = UUID.randomUUID();
        String userLogin = "dt02-user-" + userId;
        String maintainerLogin = "dt02-maintainer-" + maintainerId;
        String encoded = hashes.encode("synthetic-test-credential");

        accounts.insert(userId, userLogin, encoded, Set.of(AccountAuthority.TRIAL_USER));
        accounts.insert(maintainerId, maintainerLogin, hashes.encode("other-synthetic-test-credential"),
                Set.of(AccountAuthority.KNOWLEDGE_MAINTAINER));

        StoredAccount user = accounts.findByLoginName(userLogin).orElseThrow();
        assertThat(user.id()).isEqualTo(userId);
        assertThat(user.credentialVersion()).isEqualTo(1);
        assertThat(user.passwordHash()).isEqualTo(encoded).doesNotContain("synthetic-test-credential");
        assertThat(hashes.matches("synthetic-test-credential", user.passwordHash())).isTrue();
        assertThat(user.actor().hasAuthority(AccountAuthority.TRIAL_USER)).isTrue();
        assertThat(user.actor().hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER)).isFalse();
        assertThat(accounts.findById(maintainerId).orElseThrow().actor()
                .hasAuthority(AccountAuthority.TRIAL_USER)).isFalse();
        assertThat(accounts.findByLoginName("does-not-exist")).isEmpty();
        assertThat(accounts.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void databaseRejectsDuplicateLoginWithoutReplacingExistingAccount() {
        UUID firstId = UUID.randomUUID();
        String login = "dt02-duplicate-" + firstId;
        String firstHash = new PasswordHashes().encode("first-synthetic-credential");
        accounts.insert(firstId, login, firstHash, Set.of(AccountAuthority.TRIAL_USER));

        assertThatThrownBy(() -> accounts.insert(UUID.randomUUID(), login,
                new PasswordHashes().encode("second-synthetic-credential"),
                Set.of(AccountAuthority.TRIAL_USER)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(accounts.findByLoginName(login).orElseThrow().id()).isEqualTo(firstId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account WHERE login_name=?",
                Integer.class, login)).isEqualTo(1);
    }

    @Test
    void controlledProvisioningCreatesOnlyTrialAuthorityAndDistinctRandomCredential() {
        var provisioner = new TrialAccountProvisioner(accounts, new PasswordHashes(),
                new DataSourceTransactionManager(source));
        String login = "dt02.provision." + UUID.randomUUID();
        ProvisionedTrialAccount result = provisioner.provision(login);
        StoredAccount saved = accounts.findById(result.accountId()).orElseThrow();

        assertThat(result.initialPassword()).matches("[A-Za-z0-9_-]{32}");
        assertThat(result.toString()).doesNotContain(result.initialPassword());
        assertThat(saved.passwordHash()).startsWith("{pbkdf2-sha256-v1}")
                .doesNotContain(result.initialPassword());
        assertThat(new PasswordHashes().matches(result.initialPassword(), saved.passwordHash())).isTrue();
        assertThat(saved.loginName()).isEqualTo(login);
        assertThat(saved.credentialVersion()).isEqualTo(1);
        assertThat(saved.authorities()).containsExactly(AccountAuthority.TRIAL_USER);
        assertThat(saved.actor().hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER)).isFalse();

        String secondLogin = "dt02.provision." + UUID.randomUUID();
        assertThat(provisioner.provision(secondLogin).initialPassword()).isNotEqualTo(result.initialPassword());
    }

    @Test
    void loginNameValidationAndCaseSensitiveUniquenessMatchConfirmedRule() {
        var provisioner = new TrialAccountProvisioner(accounts, new PasswordHashes(),
                new DataSourceTransactionManager(source));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        for (String invalid : new String[] { "ab", " leading", "a b", "名字1", "a".repeat(65) }) {
            assertThatThrownBy(() -> provisioner.provision(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> provisioner.provision(null)).isInstanceOf(IllegalArgumentException.class);

        String upper = "Test." + suffix;
        String lower = "test." + suffix;
        var first = provisioner.provision(upper);
        var second = provisioner.provision(lower);
        assertThat(first.accountId()).isNotEqualTo(second.accountId());
        assertThat(accounts.findByLoginName(upper).orElseThrow().id()).isEqualTo(first.accountId());
        assertThat(accounts.findByLoginName(lower).orElseThrow().id()).isEqualTo(second.accountId());
        assertThatThrownBy(() -> provisioner.provision(upper)).isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account WHERE login_name IN (?, ?)",
                Integer.class, upper, lower)).isEqualTo(2);
    }

    @Test
    void provisioningRollsBackAccountAndAuthorityWhenWriteFailsAfterBothInserts() {
        UUID accountId = UUID.randomUUID();
        String login = "dt02-rollback-" + accountId;
        var failingRepository = new JdbcAccountRepository(jdbc) {
            @Override
            void insert(UUID id, String name, String hash, Set<AccountAuthority> authorities) {
                super.insert(id, name, hash, authorities);
                throw new IllegalStateException("synthetic failure after both writes");
            }
        };
        var provisioner = new TrialAccountProvisioner(failingRepository, new PasswordHashes(),
                new DataSourceTransactionManager(source));

        assertThatThrownBy(() -> provisioner.provision(login)).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findByLoginName(login)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account_authority a "
                + "JOIN identity_account i ON i.id=a.account_id WHERE i.login_name=?",
                Integer.class, login)).isZero();
    }
}
