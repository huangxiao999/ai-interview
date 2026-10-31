package dev.interviewcoach.material;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("local")
final class UploadService {
    private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);
    private final UploadRepository uploads;
    private final ResumeRepository roots;
    private final ResumeService resumes;
    private final AccountWriteGuard guard;
    private final PrivateFiles files;
    private final DocumentParser parser;
    private final TransactionTemplate tx;
    private final Semaphore permits = new Semaphore(2);
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(2);
    private volatile boolean ready;
    private final java.util.Set<UUID> publishing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    UploadService(UploadRepository uploads, ResumeRepository roots, ResumeService resumes,
            AccountWriteGuard guard, PrivateFiles files, DocumentParser parser, PlatformTransactionManager manager) {
        this.uploads = uploads; this.roots = roots; this.resumes = resumes;
        this.guard = guard; this.files = files; this.parser = parser; this.tx = new TransactionTemplate(manager);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recover() { uploads.recover(); ready = true; }

    UploadRepository.Upload upload(Actor actor, UUID expected, UUID operation, UUID root,
            String name, byte[] content) throws IOException {
        if (content.length == 0 || content.length > 10_000_000 || name == null || name.length() > 128
                || !name.matches("[^\\\\/:\u0000-\u001f]+\\.(?i:pdf|docx)")) {
            throw new InvalidMaterial("仅支持不超过 10,000,000 字节的 PDF/DOCX");
        }
        String type = name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? "PDF" : "DOCX";
        boolean signature = type.equals("PDF") ? content.length >= 5
                && new String(content, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")
                : content.length >= 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4;
        if (!signature) throw new InvalidMaterial("文件实际类型与扩展名不符");
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        boolean[] created = {false};
        UUID candidate = UUID.randomUUID();
        UploadRepository.Upload item;
        try { item = tx.execute(status -> {
            writeGuard(actor, expected);
            if (root != null) active(actor, root);
            var old = uploads.byOperation(actor.accountId(), operation);
            if (old.isPresent()) {
                var u = old.get();
                if (!u.sha().equals(digest) || !uploads.hasOriginalTarget(u.id(), root) || !u.name().equals(name)) {
                    throw new MaterialConflict();
                }
                return u;
            }
            var u = new UploadRepository.Upload(candidate, actor.accountId(), operation, root,
                    name, type, content.length, digest, "RECEIVING", 0, null, null, null, null, null);
            uploads.insert(u); created[0] = true; publishing.add(u.id()); return u;
        }); } catch (RuntimeException ex) { publishing.remove(candidate); throw ex; }
        if (!created[0]) return visible(actor, item.id());
        try {
            files.stage(item.id(), content);
            tx.executeWithoutResult(s -> {
                writeGuard(actor, expected);
                if (root != null) active(actor, root);
                uploads.queue(item.id());
            });
        } catch (IOException | org.springframework.security.core.AuthenticationException | MaterialUnavailable | MaterialAccountChanged ex) {
            // 确定文件失败才登记清理；数据库提交未知时绝不猜测删除已被接受的原文件。
            tx.executeWithoutResult(s -> uploads.cancel(item.id()));
            throw ex;
        } finally { publishing.remove(item.id()); }
        return visible(actor, item.id());
    }

    UploadRepository.Upload byOperation(Actor actor, UUID operation) {
        trial(actor);
        var u = uploads.byOperation(actor.accountId(), operation).orElseThrow(MaterialUnavailable::new);
        // 无根的清理回执只含最小操作状态，允许原操作者结束结果未知流程。
        if (u.state().equals("CLEANED") || u.state().equals("DELETE_PENDING")) {
            // 原操作可能在另一窗口确认后整根删除；仅返回已脱敏的清理回执，不恢复详情。
            return u;
        }
        return visible(actor, u.id());
    }

    java.util.List<UploadRepository.Upload> drafts(Actor actor) {
        trial(actor);
        return uploads.drafts(actor.accountId());
    }

    UploadRepository.Upload forVersion(Actor actor, UUID versionId) {
        return visible(actor, uploads.forVersion(actor.accountId(), versionId)
                .orElseThrow(MaterialUnavailable::new).id());
    }

    UploadRepository.Upload visible(Actor actor, UUID id) {
        trial(actor);
        var u = uploads.find(id).orElseThrow(MaterialUnavailable::new);
        if (!u.ownerId().equals(actor.accountId()) || u.state().equals("DELETE_PENDING") || u.state().equals("CLEANED")) {
            throw new MaterialUnavailable();
        }
        if (u.resumeId() != null) resumes.detail(actor, u.resumeId(), null);
        return u;
    }

    ResumeRepository.Detail confirm(Actor actor, UUID expected, UUID id, UUID operation, String rawBody) {
        var normalized = ResumeText.normalize(rawBody);
        var observed = visible(actor, id);
        if (observed.state().equals("READY")) {
            try { physical(observed); }
            catch (IOException ex) { throw new UploadFileUnavailable(); }
        }
        return tx.execute(status -> {
            writeGuard(actor, expected);
            var u = visible(actor, id);
            if (u.resumeId() != null) active(actor, u.resumeId());
            if (u.state().equals("CONFIRMED")) {
                if (!operation.equals(u.confirmOperationId())) throw new MaterialConflict();
                var detail = resumes.detail(actor, u.resumeId(), u.versionId());
                if (!detail.body().equals(normalized.body())) throw new MaterialConflict();
                return detail;
            }
            if (!u.state().equals("READY")) throw new UploadNotReady();
            // 旧正文版本的原文件关联也不可后补覆盖；只有本确认新接受的版本能绑定。
            if ((u.resumeId() == null && roots.findByCreateOperation(actor.accountId(), operation).isPresent())
                    || (u.resumeId() != null && roots.detailByOperation(actor.accountId(), u.resumeId(), operation).isPresent())) {
                throw new MaterialConflict();
            }
            var detail = u.resumeId() == null ? resumes.create(actor, expected, operation, rawBody)
                    : resumes.addVersion(actor, expected, u.resumeId(), operation, rawBody);
            // 同一数据源 REQUIRED 短事务：正文版本与原文件绑定必须共同提交。
            uploads.confirm(id, detail, operation);
            return detail;
        });
    }

    void cancel(Actor actor, UUID expected, UUID id) {
        tx.executeWithoutResult(s -> {
            writeGuard(actor, expected);
            var u = uploads.find(id).orElseThrow(MaterialUnavailable::new);
            if (!u.ownerId().equals(actor.accountId())) throw new MaterialUnavailable();
            if (u.resumeId() != null) active(actor, u.resumeId());
            if (u.state().equals("CLEANED") || u.state().equals("DELETE_PENDING")) return;
            if (u.state().equals("CONFIRMED")) throw new UploadNotReady();
            uploads.cancel(id);
        });
    }

    byte[] original(Actor actor, UUID id) throws IOException {
        var u = visible(actor, id);
        if (!u.state().equals("READY") && !u.state().equals("CONFIRMED")) throw new UploadNotReady();
        byte[] result = physical(u);
        // 读取期间删除已提交则丢弃已读字节；绝不把路径交给静态资源处理器。
        visible(actor, id);
        return result;
    }

    @Scheduled(fixedDelay = 1000)
    void work() {
        if (!ready) return;
        reconcilePublication();
        uploads.expireParsers();
        for (var item : uploads.queued()) {
            if (!permits.tryAcquire()) break;
            try {
                if (!uploads.claim(item.id())) { permits.release(); continue; }
            } catch (RuntimeException ex) { permits.release(); throw ex; }
            workers.submit(() -> {
                try {
                    var result = parser.parse(item.id(), item.type());
                    acceptResult(item, result);
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    try { acceptResult(item, new DocumentParser.Result("PARSE_FAILED", null)); }
                    catch (RuntimeException failure) { LOG.warn("Parser result pending reconciliation: {}", item.id()); }
                } finally { permits.release(); }
            });
        }
        clean();
    }

    private byte[] physical(UploadRepository.Upload u) throws IOException {
        byte[] bytes = files.read(u.id());
        try {
            if (bytes.length != u.size() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(u.sha())) {
                throw new IOException("Private file integrity mismatch");
            }
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        return bytes;
    }

    private void reconcilePublication() {
        for (var u : uploads.receiving()) {
            if (publishing.contains(u.id())) continue;
            boolean valid;
            try { physical(u); valid = true; } catch (IOException ex) { valid = false; }
            boolean published = valid;
            tx.executeWithoutResult(s -> {
                guard.lockCurrentAccount(u.ownerId());
                var root = u.resumeId() == null ? null : roots.lockRoot(u.resumeId()).orElse(null);
                var current = uploads.find(u.id()).orElseThrow(MaterialUnavailable::new);
                if (!current.state().equals("RECEIVING")) return;
                if (published && (u.resumeId() == null || root != null && root.state().equals("ACTIVE"))) uploads.queue(u.id());
                else uploads.cancel(u.id());
            });
        }
    }

    private void acceptResult(UploadRepository.Upload item, DocumentParser.Result result) {
        tx.executeWithoutResult(s -> {
            // 后台已接受工作仍按账号 → 删除根顺序裁决；不借此代替用户写入的凭证复核。
            var current = guard.lockCurrentAccount(item.ownerId());
            if (item.resumeId() != null) roots.lockRoot(item.resumeId());
            if (!current.hasAuthority(AccountAuthority.TRIAL_USER)) { uploads.cancel(item.id()); return; }
            uploads.result(item, result);
        });
    }

    void clean() {
        for (var u : uploads.cleanup()) {
            if (publishing.contains(u.id())) continue;
            try {
                files.remove(u.id());
                tx.executeWithoutResult(s -> uploads.cleaned(u.id()));
            } catch (IOException | org.springframework.dao.DataAccessException ex) {
                LOG.warn("Private cleanup pending: {}", u.id());
            }
        }
    }

    private void writeGuard(Actor actor, UUID expected) {
        trial(guard.requireCurrent(actor));
        if (!actor.accountId().equals(expected)) throw new MaterialAccountChanged();
    }
    private void trial(Actor actor) {
        if (!actor.hasAuthority(AccountAuthority.TRIAL_USER)) throw new AccessDeniedException("Trial user required");
    }
    private void active(Actor actor, UUID id) {
        var root = roots.lockRoot(id).orElseThrow(MaterialUnavailable::new);
        if (!root.ownerId().equals(actor.accountId()) || !root.state().equals("ACTIVE")) throw new MaterialUnavailable();
    }
    @PreDestroy
    void stop() { ready = false; workers.shutdownNow(); }
}

final class UploadNotReady extends RuntimeException { }
final class UploadFileUnavailable extends RuntimeException { }
