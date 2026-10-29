package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetIT {

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI base;
    private static PasswordHashes hashes;
    private static TrialAccountPasswordResetter resetter;

    @BeforeAll
    static void isolatedPostgresql() {
        String expected = System.getenv("DT04_TEST_DATABASE");
        if (expected == null || !expected.matches("dt04_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("Run scripts/Verify-PasswordReset.ps1 with an isolated PG16 database.");
        }
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        assertThat(admin.queryForObject("SELECT current_database()", String.class)).isEqualTo(expected);
        assertThat(admin.queryForObject("SHOW server_version_num", Integer.class)).isBetween(160000, 169999);
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'", Integer.class))
                .isZero();
        Flyway.configure().dataSource(url, username, password).defaultSchema("app").schemas("app")
                .createSchemas(true).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=app", username, password));
        context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0");
        base = URI.create("http://127.0.0.1:"
                + context.getEnvironment().getRequiredProperty("local.server.port"));
        hashes = context.getBean(PasswordHashes.class);
        resetter = context.getBean(TrialAccountPasswordResetter.class);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void resetAtomicallyRevokesOldSessionsAndPreservesNewAndOtherUsers() throws Exception {
        Account a = createAccount();
        Account b = createAccount();
        Client first = new Client();
        Client second = new Client();
        Client other = new Client();
        assertThat(first.login(a.login(), a.password())).isEqualTo(200);
        assertThat(second.login(a.login(), a.password())).isEqualTo(200);
        assertThat(other.login(b.login(), b.password())).isEqualTo(200);
        String oldId = new String(Base64.getDecoder().decode(first.sessionCookie()), StandardCharsets.UTF_8);
        var sessions = context.getBean(JdbcIndexedSessionRepository.class);
        var stale = sessions.findById(oldId);
        assertThat(stale).isNotNull();

        ResetTrialAccount reset = resetter.reset(a.id());
        assertThat(reset.toString()).doesNotContain(reset.newPassword());
        assertThat(reset.credentialVersion()).isEqualTo(2);
        assertThat(hashes.matches(reset.newPassword(), account(a.id()).passwordHash())).isTrue();
        assertThat(account(a.id()).credentialVersion()).isEqualTo(2);

        Client newLogin = new Client();
        assertThat(newLogin.login(a.login(), a.password())).isEqualTo(401);
        assertThat(newLogin.login(a.login(), reset.newPassword())).isEqualTo(200);
        String newSessionId = new String(Base64.getDecoder().decode(newLogin.sessionCookie()),
                StandardCharsets.UTF_8);
        int removed;
        try (var cleaner = new OldCredentialSessionCleaner(context.getBean(JdbcTemplate.class),
                context.getBean(PlatformTransactionManager.class))) {
            removed = cleaner.clean(a.id(), reset.credentialVersion());
        }
        assertThat(removed).isGreaterThanOrEqualTo(2);
        assertThat(sessions.findById(newSessionId)).isNotNull();
        assertThat(first.get("/api/auth/me")).isEqualTo(401);
        assertThat(second.get("/api/auth/me")).isEqualTo(401);
        sessions.save(stale);
        assertThat(first.get("/api/auth/me")).isEqualTo(401);
        assertThat(newLogin.get("/api/auth/me")).isEqualTo(200);
        assertThat(other.get("/api/auth/me")).isEqualTo(200);
        assertThat(account(b.id()).credentialVersion()).isEqualTo(1);

        Account c = createAccount();
        Client notCleanedYet = new Client();
        assertThat(notCleanedYet.login(c.login(), c.password())).isEqualTo(200);
        resetter.reset(c.id());
        // Authorization does not wait for best-effort physical Session cleanup.
        assertThat(notCleanedYet.get("/api/auth/me")).isEqualTo(401);
    }

    @Test
    void failedWriteRollsBackHashAndVersionAndUnknownResultCanBeReadBack() {
        Account a = createAccount();
        StoredAccount before = account(a.id());
        var failing = new JdbcAccountRepository(context.getBean(JdbcTemplate.class)) {
            @Override
            void updatePasswordAndVersion(UUID id, String hash) {
                super.updatePasswordAndVersion(id, hash);
                throw new IllegalStateException("synthetic failure before commit");
            }
        };
        var failingResetter = new TrialAccountPasswordResetter(failing, hashes,
                context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(() -> failingResetter.reset(a.id())).isInstanceOf(IllegalStateException.class);
        StoredAccount after = account(a.id());
        assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
        assertThat(after.credentialVersion()).isEqualTo(before.credentialVersion());

        // Simulate a committed reset whose command response was lost: query state, do not assume rollback.
        ResetTrialAccount committed = resetter.reset(a.id());
        assertThat(account(a.id()).credentialVersion()).isEqualTo(committed.credentialVersion());
        assertThat(account(a.id()).passwordHash()).isNotEqualTo(before.passwordHash());
        assertThatThrownBy(() -> resetter.reset(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionCleanupFailureDoesNotDelayLogicalRevocation() throws Exception {
        Account a = createAccount();
        Client old = new Client();
        assertThat(old.login(a.login(), a.password())).isEqualTo(200);
        jdbc.execute("ALTER TABLE spring_session RENAME TO spring_session_cleanup_unavailable");
        ResetTrialAccount reset;
        try {
            reset = resetter.reset(a.id());
            try (var cleaner = new OldCredentialSessionCleaner(context.getBean(JdbcTemplate.class),
                    context.getBean(PlatformTransactionManager.class))) {
                assertThatThrownBy(() -> cleaner.clean(a.id(), reset.credentialVersion()))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
        } finally {
            jdbc.execute("ALTER TABLE spring_session_cleanup_unavailable RENAME TO spring_session");
        }
        assertThat(old.get("/api/auth/me")).isEqualTo(401);
        Client fresh = new Client();
        assertThat(fresh.login(a.login(), reset.newPassword())).isEqualTo(200);
    }

    @Test
    void resetWinsWhileLoginWaitsBeforeFinalLockCheck() throws Exception {
        Account a = createAccount();
        CountDownLatch observedOldPassword = new CountDownLatch(1);
        CountDownLatch allowFinalCheck = new CountDownLatch(1);
        var pausing = new JdbcAccountRepository(context.getBean(JdbcTemplate.class)) {
            @Override
            void lockById(UUID id) {
                observedOldPassword.countDown();
                try {
                    if (!allowFinalCheck.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("login barrier timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("login barrier interrupted", exception);
                }
                super.lockById(id);
            }
        };
        var provider = new AccountAuthenticationProvider(pausing, hashes,
                context.getBean(PlatformTransactionManager.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pendingLogin = executor.submit(() -> provider.authenticate(
                    new UsernamePasswordAuthenticationToken(a.login(), a.password())));
            assertThat(observedOldPassword.await(20, TimeUnit.SECONDS)).isTrue();
            ResetTrialAccount reset = resetter.reset(a.id());
            allowFinalCheck.countDown();
            assertThatThrownBy(() -> pendingLogin.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadCredentialsException.class);
            assertThat(account(a.id()).credentialVersion()).isEqualTo(reset.credentialVersion());
        } finally {
            allowFinalCheck.countDown();
        }
    }

    @Test
    void noninteractiveResetIsRejectedBeforeFlywayOrAccountWrite() throws Exception {
        Account a = createAccount();
        StoredAccount before = account(a.id());
        String probe = "dt04_preflight_probe";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?",
                Integer.class, probe)).isZero();
        var command = new ProcessBuilder("java", "-jar",
                "target/interview-coach-backend-0.0.1-SNAPSHOT.jar",
                "--spring.profiles.active=local,reset", "--spring.main.web-application-type=none",
                "--app.reset.account-id=" + a.id(), "--spring.flyway.default-schema=" + probe,
                "--spring.flyway.schemas=" + probe).redirectErrorStream(true);
        Process process = command.start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isNotZero();
        assertThat(output).contains("interactive console").doesNotContain(a.password());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?",
                Integer.class, probe)).isZero();
        assertThat(account(a.id()).passwordHash()).isEqualTo(before.passwordHash());
        assertThat(account(a.id()).credentialVersion()).isEqualTo(before.credentialVersion());
    }

    @Test
    void realChromeTabsRejectTheOldSessionAfterReset() throws Exception {
        Account a = createAccount();
        var command = new ProcessBuilder("node", Path.of("../scripts/Verify-ResetBrowser.mjs")
                .toAbsolutePath().normalize().toString()).redirectErrorStream(true);
        command.environment().put("DT04_BROWSER_BASE", base.toString());
        command.environment().put("DT04_BROWSER_USER", a.login());
        command.environment().put("DT04_BROWSER_PASSWORD", a.password());
        Process browser = command.start();
        try (var reader = new BufferedReader(new InputStreamReader(browser.getInputStream(),
                StandardCharsets.UTF_8)); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> firstLine = executor.submit(reader::readLine);
            assertThat(firstLine.get(30, TimeUnit.SECONDS)).isEqualTo("BROWSER_READY");
            ResetTrialAccount reset = resetter.reset(a.id());
            browser.getOutputStream().write('x');
            browser.getOutputStream().flush();
            assertThat(browser.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String output = reader.readLine();
            assertThat(browser.exitValue()).as(output).isZero();
            assertThat(output).isEqualTo("Chrome reset revocation checks passed.")
                    .doesNotContain(a.password(), reset.newPassword());
        } finally {
            if (browser.isAlive()) {
                browser.destroyForcibly();
            }
        }
    }

    private static Account createAccount() {
        UUID id = UUID.randomUUID();
        String login = "dt04." + id;
        String password = "synthetic-" + UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(id, login, hashes.encode(password),
                Set.of(AccountAuthority.TRIAL_USER));
        return new Account(id, login, password);
    }

    private static StoredAccount account(UUID id) {
        return new JdbcAccountRepository(jdbc).findById(id).orElseThrow();
    }

    private record Account(UUID id, String login, String password) {
    }

    private static final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();

        int login(String login, String password) throws Exception {
            String csrf = csrf();
            String form = "username=" + URLEncoder.encode(login, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            return http.send(HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-CSRF-TOKEN", csrf)
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
        }

        String csrf() throws Exception {
            String body = http.send(HttpRequest.newBuilder(base.resolve("/api/auth/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return (String) org.springframework.boot.json.JsonParserFactory.getJsonParser()
                    .parseMap(body).get("token");
        }

        int get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
        }

        String sessionCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "SESSION".equals(cookie.getName()))
                    .findFirst().orElseThrow().getValue();
        }
    }
}
