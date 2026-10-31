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
final class KnowledgeApi {
    private final KnowledgeService service;
    private final CurrentActor actors;
    KnowledgeApi(KnowledgeService service, CurrentActor actors) { this.service = service; this.actors = actors; }

    @PostMapping("/documents")
    ResponseEntity<KnowledgeService.VersionDetail> create(Authentication auth,
            @RequestHeader("X-Expected-Account-Id") UUID expected, @RequestHeader("X-Operation-Id") UUID operation,
            @RequestBody KnowledgeInput input) {
        return result(service.submit(actors.require(auth), expected, operation, null, input));
    }

    @PostMapping("/documents/{root}/versions")
    ResponseEntity<KnowledgeService.VersionDetail> append(Authentication auth, @PathVariable UUID root,
            @RequestHeader("X-Expected-Account-Id") UUID expected, @RequestHeader("X-Operation-Id") UUID operation,
            @RequestBody KnowledgeInput input) {
        return result(service.submit(actors.require(auth), expected, operation, root, input));
    }

    @GetMapping("/documents")
    ResponseEntity<List<KnowledgeService.VersionDetail>> list(Authentication auth) {
        return result(service.list(actors.require(auth)));
    }

    @GetMapping("/documents/{root}/versions/{version}")
    ResponseEntity<KnowledgeService.VersionDetail> read(Authentication auth, @PathVariable UUID root,
            @PathVariable UUID version) { return result(service.read(actors.require(auth), root, version)); }

    @PostMapping("/documents/{root}/versions/{version}/review")
    ResponseEntity<KnowledgeService.VersionDetail> review(Authentication auth, @PathVariable UUID root,
            @PathVariable UUID version, @RequestHeader("X-Expected-Account-Id") UUID expected,
            @RequestHeader("X-Operation-Id") UUID operation, @RequestBody ReviewInput input) {
        if (input == null) throw new InvalidKnowledge("请填写审核决定");
        return result(service.review(actors.require(auth), expected, operation, root, version,
                input.digest(), input.decision(), input.reason()));
    }

    @GetMapping("/operations/{operation}")
    ResponseEntity<KnowledgeService.OperationResult> operation(Authentication auth, @PathVariable UUID operation) {
        return result(service.operation(actors.require(auth), operation));
    }

    private static <T> ResponseEntity<T> result(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    record ReviewInput(String digest, String decision, String reason) { }
}
