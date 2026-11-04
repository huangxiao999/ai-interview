package dev.interviewcoach.identity;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static dev.interviewcoach.identity.PlanGenerationIT.*;

/** 复用真实生产应用及隔离知识准备；仅供应商故障与JDBC提交确认是可控替身。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlanRevisionIT {
    @BeforeAll static void start() throws Exception { PlanGenerationIT.start(); }
    @AfterAll static void close() throws Exception {
        if(jdbc!=null && database!=null)java.nio.file.Files.writeString(Path.of("../.cache",database+"-revision-facts.json"),JSON.writeValueAsString(Map.of(
            "revisions",jdbc.queryForList("SELECT id,plan_id,revision_no,parent_revision_id,payload IS NULL AS purged FROM plan_revision ORDER BY created_at"),
            "receipts",jdbc.queryForList("SELECT owner_id,operation_id,plan_id,revision_id,kind,input_digest FROM plan_revision_request ORDER BY created_at"),
            "confirmations",jdbc.queryForList("SELECT * FROM plan_revision_confirmation ORDER BY confirmed_at"))));
        PlanGenerationIT.close();
    }
    @AfterEach void reset(){ COMMIT_LOSS.set("");REVISION_ROLLBACK.set(false); }
    record Fixture(Client client,JsonNode original,JsonNode view) {
        String plan(){return original.path("plan").path("planId").asString();}
        String path(){return "/api/plans/"+plan();}
    }
    static Fixture fixture() throws Exception {
        Client c=new Client(owner);var w=create(c);UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"SUCCEEDED");
        var original=operation(c,op);String plan=original.path("plan").path("planId").asString();
        return new Fixture(c,original,ok(c.get("/api/plans/"+plan+"/revisions/current"),200));
    }
    static Map<String,Object> topic(JsonNode view,int count){
        var t=JSON.convertValue(view.path("revision").path("content").path("topics").get(0),Map.class);
        Map<String,Object> copy=new LinkedHashMap<>();t.forEach((k,v)->copy.put(k.toString(),v));copy.put("questionCount",count);return copy;
    }
    static Map<String,Object> save(JsonNode v,Object count){var t=topic(v,5);t.put("questionCount",count);return Map.of("expectedRevisionId",v.path("revision").path("id").asString(),"topics",List.of(t));}
    static Map<String,Object> confirm(JsonNode v){return Map.of("expectedRevisionId",v.path("revision").path("id").asString());}
    static JsonNode current(Fixture f)throws Exception{return ok(f.client().get(f.path()+"/revisions/current"),200);}
    @Test @Order(1) void saveConfirmAndOriginalGenerationRemainIndependent() throws Exception {
        var f=fixture();int q=QUERIES.get(),g=GENERATIONS.get();UUID op=UUID.randomUUID();var body=save(f.view(),5);
        var saved=ok(f.client().post(f.path()+"/revisions",body,op),201);
        assertThat(saved.path("revision").path("sequence").asInt()).isEqualTo(2);
        assertThat(saved.path("revision").path("content").path("estimatedMinutes").isNull()).isTrue();
        assertThat(saved.path("confirmed").asBoolean()).isFalse();assertThat(saved.path("revision").path("content").path("totalQuestions").asInt()).isEqualTo(5);
        assertThat(ok(f.client().post(f.path()+"/revisions",body,op),201).path("revision")).isEqualTo(saved.path("revision"));
        UUID co=UUID.randomUUID();var confirmed=ok(f.client().post(f.path()+"/confirmations",confirm(saved),co),200);
        assertThat(confirmed.path("confirmed").asBoolean()).isTrue();
        assertThat(ok(f.client().post(f.path()+"/confirmations",confirm(saved),UUID.randomUUID()),200).path("confirmationId")).isEqualTo(confirmed.path("confirmationId"));
        assertThat(ok(f.client().get(f.path()),200).path("plan")).isEqualTo(f.original().path("plan"));
        var next=ok(f.client().post(f.path()+"/revisions",save(saved,15),UUID.randomUUID()),201);
        assertThat(next.path("confirmed").asBoolean()).isFalse();assertThat(next.path("revision").path("sequence").asInt()).isEqualTo(3);
        assertThat(ok(f.client().get("/api/plan-revision-operations/"+op),200).path("revision")).isEqualTo(saved.path("revision"));
        assertThat(ok(f.client().post(f.path()+"/confirmations",confirm(saved),co),200).path("confirmed").asBoolean()).isTrue();
        assertThat(QUERIES.get()).isEqualTo(q);assertThat(GENERATIONS.get()).isEqualTo(g);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_revision_confirmation WHERE plan_id=?",Integer.class,UUID.fromString(f.plan()))).isEqualTo(1);
    }
    @Test @Order(2) void strictIntegerAndCountBoundaries() throws Exception {
        var f=fixture();for(Object count:List.of(0,-1,4,16,1.5,5.0,2147483648L,"5"))assertThat(f.client().post(f.path()+"/revisions",save(f.view(),count),UUID.randomUUID()).statusCode()).as("count %s",count).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_revision WHERE plan_id=?",Integer.class,UUID.fromString(f.plan()))).isEqualTo(1);
        ok(f.client().post(f.path()+"/revisions",save(f.view(),15),UUID.randomUUID()),201);
    }
    @Test @Order(3) void invalidOrderDifficultyAndQuoteAreRejected() throws Exception {
        var f=fixture();for(var invalid:List.of(Map.entry("order",(Object)2),Map.entry("order",(Object)1.5),Map.entry("difficulty",(Object)"EXPERT"),Map.entry("resumeQuote",(Object)"forged experience"))){
            var t=topic(f.view(),5);t.put(invalid.getKey(),invalid.getValue());
            assertThat(f.client().post(f.path()+"/revisions",Map.of("expectedRevisionId",f.view().path("revision").path("id").asString(),"topics",List.of(t)),UUID.randomUUID()).statusCode()).isEqualTo(400);
        }
    }
    @Test @Order(4) void sameOperationWithChangedInputOrKindConflicts() throws Exception {
        var f=fixture();UUID op=UUID.randomUUID();ok(f.client().post(f.path()+"/revisions",save(f.view(),5),op),201);
        assertThat(f.client().post(f.path()+"/revisions",save(f.view(),6),op).statusCode()).isEqualTo(409);
        assertThat(f.client().post(f.path()+"/confirmations",confirm(f.view()),op).statusCode()).isEqualTo(409);
    }
    @Test @Order(5) void simultaneousSavesUseCasAndPreserveLosingRequest() throws Exception {
        var f=fixture();Client b=new Client(owner);var go=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {var a=executor.submit(()->{go.await();return f.client().post(f.path()+"/revisions",save(f.view(),5),UUID.randomUUID()).statusCode();});var c=executor.submit(()->{go.await();return b.post(f.path()+"/revisions",save(f.view(),6),UUID.randomUUID()).statusCode();});go.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),c.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);}
        finally{executor.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_revision WHERE plan_id=?",Integer.class,UUID.fromString(f.plan()))).isEqualTo(2);
    }
    @Test @Order(6) void confirmSaveRaceNeverConfirmsUnseenRevision() throws Exception {
        var f=fixture();Client b=new Client(owner);var go=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {var a=executor.submit(()->{go.await();return f.client().post(f.path()+"/confirmations",confirm(f.view()),UUID.randomUUID()).statusCode();});var s=executor.submit(()->{go.await();return b.post(f.path()+"/revisions",save(f.view(),5),UUID.randomUUID()).statusCode();});go.countDown();assertThat(s.get(15,TimeUnit.SECONDS)).isEqualTo(201);assertThat(a.get(15,TimeUnit.SECONDS)).isIn(200,409);}
        finally{executor.shutdownNow();}
        assertThat(current(f).path("confirmed").asBoolean()).isFalse();
    }
    @Test @Order(7) void committedSaveAndConfirmAcknowledgmentLossRecoverByOriginalOperation() throws Exception {
        var f=fixture();for(String kind:List.of("SAVE","CONFIRM")){
            var v=current(f);UUID op=UUID.randomUUID();COMMIT_LOSS.set(kind.equals("SAVE")?"REVISION_SAVE":"REVISION_CONFIRM");
            var response=f.client().post(f.path()+(kind.equals("SAVE")?"/revisions":"/confirmations"),kind.equals("SAVE")?save(v,5):confirm(v),op);
            assertThat(response.statusCode()).isEqualTo(503);
            var restored=ok(f.client().get("/api/plan-revision-operations/"+op),200);
            assertThat(restored.path("revision").path("sequence").asInt()).isEqualTo(2);
            if(kind.equals("CONFIRM"))assertThat(restored.path("confirmed").asBoolean()).isTrue();
        }
    }
    @Test @Order(8) void rollbackDoesNotLeaveRevisionPointerReceiptOrConfirmation() throws Exception {
        var f=fixture();for(String kind:List.of("SAVE","CONFIRM")){
            UUID op=UUID.randomUUID();REVISION_ROLLBACK.set(true);
            assertThat(f.client().post(f.path()+(kind.equals("SAVE")?"/revisions":"/confirmations"),kind.equals("SAVE")?save(f.view(),5):confirm(f.view()),op).statusCode()).isEqualTo(503);
            assertThat(f.client().get("/api/plan-revision-operations/"+op).statusCode()).isEqualTo(404);
            assertThat(current(f).path("revision")).isEqualTo(f.view().path("revision"));assertThat(current(f).path("confirmed").asBoolean()).isFalse();
        }
    }
    @Test @Order(9) void ownershipExpectedAccountAndCsrfCannotBeBypassed() throws Exception {
        var f=fixture();Client otherUser=new Client(other);assertThat(otherUser.get(f.path()+"/revisions/current").statusCode()).isEqualTo(404);
        assertThat(otherUser.post(f.path()+"/revisions",save(f.view(),5),UUID.randomUUID()).statusCode()).isEqualTo(404);
        f.client().session.expected=other.id();assertThat(f.client().post(f.path()+"/revisions",save(f.view(),5),UUID.randomUUID()).statusCode()).isEqualTo(409);f.client().session.expected=owner.id();
        f.client().token=null;assertThat(f.client().post(f.path()+"/confirmations",confirm(f.view()),UUID.randomUUID()).statusCode()).isEqualTo(403);
    }
    @Test @Order(10) void materialVersionChangeRejectsSaveConfirmButPreservesHistoricalRead() throws Exception {
        var f=fixture();String root=f.view().path("resumeId").asString();
        ok(f.client().post("/api/materials/resumes/"+root+"/jd-versions",Map.of("body","[P4-SYNTHETIC] Java 后端岗位换版，需要JMM和线程池。".repeat(5)),UUID.randomUUID()),201);
        assertThat(current(f).path("canEdit").asBoolean()).isFalse();
        assertThat(f.client().post(f.path()+"/revisions",save(f.view(),5),UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(f.client().post(f.path()+"/confirmations",confirm(f.view()),UUID.randomUUID()).statusCode()).isEqualTo(409);
    }
    @Test @Order(11) void deleteAfterSaveCountsAndPurgesAllPrivateRevisions() throws Exception {
        var f=fixture();UUID op=UUID.randomUUID();var saved=ok(f.client().post(f.path()+"/revisions",save(f.view(),5),op),201);ok(f.client().post(f.path()+"/confirmations",confirm(saved),UUID.randomUUID()),200);
        String root=f.view().path("resumeId").asString();assertThat(ok(f.client().get("/api/materials/resumes/"+root+"/deletion-impact"),200).path("planRevisionCount").asInt()).isEqualTo(2);
        ok(f.client().delete("/api/materials/resumes/"+root),200);
        assertThat(f.client().get(f.path()+"/revisions/current").statusCode()).isEqualTo(404);assertThat(f.client().get("/api/plan-revision-operations/"+op).statusCode()).isEqualTo(404);
        assertThat(f.client().post(f.path()+"/revisions",save(saved,6),UUID.randomUUID()).statusCode()).isEqualTo(404);
        await(()->jdbc.queryForObject("SELECT count(*) FROM plan_revision WHERE plan_id=? AND payload IS NOT NULL",Integer.class,UUID.fromString(f.plan()))==0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_revision WHERE plan_id=?",Integer.class,UUID.fromString(f.plan()))).isEqualTo(2);
    }
    @Test @Order(12) void catalogueSupportsAddReplaceReorderAndDeterministicTotal() throws Exception {
        var keeper=new KnowledgeCollectionIT.Client(maintainer);var second=KnowledgeCollectionIT.build(keeper,"[P4-SYNTHETIC] approved locks support");
        collection=KnowledgeCollectionIT.ok(keeper.post("/collections",KnowledgeCollectionIT.publication(KnowledgeCollectionIT.currentId(),List.of(KnowledgeCollectionIT.selection(knowledge,"21","jmm","visible","yes"),KnowledgeCollectionIT.selection(second,"21","locks","held","yes"))),UUID.randomUUID()));
        var f=fixture();assertThat(f.view().path("options").size()).isEqualTo(2);
        var first=topic(f.view(),3);var extra=new LinkedHashMap<>(topic(f.view(),2));extra.put("topicId",UUID.randomUUID());extra.put("order",2);extra.put("difficulty","ADVANCED");
        JsonNode choice=f.view().path("options").get(0);if(choice.path("binding").path("scenario").asString().equals("jmm"))choice=f.view().path("options").get(1);
        extra.put("coverage",JSON.convertValue(choice.path("binding"),Map.class));
        var saved=ok(f.client().post(f.path()+"/revisions",Map.of("expectedRevisionId",f.view().path("revision").path("id").asString(),"topics",List.of(first,extra)),UUID.randomUUID()),201);
        assertThat(saved.path("revision").path("content").path("totalQuestions").asInt()).isEqualTo(5);
        var unsupported=topic(saved,5);var binding=new LinkedHashMap<String,Object>();((Map<?,?>)unsupported.get("coverage")).forEach((k,v)->binding.put(k.toString(),v));binding.put("scenario","arbitrary free topic");unsupported.put("coverage",binding);
        assertThat(f.client().post(f.path()+"/revisions",Map.of("expectedRevisionId",saved.path("revision").path("id").asString(),"topics",List.of(unsupported)),UUID.randomUUID()).statusCode()).isEqualTo(409);
        // 使用服务端返回的主题身份重排；顺序按输入显式固定，不靠模型或UI推断。
        var a=JSON.convertValue(saved.path("revision").path("content").path("topics").get(1),Map.class);var b=topic(saved,3);a.put("order",1);b.put("order",2);
        var reordered=ok(f.client().post(f.path()+"/revisions",Map.of("expectedRevisionId",saved.path("revision").path("id").asString(),"topics",List.of(a,b)),UUID.randomUUID()),201);
        assertThat(reordered.path("revision").path("content").path("topics").get(0).path("coverage").path("scenario").asString()).isEqualTo("locks");
        ok(f.client().post(f.path()+"/revisions",save(reordered,5),UUID.randomUUID()),201);
    }
    @Test @Order(13) void concurrentDeletionFencesSaveAndConfirm() throws Exception {
        for(String kind:List.of("SAVE","CONFIRM")){
            var f=fixture();var otherClient=new Client(owner);var go=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
            try {
                var write=executor.submit(()->{go.await();return f.client().post(f.path()+(kind.equals("SAVE")?"/revisions":"/confirmations"),kind.equals("SAVE")?save(f.view(),5):confirm(f.view()),UUID.randomUUID()).statusCode();});
                var delete=executor.submit(()->{go.await();return otherClient.delete("/api/materials/resumes/"+f.view().path("resumeId").asString()).statusCode();});
                go.countDown();assertThat(delete.get(15,TimeUnit.SECONDS)).isEqualTo(200);assertThat(write.get(15,TimeUnit.SECONDS)).isIn(kind.equals("SAVE")?201:200,404);
            }finally{executor.shutdownNow();}
            assertThat(f.client().post(f.path()+"/confirmations",confirm(f.view()),UUID.randomUUID()).statusCode()).isEqualTo(404);
            await(()->jdbc.queryForObject("SELECT count(*) FROM plan_revision WHERE plan_id=? AND payload IS NOT NULL",Integer.class,UUID.fromString(f.plan()))==0);
        }
    }
    @Test @Order(14) void legacyV12BackfillPreservesOriginalAndPurgedPlans() throws Exception {
        fixture();var deleted=fixture();ok(deleted.client().delete("/api/materials/resumes/"+deleted.view().path("resumeId").asString()),200);
        await(()->jdbc.queryForObject("SELECT count(*) FROM interview_plan WHERE id=? AND payload IS NULL",Integer.class,UUID.fromString(deleted.plan()))==1);
        // 同一新建隔离PG16内另建V12 schema，导入生产路径生成的合成历史，不改现行app schema。
        String schema="plan_revision_upgrade";
        var legacy=org.flywaydb.core.Flyway.configure().dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).schemas(schema).defaultSchema(schema).target("12").load();
        legacy.migrate();
        for(String table:List.of("identity_account","material_resume","material_resume_version","material_jd_version","plan_workspace"))jdbc.execute("INSERT INTO "+schema+"."+table+" SELECT * FROM app."+table);
        jdbc.execute("INSERT INTO "+schema+".interview_plan(id,revision_id,workspace_id,payload,eligibility,created_at) SELECT id,revision_id,workspace_id,payload,eligibility,created_at FROM app.interview_plan");
        var before=jdbc.queryForList("SELECT id,revision_id,payload::text AS payload,created_at FROM "+schema+".interview_plan ORDER BY id");
        var checksums=jdbc.queryForList("SELECT version,checksum FROM "+schema+".flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        var upgrade=org.flywaydb.core.Flyway.configure().dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).schemas(schema).defaultSchema(schema).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);upgrade.validate();assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT id,revision_id,payload::text AS payload,created_at FROM "+schema+".interview_plan ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT version,checksum FROM "+schema+".flyway_schema_history WHERE type='SQL' AND version NOT IN ('13','14') ORDER BY installed_rank")).isEqualTo(checksums);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+schema+".interview_plan p LEFT JOIN "+schema+".plan_revision r ON r.plan_id=p.id AND r.id=p.revision_id WHERE r.id IS NULL OR p.current_revision_id IS DISTINCT FROM p.revision_id OR r.revision_no<>1 OR r.payload IS DISTINCT FROM p.payload",Integer.class)).isZero();
        int active=jdbc.queryForObject("SELECT count(*) FROM "+schema+".plan_revision WHERE payload IS NOT NULL",Integer.class),purged=jdbc.queryForObject("SELECT count(*) FROM "+schema+".plan_revision WHERE payload IS NULL",Integer.class);
        assertThat(active).isPositive();assertThat(purged).isPositive();
        java.nio.file.Files.writeString(Path.of("../.cache",database+"-revision-upgrade.json"),JSON.writeValueAsString(Map.of("schema",schema,"activePreserved",active,"purgedPreserved",purged,"oldChecksums",checksums,"migrationsApplied",2,"repeatMigrations",0)));
    }
    @Test @Order(90) void actualChromeEditsCasRecoveryAndIdentityProtection() throws Exception {
        var browserAccount=KnowledgeCollectionIT.account(AccountAuthority.TRIAL_USER);Client c=new Client(browserAccount);var material=material(c);int port=freePort();
        var directory=Path.of("../frontend").toAbsolutePath().normalize();var prefix=Path.of("../.cache",database+"-revision-browser").toAbsolutePath();
        var vite=new ProcessBuilder("node.exe","node_modules/vite/bin/vite.js","--host","127.0.0.1","--port",Integer.toString(port),"--strictPort").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+"-vite.log").toFile());safeEnvironment(vite);vite.environment().put("BACKEND_URL",base.toString());Process server=vite.start();Process browser=null;
        try {
            await(()->{try{var con=(java.net.HttpURLConnection)java.net.URI.create("http://127.0.0.1:"+port+"/login").toURL().openConnection(java.net.Proxy.NO_PROXY);con.setConnectTimeout(500);con.setReadTimeout(500);try{return con.getResponseCode()==200;}finally{con.disconnect();}}catch(Exception e){return false;}});
            var node=new ProcessBuilder("node.exe","../scripts/Verify-P4RevisionBrowser.mjs").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+".log").toFile());safeEnvironment(node);
            node.environment().putAll(Map.ofEntries(Map.entry("P4_FRONTEND_BASE","http://127.0.0.1:"+port),Map.entry("P4_BROWSER_USER",browserAccount.login()),Map.entry("P4_BROWSER_PASSWORD",browserAccount.password()),Map.entry("P4_BROWSER_OWNER",browserAccount.id().toString()),Map.entry("P4_BROWSER_ROOT",material.root().toString()),Map.entry("P4_BROWSER_VERSION",material.resume().toString()),Map.entry("P4_OTHER_USER",other.login()),Map.entry("P4_OTHER_PASSWORD",other.password()),Map.entry("P4_BROWSER_SCREENSHOT",prefix+".png"),Map.entry("P4_BROWSER_REPORT",prefix+".json")));
            browser=node.start();assertThat(browser.waitFor(180,TimeUnit.SECONDS)).as("Chrome evidence %s.log",prefix).isTrue();assertThat(browser.exitValue()).as("Chrome evidence %s.log",prefix).isZero();
        }finally{if(browser!=null && browser.isAlive())browser.destroyForcibly();server.destroyForcibly();server.waitFor(10,TimeUnit.SECONDS);}
    }
    @Test @Order(99) void emergencyStopBlocksNewConfirmationAndSaveButKeepsOriginalReceipt() throws Exception {
        var f=fixture();UUID op=UUID.randomUUID();var saved=ok(f.client().post(f.path()+"/revisions",save(f.view(),5),op),201);
        var keeper=new KnowledgeCollectionIT.Client(maintainer);KnowledgeCollectionIT.ok(keeper.post(KnowledgeCollectionIT.versionPath(knowledge)+"/stop",Map.of("digest",knowledge.digest(),"reason","Synthetic emergency stop"),UUID.randomUUID()));
        assertThat(current(f).path("canConfirm").asBoolean()).isFalse();
        assertThat(f.client().post(f.path()+"/confirmations",confirm(saved),UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(f.client().post(f.path()+"/revisions",save(saved,6),UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(ok(f.client().get("/api/plan-revision-operations/"+op),200).path("revision")).isEqualTo(saved.path("revision"));
    }
}
