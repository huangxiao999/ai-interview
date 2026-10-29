package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAuthenticationIT {

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI base;
    private static String password;
    private static String login;
    private static UUID accountId;

    @BeforeAll
    static void setUpIsolatedDatabase() {
        String database = System.getenv("DT03_TEST_DATABASE");
        if (database == null || !database.matches("dt03_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("Run scripts/Verify-SessionAuth.ps1 with an isolated database.");
        }
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String dbPassword = System.getenv("DB_PASSWORD");
        var administrative = new JdbcTemplate(new DriverManagerDataSource(url, username, dbPassword));
        assertThat(administrative.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        assertThat(administrative.queryForObject("SHOW server_version_num", Integer.class))
                .isBetween(160000, 169999);
        assertThat(administrative.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'",
                Integer.class)).isZero();
        Flyway.configure().dataSource(url, username, dbPassword).defaultSchema("app").schemas("app")
                .createSchemas(true).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=app", username, dbPassword));
        accountId = UUID.randomUUID();
        login = "dt03." + accountId;
        password = "synthetic-" + UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(accountId, login, new PasswordHashes().encode(password),
                Set.of(AccountAuthority.TRIAL_USER));
        context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0");
        base = URI.create("http://127.0.0.1:"
                + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void loginMeAndSingleSessionLogoutUsePostgresqlAndRotatedCsrf() throws Exception {
        Client first = new Client();
        Client second = new Client();
        String beforeLogin = first.csrf();
        String anonymousCookie = first.sessionCookie();
        HttpResponse<String> loginResponse = first.login(login, password, beforeLogin);
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        assertThat(first.sessionCookie()).isNotEqualTo(anonymousCookie);
        assertThat(first.getWithCookie("/api/auth/me", anonymousCookie).statusCode()).isEqualTo(401);
        assertThat(loginResponse.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(first.cookies.getCookieStore().getCookies()).anySatisfy(cookie -> {
            assertThat(cookie.getName()).isEqualTo("SESSION");
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getMaxAge()).isEqualTo(-1);
        });
        assertThat(loginResponse.headers().allValues("Set-Cookie")).anySatisfy(header ->
                assertThat(header).contains("SameSite=Lax", "HttpOnly"));
        assertThat(loginResponse.body()).contains(accountId.toString(), login, "TRIAL_USER")
                .doesNotContain(password, "passwordHash", "credentialVersion", "sessionId");
        assertThat(first.get("/api/auth/me").statusCode()).isEqualTo(200);
        String afterLogin = first.csrf();
        assertThat(afterLogin).isNotEqualTo(beforeLogin);
        assertThat(first.post("/api/auth/logout", "", beforeLogin).statusCode()).isEqualTo(403);
        assertThat(first.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name=?",
                Integer.class, accountId.toString())).isGreaterThanOrEqualTo(1);

        assertThat(second.login(login, password, second.csrf()).statusCode()).isEqualTo(200);
        assertThat(second.get("/api/auth/me").statusCode()).isEqualTo(200);
        String oldSessionCookie = first.sessionCookie();
        // Spring Session encodes the browser cookie; the repository uses the decoded session ID.
        String oldSessionId = new String(Base64.getDecoder().decode(oldSessionCookie),
                StandardCharsets.UTF_8);
        var repository = context.getBean(JdbcIndexedSessionRepository.class);
        Session stale = repository.findById(oldSessionId);
        assertThat(stale).isNotNull();
        assertThat(first.post("/api/auth/logout", "", afterLogin).statusCode()).isEqualTo(204);
        assertThat(first.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertLateSaveCannotRestoreSession(repository, stale, oldSessionId);
        assertThat(first.getWithCookie("/api/auth/me", oldSessionCookie).statusCode()).isEqualTo(401);
        assertThat(second.get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void badCredentialsInputAndCsrfDoNotAuthenticate() throws Exception {
        Client client = new Client();
        assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(client.get("/login").statusCode()).isNotEqualTo(200);
        HttpResponse<String> missingCsrf = client.login(login, password, null);
        assertThat(missingCsrf.statusCode()).isEqualTo(403);
        assertThat(missingCsrf.body()).contains("CSRF_INVALID");
        String token = client.csrf();
        Client other = new Client();
        String otherToken = other.csrf();
        assertThat(client.login(login, password, otherToken).statusCode()).isEqualTo(403);
        HttpResponse<String> wrong = client.login(login, "wrong-synthetic-password", token);
        HttpResponse<String> unknown = client.login("does-not-exist", "wrong-synthetic-password", token);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body()).contains("INVALID_CREDENTIALS");
        assertThat(client.login(login, "", token).statusCode()).isEqualTo(400);
        assertThat(client.postJson("/api/auth/login", "{}", token).statusCode()).isEqualTo(415);
        HttpResponse<String> anonymousMe = client.get("/api/auth/me");
        assertThat(anonymousMe.statusCode()).isEqualTo(401);
        assertThat(anonymousMe.body()).contains("AUTHENTICATION_REQUIRED");
        assertThat(client.post("/api/auth/logout", "", "forged-token").statusCode()).isEqualTo(403);
        assertThat(client.get("/actuator/env").statusCode()).isEqualTo(401);
    }

    @Test
    void persistedExpiryAndCredentialVersionRejectOldSession() throws Exception {
        Client expired = new Client();
        assertThat(expired.login(login, password, expired.csrf()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session "
                + "WHERE principal_name=? ORDER BY creation_time DESC LIMIT 1", Integer.class,
                accountId.toString())).isEqualTo(1800);
        long stillValid = Instant.now().minusSeconds(1780).toEpochMilli();
        jdbc.update("UPDATE spring_session SET last_access_time=?, expiry_time=? "
                + "WHERE principal_name=?", stillValid, stillValid + 1_800_000, accountId.toString());
        assertThat(expired.get("/api/auth/me").statusCode()).isEqualTo(200);
        long past = Instant.now().minusSeconds(1801).toEpochMilli();
        assertThat(jdbc.update("UPDATE spring_session SET last_access_time=?, expiry_time=? "
                + "WHERE principal_name=?", past, past + 1_800_000, accountId.toString()))
                .isGreaterThanOrEqualTo(1);
        assertThat(expired.get("/api/auth/me").statusCode()).isEqualTo(401);

        Client revoked = new Client();
        assertThat(revoked.login(login, password, revoked.csrf()).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE identity_account SET credential_version=credential_version+1 WHERE id=?", accountId);
        assertThat(revoked.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void missingAccountStateFailsClosedWithoutUsingCachedPrincipal() throws Exception {
        Client client = new Client();
        assertThat(client.login(login, password, client.csrf()).statusCode()).isEqualTo(200);
        jdbc.execute("ALTER TABLE identity_account RENAME TO identity_account_unavailable");
        try {
            HttpResponse<String> unavailable = client.get("/api/auth/me");
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(unavailable.body()).contains("SERVICE_UNAVAILABLE").doesNotContain(login);
            Client loginAttempt = new Client();
            HttpResponse<String> loginUnavailable = loginAttempt.login(login, password, loginAttempt.csrf());
            assertThat(loginUnavailable.statusCode()).isEqualTo(503);
            assertThat(loginUnavailable.body()).contains("SERVICE_UNAVAILABLE").doesNotContain(login);
        } finally {
            jdbc.execute("ALTER TABLE identity_account_unavailable RENAME TO identity_account");
        }
    }

    @Test
    void unavailableSessionStoreReturnsServiceErrorInsteadOfCachedIdentity() throws Exception {
        Client client = new Client();
        assertThat(client.login(login, password, client.csrf()).statusCode()).isEqualTo(200);
        jdbc.execute("ALTER TABLE spring_session RENAME TO spring_session_unavailable");
        try {
            HttpResponse<String> response = client.get("/api/auth/me");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("SERVICE_UNAVAILABLE").doesNotContain(login);
        } finally {
            jdbc.execute("ALTER TABLE spring_session_unavailable RENAME TO spring_session");
        }
    }

    @Test
    void maintainerIdentityDoesNotGainTrialAuthority() throws Exception {
        UUID maintainerId = UUID.randomUUID();
        String maintainerLogin = "dt03.maintainer." + maintainerId;
        String maintainerPassword = "synthetic-" + UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(maintainerId, maintainerLogin,
                new PasswordHashes().encode(maintainerPassword),
                Set.of(AccountAuthority.KNOWLEDGE_MAINTAINER));
        Client maintainer = new Client();
        assertThat(maintainer.login(maintainerLogin, maintainerPassword, maintainer.csrf())
                .statusCode()).isEqualTo(200);
        assertThat(maintainer.get("/api/auth/me").body())
                .contains("KNOWLEDGE_MAINTAINER").doesNotContain("TRIAL_USER");
        assertThat(maintainer.get("/actuator/env").statusCode()).isEqualTo(403);
        jdbc.update("INSERT INTO identity_account_authority(account_id, authority) VALUES (?, ?)",
                maintainerId, "UNRECOGNIZED_TEST_AUTHORITY");
        try {
            assertThat(maintainer.get("/api/auth/me").statusCode()).isEqualTo(503);
        } finally {
            jdbc.update("DELETE FROM identity_account_authority WHERE account_id=? AND authority=?",
                    maintainerId, "UNRECOGNIZED_TEST_AUTHORITY");
        }
    }

    @Test
    void secondApplicationContextReadsPersistedLoginSession() throws Exception {
        Client client = new Client();
        assertThat(client.login(login, password, client.csrf()).statusCode()).isEqualTo(200);
        String cookie = client.sessionCookie();
        try (var secondContext = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0")) {
            URI secondBase = URI.create("http://127.0.0.1:"
                    + secondContext.getEnvironment().getRequiredProperty("local.server.port"));
            HttpResponse<String> me = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(secondBase.resolve("/api/auth/me"))
                            .header("Cookie", "SESSION=" + cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(me.body()).contains(accountId.toString());
        }
    }

    @Test
    void realChromeBrowserUsesHttpOnlyCookieAndRotatedCsrfAcrossTabs() throws Exception {
        var command = new ProcessBuilder("node", Path.of("../scripts/Verify-SessionBrowser.mjs")
                .toAbsolutePath().normalize().toString());
        command.environment().put("DT03_BROWSER_BASE", base.toString());
        command.environment().put("DT03_BROWSER_USER", login);
        command.environment().put("DT03_BROWSER_PASSWORD", password);
        command.environment().put("DT03_BROWSER_ACCOUNT_ID", accountId.toString());
        command.redirectErrorStream(true);
        Process browser = command.start();
        boolean finished = browser.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            browser.destroyForcibly();
        }
        String output = new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("Chrome browser verification timed out").isTrue();
        assertThat(browser.exitValue()).as(output).isZero();
        assertThat(output).contains("Chrome browser Cookie, CSRF, login, shared-tab session and lost-logout-response checks passed.")
                .doesNotContain(password);
    }

    @SuppressWarnings("unchecked")
    private static <S extends Session> void assertLateSaveCannotRestoreSession(
            FindByIndexNameSessionRepository<S> repository, Session stale, String oldSessionId) {
        repository.save((S) stale);
        assertThat(repository.findById(oldSessionId)).isNull();
    }

    private static final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        String csrf() throws Exception {
            HttpResponse<String> response = get("/api/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            Map<String, Object> body = JsonParserFactory.getJsonParser().parseMap(response.body());
            assertThat(body.get("headerName")).isEqualTo("X-CSRF-TOKEN");
            return (String) body.get("token");
        }

        HttpResponse<String> login(String name, String rawPassword, String token) throws Exception {
            String form = "username=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(rawPassword, StandardCharsets.UTF_8);
            return post("/api/auth/login", form, token);
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        String sessionCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "SESSION".equals(cookie.getName()))
                    .findFirst().orElseThrow().getValue();
        }

        HttpResponse<String> getWithCookie(String path, String sessionId) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve(path))
                            .header("Cookie", "SESSION=" + sessionId).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String form, String token) throws Exception {
            var request = HttpRequest.newBuilder(base.resolve(path))
                    .header("Content-Type", "application/x-www-form-urlencoded");
            if (token != null) {
                request.header("X-CSRF-TOKEN", token);
            }
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postJson(String path, String json, String token) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path))
                            .header("Content-Type", "application/json")
                            .header("X-CSRF-TOKEN", token)
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
