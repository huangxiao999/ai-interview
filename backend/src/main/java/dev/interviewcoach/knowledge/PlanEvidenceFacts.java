package dev.interviewcoach.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** M-06首用窄接口；不开放维护者私有访问权、不存M-03的query或向量。 */
@Service @Profile("local")
public final class PlanEvidenceFacts {
    private final KnowledgeCollectionRepository collections;
    private final KnowledgeRepository knowledge;
    private final KnowledgeCollectionService service;
    private final tools.jackson.databind.ObjectMapper mapper;
    PlanEvidenceFacts(KnowledgeCollectionRepository collections,KnowledgeRepository knowledge,KnowledgeCollectionService service,tools.jackson.databind.ObjectMapper mapper){
        this.collections=collections;this.knowledge=knowledge;this.service=service;
        this.mapper=mapper;
    }
    public record Piece(UUID chunkId,UUID versionId,String body,String digest,String sourceUrl,String sourceAnchor,String quote){ }
    public record Evidence(String status,String configDigest,String retrievalPolicy,List<Piece> pieces,boolean runtimeReady){ }
    public record Binding(String configDigest,String policy){ }
    public record Option(UUID collectionId,String configDigest,String technology,String technologyVersion,String scenario,String claimKey){ }
    public List<Option> options(){
        transaction();var current=collections.findCurrent();
        if(current.isEmpty())return List.of();
        var c=collections.find(current.get()).orElseThrow();lock(c.id(),c.configDigest());
        return collections.planOptions(c.id(),c.configDigest());
    }
    public Binding lock(UUID collection,String digest){
        transaction();
        var c=collections.find(collection).orElseThrow(()->new Rejected("KNOWLEDGE_NOT_READY"));
        collections.roots(collection).stream().sorted(java.util.Comparator.comparing(UUID::toString)).forEach(knowledge::lockRoot);
        if(!c.configDigest().equals(digest))throw new Rejected("CONFIGURATION_CONFLICT");
        var config=mapper.readTree(c.config());
        if(!"qwen3.7-text-embedding-flash".equals(config.path("model").asString()) || !"1024".equals(config.path("dimension").asString())
                || !"document".equals(config.path("textType").asString()) || !"dense".equals(config.path("outputType").asString()))
            throw new Rejected("QUERY_CONFIGURATION_CONFLICT");
        return new Binding(c.configDigest(),c.retrievalPolicy());
    }
    public void lockRebinding(UUID original,String digest,UUID replacement,String replacementDigest){
        transaction();
        var a=collections.find(original).orElseThrow(()->new Rejected("KNOWLEDGE_NOT_READY"));
        var b=collections.find(replacement).orElseThrow(()->new Rejected("KNOWLEDGE_NOT_READY"));
        java.util.stream.Stream.concat(collections.roots(original).stream(),collections.roots(replacement).stream()).distinct()
                .sorted(java.util.Comparator.comparing(UUID::toString)).forEach(knowledge::lockRoot);
        if(!a.configDigest().equals(digest) || !b.configDigest().equals(replacementDigest) || !digest.equals(replacementDigest))throw new Rejected("CONFIGURATION_CONFLICT");
        lock(original,digest);lock(replacement,replacementDigest);
    }
    public boolean hasCoverage(UUID collection,String digest,String technology,String version,String scenario,String claim){
        lock(collection,digest);
        return collections.planOptions(collection,digest).stream().anyMatch(o->o.technology().equals(technology)
                && o.technologyVersion().equals(version) && o.scenario().equals(scenario) && o.claimKey().equals(claim));
    }
    public Evidence select(UUID collection,String digest,String technology,String version,String scenario,String claim,List<Double> vector){
        lock(collection,digest);
        KnowledgeCollectionService.Evidence e;
        try{e=service.selectFixed(collection,new KnowledgeCollectionPolicy.Probe(digest,
                new KnowledgeCollectionPolicy.Conditions(technology,version,scenario),claim,vector));}
        catch(InvalidKnowledge invalid){throw new Rejected("INVALID_QUERY");}
        return new Evidence(e.status(),e.configDigest(),e.retrievalPolicy(),e.candidates().stream()
                .map(p->new Piece(p.chunkId(),p.versionId(),p.body(),p.digest(),p.sourceUrl(),p.sourceAnchor(),p.quote())).toList(),false);
    }
    public boolean stillEligible(List<Piece> pieces){
        transaction();
        return !pieces.isEmpty() && pieces.stream().allMatch(p->knowledge.findApproved(p.versionId()).isPresent());
    }
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Shared transaction required");}
    public static final class Rejected extends RuntimeException { public Rejected(String reason){super(reason);} }
}
