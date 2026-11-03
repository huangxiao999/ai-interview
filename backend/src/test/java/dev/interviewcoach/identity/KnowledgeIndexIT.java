package dev.interviewcoach.identity;

import com.sun.net.httpserver.HttpServer;
import dev.interviewcoach.InterviewCoachApplication;
import dev.interviewcoach.knowledge.AliyunEmbeddingGateway;
import dev.interviewcoach.knowledge.EmbeddingGateway;
import dev.interviewcoach.knowledge.JdkHttpTransportPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeIndexIT {
    static final String API="/api/maintenance/knowledge";
    static final ObjectMapper JSON=JsonMapper.builder().build();
    static final ConcurrentHashMap<String,Integer> CALLS=new ConcurrentHashMap<>();
    static final AtomicBoolean COMMIT_FAULT=new AtomicBoolean();
    static final AtomicBoolean ACCEPT_COMMIT_FAULT=new AtomicBoolean();
    static final AtomicInteger ACCEPT_ACK_LOSSES=new AtomicInteger();
    static final AtomicBoolean PAUSE_UNAVAILABLE=new AtomicBoolean();
    static final AtomicInteger PAUSE_FAILURES=new AtomicInteger();
    static final AtomicBoolean PAUSE_COMMIT_FAULT=new AtomicBoolean();
    static volatile CountDownLatch pauseCommitted=new CountDownLatch(0);
    static volatile CountDownLatch releasePauseAck=new CountDownLatch(0);
    static volatile CountDownLatch slowRelease=new CountDownLatch(0);
    static ConfigurableApplicationContext context;
    static HttpServer supplier;
    static ExecutorService supplierThreads;
    static JdbcTemplate jdbc;
    static URI base;
    static String database;
    static String mockUrl;
    static Http2IndexFixture http2;
    static Account maintainer,other,user;

    @BeforeAll static void start() throws Exception {
        database=System.getenv("P3_INDEX_TEST_DATABASE");
        if(database==null || !database.matches("p3dt02_verify_[a-f0-9]{16}")) throw new IllegalStateException("Fresh isolated PG16 database required");
        var admin=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
        assertThat(admin.queryForObject("SELECT current_database()",String.class)).isEqualTo(database);
        assertThat(admin.queryForObject("SHOW server_version_num",Integer.class)).isBetween(160000,169999);
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'",Integer.class)).isZero();
        var old=Flyway.configure().dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).schemas("app").defaultSchema("app").target("7").load();
        assertThat(old.migrate().migrationsExecuted).isEqualTo(7);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
        var history=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        var current=Flyway.configure().dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).schemas("app").defaultSchema("app").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(2);assertThat(current.migrate().migrationsExecuted).isZero();current.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND version NOT IN ('8','9') ORDER BY installed_rank")).isEqualTo(history);
        supplier=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        supplierThreads=Executors.newFixedThreadPool(4);supplier.setExecutor(supplierThreads);
        supplier.createContext("/embedding",exchange->{
            try {
                var body=JSON.readTree(exchange.getRequestBody().readAllBytes());
                assertThat(body.path("model").asText()).isEqualTo("qwen3.7-text-embedding-flash");
                assertThat(body.path("parameters").path("dimension").asInt()).isEqualTo(1024);
                assertThat(body.path("parameters").path("text_type").asText()).isEqualTo("document");
                var texts=body.path("input").path("texts");
                String marker=texts.get(0).asText().split("\\s",2)[0];
                int call=CALLS.merge(marker,1,Integer::sum);
                if(marker.equals("[RATE_LIMIT]")) { exchange.sendResponseHeaders(429,-1);return; }
                if(marker.equals("[DROP]") || marker.equals("[DROP_RECEIPT]") || marker.equals("[BROWSER]") && call==1) { exchange.close();return; }
                if(marker.startsWith("[SLOW")) slowRelease.await(15,TimeUnit.SECONDS);
                var vectors=new ArrayList<Map<String,Object>>();
                for(int i=0;i<texts.size();i++) {
                    var vector=new ArrayList<Double>(Collections.nCopies(marker.startsWith("[BAD_DIM") || marker.equals("[BAD_USAGE_DIM]") || marker.equals("[BAD_ID_DIM]")?2:1024,0d));
                    if(!marker.equals("[ZERO]")) vector.set(0,marker.equals("[NONFINITE]")?Double.POSITIVE_INFINITY:1d);
                    vectors.add(Map.of("index",marker.startsWith("[BAD_MAP")?i+1:i,"embedding",vector));
                }
                if(marker.startsWith("[PARTIAL")) vectors.removeLast();
                var responseBody=new LinkedHashMap<String,Object>();
                responseBody.put("embeddings",vectors);responseBody.put("request_id",marker.equals("[BAD_ID_DIM]")?"invalid\nrequest":"synthetic-"+UUID.randomUUID());
                responseBody.put("usage",marker.equals("[UNKNOWN_USAGE]") || marker.equals("[BAD_USAGE_DIM]")?Map.of("prompt_tokens",4,"total_tokens",5)
                        :Map.of("prompt_tokens",texts.size()*100,"total_tokens",texts.size()*100));
                byte[] response=marker.equals("[MALFORMED]")?"{invalid".getBytes(StandardCharsets.UTF_8):JSON.writeValueAsBytes(responseBody);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(marker.equals("[HTTP_RECEIPT]")?429:200,response.length);
                exchange.getResponseBody().write(response);
            } catch(Exception failure) { /* 仅外部故障端点；客户端观察断开并记录未知。 */ }
            finally { exchange.close(); }
        });
        supplier.start();mockUrl="http://127.0.0.1:"+supplier.getAddress().getPort()+"/embedding";
        http2=new Http2IndexFixture(database);
        maintainer=account(AccountAuthority.KNOWLEDGE_MAINTAINER);other=account(AccountAuthority.KNOWLEDGE_MAINTAINER);user=account(AccountAuthority.TRIAL_USER);
        startApplication();
    }

    static void startApplication() {
        startApplication(mockUrl,false);
    }
    static void startApplication(String endpoint,boolean tls) {
        var arguments=new ArrayList<>(List.of("--server.port=0","--BAILIAN_EMBEDDING_ENABLED=false","--index-test.endpoint="+endpoint,
                "--material.private-root="+Path.of("../.cache",database,"private").toAbsolutePath()));
        if(tls)arguments.add("--index-test.tls-store="+http2.store);
        context=new SpringApplicationBuilder(InterviewCoachApplication.class,ExternalFixture.class).profiles("local")
                .run(arguments.toArray(String[]::new));
        base=URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterAll static void close() { if(context!=null) context.close();if(supplier!=null)supplier.stop(0);if(supplierThreads!=null)supplierThreads.shutdownNow();if(http2!=null)http2.close(); }

    @Test @Order(1) void fixedBatchesIdempotencyAndCompleteVectors() throws Exception {
        Client a=new Client(maintainer);var version=approved(a,"[MULTI] "+"😀".repeat(10_500));UUID op=UUID.randomUUID();
        var first=create(a,version,op);String id=(String)first.get("buildId");
        assertThat(create(a,version,op).get("buildId")).isEqualTo(id);
        assertThat(create(new Client(other),version,UUID.randomUUID()).get("buildId")).isEqualTo(id);
        awaitState(id,"SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_batch WHERE build_id=?",Integer.class,UUID.fromString(id))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_vector v JOIN knowledge_index_batch b ON b.id=v.batch_id WHERE b.build_id=?",Integer.class,UUID.fromString(id))).isEqualTo(11);
        var body=jdbc.queryForList("SELECT body FROM knowledge_index_vector v JOIN knowledge_index_batch b ON b.id=v.batch_id WHERE b.build_id=? ORDER BY v.ordinal",String.class,UUID.fromString(id));
        assertThat(String.join("",body)).isEqualTo(version.get("body"));
        assertThat(jdbc.queryForObject("SELECT min(cardinality(embedding)) FROM knowledge_index_vector",Integer.class)).isEqualTo(1024);
        assertThat(a.get(API+"/index-operations/"+op).statusCode()).isEqualTo(200);
        assertThat(new Client(other).get(API+"/index-operations/"+op).statusCode()).isEqualTo(404);
        assertThat(a.post(API+"/documents",KnowledgeMaintenanceIT.input("reuse"),op).statusCode()).isEqualTo(409);
    }
    @Test @Order(2) void rolesCsrfDigestAndUnreviewedVersionAreRejected() throws Exception {
        Client a=new Client(maintainer);var v=approved(a,"[AUTH] source");
        String path=path(v);assertThat(new Client(null).get(API+"/index-builds/"+UUID.randomUUID()).statusCode()).isEqualTo(401);
        assertThat(new Client(user).post(path,Map.of("digest",v.get("digest")),UUID.randomUUID()).statusCode()).isEqualTo(403);
        String csrf=a.token;a.token=null;assertThat(a.post(path,Map.of("digest",v.get("digest")),UUID.randomUUID()).statusCode()).isEqualTo(403);a.token=csrf;
        a.expected=user.id();assertThat(a.post(path,Map.of("digest",v.get("digest")),UUID.randomUUID()).statusCode()).isEqualTo(409);a.expected=maintainer.id();
        assertThat(a.post(path,Map.of("digest","old"),UUID.randomUUID()).statusCode()).isEqualTo(409);
        var pending=json(a.post(API+"/documents",KnowledgeMaintenanceIT.input("[PENDING]"),UUID.randomUUID()));
        assertThat(a.post(path(pending),Map.of("digest",pending.get("digest")),UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(CALLS.getOrDefault("[AUTH]",0)).isZero();
    }
    @Test @Order(3) void malformedVectorsAndManualBudgetCannotBeReset() throws Exception {
        Client a=new Client(maintainer);var v=approved(a,"[BAD_DIM] source");var b=create(a,v,UUID.randomUUID());String id=(String)b.get("buildId");
        for(int attempt=1;attempt<=3;attempt++) {
            awaitState(id,attempt==3?"FAILED_FINAL":"WAITING_MANUAL");
            assertThat(CALLS.get("[BAD_DIM]")).isEqualTo(attempt);
            assertThat(create(a,v,UUID.randomUUID()).get("buildId")).isEqualTo(id);
            if(attempt<3) {
                assertThat(json(resume(a,id,false,UUID.randomUUID())).get("code")).isEqualTo("INDEX_RETRY_CONFIRMATION_REQUIRED");
                assertThat(resume(a,id,true,UUID.randomUUID()).statusCode()).isEqualTo(200);
            }
        }
        assertThat(resume(a,id,true,UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(vectorCount(id)).isZero();
        var mapping=create(a,approved(a,"[BAD_MAP] source"),UUID.randomUUID());awaitState((String)mapping.get("buildId"),"WAITING_MANUAL");
        assertThat(vectorCount((String)mapping.get("buildId"))).isZero();
        for(String marker:List.of("[ZERO]","[NONFINITE]","[PARTIAL]")) {
            var invalid=create(a,approved(a,marker+" source"),UUID.randomUUID());String invalidId=(String)invalid.get("buildId");
            awaitState(invalidId,"WAITING_MANUAL");assertThat(vectorCount(invalidId)).isZero();assertThat(CALLS.get(marker)).isEqualTo(1);
        }
    }
    @Test @Order(4) void responseLossRequiresPerAttemptAcknowledgment() throws Exception {
        Client a=new Client(maintainer);var b=create(a,approved(a,"[DROP] source"),UUID.randomUUID());String id=(String)b.get("buildId");
        awaitState(id,"WAITING_MANUAL");assertThat(CALLS.get("[DROP]")).isEqualTo(1);
        assertThat(json(resume(a,id,false,UUID.randomUUID())).get("code")).isEqualTo("INDEX_RETRY_CONFIRMATION_REQUIRED");
        UUID op=UUID.randomUUID();var payload=resumeInput(id,true);
        assertThat(a.post(API+"/index-builds/"+id+"/resume",payload,op).statusCode()).isEqualTo(200);
        assertThat(a.post(API+"/index-builds/"+id+"/resume",payload,op).statusCode()).isEqualTo(200);
        awaitState(id,"WAITING_MANUAL");assertThat(CALLS.get("[DROP]")).isEqualTo(2);
        assertThat(json(resume(a,id,false,UUID.randomUUID())).get("code")).isEqualTo("INDEX_RETRY_CONFIRMATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=? AND usage_unknown AND usage_tokens IS NULL",Integer.class,UUID.fromString(id))).isEqualTo(2);
    }
    @Test @Order(5) void leaseExpiryFencesLateResponse() throws Exception {
        slowRelease=new CountDownLatch(1);
        Client a=new Client(maintainer);String id=(String)create(a,approved(a,"[SLOW] source"),UUID.randomUUID()).get("buildId");
        await(()->CALLS.getOrDefault("[SLOW]",0)==1);
        jdbc.update("UPDATE knowledge_index_attempt SET deadline=started_at+interval '1 millisecond' WHERE batch_id IN (SELECT id FROM knowledge_index_batch WHERE build_id=?)",UUID.fromString(id));
        slowRelease.countDown();awaitState(id,"WAITING_MANUAL");assertThat(vectorCount(id)).isZero();
        assertThat(CALLS.get("[SLOW]")).isEqualTo(1);
    }
    @Test @Order(6) void committedDispatchWithLostAcknowledgmentDoesNotSend() throws Exception {
        Client a=new Client(maintainer);COMMIT_FAULT.set(true);
        String id=(String)create(a,approved(a,"[COMMIT_UNKNOWN] source"),UUID.randomUUID()).get("buildId");
        await(()->!COMMIT_FAULT.get());
        assertThat(CALLS.getOrDefault("[COMMIT_UNKNOWN]",0)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
        jdbc.update("UPDATE knowledge_index_attempt SET deadline=started_at+interval '1 millisecond' WHERE batch_id IN (SELECT id FROM knowledge_index_batch WHERE build_id=?)",UUID.fromString(id));
        awaitState(id,"WAITING_MANUAL");assertThat(CALLS.getOrDefault("[COMMIT_UNKNOWN]",0)).isZero();
    }
    @Test @Order(7) void candidateRollbackAndOriginalAcceptanceWithoutExtraCall() throws Exception {
        jdbc.execute("CREATE FUNCTION fail_index_accept() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic acceptance fault'; END $$");
        jdbc.execute("CREATE TRIGGER fail_index_accept BEFORE INSERT ON knowledge_index_vector FOR EACH ROW EXECUTE FUNCTION fail_index_accept()");
        Client a=new Client(maintainer);String id;
        try {
            id=(String)create(a,approved(a,"[ACCEPT] source"),UUID.randomUUID()).get("buildId");
            awaitState(id,"WAITING_MANUAL");assertThat(vectorCount(id)).isZero();assertThat(CALLS.get("[ACCEPT]")).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER fail_index_accept ON knowledge_index_vector");jdbc.execute("DROP FUNCTION fail_index_accept()"); }
        assertThat(resume(a,id,false,UUID.randomUUID()).statusCode()).isEqualTo(200);awaitState(id,"SUCCEEDED");assertThat(CALLS.get("[ACCEPT]")).isEqualTo(1);
    }
    @Test @Order(8) void actualWorkerProcessRestoresStoredCandidate() throws Exception {
        context.close();context=null;
        jdbc.execute("CREATE FUNCTION pause_index_accept() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(20); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER pause_index_accept BEFORE INSERT ON knowledge_index_vector FOR EACH ROW EXECUTE FUNCTION pause_index_accept()");
        Process child=null;String id;
        try {
            child=child();Client a=new Client(maintainer);
            id=(String)create(a,approved(a,"[RESTART] source"),UUID.randomUUID()).get("buildId");
            await(()->jdbc.queryForObject("SELECT count(*) FROM knowledge_index_candidate c JOIN knowledge_index_attempt a ON a.id=c.attempt_id JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",Integer.class,UUID.fromString(id))==1);
            child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();child=null;
            assertThat(vectorCount(id)).isZero();assertThat(CALLS.get("[RESTART]")).isEqualTo(1);
        } finally { if(child!=null){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}jdbc.execute("DROP TRIGGER pause_index_accept ON knowledge_index_vector");jdbc.execute("DROP FUNCTION pause_index_accept()"); }
        try { child=child();awaitState(id,"SUCCEEDED");assertThat(CALLS.get("[RESTART]")).isEqualTo(1); }
        finally { if(child!=null){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}startApplication(); }
    }
    @Test @Order(9) void unconfiguredProductionGatewayRefusesBeforeDispatch() throws Exception {
        context.close();context=null;
        try(var unconfigured=new SpringApplicationBuilder(InterviewCoachApplication.class).profiles("local").run("--server.port=0","--BAILIAN_EMBEDDING_ENABLED=false","--material.private-root="+Path.of("../.cache",database,"private"))) {
            base=URI.create("http://127.0.0.1:"+unconfigured.getEnvironment().getRequiredProperty("local.server.port"));
            Client a=new Client(maintainer);var v=approved(a,"[UNCONFIGURED] source");
            assertThat(json(a.post(path(v),Map.of("digest",v.get("digest")),UUID.randomUUID())).get("code")).isEqualTo("INDEX_SERVICE_UNAVAILABLE");
            assertThat(CALLS.getOrDefault("[UNCONFIGURED]",0)).isZero();
        } finally { startApplication(); }
    }
    @Test @Order(10) void chromeOriginalOperationAndManualRecovery() throws Exception {
        Account browser=account(AccountAuthority.KNOWLEDGE_MAINTAINER);Client c=new Client(browser);
        var v=approved(c,"[BROWSER] synthetic public source");
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var command=new ProcessBuilder("node","node_modules/vite/bin/vite.js","--host","127.0.0.1","--port",Integer.toString(port),"--strictPort");
        command.directory(Path.of("../frontend").toFile());command.environment().put("BACKEND_URL",base.toString());
        command.redirectErrorStream(true).redirectOutput(Path.of("../.cache",database+"-vite.log").toFile());Process vite=command.start();
        try {
            await(()->healthy(URI.create("http://127.0.0.1:"+port+"/login")));
            var browserCommand=new ProcessBuilder("node",Path.of("../scripts/Verify-P3IndexBrowser.mjs").toAbsolutePath().toString());
            browserCommand.environment().put("P3_FRONTEND_BASE","http://127.0.0.1:"+port);browserCommand.environment().put("P3_BROWSER_USER",browser.login());browserCommand.environment().put("P3_BROWSER_PASSWORD",browser.password());
            browserCommand.environment().put("P3_INDEX_VERSION",v.get("versionId").toString());browserCommand.environment().put("P3_INDEX_TEST_DATABASE",database);
            browserCommand.redirectErrorStream(true).redirectOutput(Path.of("../.cache",database+"-browser.log").toFile());Process browserProcess=browserCommand.start();
            try { assertThat(browserProcess.waitFor(120,TimeUnit.SECONDS)).isTrue();assertThat(browserProcess.exitValue()).isZero(); }
            finally { if(browserProcess.isAlive())browserProcess.destroyForcibly(); }
            assertThat(CALLS.get("[BROWSER]")).isEqualTo(2);
        } finally { vite.destroy();if(!vite.waitFor(5,TimeUnit.SECONDS))vite.destroyForcibly(); }
    }

    @Test @Order(11) void queuedTimeoutDoesNotSpendAnAttempt() throws Exception {
        slowRelease=new CountDownLatch(1);Client a=new Client(maintainer);
        String blocking=(String)create(a,approved(a,"[SLOW_QUEUE] source"),UUID.randomUUID()).get("buildId");
        await(()->CALLS.getOrDefault("[SLOW_QUEUE]",0)==1);
        String queued=(String)create(a,approved(a,"[QUEUED] source"),UUID.randomUUID()).get("buildId");
        jdbc.update("UPDATE knowledge_index_batch SET queued_at=clock_timestamp()-interval '21 seconds' WHERE build_id=?",UUID.fromString(queued));
        slowRelease.countDown();awaitState(blocking,"SUCCEEDED");awaitState(queued,"WAITING_MANUAL");
        assertThat(CALLS.getOrDefault("[QUEUED]",0)).isZero();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM knowledge_index_batch WHERE build_id=?",Integer.class,UUID.fromString(queued))).isZero();
        assertThat(resume(a,queued,false,UUID.randomUUID()).statusCode()).isEqualTo(200);awaitState(queued,"SUCCEEDED");
        assertThat(CALLS.get("[QUEUED]")).isEqualTo(1);
    }
    @Test @Order(12) void concurrentMaintainersShareOneBinding() throws Exception {
        Client a=new Client(maintainer),b=new Client(other);var source=approved(a,"[CONCURRENT] source");
        try(var requests=Executors.newFixedThreadPool(2)) {
            var one=requests.submit(()->create(a,source,UUID.randomUUID()));var two=requests.submit(()->create(b,source,UUID.randomUUID()));
            String id=(String)one.get(20,TimeUnit.SECONDS).get("buildId");assertThat(two.get(20,TimeUnit.SECONDS).get("buildId")).isEqualTo(id);
            awaitState(id,"SUCCEEDED");assertThat(CALLS.get("[CONCURRENT]")).isEqualTo(1);
        }
    }
    @Test @Order(13) void revokedMaintainerCannotReceiveLatePublicCandidate() throws Exception {
        slowRelease=new CountDownLatch(1);Account owner=account(AccountAuthority.KNOWLEDGE_MAINTAINER);Client a=new Client(owner);
        String id=(String)create(a,approved(a,"[SLOW_REVOKE] source"),UUID.randomUUID()).get("buildId");
        await(()->CALLS.getOrDefault("[SLOW_REVOKE]",0)==1);
        jdbc.update("DELETE FROM identity_account_authority WHERE account_id=?",owner.id());
        slowRelease.countDown();awaitState(id,"INVALIDATED");assertThat(vectorCount(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_candidate c JOIN knowledge_index_attempt a ON a.id=c.attempt_id JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",Integer.class,UUID.fromString(id))).isZero();
    }
    @Test @Order(14) void passwordResetDoesNotCancelAcceptedBackgroundWork() throws Exception {
        slowRelease=new CountDownLatch(1);Account owner=account(AccountAuthority.KNOWLEDGE_MAINTAINER);Client a=new Client(owner);
        String id=(String)create(a,approved(a,"[SLOW_RESET] source"),UUID.randomUUID()).get("buildId");
        await(()->CALLS.getOrDefault("[SLOW_RESET]",0)==1);
        context.getBean(TrialAccountPasswordResetter.class).reset(owner.id());
        slowRelease.countDown();awaitState(id,"SUCCEEDED");
        assertThat(a.get(API+"/index-builds/"+id).statusCode()).isEqualTo(401);
        assertThat(CALLS.get("[SLOW_RESET]")).isEqualTo(1);
    }
    @Test @Order(15) void transportObeysRemainingAbsoluteDeadline() {
        slowRelease=new CountDownLatch(1);
        var gateway=new AliyunEmbeddingGateway(URI.create(mockUrl),"synthetic","fixture","fixture",JSON);
        long started=System.nanoTime();
        try {
            assertThatThrownBy(()->gateway.embed(List.of("[SLOW_HTTP] source"),java.time.Instant.now().plusMillis(300))).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
        } finally { slowRelease.countDown();gateway.close(); }
    }

    @Test @Order(16) void failedPauseRegistrationMustNotAutomaticallyAcceptAfterRecovery() throws Exception {
        jdbc.execute("CREATE FUNCTION fail_pause_accept() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic acceptance fault'; END $$");
        jdbc.execute("CREATE TRIGGER fail_pause_accept BEFORE INSERT ON knowledge_index_vector FOR EACH ROW EXECUTE FUNCTION fail_pause_accept()");
        Client a=new Client(maintainer);String id;
        int failures=PAUSE_FAILURES.get();PAUSE_UNAVAILABLE.set(true);
        try {
            id=(String)create(a,approved(a,"[PAUSE_FAILURE] source"),UUID.randomUUID()).get("buildId");
            await(()->PAUSE_FAILURES.get()>=failures+2);
        } finally {
            jdbc.execute("DROP TRIGGER fail_pause_accept ON knowledge_index_vector");jdbc.execute("DROP FUNCTION fail_pause_accept()");
        }
        try {
            // 已恢复接收写入能力；经过多个真实调度周期仍只能补记暂停，不能重新接收。
            int before=PAUSE_FAILURES.get();await(()->PAUSE_FAILURES.get()>=before+3);
            assertThat(vectorCount(id)).isZero();assertThat(CALLS.get("[PAUSE_FAILURE]")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM knowledge_index_build WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("CANDIDATE_READY");
        } finally { PAUSE_UNAVAILABLE.set(false); }
        awaitState(id,"WAITING_MANUAL");
        String canary=(String)create(a,approved(a,"[PAUSE_CANARY] source"),UUID.randomUUID()).get("buildId");awaitState(canary,"SUCCEEDED");
        assertThat(vectorCount(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_candidate c JOIN knowledge_index_attempt a ON a.id=c.attempt_id JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
        assertThat(resume(a,id,false,UUID.randomUUID()).statusCode()).isEqualTo(200);awaitState(id,"SUCCEEDED");
        assertThat(CALLS.get("[PAUSE_FAILURE]")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM knowledge_index_batch WHERE build_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
    }

    @Test @Order(17) void committedAcceptanceWithLostAcknowledgmentKeepsOriginalResult() throws Exception {
        Client a=new Client(maintainer);
        for(boolean multi:List.of(false,true)) {
            int losses=ACCEPT_ACK_LOSSES.get(),calls=CALLS.values().stream().mapToInt(Integer::intValue).sum();
            String marker=multi?"[ACCEPT_ACK_MULTI]":"[ACCEPT_ACK]";
            var v=approved(a,marker+" "+(multi?"😀".repeat(10_500):"source"));UUID operation=UUID.randomUUID();
            ACCEPT_COMMIT_FAULT.set(true);
            String id=(String)create(a,v,operation).get("buildId");
            await(()->ACCEPT_ACK_LOSSES.get()==losses+1);awaitState(id,"SUCCEEDED");
            assertThat(json(a.get(API+"/index-operations/"+operation))).containsEntry("buildId",id).containsEntry("state","SUCCEEDED");
            assertThat(create(a,v,UUID.randomUUID()).get("buildId")).isEqualTo(id);
            UUID attempt=jdbc.queryForList("SELECT current_attempt FROM knowledge_index_batch WHERE build_id=? ORDER BY ordinal",UUID.class,UUID.fromString(id)).getFirst();
            assertThat(a.post(API+"/index-builds/"+id+"/resume",Map.of("attemptId",attempt,"acceptPossibleDuplicateCharge",false),UUID.randomUUID()).statusCode()).isEqualTo(409);
            assertThat(vectorCount(id)).isEqualTo(multi?11:1);
            assertThat(jdbc.queryForList("SELECT attempt_count FROM knowledge_index_batch WHERE build_id=? ORDER BY ordinal",Integer.class,UUID.fromString(id))).containsOnly(1).hasSize(multi?2:1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_candidate c JOIN knowledge_index_attempt a ON a.id=c.attempt_id JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=? AND a.state='ACCEPTED'",Integer.class,UUID.fromString(id))).isEqualTo(multi?2:1);
            assertThat(CALLS.values().stream().mapToInt(Integer::intValue).sum()-calls).isEqualTo(multi?2:1);
            // 后续真实工作完成说明执行者已经走过未知提交后的核实，原构建不会被改回暂停。
            String next=(String)create(a,approved(a,marker+"_NEXT source"),UUID.randomUUID()).get("buildId");awaitState(next,"SUCCEEDED");
            assertThat(json(a.get(API+"/index-builds/"+id))).containsEntry("state","SUCCEEDED");
        }
    }

    @Test @Order(18) void stalePauseAfterLostAcknowledgmentCannotUndoManualRecovery() throws Exception {
        jdbc.execute("CREATE FUNCTION fail_pause_race() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic acceptance fault'; END $$");
        jdbc.execute("CREATE TRIGGER fail_pause_race BEFORE INSERT ON knowledge_index_vector FOR EACH ROW EXECUTE FUNCTION fail_pause_race()");
        pauseCommitted=new CountDownLatch(1);releasePauseAck=new CountDownLatch(1);PAUSE_COMMIT_FAULT.set(true);
        Client a=new Client(maintainer);String id;
        try {
            id=(String)create(a,approved(a,"[PAUSE_RACE] source"),UUID.randomUUID()).get("buildId");
            assertThat(pauseCommitted.await(15,TimeUnit.SECONDS)).isTrue();awaitState(id,"WAITING_MANUAL");
        } finally {
            jdbc.execute("DROP TRIGGER fail_pause_race ON knowledge_index_vector");jdbc.execute("DROP FUNCTION fail_pause_race()");
        }
        try {
            assertThat(resume(a,id,false,UUID.randomUUID()).statusCode()).isEqualTo(200);
        } finally { releasePauseAck.countDown();PAUSE_COMMIT_FAULT.set(false); }
        awaitState(id,"SUCCEEDED");assertThat(CALLS.get("[PAUSE_RACE]")).isEqualTo(1);
        assertThat(vectorCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM knowledge_index_batch WHERE build_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
    }

    @Test @Order(19) void flashUsageAndRequestIdentityArePersistedWithoutSecrets() throws Exception {
        Client a=new Client(maintainer);UUID operation=UUID.randomUUID();var v=approved(a,"[FLASH_USAGE] source");
        String id=(String)create(a,v,operation).get("buildId");awaitState(id,"SUCCEEDED");
        var row=jdbc.queryForMap("SELECT config_json::text,config_digest FROM knowledge_index_build WHERE id=?",UUID.fromString(id));
        var config=JSON.readTree(row.get("config_json").toString());
        assertThat(config.path("model").asText()).isEqualTo("qwen3.7-text-embedding-flash");
        assertThat(config.path("outputType").asText()).isEqualTo("dense");
        assertThat(config.path("adapterRevision").asText()).isEqualTo("dashscope-native-dense-v2");
        assertThat(row.get("config_json").toString()).doesNotContain("Bearer", "DASHSCOPE_API_KEY", "synthetic");
        var usage=jdbc.queryForMap("SELECT provider_request_id,usage_tokens,usage_unknown FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",UUID.fromString(id));
        assertThat(usage.get("provider_request_id").toString()).startsWith("synthetic-");
        assertThat(usage).containsEntry("usage_tokens",100L).containsEntry("usage_unknown",false);
        assertThat(create(a,v,operation).get("buildId")).isEqualTo(id);
        assertThat(create(a,v,UUID.randomUUID()).get("buildId")).isEqualTo(id);
        assertThat(CALLS.get("[FLASH_USAGE]")).isEqualTo(1);
    }

    @Test @Order(20) void contradictoryUsageStaysUnknownAndDoesNotBecomeZero() throws Exception {
        Client a=new Client(maintainer);String id=(String)create(a,approved(a,"[UNKNOWN_USAGE] source"),UUID.randomUUID()).get("buildId");
        awaitState(id,"SUCCEEDED");
        var usage=jdbc.queryForMap("SELECT usage_tokens,usage_unknown FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",UUID.fromString(id));
        assertThat(usage.get("usage_tokens")).isNull();assertThat(usage.get("usage_unknown")).isEqualTo(true);
        assertThat(CALLS.get("[UNKNOWN_USAGE]")).isEqualTo(1);
    }

    @Test @Order(21) void rateLimitRequiresEachChargeConfirmationAndNeverResetsBudget() throws Exception {
        Client a=new Client(maintainer);var v=approved(a,"[RATE_LIMIT] source");UUID operation=UUID.randomUUID();
        String id=(String)create(a,v,operation).get("buildId");awaitState(id,"WAITING_MANUAL");
        for(int attempt=1;attempt<=3;attempt++) {
            assertThat(CALLS.get("[RATE_LIMIT]")).isEqualTo(attempt);
            assertThat(create(a,v,UUID.randomUUID()).get("buildId")).isEqualTo(id);
            if(attempt==3) break;
            assertThat(resume(a,id,false,UUID.randomUUID()).statusCode()).isEqualTo(409);
            UUID resumeOperation=UUID.randomUUID();var input=resumeInput(id,true);
            assertThat(a.post(API+"/index-builds/"+id+"/resume",input,resumeOperation).statusCode()).isEqualTo(200);
            assertThat(a.post(API+"/index-builds/"+id+"/resume",input,resumeOperation).statusCode()).isEqualTo(200);
            awaitState(id,attempt==2?"FAILED_FINAL":"WAITING_MANUAL");
        }
        context.close();startApplication();a=new Client(maintainer);
        assertThat(resume(a,id,true,UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(json(a.get(API+"/index-operations/"+operation))).containsEntry("buildId",id).containsEntry("state","FAILED_FINAL");
        assertThat(CALLS.get("[RATE_LIMIT]")).isEqualTo(3);assertThat(vectorCount(id)).isZero();
    }

    @Test @Order(22) void legacyV4BindingIsQueriedButNeverReusedOrRedispatchedAsFlash() throws Exception {
        Client a=new Client(maintainer);
        // 仅种入明确的历史持久夹具；不冒充本轮曾执行旧供应商，也不修改旧manifest/数据库。
        for(boolean complete:List.of(true,false)) {
            String marker=complete?"[LEGACY_COMPLETE]":"[LEGACY_UNKNOWN]";
            var v=approved(a,marker+" source");UUID build=UUID.randomUUID(),operation=UUID.randomUUID();
            var legacy=new TreeMap<>(context.getBean(EmbeddingGateway.class).configuration());
            legacy.put("model","text-embedding-v4");legacy.remove("outputType");legacy.remove("adapterRevision");
            String config=JSON.writeValueAsString(legacy),input=v.get("documentId")+":"+v.get("versionId")+":"+v.get("digest");
            jdbc.update("INSERT INTO knowledge_index_build(id,document_id,version_id,owner_id,version_digest,config_digest,config_json,policy,state) VALUES(?,?,?,?,?,?,?::jsonb,?,?)",
                    build,UUID.fromString(v.get("documentId").toString()),UUID.fromString(v.get("versionId").toString()),maintainer.id(),v.get("digest"),sha(config),config,"paragraph-1000cp-batch10-no-overlap-v1",complete?"SUCCEEDED":"WAITING_MANUAL");
            jdbc.update("INSERT INTO knowledge_index_request(actor_id,operation_id,build_id,kind,input_digest) VALUES(?,?,?,'CREATE',?)",maintainer.id(),operation,build,sha(input));
            UUID batch=UUID.randomUUID(),attempt=UUID.randomUUID();
            jdbc.update("INSERT INTO knowledge_index_batch(id,build_id,ordinal,input_json,input_digest,state,attempt_count,generation,current_attempt) VALUES(?,?,0,'[]',?,?,1,1,?)",batch,build,sha("[]"),complete?"ACCEPTED":"WAITING_MANUAL",attempt);
            jdbc.update("INSERT INTO knowledge_index_attempt(id,batch_id,attempt_no,generation,executor_id,state,started_at,deadline,usage_unknown) VALUES(?,?,1,1,?,?,clock_timestamp(),clock_timestamp()+interval '60 seconds',true)",attempt,batch,UUID.randomUUID(),complete?"ACCEPTED":"EXTERNAL_UNKNOWN");
            if(complete) {
                Double[] vector=new Double[1024];Arrays.fill(vector,0d);vector[0]=0.25;
                jdbc.update("INSERT INTO knowledge_index_vector(batch_id,chunk_id,ordinal,start_offset,end_offset,body,digest,embedding) VALUES(?,?,0,0,1,'x',?,?)",batch,UUID.randomUUID(),sha("x"),vector);
            }
            var original=jdbc.queryForMap("SELECT * FROM knowledge_index_build WHERE id=?",build);
            assertThat(create(a,v,operation).get("buildId")).isEqualTo(build.toString());
            if(!complete) assertThat(resume(a,build.toString(),true,UUID.randomUUID()).statusCode()).isEqualTo(503);
            String fresh=(String)create(a,v,UUID.randomUUID()).get("buildId");assertThat(fresh).isNotEqualTo(build.toString());awaitState(fresh,"SUCCEEDED");
            assertThat(create(a,v,UUID.randomUUID()).get("buildId")).isEqualTo(fresh);
            assertThat(jdbc.queryForMap("SELECT * FROM knowledge_index_build WHERE id=?",build)).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM knowledge_index_batch WHERE id=?",Integer.class,batch)).isEqualTo(1);
            assertThat(vectorCount(build.toString())).isEqualTo(complete?1:0);
            if(complete) assertThat(jdbc.queryForObject("SELECT embedding[1] FROM knowledge_index_vector WHERE batch_id=?",Double.class,batch)).isEqualTo(0.25);
            assertThat(CALLS.get(marker)).isEqualTo(1);
        }
    }

    static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test @Order(23) void refusedHttp2StreamCannotHideASecondPostInsideOneAttempt() throws Exception {
        http2.defaultJdkControl();
        context.close();
        try {
            startApplication(http2.endpoint.toString(),true);Client a=new Client(maintainer);
            String id=(String)create(a,approved(a,"[H2_REFUSED] source"),UUID.randomUUID()).get("buildId");
            awaitState(id,"WAITING_MANUAL");
            for(int attempt=1;attempt<=3;attempt++) {
                assertThat(http2.calls("[H2_REFUSED]")).isEqualTo(attempt);
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM knowledge_index_batch WHERE build_id=?",Integer.class,UUID.fromString(id))).isEqualTo(attempt);
                if(attempt==3)break;
                assertThat(resume(a,id,false,UUID.randomUUID()).statusCode()).isEqualTo(409);
                assertThat(resume(a,id,true,UUID.randomUUID()).statusCode()).isEqualTo(200);
                awaitState(id,attempt==2?"FAILED_FINAL":"WAITING_MANUAL");
            }
            assertThat(vectorCount(id)).isZero();assertThat(resume(a,id,true,UUID.randomUUID()).statusCode()).isEqualTo(409);
            assertThat(http2.calls("[H2_REFUSED]")).isEqualTo(3);
            String bad=(String)create(a,approved(a,"[H2_BAD_DIM] source"),UUID.randomUUID()).get("buildId");awaitState(bad,"WAITING_MANUAL");
            var receipt=attemptReceipt(bad);
            assertThat(receipt).containsEntry("provider_request_id","synthetic-h2-1").containsEntry("usage_tokens",100L).containsEntry("usage_unknown",false);
            assertThat(http2.calls("[H2_BAD_DIM]")).isEqualTo(1);assertThat(vectorCount(bad)).isZero();
        } finally { context.close();startApplication(); }
    }

    @Test @Order(24) void rejectedCandidatesKeepOnlyIndependentlyValidReceiptFacts() throws Exception {
        Client a=new Client(maintainer);
        for(String marker:List.of("[BAD_DIM_META]","[BAD_MAP_META]","[PARTIAL_META]","[BAD_USAGE_DIM]","[BAD_ID_DIM]","[HTTP_RECEIPT]","[MALFORMED]","[DROP_RECEIPT]")) {
            String id=(String)create(a,approved(a,marker+" source"),UUID.randomUUID()).get("buildId");awaitState(id,"WAITING_MANUAL");
            var receipt=attemptReceipt(id);
            boolean idKnown=!List.of("[BAD_ID_DIM]","[MALFORMED]","[DROP_RECEIPT]").contains(marker);
            boolean tokensKnown=!List.of("[BAD_USAGE_DIM]","[MALFORMED]","[DROP_RECEIPT]").contains(marker);
            if(idKnown)assertThat(receipt.get("provider_request_id").toString()).startsWith("synthetic-");
            else assertThat(receipt.get("provider_request_id")).isNull();
            assertThat(receipt.get("usage_tokens")).isEqualTo(tokensKnown?100L:null);
            assertThat(receipt.get("usage_unknown")).isEqualTo(!tokensKnown);
            assertThat(vectorCount(id)).isZero();assertThat(CALLS.get(marker)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_candidate c JOIN knowledge_index_attempt a ON a.id=c.attempt_id JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",Integer.class,UUID.fromString(id))).isZero();
        }
    }

    @Test @Order(25) void lateResponseRecordsOriginalReceiptWithoutRevivingCandidateOrState() throws Exception {
        slowRelease=new CountDownLatch(1);Client a=new Client(maintainer);
        String id=(String)create(a,approved(a,"[SLOW_RECEIPT] source"),UUID.randomUUID()).get("buildId");
        await(()->CALLS.getOrDefault("[SLOW_RECEIPT]",0)==1);
        UUID original=jdbc.queryForObject("SELECT current_attempt FROM knowledge_index_batch WHERE build_id=?",UUID.class,UUID.fromString(id));
        jdbc.update("UPDATE knowledge_index_attempt SET deadline=started_at+interval '1 millisecond' WHERE id=?",original);
        slowRelease.countDown();awaitState(id,"WAITING_MANUAL");
        var receipt=attemptReceipt(id);
        assertThat(receipt.get("provider_request_id").toString()).startsWith("synthetic-");
        assertThat(receipt).containsEntry("usage_tokens",100L).containsEntry("usage_unknown",false);
        assertThat(vectorCount(id)).isZero();assertThat(CALLS.get("[SLOW_RECEIPT]")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM knowledge_index_attempt WHERE id=?",String.class,original)).isEqualTo("EXTERNAL_UNKNOWN");
        assertThat(resume(a,id,true,UUID.randomUUID()).statusCode()).isEqualTo(200);awaitState(id,"SUCCEEDED");
        assertThat(CALLS.get("[SLOW_RECEIPT]")).isEqualTo(2);assertThat(vectorCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT provider_request_id,usage_tokens,usage_unknown FROM knowledge_index_attempt WHERE id=?",original)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT state FROM knowledge_index_attempt WHERE id=?",String.class,original)).isEqualTo("EXTERNAL_UNKNOWN");
    }

    @Test @Order(26) void oldV4StoredCandidateSurvivesRestartAndKeepsItsOwnBinding() throws Exception {
        Client a=new Client(maintainer);String body="[LEGACY_CANDIDATE] source";var v=approved(a,body);
        UUID build=UUID.randomUUID(),batch=UUID.randomUUID(),attempt=UUID.randomUUID(),operation=UUID.randomUUID();
        var legacy=new TreeMap<>(context.getBean(EmbeddingGateway.class).configuration());legacy.put("model","text-embedding-v4");legacy.remove("outputType");legacy.remove("adapterRevision");
        String config=JSON.writeValueAsString(legacy);
        jdbc.update("INSERT INTO knowledge_index_build(id,document_id,version_id,owner_id,version_digest,config_digest,config_json,policy,state,error_category) VALUES(?,?,?,?,?,?,?::jsonb,?,'WAITING_MANUAL','ACCEPTANCE_UNKNOWN')",build,
                UUID.fromString(v.get("documentId").toString()),UUID.fromString(v.get("versionId").toString()),maintainer.id(),v.get("digest"),sha(config),config,"paragraph-1000cp-batch10-no-overlap-v1");
        var chunk=new LinkedHashMap<String,Object>();chunk.put("id",UUID.randomUUID());chunk.put("ordinal",0);chunk.put("startOffset",0);chunk.put("endOffset",body.length());chunk.put("text",body);chunk.put("digest",sha(body));
        String input=JSON.writeValueAsString(List.of(chunk));
        jdbc.update("INSERT INTO knowledge_index_batch(id,build_id,ordinal,input_json,input_digest,state,attempt_count,generation,current_attempt) VALUES(?,?,0,?::jsonb,?,'CANDIDATE_READY',1,1,?)",batch,build,input,sha(input),attempt);
        jdbc.update("INSERT INTO knowledge_index_attempt(id,batch_id,attempt_no,generation,executor_id,state,started_at,deadline,provider_request_id,usage_tokens,usage_unknown) VALUES(?,?,1,1,?,'RESPONSE_STORED',clock_timestamp(),clock_timestamp()+interval '60 seconds','synthetic-legacy',17,false)",attempt,batch,UUID.randomUUID());
        var vector=new ArrayList<Double>(Collections.nCopies(1024,0d));vector.set(0,0.5);
        jdbc.update("INSERT INTO knowledge_index_candidate(attempt_id,payload) VALUES(?,?::jsonb)",attempt,JSON.writeValueAsString(Map.of("vectors",List.of(vector),"requestId","synthetic-legacy","usageTokens",17)));
        jdbc.update("INSERT INTO knowledge_index_request(actor_id,operation_id,build_id,kind,input_digest) VALUES(?,?,?,'CREATE',?)",maintainer.id(),operation,build,sha(v.get("documentId")+":"+v.get("versionId")+":"+v.get("digest")));
        String stored=jdbc.queryForObject("SELECT payload::text FROM knowledge_index_candidate WHERE attempt_id=?",String.class,attempt);
        context.close();startApplication();awaitState(build.toString(),"SUCCEEDED");a=new Client(maintainer);
        assertThat(CALLS.getOrDefault("[LEGACY_CANDIDATE]",0)).isZero();
        assertThat(jdbc.queryForObject("SELECT embedding[1] FROM knowledge_index_vector WHERE batch_id=?",Double.class,batch)).isEqualTo(0.5);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM knowledge_index_candidate WHERE attempt_id=?",String.class,attempt)).isEqualTo(stored);
        assertThat(create(a,v,operation).get("buildId")).isEqualTo(build.toString());
        String fresh=(String)create(a,v,UUID.randomUUID()).get("buildId");assertThat(fresh).isNotEqualTo(build.toString());awaitState(fresh,"SUCCEEDED");
        assertThat(CALLS.get("[LEGACY_CANDIDATE]")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT config_json->>'model' FROM knowledge_index_build WHERE id=?",String.class,build)).isEqualTo("text-embedding-v4");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM knowledge_index_batch WHERE id=?",Integer.class,batch)).isEqualTo(1);
    }

    static Map<String,Object> attemptReceipt(String build) {
        return jdbc.queryForMap("SELECT provider_request_id,usage_tokens,usage_unknown FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.current_attempt=a.id WHERE b.build_id=?",UUID.fromString(build));
    }

    static Process child() throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}base=URI.create("http://127.0.0.1:"+port);
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin/java.exe").toString());command.addAll(JdkHttpTransportPolicy.jvmArguments());
        command.addAll(List.of("-cp",System.getProperty("java.class.path"),WorkerProcess.class.getName(),Integer.toString(port),mockUrl,database));
        var pb=new ProcessBuilder(command);
        pb.redirectErrorStream(true).redirectOutput(Path.of("../.cache",database+"-child-"+UUID.randomUUID()+".log").toFile());var process=pb.start();
        try { await(()->healthy(base.resolve("/api/auth/csrf")));return process; }
        catch(Exception|Error ex){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);throw ex;}
    }
    public static class WorkerProcess {
        public static void main(String[] args) { new SpringApplicationBuilder(InterviewCoachApplication.class,ExternalFixture.class).profiles("local").run("--server.port="+args[0],"--BAILIAN_EMBEDDING_ENABLED=false","--index-test.endpoint="+args[1],"--material.private-root="+Path.of("../.cache",args[2],"private")); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class ExternalFixture {
        @Bean @Primary EmbeddingGateway testExternalGateway(Environment env,ObjectMapper mapper) throws Exception {
            String store=env.getProperty("index-test.tls-store");
            return new AliyunEmbeddingGateway(URI.create(env.getRequiredProperty("index-test.endpoint")),"synthetic","fixture","fixture",mapper,
                    store==null?null:Http2IndexFixture.trust(Path.of(store)));
        }
        @Bean @Primary DataSource faultBoundaryDataSource() {
            var real=new DriverManagerDataSource(System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
            return new DelegatingDataSource(real) {
                @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
                @Override public Connection getConnection(String u,String p) throws SQLException { return wrap(super.getConnection(u,p)); }
            };
        }
        static Connection wrap(Connection real) {
            var dispatch=new AtomicBoolean();
            var acceptance=new AtomicBoolean();
            var pausing=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql) {
                        if(sql.contains("INSERT INTO knowledge_index_attempt"))dispatch.set(true);
                        if(sql.contains("INSERT INTO knowledge_index_vector"))acceptance.set(true);
                        if(sql.contains("error_category='ACCEPTANCE_UNKNOWN'")) {
                            pausing.set(true);
                            if(PAUSE_UNAVAILABLE.get()) { PAUSE_FAILURES.incrementAndGet();throw new SQLException("Synthetic pause registration unavailable","08006"); }
                        }
                    }
                    Object result=method.invoke(real,args);
                    if(method.getName().equals("commit") && dispatch.get() && COMMIT_FAULT.compareAndSet(true,false))throw new SQLException("Synthetic lost COMMIT acknowledgment after real PG commit","08006");
                    if(method.getName().equals("commit") && acceptance.get() && ACCEPT_COMMIT_FAULT.compareAndSet(true,false)) {
                        ACCEPT_ACK_LOSSES.incrementAndGet();throw new SQLException("Synthetic lost acceptance acknowledgment after real PG commit","08006");
                    }
                    if(method.getName().equals("commit") && pausing.get() && PAUSE_COMMIT_FAULT.compareAndSet(true,false)) {
                        pauseCommitted.countDown();
                        if(!releasePauseAck.await(15,TimeUnit.SECONDS))throw new SQLException("Synthetic pause acknowledgment gate timed out","08006");
                        throw new SQLException("Synthetic lost pause acknowledgment after real PG commit","08006");
                    }
                    return result;
                } catch(InvocationTargetException ex){throw ex.getCause();}
            });
        }
    }
    static Account account(AccountAuthority authority) { UUID id=UUID.randomUUID();String login="i."+id,password="synthetic-"+UUID.randomUUID();new JdbcAccountRepository(jdbc).insert(id,login,new PasswordHashes().encode(password),Set.of(authority));return new Account(id,login,password); }
    record Account(UUID id,String login,String password) { }
    static String path(Map<String,Object> v) { return API+"/documents/"+v.get("documentId")+"/versions/"+v.get("versionId")+"/index-builds"; }
    static Map<String,Object> approved(Client client,String body) throws Exception { var v=json(client.post(API+"/documents",KnowledgeMaintenanceIT.input(body),UUID.randomUUID()));assertThat(v).containsKey("versionId");assertThat(client.post(API+"/documents/"+v.get("documentId")+"/versions/"+v.get("versionId")+"/review",Map.of("digest",v.get("digest"),"decision","APPROVE","reason","Synthetic workflow fixture"),UUID.randomUUID()).statusCode()).isEqualTo(200);return v; }
    static Map<String,Object> create(Client c,Map<String,Object> v,UUID op) throws Exception { var r=c.post(path(v),Map.of("digest",v.get("digest")),op);assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return json(r); }
    static Map<String,Object> resumeInput(String id,boolean ack) { UUID attempt=jdbc.queryForObject("SELECT current_attempt FROM knowledge_index_batch WHERE build_id=? AND state<>'ACCEPTED' ORDER BY ordinal LIMIT 1",UUID.class,UUID.fromString(id));var input=new LinkedHashMap<String,Object>();input.put("attemptId",attempt);input.put("acceptPossibleDuplicateCharge",ack);return input; }
    static HttpResponse<String> resume(Client c,String id,boolean ack,UUID op) throws Exception { return c.post(API+"/index-builds/"+id+"/resume",resumeInput(id,ack),op); }
    static int vectorCount(String id) { return jdbc.queryForObject("SELECT count(*) FROM knowledge_index_vector v JOIN knowledge_index_batch b ON b.id=v.batch_id WHERE b.build_id=?",Integer.class,UUID.fromString(id)); }
    static void awaitState(String id,String state) throws Exception { await(()->state.equals(jdbc.queryForObject("SELECT state FROM knowledge_index_build WHERE id=?",String.class,UUID.fromString(id)))); }
    static void await(BooleanSupplier condition) throws Exception { for(int i=0;i<200;i++){if(condition.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError("Timed out awaiting isolated fact"); }
    static boolean healthy(URI uri) {
        try { var c=(HttpURLConnection)uri.toURL().openConnection(java.net.Proxy.NO_PROXY);c.setConnectTimeout(500);c.setReadTimeout(500);return c.getResponseCode()==200; }
        catch(Exception unavailable) { return false; }
    }
    static Map<String,Object> json(HttpResponse<String> r) { return org.springframework.boot.json.JsonParserFactory.getJsonParser().parseMap(r.body()); }
    static class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        UUID expected;String token;
        Client(Account a) throws Exception { if(a==null)return;expected=a.id();token=(String)json(get("/api/auth/csrf")).get("token");var r=http.send(HttpRequest.newBuilder(base.resolve("/api/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",token).POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(a.login(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(a.password(),StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(200);token=(String)json(get("/api/auth/csrf")).get("token"); }
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> post(String path,Map<String,?> data,UUID op) throws Exception { var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("X-Operation-Id",op.toString());if(expected!=null)b.header("X-Expected-Account-Id",expected.toString());if(token!=null)b.header("X-CSRF-TOKEN",token);return http.send(b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
