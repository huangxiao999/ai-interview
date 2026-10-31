package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import dev.interviewcoach.knowledge.KnowledgeService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMaintenanceIT {
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI base;
    private static Account maintainer;
    private static Account other;
    private static Account user;
    private static final String API = "/api/maintenance/knowledge";

    @BeforeAll
    static void startIsolated() {
        String database = System.getenv("P3_TEST_DATABASE");
        if (database == null || !database.matches("p3dt01_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("Run Verify-P3Knowledge.ps1 with a fresh isolated database");
        }
        String url = System.getenv("DB_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD")));
        assertThat(admin.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        assertThat(admin.queryForObject("SHOW server_version_num", Integer.class)).isBetween(160000, 169999);
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'", Integer.class)).isZero();
        var old = Flyway.configure().dataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))
                .schemas("app").defaultSchema("app").target("6").load();
        assertThat(old.migrate().migrationsExecuted).isEqualTo(6);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=app", System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD")));
        var history = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        var current = Flyway.configure().dataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))
                .schemas("app").defaultSchema("app").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.migrate().migrationsExecuted).isZero();
        current.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND version<>'7' ORDER BY installed_rank"))
                .isEqualTo(history);
        maintainer = account(AccountAuthority.KNOWLEDGE_MAINTAINER);
        other = account(AccountAuthority.KNOWLEDGE_MAINTAINER);
        user = account(AccountAuthority.TRIAL_USER);
        context = new SpringApplicationBuilder(InterviewCoachApplication.class).profiles("local")
                .run("--server.port=0", "--material.private-root=" + Path.of("../.cache", database, "private").toAbsolutePath().normalize());
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    static Account account(AccountAuthority authority) {
        UUID id = UUID.randomUUID();
        String login = "k." + id;
        String secret = "synthetic-" + UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(id, login, new PasswordHashes().encode(secret), Set.of(authority));
        return new Account(id, login, secret);
    }
    @AfterAll static void close() { if (context != null) context.close(); }

    @Test void approvedSourceGateAndImmutableHistory() throws Exception {
        Client c = new Client(maintainer);
        UUID op = UUID.randomUUID();
        var first = created(c, input("Synthetic first"), op, null);
        UUID version = id(first, "versionId");
        var sources = context.getBean(KnowledgeService.class);
        assertThat(sources.findApproved(version)).isEmpty();
        UUID review = UUID.randomUUID();
        var approved = review(c, first, review, "APPROVE", "Synthetic verified source");
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(sources.findApproved(version).orElseThrow().body()).isEqualTo("Synthetic first");
        assertThat(json(review(c, first, review, "APPROVE", "Synthetic verified source"))).isEqualTo(json(approved));
        var second = created(c, input("Synthetic second"), UUID.randomUUID(), id(first,"documentId"));
        assertThat(second.get("versionNo")).isEqualTo(2);
        assertThat(review(c, second, UUID.randomUUID(), "REJECT", "Not verified").statusCode()).isEqualTo(200);
        assertThat(sources.findApproved(id(second,"versionId"))).isEmpty();
        assertThat(sources.findApproved(version).orElseThrow().body()).isEqualTo("Synthetic first");
        assertThat(json(c.get(API + "/operations/" + op)).get("version")).isInstanceOf(Map.class);
        assertThat(((Map<?,?>)json(c.get(API + "/operations/" + op)).get("version")).get("versionId")).isEqualTo(version.toString());
    }

    @Test void requestIdentityIncludesOriginalTargetAndBody() throws Exception {
        Client c = new Client(maintainer);
        UUID op = UUID.randomUUID();
        var first = created(c, input("A\r\nB"), op, null);
        assertThat(created(c, input("  A\nB  "), op, null).get("versionId")).isEqualTo(first.get("versionId"));
        assertThat(c.post(API+"/documents", input("Different"),op).statusCode()).isEqualTo(409);
        assertThat(c.post(API+"/documents/"+first.get("documentId")+"/versions",input("A\nB"),op).statusCode()).isEqualTo(409);
        var badReview = new LinkedHashMap<>(reviewInput(first,"APPROVE","Verified")); badReview.put("digest","old-digest");
        assertThat(c.post(reviewPath(first),badReview,UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(c.post(reviewPath(first),reviewInput(first,"APPROVE","Verified"),op).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_version WHERE document_id=?",Integer.class,id(first,"documentId"))).isEqualTo(1);
    }

    @Test void authenticationRoleCsrfAndExpectedAccountProtectEveryWrite() throws Exception {
        Client c = new Client(maintainer);
        Client u = new Client(user);
        Client anonymous = new Client(null);
        assertThat(anonymous.get(API+"/documents").statusCode()).isEqualTo(401);
        assertThat(u.post(API+"/documents",input("Unauthorised"),UUID.randomUUID()).statusCode()).isEqualTo(403);
        String token = c.token; c.token=null;
        assertThat(c.post(API+"/documents",input("Missing csrf"),UUID.randomUUID()).statusCode()).isEqualTo(403);
        c.token=token; c.expected=user.id();
        assertThat(c.post(API+"/documents",input("Wrong account"),UUID.randomUUID()).statusCode()).isEqualTo(409);
        c.expected=maintainer.id();
        var v = created(c,input("Role protected"),UUID.randomUUID(),null);
        assertThat(u.post(reviewPath(v),reviewInput(v,"APPROVE","No"),UUID.randomUUID()).statusCode()).isEqualTo(403);
        var privateResume = json(u.post("/api/materials/resumes",Map.of("body","Synthetic private ".repeat(10)),UUID.randomUUID()));
        assertThat(c.get("/api/materials/resumes/"+privateResume.get("resumeId")).statusCode()).isEqualTo(403);
        assertThat(u.get(API+"/documents/"+v.get("documentId")+"/versions/"+v.get("versionId")).statusCode()).isEqualTo(403);
    }

    @Test void inputBoundsAndDangerousSourcesAreRejectedWithoutTruncation() throws Exception {
        Client c = new Client(maintainer);
        for (String url : new String[]{"javascript:alert(1)","file:///C:/private", "https://user:secret@example.invalid", "https:///missing"}) {
            var data = input("Synthetic"); data.put("sourceUrl",url);
            assertThat(c.post(API+"/documents",data,UUID.randomUUID()).statusCode()).isEqualTo(400);
        }
        assertThat(c.post(API+"/documents",input("x".repeat(100_001)),UUID.randomUUID()).statusCode()).isEqualTo(400);
        var data = input("😀".repeat(100_000));
        assertThat(created(c,data,UUID.randomUUID(),null).get("body")).isEqualTo(data.get("body"));
        var blank = input("   ");
        assertThat(c.post(API+"/documents",blank,UUID.randomUUID()).statusCode()).isEqualTo(400);
    }

    @Test void oppositeReviewersSerializeAndAppendIsExactlyOnce() throws Exception {
        Client a = new Client(maintainer); Client b = new Client(other);
        var v = created(a,input("Concurrent"),UUID.randomUUID(),null);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->review(a,v,UUID.randomUUID(),"APPROVE","Verified").statusCode());
            var two=executor.submit(()->review(b,v,UUID.randomUUID(),"REJECT","Not verified").statusCode());
            assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            UUID op=UUID.randomUUID(); Client same=new Client(maintainer);
            String path=API+"/documents/"+v.get("documentId")+"/versions";
            var three=executor.submit(()->a.post(path,input("V2"),op));
            var four=executor.submit(()->same.post(path,input("V2"),op));
            assertThat(json(three.get(20,TimeUnit.SECONDS))).isEqualTo(json(four.get(20,TimeUnit.SECONDS)));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_review WHERE version_id=?",Integer.class,id(v,"versionId"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_version WHERE document_id=?",Integer.class,id(v,"documentId"))).isEqualTo(2);
    }

    @Test void failedLocalTransactionsDoNotPublishVersionOrReview() throws Exception {
        Client c=new Client(maintainer);
        int roots=jdbc.queryForObject("SELECT count(*) FROM knowledge_document",Integer.class);
        jdbc.execute("CREATE FUNCTION fail_knowledge() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic fault'; END $$");
        jdbc.execute("CREATE TRIGGER fail_knowledge BEFORE INSERT ON knowledge_version FOR EACH ROW EXECUTE FUNCTION fail_knowledge()");
        UUID op=UUID.randomUUID();
        try { assertThat(c.post(API+"/documents",input("Rollback"),op).statusCode()).isEqualTo(503); }
        finally { jdbc.execute("DROP TRIGGER fail_knowledge ON knowledge_version"); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_document",Integer.class)).isEqualTo(roots);
        var v=created(c,input("Rollback"),op,null);
        jdbc.execute("CREATE TRIGGER fail_knowledge BEFORE INSERT ON knowledge_review FOR EACH ROW EXECUTE FUNCTION fail_knowledge()");
        try { assertThat(review(c,v,UUID.randomUUID(),"APPROVE","Verified").statusCode()).isEqualTo(503); }
        finally { jdbc.execute("DROP TRIGGER fail_knowledge ON knowledge_review"); jdbc.execute("DROP FUNCTION fail_knowledge()"); }
        assertThat(context.getBean(KnowledgeService.class).findApproved(id(v,"versionId"))).isEmpty();
    }

    @Test void resetAheadOfBlockedOldReviewRejectsTheLateWrite() throws Exception {
        Account owner=account(AccountAuthority.KNOWLEDGE_MAINTAINER); Client c=new Client(owner);
        var v=created(c,input("Revocation"),UUID.randomUUID(),null);
        try (var locked=DriverManager.getConnection(System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
                var executor=Executors.newFixedThreadPool(2)) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM identity_account WHERE id=? FOR UPDATE")) {
                statement.setObject(1,owner.id()); assertThat(statement.executeQuery().next()).isTrue();
            }
            var reset=executor.submit(()->context.getBean(TrialAccountPasswordResetter.class).reset(owner.id()));
            awaitWaiters(1);
            var review=executor.submit(()->review(c,v,UUID.randomUUID(),"APPROVE","Late").statusCode());
            awaitWaiters(2); locked.commit();
            assertThat(reset.get(20,TimeUnit.SECONDS).credentialVersion()).isEqualTo(2);
            assertThat(review.get(20,TimeUnit.SECONDS)).isEqualTo(401);
        }
        assertThat(context.getBean(KnowledgeService.class).findApproved(id(v,"versionId"))).isEmpty();
    }

    private static void awaitWaiters(int expected) throws Exception {
        for(int i=0;i<100;i++) {
            if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND query LIKE '%identity_account%FOR UPDATE%'",Integer.class)>=expected) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Expected account lock contention");
    }

    @Test void realChromeMaintenanceFlow() throws Exception {
        Account browserAccount=account(AccountAuthority.KNOWLEDGE_MAINTAINER);
        int port; try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        var viteCommand=new ProcessBuilder("node","node_modules/vite/bin/vite.js","--host","127.0.0.1","--port",Integer.toString(port),"--strictPort");
        viteCommand.directory(Path.of("../frontend").toFile()); viteCommand.environment().put("BACKEND_URL",base.toString());
        viteCommand.redirectErrorStream(true).redirectOutput(Path.of("../.cache",System.getenv("P3_TEST_DATABASE")+"-vite.log").toFile());
        Process vite=viteCommand.start();
        try {
            URI frontend=URI.create("http://127.0.0.1:"+port);
            boolean ready=false;
            for(int i=0;i<100;i++) { try {
                var connection=(HttpURLConnection)frontend.resolve("/login").toURL().openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(500);connection.setReadTimeout(500);
                if(connection.getResponseCode()==200) { ready=true;break; }
            } catch(java.io.IOException ex) { Thread.sleep(100); } }
            assertThat(ready).isTrue();
            var browser=new ProcessBuilder("node",Path.of("../scripts/Verify-P3KnowledgeBrowser.mjs").toAbsolutePath().normalize().toString());
            browser.environment().put("P3_FRONTEND_BASE",frontend.toString());
            browser.environment().put("P3_BROWSER_USER",browserAccount.login());
            browser.environment().put("P3_BROWSER_PASSWORD",browserAccount.secret());
            browser.environment().put("P3_OTHER_USER",user.login());browser.environment().put("P3_OTHER_PASSWORD",user.secret());
            browser.redirectErrorStream(true).redirectOutput(Path.of("../.cache",System.getenv("P3_TEST_DATABASE")+"-browser.log").toFile());
            var process=browser.start();
            try { assertThat(process.waitFor(120,TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
            finally { if(process.isAlive()) process.destroyForcibly(); }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_version WHERE submitted_by=?",Integer.class,browserAccount.id())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_review WHERE reviewer_id=?",Integer.class,browserAccount.id())).isEqualTo(2);
        } finally { vite.destroy(); if(!vite.waitFor(5,TimeUnit.SECONDS)) vite.destroyForcibly(); }
    }

    static Map<String,Object> input(String body) {
        var m=new LinkedHashMap<String,Object>(); m.put("title","Synthetic knowledge");m.put("body",body);
        m.put("sourceUrl","https://example.invalid/approved-source");m.put("sourceAnchor","section 1");
        m.put("applicability","Java 21 synthetic fixture, no factual quality claim");m.put("usageNote","Synthetic fixture");
        m.put("verificationNote","Synthetic workflow only");m.put("verifiedOn","2026-10-31");return m;
    }
    static Map<String,Object> created(Client c,Map<String,Object> input,UUID operation,UUID root) throws Exception {
        var response=c.post(API+(root==null?"/documents":"/documents/"+root+"/versions"),input,operation);
        assertThat(response.statusCode()).isEqualTo(200);return json(response);
    }
    static String reviewPath(Map<String,Object> v) { return API+"/documents/"+v.get("documentId")+"/versions/"+v.get("versionId")+"/review"; }
    static Map<String,Object> reviewInput(Map<String,Object> v,String decision,String reason) { return Map.of("digest",v.get("digest"),"decision",decision,"reason",reason); }
    static HttpResponse<String> review(Client c,Map<String,Object> v,UUID operation,String decision,String reason) throws Exception { return c.post(reviewPath(v),reviewInput(v,decision,reason),operation); }
    static Map<String,Object> json(HttpResponse<String> response) { return JsonParserFactory.getJsonParser().parseMap(response.body()); }
    static UUID id(Map<String,Object> v,String key) { return UUID.fromString((String)v.get(key)); }
    record Account(UUID id,String login,String secret) { }
    static final class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        UUID expected; String token;
        Client(Account account) throws Exception {
            if(account==null) return;
            expected=account.id();token=(String)json(get("/api/auth/csrf")).get("token");
            String form="username="+URLEncoder.encode(account.login(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(account.secret(),StandardCharsets.UTF_8);
            var request=HttpRequest.newBuilder(base.resolve("/api/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",token).POST(HttpRequest.BodyPublishers.ofString(form)).build();
            assertThat(http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            token=(String)json(get("/api/auth/csrf")).get("token");
        }
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> post(String path,Map<String,Object> body,UUID operation) throws Exception {
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
            if(token!=null) builder.header("X-CSRF-TOKEN",token);
            if(expected!=null) builder.header("X-Expected-Account-Id",expected.toString());
            builder.header("X-Operation-Id",operation.toString());
            String json=context.getBean(tools.jackson.databind.ObjectMapper.class).writeValueAsString(body);
            return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
