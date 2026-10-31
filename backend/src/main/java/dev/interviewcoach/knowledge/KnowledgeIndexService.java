package dev.interviewcoach.knowledge;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("local")
public final class KnowledgeIndexService {
    private final KnowledgeIndexRepository repository;
    private final KnowledgeRepository knowledge;
    private final AccountWriteGuard accounts;
    private final EmbeddingGateway gateway;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final UUID executor=UUID.randomUUID();

    KnowledgeIndexService(KnowledgeIndexRepository repository, KnowledgeRepository knowledge, AccountWriteGuard accounts,
            EmbeddingGateway gateway,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.repository=repository;this.knowledge=knowledge;this.accounts=accounts;this.gateway=gateway;this.mapper=mapper;
        transaction=new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    Detail create(Actor actor,UUID expected,UUID operation,UUID root,UUID version,String digest) {
        String input=KnowledgeIndexPolicy.hash(root+":"+version+":"+digest);
        return transaction.execute(status->{
            current(actor,expected);
            var previous=repository.findRequest(actor.accountId(),operation);
            if(previous.isPresent()) {
                if(!previous.get().kind().equals("CREATE") || !previous.get().digest().equals(input)) throw new KnowledgeConflict();
                return detail(repository.findBuild(previous.get().build()).orElseThrow(KnowledgeUnavailable::new));
            }
            if(knowledge.findOperation(actor.accountId(),operation).isPresent()) throw new KnowledgeConflict();
            knowledge.lockRoot(root);
            var source=knowledge.findVersion(root,version).orElseThrow(KnowledgeUnavailable::new);
            if(!source.digest().equals(digest)) throw new KnowledgeConflict();
            if(!"APPROVE".equals(source.decision())) throw new KnowledgeStateConflict();
            String config=configuration();
            var existing=repository.findBinding(version,KnowledgeIndexPolicy.hash(config));
            if(existing.isPresent()) {
                repository.request(actor.accountId(),operation,existing.get().id(),"CREATE",input);
                return detail(existing.get());
            }
            if(!gateway.available()) throw new IndexServiceUnavailable();
            var build=new KnowledgeIndexRepository.Build(UUID.randomUUID(),root,version,actor.accountId(),digest,
                    KnowledgeIndexPolicy.hash(config),config,KnowledgeIndexPolicy.REVISION,"QUEUED",null);
            repository.create(build);
            int ordinal=0;
            for(var chunks:KnowledgeIndexPolicy.batches(source.input().body())) repository.batch(build.id(),ordinal++,mapper.writeValueAsString(chunks));
            repository.request(actor.accountId(),operation,build.id(),"CREATE",input);
            return detail(build);
        });
    }

    Detail resume(Actor actor,UUID expected,UUID operation,UUID buildId,UUID expectedAttempt,boolean acknowledge) {
        var original=repository.findBuild(buildId).orElseThrow(KnowledgeUnavailable::new);
        String input=KnowledgeIndexPolicy.hash(buildId+":"+expectedAttempt+":"+acknowledge);
        return transaction.execute(status->{
            current(actor,expected);
            knowledge.lockRoot(original.root());
            var old=repository.findRequest(actor.accountId(),operation);
            if(old.isPresent()) {
                if(!old.get().kind().equals("RESUME") || !old.get().digest().equals(input)) throw new KnowledgeConflict();
                return detail(repository.findBuild(old.get().build()).orElseThrow());
            }
            if(knowledge.findOperation(actor.accountId(),operation).isPresent()) throw new KnowledgeConflict();
            var build=repository.findBuild(buildId).orElseThrow();
            if(!qualified(build)) throw new KnowledgeStateConflict();
            if(!build.state().equals("WAITING_MANUAL")) throw new KnowledgeStateConflict();
            var batch=repository.batches(buildId).stream().filter(b->!b.state().equals("ACCEPTED")).findFirst().orElseThrow(KnowledgeStateConflict::new);
            if(!java.util.Objects.equals(expectedAttempt,batch.attempt())) throw new KnowledgeConflict();
            if(batch.state().equals("CANDIDATE_READY")) {
                // 接收未知先核实原事务；不清候选、不花外调机会。
                repository.state(buildId,batch.id(),"CANDIDATE_READY",null);
            } else {
                if(batch.attempts()>=3) throw new KnowledgeStateConflict();
                // 已派发后的再次外调都可能新增费用；本轮没有可核实账单，逐次确认不能只覆盖断连。
                if(batch.attempt()!=null && !acknowledge)
                    throw new IndexRetryConfirmation();
                if(!gateway.available() || !configuration().equals(canonical(build.config()))) throw new IndexServiceUnavailable();
                repository.resume(build,batch);
            }
            repository.request(actor.accountId(),operation,buildId,"RESUME",input);
            return detail(repository.findBuild(buildId).orElseThrow());
        });
    }

    Detail operation(Actor actor,UUID id) {
        maintainer(actor);
        var request=repository.findRequest(actor.accountId(),id).orElseThrow(KnowledgeUnavailable::new);
        return detail(repository.findBuild(request.build()).orElseThrow(KnowledgeUnavailable::new));
    }
    List<Detail> list(Actor actor,UUID root,UUID version) { maintainer(actor); return repository.list(root,version).stream().map(this::detail).toList(); }
    Detail read(Actor actor,UUID id) { maintainer(actor); return detail(repository.findBuild(id).orElseThrow(KnowledgeUnavailable::new)); }

    /** 单活动执行者调用；每次仅处理一个固定批次，所有网络I/O在事务外。 */
    public void workOnce() {
        var selection=repository.findWork();
        if(selection.isEmpty()) return;
        var selected=selection.get();
        var original=repository.findBuild(selected.build()).orElseThrow();
        Dispatch dispatch;
        try {
        dispatch=transaction.execute(status->{
            var currentActor=accounts.lockCurrentAccount(original.owner());
            knowledge.lockRoot(original.root());
            var build=repository.findBuild(original.id()).orElseThrow();
            var batch=repository.findBatch(selected.id()).orElseThrow();
            if(!currentActor.hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER) || !qualified(build)) { repository.invalidate(build,batch);return null; }
            if(batch.state().equals("CANDIDATE_READY")) { accept(build,batch);return null; }
            if(batch.state().equals("RUNNING")) {
                var attempt=repository.findAttempt(batch.attempt()).orElseThrow();
                if(!attempt.executor().equals(executor) || !repository.now().isBefore(attempt.deadline())) repository.failed(build,batch,"EXTERNAL_UNKNOWN",true);
                return null;
            }
            if(!batch.state().equals("QUEUED") || !build.state().equals("QUEUED")) return null;
            if(repository.now().isAfter(batch.queuedAt().plusSeconds(KnowledgeIndexPolicy.QUEUE_SECONDS))) {
                repository.state(build.id(),batch.id(),"WAITING_MANUAL","QUEUE_TIMEOUT");return null;
            }
            if(!gateway.available() || !configuration().equals(canonical(build.config()))) {
                repository.state(build.id(),batch.id(),"WAITING_MANUAL","SERVICE_UNAVAILABLE");return null;
            }
            UUID attempt=UUID.randomUUID();
            OffsetDateTime now=repository.now();
            repository.dispatch(build,batch,attempt,executor,now);
            return new Dispatch(build,repository.findBatch(batch.id()).orElseThrow(),now.plusSeconds(KnowledgeIndexPolicy.SERVICE_SECONDS));
        });
        } catch(RuntimeException failure) {
            try { repository.suspendAcceptance(original.id()); }
            catch(RuntimeException unavailable) { failure.addSuppressed(unavailable); }
            throw failure;
        }
        // execute返回即已成功提交；若提交抛错，本方法退出，绝不据内存的尝试意图派发。
        if(dispatch==null) return;
        try {
            boolean valid=Boolean.TRUE.equals(transaction.execute(status->{
                var actor=accounts.lockCurrentAccount(dispatch.build().owner()); knowledge.lockRoot(dispatch.build().root());
                if(!actor.hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER) || !qualified(dispatch.build())) {
                    repository.invalidate(dispatch.build(),dispatch.batch());return false;
                }
                return active(dispatch);
            }));
            if(!valid) return;
            var chunks=chunks(dispatch.batch());
            var response=gateway.embed(chunks.stream().map(KnowledgeIndexPolicy.Chunk::text).toList(),dispatch.deadline().toInstant());
            validate(response,chunks.size());
            transaction.executeWithoutResult(status->{
                var actor=accounts.lockCurrentAccount(dispatch.build().owner());knowledge.lockRoot(dispatch.build().root());
                if(!actor.hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER) || !qualified(dispatch.build())) repository.invalidate(dispatch.build(),dispatch.batch());
                else if(active(dispatch)) repository.candidate(dispatch.build(),dispatch.batch(),mapper.writeValueAsString(response),response);
            });
        } catch(IndexExternalFailure failure) {
            transaction.executeWithoutResult(status->{
                accounts.lockCurrentAccount(dispatch.build().owner());knowledge.lockRoot(dispatch.build().root());
                if(active(dispatch)) repository.failed(dispatch.build(),dispatch.batch(),failure.category,
                        failure.category.equals("EXTERNAL_UNKNOWN") || failure.category.equals("DEADLINE"));
            });
        }
    }

    /** 每次启动只恢复一次原本已存但接收暂停的候选，无外部调用。 */
    void recoverAcceptance() {
        for(var row:repository.recoveryCandidates()) {
            var b=repository.findBuild(row).orElseThrow();
            transaction.executeWithoutResult(s->{ accounts.lockCurrentAccount(b.owner());knowledge.lockRoot(b.root());
                var batch=repository.batches(b.id()).stream().filter(x->x.state().equals("CANDIDATE_READY")).findFirst();
                batch.ifPresent(x->repository.state(b.id(),x.id(),"CANDIDATE_READY",null));
            });
        }
    }

    private void accept(KnowledgeIndexRepository.Build build,KnowledgeIndexRepository.Batch batch) {
        var response=mapper.readValue(repository.findCandidate(batch.attempt()).orElseThrow(),EmbeddingGateway.Response.class);
        var chunks=chunks(batch);validate(response,chunks.size());
        repository.accept(build,batch,chunks,response);
    }
    private boolean active(Dispatch d) {
        var current=repository.findBatch(d.batch().id()).orElseThrow();
        return current.state().equals("RUNNING") && d.batch().attempt().equals(current.attempt())
                && current.generation()==d.batch().generation()
                && repository.now().isBefore(repository.findAttempt(current.attempt()).orElseThrow().deadline());
    }
    private boolean qualified(KnowledgeIndexRepository.Build b) {
        return knowledge.findApproved(b.version()).filter(v->v.documentId().equals(b.root()) && v.digest().equals(b.digest())).isPresent();
    }
    private List<KnowledgeIndexPolicy.Chunk> chunks(KnowledgeIndexRepository.Batch b) {
        List<KnowledgeIndexPolicy.Chunk> result=mapper.readValue(b.input(),new TypeReference<List<KnowledgeIndexPolicy.Chunk>>(){});
        if(!KnowledgeIndexPolicy.hash(mapper.writeValueAsString(result)).equals(b.digest())) throw new KnowledgeConflict();
        return result;
    }
    private String configuration() { return mapper.writeValueAsString(new java.util.TreeMap<>(gateway.configuration())); }
    private String canonical(String json) { return mapper.writeValueAsString(new java.util.TreeMap<>(mapper.readValue(json,new TypeReference<Map<String,String>>(){}))); }
    private void current(Actor observed,UUID expected) { maintainer(accounts.requireCurrent(observed));if(!observed.accountId().equals(expected)) throw new KnowledgeAccountChanged(); }
    private static void maintainer(Actor actor) { if(!actor.hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER)) throw new AccessDeniedException("Knowledge maintainer required"); }
    private static void validate(EmbeddingGateway.Response response,int count) {
        if(response==null || response.vectors()==null || response.vectors().size()!=count) throw new IndexExternalFailure("INVALID_RESPONSE");
        for(var vector:response.vectors()) {
            if(vector==null || vector.size()!=1024) throw new IndexExternalFailure("INVALID_DIMENSION");
            boolean nonzero=false;
            for(Double v:vector) { if(v==null || !Double.isFinite(v)) throw new IndexExternalFailure("INVALID_VECTOR");if(v!=0) nonzero=true; }
            if(!nonzero) throw new IndexExternalFailure("ZERO_VECTOR");
        }
        if(response.requestId()!=null && response.requestId().length()>200) throw new IndexExternalFailure("INVALID_RESPONSE");
    }
    private Detail detail(KnowledgeIndexRepository.Build build) {
        var batches=new ArrayList<BatchDetail>();
        for(var b:repository.batches(build.id())) {
            var a=b.attempt()==null ? null : repository.findAttempt(b.attempt()).orElseThrow();
            batches.add(new BatchDetail(b.id(),b.ordinal(),b.state(),b.attempts(),3-b.attempts(),b.generation(),b.attempt(),
                    a==null?null:a.state(),a==null?null:a.deadline()));
        }
        return new Detail(build.id(),build.root(),build.version(),build.state(),build.error(),build.digest(),build.configDigest(),build.policy(),List.copyOf(batches));
    }
    record Dispatch(KnowledgeIndexRepository.Build build,KnowledgeIndexRepository.Batch batch,OffsetDateTime deadline) { }
    record Detail(UUID buildId,UUID documentId,UUID versionId,String state,String errorCategory,String versionDigest,String configDigest,String policy,List<BatchDetail> batches) { }
    record BatchDetail(UUID batchId,int ordinal,String state,int attempts,int remaining,long generation,UUID attemptId,String attemptState,OffsetDateTime deadline) { }
}

class IndexRetryConfirmation extends RuntimeException { }
