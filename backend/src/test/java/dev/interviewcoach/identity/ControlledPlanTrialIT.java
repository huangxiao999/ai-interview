package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.JsonNode;
import static dev.interviewcoach.identity.PlanGenerationIT.*;
import static org.assertj.core.api.Assertions.*;

/** 每个场景由脚本创建独立PG16，绝不删窗口或重置额度；只替代供应商HTTP/故障。 */
class ControlledPlanTrialIT {
    static JsonNode draft;
    static UUID operation,workspace;
    static Path manifest;
    static final AtomicReference<String> TRIAL_MODE=new AtomicReference<>("NORMAL");
    @BeforeAll static void setup() throws Exception {
        PlanGenerationIT.start();
        var client=new Client(owner);draft=create(client);workspace=id(draft,"workspaceId");operation=UUID.randomUUID();
        context.close();
        manifest=Path.of("../.cache",database+"-trial-manifest.json").toAbsolutePath();
        var row=jdbc.queryForMap("SELECT initial_digest,input::text FROM plan_workspace WHERE id=?",workspace);
        var fixed=JSON.readTree(row.get("input").toString());
        var facts=Map.ofEntries(Map.entry("authorizationId","p4-20261104-single-window-v1"),Map.entry("ownerId",owner.id()),Map.entry("credentialVersion",1),Map.entry("workspaceId",workspace),
                Map.entry("inputDigest",row.get("initial_digest")),Map.entry("caseId","REF-P01/v0.1"),
                Map.entry("resumeDigest",hash(jdbc.queryForObject("SELECT body FROM material_resume_version WHERE id=?",String.class,id(fixed,"resumeVersionId")))),
                Map.entry("jdDigest",hash(jdbc.queryForObject("SELECT body FROM material_jd_version WHERE id=?",String.class,id(fixed,"jdVersionId")))),
                Map.entry("startsAt",OffsetDateTime.now().minusMinutes(1)),Map.entry("endsAt",OffsetDateTime.now().plusMinutes(30)));
        Files.writeString(manifest,JSON.writeValueAsString(facts));
        supplier.createContext("/api/v1/services/embeddings/text-embedding/text-embedding",exchange->{
            int count=QUERIES.incrementAndGet();byte[] body=exchange.getRequestBody().readAllBytes();var input=JSON.readTree(body);
            HTTP_EVENTS.add(Map.of("kind","NATIVE_QUERY","sequence",count,"wireDigest",hash(new String(body,java.nio.charset.StandardCharsets.UTF_8)),"at",java.time.Instant.now().toString()));
            assertThat(input.path("parameters").path("text_type").asString()).isEqualTo("query");assertThat(input.path("model").asString()).isEqualTo("qwen3.7-text-embedding-flash");
            if(TRIAL_MODE.get().equals("DISCONNECT")){exchange.close();return;}
            if(TRIAL_MODE.get().equals("BLOCK_QUERY")){entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
            if(TRIAL_MODE.get().equals("DISCONNECT_AFTER_STOP")){exchange.close();return;}
            var vector=TRIAL_MODE.get().equals("INVALID_QUERY")?List.of(1.0):KnowledgeCollectionIT.vector(0);
            byte[] result=JSON.writeValueAsBytes(Map.of("request_id","query-trial","embeddings",List.of(Map.of("index",0,"embedding",vector)),"usage",Map.of("prompt_tokens",32,"total_tokens",32)));
            exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
        });
        supplier.createContext("/compatible-mode/v1/chat/completions",exchange->{
            int count=GENERATIONS.incrementAndGet();byte[] body=exchange.getRequestBody().readAllBytes();var raw=JSON.readTree(body);
            HTTP_EVENTS.add(Map.of("kind","OPENAI_GENERATE","sequence",count,"wireDigest",hash(new String(body,java.nio.charset.StandardCharsets.UTF_8)),"at",java.time.Instant.now().toString()));
            assertThat(raw.path("enable_thinking").asBoolean()).isFalse();assertThat(raw.path("max_tokens").asInt()).isEqualTo(4096);assertThat(raw.has("tools")).isFalse();assertThat(raw.has("enable_search")).isFalse();
            if(TRIAL_MODE.get().equals("BLOCK_GENERATE")){entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
            var request=JSON.readTree(raw.path("messages").get(1).path("content").asString());
            var piece=request.path("evidence").path("pieces").get(0);
            var content=Map.of("materialDecision","CLEAR","findings",List.of(),"plan",Map.of("totalQuestions",8,"topics",List.of(Map.of("name","Java","questionCount",8,"difficulty","MEDIUM","reason","固定合成资料及知识", "resumeQuote","线程池项目","jdQuote","Java 后端","evidenceRefs",List.of(piece.path("chunkId").asString()))),"estimatedMinutes",30));
            byte[] result=JSON.writeValueAsBytes(Map.of("id","generation-trial","model","qwen3.8-flash","usage",Map.of("prompt_tokens",100,"completion_tokens",20,"total_tokens",120),"choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",JSON.writeValueAsString(content))))));
            exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
        });
        startTrial();
    }
    static void startTrial(){
        context=new SpringApplicationBuilder(InterviewCoachApplication.class,FixtureConfig.class).profiles("local","plan-verification","plan-trial-verification")
                .run("--server.port=0","--server.address=127.0.0.1","--spring.config.import=","--BAILIAN_EMBEDDING_ENABLED=false","--BAILIAN_PLAN_ENABLED=true","--interview.plan.proof-enabled=false",
                        "--P4_CONTROLLED_TRIAL_MANIFEST="+manifest,"--BAILIAN_EMBEDDING_WORKSPACE=isolated-fixture","--DASHSCOPE_API_KEY=isolated-plan-key",
                        "--BAILIAN_EMBEDDING_ENDPOINT=http://127.0.0.1:"+supplier.getAddress().getPort()+"/api/v1/services/embeddings/text-embedding/text-embedding",
                        "--BAILIAN_PLAN_ENDPOINT=http://127.0.0.1:"+supplier.getAddress().getPort()+"/compatible-mode/v1/chat/completions",
                        "--material.private-root="+Path.of("../.cache",database,"private").toAbsolutePath());
        base=java.net.URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port"));KnowledgeCollectionIT.context=context;KnowledgeCollectionIT.base=base;
    }
    @AfterAll static void teardown() throws Exception {
        Files.writeString(Path.of("../.cache",database+"-trial-facts.json"),JSON.writeValueAsString(Map.of("window",jdbc.queryForList("SELECT * FROM plan_controlled_window"),"requests",jdbc.queryForList("SELECT * FROM plan_controlled_request"),"mode",TRIAL_MODE.get())));
        PlanGenerationIT.close();
    }
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static JsonNode window(Client client)throws Exception{return ok(client.get("/api/plan-controlled-window"),200);}
    static void waiting(Client client)throws Exception{awaitState(client,operation,"WAITING_MANUAL");}
    static JsonNode preview(Client client,String kind)throws Exception{return ok(client.get("/api/plan-controlled-window/request?kind="+kind),200);}
    static void approve(Client client,String kind)throws Exception{
        var request=preview(client,kind);
        ok(client.post("/api/plan-controlled-window/approval",Map.of("kind",kind,"wireDigest",request.path("wireDigest").asString(),"inputTokens",1000,"tokenizerEvidence","isolated synthetic protocol fixture; not a real tokenizer or quality proof"),UUID.randomUUID()),200);
    }
    static void billQuery(Client client)throws Exception{
        ok(client.post("/api/plan-controlled-window/bill",Map.of("kind","QUERY","requestId","query-trial","billedCny",0.000004,"reference","isolated supplier bill fixture; not real billing"),UUID.randomUUID()),200);
    }
    static void queryPrepared(Client client)throws Exception{generate(client,draft,operation);waiting(client);assertThat(operation(client,operation).path("reason").asString()).isEqualTo("TRIAL_REQUEST_PREPARATION");}
    static void generationPrepared(Client client)throws Exception{
        queryPrepared(client);approve(client,"QUERY");resume(client,operation,"RETRY",false);waiting(client);
        assertThat(window(client).path("state").asString()).isEqualTo("WAITING_BILL");billQuery(client);
        resume(client,operation,"RETRY",false);waiting(client);assertThat(operation(client,operation).path("reason").asString()).isEqualTo("TRIAL_REQUEST_PREPARATION");
    }
    @Test void controlledWorkflowUsesProductionRulesAndNeverReplaysHttp()throws Exception{
        var client=new Client(owner);queryPrepared(client);
        assertThat(QUERIES.get()).isZero();assertThat(GENERATIONS.get()).isZero();assertThat(operation(client,operation).path("queryUsed").asInt()).isZero();
        var request=preview(client,"QUERY");
        assertThat(new Client(other).get("/api/plan-controlled-window").statusCode()).isEqualTo(409);
        assertThat(client.post("/api/plan-controlled-window/approval",Map.of("kind","QUERY","wireDigest",request.path("wireDigest").asString(),"inputTokens",4001,"tokenizerEvidence","over limit fixture"),UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(client.post("/api/plan-controlled-window/approval",Map.of("kind","QUERY","wireDigest","0".repeat(64),"inputTokens",1000,"tokenizerEvidence","wrong request fixture"),UUID.randomUUID()).statusCode()).isEqualTo(409);
        approve(client,"QUERY");resume(client,operation,"RETRY",false);waiting(client);
        assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isZero();assertThat(window(client).path("state").asString()).isEqualTo("WAITING_BILL");
        assertThat(jdbc.queryForObject("SELECT cost_unknown FROM plan_external_attempt WHERE workspace_id=? AND kind='QUERY'",Boolean.class,workspace)).isTrue();
        Thread.sleep(400);assertThat(GENERATIONS.get()).isZero();billQuery(client);
        assertThat(jdbc.queryForObject("SELECT cost_unknown FROM plan_external_attempt WHERE workspace_id=? AND kind='QUERY'",Boolean.class,workspace)).isFalse();
        resume(client,operation,"RETRY",false);waiting(client);var generation=JSON.readTree(preview(client,"GENERATE").path("wireJson").asString());
        assertThat(generation.path("enable_thinking").asBoolean()).isFalse();assertThat(generation.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(generation.path("messages").get(0).path("content").asString()).contains("JSON","plan-json-default8-single-binding-v2");
        approve(client,"GENERATE");resume(client,operation,"RETRY",false);awaitState(client,operation,"SUCCEEDED");
        var done=operation(client,operation);assertThat(done.path("plan").path("content").path("totalQuestions").asInt()).isEqualTo(8);assertThat(done.path("eligibility").asString()).isEqualTo("CONTROLLED_TRIAL");
        assertThat(done.path("initialUsed").asInt()).isEqualTo(1);assertThat(done.path("autoUsed").asInt()).isZero();assertThat(done.path("manualUsed").asInt()).isZero();assertThat(done.path("queryUsed").asInt()).isEqualTo(1);
        assertThat(GENERATIONS.get()).isEqualTo(1);assertThat(QUERIES.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT (evidence->>'runtimeReady')::boolean FROM plan_workspace WHERE id=?",Boolean.class,workspace)).isFalse();
        assertThat(window(client).path("reservedCny").decimalValue()).isEqualByComparingTo("0.0119842");
        assertThat(ok(client.get("/api/plans/"+done.path("plan").path("planId").asString()+"/revisions/current"),200).path("canEdit").asBoolean()).isFalse();
        var unrelated=create(client);assertThat(client.post("/api/plan-workspaces/"+id(unrelated,"workspaceId")+"/generation",Map.of(),UUID.randomUUID()).statusCode()).isEqualTo(503);
        generate(client,draft,UUID.randomUUID());assertThat(GENERATIONS.get()).isEqualTo(1);
        context.close();startTrial();client=new Client(owner);assertThat(operation(client,operation).path("state").asString()).isEqualTo("SUCCEEDED");assertThat(GENERATIONS.get()).isEqualTo(1);
        ok(client.delete("/api/materials/resumes/"+done.path("resumeId").asString()),200);
        await(()->jdbc.queryForObject("SELECT input_digest IS NULL AND state='STOPPED' FROM plan_controlled_window",Boolean.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_controlled_request WHERE wire_digest IS NOT NULL OR tokenizer_evidence IS NOT NULL OR billing_reference IS NOT NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=? AND payload IS NOT NULL",Integer.class,workspace)).isZero();
        assertThat(GENERATIONS.get()).isEqualTo(1);
    }
    @Test void invalidQueryKeepsUsageAndStopsAllFurtherDispatch()throws Exception{
        var client=new Client(owner);queryPrepared(client);approve(client,"QUERY");TRIAL_MODE.set("INVALID_QUERY");resume(client,operation,"RETRY",false);waiting(client);
        assertThat(operation(client,operation).path("reason").asString()).isEqualTo("INVALID_QUERY");assertThat(window(client).path("state").asString()).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM plan_external_attempt WHERE workspace_id=?",Long.class,workspace)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT cost_unknown FROM plan_external_attempt WHERE workspace_id=?",Boolean.class,workspace)).isTrue();
        billQuery(client);assertThat(window(client).path("state").asString()).isEqualTo("STOPPED");assertThat(jdbc.queryForObject("SELECT cost_unknown FROM plan_external_attempt WHERE workspace_id=?",Boolean.class,workspace)).isFalse();
        resume(client,operation,"RETRY",false);waiting(client);assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isZero();
    }
    @Test void changedMaterialsStillAllowEmergencyStopAndKnownBillReconciliation()throws Exception{
        var client=new Client(owner);queryPrepared(client);approve(client,"QUERY");resume(client,operation,"RETRY",false);waiting(client);
        ok(client.post("/api/materials/resumes/"+draft.path("resumeId").asString()+"/jd-versions",Map.of("body","[P4-SYNTHETIC] 换版后的Java后端岗位，不得代替原输入。".repeat(4)),UUID.randomUUID()),201);
        ok(client.post("/api/plan-controlled-window/stop",Map.of(),UUID.randomUUID()),200);billQuery(client);
        assertThat(window(client).path("state").asString()).isEqualTo("STOPPED");assertThat(window(client).path("reason").asString()).isEqualTo("OPERATOR_STOPPED");
        assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isZero();
    }
    @Test void documentBindingUsesProductionProviderAndWorkspaceRules()throws Exception{
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var guard=context.getBean(AccountWriteGuard.class);var facts=context.getBean(dev.interviewcoach.knowledge.PlanEvidenceFacts.class);
        var materials=context.getBean(dev.interviewcoach.material.MaterialPlanFacts.class);
        var current=new Actor(owner.id(),java.util.Set.of(AccountAuthority.TRIAL_USER),1);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{guard.requireCurrent(current);materials.lockRoot(owner.id(),id(draft,"resumeId"),false);
            facts.requireTrialConfiguration(UUID.fromString(collection.get("collectionId").toString()),collection.get("configDigest").toString(),"different-workspace");}))
                .isInstanceOf(dev.interviewcoach.knowledge.PlanEvidenceFacts.Rejected.class).hasMessage("QUERY_CONFIGURATION_CONFLICT");
        tx.executeWithoutResult(s->{guard.requireCurrent(current);materials.lockRoot(owner.id(),id(draft,"resumeId"),false);
            facts.requireTrialConfiguration(UUID.fromString(collection.get("collectionId").toString()),collection.get("configDigest").toString(),"isolated-fixture");});
        assertThat(QUERIES.get()).isZero();assertThat(GENERATIONS.get()).isZero();assertThat(jdbc.queryForObject("SELECT query_used+generation_used FROM plan_controlled_window",Integer.class)).isZero();
    }
    @Test void startupRejectsUnsafeConfigurationAndRealJarPreservesFrozenWindow()throws Exception{
        context.close();
        var defaults=new java.util.LinkedHashMap<String,String>();
        defaults.put("server.port","0");defaults.put("server.address","127.0.0.1");defaults.put("spring.config.import","");
        defaults.put("BAILIAN_EMBEDDING_ENABLED","false");defaults.put("BAILIAN_PLAN_ENABLED","true");defaults.put("interview.plan.proof-enabled","false");
        defaults.put("P4_CONTROLLED_TRIAL_MANIFEST",manifest.toString());defaults.put("BAILIAN_EMBEDDING_WORKSPACE","isolated-fixture");defaults.put("DASHSCOPE_API_KEY","isolated-plan-key");
        defaults.put("BAILIAN_EMBEDDING_ENDPOINT","http://127.0.0.1:"+supplier.getAddress().getPort()+"/api/v1/services/embeddings/text-embedding/text-embedding");
        defaults.put("BAILIAN_PLAN_ENDPOINT","http://127.0.0.1:"+supplier.getAddress().getPort()+"/compatible-mode/v1/chat/completions");
        defaults.put("material.private-root",Path.of("../.cache",database,"private").toAbsolutePath().toString());
        for(var override:List.of(Map.entry("interview.plan.proof-enabled","true"),Map.entry("BAILIAN_EMBEDDING_ENABLED","true"),
                Map.entry("BAILIAN_PLAN_ENDPOINT","https://example.invalid/chat/completions"),Map.entry("DASHSCOPE_API_KEY","not-the-isolated-key"),Map.entry("P4_PLAN_TEST_DATABASE","wrong-marker"))){
            var settings=new java.util.LinkedHashMap<>(defaults);settings.put(override.getKey(),override.getValue());
            assertThatThrownBy(()->new SpringApplicationBuilder(InterviewCoachApplication.class,FixtureConfig.class).profiles("local","plan-verification","plan-trial-verification")
                    .run(settings.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new))).isInstanceOf(RuntimeException.class);
            assertThat(QUERIES.get()).isZero();assertThat(GENERATIONS.get()).isZero();
        }
        // 真实profile不能借验证参数使用回环地址；只核配置，不执行外部网络。
        assertThatThrownBy(()->new SpringApplicationBuilder(InterviewCoachApplication.class,FixtureConfig.class).profiles("local")
                .run(defaults.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new))).isInstanceOf(RuntimeException.class);
        String original=Files.readString(manifest);var changed=JSON.readTree(original);((tools.jackson.databind.node.ObjectNode)changed).put("caseId","REF-P02/v0.1");Files.writeString(manifest,JSON.writeValueAsString(changed));
        try{assertThatThrownBy(ControlledPlanTrialIT::startTrial).isInstanceOf(RuntimeException.class);}finally{Files.writeString(manifest,original);}
        // 实际打包JAR在另一个JVM读取同一窗口，覆盖跨JVM的Map迭代随机化。
        int port=freePort();var args=new java.util.ArrayList<String>();args.add(javaExecutable());args.addAll(dev.interviewcoach.knowledge.JdkHttpTransportPolicy.jvmArguments());
        args.addAll(List.of("-jar","target/interview-coach-backend-0.0.1-SNAPSHOT.jar","--spring.profiles.active=local,plan-trial-verification"));
        defaults.put("server.port",String.valueOf(port));defaults.forEach((k,v)->args.add("--"+k+"="+v));
        var builder=new ProcessBuilder(args);safeEnvironment(builder);
        builder.redirectOutput(Path.of("../.cache",database+"-trial-jar.log").toFile());builder.redirectError(Path.of("../.cache",database+"-trial-jar.err").toFile());
        var process=builder.start();boolean up=false;
        try{
            var http=java.net.http.HttpClient.newHttpClient();
            for(int i=0;i<60 && process.isAlive();i++){
                try{var response=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(java.time.Duration.ofSeconds(1)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());if(response.statusCode()==200 && JSON.readTree(response.body()).path("status").asString().equals("UP")){up=true;break;}}catch(Exception notReady){ }
                Thread.sleep(500);
            }
            assertThat(up).as("actual trial JAR startup in another JVM").isTrue();assertThat(jdbc.queryForObject("SELECT query_used+generation_used FROM plan_controlled_window",Integer.class)).isZero();
        }finally{process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
        assertThat(QUERIES.get()).isZero();assertThat(GENERATIONS.get()).isZero();startTrial();
    }
    @Test void externalUnknownNeverBecomesZeroCostOrAutomaticRetry()throws Exception{
        var client=new Client(owner);queryPrepared(client);approve(client,"QUERY");TRIAL_MODE.set("DISCONNECT");resume(client,operation,"RETRY",false);waiting(client);
        assertThat(window(client).path("state").asString()).isEqualTo("STOPPED");assertThat(operation(client,operation).path("reason").asString()).isEqualTo("EXTERNAL_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT input_tokens IS NULL AND output_tokens IS NULL AND cost_unknown FROM plan_external_attempt WHERE workspace_id=?",Boolean.class,workspace)).isTrue();
        resume(client,operation,"RETRY",true);waiting(client);assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isZero();
    }
    @Test void committedDispatchIntentWithLostAcknowledgmentNeverSendsAfterRestart()throws Exception{
        var client=new Client(owner);queryPrepared(client);approve(client,"QUERY");COMMIT_LOSS.set("INTENT");resume(client,operation,"RETRY",false);awaitState(client,operation,"PAUSED_LOCAL");
        assertThat(QUERIES.get()).isZero();assertThat(jdbc.queryForObject("SELECT query_used FROM plan_controlled_window",Integer.class)).isEqualTo(1);
        context.close();startTrial();client=new Client(owner);
        assertThat(window(client).path("state").asString()).isEqualTo("STOPPED");assertThat(window(client).path("reason").asString()).isEqualTo("RESTART_EXECUTION_UNKNOWN");assertThat(QUERIES.get()).isZero();
    }
    @Test void receiptCommitLossRetainsKnownUsageAndRequiresOriginalResultInspection()throws Exception{
        var client=new Client(owner);queryPrepared(client);approve(client,"QUERY");COMMIT_LOSS.set("RECEIPT");resume(client,operation,"RETRY",false);awaitState(client,operation,"PAUSED_LOCAL");
        assertThat(window(client).path("state").asString()).isEqualTo("WAITING_BILL");assertThat(jdbc.queryForObject("SELECT input_tokens FROM plan_external_attempt WHERE workspace_id=?",Long.class,workspace)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT query_vector IS NULL FROM plan_workspace WHERE id=?",Boolean.class,workspace)).isTrue();
        billQuery(client);resume(client,operation,"RETRY",false);waiting(client);assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isZero();
    }
    @Test void emergencyStopFencesLateQueryButKeepsItsReceipt()throws Exception{
        var client=new Client(owner);queryPrepared(client);approve(client,"QUERY");entered=new CountDownLatch(1);release=new CountDownLatch(1);TRIAL_MODE.set("BLOCK_QUERY");resume(client,operation,"RETRY",false);
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();ok(client.post("/api/plan-controlled-window/stop",Map.of(),UUID.randomUUID()),200);release.countDown();waiting(client);
        assertThat(jdbc.queryForObject("SELECT query_vector IS NULL FROM plan_workspace WHERE id=?",Boolean.class,workspace)).isTrue();assertThat(jdbc.queryForObject("SELECT input_tokens FROM plan_external_attempt WHERE workspace_id=?",Long.class,workspace)).isEqualTo(32);
        assertThat(window(client).path("reason").asString()).isEqualTo("OPERATOR_STOPPED");assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isZero();
    }
    @Test void stoppedQueryFailureCannotOverwriteOriginalStopReason()throws Exception{
        var client=new Client(owner);queryPrepared(client);approve(client,"QUERY");entered=new CountDownLatch(1);release=new CountDownLatch(1);TRIAL_MODE.set("BLOCK_QUERY");resume(client,operation,"RETRY",false);
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();ok(client.post("/api/plan-controlled-window/stop",Map.of(),UUID.randomUUID()),200);
        TRIAL_MODE.set("DISCONNECT_AFTER_STOP");release.countDown();waiting(client);
        assertThat(window(client).path("reason").asString()).isEqualTo("OPERATOR_STOPPED");assertThat(operation(client,operation).path("reason").asString()).isEqualTo("EXTERNAL_UNKNOWN");
        assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isZero();
    }
    @Test void deletionDuringGenerationPurgesNewTrialMetadataAndRejectsLateCandidate()throws Exception{
        var client=new Client(owner);generationPrepared(client);approve(client,"GENERATE");entered=new CountDownLatch(1);release=new CountDownLatch(1);TRIAL_MODE.set("BLOCK_GENERATE");resume(client,operation,"RETRY",false);
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();ok(client.delete("/api/materials/resumes/"+draft.path("resumeId").asString()),200);release.countDown();
        await(()->jdbc.queryForObject("SELECT input IS NULL AND prompt IS NULL AND query_vector IS NULL FROM plan_workspace WHERE id=?",Boolean.class,workspace));
        await(()->jdbc.queryForObject("SELECT state='FENCED' FROM plan_external_attempt WHERE workspace_id=? AND kind='GENERATE'",Boolean.class,workspace));
        assertThat(jdbc.queryForObject("SELECT reason FROM plan_controlled_window",String.class)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE workspace_id=?",Integer.class,workspace)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_generation_candidate WHERE workspace_id=?",Integer.class,workspace)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_controlled_request WHERE wire_digest IS NOT NULL OR tokenizer_evidence IS NOT NULL OR billing_reference IS NOT NULL",Integer.class)).isZero();assertThat(QUERIES.get()).isEqualTo(1);assertThat(GENERATIONS.get()).isEqualTo(1);
    }
}
