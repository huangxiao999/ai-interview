package dev.interviewcoach.knowledge;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!bootstrap")
public final class KnowledgeService {
    private final KnowledgeRepository repository;
    private final AccountWriteGuard accounts;
    private final TransactionTemplate transaction;

    KnowledgeService(KnowledgeRepository repository, AccountWriteGuard accounts, PlatformTransactionManager manager) {
        this.repository = repository;
        this.accounts = accounts;
        transaction = new TransactionTemplate(manager);
    }

    VersionDetail submit(Actor actor, UUID expected, UUID operation, UUID documentId, KnowledgeInput raw) {
        requireMaintainer(actor);
        if (raw == null) throw new InvalidKnowledge("请填写知识资料");
        KnowledgeInput input = raw.normalized();
        return transaction.execute(status -> {
            current(actor, expected);
            var previous = repository.findOperation(actor.accountId(), operation);
            if (previous.isPresent()) {
                var old = previous.get();
                if (!old.kind().equals("SUBMIT") || !old.version().input().equals(input)
                        || !java.util.Objects.equals(documentId, old.version().inputDocumentId())) throw new KnowledgeConflict();
                return detail(old.version());
            }
            UUID root = documentId == null ? UUID.randomUUID() : documentId;
            if (repository.reservedIndexOperation(actor.accountId(), operation)) throw new KnowledgeConflict();
            // 固定顺序：当前账号行锁 → 公共知识根锁。跨维护者审核也由同一根串行裁决。
            if (documentId == null) repository.createRoot(root);
            repository.lockRoot(root);
            UUID version = UUID.randomUUID();
            repository.insert(version, root, documentId, actor.accountId(), operation, input);
            return detail(repository.findVersion(root, version).orElseThrow());
        });
    }

    VersionDetail review(Actor actor, UUID expected, UUID operation, UUID root, UUID version,
            String digest, String decision, String rawReason) {
        requireMaintainer(actor);
        if (!("APPROVE".equals(decision) || "REJECT".equals(decision))) throw new InvalidKnowledge("请选择审核决定");
        String reason = KnowledgeInput.text(rawReason, 2_000);
        return transaction.execute(status -> {
            current(actor, expected);
            repository.lockRoot(root);
            var original = repository.findVersion(root, version).orElseThrow(KnowledgeUnavailable::new);
            if (!original.digest().equals(digest)) throw new KnowledgeConflict();
            var previous = repository.findOperation(actor.accountId(), operation);
            if (previous.isPresent()) {
                var old = previous.get();
                if (!old.kind().equals("REVIEW") || !old.version().id().equals(version)
                        || !decision.equals(old.version().decision()) || !reason.equals(old.version().reason())) {
                    throw new KnowledgeConflict();
                }
                return detail(old.version());
            }
            if (original.decision() != null) throw new KnowledgeStateConflict();
            if (repository.reservedIndexOperation(actor.accountId(), operation)) throw new KnowledgeConflict();
            repository.review(version, actor.accountId(), operation, decision, reason);
            return detail(repository.findVersion(root, version).orElseThrow());
        });
    }

    List<VersionDetail> list(Actor actor) {
        requireMaintainer(actor);
        return repository.list().stream().map(KnowledgeService::detail).toList();
    }

    VersionDetail read(Actor actor, UUID root, UUID version) {
        requireMaintainer(actor);
        return detail(repository.findVersion(root, version).orElseThrow(KnowledgeUnavailable::new));
    }

    OperationResult operation(Actor actor, UUID operation) {
        requireMaintainer(actor);
        var fact = repository.findOperation(actor.accountId(), operation).orElseThrow(KnowledgeUnavailable::new);
        return new OperationResult(operation, fact.kind(), detail(fact.version()));
    }

    /** 精确版本的生产来源门禁；不检索、不自动判断片段支持关系，不返回未审核正文。 */
    public Optional<ApprovedSource> findApproved(UUID versionId) {
        return repository.findApproved(versionId).map(v -> new ApprovedSource(v.id(), v.input().title(),
                v.input().body(), v.input().sourceUrl(), v.input().sourceAnchor(), v.input().applicability(),
                v.input().usageNote(), v.digest(), v.reviewedAt()));
    }

    private void current(Actor observed, UUID expected) {
        requireMaintainer(accounts.requireCurrent(observed));
        if (!observed.accountId().equals(expected)) throw new KnowledgeAccountChanged();
    }

    private static void requireMaintainer(Actor actor) {
        if (!actor.hasAuthority(AccountAuthority.KNOWLEDGE_MAINTAINER)) {
            throw new AccessDeniedException("Knowledge-maintainer authority is required.");
        }
    }

    private static VersionDetail detail(KnowledgeRepository.Version v) {
        var i = v.input();
        String state = v.decision() == null ? "PENDING_REVIEW" : v.decision().equals("APPROVE") ? "APPROVED" : "REJECTED";
        return new VersionDetail(v.documentId(), v.id(), v.versionNo(), i.title(), i.body(), i.sourceUrl(),
                i.sourceAnchor(), i.applicability(), i.usageNote(), i.verificationNote(), i.verifiedOn(),
                v.digest(), state, v.submittedAt(), v.reason(), v.reviewerId(), v.reviewedAt());
    }

    record VersionDetail(UUID documentId, UUID versionId, int versionNo, String title, String body,
            String sourceUrl, String sourceAnchor, String applicability, String usageNote, String verificationNote,
            LocalDate verifiedOn, String digest, String state, OffsetDateTime submittedAt,
            String reviewReason, UUID reviewerId, OffsetDateTime reviewedAt) { }
    record OperationResult(UUID operationId, String kind, VersionDetail version) { }
    public record ApprovedSource(UUID versionId, String title, String body, String sourceUrl, String sourceAnchor,
            String applicability, String usageNote, String digest, OffsetDateTime reviewedAt) { }
}
