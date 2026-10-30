package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
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

    @Test
    void springWiredProvisioningCommitsOrRollsBackAccountAndAuthorityTogether() {
        try (var context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0")) {
            var wired = context.getBean(TrialAccountProvisioner.class);
            var actualJdbc = context.getBean(JdbcTemplate.class);
            String successLogin = "dt02.wired." + UUID.randomUUID();
            var created = wired.provision(successLogin);
            assertThat(actualJdbc.queryForObject("SELECT count(*) FROM identity_account WHERE id=?",
                    Integer.class, created.accountId())).isEqualTo(1);
            assertThat(actualJdbc.queryForObject("SELECT count(*) FROM identity_account_authority "
                    + "WHERE account_id=? AND authority='TRIAL_USER'", Integer.class,
                    created.accountId())).isEqualTo(1);

            String failedLogin = "dt02.wired.fail." + UUID.randomUUID();
            actualJdbc.execute("ALTER TABLE identity_account_authority ADD CONSTRAINT "
                    + "dt02_reject_trial_for_rollback CHECK (authority <> 'TRIAL_USER') NOT VALID");
            try {
                assertThatThrownBy(() -> wired.provision(failedLogin))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(actualJdbc.queryForObject("SELECT count(*) FROM identity_account WHERE login_name=?",
                        Integer.class, failedLogin)).isZero();
                assertThat(actualJdbc.queryForObject("SELECT count(*) FROM identity_account_authority a "
                        + "JOIN identity_account i ON i.id=a.account_id WHERE i.login_name=?",
                        Integer.class, failedLogin)).isZero();
            } finally {
                actualJdbc.execute("ALTER TABLE identity_account_authority "
                        + "DROP CONSTRAINT dt02_reject_trial_for_rollback");
            }
        }
    }

    @Test
    void twoSpringWiredRequestsForSameLoginProduceOneAccountAndOneAuthority() throws Exception {
        try (var context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0");
                var executor = Executors.newFixedThreadPool(2)) {
            var wired = context.getBean(TrialAccountProvisioner.class);
            String login = "dt02.concurrent." + UUID.randomUUID();
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent start barrier timed out.");
                }
                try {
                    wired.provision(login);
                    return true;
                } catch (DuplicateKeyException expected) {
                    return false;
                }
            })).toList();
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (var attempt : attempts) {
                successes += attempt.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);
            UUID accountId = accounts.findByLoginName(login).orElseThrow().id();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account WHERE login_name=?",
                    Integer.class, login)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account_authority WHERE account_id=?",
                    Integer.class, accountId)).isEqualTo(1);
        }
    }

    @Test
    void unknownCommitResultIsReconciledFromTheOriginalLoginFact() {
        for (boolean commitFirst : new boolean[] {false, true}) {
            var faultSource = new CommitConnectionLossDataSource(source, commitFirst);
            var faultJdbc = new JdbcTemplate(faultSource);
            var provisioner = new TrialAccountProvisioner(new JdbcAccountRepository(faultJdbc),
                    new PasswordHashes(), new DataSourceTransactionManager(faultSource));
            String login = "dt02.commit." + UUID.randomUUID();
            assertThatThrownBy(() -> provisioner.provision(login)).isInstanceOf(RuntimeException.class);
            int accountsAfter = jdbc.queryForObject("SELECT count(*) FROM identity_account WHERE login_name=?",
                    Integer.class, login);
            int authoritiesAfter = jdbc.queryForObject("SELECT count(*) FROM identity_account_authority a "
                    + "JOIN identity_account i ON i.id=a.account_id WHERE i.login_name=?",
                    Integer.class, login);
            assertThat(accountsAfter).isEqualTo(commitFirst ? 1 : 0);
            assertThat(authoritiesAfter).isEqualTo(accountsAfter);
            // A lost commit acknowledgement is resolved by reading the original login, not by retrying.
            assertThat(accounts.findByLoginName(login).isPresent()).isEqualTo(commitFirst);
        }
    }

    @Test
    void independentJdkPbkdf2FixtureVerifiesStoredVersionAndParameters() throws Exception {
        String raw = "synthetic-fixture-credential";
        String encoded = new PasswordHashes().encode(raw);
        String prefix = "{pbkdf2-sha256-v1}";
        assertThat(encoded).startsWith(prefix);
        byte[] bytes = Base64.getDecoder().decode(encoded.substring(prefix.length()));
        assertThat(bytes).hasSize(48);
        byte[] salt = Arrays.copyOfRange(bytes, 0, 16);
        byte[] actualHash = Arrays.copyOfRange(bytes, 16, bytes.length);
        var specification = new PBEKeySpec(raw.toCharArray(), salt, 310_000, 256);
        byte[] independentlyCalculated = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification).getEncoded();
        assertThat(actualHash).isEqualTo(independentlyCalculated);
        assertThat(new PasswordHashes().matches(raw + "-wrong", encoded)).isFalse();
    }

    @Test
    void loginNameBoundariesUseExactOriginalAsciiValues() {
        var provisioner = new TrialAccountProvisioner(accounts, new PasswordHashes(),
                new DataSourceTransactionManager(source));
        for (String valid : new String[] {"a_3", "A".repeat(64)}) {
            var created = provisioner.provision(valid);
            assertThat(accounts.findByLoginName(valid).orElseThrow().id()).isEqualTo(created.accountId());
        }
        for (String invalid : new String[] {" ab", "ab ", "abc ", "abc\n", "\tabc",
                "abc\r\n", "ébc", "abc中", "ab", "a".repeat(65)}) {
            assertThatThrownBy(() -> provisioner.provision(invalid))
                    .as("invalid original login name must be rejected")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void noninteractiveOrWrongModeProvisionIsRejectedBeforeFlywayAndWrites() throws Exception {
        for (boolean correctMode : new boolean[] {true, false}) {
            String probe = "dt02_preflight_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String login = "dt02.preflight." + UUID.randomUUID();
            int historyBefore = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",
                    Integer.class);
            int accountBefore = jdbc.queryForObject("SELECT count(*) FROM identity_account",
                    Integer.class);
            int authorityBefore = jdbc.queryForObject("SELECT count(*) FROM identity_account_authority",
                    Integer.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?",
                    Integer.class, probe)).isZero();
            var command = new ProcessBuilder("java", "-jar",
                    "target/interview-coach-backend-0.0.1-SNAPSHOT.jar",
                    "--spring.profiles.active=local,provision",
                    "--spring.main.web-application-type=" + (correctMode ? "none" : "servlet"),
                    "--app.provision.login-name=" + login,
                    "--spring.flyway.default-schema=" + probe,
                    "--spring.flyway.schemas=" + probe).redirectErrorStream(true);
            Process process = command.start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).isNotZero();
            assertThat(output).contains(correctMode ? "interactive console" : "local non-web mode")
                    .doesNotContain("Initial password");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?",
                    Integer.class, probe)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",
                    Integer.class)).isEqualTo(historyBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account",
                    Integer.class)).isEqualTo(accountBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account_authority",
                    Integer.class)).isEqualTo(authorityBefore);
            assertThat(accounts.findByLoginName(login)).isEmpty();
        }
    }

    @Test
    void realWindowsFileChecksRejectMissingBroadAclAndExpectedOwnerMismatch() throws Exception {
        Path cache = Path.of("../.cache").toAbsolutePath().normalize();
        assertThat(cache.startsWith(Path.of("..").toAbsolutePath().normalize())).isTrue();
        Path fixture = Files.createTempDirectory(cache, "dt02-acl-evidence-");
        Path safeFile = Files.createFile(fixture.resolve("owned-file"));
        String actualOwner = Files.getOwner(safeFile).getName();
        AccountMaintenancePreflight.requirePrivateOwnerAndAcl(safeFile, actualOwner);
        assertThatThrownBy(() -> AccountMaintenancePreflight.requirePrivateOwnerAndAcl(
                fixture.resolve("missing"), actualOwner)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AccountMaintenancePreflight.requirePrivateOwnerAndAcl(
                safeFile, actualOwner + "-different")).isInstanceOf(IllegalStateException.class);

        Path broadFile = Files.createFile(fixture.resolve("broad-file"));
        var view = Files.getFileAttributeView(broadFile, AclFileAttributeView.class);
        assertThat(view).isNotNull();
        var users = broadFile.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("BUILTIN\\Users");
        var allowUsers = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users)
                .setPermissions(AclEntryPermission.READ_DATA).build();
        var acl = new java.util.ArrayList<>(view.getAcl());
        acl.add(allowUsers);
        view.setAcl(acl);
        assertThatThrownBy(() -> AccountMaintenancePreflight.requirePrivateOwnerAndAcl(
                broadFile, actualOwner)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access beyond the operator");
    }

    @Test
    void realWindowsSymbolicLinkIsRejectedWhenLinkCreationIsAvailable() throws Exception {
        Path cache = Path.of("../.cache").toAbsolutePath().normalize();
        Path fixture = Files.createTempDirectory(cache, "dt02-link-evidence-");
        Path target = Files.createFile(fixture.resolve("target"));
        Path link = fixture.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException exception) {
            org.junit.jupiter.api.Assumptions.abort("Windows symbolic-link creation is unavailable: "
                    + exception.getClass().getSimpleName());
        }
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThatThrownBy(() -> AccountMaintenancePreflight.requirePrivateOwnerAndAcl(
                link, Files.getOwner(target).getName())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realWindowsJunctionIsRejectedEvenThoughItIsNotASymbolicLink() throws Exception {
        Path cache = Path.of("../.cache").toAbsolutePath().normalize();
        Path fixture = Files.createTempDirectory(cache, "dt02-junction-evidence-");
        Path target = Files.createDirectory(fixture.resolve("target"));
        Path link = fixture.resolve("link");
        String command = "$null = New-Item -ItemType Junction -Path '"
                + link.toString().replace("'", "''") + "' -Target '"
                + target.toString().replace("'", "''") + "'; if (-not $?) { exit 1 }";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-Command", command).redirectErrorStream(true).start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(Files.isSymbolicLink(link)).isFalse();
        assertThat(Files.readAttributes(link, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther()).isTrue();
        assertThatThrownBy(() -> AccountMaintenancePreflight.requirePrivateOwnerAndAcl(
                link, Files.getOwner(target).getName())).isInstanceOf(IllegalStateException.class);
    }

    private static final class CommitConnectionLossDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final boolean commitFirst;

        private CommitConnectionLossDataSource(DataSource delegate, boolean commitFirst) {
            this.delegate = delegate;
            this.commitFirst = commitFirst;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection physical) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            if (commitFirst) {
                                physical.commit();
                            }
                            physical.close();
                            throw new SQLTransientConnectionException("synthetic connection lost at commit");
                        }
                        try {
                            return method.invoke(physical, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }
    }
}
