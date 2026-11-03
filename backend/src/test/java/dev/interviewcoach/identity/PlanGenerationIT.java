package dev.interviewcoach.identity;

import com.sun.net.httpserver.HttpServer;
import dev.interviewcoach.InterviewCoachApplication;
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
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 唯一替身是外部query/生成HTTP及外部索引向量；权限/业务规则/仓储/事务均为生产路径。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlanGenerationIT {
    static final ObjectMapper JSON=JsonMapper.builder().build();
    static final AtomicReference<String> MODE=new AtomicReference<>("NORMAL"),COMMIT_LOSS=new AtomicReference<>("");
    static final AtomicInteger QUERIES=new AtomicInteger(),GENERATIONS=new AtomicInteger();
    static final AtomicBoolean PAUSE_READ_FAIL=new AtomicBoolean();
    static final AtomicBoolean RECEIPT_ROLLBACK=new AtomicBoolean();
    static final AtomicBoolean REVISION_ROLLBACK=new AtomicBoolean();
    static final AtomicBoolean EVIDENCE_ROLLBACK=new AtomicBoolean(),EVIDENCE_COMMIT_LOSS=new AtomicBoolean();
    static final AtomicInteger EVIDENCE_FAILURES=new AtomicInteger();
    static final AtomicInteger PAUSE_READS=new AtomicInteger();
    static final List<Map<String,Object>> HTTP_EVENTS=new CopyOnWriteArrayList<>();
    static volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
    static JdbcTemplate jdbc;
    static ConfigurableApplicationContext context;
    static URI base;
    static HttpServer supplier;
    static ExecutorService supplierThreads;
    static String database;
    static KnowledgeCollectionIT.Account owner,other,maintainer;
    static Map<String,Object> collection;
    static KnowledgeCollectionIT.Fixture knowledge;
    @BeforeAll static void start() throws Exception {
        database=System.getenv("P4_PLAN_TEST_DATABASE");
        assertThat(database).matches("p4dt01_verify_[a-f0-9]{16}");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(database);
        assertThat(jdbc.queryForObject("SHOW server_version_num",Integer.class)).isBetween(160000,169999);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'",Integer.class)).isZero();
        Flyway.configure().dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).schemas("app").defaultSchema("app").target("9").load().migrate();
        var old=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        var flyway=Flyway.configure().dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).schemas("app").defaultSchema("app").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND version NOT IN ('10','11','12','13') ORDER BY installed_rank")).isEqualTo(old);
        supplier=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);supplierThreads=Executors.newFixedThreadPool(8);supplier.setExecutor(supplierThreads);
        supplier.createContext("/query",exchange->{
            int sent=QUERIES.incrementAndGet();HTTP_EVENTS.add(Map.of("kind","QUERY","sequence",sent,"mode",MODE.get(),"at",java.time.Instant.now().toString()));var raw=JSON.readTree(exchange.getRequestBody().readAllBytes());assertThat(raw.path("dimension").asInt()).isEqualTo(1024);
            Object vector=MODE.get().equals("BAD_VECTOR")?List.of(1d):KnowledgeCollectionIT.vector(0);
            byte[] bytes=JSON.writeValueAsBytes(Map.of("content",Map.of("vector",vector),"requestId","query-"+UUID.randomUUID(),"inputTokens",10));
            exchange.sendResponseHeaders(MODE.get().equals("QUERY_FAIL")?500:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        supplier.createContext("/generate",exchange->{
            int sent=GENERATIONS.incrementAndGet();var raw=JSON.readTree(exchange.getRequestBody().readAllBytes());String mode=MODE.get();HTTP_EVENTS.add(Map.of("kind","GENERATE","sequence",sent,"mode",mode,"at",java.time.Instant.now().toString()));
            if(mode.equals("BLOCK")){entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
            if(mode.equals("UNKNOWN")){exchange.close();return;}
            var piece=raw.path("evidence").path("pieces").get(0);
            var topic=Map.of("name","Java","questionCount",mode.equals("INVALID")?7:8,"difficulty","MEDIUM","reason",mode.equals("BROWSER")?"按线程池项目安排 <img src=x onerror=\"window.planInjected=true\">":"按线程池项目和Java后端岗位安排",
                    "resumeQuote","线程池项目","jdQuote","Java 后端","evidenceRefs",List.of(piece.path("chunkId").asString()));
            Object findings=mode.equals("REVIEW")?List.of(Map.of("kind","LOW_MATCH","source","JD","quote","Java 后端")):List.of();
            var content=Map.of("materialDecision",mode.equals("REVIEW")?"REVIEW_REQUIRED":"CLEAR","findings",findings,
                    "plan",Map.of("totalQuestions",mode.equals("INVALID")?7:8,"topics",List.of(topic),"estimatedMinutes",30));
            byte[] bytes=JSON.writeValueAsBytes(Map.of("content",content,"requestId","generation-"+UUID.randomUUID(),"inputTokens",100,"outputTokens",20));
            exchange.sendResponseHeaders(mode.equals("FAIL")?500:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });supplier.start();
        KnowledgeCollectionIT.jdbc=jdbc;
        owner=KnowledgeCollectionIT.account(AccountAuthority.TRIAL_USER);other=KnowledgeCollectionIT.account(AccountAuthority.TRIAL_USER);maintainer=KnowledgeCollectionIT.account(AccountAuthority.KNOWLEDGE_MAINTAINER);
        KnowledgeCollectionIT.maintainer=maintainer;KnowledgeCollectionIT.user=owner;
        startContext(true);
        var keeper=new KnowledgeCollectionIT.Client(maintainer);
        knowledge=KnowledgeCollectionIT.build(keeper,"[P4-SYNTHETIC] Java21 happens-before engineering source only");
        collection=KnowledgeCollectionIT.ok(keeper.post("/collections",KnowledgeCollectionIT.publication(null,List.of(KnowledgeCollectionIT.selection(knowledge,"21","jmm","visible","yes"))),UUID.randomUUID()));
    }
    static void startContext(boolean enabled){
        context=new SpringApplicationBuilder(InterviewCoachApplication.class,FixtureConfig.class).profiles("local","plan-verification").run("--server.port=0","--server.address=127.0.0.1","--spring.config.import=","--BAILIAN_EMBEDDING_ENABLED=false",
                "--interview.plan.proof-enabled="+enabled,"--interview.plan.proof-endpoint=http://127.0.0.1:"+supplier.getAddress().getPort(),
                "--material.private-root="+Path.of("../.cache",database,"private").toAbsolutePath());
        base=URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port"));
        KnowledgeCollectionIT.context=context;KnowledgeCollectionIT.base=base;
    }
    @AfterAll static void close() throws Exception {release.countDown();if(context!=null)context.close();if(supplier!=null)supplier.stop(0);if(supplierThreads!=null)supplierThreads.shutdownNow();
        if(database!=null)java.nio.file.Files.writeString(Path.of("../.cache",database+"-facts.json"),JSON.writeValueAsString(Map.of("httpEvents",HTTP_EVENTS,"queryRequests",QUERIES.get(),"generationRequests",GENERATIONS.get(),"workspaces",jdbc.queryForList("SELECT id,operation_id,state,reason,generation,initial_used,auto_used,manual_used,query_used,resume_seq,input IS NULL AS input_purged,query_vector IS NULL AS vector_purged,evidence IS NULL AS evidence_purged,prompt IS NULL AS prompt_purged FROM plan_workspace ORDER BY created_at"),"attempts",jdbc.queryForList("SELECT id,workspace_id,kind,ordinal,category,state,request_id,input_tokens,output_tokens,cost_unknown FROM plan_external_attempt ORDER BY created_at"),"cleanup",jdbc.queryForList("SELECT target_id,state FROM material_cleanup_item WHERE target_type='PLAN_PRIVATE_GRAPH' ORDER BY target_id"))));
    }
    @AfterEach void reset(){MODE.set("NORMAL");COMMIT_LOSS.set("");PAUSE_READ_FAIL.set(false);RECEIPT_ROLLBACK.set(false);EVIDENCE_ROLLBACK.set(false);EVIDENCE_COMMIT_LOSS.set(false);release.countDown();}

    @Test @Order(1) void defaultPlanUsesProductionOwnersAndReplaysOriginalResult() throws Exception {
        Client c=new Client(owner);var m=material(c);UUID creation=UUID.randomUUID();var input=input(m);
        var draft=ok(c.post("/api/plan-workspaces",input,creation),201);assertThat(ok(c.post("/api/plan-workspaces",input,creation),201).path("workspaceId").asString()).isEqualTo(draft.path("workspaceId").asString());
        UUID operation=UUID.randomUUID();var w=generate(c,draft,operation);awaitState(c,operation,"SUCCEEDED");var done=operation(c,operation);
        assertThat(done.path("plan").path("content").path("totalQuestions").asInt()).isEqualTo(8);assertThat(done.path("eligibility").asString()).isEqualTo("ENGINEERING_ONLY");
        assertThat(done.path("initialUsed").asInt()).isEqualTo(1);assertThat(done.path("queryUsed").asInt()).isEqualTo(1);
        int calls=GENERATIONS.get();var replay=generate(c,draft,UUID.randomUUID());assertThat(replay.path("operationId").asString()).isEqualTo(operation.toString());assertThat(GENERATIONS.get()).isEqualTo(calls);
        var second=create(c);UUID secondOperation=UUID.randomUUID();generate(c,second,secondOperation);awaitState(c,secondOperation,"SUCCEEDED");
        assertThat(c.post("/api/plan-workspaces/"+id(w,"workspaceId")+"/generation",Map.of(),secondOperation).statusCode()).isEqualTo(409);
        var changed=new HashMap<>(input);changed.put("scenario","different");assertThat(c.post("/api/plan-workspaces",changed,creation).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,id(w,"workspaceId"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='app' AND table_name LIKE '%entitlement%'",Integer.class)).isZero();
    }
    @Test @Order(2) void permissionsAndCsrfCannotBeBypassed() throws Exception {
        Client a=new Client(owner),b=new Client(other),maintenance=new Client(maintainer);var m=material(a);var w=ok(a.post("/api/plan-workspaces",input(m),UUID.randomUUID()),201);
        assertThat(b.get("/api/plan-workspaces/"+id(w,"workspaceId")).statusCode()).isEqualTo(404);
        assertThat(maintenance.post("/api/plan-workspaces",input(m),UUID.randomUUID()).statusCode()).isEqualTo(403);
        String csrf=a.token;a.token=null;assertThat(a.post("/api/plan-workspaces",input(m),UUID.randomUUID()).statusCode()).isEqualTo(403);a.token=csrf;
        assertThat(new Client(null).get("/api/plan-workspaces/"+id(w,"workspaceId")).statusCode()).isEqualTo(401);
    }
    @Test @Order(3) void fourAttemptsRemainOneInitialOneAutoAndTwoManual() throws Exception {
        MODE.set("FAIL");Client c=new Client(owner);var w=create(c);UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"WAITING_MANUAL");
        assertThat(operation(c,op).path("initialUsed").asInt()).isEqualTo(1);assertThat(operation(c,op).path("autoUsed").asInt()).isEqualTo(1);
        resume(c,op,"RETRY",false);awaitState(c,op,"WAITING_MANUAL");assertThat(operation(c,op).path("manualUsed").asInt()).isEqualTo(1);
        resume(c,op,"RETRY",false);awaitState(c,op,"FAILED_FINAL");var done=operation(c,op);assertThat(done.path("manualUsed").asInt()).isEqualTo(2);
        assertThat(done.path("attempts").size()).isEqualTo(5); // 1 query + 4 generation, never conflated.
        assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","RETRY","acceptUnknownCost",true),UUID.randomUUID()).statusCode()).isEqualTo(409);
    }
    @Test @Order(4) void unknownExternalExecutionRequiresExplicitCostAndNeverUsesHiddenRetry() throws Exception {
        MODE.set("UNKNOWN");Client c=new Client(owner);var w=create(c);int before=GENERATIONS.get();UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"WAITING_MANUAL");
        assertThat(operation(c,op).path("reason").asString()).isEqualTo("EXTERNAL_UNKNOWN");Thread.sleep(300);assertThat(GENERATIONS.get()-before).isEqualTo(1);
        assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","RETRY","acceptUnknownCost",false),UUID.randomUUID()).statusCode()).isEqualTo(409);
        MODE.set("NORMAL");resume(c,op,"RETRY",true);awaitState(c,op,"SUCCEEDED");assertThat(operation(c,op).path("autoUsed").asInt()).isZero();assertThat(operation(c,op).path("manualUsed").asInt()).isEqualTo(1);
    }
    @Test @Order(5) void malformedQueryKeepsVerifiedReceiptWithoutGeneration() throws Exception {
        MODE.set("BAD_VECTOR");Client c=new Client(owner);var w=create(c);int before=GENERATIONS.get();UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"WAITING_MANUAL");
        var receipt=jdbc.queryForMap("SELECT request_id,input_tokens,cost_unknown FROM plan_external_attempt WHERE workspace_id=?",id(w,"workspaceId"));
        assertThat(receipt.get("request_id")).isNotNull();assertThat(((Number)receipt.get("input_tokens")).longValue()).isEqualTo(10);assertThat(receipt.get("cost_unknown")).isEqualTo(true);
        assertThat(operation(c,op).path("initialUsed").asInt()).isZero();assertThat(GENERATIONS.get()).isEqualTo(before);
    }
    @Test @Order(6) void candidateCommitAcknowledgmentLossOnlyRecoversSameCandidate() throws Exception {
        Client c=new Client(owner);var w=create(c);COMMIT_LOSS.set("CANDIDATE");UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");
        int calls=GENERATIONS.get();assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=?",Integer.class,id(w,"workspaceId"))).isEqualTo(1);
        resume(c,op,"ACCEPT",false);awaitState(c,op,"SUCCEEDED");assertThat(GENERATIONS.get()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,id(w,"workspaceId"))).isEqualTo(1);
    }
    @Test @Order(7) void planCommitAcknowledgmentLossReturnsOriginalSuccess() throws Exception {
        Client c=new Client(owner);var w=create(c);COMMIT_LOSS.set("PLAN");UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"SUCCEEDED");Thread.sleep(300);
        assertThat(operation(c,op).path("state").asString()).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,id(w,"workspaceId"))).isEqualTo(1);
    }
    @Test @Order(8) void dispatchCommitLossDoesNotSendFromUnconfirmedMemory() throws Exception {
        Client c=new Client(owner);var w=create(c);int before=QUERIES.get();COMMIT_LOSS.set("INTENT");UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");
        assertThat(QUERIES.get()).isEqualTo(before);assertThat(operation(c,op).path("reason").asString()).isEqualTo("DISPATCH_NOT_SENT");
        resume(c,op,"RETRY",false);awaitState(c,op,"SUCCEEDED");assertThat(operation(c,op).path("queryUsed").asInt()).isEqualTo(2);
    }
    @Test @Order(9) void invalidGeneratedCandidateConsumesOriginalBudget() throws Exception {
        MODE.set("INVALID");Client c=new Client(owner);var w=create(c);UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"WAITING_MANUAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=? AND state='REJECTED'",Integer.class,id(w,"workspaceId"))).isEqualTo(2);
        MODE.set("NORMAL");resume(c,op,"RETRY",false);awaitState(c,op,"SUCCEEDED");assertThat(operation(c,op).path("manualUsed").asInt()).isEqualTo(1);
    }
    @Test @Order(10) void materialFindingsRequireActualBoundUserConfirmation() throws Exception {
        MODE.set("REVIEW");Client c=new Client(owner);var w=create(c);UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"WAITING_INPUT");var review=operation(c,op).path("materialReview");
        assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","CONFIRM_INPUT","acceptUnknownCost",false,"findingDigest","forged"),UUID.randomUUID()).statusCode()).isEqualTo(409);
        ok(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","CONFIRM_INPUT","acceptUnknownCost",false,"findingDigest",review.path("digest").asString()),UUID.randomUUID()),202);
        awaitState(c,op,"SUCCEEDED");assertThat(operation(c,op).path("manualUsed").asInt()).isEqualTo(1);
    }
    @Test @Order(11) void deletionAfterDispatchRejectsLatePrivateBodyButKeepsReceipt() throws Exception {
        MODE.set("BLOCK");entered=new CountDownLatch(1);release=new CountDownLatch(1);Client c=new Client(owner);var m=material(c);var w=ok(c.post("/api/plan-workspaces",input(m),UUID.randomUUID()),201);UUID op=UUID.randomUUID();generate(c,w,op);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        ok(c.delete("/api/materials/resumes/"+m.root()),200);release.countDown();await(()->jdbc.queryForObject("SELECT request_id FROM plan_external_attempt WHERE workspace_id=? AND kind='GENERATE'",String.class,id(w,"workspaceId"))!=null);
        assertThat(c.get("/api/plan-operations/"+op+"?kind=GENERATE_PLAN").statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=?",Integer.class,id(w,"workspaceId"))).isZero();
        await(()->jdbc.queryForObject("SELECT input IS NULL AND query_vector IS NULL AND prompt IS NULL FROM plan_workspace WHERE id=?",Boolean.class,id(w,"workspaceId")));
    }
    @Test @Order(12) void deletionIncludesAcceptedPlanAndNewPendingGraph() throws Exception {
        Client c=new Client(owner);var m=material(c);var w=ok(c.post("/api/plan-workspaces",input(m),UUID.randomUUID()),201);UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"SUCCEEDED");
        var impact=ok(c.get("/api/materials/resumes/"+m.root()+"/deletion-impact"),200);assertThat(impact.path("planCount").asInt()).isEqualTo(1);assertThat(impact.path("planCandidateCount").asInt()).isEqualTo(1);
        ok(c.delete("/api/materials/resumes/"+m.root()),200);
        await(()->jdbc.queryForObject("SELECT payload IS NULL FROM interview_plan WHERE workspace_id=?",Boolean.class,id(w,"workspaceId")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_cleanup_item WHERE target_type='PLAN_PRIVATE_GRAPH' AND target_id=? AND state='CLEANED'",Integer.class,id(w,"workspaceId"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_collection",Integer.class)).isEqualTo(1);
    }
    @Test @Order(13) void normalApplicationRemainsClosedEvenWithSyntheticInput() throws Exception {
        context.close();startContext(false);Client c=new Client(owner);var w=create(c);int before=GENERATIONS.get();
        assertThat(c.post("/api/plan-workspaces/"+id(w,"workspaceId")+"/generation",Map.of(),UUID.randomUUID()).statusCode()).isEqualTo(503);assertThat(GENERATIONS.get()).isEqualTo(before);
        context.close();startContext(true);
    }

    @Test @Order(14) void pauseRegistrationFailureNeverAutoAcceptsEvenAfterRealJavaRestart() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();
        jdbc.execute("CREATE SEQUENCE p4_accept_calls");
        jdbc.execute("CREATE FUNCTION p4_deny_accept() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM nextval('p4_accept_calls'); RAISE EXCEPTION 'isolated acceptance unavailable'; END $$");
        jdbc.execute("CREATE TRIGGER p4_deny_accept BEFORE INSERT ON interview_plan FOR EACH ROW WHEN(NEW.workspace_id='"+workspace+"'::uuid) EXECUTE FUNCTION p4_deny_accept()");
        jdbc.execute("CREATE FUNCTION p4_deny_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated pause unavailable'; END $$");
        jdbc.execute("CREATE TRIGGER p4_deny_pause BEFORE UPDATE ON plan_workspace FOR EACH ROW WHEN(NEW.id='"+workspace+"'::uuid AND NEW.state='PAUSED_LOCAL') EXECUTE FUNCTION p4_deny_pause()");
        generate(c,w,op);await(()->jdbc.queryForObject("SELECT is_called FROM p4_accept_calls",Boolean.class));Thread.sleep(700);
        assertThat(jdbc.queryForObject("SELECT last_value FROM p4_accept_calls",Long.class)).isEqualTo(1);int calls=GENERATIONS.get();
        context.close();context=null;
        jdbc.execute("DROP TRIGGER p4_deny_pause ON plan_workspace");jdbc.execute("DROP FUNCTION p4_deny_pause()");
        jdbc.execute("DROP TRIGGER p4_deny_accept ON interview_plan");jdbc.execute("DROP FUNCTION p4_deny_accept()");
        int port=freePort();var log=Path.of("../.cache",database+"-restart.log").toAbsolutePath();
        var process=new ProcessBuilder(javaExecutable(),"-Djdk.httpclient.redirects.retrylimit=1","-Djdk.httpclient.disableRetryConnect=true","-Djdk.httpclient.enableAllMethodRetry=false","-jar","target/interview-coach-backend-0.0.1-SNAPSHOT.jar",
                "--spring.profiles.active=local,plan-verification","--spring.config.import=","--server.address=127.0.0.1","--server.port="+port,"--BAILIAN_EMBEDDING_ENABLED=false",
                "--interview.plan.proof-enabled=true","--interview.plan.proof-endpoint=http://127.0.0.1:"+supplier.getAddress().getPort(),"--material.private-root="+Path.of("../.cache",database,"private-child").toAbsolutePath())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        safeEnvironment(process);Process child=process.start();base=URI.create("http://127.0.0.1:"+port);KnowledgeCollectionIT.base=base;
        try{
            await(()->{try{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/actuator/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception notReady){return false;}});
            c=new Client(owner);awaitState(c,op,"PAUSED_LOCAL");Thread.sleep(400);assertThat(GENERATIONS.get()).isEqualTo(calls);
            resume(c,op,"ACCEPT",false);awaitState(c,op,"SUCCEEDED");assertThat(GENERATIONS.get()).isEqualTo(calls);
            String jarHash;try(var stream=new java.security.DigestInputStream(java.nio.file.Files.newInputStream(Path.of("target/interview-coach-backend-0.0.1-SNAPSHOT.jar")),java.security.MessageDigest.getInstance("SHA-256"))){stream.transferTo(java.io.OutputStream.nullOutputStream());jarHash=HexFormat.of().formatHex(stream.getMessageDigest().digest());}
            java.nio.file.Files.writeString(Path.of("../.cache",database+"-restart.json"),JSON.writeValueAsString(Map.of("pid",child.pid(),"jarSha256",jarHash,"workspaceId",workspace,"operationId",op,"generationCallsBefore",calls,"generationCallsAfter",GENERATIONS.get(),"result","SUCCEEDED","log",log.toString())));
        }finally{child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();startContext(true);}
    }
    @Test @Order(15) void deletionFirstPreventsNewPrivateWorkspace() throws Exception {
        Client c=new Client(owner);var m=material(c);ok(c.delete("/api/materials/resumes/"+m.root()),200);
        assertThat(c.post("/api/plan-workspaces",input(m),UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_workspace WHERE resume_id=?",Integer.class,m.root())).isZero();
    }
    @Test @Order(16) void changedMaterialFencesLateCandidateWithoutReplacingOldBinding() throws Exception {
        MODE.set("BLOCK");entered=new CountDownLatch(1);release=new CountDownLatch(1);Client c=new Client(owner);var m=material(c);var w=ok(c.post("/api/plan-workspaces",input(m),UUID.randomUUID()),201);UUID op=UUID.randomUUID();generate(c,w,op);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        ok(c.post("/api/materials/resumes/"+m.root()+"/versions",Map.of("body","[P4-SYNTHETIC] 线程池项目更新，新版本资料。".repeat(8)),UUID.randomUUID()),201);release.countDown();awaitState(c,op,"INVALIDATED");
        assertThat(operation(c,op).path("resumeVersionId").asString()).isEqualTo(m.resume().toString());assertThat(operation(c,op).path("materialsCurrent").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=?",Integer.class,id(w,"workspaceId"))).isZero();
    }
    @Test @Order(17) void knowledgeStopFencesCandidateAndKeepsVerifiedUsage() throws Exception {
        var keeper=new KnowledgeCollectionIT.Client(maintainer);var temp=KnowledgeCollectionIT.build(keeper,"[P4-SYNTHETIC] stop during plan generation");
        var isolated=KnowledgeCollectionIT.ok(keeper.post("/collections",KnowledgeCollectionIT.publication(KnowledgeCollectionIT.currentId(),List.of(KnowledgeCollectionIT.selection(temp,"21","jmm","visible","yes"))),UUID.randomUUID()));
        MODE.set("BLOCK");entered=new CountDownLatch(1);release=new CountDownLatch(1);Client c=new Client(owner);var in=new HashMap<>(input(material(c)));in.put("collectionId",isolated.get("collectionId"));in.put("configDigest",isolated.get("configDigest"));var w=ok(c.post("/api/plan-workspaces",in,UUID.randomUUID()),201);UUID op=UUID.randomUUID();generate(c,w,op);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        KnowledgeCollectionIT.ok(keeper.post(KnowledgeCollectionIT.versionPath(temp)+"/stop",Map.of("digest",temp.digest(),"reason","Synthetic emergency stop"),UUID.randomUUID()));release.countDown();awaitState(c,op,"BLOCKED_KNOWLEDGE");
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM plan_external_attempt WHERE workspace_id=? AND kind='GENERATE'",Long.class,id(w,"workspaceId"))).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=?",Integer.class,id(w,"workspaceId"))).isZero();
        collection=KnowledgeCollectionIT.ok(keeper.post("/collections",KnowledgeCollectionIT.publication(KnowledgeCollectionIT.currentId(),List.of(KnowledgeCollectionIT.selection(knowledge,"21","jmm","visible","yes"))),UUID.randomUUID()));
    }
    @Test @Order(18) void pendingNewGraphBlocksCompletionAndFailureRotatesToOtherOwner() throws Exception {
        Client a=new Client(owner),b=new Client(other);var m=material(a);var w=ok(a.post("/api/plan-workspaces",input(m),UUID.randomUUID()),201);UUID op=UUID.randomUUID();generate(a,w,op);awaitState(a,op,"SUCCEEDED");UUID workspace=id(w,"workspaceId");
        jdbc.execute("CREATE FUNCTION p4_deny_clean() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated graph purge failure'; END $$");
        jdbc.execute("CREATE TRIGGER p4_deny_clean BEFORE UPDATE ON plan_workspace FOR EACH ROW WHEN(NEW.id='"+workspace+"'::uuid AND NEW.input IS NULL) EXECUTE FUNCTION p4_deny_clean()");
        try{
            ok(a.delete("/api/materials/resumes/"+m.root()),200);var original=jdbc.queryForMap("SELECT requested_at,cleanup_deadline_at FROM material_delete_request WHERE resume_id=?",m.root());
            await(()->jdbc.queryForObject("SELECT cleanup_error_code FROM plan_workspace WHERE id=?",String.class,workspace)!=null);
            assertThat(jdbc.queryForObject("SELECT state FROM material_delete_request WHERE resume_id=?",String.class,m.root())).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_cleanup_item c JOIN material_delete_request d ON d.id=c.delete_request_id WHERE d.resume_id=? AND c.target_type='PRIVATE_FILE' AND c.state='PENDING'",Integer.class,m.root())).isZero();
            var mb=material(b);var wb=ok(b.post("/api/plan-workspaces",input(mb),UUID.randomUUID()),201);UUID opb=UUID.randomUUID();generate(b,wb,opb);awaitState(b,opb,"SUCCEEDED");ok(b.delete("/api/materials/resumes/"+mb.root()),200);
            await(()->"CLEANED".equals(jdbc.queryForObject("SELECT state FROM material_delete_request WHERE resume_id=?",String.class,mb.root())));
            assertThat(jdbc.queryForMap("SELECT requested_at,cleanup_deadline_at FROM material_delete_request WHERE resume_id=?",m.root())).isEqualTo(original);
        }finally{jdbc.execute("DROP TRIGGER p4_deny_clean ON plan_workspace");jdbc.execute("DROP FUNCTION p4_deny_clean()");}
        await(()->"CLEANED".equals(jdbc.queryForObject("SELECT state FROM material_delete_request WHERE resume_id=?",String.class,m.root())));
    }
    @Test @Order(19) void queueDeadlineBeforePermitDoesNotConsumeGenerationOrQuery() throws Exception {
        var permits=context.getBean(dev.interviewcoach.execution.ExternalAiPermits.class);for(int i=0;i<5;i++)assertThat(permits.acquire()).isTrue();
        try{Client c=new Client(owner);var w=create(c);UUID op=UUID.randomUUID();int q=QUERIES.get();generate(c,w,op);
            jdbc.update("UPDATE plan_workspace SET queued_at=clock_timestamp()-interval '21 seconds' WHERE id=?",id(w,"workspaceId"));awaitState(c,op,"WAITING_MANUAL");
            assertThat(operation(c,op).path("reason").asString()).isEqualTo("QUEUE_TIMEOUT");assertThat(operation(c,op).path("initialUsed").asInt()).isZero();assertThat(operation(c,op).path("queryUsed").asInt()).isZero();assertThat(QUERIES.get()).isEqualTo(q);
        }finally{for(int i=0;i<5;i++)permits.release();}
    }
    @Test @Order(20) void lateResponseIsDistinctFromUnknownExternalExecution() throws Exception {
        MODE.set("BLOCK");entered=new CountDownLatch(1);release=new CountDownLatch(1);Client c=new Client(owner);var w=create(c);UUID op=UUID.randomUUID();generate(c,w,op);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE plan_workspace SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",id(w,"workspaceId"));release.countDown();awaitState(c,op,"WAITING_MANUAL");
        assertThat(operation(c,op).path("reason").asString()).isEqualTo("LATE_RESPONSE");assertThat(jdbc.queryForObject("SELECT state FROM plan_external_attempt WHERE workspace_id=? AND kind='GENERATE'",String.class,id(w,"workspaceId"))).isEqualTo("FENCED");
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM plan_external_attempt WHERE workspace_id=? AND kind='GENERATE'",Long.class,id(w,"workspaceId"))).isEqualTo(100);
        assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","RETRY","acceptUnknownCost",false),UUID.randomUUID()).statusCode()).isEqualTo(409);
    }
    @Test @Order(21) void fiveActualHeldRequestsBoundAdditionalPlanAndSharedIndexPermit() throws Exception {
        MODE.set("BLOCK");entered=new CountDownLatch(5);release=new CountDownLatch(1);Client c=new Client(owner);var operations=new ArrayList<UUID>();
        for(int i=0;i<5;i++){var w=create(c);UUID op=UUID.randomUUID();generate(c,w,op);operations.add(op);}
        assertThat(entered.await(12,TimeUnit.SECONDS)).isTrue();int q=QUERIES.get(),g=GENERATIONS.get();var sixth=create(c);UUID sixthOp=UUID.randomUUID();generate(c,sixth,sixthOp);Thread.sleep(500);
        assertThat(operation(c,sixthOp).path("state").asString()).isEqualTo("QUEUED");assertThat(QUERIES.get()).isEqualTo(q);assertThat(GENERATIONS.get()).isEqualTo(g);
        assertThat(context.getBean(dev.interviewcoach.execution.ExternalAiPermits.class).acquire()).isFalse();release.countDown();
        for(var op:operations)awaitState(c,op,"SUCCEEDED");awaitState(c,sixthOp,"SUCCEEDED");
    }

    @Test @Order(22) void explicitSameConfigurationKnowledgeRebindKeepsOriginalBudgetAndQuery() throws Exception {
        var keeper=new KnowledgeCollectionIT.Client(maintainer);var temp=KnowledgeCollectionIT.build(keeper,"[P4-SYNTHETIC] rebind source");
        var first=KnowledgeCollectionIT.ok(keeper.post("/collections",KnowledgeCollectionIT.publication(KnowledgeCollectionIT.currentId(),List.of(KnowledgeCollectionIT.selection(temp,"21","jmm","visible","yes"))),UUID.randomUUID()));
        Client c=new Client(owner);var in=new HashMap<>(input(material(c)));in.put("collectionId",first.get("collectionId"));in.put("configDigest",first.get("configDigest"));var w=ok(c.post("/api/plan-workspaces",in,UUID.randomUUID()),201);UUID op=UUID.randomUUID();
        MODE.set("BLOCK");entered=new CountDownLatch(1);release=new CountDownLatch(1);generate(c,w,op);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        KnowledgeCollectionIT.ok(keeper.post(KnowledgeCollectionIT.versionPath(temp)+"/stop",Map.of("digest",temp.digest(),"reason","Synthetic rebind proof"),UUID.randomUUID()));release.countDown();awaitState(c,op,"BLOCKED_KNOWLEDGE");
        collection=KnowledgeCollectionIT.ok(keeper.post("/collections",KnowledgeCollectionIT.publication(KnowledgeCollectionIT.currentId(),List.of(KnowledgeCollectionIT.selection(knowledge,"21","jmm","visible","yes"))),UUID.randomUUID()));
        int q=QUERIES.get();var changed=new HashMap<String,Object>();changed.put("action","RETRY");changed.put("acceptUnknownCost",false);changed.put("evidenceCollectionId",collection.get("collectionId"));changed.put("evidenceConfigDigest","incorrect");
        assertThat(c.post("/api/plan-operations/"+op+"/resume",changed,UUID.randomUUID()).statusCode()).isEqualTo(409);
        MODE.set("NORMAL");changed.put("evidenceConfigDigest",collection.get("configDigest"));ok(c.post("/api/plan-operations/"+op+"/resume",changed,UUID.randomUUID()),202);awaitState(c,op,"SUCCEEDED");
        var done=operation(c,op);assertThat(done.path("initialUsed").asInt()).isEqualTo(1);assertThat(done.path("manualUsed").asInt()).isEqualTo(1);assertThat(done.path("autoUsed").asInt()).isZero();assertThat(QUERIES.get()).isEqualTo(q);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_input_revision WHERE workspace_id=? AND confirmation->'fixedInput' IS NOT NULL",Integer.class,id(w,"workspaceId"))).isEqualTo(2);
        assertThat(done.path("knowledgeBinding").path("collectionId").asString()).isEqualTo(collection.get("collectionId").toString());
    }

    @Test @Order(23) void actualChromeReceiptIdentityRecoveryAndDeletion() throws Exception {
        MODE.set("BROWSER");Client c=new Client(owner);var material=material(c);int before=GENERATIONS.get();int port=freePort();
        var directory=Path.of("../frontend").toAbsolutePath().normalize();var prefix=Path.of("../.cache",database+"-browser").toAbsolutePath();
        var vite=new ProcessBuilder("node.exe","node_modules/vite/bin/vite.js","--host","127.0.0.1","--port",Integer.toString(port),"--strictPort").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+"-vite.log").toFile());
        safeEnvironment(vite);vite.environment().put("BACKEND_URL",base.toString());Process server=vite.start();Process browser=null;
        try{
            await(()->{try{var connection=(HttpURLConnection)URI.create("http://127.0.0.1:"+port+"/login").toURL().openConnection(java.net.Proxy.NO_PROXY);connection.setConnectTimeout(500);connection.setReadTimeout(500);try{return connection.getResponseCode()==200;}finally{connection.disconnect();}}catch(Exception notReady){return false;}});
            var node=new ProcessBuilder("node.exe","../scripts/Verify-P4PlanBrowser.mjs").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+".log").toFile());safeEnvironment(node);
            node.environment().putAll(Map.ofEntries(Map.entry("P4_FRONTEND_BASE","http://127.0.0.1:"+port),Map.entry("P4_BROWSER_USER",owner.login()),Map.entry("P4_BROWSER_PASSWORD",owner.password()),Map.entry("P4_OTHER_USER",other.login()),Map.entry("P4_OTHER_PASSWORD",other.password()),Map.entry("P4_BROWSER_OWNER",owner.id().toString()),Map.entry("P4_BROWSER_ROOT",material.root().toString()),Map.entry("P4_BROWSER_VERSION",material.resume().toString()),Map.entry("P4_BROWSER_SCREENSHOT",prefix+".png"),Map.entry("P4_BROWSER_REPORT",prefix+".json")));
            browser=node.start();assertThat(browser.waitFor(150,TimeUnit.SECONDS)).as("Chrome process deadline; inspect %s.log",prefix).isTrue();assertThat(browser.exitValue()).as("Chrome evidence %s.log",prefix).isZero();
            var report=JSON.readTree(java.nio.file.Files.readString(Path.of(prefix+".json")));assertThat(report.path("createPosts").asInt()).isEqualTo(1);assertThat(report.path("generationPosts").asInt()).isEqualTo(1);assertThat(GENERATIONS.get()-before).isEqualTo(1);
            await(()->"CLEANED".equals(jdbc.queryForObject("SELECT state FROM material_delete_request WHERE resume_id=?",String.class,material.root())));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_workspace WHERE resume_id=? AND (input IS NOT NULL OR query_vector IS NOT NULL OR evidence IS NOT NULL OR prompt IS NOT NULL)",Integer.class,material.root())).isZero();
        }finally{if(browser!=null && browser.isAlive())browser.destroyForcibly();server.destroyForcibly();server.waitFor(10,TimeUnit.SECONDS);}
    }

    @Test @Order(24) void unknownExecutionRegistrationFailureStillRequiresCostConfirmation() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int calls=GENERATIONS.get();
        jdbc.execute("CREATE FUNCTION p4_deny_unknown() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated unknown registration unavailable'; END $$");
        jdbc.execute("CREATE TRIGGER p4_deny_unknown BEFORE UPDATE ON plan_workspace FOR EACH ROW WHEN(NEW.id='"+workspace+"'::uuid AND NEW.state='WAITING_MANUAL') EXECUTE FUNCTION p4_deny_unknown()");
        MODE.set("UNKNOWN");try{generate(c,w,op);await(()->GENERATIONS.get()>calls);Thread.sleep(500);assertThat(GENERATIONS.get()-calls).isEqualTo(1);}
        finally{jdbc.execute("DROP TRIGGER p4_deny_unknown ON plan_workspace");jdbc.execute("DROP FUNCTION p4_deny_unknown()");}
        awaitState(c,op,"WAITING_MANUAL");assertThat(operation(c,op).path("requiresCostConfirmation").asBoolean()).isTrue();
        assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","RETRY","acceptUnknownCost",false),UUID.randomUUID()).statusCode()).isEqualTo(409);
    }

    @Test @Order(25) void proofActivationRejectsOrdinaryProfileAndNonLoopbackEndpoint() {
        int calls=GENERATIONS.get()+QUERIES.get();context.close();context=null;
        try{
            assertThatThrownBy(()->new SpringApplicationBuilder(InterviewCoachApplication.class).profiles("local").run("--server.port=0","--spring.config.import=","--BAILIAN_EMBEDDING_ENABLED=false","--interview.plan.proof-enabled=true","--interview.plan.proof-endpoint=http://127.0.0.1:"+supplier.getAddress().getPort())).hasStackTraceContaining("Plan proof requires");
            assertThatThrownBy(()->new SpringApplicationBuilder(InterviewCoachApplication.class).profiles("local","plan-verification").run("--server.port=0","--spring.config.import=","--BAILIAN_EMBEDDING_ENABLED=false","--interview.plan.proof-enabled=true","--interview.plan.proof-endpoint=https://example.invalid")).hasStackTraceContaining("Plan proof requires");
            assertThat(GENERATIONS.get()+QUERIES.get()).isEqualTo(calls);
        }finally{startContext(true);}
    }

    @Test @Order(26) void pauseCommitLossAndReadFailureCannotOverwriteLaterManualResume() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();
        jdbc.execute("CREATE FUNCTION p4_deny_once_accept() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated accept rollback'; END $$");
        jdbc.execute("CREATE TRIGGER p4_deny_once_accept BEFORE INSERT ON interview_plan FOR EACH ROW WHEN(NEW.workspace_id='"+workspace+"'::uuid) EXECUTE FUNCTION p4_deny_once_accept()");
        COMMIT_LOSS.set("PAUSE");generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");await(()->PAUSE_READS.get()>0);int calls=GENERATIONS.get();
        jdbc.execute("DROP TRIGGER p4_deny_once_accept ON interview_plan");jdbc.execute("DROP FUNCTION p4_deny_once_accept()");
        resume(c,op,"ACCEPT",false);assertThat(operation(c,op).path("state").asString()).isEqualTo("CANDIDATE_READY");
        PAUSE_READ_FAIL.set(false);awaitState(c,op,"SUCCEEDED");assertThat(GENERATIONS.get()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT resume_seq FROM plan_workspace WHERE id=?",Long.class,workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,workspace)).isEqualTo(1);
    }

    @Test @Order(27) void incompatibleFixedConfigurationCannotSilentlyDispatchWithNewDefaults() throws Exception {
        var permits=context.getBean(dev.interviewcoach.execution.ExternalAiPermits.class);for(int i=0;i<5;i++)assertThat(permits.acquire()).isTrue();
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int q=QUERIES.get(),g=GENERATIONS.get();String fixed=jdbc.queryForObject("SELECT confirmation::text FROM plan_input_revision WHERE workspace_id=? AND revision=1",String.class,workspace);
        try{generate(c,w,op);jdbc.update("UPDATE plan_input_revision SET confirmation=jsonb_set(confirmation,'{model}','\"previous-model-configuration\"') WHERE workspace_id=? AND revision=1",workspace);}
        finally{for(int i=0;i<5;i++)permits.release();}
        awaitState(c,op,"PAUSED_LOCAL");var paused=operation(c,op);assertThat(paused.path("reason").asString()).isEqualTo("CONFIGURATION_CHANGED");assertThat(paused.path("canRetry").asBoolean()).isFalse();assertThat(QUERIES.get()).isEqualTo(q);assertThat(GENERATIONS.get()).isEqualTo(g);
        assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","RETRY","acceptUnknownCost",false),UUID.randomUUID()).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE plan_input_revision SET confirmation=?::jsonb WHERE workspace_id=? AND revision=1",fixed,workspace);resume(c,op,"RETRY",false);awaitState(c,op,"SUCCEEDED");assertThat(operation(c,op).path("initialUsed").asInt()).isEqualTo(1);
    }

    @Test @Order(28) void forciblyKilledJarWithUnregisteredPauseNeverAutomaticallyAcceptsCandidate() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();context.close();context=null;
        jdbc.execute("CREATE SEQUENCE p4_hard_accept_calls");
        jdbc.execute("CREATE FUNCTION p4_hard_deny_accept() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM nextval('p4_hard_accept_calls'); RAISE EXCEPTION 'isolated acceptance unavailable'; END $$");
        jdbc.execute("CREATE TRIGGER p4_hard_deny_accept BEFORE INSERT ON interview_plan FOR EACH ROW WHEN(NEW.workspace_id='"+workspace+"'::uuid) EXECUTE FUNCTION p4_hard_deny_accept()");
        jdbc.execute("CREATE FUNCTION p4_hard_deny_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated pause unavailable'; END $$");
        jdbc.execute("CREATE TRIGGER p4_hard_deny_pause BEFORE UPDATE ON plan_workspace FOR EACH ROW WHEN(NEW.id='"+workspace+"'::uuid AND NEW.state='PAUSED_LOCAL') EXECUTE FUNCTION p4_hard_deny_pause()");
        Process first=null,second=null;
        try{
            first=hardRestartJar("before-kill");c=new Client(owner);generate(c,w,op);await(()->jdbc.queryForObject("SELECT is_called FROM p4_hard_accept_calls",Boolean.class));Thread.sleep(500);
            assertThat(jdbc.queryForObject("SELECT last_value FROM p4_hard_accept_calls",Long.class)).isEqualTo(1);int calls=GENERATIONS.get();long killedPid=first.pid();first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT state FROM plan_workspace WHERE id=?",String.class,workspace)).isEqualTo("CANDIDATE_READY");
            jdbc.execute("DROP TRIGGER p4_hard_deny_pause ON plan_workspace");jdbc.execute("DROP FUNCTION p4_hard_deny_pause()");jdbc.execute("DROP TRIGGER p4_hard_deny_accept ON interview_plan");jdbc.execute("DROP FUNCTION p4_hard_deny_accept()");
            second=hardRestartJar("after-kill");assertThat(second.pid()).isNotEqualTo(killedPid);c=new Client(owner);awaitState(c,op,"PAUSED_LOCAL");Thread.sleep(400);assertThat(GENERATIONS.get()).isEqualTo(calls);
            resume(c,op,"ACCEPT",false);awaitState(c,op,"SUCCEEDED");assertThat(GENERATIONS.get()).isEqualTo(calls);
            java.nio.file.Files.writeString(Path.of("../.cache",database+"-hard-restart.json"),JSON.writeValueAsString(Map.of("killedPid",killedPid,"newPid",second.pid(),"workspace",workspace,"operation",op,"generationCallsBeforeKill",calls,"generationCallsAfterAcceptance",GENERATIONS.get(),"pauseWasPersistedBeforeKill",false,"result","SUCCEEDED")));
        }finally{if(first!=null && first.isAlive()){first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS);}if(second!=null && second.isAlive()){second.destroyForcibly();second.waitFor(10,TimeUnit.SECONDS);}startContext(true);}
    }
    static Process hardRestartJar(String phase) throws Exception {
        int port=freePort();var process=new ProcessBuilder(javaExecutable(),"-Djdk.httpclient.redirects.retrylimit=1","-Djdk.httpclient.disableRetryConnect=true","-Djdk.httpclient.enableAllMethodRetry=false","-jar","target/interview-coach-backend-0.0.1-SNAPSHOT.jar",
                "--spring.profiles.active=local,plan-verification","--spring.config.import=","--server.address=127.0.0.1","--server.port="+port,"--BAILIAN_EMBEDDING_ENABLED=false","--interview.plan.proof-enabled=true","--interview.plan.proof-endpoint=http://127.0.0.1:"+supplier.getAddress().getPort(),"--material.private-root="+Path.of("../.cache",database,"private-hard-"+phase).toAbsolutePath())
                .redirectErrorStream(true).redirectOutput(Path.of("../.cache",database+"-hard-"+phase+".log").toFile());safeEnvironment(process);Process child=process.start();
        base=URI.create("http://127.0.0.1:"+port);KnowledgeCollectionIT.base=base;
        try{await(()->{try{return child.isAlive() && HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/actuator/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception notReady){return false;}});return child;}
        catch(Exception | AssertionError failure){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);throw failure;}
    }

    @Test @Order(29) void queryReceiptRollbackPausesAndRecoversWithoutRestart() throws Exception {queryReceiptRecovery(false);}
    @Test @Order(30) void queryReceiptCommitLossPausesAndRecoversWithoutRestart() throws Exception {queryReceiptRecovery(true);}
    void queryReceiptRecovery(boolean committed) throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int queries=QUERIES.get(),generations=GENERATIONS.get();var application=context;
        if(committed)COMMIT_LOSS.set("RECEIPT");else RECEIPT_ROLLBACK.set(true);
        generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");Thread.sleep(350);
        assertThat(context).isSameAs(application);assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()).isEqualTo(generations);
        var attempt=jdbc.queryForMap("SELECT id,request_id,input_tokens,cost_unknown FROM plan_external_attempt WHERE workspace_id=? AND kind='QUERY'",workspace);
        if(committed){assertThat(attempt.get("request_id")).isNotNull();assertThat(((Number)attempt.get("input_tokens")).longValue()).isEqualTo(10);}
        else{assertThat(attempt.get("request_id")).isNull();assertThat(attempt.get("input_tokens")).isNull();}
        assertThat(attempt.get("cost_unknown")).isEqualTo(true);assertThat(operation(c,op).path("queryUsed").asInt()).isEqualTo(1);assertThat(operation(c,op).path("initialUsed").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT query_vector IS NULL FROM plan_workspace WHERE id=?",Boolean.class,workspace)).isTrue();
        resume(c,op,"RETRY",true);awaitState(c,op,"SUCCEEDED");assertThat(context).isSameAs(application);
        assertThat(operation(c,op).path("queryUsed").asInt()).isEqualTo(2);assertThat(operation(c,op).path("initialUsed").asInt()).isEqualTo(1);assertThat(operation(c,op).path("autoUsed").asInt()).isZero();
        assertThat(QUERIES.get()-queries).isEqualTo(2);assertThat(GENERATIONS.get()-generations).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_id FROM plan_external_attempt WHERE id=?",String.class,attempt.get("id"))).isEqualTo(attempt.get("request_id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,workspace)).isEqualTo(1);
    }
    @Test @Order(31) void queryFailureReceiptRollbackAlsoRemainsRecoverable() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID op=UUID.randomUUID();int queries=QUERIES.get(),generations=GENERATIONS.get();MODE.set("QUERY_FAIL");RECEIPT_ROLLBACK.set(true);generate(c,w,op);awaitState(c,op,"WAITING_MANUAL");
        assertThat(operation(c,op).path("requiresCostConfirmation").asBoolean()).isTrue();assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()).isEqualTo(generations);
        assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","RETRY","acceptUnknownCost",false),UUID.randomUUID()).statusCode()).isEqualTo(409);
        MODE.set("NORMAL");resume(c,op,"RETRY",true);awaitState(c,op,"SUCCEEDED");assertThat(operation(c,op).path("queryUsed").asInt()).isEqualTo(2);
    }

    @Test @Order(32) void chromeResumeUpdateAllowsExplicitNewPlanAfterReturningToMaterials() throws Exception {invalidatedBrowser("RESUME");}
    @Test @Order(33) void chromeJdUpdateAllowsExplicitNewPlanAfterReturningToMaterials() throws Exception {invalidatedBrowser("JD");}
    void invalidatedBrowser(String kind) throws Exception {
        var browserAccount=KnowledgeCollectionIT.account(AccountAuthority.TRIAL_USER);Client c=new Client(browserAccount);var material=material(c);int before=GENERATIONS.get();int port=freePort();
        MODE.set("BLOCK");entered=new CountDownLatch(1);release=new CountDownLatch(1);var directory=Path.of("../frontend").toAbsolutePath().normalize();var prefix=Path.of("../.cache",database+"-invalidated-"+kind.toLowerCase(Locale.ROOT)).toAbsolutePath();
        var vite=new ProcessBuilder("node.exe","node_modules/vite/bin/vite.js","--host","127.0.0.1","--port",Integer.toString(port),"--strictPort").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+"-vite.log").toFile());safeEnvironment(vite);vite.environment().put("BACKEND_URL",base.toString());Process server=vite.start();Process browser=null;
        try{
            await(()->KnowledgeIndexIT.healthy(URI.create("http://127.0.0.1:"+port+"/login")));
            var node=new ProcessBuilder("node.exe","../scripts/Verify-P4PlanBrowser.mjs").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+".log").toFile());safeEnvironment(node);
            node.environment().putAll(Map.ofEntries(Map.entry("P4_FRONTEND_BASE","http://127.0.0.1:"+port),Map.entry("P4_BROWSER_USER",browserAccount.login()),Map.entry("P4_BROWSER_PASSWORD",browserAccount.password()),Map.entry("P4_BROWSER_OWNER",browserAccount.id().toString()),Map.entry("P4_BROWSER_ROOT",material.root().toString()),Map.entry("P4_BROWSER_VERSION",material.resume().toString()),Map.entry("P4_BROWSER_SCENARIO","INVALIDATED"),Map.entry("P4_BROWSER_UPDATE_KIND",kind),Map.entry("P4_BROWSER_SCREENSHOT",prefix+".png"),Map.entry("P4_BROWSER_REPORT",prefix+".json")));
            browser=node.start();assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();String table=kind.equals("RESUME")?"material_resume_version":"material_jd_version";
            await(()->jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE resume_id=?",Integer.class,material.root())==2);MODE.set("NORMAL");release.countDown();
            assertThat(browser.waitFor(120,TimeUnit.SECONDS)).as("Chrome review evidence %s.log",prefix).isTrue();assertThat(browser.exitValue()).as("Chrome review evidence %s.log",prefix).isZero();
            var proof=JSON.readTree(java.nio.file.Files.readString(Path.of(prefix+".json")));UUID old=UUID.fromString(proof.path("old").path("workspace").asString()),next=UUID.fromString(proof.path("current").path("workspace").asString());
            assertThat(jdbc.queryForObject("SELECT state FROM plan_workspace WHERE id=?",String.class,old)).isEqualTo("INVALIDATED");assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,old)).isZero();
            assertThat(jdbc.queryForObject("SELECT state FROM plan_workspace WHERE id=?",String.class,next)).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_workspace WHERE resume_id=?",Integer.class,material.root())).isEqualTo(2);assertThat(GENERATIONS.get()-before).isEqualTo(2);
            String key=kind.equals("RESUME")?"resumeVersionId":"jdVersionId";UUID latest=jdbc.queryForObject("SELECT id FROM "+table+" WHERE resume_id=? AND version_no=2",UUID.class,material.root());
            assertThat(jdbc.queryForObject("SELECT input->>? FROM plan_workspace WHERE id=?",String.class,key,next)).isEqualTo(latest.toString());
            assertThat(jdbc.queryForObject("SELECT input->>? FROM plan_workspace WHERE id=?",String.class,key,old)).isEqualTo((kind.equals("RESUME")?material.resume():material.jd()).toString());
        }finally{release.countDown();if(browser!=null && browser.isAlive())browser.destroyForcibly();server.destroyForcibly();server.waitFor(10,TimeUnit.SECONDS);}
    }

    static int freePort() throws Exception {try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    static String javaExecutable(){return Path.of(System.getProperty("java.home"),"bin","java.exe").toString();}
    static void safeEnvironment(ProcessBuilder process){
        var env=process.environment();env.clear();
        for(String name:List.of("PATH","SystemRoot","ComSpec","TEMP","TMP","APPDATA","LOCALAPPDATA","USERPROFILE","DB_URL","DB_USERNAME","DB_PASSWORD","P4_PLAN_TEST_DATABASE")){
            String value=System.getenv(name);if(value!=null)env.put(name,value);
        }
    }

    record Material(UUID root,UUID resume,UUID jd){ }
    static Material material(Client c) throws Exception {
        var r=ok(c.post("/api/materials/resumes",Map.of("body","[P4-SYNTHETIC] 线程池项目：Java并发实践、状态恢复、事务与清理。".repeat(5)),UUID.randomUUID()),201);
        UUID root=id(r,"resumeId");var j=ok(c.post("/api/materials/resumes/"+root+"/jd-versions",Map.of("body","[P4-SYNTHETIC] Java 后端岗位，需要理解JMM与线程池、并发取消与恢复。".repeat(3)),UUID.randomUUID()),201);
        return new Material(root,id(r,"versionId"),id(j,"versionId"));
    }
    static Map<String,Object> input(Material m){return Map.ofEntries(Map.entry("resumeId",m.root()),Map.entry("resumeVersionId",m.resume()),Map.entry("jdVersionId",m.jd()),Map.entry("collectionId",collection.get("collectionId")),Map.entry("configDigest",collection.get("configDigest")),Map.entry("technology","Java"),Map.entry("technologyVersion","21"),Map.entry("scenario","jmm"),Map.entry("claimKey","visible"),Map.entry("materialsConfirmed",true),Map.entry("restrictedCoverageConfirmed",true),Map.entry("newPlanIntent",true));}
    static JsonNode create(Client c) throws Exception {return ok(c.post("/api/plan-workspaces",input(material(c)),UUID.randomUUID()),201);}
    static JsonNode generate(Client c,JsonNode w,UUID operation) throws Exception{return ok(c.post("/api/plan-workspaces/"+id(w,"workspaceId")+"/generation",Map.of(),operation),202);}
    static JsonNode operation(Client c,UUID operation) throws Exception{return ok(c.get("/api/plan-operations/"+operation+"?kind=GENERATE_PLAN"),200);}
    static void resume(Client c,UUID op,String action,boolean cost) throws Exception {ok(c.post("/api/plan-operations/"+op+"/resume",Map.of("action",action,"acceptUnknownCost",cost),UUID.randomUUID()),202);}
    static UUID id(JsonNode n,String key){return UUID.fromString(n.path(key).asString());}
    static JsonNode ok(HttpResponse<String> response,int expected){assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expected);return JSON.readTree(response.body());}
    static void awaitState(Client c,UUID op,String state) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);String last="";while(System.nanoTime()<end){last=operation(c,op).path("state").asString();if(last.equals(state))return;Thread.sleep(50);}throw new AssertionError("Expected "+state+", actual "+last);}
    static void await(BooleanSupplier condition) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}throw new AssertionError("Persistent condition not met");}
    @Test @Order(34) void evidenceRollbackPausesAndRecoversWithoutRestart() throws Exception {evidenceRecovery(false);}
    @Test @Order(35) void evidenceCommitLossPausesAndRecoversWithoutRestart() throws Exception {evidenceRecovery(true);}
    void evidenceRecovery(boolean committed) throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int queries=QUERIES.get(),generations=GENERATIONS.get(),failures=EVIDENCE_FAILURES.get();var application=context;
        if(committed)EVIDENCE_COMMIT_LOSS.set(true);else EVIDENCE_ROLLBACK.set(true);
        generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");
        assertThat(EVIDENCE_FAILURES.get()-failures).isEqualTo(1);assertThat(context).isSameAs(application);
        assertThat(jdbc.queryForObject("SELECT evidence IS NOT NULL FROM plan_workspace WHERE id=?",Boolean.class,workspace)).isEqualTo(committed);
        var storedVector=jdbc.queryForObject("SELECT query_vector::text FROM plan_workspace WHERE id=?",String.class,workspace);
        var originalAttempt=jdbc.queryForMap("SELECT id,request_id,input_tokens,cost_unknown FROM plan_external_attempt WHERE workspace_id=? AND kind='QUERY'",workspace);
        assertThat(storedVector).isNotNull();assertThat(originalAttempt.get("request_id")).isNotNull();assertThat(((Number)originalAttempt.get("input_tokens")).longValue()).isEqualTo(10);assertThat(originalAttempt.get("cost_unknown")).isEqualTo(true);
        assertThat(operation(c,op).path("queryUsed").asInt()).isEqualTo(1);assertThat(operation(c,op).path("initialUsed").asInt()).isZero();
        assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()).isEqualTo(generations);
        resume(c,op,"RETRY",false);awaitState(c,op,"SUCCEEDED");assertThat(context).isSameAs(application);
        var done=operation(c,op);assertThat(done.path("queryUsed").asInt()).isEqualTo(1);assertThat(done.path("initialUsed").asInt()).isEqualTo(1);assertThat(done.path("autoUsed").asInt()).isZero();assertThat(done.path("manualUsed").asInt()).isZero();
        assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()-generations).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT query_vector::text FROM plan_workspace WHERE id=?",String.class,workspace)).isEqualTo(storedVector);
        assertThat(jdbc.queryForMap("SELECT id,request_id,input_tokens,cost_unknown FROM plan_external_attempt WHERE workspace_id=? AND kind='QUERY'",workspace)).isEqualTo(originalAttempt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,workspace)).isEqualTo(1);
    }
    @Test @Order(36) void evidencePauseWriteAndReadFailuresNeverContinueAutomatically() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int queries=QUERIES.get(),generations=GENERATIONS.get(),reads=PAUSE_READS.get();
        jdbc.execute("CREATE SEQUENCE p4_evidence_pause_calls");
        jdbc.execute("CREATE FUNCTION p4_evidence_deny_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM nextval('p4_evidence_pause_calls'); RAISE EXCEPTION 'isolated evidence pause unavailable'; END $$");
        jdbc.execute("CREATE TRIGGER p4_evidence_deny_pause BEFORE UPDATE ON plan_workspace FOR EACH ROW WHEN(NEW.id='"+workspace+"'::uuid AND NEW.state='PAUSED_LOCAL') EXECUTE FUNCTION p4_evidence_deny_pause()");
        try{
            EVIDENCE_ROLLBACK.set(true);generate(c,w,op);await(()->jdbc.queryForObject("SELECT last_value FROM p4_evidence_pause_calls",Long.class)>=2);
            PAUSE_READ_FAIL.set(true);await(()->PAUSE_READS.get()>reads);
            assertThat(jdbc.queryForObject("SELECT state FROM plan_workspace WHERE id=?",String.class,workspace)).isEqualTo("PREPARING_EVIDENCE");
            assertThat(jdbc.queryForObject("SELECT query_vector IS NOT NULL AND evidence IS NULL FROM plan_workspace WHERE id=?",Boolean.class,workspace)).isTrue();
            assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()).isEqualTo(generations);
        }finally{jdbc.execute("DROP TRIGGER p4_evidence_deny_pause ON plan_workspace");jdbc.execute("DROP FUNCTION p4_evidence_deny_pause()");jdbc.execute("DROP SEQUENCE p4_evidence_pause_calls");PAUSE_READ_FAIL.set(false);}
        awaitState(c,op,"PAUSED_LOCAL");assertThat(GENERATIONS.get()).isEqualTo(generations);
        resume(c,op,"RETRY",false);awaitState(c,op,"SUCCEEDED");assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()-generations).isEqualTo(1);
    }
    @Test @Order(37) void evidencePauseCommitLossCannotOverwriteManualRecovery() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int queries=QUERIES.get(),generations=GENERATIONS.get(),reads=PAUSE_READS.get();
        EVIDENCE_COMMIT_LOSS.set(true);COMMIT_LOSS.set("PAUSE");generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");await(()->PAUSE_READS.get()>reads);
        try{resume(c,op,"RETRY",false);assertThat(operation(c,op).path("state").asString()).isEqualTo("QUEUED");assertThat(GENERATIONS.get()).isEqualTo(generations);}
        finally{PAUSE_READ_FAIL.set(false);}
        awaitState(c,op,"SUCCEEDED");assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()-generations).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resume_seq FROM plan_workspace WHERE id=?",Long.class,workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,workspace)).isEqualTo(1);
    }
    @Test @Order(38) void deletionWinsAgainstDelayedEvidencePause() throws Exception {
        Client c=new Client(owner);var m=material(c);var w=ok(c.post("/api/plan-workspaces",input(m),UUID.randomUUID()),201);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int queries=QUERIES.get(),generations=GENERATIONS.get(),reads=PAUSE_READS.get();
        EVIDENCE_COMMIT_LOSS.set(true);COMMIT_LOSS.set("PAUSE");generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");await(()->PAUSE_READS.get()>reads);
        try{ok(c.delete("/api/materials/resumes/"+m.root()),200);assertThat(c.post("/api/plan-operations/"+op+"/resume",Map.of("action","RETRY","acceptUnknownCost",false),UUID.randomUUID()).statusCode()).isEqualTo(404);}
        finally{PAUSE_READ_FAIL.set(false);}
        await(()->jdbc.queryForObject("SELECT input IS NULL AND query_vector IS NULL AND evidence IS NULL AND prompt IS NULL FROM plan_workspace WHERE id=?",Boolean.class,workspace));
        assertThat(jdbc.queryForObject("SELECT state FROM plan_workspace WHERE id=?",String.class,workspace)).isEqualTo("INVALIDATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,workspace)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=?",Integer.class,workspace)).isZero();
        assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()).isEqualTo(generations);
    }
    @Test @Order(39) void stoppedKnowledgeStillBlocksRecoveredEvidence() throws Exception {
        var keeper=new KnowledgeCollectionIT.Client(maintainer);var temp=KnowledgeCollectionIT.build(keeper,"[P4-SYNTHETIC] stopped evidence recovery source");
        var published=KnowledgeCollectionIT.ok(keeper.post("/collections",KnowledgeCollectionIT.publication(KnowledgeCollectionIT.currentId(),List.of(KnowledgeCollectionIT.selection(temp,"21","jmm","visible","yes"))),UUID.randomUUID()));
        Client c=new Client(owner);var in=new HashMap<>(input(material(c)));in.put("collectionId",published.get("collectionId"));in.put("configDigest",published.get("configDigest"));var w=ok(c.post("/api/plan-workspaces",in,UUID.randomUUID()),201);UUID workspace=id(w,"workspaceId"),op=UUID.randomUUID();int queries=QUERIES.get(),generations=GENERATIONS.get();
        EVIDENCE_ROLLBACK.set(true);generate(c,w,op);awaitState(c,op,"PAUSED_LOCAL");
        KnowledgeCollectionIT.ok(keeper.post(KnowledgeCollectionIT.versionPath(temp)+"/stop",Map.of("digest",temp.digest(),"reason","Synthetic evidence recovery stop"),UUID.randomUUID()));
        resume(c,op,"RETRY",false);awaitState(c,op,"BLOCKED_KNOWLEDGE");
        assertThat(QUERIES.get()-queries).isEqualTo(1);assertThat(GENERATIONS.get()).isEqualTo(generations);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,workspace)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=?",Integer.class,workspace)).isZero();
    }
    static final class Client {
        final KnowledgeCollectionIT.Client session;
        String token;
        Client(KnowledgeCollectionIT.Account account)throws Exception{session=new KnowledgeCollectionIT.Client(account);token=session.token;}
        HttpResponse<String> get(String path)throws Exception{return session.getAbsolute(path);}
        HttpResponse<String> post(String path,Map<String,?> body,UUID operation)throws Exception{return send(path,"POST",body,operation);}
        HttpResponse<String> delete(String path)throws Exception{return send(path,"DELETE",null,null);}
        HttpResponse<String> send(String path,String method,Map<String,?> body,UUID operation)throws Exception{
            var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
            if(session.expected!=null)b.header("X-Expected-Account-Id",session.expected.toString());if(token!=null)b.header("X-CSRF-TOKEN",token);if(operation!=null)b.header("X-Operation-Id",operation.toString());
            return session.http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class FixtureConfig {
        @Bean @Primary EmbeddingGateway indexSupplier(){return new EmbeddingGateway(){public boolean available(){return true;}public Map<String,String> configuration(){return Map.of("provider","synthetic-fixture","model","qwen3.7-text-embedding-flash","dimension","1024","textType","document","outputType","dense");}public Response embed(List<String> texts,java.time.Instant deadline){return new Response(texts.stream().map(t->KnowledgeCollectionIT.vector(0)).toList(),"synthetic-index",10L);}};}
        @Bean @Primary DataSource faultDataSource(){return new DelegatingDataSource(new DriverManagerDataSource(System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
            @Override public Connection getConnection()throws SQLException{return wrap(super.getConnection());}
        };}
        static Connection wrap(Connection real){
            var stage=new AtomicReference<>("");
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{
                    if(method.getName().equals("prepareStatement") && args[0] instanceof String sql){
                        if(sql.startsWith("SELECT * FROM plan_workspace") && Thread.currentThread().getName().contains("scheduling") && PAUSE_READ_FAIL.get()){PAUSE_READS.incrementAndGet();throw new SQLException("Synthetic pause read unavailable","08006");}
                        if(sql.startsWith("INSERT INTO plan_generation_candidate"))stage.set("CANDIDATE");
                        if(sql.startsWith("INSERT INTO interview_plan"))stage.set("PLAN");
                        if(sql.startsWith("INSERT INTO plan_revision_request"))stage.set(sql.contains("'SAVE'")?"REVISION_SAVE":"REVISION_CONFIRM");
                        if(sql.startsWith("INSERT INTO plan_external_attempt"))stage.set("INTENT");
                        if(sql.startsWith("UPDATE plan_workspace SET state='PAUSED_LOCAL'"))stage.set("PAUSE");
                        if(sql.startsWith("UPDATE plan_external_attempt SET request_id=COALESCE"))stage.set("RECEIPT");
                        if(sql.startsWith("UPDATE plan_workspace SET evidence="))stage.set("EVIDENCE");
                    }
                    if(method.getName().equals("commit") && stage.get().equals("RECEIPT") && RECEIPT_ROLLBACK.compareAndSet(true,false)){real.rollback();throw new SQLException("Synthetic receipt transaction rollback","08006");}
                    if(method.getName().equals("commit") && stage.get().equals("EVIDENCE") && EVIDENCE_ROLLBACK.compareAndSet(true,false)){real.rollback();EVIDENCE_FAILURES.incrementAndGet();throw new SQLException("Synthetic evidence transaction rollback","08006");}
                    if(method.getName().equals("commit") && stage.get().startsWith("REVISION_") && REVISION_ROLLBACK.compareAndSet(true,false)){real.rollback();throw new SQLException("Synthetic revision transaction rolled back","08006");}
                    Object result=method.invoke(real,args);
                    if(method.getName().equals("commit") && stage.get().equals("EVIDENCE") && EVIDENCE_COMMIT_LOSS.compareAndSet(true,false)){EVIDENCE_FAILURES.incrementAndGet();throw new SQLException("Synthetic evidence actual commit acknowledgment lost","08006");}
                    if(method.getName().equals("commit") && !stage.get().isEmpty() && COMMIT_LOSS.compareAndSet(stage.get(),"")){if(stage.get().equals("PAUSE"))PAUSE_READ_FAIL.set(true);throw new SQLException("Synthetic lost actual commit acknowledgment","08006");}
                    return result;
                }catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
    }
}
