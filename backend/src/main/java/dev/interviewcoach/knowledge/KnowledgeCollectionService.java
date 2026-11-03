package dev.interviewcoach.knowledge;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** M-06维护发布与探测；无供应商调用，不自动批准语义质量或供真实评分使用。 */
@Service
@Profile("local")
public final class KnowledgeCollectionService {
    private final KnowledgeCollectionRepository collections;
    private final KnowledgeRepository knowledge;
    private final KnowledgeIndexRepository index;
    private final AccountWriteGuard accounts;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    KnowledgeCollectionService(KnowledgeCollectionRepository collections,KnowledgeRepository knowledge,KnowledgeIndexRepository index,
            AccountWriteGuard accounts,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.collections=collections;this.knowledge=knowledge;this.index=index;this.accounts=accounts;this.mapper=mapper;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(5);
    }

    CollectionDetail publish(Actor actor,UUID expected,UUID operation,KnowledgeCollectionPolicy.Publish raw) {
        if(raw==null)throw new InvalidKnowledge("请提供完整集合清单");
        var input=raw.normalized();String digest=hash(input);
        return transaction.execute(status->{
            current(actor,expected);
            var prior=collections.findRequest(actor.accountId(),operation);
            if(prior.isPresent()) { check(prior.get(),"PUBLISH",digest);return detail(prior.get().resource()); }
            rejectOtherOperation(actor,operation);
            var selected=input.builds().stream().map(s->index.findBuild(s.buildId()).orElseThrow(KnowledgeUnavailable::new)).toList();
            // 所有多根操作同序：账号→UUID排序知识根→发布指针。停用/后台接收共享根锁。
            lockRoots(selected.stream().map(KnowledgeIndexRepository.Build::root).toList());
            var entries=new ArrayList<KnowledgeCollectionRepository.Entry>();
            KnowledgeIndexRepository.Build binding=null;
            for(var selection:input.builds()) {
                var build=index.findBuild(selection.buildId()).orElseThrow(KnowledgeUnavailable::new);
                if(!"SUCCEEDED".equals(build.state()))throw new KnowledgeStateConflict();
                var source=knowledge.findApproved(build.version()).orElseThrow(KnowledgeStateConflict::new);
                if(!source.documentId().equals(build.root()) || !source.digest().equals(build.digest())
                        || !KnowledgeIndexPolicy.REVISION.equals(build.policy())
                        || !KnowledgeIndexPolicy.hash(canonical(build.config())).equals(build.configDigest()))throw new KnowledgeConflict();
                if(binding==null)binding=build;
                else if(!binding.configDigest().equals(build.configDigest()) || !canonical(binding.config()).equals(canonical(build.config()))
                        || !binding.policy().equals(build.policy()))throw new KnowledgeConflict();
                entries.addAll(verifiedEntries(build,source,selection));
            }
            if(entries.isEmpty() || entries.size()>500 || entries.stream().map(KnowledgeCollectionRepository.Entry::chunkId).distinct().count()!=entries.size())
                throw new InvalidKnowledge("集合片段为空、重复或超过工程上限");
            var current=collections.lockCurrent().orElse(null);
            if(!Objects.equals(current,input.expectedCurrentCollectionId()))throw new KnowledgeStateConflict();
            UUID id=UUID.randomUUID();
            collections.create(id,actor.accountId(),binding,hash(entries),entries.size());
            for(var entry:entries)collections.insert(id,entry);
            collections.activate(id);
            collections.request(actor.accountId(),operation,"PUBLISH",digest,id);
            return detail(id);
        });
    }

    private List<KnowledgeCollectionRepository.Entry> verifiedEntries(KnowledgeIndexRepository.Build build,
            KnowledgeRepository.Version source,KnowledgeCollectionPolicy.Selection selection) {
        var expected=new HashMap<UUID,KnowledgeIndexPolicy.Chunk>();var batchIds=new HashMap<UUID,UUID>();int end=0;int ordinal=0;int batchOrdinal=0;
        var batches=index.batches(build.id());
        if(batches.isEmpty())throw new KnowledgeStateConflict();
        for(var batch:batches) {
            if(!"ACCEPTED".equals(batch.state()) || batch.ordinal()!=batchOrdinal++)throw new KnowledgeStateConflict();
            var chunks=mapper.readValue(batch.input(),new TypeReference<List<KnowledgeIndexPolicy.Chunk>>() { });
            // jsonb会重排键/空白；用原Chunk契约重建序列校验，不能直接hash数据库格式化文本。
            if(!KnowledgeIndexPolicy.hash(mapper.writeValueAsString(chunks)).equals(batch.digest()))throw new KnowledgeStateConflict();
            if(chunks.isEmpty() || chunks.size()>10)throw new KnowledgeStateConflict();
            for(var chunk:chunks) {
                if(chunk.startOffset()!=end || chunk.ordinal()!=ordinal++ || chunk.endOffset()>source.input().body().length()
                        || chunk.endOffset()<=end || !source.input().body().substring(end,chunk.endOffset()).equals(chunk.text())
                        || !KnowledgeIndexPolicy.hash(chunk.text()).equals(chunk.digest()) || expected.put(chunk.id(),chunk)!=null)
                    throw new KnowledgeStateConflict();
                end=chunk.endOffset();
                batchIds.put(chunk.id(),batch.id());
            }
        }
        if(end!=source.input().body().length())throw new KnowledgeStateConflict();
        var vectors=collections.vectors(build.id());
        var claims=new HashMap<UUID,KnowledgeCollectionPolicy.Claim>();
        selection.claims().forEach(c->claims.put(c.chunkId(),c));
        if(vectors.size()!=expected.size() || !claims.keySet().equals(expected.keySet()))throw new KnowledgeStateConflict();
        var seen=new HashSet<UUID>();var entries=new ArrayList<KnowledgeCollectionRepository.Entry>();var c=selection.conditions();
        for(var v:vectors) {
            var chunk=expected.get(v.chunk());
            if(chunk==null || !seen.add(v.chunk()) || !v.batch().equals(batchIds.get(v.chunk())) || v.ordinal()!=chunk.ordinal() || v.start()!=chunk.startOffset() || v.end()!=chunk.endOffset()
                    || !v.body().equals(chunk.text()) || !v.digest().equals(chunk.digest()))throw new KnowledgeStateConflict();
            var claim=claims.get(v.chunk());
            if(!v.body().contains(claim.quote()))throw new InvalidKnowledge("引用摘录不在对应片段原文中");
            entries.add(new KnowledgeCollectionRepository.Entry(v.chunk(),build.id(),build.version(),build.root(),source.digest(),source.reviewedAt(),
                    source.input().sourceUrl(),source.input().sourceAnchor(),source.input().applicability(),c.technology(),c.technologyVersion(),c.scenario(),
                    claim.claimKey(),claim.claimValue(),claim.quote(),claim.verificationNote(),v.start(),v.end(),v.body(),v.digest(),KnowledgeCollectionPolicy.vector(v.vector())));
        }
        return entries;
    }

    StopDetail stop(Actor actor,UUID expected,UUID operation,UUID root,UUID version,KnowledgeCollectionPolicy.Stop raw) {
        if(raw==null || raw.digest()==null)throw new InvalidKnowledge("请固定版本摘要及停用原因");
        var reason=KnowledgeInput.text(raw.reason(),2000);String digest=hash(List.of(root,version,raw.digest(),reason));
        return transaction.execute(status->{
            current(actor,expected);
            var prior=collections.findRequest(actor.accountId(),operation);
            if(prior.isPresent()) { check(prior.get(),"STOP",digest);return stopDetail(prior.get().resource()); }
            rejectOtherOperation(actor,operation);knowledge.lockRoot(root);
            var source=knowledge.findVersion(root,version).orElseThrow(KnowledgeUnavailable::new);
            if(!source.digest().equals(raw.digest()))throw new KnowledgeConflict();
            if(!"APPROVE".equals(source.decision()))throw new KnowledgeStateConflict();
            var stopped=collections.findStop(version);
            if(stopped.isPresent()) { if(!stopped.get().reason().equals(reason))throw new KnowledgeStateConflict(); }
            else {
                collections.stop(version,actor.accountId(),reason);
                index.invalidateForStop(version);
            }
            collections.request(actor.accountId(),operation,"STOP",digest,version);
            return stopDetail(version);
        });
    }

    Evidence probe(Actor actor,UUID expected,UUID collection,KnowledgeCollectionPolicy.Probe input) {
        return transaction.execute(status->{current(actor,expected);return selectFixed(collection,input);});
    }
    /** 同一M-06生产过滤规则供首个M-03消费者使用；调用方已持账号/资料锁，共享当前事务。 */
    Evidence selectFixed(UUID collection,KnowledgeCollectionPolicy.Probe input) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Evidence selection requires caller transaction");
        if(input==null)throw new InvalidKnowledge("请提供固定集合配置及公共/合成向量");
        String vector=KnowledgeCollectionPolicy.vector(input.vector());
            var fixed=collections.find(collection).orElseThrow(KnowledgeUnavailable::new);
            lockRoots(collections.roots(collection));
            if(!fixed.configDigest().equals(input.configDigest()))throw new KnowledgeConflict();
            if(input.conditions()==null || input.conditions().incomplete() || input.claimKey()==null || input.claimKey().isBlank())
                return evidence(fixed,"NO_APPLICABLE_EVIDENCE","CONDITIONS_REQUIRED",List.of());
            var c=input.conditions().normalized();var key=KnowledgeInput.text(input.claimKey(),200);
            var hits=collections.search(collection,c,key,vector);
            // 先识别同条件已人工标注的冲突，不能用TopK或阈值裁掉相反证据。
            if(hits.stream().map(h->h.entry().claimValue()).distinct().count()>1) {
                var conflicts=hits.stream().map(h->{var e=h.entry();return new Conflict(e.chunkId(),e.versionId(),e.claimKey(),e.claimValue(),e.sourceUrl(),e.sourceAnchor());}).toList();
                return new Evidence(fixed.id(),fixed.configDigest(),fixed.retrievalPolicy(),fixed.manifestDigest(),
                        "CONFLICT_UNRESOLVED","MANUAL_CLAIMS_DISAGREE",List.of(),conflicts,false);
            }
            var selected=new ArrayList<Candidate>();int size=0;
            for(var hit:hits) {
                if(!Double.isFinite(hit.similarity()) || hit.similarity()<KnowledgeCollectionPolicy.MIN_SIMILARITY)continue;
                var e=hit.entry();int length=e.body().codePointCount(0,e.body().length());
                if(size+length>KnowledgeCollectionPolicy.MAX_CODE_POINTS)continue;
                selected.add(new Candidate(e.chunkId(),e.buildId(),e.versionId(),e.documentId(),e.versionDigest(),e.reviewedAt(),
                        e.sourceUrl(),e.sourceAnchor(),e.applicability(),e.startOffset(),e.endOffset(),e.body(),e.digest(),
                        e.claimKey(),e.claimValue(),e.quote(),e.verificationNote(),hit.similarity()));size+=length;
                if(selected.size()==KnowledgeCollectionPolicy.TOP_K)break;
            }
            return evidence(fixed,selected.isEmpty()?"NO_APPLICABLE_EVIDENCE":"EVIDENCE_AVAILABLE",
                    selected.isEmpty()?"NO_ELIGIBLE_MATCH":"SEMANTIC_SUPPORT_REVIEW_REQUIRED",List.copyOf(selected));
    }
    /** 维护者从生产能力读取实际片段身份/原文，发布标注无需直接访问数据库。 */
    PublicationSource publicationSource(Actor actor,UUID buildId) {
        return transaction.execute(status->{
            maintainer(accounts.requireCurrent(actor));
            var build=index.findBuild(buildId).orElseThrow(KnowledgeUnavailable::new);knowledge.lockRoot(build.root());
            var source=knowledge.findVersion(build.root(),build.version()).orElseThrow(KnowledgeUnavailable::new);
            var chunks=collections.vectors(build.id()).stream().map(v->new PublicationChunk(v.chunk(),v.ordinal(),v.start(),v.end(),v.body(),v.digest())).toList();
            boolean eligible="SUCCEEDED".equals(build.state()) && knowledge.findApproved(build.version()).isPresent();
            return new PublicationSource(build.id(),build.root(),build.version(),build.digest(),build.configDigest(),build.policy(),
                    source.input().sourceUrl(),source.input().sourceAnchor(),source.input().applicability(),eligible,chunks);
        });
    }
    CollectionDetail read(Actor actor,UUID id) { maintainer(actor);return detail(id); }
    CollectionDetail current(Actor actor) { maintainer(actor);return detail(collections.findCurrent().orElseThrow(KnowledgeUnavailable::new)); }
    StopDetail stopped(Actor actor,UUID root,UUID version) {
        maintainer(actor);knowledge.findVersion(root,version).orElseThrow(KnowledgeUnavailable::new);return stopDetail(version);
    }
    Operation operation(Actor actor,UUID id) {
        maintainer(actor);var r=collections.findRequest(actor.accountId(),id).orElseThrow(KnowledgeUnavailable::new);
        return new Operation(id,r.kind(),r.resource(),r.digest());
    }
    private CollectionDetail detail(UUID id) {
        var c=collections.find(id).orElseThrow(KnowledgeUnavailable::new);
        return new CollectionDetail(c.id(),c.configDigest(),c.chunkPolicy(),c.retrievalPolicy(),c.manifestDigest(),c.entryCount(),c.publishedAt(),false);
    }
    private StopDetail stopDetail(UUID version) {
        var s=collections.findStop(version).orElseThrow(KnowledgeUnavailable::new);
        return new StopDetail(s.versionId(),s.actorId(),s.reason(),s.stoppedAt());
    }
    private Evidence evidence(KnowledgeCollectionRepository.Collection c,String status,String gap,List<Candidate> candidates) {
        return new Evidence(c.id(),c.configDigest(),c.retrievalPolicy(),c.manifestDigest(),status,gap,candidates,List.of(),false);
    }
    private void lockRoots(List<UUID> roots) { roots.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).forEach(knowledge::lockRoot); }
    private void current(Actor actor,UUID expected) {
        maintainer(accounts.requireCurrent(actor));if(!actor.accountId().equals(expected))throw new KnowledgeAccountChanged();
    }
    private static void maintainer(Actor actor) {
        if(!actor.hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER))throw new AccessDeniedException("Knowledge-maintainer authority is required.");
    }
    private void rejectOtherOperation(Actor actor,UUID op) {
        if(knowledge.findOperation(actor.accountId(),op).isPresent() || index.findRequest(actor.accountId(),op).isPresent())throw new KnowledgeConflict();
    }
    private static void check(KnowledgeCollectionRepository.Request r,String kind,String digest) {
        if(!r.kind().equals(kind) || !r.digest().equals(digest))throw new KnowledgeConflict();
    }
    private String hash(Object value) { return KnowledgeIndexPolicy.hash(mapper.writeValueAsString(value)); }
    private String canonical(String json) { return mapper.writeValueAsString(mapper.readValue(json,new TypeReference<java.util.TreeMap<String,String>>() { })); }
    record CollectionDetail(UUID collectionId,String configDigest,String chunkPolicy,String retrievalPolicy,String manifestDigest,int entryCount,OffsetDateTime publishedAt,boolean runtimeReady) { }
    record StopDetail(UUID versionId,UUID actorId,String reason,OffsetDateTime stoppedAt) { }
    record Operation(UUID operationId,String kind,UUID resourceId,String inputDigest) { }
    record PublicationSource(UUID buildId,UUID documentId,UUID versionId,String versionDigest,String configDigest,String chunkPolicy,
            String sourceUrl,String sourceAnchor,String applicability,boolean eligible,List<PublicationChunk> chunks) { }
    record PublicationChunk(UUID chunkId,int ordinal,int startOffset,int endOffset,String body,String digest) { }
    record Evidence(UUID collectionId,String configDigest,String retrievalPolicy,String manifestDigest,String status,String gap,List<Candidate> candidates,List<Conflict> conflicts,boolean runtimeReady) { }
    record Conflict(UUID chunkId,UUID versionId,String claimKey,String claimValue,String sourceUrl,String sourceAnchor) { }
    record Candidate(UUID chunkId,UUID buildId,UUID versionId,UUID documentId,String versionDigest,OffsetDateTime reviewedAt,
            String sourceUrl,String sourceAnchor,String applicability,int startOffset,int endOffset,String body,String digest,
            String claimKey,String claimValue,String quote,String verificationNote,double similarity) { }
}
