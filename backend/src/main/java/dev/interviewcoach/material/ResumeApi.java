package dev.interviewcoach.material;

import dev.interviewcoach.identity.CurrentActor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/materials/resumes")
final class ResumeApi {

    private final ResumeService service;
    private final CurrentActor currentActor;

    ResumeApi(ResumeService service, CurrentActor currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    @PostMapping
    ResponseEntity<ResumeDetail> create(Authentication authentication,
            @RequestHeader("X-Expected-Account-Id") UUID expectedAccountId,
            @RequestHeader("X-Operation-Id") UUID operationId, @RequestBody BodyInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.create(currentActor.require(authentication), expectedAccountId, operationId,
                        input == null ? null : input.body())));
    }

    @PostMapping("/{resumeId}/versions")
    ResponseEntity<ResumeDetail> addVersion(Authentication authentication,
            @PathVariable UUID resumeId, @RequestHeader("X-Expected-Account-Id") UUID expectedAccountId,
            @RequestHeader("X-Operation-Id") UUID operationId,
            @RequestBody BodyInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.addVersion(currentActor.require(authentication), expectedAccountId, resumeId,
                        operationId, input == null ? null : input.body())));
    }

    @GetMapping
    ResponseEntity<List<ResumeSummary>> list(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(currentActor.require(authentication)).stream()
                        .map(ResumeSummary::from).toList());
    }

    @GetMapping("/operations/{operationId}")
    ResponseEntity<ResumeDetail> createdByOperation(Authentication authentication,
            @PathVariable UUID operationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.createdByOperation(
                        currentActor.require(authentication), operationId)));
    }

    @GetMapping("/{resumeId}")
    ResponseEntity<ResumeDetail> detail(Authentication authentication, @PathVariable UUID resumeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.detail(currentActor.require(authentication), resumeId, null)));
    }

    @GetMapping("/{resumeId}/versions/{versionId}")
    ResponseEntity<ResumeDetail> version(Authentication authentication, @PathVariable UUID resumeId,
            @PathVariable UUID versionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.detail(currentActor.require(authentication), resumeId, versionId)));
    }

    @GetMapping("/{resumeId}/versions")
    ResponseEntity<List<ResumeSummary>> versions(Authentication authentication, @PathVariable UUID resumeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.versions(currentActor.require(authentication), resumeId, false).stream()
                        .map(ResumeSummary::from).toList());
    }

    @PostMapping("/{resumeId}/jd-versions")
    ResponseEntity<ResumeDetail> addJdVersion(Authentication authentication, @PathVariable UUID resumeId,
            @RequestHeader("X-Expected-Account-Id") UUID expectedAccountId,
            @RequestHeader("X-Operation-Id") UUID operationId, @RequestBody BodyInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.addJdVersion(currentActor.require(authentication),
                        expectedAccountId, resumeId, operationId, input == null ? null : input.body())));
    }

    @GetMapping("/{resumeId}/jd-versions")
    ResponseEntity<List<ResumeSummary>> jdVersions(Authentication authentication, @PathVariable UUID resumeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.versions(currentActor.require(authentication), resumeId, true).stream()
                        .map(ResumeSummary::from).toList());
    }

    @GetMapping("/{resumeId}/jd-versions/{versionId}")
    ResponseEntity<ResumeDetail> jdVersion(Authentication authentication, @PathVariable UUID resumeId,
            @PathVariable UUID versionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.jdDetail(currentActor.require(authentication), resumeId, versionId, null)));
    }

    @GetMapping("/{resumeId}/jd-operations/{operationId}")
    ResponseEntity<ResumeDetail> jdByOperation(Authentication authentication, @PathVariable UUID resumeId,
            @PathVariable UUID operationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.jdDetail(currentActor.require(authentication), resumeId, null, operationId)));
    }

    @GetMapping("/{resumeId}/operations/{operationId}")
    ResponseEntity<ResumeDetail> versionByOperation(Authentication authentication,
            @PathVariable UUID resumeId, @PathVariable UUID operationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResumeDetail.from(service.versionByOperation(
                        currentActor.require(authentication), resumeId, operationId)));
    }

    @GetMapping("/{resumeId}/deletion-impact")
    ResponseEntity<DeletionImpact> deletionImpact(Authentication authentication, @PathVariable UUID resumeId) {
        var impact = service.deletionImpact(currentActor.require(authentication), resumeId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new DeletionImpact(impact.versionCount(), impact.jdVersionCount(), impact.fileCount()));
    }

    @DeleteMapping("/{resumeId}")
    ResponseEntity<DeletionStatus> delete(Authentication authentication, @PathVariable UUID resumeId,
            @RequestHeader("X-Expected-Account-Id") UUID expectedAccountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(DeletionStatus.from(service.delete(currentActor.require(authentication), expectedAccountId, resumeId)));
    }

    @GetMapping("/{resumeId}/deletion")
    ResponseEntity<DeletionStatus> deletion(Authentication authentication, @PathVariable UUID resumeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(DeletionStatus.from(service.deletion(currentActor.require(authentication), resumeId)));
    }

    record BodyInput(String body) {
    }

    record DeletionImpact(int versionCount, int jdVersionCount, int fileCount) {
    }

    record ResumeSummary(UUID resumeId, UUID versionId, int versionNo, int codepointCount,
            OffsetDateTime confirmedAt) {
        static ResumeSummary from(ResumeRepository.Summary source) {
            return new ResumeSummary(source.resumeId(), source.versionId(), source.versionNo(),
                    source.codepointCount(), source.confirmedAt());
        }
    }

    record ResumeDetail(UUID resumeId, UUID versionId, int versionNo, String body,
            int codepointCount, OffsetDateTime confirmedAt) {
        static ResumeDetail from(ResumeRepository.Detail source) {
            return new ResumeDetail(source.resumeId(), source.versionId(), source.versionNo(),
                    source.body(), source.codepointCount(), source.confirmedAt());
        }
    }

    record DeletionStatus(UUID requestId, OffsetDateTime requestedAt, OffsetDateTime deadlineAt,
            String state, OffsetDateTime cleanedAt) {
        static DeletionStatus from(ResumeRepository.Deletion source) {
            return new DeletionStatus(source.requestId(), source.requestedAt(), source.deadlineAt(),
                    source.state(), source.cleanedAt());
        }
    }
}
