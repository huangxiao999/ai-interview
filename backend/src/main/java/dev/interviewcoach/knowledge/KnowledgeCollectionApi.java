package dev.interviewcoach.knowledge;

import dev.interviewcoach.identity.CurrentActor;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/maintenance/knowledge")
final class KnowledgeCollectionApi {
    private final KnowledgeCollectionService service;
    private final CurrentActor actors;
    KnowledgeCollectionApi(KnowledgeCollectionService service,CurrentActor actors) { this.service=service;this.actors=actors; }
    @GetMapping("/index-builds/{id}/publication-source")
    ResponseEntity<KnowledgeCollectionService.PublicationSource> publicationSource(Authentication auth,@PathVariable UUID id) {
        return result(service.publicationSource(actors.require(auth),id));
    }
    @PostMapping("/collections")
    ResponseEntity<KnowledgeCollectionService.CollectionDetail> publish(Authentication auth,@RequestHeader("X-Expected-Account-Id") UUID expected,
            @RequestHeader("X-Operation-Id") UUID operation,@RequestBody KnowledgeCollectionPolicy.Publish input) {
        return result(service.publish(actors.require(auth),expected,operation,input));
    }
    @GetMapping("/collections/current")
    ResponseEntity<KnowledgeCollectionService.CollectionDetail> current(Authentication auth) { return result(service.current(actors.require(auth))); }
    @GetMapping("/collections/{id}")
    ResponseEntity<KnowledgeCollectionService.CollectionDetail> read(Authentication auth,@PathVariable UUID id) { return result(service.read(actors.require(auth),id)); }
    @PostMapping("/collections/{id}/probe")
    ResponseEntity<KnowledgeCollectionService.Evidence> probe(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Expected-Account-Id") UUID expected,
            @RequestBody KnowledgeCollectionPolicy.Probe input) { return result(service.probe(actors.require(auth),expected,id,input)); }
    @PostMapping("/documents/{root}/versions/{version}/stop")
    ResponseEntity<KnowledgeCollectionService.StopDetail> stop(Authentication auth,@PathVariable UUID root,@PathVariable UUID version,
            @RequestHeader("X-Expected-Account-Id") UUID expected,@RequestHeader("X-Operation-Id") UUID operation,@RequestBody KnowledgeCollectionPolicy.Stop input) {
        return result(service.stop(actors.require(auth),expected,operation,root,version,input));
    }
    @GetMapping("/documents/{root}/versions/{version}/stop")
    ResponseEntity<KnowledgeCollectionService.StopDetail> stopped(Authentication auth,@PathVariable UUID root,@PathVariable UUID version) {
        return result(service.stopped(actors.require(auth),root,version));
    }
    @GetMapping("/collection-operations/{operation}")
    ResponseEntity<KnowledgeCollectionService.Operation> operation(Authentication auth,@PathVariable UUID operation) { return result(service.operation(actors.require(auth),operation)); }
    private static <T> ResponseEntity<T> result(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
