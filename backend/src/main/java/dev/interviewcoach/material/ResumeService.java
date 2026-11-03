package dev.interviewcoach.material;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!bootstrap")
final class ResumeService {

    private final ResumeRepository repository;
    private final AccountWriteGuard accountWriteGuard;
    private final TransactionTemplate transaction;
    private final List<MaterialDeletionParticipant> deletionParticipants;

    ResumeService(ResumeRepository repository, AccountWriteGuard accountWriteGuard,
            PlatformTransactionManager manager, List<MaterialDeletionParticipant> deletionParticipants) {
        this.repository = repository;
        this.accountWriteGuard = accountWriteGuard;
        this.transaction = new TransactionTemplate(manager);
        this.deletionParticipants = List.copyOf(deletionParticipants);
    }

    ResumeRepository.Detail create(Actor actor, UUID expectedAccountId, UUID operationId, String rawBody) {
        requireTrial(actor);
        ResumeText.Normalized body = ResumeText.normalize(rawBody);
        return transaction.execute(status -> {
            requireCurrentTrial(actor, expectedAccountId);
            var previous = repository.findByCreateOperation(actor.accountId(), operationId);
            if (previous.isPresent()) {
                var detail = repository.detailByOperation(actor.accountId(), previous.get().id(), operationId)
                        .orElseThrow(MaterialUnavailable::new);
                assertSameOperationBody(detail, body);
                return detail;
            }
            UUID resumeId = UUID.randomUUID();
            repository.insertRoot(resumeId, actor.accountId(), operationId);
            repository.insertVersion(UUID.randomUUID(), resumeId, 1, operationId, body);
            return repository.detail(actor.accountId(), resumeId, null)
                    .orElseThrow(IllegalStateException::new);
        });
    }

    ResumeRepository.Detail addVersion(Actor actor, UUID expectedAccountId, UUID resumeId,
            UUID operationId, String rawBody) {
        requireTrial(actor);
        ResumeText.Normalized body = ResumeText.normalize(rawBody);
        return transaction.execute(status -> {
            requireCurrentTrial(actor, expectedAccountId);
            requireActiveRoot(actor, resumeId);
            var previous = repository.detailByOperation(actor.accountId(), resumeId, operationId);
            if (previous.isPresent()) {
                assertSameOperationBody(previous.get(), body);
                return previous.get();
            }
            repository.insertVersion(UUID.randomUUID(), resumeId, repository.nextVersion(resumeId),
                    operationId, body);
            return repository.detailByOperation(actor.accountId(), resumeId, operationId)
                    .orElseThrow(IllegalStateException::new);
        });
    }

    List<ResumeRepository.Summary> list(Actor actor) {
        requireTrial(actor);
        return repository.list(actor.accountId());
    }

    ResumeRepository.Detail detail(Actor actor, UUID resumeId, UUID versionId) {
        requireTrial(actor);
        return repository.detail(actor.accountId(), resumeId, versionId)
                .orElseThrow(MaterialUnavailable::new);
    }

    ResumeRepository.Detail createdByOperation(Actor actor, UUID operationId) {
        requireTrial(actor);
        var root = repository.findByCreateOperation(actor.accountId(), operationId)
                .orElseThrow(MaterialUnavailable::new);
        return repository.detailByOperation(actor.accountId(), root.id(), operationId)
                .orElseThrow(MaterialUnavailable::new);
    }

    ResumeRepository.Detail versionByOperation(Actor actor, UUID resumeId, UUID operationId) {
        requireTrial(actor);
        return repository.detailByOperation(actor.accountId(), resumeId, operationId)
                .orElseThrow(MaterialUnavailable::new);
    }

    ResumeRepository.Detail addJdVersion(Actor actor, UUID expectedAccountId, UUID resumeId,
            UUID operationId, String rawBody) {
        requireTrial(actor);
        var body = ResumeText.normalizeJd(rawBody);
        return transaction.execute(status -> {
            // 固定锁顺序：M-01 当前凭证与权限 → M-02 删除根 → 版本接受。
            requireCurrentTrial(actor, expectedAccountId);
            requireActiveRoot(actor, resumeId);
            var previous = repository.jdDetail(actor.accountId(), resumeId, null, operationId);
            if (previous.isPresent()) {
                assertSameOperationBody(previous.get(), body);
                return previous.get();
            }
            repository.insertJdVersion(UUID.randomUUID(), resumeId, operationId, body);
            return repository.jdDetail(actor.accountId(), resumeId, null, operationId)
                    .orElseThrow(IllegalStateException::new);
        });
    }

    List<ResumeRepository.Summary> versions(Actor actor, UUID resumeId, boolean jdVersion) {
        requireTrial(actor);
        return transaction.execute(status -> {
            requireActiveRoot(actor, resumeId);
            return repository.versions(actor.accountId(), resumeId, jdVersion);
        });
    }

    ResumeRepository.Detail jdDetail(Actor actor, UUID resumeId, UUID versionId, UUID operationId) {
        requireTrial(actor);
        return repository.jdDetail(actor.accountId(), resumeId, versionId, operationId)
                .orElseThrow(MaterialUnavailable::new);
    }

    DeletionImpact deletionImpact(Actor actor, UUID resumeId) {
        requireTrial(actor);
        return transaction.execute(status -> {
            requireActiveRoot(actor, resumeId);
            var additional=deletionParticipants.stream().map(p->p.impact(actor.accountId(),resumeId)).toList();
            return new DeletionImpact(repository.countVersions(resumeId), repository.countJdVersions(resumeId),
                    repository.countFiles(resumeId), additional.stream().mapToInt(MaterialDeletionParticipant.Impact::plans).sum(),
                    additional.stream().mapToInt(MaterialDeletionParticipant.Impact::operations).sum(),
                    additional.stream().mapToInt(MaterialDeletionParticipant.Impact::candidates).sum(),
                    additional.stream().mapToInt(MaterialDeletionParticipant.Impact::revisions).sum());
        });
    }

    record DeletionImpact(int versionCount, int jdVersionCount, int fileCount, int planCount, int planOperationCount, int planCandidateCount, int planRevisionCount) {
    }

    ResumeRepository.Deletion delete(Actor actor, UUID expectedAccountId, UUID resumeId) {
        requireTrial(actor);
        return transaction.execute(status -> {
            requireCurrentTrial(actor, expectedAccountId);
            var root = repository.lockRoot(resumeId).orElseThrow(MaterialUnavailable::new);
            if (!root.ownerId().equals(actor.accountId())) {
                throw new MaterialUnavailable();
            }
            if (root.state().equals("DELETED")) {
                return repository.deletion(actor.accountId(), resumeId)
                        .orElseThrow(IllegalStateException::new);
            }
            UUID requestId = UUID.randomUUID();
            var now = repository.databaseTime();
            repository.recordDeletion(requestId, resumeId, now, repository.versionIds(resumeId));
            // 同事务扩展实际范围；参与者必须先登记PENDING，随后统一核全部清理项，不能只看文件。
            for(var participant:deletionParticipants) participant.invalidateAndRegister(actor.accountId(),resumeId,requestId);
            repository.refreshDeletionState(requestId);
            return repository.deletion(actor.accountId(), resumeId)
                    .orElseThrow(IllegalStateException::new);
        });
    }

    ResumeRepository.Deletion deletion(Actor actor, UUID resumeId) {
        requireTrial(actor);
        return repository.deletion(actor.accountId(), resumeId)
                .orElseThrow(MaterialUnavailable::new);
    }

    private void requireActiveRoot(Actor actor, UUID resumeId) {
        var root = repository.lockRoot(resumeId).orElseThrow(MaterialUnavailable::new);
        if (!root.ownerId().equals(actor.accountId()) || !root.state().equals("ACTIVE")) {
            throw new MaterialUnavailable();
        }
    }

    private void requireTrial(Actor actor) {
        if (!actor.hasAuthority(AccountAuthority.TRIAL_USER)) {
            throw new AccessDeniedException("Trial-user authority is required.");
        }
    }

    private void requireCurrentTrial(Actor actor, UUID expectedAccountId) {
        requireTrial(accountWriteGuard.requireCurrent(actor));
        if (!actor.accountId().equals(expectedAccountId)) {
            throw new MaterialAccountChanged();
        }
    }

    private void assertSameOperationBody(ResumeRepository.Detail previous, ResumeText.Normalized body) {
        if (!previous.body().equals(body.body())) {
            throw new MaterialConflict();
        }
    }
}

final class MaterialConflict extends RuntimeException {
}

final class MaterialAccountChanged extends RuntimeException {
}
