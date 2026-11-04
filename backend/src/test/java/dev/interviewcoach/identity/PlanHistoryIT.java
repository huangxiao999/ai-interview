package dev.interviewcoach.identity;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static dev.interviewcoach.identity.PlanGenerationIT.*;

/** 真实生产历史查询+原生成/修订路径；没有历史测试状态机或仓储替身。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlanHistoryIT {
    @BeforeAll static void start() throws Exception { PlanGenerationIT.start(); }
    @AfterAll static void close() throws Exception { PlanGenerationIT.close(); }
    @AfterEach void reset(){ MODE.set("NORMAL"); COMMIT_LOSS.set(""); HISTORY_READ_FAIL.set(false); release.countDown(); }
    static JsonNode history(Client c,String query)throws Exception{return ok(c.get("/api/plan-workspaces"+query),200);}
    static JsonNode completed(Client c)throws Exception {var w=create(c);UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"SUCCEEDED");return operation(c,op);}
    @Test @Order(1) void ownedDiscoveryIncludesDraftFailureAndOriginalAcceptedResultWithoutExternalCalls() throws Exception {
        Client c=new Client(owner);var accepted=completed(c);var draft=create(c);
        MODE.set("INVALID");var failed=create(c);UUID op=UUID.randomUUID();generate(c,failed,op);awaitState(c,op,"WAITING_MANUAL");MODE.set("NORMAL");
        int q=QUERIES.get(),g=GENERATIONS.get();var page=history(c,"");
        assertThat(page.path("items").valueStream().map(r->r.path("workspaceId").asString()).toList()).contains(accepted.path("workspaceId").asString(),draft.path("workspaceId").asString(),failed.path("workspaceId").asString());
        assertThat(page.path("items").valueStream().map(r->r.path("state").asString()).toList()).contains("SUCCEEDED","DRAFT","WAITING_MANUAL");
        assertThat(page.toString()).doesNotContain("线程池项目","resumeQuote","query_vector","prompt","payload");
        assertThat(c.get("/api/plan-workspaces").headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(history(new Client(other),"").path("items").isEmpty()).isTrue();
        assertThat(new Client(maintainer).get("/api/plan-workspaces").statusCode()).isEqualTo(403);
        assertThat(new Client(other).get("/api/plan-workspaces?resumeId="+accepted.path("resumeId").asString()).statusCode()).isEqualTo(404);
        assertThat(new Client(other).get("/api/plan-workspaces/"+accepted.path("workspaceId").asString()).statusCode()).isEqualTo(404);
        assertThat(QUERIES.get()).isEqualTo(q);assertThat(GENERATIONS.get()).isEqualTo(g);
        java.nio.file.Files.writeString(Path.of("../.cache",database+"-history-page.json"),page.toString());
    }
    @Test @Order(2) void keysetPagesAreStableForEqualTimesAndNewInsertions() throws Exception {
        var account=KnowledgeCollectionIT.account(AccountAuthority.TRIAL_USER);Client c=new Client(account);var expected=new HashSet<String>();
        for(int i=0;i<5;i++)expected.add(create(c).path("workspaceId").asString());
        // 只控制排序边界的数据库时钟夹具；记录本身全部由生产创建规则接受。
        jdbc.update("UPDATE plan_workspace SET created_at='2026-11-01T00:00:00Z' WHERE owner_id=?",account.id());
        var a=history(c,"?limit=2");assertThat(a.path("items").size()).isEqualTo(2);assertThat(a.path("nextCursor").isString()).isTrue();
        var newest=create(c);var seen=new ArrayList<String>();String cursor=null;
        for(int i=0;i<3;i++){
            var p=i==0?a:history(c,"?limit=2&cursor="+URLEncoder.encode(cursor,StandardCharsets.UTF_8));
            p.path("items").valueStream().forEach(r->seen.add(r.path("workspaceId").asString()));
            cursor=p.path("nextCursor").isNull()?null:p.path("nextCursor").asString();
        }
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(cursor).isNull();assertThat(seen).doesNotContain(newest.path("workspaceId").asString());
        assertThat(history(c,"?limit=2").path("items").get(0).path("workspaceId")).isEqualTo(newest.path("workspaceId"));
        for(String query:List.of("?limit=0","?limit=51","?cursor=not-a-cursor","?cursor="))assertThat(c.get("/api/plan-workspaces"+query).statusCode()).isEqualTo(400);
    }
    @Test @Order(3) void materialChangeKeepsFixedRevisionAndDraftVisibleButRequiresNewPlan() throws Exception {
        Client c=new Client(owner);var original=completed(c);String root=original.path("resumeId").asString(),workspace=original.path("workspaceId").asString();
        ok(c.post("/api/materials/resumes/"+root+"/jd-versions",Map.of("body","[P4-SYNTHETIC] Java 后端岗位换版，需要JMM和线程池。".repeat(5)),UUID.randomUUID()),201);
        var p=history(c,"?resumeId="+root);assertThat(p.path("items").size()).isEqualTo(1);var entry=p.path("items").get(0);
        assertThat(entry.path("materialsCurrent").asBoolean()).isFalse();assertThat(entry.path("jdVersion").asInt()).isEqualTo(1);
        assertThat(entry.path("jdVersionId")).isEqualTo(original.path("jdVersionId"));
        assertThat(ok(c.get("/api/plan-workspaces/"+workspace),200).path("plan")).isEqualTo(original.path("plan"));
        var detail=ok(c.get("/api/plans/"+entry.path("planId").asString()+"/revisions/"+entry.path("revisionId").asString()),200);
        assertThat(detail.path("canEdit").asBoolean()).isFalse();
    }
    @Test @Order(4) void currentSummaryAndFixedOlderRevisionNeverDrift() throws Exception {
        var f=PlanRevisionIT.fixture();var saved=ok(f.client().post(f.path()+"/revisions",PlanRevisionIT.save(f.view(),5),UUID.randomUUID()),201);
        ok(f.client().post(f.path()+"/confirmations",PlanRevisionIT.confirm(saved),UUID.randomUUID()),200);
        var row=history(f.client(),"?resumeId="+f.original().path("resumeId").asString()).path("items").get(0);
        assertThat(row.path("revisionId")).isEqualTo(saved.path("revision").path("id"));assertThat(row.path("confirmed").asBoolean()).isTrue();
        var old=ok(f.client().get(f.path()+"/revisions/"+f.view().path("revision").path("id").asString()),200);
        assertThat(old.path("revision").path("content").path("totalQuestions").asInt()).isEqualTo(8);
        assertThat(old.path("current").asBoolean()).isFalse();assertThat(old.path("canEdit").asBoolean()).isFalse();
    }
    @Test @Order(5) void deletedRootDisappearsImmediatelyAndOldCursorCannotBypassBarrier() throws Exception {
        Client c=new Client(owner);var original=completed(c);String root=original.path("resumeId").asString();
        var before=history(c,"");ok(c.delete("/api/materials/resumes/"+root),200);
        assertThat(history(c,"").path("items").valueStream().map(r->r.path("workspaceId").asString()).toList()).doesNotContain(original.path("workspaceId").asString());
        assertThat(c.get("/api/plan-workspaces?resumeId="+root).statusCode()).isEqualTo(404);
        assertThat(c.get("/api/plan-workspaces/"+original.path("workspaceId").asString()).statusCode()).isEqualTo(404);
        assertThat(history(c,"?limit=1").path("items").size()).isEqualTo(1);assertThat(before.path("items").size()).isPositive();
    }
    @Test @Order(6) void acceptedPlanCommitLossIsDiscoverableWithoutRegeneration() throws Exception {
        Client c=new Client(owner);var w=create(c);COMMIT_LOSS.set("PLAN");UUID op=UUID.randomUUID();generate(c,w,op);awaitState(c,op,"SUCCEEDED");
        int q=QUERIES.get(),g=GENERATIONS.get();var row=history(c,"?resumeId="+w.path("resumeId").asString()).path("items").get(0);
        assertThat(row.path("operationId").asString()).isEqualTo(op.toString());
        assertThat(ok(c.get("/api/plan-operations/"+op+"?kind=GENERATE_PLAN"),200).path("plan").path("planId")).isEqualTo(row.path("planId"));
        assertThat(QUERIES.get()).isEqualTo(q);assertThat(GENERATIONS.get()).isEqualTo(g);
    }
    @Test @Order(7) void databaseFailureIsNotAnEmptySuccessAndAnonymousIsRejected() throws Exception {
        Client c=new Client(owner);int q=QUERIES.get(),g=GENERATIONS.get();HISTORY_READ_FAIL.set(true);
        assertThat(c.get("/api/plan-workspaces").statusCode()).isEqualTo(503);assertThat(history(c,"").path("items").isEmpty()).isFalse();
        var anonymous=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(base.resolve("/api/plan-workspaces")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);assertThat(QUERIES.get()).isEqualTo(q);assertThat(GENERATIONS.get()).isEqualTo(g);
    }
    @Test @Order(8) void listAndDeleteSerializeThroughTheProductionAccountGuard() throws Exception {
        Client c=new Client(owner),reader=new Client(owner);var w=completed(c);String root=w.path("resumeId").asString();
        var executor=Executors.newSingleThreadExecutor();
        try(var connection=java.sql.DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM app.identity_account WHERE id=? FOR UPDATE")){lock.setObject(1,owner.id());lock.executeQuery();}
            var submitted=new CountDownLatch(1);var pending=executor.submit(()->{submitted.countDown();return history(reader,"?resumeId="+root);});assertThat(submitted.await(2,TimeUnit.SECONDS)).isTrue();
            await(()->jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>0);
            assertThat(pending.isDone()).isFalse();connection.commit();
            assertThat(pending.get(10,TimeUnit.SECONDS).path("items").size()).isEqualTo(1);
        }finally{executor.shutdownNow();}
        ok(c.delete("/api/materials/resumes/"+root),200);assertThat(reader.get("/api/plan-workspaces?resumeId="+root).statusCode()).isEqualTo(404);
        assertThat(history(reader,"").path("items").valueStream().map(r->r.path("workspaceId").asString()).toList()).doesNotContain(w.path("workspaceId").asString());
    }
    @Test @Order(90) void actualChromeHistoryNavigationIdentityAndDeletion() throws Exception {
        var account=KnowledgeCollectionIT.account(AccountAuthority.TRIAL_USER);Client c=new Client(account);var a=completed(c);var b=completed(c);var draft=create(c);int q=QUERIES.get(),g=GENERATIONS.get();
        int port=freePort();var directory=Path.of("../frontend").toAbsolutePath().normalize();var prefix=Path.of("../.cache",database+"-history-browser").toAbsolutePath();
        var vite=new ProcessBuilder("node.exe","node_modules/vite/bin/vite.js","--host","127.0.0.1","--port",Integer.toString(port),"--strictPort").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+"-vite.log").toFile());safeEnvironment(vite);vite.environment().put("BACKEND_URL",base.toString());Process server=vite.start();Process browser=null;
        try{
            await(()->{try{var con=(java.net.HttpURLConnection)java.net.URI.create("http://127.0.0.1:"+port+"/login").toURL().openConnection(java.net.Proxy.NO_PROXY);con.setConnectTimeout(500);con.setReadTimeout(500);try{return con.getResponseCode()==200;}finally{con.disconnect();}}catch(Exception e){return false;}});
            var node=new ProcessBuilder("node.exe","../scripts/Verify-P4HistoryBrowser.mjs").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Path.of(prefix+".log").toFile());safeEnvironment(node);
            node.environment().putAll(Map.ofEntries(Map.entry("P4_FRONTEND_BASE","http://127.0.0.1:"+port),Map.entry("P4_BROWSER_USER",account.login()),Map.entry("P4_BROWSER_PASSWORD",account.password()),Map.entry("P4_OTHER_USER",other.login()),Map.entry("P4_OTHER_PASSWORD",other.password()),Map.entry("P4_BROWSER_OWNER",account.id().toString()),Map.entry("P4_HISTORY_A",a.path("workspaceId").asString()),Map.entry("P4_HISTORY_B",b.path("workspaceId").asString()),Map.entry("P4_HISTORY_ROOT",a.path("resumeId").asString()),Map.entry("P4_HISTORY_DRAFT",draft.path("workspaceId").asString()),Map.entry("P4_BROWSER_SCREENSHOT",prefix+".png"),Map.entry("P4_BROWSER_REPORT",prefix+".json")));
            browser=node.start();assertThat(browser.waitFor(200,TimeUnit.SECONDS)).as("Chrome history %s.log",prefix).isTrue();assertThat(browser.exitValue()).as("Chrome history %s.log",prefix).isZero();
            assertThat(QUERIES.get()).isEqualTo(q);assertThat(GENERATIONS.get()).isEqualTo(g);
        } finally{if(browser!=null&&browser.isAlive())browser.destroyForcibly();server.destroyForcibly();server.waitFor(10,TimeUnit.SECONDS);}
    }
}
