package dev.interviewcoach.knowledge;

import dev.interviewcoach.identity.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/maintenance/knowledge")
final class KnowledgeIndexApi {
    private final KnowledgeIndexService service;
    private final CurrentActor actors;
    KnowledgeIndexApi(KnowledgeIndexService service,CurrentActor actors) { this.service=service;this.actors=actors; }

    @PostMapping("/documents/{root}/versions/{version}/index-builds")
    ResponseEntity<KnowledgeIndexService.Detail> create(Authentication auth,@PathVariable UUID root,@PathVariable UUID version,
            @RequestHeader("X-Expected-Account-Id") UUID expected,@RequestHeader("X-Operation-Id") UUID operation,@RequestBody Create input) {
        if(input==null || input.digest()==null) throw new InvalidKnowledge("请指定固定知识摘要");
        return result(service.create(actors.require(auth),expected,operation,root,version,input.digest()));
    }
    @GetMapping("/documents/{root}/versions/{version}/index-builds")
    ResponseEntity<List<KnowledgeIndexService.Detail>> list(Authentication auth,@PathVariable UUID root,@PathVariable UUID version) {
        return result(service.list(actors.require(auth),root,version));
    }
    @GetMapping("/index-builds/{id}")
    ResponseEntity<KnowledgeIndexService.Detail> read(Authentication auth,@PathVariable UUID id) { return result(service.read(actors.require(auth),id)); }
    @PostMapping("/index-builds/{id}/resume")
    ResponseEntity<KnowledgeIndexService.Detail> resume(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Expected-Account-Id") UUID expected,
            @RequestHeader("X-Operation-Id") UUID operation,@RequestBody Resume input) {
        if(input==null) throw new InvalidKnowledge("请核实原尝试");
        return result(service.resume(actors.require(auth),expected,operation,id,input.attemptId(),input.acceptPossibleDuplicateCharge()));
    }
    @GetMapping("/index-operations/{operation}")
    ResponseEntity<KnowledgeIndexService.Detail> operation(Authentication auth,@PathVariable UUID operation) {
        return result(service.operation(actors.require(auth),operation));
    }
    private static <T> ResponseEntity<T> result(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    record Create(String digest) { }
    record Resume(UUID attemptId,boolean acceptPossibleDuplicateCharge) { }
}
