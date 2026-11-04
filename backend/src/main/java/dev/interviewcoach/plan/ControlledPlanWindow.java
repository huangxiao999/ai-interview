package dev.interviewcoach.plan;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import dev.interviewcoach.knowledge.JdkHttpTransportPolicy;
import dev.interviewcoach.knowledge.PlanEvidenceFacts;
import dev.interviewcoach.material.MaterialPlanFacts;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static dev.interviewcoach.plan.PlanPolicy.*;

/** M-03单次授权窗口。领域锁之后才锁窗口；窗口内不反向访问其他领域或执行网络I/O。 */
@Service @Profile("local")
final class ControlledPlanWindow {
    static final String AUTHORIZATION="p4-20261104-single-window-v1";
    private final JdbcTemplate jdbc;
    private final PlanRepository data;
    private final AccountWriteGuard accounts;
    private final MaterialPlanFacts materials;
    private final PlanEvidenceFacts knowledge;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Manifest manifest;
    private volatile boolean ready;
    record Manifest(String authorizationId,UUID ownerId,long credentialVersion,UUID workspaceId,String inputDigest,
                    String resumeDigest,String jdDigest,String caseId,OffsetDateTime startsAt,OffsetDateTime endsAt){ }
    record Approval(String kind,String wireDigest,long inputTokens,String tokenizerEvidence){ }
    record Bill(String kind,String requestId,BigDecimal billedCny,String reference){ }
    record Status(String state,String reason,List<Map<String,Object>> requests,BigDecimal reservedCny){ }
    ControlledPlanWindow(Environment env,JdbcTemplate jdbc,PlanRepository data,AccountWriteGuard accounts,
            MaterialPlanFacts materials,PlanEvidenceFacts knowledge,ObjectMapper mapper,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.data=data;this.accounts=accounts;this.materials=materials;this.knowledge=knowledge;this.mapper=mapper;
        tx=new TransactionTemplate(manager);tx.setTimeout(5);
        if(!env.getProperty("BAILIAN_PLAN_ENABLED",Boolean.class,false)){manifest=null;return;}
        if(!JdkHttpTransportPolicy.enforced() || env.getProperty("interview.plan.proof-enabled",Boolean.class,false)
                || !"false".equals(env.getProperty("BAILIAN_EMBEDDING_ENABLED","false")))
            throw new IllegalStateException("Controlled plan requires startup retry policy, proof off and document dispatch off (D=0)");
        try{
            Path path=Path.of(env.getRequiredProperty("P4_CONTROLLED_TRIAL_MANIFEST")).toAbsolutePath();
            if(Files.isSymbolicLink(path) || Files.size(path)>8192)throw new IllegalArgumentException();
            manifest=mapper.readerFor(Manifest.class).with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(Files.readString(path));
            if(manifest==null || !AUTHORIZATION.equals(manifest.authorizationId()) || manifest.ownerId()==null || manifest.workspaceId()==null
                    || manifest.credentialVersion()<1 || !digest(manifest.inputDigest()) || !digest(manifest.resumeDigest()) || !digest(manifest.jdDigest())
                    || manifest.caseId()==null || !manifest.caseId().matches("REF-P(?:0[1-9]|10)/v0\\.1")
                    || manifest.startsAt()==null || manifest.endsAt()==null || !manifest.endsAt().isAfter(manifest.startsAt()))throw new IllegalArgumentException();
        }catch(Exception invalid){throw new IllegalStateException("Invalid controlled plan manifest; do not include keys or private text");}
    }
    @EventListener(ApplicationReadyEvent.class) void initialize(){
        if(manifest==null)return;
        tx.executeWithoutResult(s->{
            var w=lockBinding(null);
            // Map.of的迭代顺序跨JVM不稳定；相同窗口重启必须得到同一摘要。
            String frozen=hash(mapper.writeValueAsString(new java.util.TreeMap<>(Map.of("manifest",manifest,"protocol",BailianPlanTransport.PROMPT_REVISION,"generation",CONFIG,"query",QUERY_CONFIG,"policy",POLICY))));
            jdbc.update("INSERT INTO plan_controlled_window(authorization_id,owner_id,workspace_id,manifest_digest,input_digest,starts_at,ends_at,state) VALUES(?,?,?,?,?,?,?,'ACTIVE') ON CONFLICT(authorization_id) DO NOTHING",
                    AUTHORIZATION,w.owner(),w.id(),frozen,w.digest(),manifest.startsAt(),manifest.endsAt());
            var row=lock();
            if(!frozen.equals(row.get("manifest_digest")))throw new IllegalStateException("Existing authorization cannot be replaced or reset");
            // 上次可能已外发的INTENT即使没有回执，也不能在重启后当作未发送。
            if(jdbc.queryForObject("SELECT count(*) FROM plan_controlled_request WHERE authorization_id=? AND state='INTENT'",Integer.class,AUTHORIZATION)>0)
                stopLocked("RESTART_EXECUTION_UNKNOWN");
        });ready=true;
    }
    boolean enabled(){return manifest!=null;}
    UUID workspace(){if(manifest==null)throw new Rejected("RESOURCE_UNAVAILABLE","受控窗口未配置");return manifest.workspaceId();}
    boolean bound(UUID workspace){return manifest!=null && manifest.workspaceId().equals(workspace);}
    boolean sameCredential(Actor actor){return manifest!=null && manifest.ownerId().equals(actor.accountId()) && manifest.credentialVersion()==actor.credentialVersion();}
    java.time.Instant deadline(java.time.Instant serviceDeadline){return manifest!=null && manifest.endsAt().toInstant().isBefore(serviceDeadline)?manifest.endsAt().toInstant():serviceDeadline;}
    String eligibility(UUID workspace){return jdbc.queryForObject("SELECT count(*) FROM plan_controlled_window WHERE workspace_id=?",Integer.class,workspace)>0?"CONTROLLED_TRIAL":"ENGINEERING_ONLY";}
    private static boolean digest(String s){return s!=null && s.matches("[a-f0-9]{64}");}
    /** 生产入口已持账号→资料→知识→工作稿锁；准备不消耗产品尝试或窗口派发。 */
    String reserve(PlanRepository.Work work,UUID attempt,String kind,String wireDigest){
        if(!ready || !bound(work.id()) || !manifest.inputDigest().equals(work.digest()) || work.revision()!=1)return "TRIAL_BINDING_REQUIRED";
        var window=lock();
        if(!"ACTIVE".equals(window.get("state")))return "TRIAL_"+window.get("state");
        var now=data.now();
        if(now.isBefore(manifest.startsAt()))return "TRIAL_NOT_STARTED";
        if(!now.isBefore(manifest.endsAt())){stopLocked("WINDOW_EXPIRED");return "TRIAL_STOPPED";}
        var rows=jdbc.queryForList("SELECT * FROM plan_controlled_request WHERE authorization_id=? AND kind=?",AUTHORIZATION,kind);
        if(rows.isEmpty()){
            jdbc.update("INSERT INTO plan_controlled_request(authorization_id,kind,wire_digest,output_limit,state) VALUES(?,?,?,?, 'PREPARED')",AUTHORIZATION,kind,wireDigest,"GENERATE".equals(kind)?4096:0);
            return "TRIAL_REQUEST_PREPARATION";
        }
        var request=rows.getFirst();
        if(!wireDigest.equals(request.get("wire_digest"))){stopLocked("FIXED_WIRE_CHANGED");return "TRIAL_STOPPED";}
        if(!"APPROVED".equals(request.get("state")))return "TRIAL_REQUEST_"+request.get("state");
        if(jdbc.queryForObject("SELECT count(*) FROM plan_controlled_request WHERE authorization_id=? AND state='INTENT'",Integer.class,AUTHORIZATION)>0)return "TRIAL_CAPACITY";
        long tokens=((Number)request.get("input_tokens")).longValue();
        // 按已确认费率全额预留，不抵扣免费额度，不把usage当实际账单；不退回已派发额度。
        BigDecimal cost=BigDecimal.valueOf(tokens).multiply(new BigDecimal("QUERY".equals(kind)?"0.000000125":"0.0000008"));
        if("GENERATE".equals(kind))cost=cost.add(new BigDecimal("0.0110592"));
        BigDecimal total=((BigDecimal)window.get("reserved_cny")).add(cost);
        if(total.compareTo(new BigDecimal("5"))>0){stopLocked("BUDGET_LIMIT");return "TRIAL_STOPPED";}
        String column="QUERY".equals(kind)?"query_used":"generation_used";
        if(((Number)window.get(column)).intValue()!=0)return "TRIAL_REQUEST_ALREADY_DISPATCHED";
        jdbc.update("UPDATE plan_controlled_window SET "+column+"="+column+"+1,reserved_cny=? WHERE authorization_id=?",total,AUTHORIZATION);
        // attempt外键在同一事务稍后INSERT，故此处由调用者最后调用attach。
        jdbc.update("UPDATE plan_controlled_request SET state='INTENT',reserved_cny=? WHERE authorization_id=? AND kind=?",cost,AUTHORIZATION,kind);
        return null;
    }
    void attach(UUID work,UUID attempt,String kind){if(bound(work))jdbc.update("UPDATE plan_controlled_request SET attempt_id=? WHERE authorization_id=? AND kind=? AND state='INTENT' AND attempt_id IS NULL",attempt,AUTHORIZATION,kind);}
    /** 最后一次资格复核及持久的一次性发送权；提交确认未知则调用者不会执行HTTP。 */
    boolean claim(UUID work,UUID attempt,String wireDigest,java.time.Instant deadline){
        if(!ready || !bound(work))return false;
        return Boolean.TRUE.equals(tx.execute(s->{
            var w=lockBinding(null);
            var in=w.input();boolean coverage=knowledge.hasCoverage(in.collectionId(),in.configDigest(),in.technology(),in.technologyVersion(),in.scenario(),in.claimKey());
            var row=lock();
            if(!"ACTIVE".equals(row.get("state")) || !data.now().isBefore(manifest.endsAt())
                    || !data.now().toInstant().isBefore(deadline) || !List.of("PREPARING_EVIDENCE","RUNNING").contains(w.state()))return false;
            if(!coverage){stopLocked("KNOWLEDGE_NOT_READY");return false;}
            return jdbc.update("UPDATE plan_controlled_request SET send_claimed=true WHERE authorization_id=? AND attempt_id=? AND wire_digest=? AND state='INTENT' AND send_claimed=false",AUTHORIZATION,attempt,wireDigest)==1;
        }));
    }
    /** 已有用量独立保存。缺失/矛盾立即停止，正常回执仍等实际费用核实再放下一派发。 */
    void receipt(UUID work,UUID attempt,PlanTransport.Receipt receipt){
        if(!bound(work))return;lock();
        var rows=jdbc.queryForList("SELECT * FROM plan_controlled_request WHERE attempt_id=?",attempt);if(rows.isEmpty())return;
        var row=rows.getFirst();
        if(!"INTENT".equals(row.get("state")))return;
        boolean valid=receipt!=null && receipt.requestId()!=null && receipt.inputTokens()!=null && receipt.outputTokens()!=null
                && receipt.inputTokens()>0 && receipt.inputTokens()<=((Number)row.get("input_tokens")).longValue()
                && receipt.outputTokens()<=((Number)row.get("output_limit")).longValue();
        jdbc.update("UPDATE plan_controlled_request SET request_id=?,reported_input=?,reported_output=?,state=? WHERE attempt_id=?",
                receipt==null?null:receipt.requestId(),receipt==null?null:receipt.inputTokens(),receipt==null?null:receipt.outputTokens(),valid?"RECEIPTED":"FAILED",attempt);
        if(!valid)stopLocked("USAGE_OR_EXECUTION_UNKNOWN");
        else jdbc.update("UPDATE plan_controlled_window SET state='WAITING_BILL',reason='BILL_NOT_VERIFIED' WHERE authorization_id=? AND state='ACTIVE'",AUTHORIZATION);
    }
    void stop(UUID work,String reason){if(bound(work)){lock();stopLocked(reason);}}
    boolean receiveAllowed(UUID work){
        if(!bound(work))return true;
        var row=lock();return !"STOPPED".equals(row.get("state"));
    }
    boolean resumable(UUID work){
        return jdbc.queryForObject("SELECT count(*) FROM plan_controlled_window WHERE workspace_id=? AND state='STOPPED'",Integer.class,work)==0;
    }
    private void stopLocked(String reason){
        // 第一次停用事实不被迟到异常改写；删除是更强的永久屏障，可覆盖此前原因。
        jdbc.update("UPDATE plan_controlled_window SET state='STOPPED',reason=? WHERE authorization_id=? AND (state<>'STOPPED' OR (?='DELETED' AND reason IS DISTINCT FROM 'DELETED'))",reason,AUTHORIZATION,reason);
    }
    private Map<String,Object> lock(){return jdbc.queryForMap("SELECT * FROM plan_controlled_window WHERE authorization_id=? FOR UPDATE",AUTHORIZATION);}
    private PlanRepository.Work lockBinding(Actor actor){
        if(manifest==null)throw new Rejected("RESOURCE_UNAVAILABLE","受控窗口未配置");
        var current=actor==null?accounts.lockCurrentAccount(manifest.ownerId()):accounts.requireCurrent(actor);
        if(!current.accountId().equals(manifest.ownerId()) || current.credentialVersion()!=manifest.credentialVersion() || !current.hasAuthority(AccountAuthority.TRIAL_USER))throw new Rejected("ACCOUNT_CHANGED","受控窗口身份已变化");
        var w=data.find(manifest.workspaceId()).orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE","工作稿不可用"));
        if(w.input()==null || !w.owner().equals(current.accountId()))throw new Rejected("RESOURCE_UNAVAILABLE","工作稿不可用");
        var facts=materials.require(current.accountId(),w.root(),w.input().resumeVersionId(),w.input().jdVersionId(),true);
        knowledge.lock(w.input().collectionId(),w.input().configDigest());data.lock(w.id());w=data.find(w.id()).orElseThrow();
        if(!w.owner().equals(current.accountId()) || !w.digest().equals(manifest.inputDigest()) || w.revision()!=1
                || !hash(facts.resume()).equals(manifest.resumeDigest()) || !hash(facts.jd()).equals(manifest.jdDigest())
                || !facts.resume().contains("[P4-SYNTHETIC]") || !facts.jd().contains("[P4-SYNTHETIC]"))throw new Rejected("OPERATION_CONFLICT","固定合成资料不匹配");
        return data.find(w.id()).orElseThrow();
    }
    /** 费用核实不要求资料仍是最新版，也不重新取得生成资格；删除后拒绝新增依据文字。 */
    private void lockAudit(Actor actor){
        if(manifest==null)throw new Rejected("RESOURCE_UNAVAILABLE","受控窗口未配置");
        var current=accounts.requireCurrent(actor);
        if(!current.accountId().equals(manifest.ownerId()) || !current.hasAuthority(AccountAuthority.TRIAL_USER))throw new Rejected("ACCOUNT_CHANGED","不是受控窗口拥有者");
        var w=data.find(manifest.workspaceId()).orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE","工作稿不可用"));
        materials.lockRoot(current.accountId(),w.root(),false);data.lock(w.id());
    }
    Status status(Actor actor){return tx.execute(s->{lockAudit(actor);var row=lock();return new Status(row.get("state").toString(),(String)row.get("reason"),
            jdbc.queryForList("SELECT kind,wire_digest,input_tokens,output_limit,tokenizer_evidence,state,attempt_id,request_id,reported_input,reported_output,reserved_cny,billed_cny,billing_reference FROM plan_controlled_request WHERE authorization_id=? ORDER BY kind",AUTHORIZATION),(BigDecimal)row.get("reserved_cny"));});}
    void approve(Actor actor,UUID expected,Approval a){tx.executeWithoutResult(s->{
        requireExpected(actor,expected);lockBinding(actor);var window=lock();
        if(a==null || !List.of("QUERY","GENERATE").contains(a.kind()==null?"":a.kind()) || !digest(a.wireDigest()) || a.inputTokens()<1 || a.inputTokens()>("QUERY".equals(a.kind())?4000:32000)
                || a.tokenizerEvidence()==null || a.tokenizerEvidence().isBlank() || a.tokenizerEvidence().length()>500)throw new Rejected("INVALID_INPUT","须登记对应完整请求的实际Token核计依据");
        var previous=jdbc.queryForList("SELECT input_tokens,tokenizer_evidence FROM plan_controlled_request WHERE authorization_id=? AND kind=? AND wire_digest=? AND input_tokens IS NOT NULL",AUTHORIZATION,a.kind(),a.wireDigest());
        if(!previous.isEmpty() && ((Number)previous.getFirst().get("input_tokens")).longValue()==a.inputTokens() && a.tokenizerEvidence().equals(previous.getFirst().get("tokenizer_evidence")))return;
        if(!"ACTIVE".equals(window.get("state")))throw new Rejected("OPERATION_CONFLICT","窗口未放行");
        if(jdbc.update("UPDATE plan_controlled_request SET input_tokens=?,tokenizer_evidence=?,state='APPROVED' WHERE authorization_id=? AND kind=? AND wire_digest=? AND state='PREPARED'",a.inputTokens(),a.tokenizerEvidence(),AUTHORIZATION,a.kind(),a.wireDigest())!=1)throw new Rejected("OPERATION_CONFLICT","仅可批准原未派发请求");
    });}
    void bill(Actor actor,UUID expected,Bill b){tx.executeWithoutResult(s->{
        requireExpected(actor,expected);lockAudit(actor);var row=lock();
        if(b==null || b.billedCny()==null || b.billedCny().signum()<0 || b.billedCny().scale()>8 || b.billedCny().compareTo(new BigDecimal("99999999.99999999"))>0 || b.reference()==null || b.reference().isBlank() || b.reference().length()>500)throw new Rejected("INVALID_INPUT","须提供实际费用核实依据，未知不可填零");
        var prior=jdbc.queryForList("SELECT billed_cny,billing_reference FROM plan_controlled_request WHERE authorization_id=? AND kind=? AND request_id=? AND billed_cny IS NOT NULL",AUTHORIZATION,b.kind(),b.requestId());
        if(!prior.isEmpty() && ((BigDecimal)prior.getFirst().get("billed_cny")).compareTo(b.billedCny())==0 && b.reference().equals(prior.getFirst().get("billing_reference")))return;
        var requests=jdbc.queryForList("SELECT * FROM plan_controlled_request WHERE authorization_id=? AND kind=? AND request_id=? AND state IN ('RECEIPTED','FAILED') AND billed_cny IS NULL",AUTHORIZATION,b.kind(),b.requestId());
        if(requests.isEmpty() || !List.of("WAITING_BILL","STOPPED").contains(row.get("state")))throw new Rejected("OPERATION_CONFLICT","只能核实原已知回执");
        var request=requests.getFirst();jdbc.update("UPDATE plan_controlled_request SET billed_cny=?,billing_reference=? WHERE authorization_id=? AND kind=?",b.billedCny(),b.reference(),AUTHORIZATION,b.kind());
        jdbc.update("UPDATE plan_external_attempt SET cost_unknown=false WHERE id=?",request.get("attempt_id"));
        if(b.billedCny().compareTo((BigDecimal)request.get("reserved_cny"))>0)stopLocked("BILL_EXCEEDS_RESERVATION");
        else jdbc.update("UPDATE plan_controlled_window SET state='ACTIVE',reason=NULL WHERE authorization_id=? AND state='WAITING_BILL'",AUTHORIZATION);
    });}
    void stop(Actor actor,UUID expected){tx.executeWithoutResult(s->{requireExpected(actor,expected);lockAudit(actor);lock();stopLocked("OPERATOR_STOPPED");});}
    private static void requireExpected(Actor actor,UUID expected){if(!actor.accountId().equals(expected))throw new Rejected("ACCOUNT_CHANGED","账号已变化");}
    void purge(UUID work){
        if(jdbc.queryForObject("SELECT count(*) FROM plan_controlled_window WHERE workspace_id=?",Integer.class,work)==0)return;
        jdbc.queryForList("SELECT authorization_id FROM plan_controlled_window WHERE workspace_id=? FOR UPDATE",work);
        jdbc.update("UPDATE plan_controlled_window SET state='STOPPED',reason='DELETED',input_digest=NULL WHERE workspace_id=?",work);
        jdbc.update("UPDATE plan_controlled_request SET wire_digest=NULL,tokenizer_evidence=NULL,billing_reference=NULL WHERE authorization_id IN(SELECT authorization_id FROM plan_controlled_window WHERE workspace_id=?)",work);
    }
}
