package dev.interviewcoach.material;

import dev.interviewcoach.identity.CurrentActor;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("local")
@RequestMapping("/api/materials/uploads")
final class UploadApi {
    private final UploadService service;
    private final CurrentActor actors;
    UploadApi(UploadService service, CurrentActor actors) { this.service = service; this.actors = actors; }

    @GetMapping
    ResponseEntity<java.util.List<Status>> drafts(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.drafts(actors.require(auth)).stream().map(this::statusBody).toList());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Status> upload(Authentication auth, @RequestHeader("X-Expected-Account-Id") UUID expected,
            @RequestHeader("X-Operation-Id") UUID operation, @RequestParam(required = false) UUID resumeId,
            @RequestParam MultipartFile file) throws IOException {
        return ok(service.upload(actors.require(auth), expected, operation, resumeId, file.getOriginalFilename(), file.getBytes()));
    }
    @GetMapping("/operations/{operation}")
    ResponseEntity<Status> operation(Authentication auth, @PathVariable UUID operation) {
        return ok(service.byOperation(actors.require(auth), operation));
    }
    @GetMapping("/{id}")
    ResponseEntity<Status> status(Authentication auth, @PathVariable UUID id) {
        return ok(service.visible(actors.require(auth), id));
    }

    @GetMapping("/versions/{versionId}")
    ResponseEntity<Status> forVersion(Authentication auth, @PathVariable UUID versionId) {
        return ok(service.forVersion(actors.require(auth), versionId));
    }
    @PostMapping("/{id}/confirmation")
    ResponseEntity<ResumeApi.ResumeDetail> confirm(Authentication auth, @PathVariable UUID id,
            @RequestHeader("X-Expected-Account-Id") UUID expected,
            @RequestHeader("X-Operation-Id") UUID operation, @RequestBody ResumeApi.BodyInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ResumeApi.ResumeDetail.from(
                service.confirm(actors.require(auth), expected, id, operation, input == null ? null : input.body())));
    }
    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(Authentication auth, @PathVariable UUID id,
            @RequestHeader("X-Expected-Account-Id") UUID expected) {
        service.cancel(actors.require(auth), expected, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{id}/original")
    ResponseEntity<byte[]> original(Authentication auth, @PathVariable UUID id) throws IOException {
        var u = service.visible(actors.require(auth), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(u.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(service.original(actors.require(auth), id));
    }
    private ResponseEntity<Status> ok(UploadRepository.Upload u) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(statusBody(u));
    }
    private Status statusBody(UploadRepository.Upload u) {
        if (u.state().equals("DELETE_PENDING") || u.state().equals("CLEANED")) {
            return new Status(u.id(), u.resumeId(), u.operationId(), "removed", u.state(), null, null, null);
        }
        return new Status(u.id(), u.resumeId(), u.operationId(), u.name(), u.state(), u.body(), u.error(), u.versionId());
    }
    record Status(UUID uploadId, UUID resumeId, UUID operationId, String name, String state, String body, String errorCode, UUID versionId) { }
}

@Configuration
@Profile("local")
@EnableScheduling
class UploadScheduling { }
