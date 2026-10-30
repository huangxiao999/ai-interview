package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.net.HttpURLConnection;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

import static org.assertj.core.api.Assertions.assertThat;

class ResumeMaterialIT {

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI base;
    private static Process vite;
    private static Account a;
    private static Account b;
    private static Account maintainer;

    @BeforeAll
    static void isolatedDatabase() {
        String database = System.getenv("P2_DT01_TEST_DATABASE");
        if (database == null || !database.matches("p2dt01_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("A new isolated P2-DT-01 database is required.");
        }
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        assertThat(admin.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        assertThat(admin.queryForObject("SHOW server_version_num", Integer.class))
                .isBetween(160000, 169999);
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'",
                Integer.class)).isZero();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
                .defaultSchema("app").schemas("app").createSchemas(true)
                .locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=app", username, password));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE success AND version = '3'", Integer.class)).isEqualTo(1);
        a = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        b = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        maintainer = insertAccount(Set.of(AccountAuthority.KNOWLEDGE_MAINTAINER));
        context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0");
        base = URI.create("http://127.0.0.1:"
                + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private static Account insertAccount(Set<AccountAuthority> authorities) {
        UUID id = UUID.randomUUID();
        String login = "p2." + id;
        String password = "synthetic-" + UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(id, login, new PasswordHashes().encode(password), authorities);
        return new Account(id, login, password);
    }

    @AfterAll
    static void close() {
        if (vite != null) {
            vite.destroy();
            try {
                if (!vite.waitFor(5, TimeUnit.SECONDS)) vite.destroyForcibly();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                vite.destroyForcibly();
            }
        }
        if (context != null) context.close();
    }

    @Test
    void realChromeCanSaveReloadAndDeletePaste() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URI frontend = URI.create("http://127.0.0.1:" + port);
        var command = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "--host",
                "127.0.0.1", "--port", Integer.toString(port), "--strictPort");
        command.directory(Path.of("../frontend").toFile());
        command.environment().put("BACKEND_URL", base.toString());
        command.redirectErrorStream(true);
        command.redirectOutput(Path.of("../.cache/p2-dt01-vite.log").toFile());
        vite = command.start();
        boolean ready = false;
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!vite.isAlive()) throw new IllegalStateException("Vite exited before browser verification.");
            try {
                var connection = (HttpURLConnection) frontend.resolve("/login").toURL()
                        .openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                ready = connection.getResponseCode() == 200;
                connection.disconnect();
                if (ready) break;
            } catch (java.io.IOException ignored) {
                // The loopback server may not have bound its port yet.
            }
            Thread.sleep(100);
        }
        assertThat(ready).as("Vite did not become ready").isTrue();
        var browserCommand = new ProcessBuilder("node", Path.of("../scripts/Verify-P2ResumeBrowser.mjs")
                .toAbsolutePath().normalize().toString());
        browserCommand.environment().put("P2_FRONTEND_BASE", frontend.toString());
        browserCommand.environment().put("P2_BROWSER_USER", a.login());
        browserCommand.environment().put("P2_BROWSER_PASSWORD", a.password());
        browserCommand.redirectErrorStream(true);
        Process browser = browserCommand.start();
        boolean finished = browser.waitFor(90, TimeUnit.SECONDS);
        if (!finished) browser.destroyForcibly();
        String output = new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("Chrome material verification timed out").isTrue();
        assertThat(browser.exitValue()).as(output).isZero();
        assertThat(output).contains("P2-DT-01 real Chrome material checks passed.")
                .doesNotContain(a.password());
    }

    @Test
    void pasteBoundaryIdempotencyAndVersionFacts() throws Exception {
        Client user = new Client(a);
        UUID operation = UUID.randomUUID();
        String body = "\r\n" + "😀".repeat(100) + "\r\n";
        HttpResponse<String> created = user.create(body, operation);
        assertThat(created.statusCode()).isEqualTo(201);
        Map<String, Object> first = json(created);
        UUID root = UUID.fromString((String) first.get("resumeId"));
        UUID version = UUID.fromString((String) first.get("versionId"));
        assertThat(first.get("codepointCount")).isEqualTo(100);
        assertThat(first.get("body")).isEqualTo("😀".repeat(100));
        assertThat(json(user.create(body, operation)).get("versionId")).isEqualTo(version.toString());
        assertThat(json(user.get("/api/materials/resumes/operations/" + operation))
                .get("versionId")).isEqualTo(version.toString());
        assertThat(user.create("a".repeat(99), UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(user.create("a".repeat(20_001), UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(user.create("a".repeat(20_000), UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isEqualTo(1);
        assertThat(user.create("b".repeat(100), operation).statusCode()).isEqualTo(409);
        assertThat(user.get("/api/materials/resumes/" + root + "/versions/" + version).statusCode())
                .isEqualTo(200);
    }

    @Test
    void appendKeepsOriginalVersionAndUnknownResponseCanBeChecked() throws Exception {
        Client user = new Client(a);
        UUID createOperation = UUID.randomUUID();
        UUID root = UUID.fromString((String) user.createWithoutReadingBody("v".repeat(100),
                createOperation).get("resumeId"));
        String basePath = "/api/materials/resumes/" + root;
        UUID firstVersion = UUID.fromString((String) json(user.get(basePath)).get("versionId"));
        UUID appendOperation = UUID.randomUUID();
        Map<String, Object> second = json(user.addVersion(root, "n".repeat(120), appendOperation));
        assertThat(second.get("versionNo")).isEqualTo(2);
        assertThat(json(user.addVersion(root, "n".repeat(120), appendOperation))
                .get("versionId")).isEqualTo(second.get("versionId"));
        assertThat(json(user.get(basePath + "/versions/" + firstVersion)).get("body"))
                .isEqualTo("v".repeat(100));
        assertThat(json(user.get(basePath)).get("body")).isEqualTo("n".repeat(120));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isEqualTo(2);
    }

    @Test
    void failedCleanupInsertRollsBackBarrierAndBodyThenRetrySucceeds() throws Exception {
        Client user = new Client(a);
        UUID root = UUID.fromString((String) json(user.create("r".repeat(100), UUID.randomUUID()))
                .get("resumeId"));
        jdbc.execute("CREATE FUNCTION app.fail_p2_cleanup() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'synthetic cleanup failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_p2_cleanup BEFORE INSERT ON material_cleanup_item "
                + "FOR EACH ROW EXECUTE FUNCTION app.fail_p2_cleanup()");
        try {
            assertThat(user.delete(root, true).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT state FROM material_resume WHERE id = ?",
                    String.class, root)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                    Integer.class, root)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_delete_request WHERE resume_id = ?",
                    Integer.class, root)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_p2_cleanup ON material_cleanup_item");
            jdbc.execute("DROP FUNCTION app.fail_p2_cleanup()");
        }
        assertThat(user.delete(root, true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isZero();
    }

    @Test
    void directAccessCsrfAndDeletionStayWithinOwner() throws Exception {
        Client alice = new Client(a);
        Client bob = new Client(b);
        Client keeper = new Client(maintainer);
        Client anonymous = new Client();
        anonymous.csrf();
        UUID op = UUID.randomUUID();
        UUID root = UUID.fromString((String) json(alice.create("a".repeat(100), op))
                .get("resumeId"));
        UUID bobRoot = UUID.fromString((String) json(bob.create("b".repeat(100), UUID.randomUUID()))
                .get("resumeId"));
        String path = "/api/materials/resumes/" + root;
        assertThat(anonymous.get(path).statusCode()).isEqualTo(401);
        assertThat(anonymous.create("a".repeat(100), UUID.randomUUID()).statusCode()).isEqualTo(401);
        assertThat(bob.get(path).statusCode()).isEqualTo(404);
        assertThat(bob.get("/api/materials/resumes").body()).doesNotContain(root.toString());
        assertThat(bob.addVersion(root, "z".repeat(100), UUID.randomUUID()).statusCode())
                .isEqualTo(404);
        assertThat(bob.delete(root, true).statusCode()).isEqualTo(404);
        assertThat(keeper.get(path).statusCode()).isEqualTo(403);
        assertThat(keeper.get("/api/materials/resumes").statusCode()).isEqualTo(403);
        assertThat(keeper.create("k".repeat(100), UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(alice.delete(root, false).statusCode()).isEqualTo(403);
        assertThat(alice.get(path).statusCode()).isEqualTo(200);
        assertThat(json(alice.get(path + "/deletion-impact")).get("versionCount")).isEqualTo(1);

        HttpResponse<String> deleted = alice.delete(root, true);
        assertThat(deleted.statusCode()).isEqualTo(200);
        String requestId = (String) json(deleted).get("requestId");
        assertThat(json(alice.delete(root, true)).get("requestId")).isEqualTo(requestId);
        assertThat(alice.get(path).statusCode()).isEqualTo(404);
        assertThat(bob.get(path + "/deletion").statusCode()).isEqualTo(404);
        assertThat(json(alice.get(path + "/deletion")).get("state")).isEqualTo("CLEANED");
        assertThat(alice.create("a".repeat(100), op).statusCode()).isEqualTo(404);
        assertThat(bob.get("/api/materials/resumes/" + bobRoot).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_cleanup_item "
                + "WHERE delete_request_id = ?", Integer.class, UUID.fromString(requestId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM material_resume WHERE id = ?",
                String.class, root)).isEqualTo("DELETED");
    }

    @Test
    void concurrentAppendAndDeleteCannotLeaveUntrackedBody() throws Exception {
        Client first = new Client(a);
        Client second = new Client(a);
        UUID root = UUID.fromString((String) json(first.create("x".repeat(100), UUID.randomUUID()))
                .get("resumeId"));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var append = executor.submit(() -> {
                gate.await();
                return first.addVersion(root, "y".repeat(100), UUID.randomUUID()).statusCode();
            });
            var delete = executor.submit(() -> {
                gate.await();
                return second.delete(root, true).statusCode();
            });
            gate.countDown();
            assertThat(append.get(30, TimeUnit.SECONDS)).isIn(201, 404);
            assertThat(delete.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(first.get("/api/materials/resumes/" + root).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_cleanup_item i "
                + "JOIN material_delete_request d ON d.id = i.delete_request_id "
                + "WHERE d.resume_id = ?", Integer.class, root)).isBetween(1, 2);
    }

    private static Map<String, Object> json(HttpResponse<String> response) {
        return JsonParserFactory.getJsonParser().parseMap(response.body());
    }

    private record Account(UUID id, String login, String password) {
    }

    private static final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        private String token;

        Client() {
        }

        Client(Account account) throws Exception {
            String before = csrf();
            String form = "username=" + URLEncoder.encode(account.login(), StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(account.password(), StandardCharsets.UTF_8);
            assertThat(send("POST", "/api/auth/login", form, before, null).statusCode()).isEqualTo(200);
            token = csrf();
        }

        String csrf() throws Exception {
            String result = (String) json(get("/api/auth/csrf")).get("token");
            token = result;
            return result;
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, null, null);
        }

        HttpResponse<String> create(String body, UUID operation) throws Exception {
            return send("POST", "/api/materials/resumes", bodyJson(body), token, operation);
        }

        Map<String, Object> createWithoutReadingBody(String body, UUID operation) throws Exception {
            var builder = HttpRequest.newBuilder(base.resolve("/api/materials/resumes"))
                    .header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", token)
                    .header("X-Operation-Id", operation.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson(body)));
            assertThat(http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(201);
            return json(get("/api/materials/resumes/operations/" + operation));
        }

        HttpResponse<String> addVersion(UUID root, String body, UUID operation) throws Exception {
            return send("POST", "/api/materials/resumes/" + root + "/versions",
                    bodyJson(body), token, operation);
        }

        HttpResponse<String> delete(UUID root, boolean withCsrf) throws Exception {
            return send("DELETE", "/api/materials/resumes/" + root, "", withCsrf ? token : null, null);
        }

        private String bodyJson(String body) {
            return "{\"body\":\"" + body.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", "\\r").replace("\n", "\\n") + "\"}";
        }

        private HttpResponse<String> send(String method, String path, String body,
                String csrf, UUID operation) throws Exception {
            var builder = HttpRequest.newBuilder(base.resolve(path)).header("Accept", "application/json");
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (operation != null) builder.header("X-Operation-Id", operation.toString());
            if (method.equals("POST")) {
                builder.header("Content-Type", path.equals("/api/auth/login")
                        ? "application/x-www-form-urlencoded" : "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else if (method.equals("DELETE")) {
                builder.DELETE();
            } else {
                builder.GET();
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
