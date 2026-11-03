package dev.interviewcoach.plan;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import dev.interviewcoach.execution.ExternalAiPermits;
import dev.interviewcoach.knowledge.PlanEvidenceFacts;
import dev.interviewcoach.material.MaterialDeletionParticipant;
import dev.interviewcoach.material.MaterialPlanFacts;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static dev.interviewcoach.plan.PlanPolicy.*;

/** M-03默认计划首用：规则、尝试、候选、结果和私有删除在同一拥有者下闭环。 */
@Service @Profile("local")
public final class PlanService implements MaterialDeletionParticipant {
    private final PlanRepository data;
    private final MaterialPlanFacts materials;
    private final PlanEvidenceFacts knowledge;
    private final AccountWriteGuard accounts;
    private final PlanTransport transport;
    private final ExternalAiPermits permits;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Map<UUID,Pause> pending=new ConcurrentHashMap<>();
    private boolean recovered;
    PlanService(PlanRepository data,MaterialPlanFacts materials,PlanEvidenceFacts knowledge,AccountWriteGuard accounts,
                PlanTransport transport,ExternalAiPermits permits,ObjectMapper mapper,PlatformTransactionManager manager){
        this.data=data;this.materials=materials;this.knowledge=knowledge;this.accounts=accounts;this.transport=transport;
        this.permits=permits;this.mapper=mapper;transaction=new TransactionTemplate(manager);transaction.setTimeout(5);
    }
    record Source(UUID versionId,String sourceUrl,String sourceAnchor){ }
    record PlanDetail(UUID planId,UUID revisionId,Draft content,String eligibility,List<Source> sources){ }
    record Preparation(boolean available,String eligibility,List<PlanEvidenceFacts.Option> options){ }
    Preparation preparation(Actor actor){return transaction.execute(status->{user(actor,actor.accountId());return new Preparation(transport.available(),"ENGINEERING_ONLY",knowledge.options());});}
    record View(UUID workspaceId,UUID operationId,UUID resumeId,UUID resumeVersionId,UUID jdVersionId,String state,String reason,int initialUsed,int autoUsed,int manualUsed,int queryUsed,
                long generation,OffsetDateTime queuedAt,OffsetDateTime deadlineAt,JsonNode materialReview,
                List<Map<String,Object>> attempts,PlanDetail plan,String eligibility,boolean materialsCurrent,PlanEvidenceFacts.Option knowledgeBinding,
                boolean canAcceptCandidate,boolean canRetry,boolean requiresCostConfirmation){ }
    private record Context(PlanRepository.Work work,MaterialPlanFacts.Snapshot facts,boolean valid){ }
    private record Pause(PlanRepository.Work work,long resumeSeq,String stage){ }
    private record Dispatch(PlanRepository.Work work,UUID attempt,String kind,OffsetDateTime deadline,Object input){ }

    View create(Actor actor,UUID expected,UUID operation,Input raw){
        if(raw==null)throw new Rejected("INVALID_INPUT","请提供固定资料");
        var input=raw.checked();String digest=hash(mapper.writeValueAsString(input));
        return transaction.execute(status->{
            user(actor,expected);
            materials.require(actor.accountId(),input.resumeId(),input.resumeVersionId(),input.jdVersionId(),true);
            var old=data.findWhere("owner_id=? AND create_operation_id=?",actor.accountId(),operation);
            if(old.isPresent()){if(!old.get().digest().equals(digest))conflict();return view(old.get());}
            // 已明确变更资料是新计划输入；旧未完成工作失效，但原尝试和成功结果从不改写。
            data.write("UPDATE plan_workspace SET state='INVALIDATED',reason='MATERIAL_CHANGED',generation=generation+1 "
                    +"WHERE owner_id=? AND resume_id=? AND state NOT IN ('DRAFT','SUCCEEDED','FAILED_FINAL','INVALIDATED') "
                    +"AND (input->>'resumeVersionId'<>? OR input->>'jdVersionId'<>?)",actor.accountId(),input.resumeId(),input.resumeVersionId().toString(),input.jdVersionId().toString());
            if(data.count("SELECT count(*) FROM plan_workspace WHERE owner_id=? AND resume_id=? AND state NOT IN ('DRAFT','SUCCEEDED','FAILED_FINAL','INVALIDATED')",actor.accountId(),input.resumeId())>0)
                throw new Rejected("OPERATION_CONFLICT","请先核实或完成原计划操作");
            UUID id=UUID.randomUUID();materials.open(actor.accountId(),id,input.resumeId(),input.resumeVersionId(),input.jdVersionId(),input.materialsConfirmed());
            data.write("INSERT INTO plan_workspace(id,owner_id,resume_id,create_operation_id,initial_digest,input) VALUES(?,?,?,?,?,?::jsonb)",
                    id,actor.accountId(),input.resumeId(),operation,digest,mapper.writeValueAsString(input));
            data.write("INSERT INTO plan_input_revision(workspace_id,revision,input_digest,confirmation) VALUES(?,1,?,?::jsonb)",id,digest,mapper.writeValueAsString(Map.of("model",CONFIG,"query",QUERY_CONFIG,"policy",POLICY,"fixedInput",input,"materialsConfirmed",true,"restrictedCoverageConfirmed",true)));
            return view(data.find(id).orElseThrow());
        });
    }
    View generate(Actor actor,UUID expected,UUID workspace,UUID operation){
        return transaction.execute(status->{
            user(actor,expected);var w=owned(actor,workspace);var c=context(w,false);w=c.work();
            var requested=data.findWhere("owner_id=? AND operation_id=?",actor.accountId(),operation);
            if(requested.isPresent() && !requested.get().id().equals(workspace))conflict();
            if(w.operation()!=null)return view(w); // 同一业务步骤换UUID也不产生另一预算。
            if(!"DRAFT".equals(w.state()))conflict();
            if(!transport.available())throw new Rejected("PLAN_SERVICE_UNAVAILABLE","真实计划服务未放行，本次未派发");
            if(!c.valid() || !synthetic(c.facts()))throw new Rejected("PLAN_SERVICE_UNAVAILABLE","工程装配只允许明确标记的合成资料");
            if(data.count("SELECT count(*) FROM plan_workspace WHERE resume_id=? AND id<>? AND state NOT IN ('DRAFT','SUCCEEDED','FAILED_FINAL','INVALIDATED')",w.root(),w.id())>0)conflict();
            if(data.findWhere("owner_id=? AND operation_id=?",actor.accountId(),operation).isPresent())conflict();
            data.write("UPDATE plan_workspace SET operation_id=?,state='QUEUED',reason=NULL,queued_at=clock_timestamp() WHERE id=?",operation,w.id());
            return view(data.find(w.id()).orElseThrow());
        });
    }
    View readOperation(Actor actor,UUID id,String kind){
        return transaction.execute(status->{
            user(actor,actor.accountId());
            var w=switch(kind){
                case "CREATE_WORKSPACE"->data.findWhere("owner_id=? AND create_operation_id=?",actor.accountId(),id);
                case "GENERATE_PLAN"->data.findWhere("owner_id=? AND operation_id=?",actor.accountId(),id);
                case "RESUME"->data.findWhere("owner_id=? AND id IN(SELECT workspace_id FROM plan_resume_request WHERE owner_id=? AND request_id=?)",actor.accountId(),actor.accountId(),id);
                default->throw new Rejected("INVALID_INPUT","原操作种类不正确");
            };
            return view(w.orElseThrow(PlanService::missing));
        });
    }
    View readWorkspace(Actor actor,UUID id){return transaction.execute(status->{user(actor,actor.accountId());return view(owned(actor,id));});}
    View readPlan(Actor actor,UUID id){return transaction.execute(status->{user(actor,actor.accountId());
        return view(data.findWhere("owner_id=? AND id IN(SELECT workspace_id FROM interview_plan WHERE id=?)",actor.accountId(),id).orElseThrow(PlanService::missing));});}

    View resume(Actor actor,UUID expected,UUID operation,UUID resumeOperation,Resume input){
        if(input==null)throw new Rejected("INVALID_INPUT","请选择核实后的恢复动作");
        String digest=hash(mapper.writeValueAsString(input));
        return transaction.execute(status->{
            user(actor,expected);var original=data.findWhere("owner_id=? AND operation_id=?",actor.accountId(),operation).orElseThrow(PlanService::missing);
            var c=context(original,false,input.evidenceCollectionId(),input.evidenceConfigDigest());var w=c.work();
            if(data.count("SELECT count(*) FROM plan_resume_request WHERE workspace_id=? AND owner_id=? AND request_id=? AND input_digest=?",w.id(),actor.accountId(),resumeOperation,digest)==1)return view(w);
            if(data.count("SELECT count(*) FROM plan_resume_request WHERE owner_id=? AND request_id=?",actor.accountId(),resumeOperation)>0)conflict();
            if(!c.valid() || List.of("SUCCEEDED","INVALIDATED","FAILED_FINAL","QUEUED","RUNNING","PREPARING_EVIDENCE","DRAFT").contains(w.state()))conflict();
            if(!transport.available())throw new Rejected("PLAN_SERVICE_UNAVAILABLE","真实服务保持关闭");
            var candidate=data.candidate(w.id());String next;
            boolean rebind=input.evidenceCollectionId()!=null;
            if(rebind){
                if(!"RETRY".equals(input.action()) || !"BLOCKED_KNOWLEDGE".equals(w.state()) || input.evidenceCollectionId().equals(w.input().collectionId()))conflict();
                var old=w.input();var changed=new Input(old.resumeId(),old.resumeVersionId(),old.jdVersionId(),input.evidenceCollectionId(),input.evidenceConfigDigest(),old.technology(),old.technologyVersion(),old.scenario(),old.claimKey(),true,true,true).checked();
                if(!knowledge.hasCoverage(changed.collectionId(),changed.configDigest(),changed.technology(),changed.technologyVersion(),changed.scenario(),changed.claimKey()))throw new Rejected("KNOWLEDGE_NOT_READY","新集合仍无同条件的已审核依据");
                data.write("UPDATE plan_generation_candidate SET state='REJECTED' WHERE workspace_id=? AND state='STORED'",w.id());
                data.write("INSERT INTO plan_input_revision(workspace_id,revision,input_digest,confirmation) VALUES(?,?,?,?::jsonb)",w.id(),w.revision()+1,hash(mapper.writeValueAsString(changed)),mapper.writeValueAsString(Map.of("fixedInput",changed,"reason","EXPLICIT_KNOWLEDGE_REBIND")));
                data.write("UPDATE plan_workspace SET input=?::jsonb,input_revision=input_revision+1,evidence=NULL,prompt=NULL WHERE id=?",mapper.writeValueAsString(changed),w.id());
                candidate=java.util.Optional.empty();
            }
            if("ACCEPT".equals(input.action())){
                if(candidate.isEmpty() || candidate.get().generation()!=w.generation() || candidate.get().revision()!=w.revision())conflict();
                next="CANDIDATE_READY";
            }else if("RETRY".equals(input.action()) || "CONFIRM_INPUT".equals(input.action())){
                if(candidate.isPresent())throw new Rejected("OPERATION_CONFLICT","已有原候选，请恢复接收");
                if(List.of("EXTERNAL_UNKNOWN","LATE_RESPONSE").contains(w.reason()==null?"":w.reason()) && !input.acceptUnknownCost())throw new Rejected("RETRY_CONFIRMATION_REQUIRED","上次可能执行并收费，需要逐次确认");
                if("CONFIRM_INPUT".equals(input.action())){
                    if(!"WAITING_INPUT".equals(w.state()))conflict();materials.confirm(w.id(),input.findingDigest());
                    data.write("UPDATE plan_workspace SET input_revision=input_revision+1 WHERE id=?",w.id());
                    data.write("INSERT INTO plan_input_revision(workspace_id,revision,input_digest,confirmation) VALUES(?,?,?,?::jsonb)",w.id(),w.revision()+1,hash(w.digest()+":"+input.findingDigest()),mapper.writeValueAsString(Map.of("fixedInput",w.input(),"findingDigest",input.findingDigest())));
                }else if("WAITING_INPUT".equals(w.state()))conflict();
                if((w.vector()==null && w.query()>=3) || (w.initial()>0 && w.manual()>=2))throw new Rejected("OPERATION_CONFLICT","原步骤预算已耗尽");
                data.write("UPDATE plan_workspace SET manual_mode=true,generation=generation+1 WHERE id=?",w.id());next="QUEUED";
            }else throw new Rejected("INVALID_INPUT","恢复动作不正确");
            data.write("UPDATE plan_workspace SET state=?,reason=NULL,queued_at=clock_timestamp(),resume_seq=resume_seq+1,last_resume_id=?,last_resume_digest=? WHERE id=?",next,resumeOperation,digest,w.id());
            data.write("INSERT INTO plan_resume_request(owner_id,request_id,workspace_id,input_digest,accepted_seq) VALUES(?,?,?,?,?)",actor.accountId(),resumeOperation,w.id(),digest,w.resumeSeq()+1);
            return view(data.find(w.id()).orElseThrow());
        });
    }

    private View view(PlanRepository.Work w){
        if(w.input()==null)throw missing();
        var facts=materials.require(w.owner(),w.root(),w.input().resumeVersionId(),w.input().jdVersionId(),false);
        var result=data.plan(w.id()).map(p->new PlanDetail((UUID)p.get("id"),(UUID)p.get("revision_id"),
                mapper.readValue(p.get("payload").toString(),Draft.class),p.get("eligibility").toString(),evidence(w).pieces().stream().map(s->new Source(s.versionId(),s.sourceUrl(),s.sourceAnchor())).toList())).orElse(null);
        boolean hasCandidate=data.candidate(w.id()).isPresent();
        boolean resumable=facts.current() && configurationReady(w) && List.of("WAITING_MANUAL","PAUSED_LOCAL","BLOCKED_KNOWLEDGE").contains(w.state());
        return new View(w.id(),w.operation(),w.root(),w.input().resumeVersionId(),w.input().jdVersionId(),w.state(),w.reason(),w.initial(),w.automatic(),w.manual(),w.query(),w.generation(),w.queued(),w.deadline(),
                materials.review(w.id()),data.attempts(w.id()),result,"ENGINEERING_ONLY",facts.current(),
                new PlanEvidenceFacts.Option(w.input().collectionId(),w.input().configDigest(),w.input().technology(),w.input().technologyVersion(),w.input().scenario(),w.input().claimKey()),
                resumable && hasCandidate,resumable && !hasCandidate && (w.initial()==0 || w.manual()<2) && (w.vector()!=null || w.query()<3),
                List.of("EXTERNAL_UNKNOWN","LATE_RESPONSE").contains(w.reason()==null?"":w.reason()));
    }
    private void user(Actor actor,UUID expected){
        var current=accounts.requireCurrent(actor);
        if(!current.hasAuthority(AccountAuthority.TRIAL_USER))throw new AccessDeniedException("Trial user required");
        if(!current.accountId().equals(expected))throw new Rejected("ACCOUNT_CHANGED","账号已变化");
    }
    private PlanRepository.Work owned(Actor actor,UUID id){var w=data.find(id).orElseThrow(PlanService::missing);if(!w.owner().equals(actor.accountId()))throw missing();return w;}
    private Context context(PlanRepository.Work original,boolean audit){
        return context(original,audit,null,null);
    }
    private Context context(PlanRepository.Work original,boolean audit,UUID replacement,String replacementDigest){
        var actor=accounts.lockCurrentAccount(original.owner());
        boolean active=materials.lockRoot(original.owner(),original.root(),audit);
        // 领取快照可能在等待账号锁期间被人工重绑；后续领域锁必须来自锁内最新绑定。
        var fixed=data.find(original.id()).orElseThrow();
        MaterialPlanFacts.Snapshot facts=null;
        if(active && fixed.input()!=null){
            facts=materials.require(original.owner(),original.root(),fixed.input().resumeVersionId(),fixed.input().jdVersionId(),false);
            if(replacement==null){if(replacementDigest!=null)conflict();knowledge.lock(fixed.input().collectionId(),fixed.input().configDigest());}
            else knowledge.lockRebinding(fixed.input().collectionId(),fixed.input().configDigest(),replacement,replacementDigest);
        }
        data.lock(original.id());var w=data.find(original.id()).orElseThrow();
        boolean valid=active && actor.hasAuthority(AccountAuthority.TRIAL_USER) && facts!=null && facts.current() && w.input()!=null;
        if(valid && !configurationReady(w)){
            // 原操作只能使用首次固定的配置；部署升级不得静默给旧操作换模型、提示词或预算政策。
            if(!List.of("SUCCEEDED","INVALIDATED","FAILED_FINAL").contains(w.state()))state(w,"PAUSED_LOCAL","CONFIGURATION_CHANGED");
            return new Context(data.find(w.id()).orElseThrow(),facts,false);
        }
        if(!valid && !List.of("SUCCEEDED","INVALIDATED").contains(w.state()))
            data.write("UPDATE plan_workspace SET state='INVALIDATED',reason='QUALIFICATION_CHANGED',generation=generation+1 WHERE id=?",w.id());
        return new Context(data.find(w.id()).orElseThrow(),facts,valid);
    }
    private static boolean synthetic(MaterialPlanFacts.Snapshot f){return f!=null && f.resume().contains("[P4-SYNTHETIC]") && f.jd().contains("[P4-SYNTHETIC]");}
    private static Rejected missing(){return new Rejected("RESOURCE_UNAVAILABLE","计划或原操作不可访问");}
    private static void conflict(){throw new Rejected("OPERATION_CONFLICT","原操作、状态或固定输入不一致");}
    private boolean configurationReady(PlanRepository.Work work){
        return data.fixedConfiguration(work.id()).map(c->CONFIG.equals(c.path("model").asString()) && QUERY_CONFIG.equals(c.path("query").asString()) && POLICY.equals(c.path("policy").asString())).orElse(false);
    }

    /** 启动恢复先查业务成功；未接收候选一律等待显式恢复，缺暂停行绝不表示可自动接收。 */
    synchronized boolean reconcile(){
        for(var p:List.copyOf(pending.values())){
            transaction.executeWithoutResult(status->{var c=context(p.work(),true);var w=c.work();
                if(c.valid() && !"SUCCEEDED".equals(w.state()) && w.resumeSeq()==p.resumeSeq()){
                    if("DISPATCH".equals(p.stage()))data.write("UPDATE plan_external_attempt SET state='FAILED' WHERE workspace_id=? AND state='DISPATCH_INTENT'",w.id());
                    // 外调未知的落库失败不允许降级为普通本地暂停，否则会绕过逐次费用确认。
                    if("EXTERNAL_UNKNOWN".equals(p.stage())){
                        data.write("UPDATE plan_external_attempt SET state='EXTERNAL_UNKNOWN' WHERE workspace_id=? AND state='DISPATCH_INTENT'",w.id());
                        data.write("UPDATE plan_workspace SET state='WAITING_MANUAL',reason='EXTERNAL_UNKNOWN' WHERE id=?",w.id());
                    }else data.write("UPDATE plan_workspace SET state='PAUSED_LOCAL',reason=? WHERE id=?",p.stage().equals("DISPATCH")?"DISPATCH_NOT_SENT":"LOCAL_RESULT_UNKNOWN",w.id());
                }});
            pending.remove(p.work().id(),p);
        }
        if(!recovered){
            for(var id:data.interrupted())transaction.executeWithoutResult(status->{var old=data.find(id).orElseThrow();var c=context(old,true);var w=c.work();
                if(!c.valid() || "SUCCEEDED".equals(w.state()))return;
                if(data.candidate(id).isPresent())data.write("UPDATE plan_workspace SET state='PAUSED_LOCAL',reason='RESTART_CANDIDATE' WHERE id=?",id);
                else {
                    boolean unknown=data.attempt(id).map(a->"DISPATCH_INTENT".equals(a.state())).orElse(false);
                    data.write("UPDATE plan_external_attempt SET state='EXTERNAL_UNKNOWN' WHERE workspace_id=? AND state='DISPATCH_INTENT'",id);
                    data.write("UPDATE plan_workspace SET state='WAITING_MANUAL',reason=?,generation=generation+1 WHERE id=?",unknown?"EXTERNAL_UNKNOWN":"RESTART_PREPARATION",id);
                }});
            recovered=true;
        }
        return pending.size()<5;
    }
    List<UUID> ready(){return data.ready();}
    void work(UUID id){
        if(pending.containsKey(id))return;
        var original=data.find(id).orElseThrow();
        if("CANDIDATE_READY".equals(original.state())){
            try{accept(original);}catch(RuntimeException failure){pause(original,"ACCEPT");throw failure;}return;
        }
        if(!"QUEUED".equals(original.state()) || !transport.available())return;
        var reserved=new AtomicBoolean(false);
        try{
            var w=transaction.execute(status->{data.limitLockWait(original.queued().plusSeconds(20));var c=context(original,true);var row=c.work();
                if(!c.valid() || !"QUEUED".equals(row.state()))return null;
                if(!data.now().isBefore(row.queued().plusSeconds(20))){state(row,"WAITING_MANUAL","QUEUE_TIMEOUT");return null;}
                if(!permits.acquire())return null;reserved.set(true);
                data.write("UPDATE plan_workspace SET state='PREPARING_EVIDENCE',deadline_at=clock_timestamp()+interval '60 seconds' WHERE id=?",id);
                return data.find(id).orElseThrow();});
            if(w==null)return;
            if(w.vector()==null){
                var dispatch=dispatch(w,"QUERY");
                if(dispatch==null)return;
                PlanTransport.Reply response;
                try{response=transport.send("QUERY",dispatch.input(),dispatch.deadline().toInstant());}
                catch(PlanTransport.Failure failure){recordFailure(dispatch,failure);return;}
                finally{release(reserved);}
                saveQuery(dispatch,response);w=data.find(id).orElseThrow();
            }else release(reserved);
            final var prepared=w;
            boolean evidenceReady=Boolean.TRUE.equals(transaction.execute(status->{data.limitLockWait(prepared.deadline());var c=context(prepared,true);var row=c.work();
                if(!c.valid() || !live(row,prepared))return false;
                var vector=mapper.readValue(row.vector(),new TypeReference<List<Double>>(){});
                var in=row.input();var proof=knowledge.select(in.collectionId(),in.configDigest(),in.technology(),in.technologyVersion(),in.scenario(),in.claimKey(),vector);
                if(!"EVIDENCE_AVAILABLE".equals(proof.status()) || proof.pieces().stream().anyMatch(p->!p.body().contains("[P4-SYNTHETIC]"))){state(row,"BLOCKED_KNOWLEDGE",proof.status());return false;}
                data.write("UPDATE plan_workspace SET evidence=?::jsonb WHERE id=?",mapper.writeValueAsString(proof),row.id());return true;}));
            if(!evidenceReady)return;
            if(!permits.acquire()){
                transaction.executeWithoutResult(status->{var c=context(prepared,true);if(c.valid())state(c.work(),"WAITING_MANUAL","CAPACITY_BEFORE_DISPATCH");});return;
            }
            reserved.set(true);w=data.find(id).orElseThrow();var dispatch=dispatch(w,"GENERATE");
            if(dispatch==null)return;
            PlanTransport.Reply response;
            try{response=transport.send("GENERATE",dispatch.input(),dispatch.deadline().toInstant());}
            catch(PlanTransport.Failure failure){recordFailure(dispatch,failure);return;}
            finally{release(reserved);}
            try{saveCandidate(dispatch,response);}catch(RuntimeException failure){pause(dispatch.work(),"CANDIDATE");throw failure;}
        }catch(MaterialPlanFacts.Rejected | PlanEvidenceFacts.Rejected invalid){
            transaction.executeWithoutResult(status->{accounts.lockCurrentAccount(original.owner());materials.lockRoot(original.owner(),original.root(),true);data.lock(id);
                var row=data.find(id).orElseThrow();if(!List.of("SUCCEEDED","INVALIDATED").contains(row.state()))state(row,"BLOCKED_KNOWLEDGE",invalid.getMessage());});
        }finally{release(reserved);}
    }
    private void release(AtomicBoolean reserved){if(reserved.getAndSet(false))permits.release();}
    private Dispatch dispatch(PlanRepository.Work original,String kind){
        try{return transaction.execute(status->{data.limitLockWait(original.deadline());var c=context(original,true);var w=c.work();if(!c.valid() || !live(w,original))return null;
            int ordinal;String category;
            if(kind.equals("QUERY")){
                var in=w.input();if(!knowledge.hasCoverage(in.collectionId(),in.configDigest(),in.technology(),in.technologyVersion(),in.scenario(),in.claimKey())){state(w,"BLOCKED_KNOWLEDGE","NO_APPLICABLE_EVIDENCE");return null;}
                if(w.query()>=3){state(w,"WAITING_MANUAL","QUERY_BUDGET_EXHAUSTED");return null;}ordinal=w.query()+1;category=ordinal==1?"INITIAL":"MANUAL";}
            else{ordinal=w.initial()+w.automatic()+w.manual()+1;
                if(w.initial()==0)category="INITIAL";else if(!w.manualMode() && w.automatic()==0)category="AUTO";else if(w.manualMode() && w.manual()<2)category="MANUAL";else{state(w,w.manual()>=2?"FAILED_FINAL":"WAITING_MANUAL","BUDGET_EXHAUSTED");return null;}}
            UUID attempt=UUID.randomUUID();OffsetDateTime deadline=kind.equals("QUERY")?min(w.deadline(),data.now().plusSeconds(10)):w.deadline();
            Object payload;
            if(kind.equals("QUERY"))payload=Map.of("model",QUERY_CONFIG,"query",w.input().technology()+" "+w.input().technologyVersion()+" "+w.input().scenario()+" "+w.input().claimKey(),"dimension",1024);
            else{var proof=evidence(w);if(!knowledge.stillEligible(proof.pieces())){state(w,"BLOCKED_KNOWLEDGE","KNOWLEDGE_STOPPED");return null;}
                payload=Map.of("model",MODEL,"configRevision",CONFIG,"policy",POLICY,"inputRevision",w.revision(),"resume",redact(c.facts().resume()),"jd",redact(c.facts().jd()),
                        "conditions",w.input(),"evidence",proof,"maxOutputTokens",4096);
                data.write("UPDATE plan_workspace SET prompt=?::jsonb WHERE id=?",mapper.writeValueAsString(payload),w.id());}
            data.write("INSERT INTO plan_external_attempt(id,workspace_id,kind,ordinal,category,generation,input_revision,state,deadline_at) VALUES(?,?,?,?,?,?,?,'DISPATCH_INTENT',?)",
                    attempt,w.id(),kind,ordinal,category,w.generation(),w.revision(),deadline);
            if(kind.equals("QUERY"))data.write("UPDATE plan_workspace SET query_used=query_used+1 WHERE id=?",w.id());
            else{String column=switch(category){case "INITIAL"->"initial_used";case "AUTO"->"auto_used";default->"manual_used";};
                data.write("UPDATE plan_workspace SET state='RUNNING',"+column+"="+column+"+1 WHERE id=?",w.id());}
            return new Dispatch(w,attempt,kind,deadline,payload);
        });}catch(RuntimeException unknown){pause(original,"DISPATCH");throw unknown;}
    }
    private static OffsetDateTime min(OffsetDateTime a,OffsetDateTime b){return a.isBefore(b)?a:b;}
    private static String redact(String s){return s.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[已移除邮箱]")
            .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])","[已移除电话]").replaceAll("(?m)^(姓名|身份证|证件号|name)\\s*[:：].*$","[已移除非必要身份信息]");}
    private boolean live(PlanRepository.Work w,PlanRepository.Work old){return w.generation()==old.generation() && !List.of("INVALIDATED","SUCCEEDED","FAILED_FINAL").contains(w.state()) && w.deadline()!=null && data.now().isBefore(w.deadline());}
    private PlanEvidenceFacts.Evidence evidence(PlanRepository.Work w){return mapper.readValue(w.evidence(),PlanEvidenceFacts.Evidence.class);}
    private void receipt(UUID id,PlanTransport.Receipt r){if(r==null)return;
        data.write("UPDATE plan_external_attempt SET request_id=COALESCE(request_id,?),input_tokens=COALESCE(input_tokens,?),output_tokens=COALESCE(output_tokens,?) WHERE id=?",r.requestId(),r.inputTokens(),r.outputTokens(),id);}
    /** 已核实用量独立于候选资格；只补原attempt，不因向量/停用/删除拒收而丢失回执。 */
    private void auditReceipt(Dispatch d,PlanTransport.Receipt receipt){
        transaction.executeWithoutResult(status->{accounts.lockCurrentAccount(d.work().owner());
            materials.lockRoot(d.work().owner(),d.work().root(),true);data.lock(d.work().id());receipt(d.attempt(),receipt);});
    }
    private void saveQuery(Dispatch d,PlanTransport.Reply response){
        try{
        // 回执登记也是原query的本地接收步骤；失败或提交未知必须进入同一暂停保护。
        auditReceipt(d,response.receipt());
        transaction.executeWithoutResult(status->{data.limitLockWait(d.deadline());var c=context(d.work(),true);receipt(d.attempt(),response.receipt());var w=c.work();
            if(!c.valid() || !live(w,d.work()) || !data.now().isBefore(d.deadline())){
                data.write("UPDATE plan_external_attempt SET state='FENCED' WHERE id=?",d.attempt());
                if(c.valid() && w.generation()==d.work().generation() && !List.of("SUCCEEDED","FAILED_FINAL").contains(w.state()))state(w,"WAITING_MANUAL","LATE_RESPONSE");return;}
            JsonNode vector=response.content().path("vector");if(!vector.isArray() || vector.size()!=1024)throw new Rejected("INVALID_CANDIDATE","查询向量维度错误");
            var values=mapper.convertValue(vector,new TypeReference<List<Double>>(){});
            // float32非零和有限数值仍由M-06生产向量策略核查，不用测试向量绕过。
            var in=w.input();knowledge.select(in.collectionId(),in.configDigest(),in.technology(),in.technologyVersion(),in.scenario(),in.claimKey(),values);
            data.write("UPDATE plan_workspace SET query_vector=?::jsonb WHERE id=?",mapper.writeValueAsString(values),w.id());
            data.write("UPDATE plan_external_attempt SET state='RESPONSE_STORED' WHERE id=?",d.attempt());
        });}catch(Rejected | PlanEvidenceFacts.Rejected invalid){recordFailure(d,new PlanTransport.Failure("INVALID_QUERY",response.receipt()));}
        catch(RuntimeException unknown){pause(d.work(),"QUERY");throw unknown;}
    }
    private void saveCandidate(Dispatch d,PlanTransport.Reply response){
        auditReceipt(d,response.receipt());
        String payload=mapper.writeValueAsString(response.content());
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536){recordFailure(d,new PlanTransport.Failure("RESPONSE_LIMIT",response.receipt()));return;}
        transaction.executeWithoutResult(status->{data.limitLockWait(d.deadline());var c=context(d.work(),true);var w=c.work();receipt(d.attempt(),response.receipt());
            if(!c.valid() || w.generation()!=d.work().generation() || List.of("SUCCEEDED","FAILED_FINAL","INVALIDATED").contains(w.state())){
                data.write("UPDATE plan_external_attempt SET state='FENCED' WHERE id=?",d.attempt());return;}
            if(!live(w,d.work())){data.write("UPDATE plan_external_attempt SET state='FENCED' WHERE id=?",d.attempt());state(w,"WAITING_MANUAL","LATE_RESPONSE");return;}
            if(!knowledge.stillEligible(evidence(w).pieces())){data.write("UPDATE plan_external_attempt SET state='FENCED' WHERE id=?",d.attempt());state(w,"BLOCKED_KNOWLEDGE","KNOWLEDGE_STOPPED");return;}
            data.write("INSERT INTO plan_generation_candidate(id,workspace_id,attempt_id,generation,input_revision,payload,digest,state) VALUES(?,?,?,?,?,?::jsonb,?,'STORED') ON CONFLICT(attempt_id) DO NOTHING",
                    UUID.randomUUID(),w.id(),d.attempt(),w.generation(),w.revision(),payload,hash(payload));
            data.write("UPDATE plan_external_attempt SET state='RESPONSE_STORED' WHERE id=?",d.attempt());state(w,"CANDIDATE_READY",null);
        });
    }
    private void accept(PlanRepository.Work original){
        transaction.executeWithoutResult(status->{var c=context(original,true);var w=c.work();if(!c.valid() || "SUCCEEDED".equals(w.state()))return;
            var candidate=data.candidate(w.id()).orElse(null);if(candidate==null || candidate.generation()!=w.generation() || candidate.revision()!=w.revision())return;
            var proof=evidence(w);if(!knowledge.stillEligible(proof.pieces())){state(w,"BLOCKED_KNOWLEDGE","KNOWLEDGE_STOPPED");return;}
            try{
                Candidate parsed=mapper.readerFor(Candidate.class).with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(candidate.payload());
                if(parsed==null)throw new Rejected("INVALID_CANDIDATE","候选不能为空");
                String findings=materials.findings(w.id(),c.facts(),parsed.materialDecision(),parsed.findings()==null?List.of():parsed.findings());
                if(!List.of("CLEAR","CONFIRMED").contains(materials.review(w.id()).path("state").asString())){
                    data.write("UPDATE plan_generation_candidate SET state='REJECTED' WHERE id=?",candidate.id());
                    state(w,"WAITING_INPUT",parsed.materialDecision()+":"+findings);return;
                }
                validate(parsed.plan(),w.input(),c.facts(),proof.pieces().stream().map(PlanEvidenceFacts.Piece::chunkId).toList());
                data.write("INSERT INTO interview_plan(id,revision_id,workspace_id,payload,eligibility) VALUES(?,?,?,?::jsonb,'ENGINEERING_ONLY') ON CONFLICT(workspace_id) DO NOTHING",
                        UUID.randomUUID(),UUID.randomUUID(),w.id(),mapper.writeValueAsString(parsed.plan()));
                data.write("UPDATE plan_generation_candidate SET state='ACCEPTED' WHERE id=?",candidate.id());state(w,"SUCCEEDED",null);
            }catch(Rejected | MaterialPlanFacts.Rejected | tools.jackson.core.JacksonException invalid){
                data.write("UPDATE plan_generation_candidate SET state='REJECTED' WHERE id=?",candidate.id());failure(w,"INVALID_CANDIDATE",false);
            }
        });
    }
    private void recordFailure(Dispatch d,PlanTransport.Failure failure){
        try{
        auditReceipt(d,failure.receipt);
        transaction.executeWithoutResult(status->{var c=context(d.work(),true);receipt(d.attempt(),failure.receipt);
            if(c.valid() && c.work().generation()==d.work().generation() && !"SUCCEEDED".equals(c.work().state()))failure(c.work(),failure.category,"EXTERNAL_UNKNOWN".equals(failure.category));
        });}catch(RuntimeException unknown){pause(d.work(),"EXTERNAL_UNKNOWN");throw unknown;}
    }
    private void failure(PlanRepository.Work w,String reason,boolean unknown){
        var a=data.attempt(w.id()).orElse(null);if(a==null)return;
        data.write("UPDATE plan_external_attempt SET state=? WHERE id=?",unknown?"EXTERNAL_UNKNOWN":"FAILED",a.id());
        if(unknown){state(w,"WAITING_MANUAL","EXTERNAL_UNKNOWN");return;}
        if(a.kind().equals("QUERY")){state(w,"WAITING_MANUAL",reason);return;}
        if(!w.manualMode() && w.automatic()==0){data.write("UPDATE plan_workspace SET state='QUEUED',reason=?,queued_at=clock_timestamp() WHERE id=?",reason,w.id());}
        else state(w,w.manual()>=2?"FAILED_FINAL":"WAITING_MANUAL",reason);
    }
    private void state(PlanRepository.Work w,String state,String reason){data.write("UPDATE plan_workspace SET state=?,reason=? WHERE id=?",state,reason,w.id());}
    private void pause(PlanRepository.Work w,String stage){pending.putIfAbsent(w.id(),new Pause(w,w.resumeSeq(),stage));}

    @Override public Impact impact(UUID owner,UUID root){
        return new Impact(data.count("SELECT count(*) FROM interview_plan p JOIN plan_workspace w ON w.id=p.workspace_id WHERE w.owner_id=? AND w.resume_id=?",owner,root),
                data.count("SELECT count(*) FROM plan_workspace WHERE owner_id=? AND resume_id=? AND operation_id IS NOT NULL",owner,root),
                data.count("SELECT count(*) FROM plan_generation_candidate c JOIN plan_workspace w ON w.id=c.workspace_id WHERE w.owner_id=? AND w.resume_id=?",owner,root));
    }
    @Override public void invalidateAndRegister(UUID owner,UUID root,UUID deletion){
        for(UUID id:data.graphs(root)){data.lock(id);var w=data.find(id).orElseThrow();if(!w.owner().equals(owner))throw new IllegalStateException("Plan owner mismatch");
            data.write("UPDATE plan_workspace SET state='INVALIDATED',reason='DELETED',generation=generation+1 WHERE id=?",id);
            data.write("INSERT INTO material_cleanup_item(id,delete_request_id,target_type,target_id,state) VALUES(?,?,'PLAN_PRIVATE_GRAPH',?,'PENDING') ON CONFLICT DO NOTHING",UUID.randomUUID(),deletion,id);
        }
    }
    void cleanup(){
        for(var target:data.cleanup())try{transaction.executeWithoutResult(status->{
            UUID owner=(UUID)target.get("owner_id"),root=(UUID)target.get("resume_id"),id=(UUID)target.get("target_id");
            accounts.lockCurrentAccount(owner);if(materials.lockRoot(owner,root,true))throw new IllegalStateException("Cannot purge an active root");data.lock(id);
            data.write("UPDATE plan_workspace SET input=NULL,query_vector=NULL,evidence=NULL,prompt=NULL,last_resume_digest=NULL,cleanup_attempted_at=clock_timestamp(),cleanup_error_code=NULL WHERE id=?",id);
            data.write("UPDATE plan_input_revision SET confirmation=NULL WHERE workspace_id=?",id);
            data.write("UPDATE plan_generation_candidate SET payload=NULL,state='PURGED' WHERE workspace_id=?",id);
            data.write("UPDATE interview_plan SET payload=NULL WHERE workspace_id=?",id);
            data.write("UPDATE material_cleanup_item SET state='CLEANED' WHERE id=?",target.get("id"));
            data.write("UPDATE material_delete_request d SET state='CLEANED',cleaned_at=clock_timestamp() WHERE id=? AND state='PENDING' "
                    +"AND NOT EXISTS(SELECT 1 FROM material_cleanup_item c WHERE c.delete_request_id=d.id AND c.state='PENDING')",target.get("delete_request_id"));
        });}catch(RuntimeException failure){
            // 完成事务回滚后只记原图失败；不撤销屏障、不重置24小时期限，轮转后继续其他目标。
            transaction.executeWithoutResult(status->{UUID owner=(UUID)target.get("owner_id"),root=(UUID)target.get("resume_id"),id=(UUID)target.get("target_id");
                accounts.lockCurrentAccount(owner);materials.lockRoot(owner,root,true);data.lock(id);
                data.write("UPDATE plan_workspace SET cleanup_attempted_at=clock_timestamp(),cleanup_error_code='DATABASE_CLEANUP_FAILED' WHERE id=?",id);
            });
        }
    }
}
