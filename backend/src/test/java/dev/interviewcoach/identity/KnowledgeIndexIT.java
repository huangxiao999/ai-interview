package dev.interviewcoach.identity;

import com.sun.net.httpserver.HttpServer;
import dev.interviewcoach.InterviewCoachApplication;
import dev.interviewcoach.knowledge.AliyunEmbeddingGateway;
import dev.interviewcoach.knowledge.EmbeddingGateway;
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
    static volatile CountDownLatch slowRelease=new CountDownLatch(0);
    static ConfigurableApplicationContext context;
    static HttpServer supplier;
    static ExecutorService supplierThreads;
    static JdbcTemplate jdbc;
    static URI base;
    static String database;
    static String mockUrl;
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
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);assertThat(current.migrate().migrationsExecuted).isZero();current.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND version<>'8' ORDER BY installed_rank")).isEqualTo(history);
        supplier=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        supplierThreads=Executors.newFixedThreadPool(4);supplier.setExecutor(supplierThreads);
        supplier.createContext("/embedding",exchange->{
            try {
                var body=JSON.readTree(exchange.getRequestBody().readAllBytes());
                assertThat(body.path("model").asText()).isEqualTo("text-embedding-v4");
                assertThat(body.path("parameters").path("dimension").asInt()).isEqualTo(1024);
                assertThat(body.path("parameters").path("text_type").asText()).isEqualTo("document");
                var texts=body.path("input").path("texts");
                String marker=texts.get(0).asText().split("\\s",2)[0];
                int call=CALLS.merge(marker,1,Integer::sum);
                if(marker.equals("[DROP]") || marker.equals("[BROWSER]") && call==1) { exchange.close();return; }
                if(marker.startsWith("[SLOW")) slowRelease.await(15,TimeUnit.SECONDS);
                var vectors=new ArrayList<Map<String,Object>>();
                for(int i=0;i<texts.size();i++) {
                    var vector=new ArrayList<Double>(Collections.nCopies(marker.equals("[BAD_DIM]")?2:1024,0d));
                    if(!marker.equals("[ZERO]")) vector.set(0,marker.equals("[NONFINITE]")?Double.POSITIVE_INFINITY:1d);
                    vectors.add(Map.of("index",marker.equals("[BAD_MAP]")?i+1:i,"embedding",vector));
                }
                if(marker.equals("[PARTIAL]")) vectors.removeLast();
                byte[] response=JSON.writeValueAsBytes(Map.of("output",Map.of("embeddings",vectors),"request_id","synthetic-"+UUID.randomUUID(),"usage",Map.of("total_tokens",texts.size()*100)));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
                exchange.getResponseBody().write(response);
            } catch(Exception failure) { /* 仅外部故障端点；客户端观察断开并记录未知。 */ }
            finally { exchange.close(); }
        });
        supplier.start();mockUrl="http://127.0.0.1:"+supplier.getAddress().getPort()+"/embedding";
        maintainer=account(AccountAuthority.KNOWLEDGE_MAINTAINER);other=account(AccountAuthority.KNOWLEDGE_MAINTAINER);user=account(AccountAuthority.TRIAL_USER);
        startApplication();
    }

    static void startApplication() {
        context=new SpringApplicationBuilder(InterviewCoachApplication.class,ExternalFixture.class).profiles("local")
                .run("--server.port=0","--index-test.endpoint="+mockUrl,"--material.private-root="+Path.of("../.cache",database,"private").toAbsolutePath());
        base=URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterAll static void close() { if(context!=null) context.close();if(supplier!=null)supplier.stop(0);if(supplierThreads!=null)supplierThreads.shutdownNow(); }

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
        try(var unconfigured=new SpringApplicationBuilder(InterviewCoachApplication.class).profiles("local").run("--server.port=0","--material.private-root="+Path.of("../.cache",database,"private"))) {
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

    static Process child() throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}base=URI.create("http://127.0.0.1:"+port);
        var pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-cp",System.getProperty("java.class.path"),WorkerProcess.class.getName(),Integer.toString(port),mockUrl,database);
        pb.redirectErrorStream(true).redirectOutput(Path.of("../.cache",database+"-child-"+UUID.randomUUID()+".log").toFile());var process=pb.start();
        try { await(()->healthy(base.resolve("/api/auth/csrf")));return process; }
        catch(Exception|Error ex){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);throw ex;}
    }
    public static class WorkerProcess {
        public static void main(String[] args) { new SpringApplicationBuilder(InterviewCoachApplication.class,ExternalFixture.class).profiles("local").run("--server.port="+args[0],"--index-test.endpoint="+args[1],"--material.private-root="+Path.of("../.cache",args[2],"private")); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class ExternalFixture {
        @Bean @Primary EmbeddingGateway testExternalGateway(Environment env,ObjectMapper mapper) { return new AliyunEmbeddingGateway(URI.create(env.getRequiredProperty("index-test.endpoint")),"synthetic","fixture","fixture",mapper); }
        @Bean @Primary DataSource faultBoundaryDataSource() {
            var real=new DriverManagerDataSource(System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
            return new DelegatingDataSource(real) {
                @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
                @Override public Connection getConnection(String u,String p) throws SQLException { return wrap(super.getConnection(u,p)); }
            };
        }
        static Connection wrap(Connection real) {
            var dispatch=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql && sql.contains("INSERT INTO knowledge_index_attempt"))dispatch.set(true);
                    Object result=method.invoke(real,args);
                    if(method.getName().equals("commit") && dispatch.get() && COMMIT_FAULT.compareAndSet(true,false))throw new SQLException("Synthetic lost COMMIT acknowledgment after real PG commit","08006");
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
