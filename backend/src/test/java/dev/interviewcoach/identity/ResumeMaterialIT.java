package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.net.HttpURLConnection;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
class ResumeMaterialIT {

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI base;
    private static Process vite;
    private static Account a;
    private static Account b;
    private static Account maintainer;

    @BeforeAll
    static void isolatedDatabase() {
        String database = System.getenv("P2_DT01_TEST_DATABASE");
        if (database == null || !database.matches("p2dt01_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("A new isolated P2-DT-01 database is required.");
        }
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        assertThat(admin.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        assertThat(admin.queryForObject("SHOW server_version_num", Integer.class))
                .isBetween(160000, 169999);
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'",
                Integer.class)).isZero();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
                .defaultSchema("app").schemas("app").createSchemas(true)
                .locations("classpath:db/migration").target("3").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=app", username, password));
        Account legacy = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        UUID legacyRoot = UUID.randomUUID();
        UUID legacyVersion = UUID.randomUUID();
        jdbc.update("INSERT INTO material_resume(id,owner_account_id,create_operation_id) VALUES(?,?,?)",
                legacyRoot, legacy.id(), UUID.randomUUID());
        jdbc.update("INSERT INTO material_resume_version(id,resume_id,version_no,operation_id,body,codepoint_count) "
                + "VALUES(?,?,1,?,?,100)", legacyVersion, legacyRoot, UUID.randomUUID(), "L".repeat(100));
        flyway = Flyway.configure().dataSource(url, username, password).defaultSchema("app")
                .schemas("app").locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(jdbc.queryForObject("SELECT body FROM material_resume_version WHERE id = ?",
                String.class, legacyVersion)).isEqualTo("L".repeat(100));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE success AND version = '3'", Integer.class)).isEqualTo(1);
        a = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        b = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        maintainer = insertAccount(Set.of(AccountAuthority.KNOWLEDGE_MAINTAINER));
        context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .sources(UploadCommitFaults.class)
                .profiles("local").run("--server.port=0", "--material.private-root=" + privateRoot());
        base = URI.create("http://127.0.0.1:"
                + context.getEnvironment().getRequiredProperty("local.server.port"));
        assertThat(context.getBean(jakarta.servlet.MultipartConfigElement.class).getFileSizeThreshold()).isEqualTo(10_000_000);
    }

    private static Account insertAccount(Set<AccountAuthority> authorities) {
        UUID id = UUID.randomUUID();
        String login = "p2." + id;
        String password = "synthetic-" + UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(id, login, new PasswordHashes().encode(password), authorities);
        return new Account(id, login, password);
    }

    private static Path privateRoot() {
        return Path.of("../.cache", System.getenv("P2_DT01_TEST_DATABASE"), "private").toAbsolutePath().normalize();
    }

    @Test
    void zzRestartReconcilesHalfPublicationAndRetriesPhysicalCleanup() throws Exception {
        context.close();
        UUID staged = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        UUID interrupted = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        for (UUID id : java.util.List.of(staged, published, interrupted, blocked)) {
            jdbc.update("INSERT INTO material_upload(id,owner_account_id,operation_id,original_name,file_type,byte_size,sha256,state,deadline_at) "
                    + "VALUES (?,?,?,'synthetic.pdf','PDF',10,?, ?, clock_timestamp()-interval '1 minute')",
                    id, a.id(), UUID.randomUUID(), "a".repeat(64), id.equals(interrupted) ? "PARSING" : "RECEIVING");
        }
        java.nio.file.Files.writeString(privateRoot().resolve(staged + ".part"), "synthetic");
        java.nio.file.Files.writeString(privateRoot().resolve(published + ".bin"), "synthetic");
        java.nio.file.Files.writeString(privateRoot().resolve(interrupted + ".bin"), "synthetic");
        Path blocking = java.nio.file.Files.createDirectory(privateRoot().resolve(blocked + ".bin"));
        java.nio.file.Files.writeString(blocking.resolve("sentinel"), "synthetic");
        context = new SpringApplicationBuilder(InterviewCoachApplication.class).sources(UploadCommitFaults.class)
                .profiles("local").run("--server.port=0", "--material.private-root=" + privateRoot());
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
        for (int n = 0; n < 100; n++) {
            if (jdbc.queryForObject("SELECT count(*) FROM material_upload WHERE id IN (?,?) AND state='CLEANED'",
                    Integer.class, staged, published) == 2) break;
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_upload WHERE id IN (?,?) AND state='CLEANED'",
                Integer.class, staged, published)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM material_upload WHERE id=?", String.class, interrupted)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT parsed_body FROM material_upload WHERE id=?", String.class, interrupted)).isNull();
        assertThat(jdbc.queryForObject("SELECT state FROM material_upload WHERE id=?", String.class, blocked)).isEqualTo("DELETE_PENDING");
        assertThat(java.nio.file.Files.exists(blocking.resolve("sentinel"))).isTrue();
        java.nio.file.Files.delete(blocking.resolve("sentinel"));
        java.nio.file.Files.delete(blocking);
        for (int n = 0; n < 100 && !"CLEANED".equals(jdbc.queryForObject("SELECT state FROM material_upload WHERE id=?", String.class, blocked)); n++) Thread.sleep(100);
        assertThat(jdbc.queryForObject("SELECT state FROM material_upload WHERE id=?", String.class, blocked)).isEqualTo("CLEANED");
        for (UUID id : java.util.List.of(staged, published, interrupted, blocked)) {
            assertThat(java.nio.file.Files.exists(privateRoot().resolve(id + ".bin"))).isFalse();
            assertThat(java.nio.file.Files.exists(privateRoot().resolve(id + ".part"))).isFalse();
        }
    }

    @Test
    void realCommitUnknownIsResolvedWithoutAnotherVersionOrFile() throws Exception {
        var user = new Client(a);
        String id = json(user.upload("unknown.docx", docx("Commit acknowledgement ".repeat(10)), UUID.randomUUID(), null)).get("uploadId").toString();
        awaitUpload(user, id);
        UUID operation = UUID.randomUUID();
        UploadCommitFaults.LOSE_NEXT_CONFIRM_COMMIT.set(true);
        try {
            var response = user.send("POST", "/api/materials/uploads/" + id + "/confirmation",
                    user.bodyJson("Committed once ".repeat(10)), user.token, operation);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json(user.get("/api/materials/uploads/" + id)).get("state")).isEqualTo("CONFIRMED");
            var retried = user.send("POST", "/api/materials/uploads/" + id + "/confirmation",
                    user.bodyJson("Committed once ".repeat(10)), user.token, operation);
            assertThat(retried.statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE operation_id=?", Integer.class, operation)).isEqualTo(1);
            assertThat(java.nio.file.Files.exists(privateRoot().resolve(id + ".bin"))).isTrue();
        } finally { UploadCommitFaults.LOSE_NEXT_CONFIRM_COMMIT.set(false); }
    }

    @Test
    void uploadedDocxRequiresConfirmationAndOriginalIsPrivate() throws Exception {
        var user = new Client(a);
        UUID op = UUID.randomUUID();
        byte[] file = docx("Synthetic Java resume ".repeat(12));
        var response = user.upload("resume.docx", file, op, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var upload = json(response);
        String id = upload.get("uploadId").toString();
        assertThat(user.get("/api/materials/uploads/operations/" + op).statusCode()).isEqualTo(200);
        assertThat(json(user.upload("resume.docx", file, op, null)).get("uploadId")).isEqualTo(id);
        assertThat(user.upload("other.docx", file, op, null).statusCode()).isEqualTo(409);
        var parsed = awaitUpload(user, id);
        assertThat(parsed.get("state")).isEqualTo("READY");
        assertThat(parsed.get("body").toString()).contains("Synthetic Java resume");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume WHERE create_operation_id=?", Integer.class, op)).isZero();
        assertThat(new Client().get("/api/materials/uploads/" + id + "/original").statusCode()).isEqualTo(401);
        assertThat(new Client(b).get("/api/materials/uploads/" + id).statusCode()).isEqualTo(404);
        assertThat(new Client(maintainer).get("/api/materials/uploads/" + id).statusCode()).isEqualTo(403);
        assertThat(new Client(b).get("/api/materials/uploads/" + id + "/original").statusCode()).isEqualTo(404);
        assertThat(new Client(maintainer).get("/api/materials/uploads/" + id + "/original").statusCode()).isEqualTo(403);
        assertThat(user.get("/api/materials/uploads/" + id + "/original").headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        UUID confirmation = UUID.randomUUID();
        String body = "Edited verified resume ".repeat(10);
        var saved = user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson(body), user.token, confirmation);
        assertThat(saved.statusCode()).isEqualTo(200);
        var detail = json(saved);
        UUID root = UUID.fromString(detail.get("resumeId").toString());
        assertThat(json(user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson(body), user.token, confirmation))
                .get("versionId")).isEqualTo(detail.get("versionId"));
        assertThat(user.get("/api/materials/uploads/versions/" + detail.get("versionId")).statusCode()).isEqualTo(200);
        assertThat(json(user.addVersion(root, "Second immutable version ".repeat(10), UUID.randomUUID())).get("versionNo")).isEqualTo(2);
        assertThat(json(user.upload("resume.docx", file, op, null)).get("uploadId")).isEqualTo(id);
        assertThat(json(user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson(body), user.token, confirmation)).get("versionId")).isEqualTo(detail.get("versionId"));
        assertThat(json(user.get("/api/materials/resumes/" + root + "/deletion-impact")).get("fileCount")).isEqualTo(1);
        assertThat(user.delete(root, true).statusCode()).isEqualTo(200);
        assertThat(user.get("/api/materials/uploads/" + id + "/original").statusCode()).isEqualTo(404);
        var tombstone = json(user.get("/api/materials/uploads/operations/" + op));
        assertThat(tombstone.get("name")).isEqualTo("removed");
        assertThat(tombstone.get("body")).isNull();
        assertThat(tombstone.get("versionId")).isNull();
        for (int n = 0; n < 450 && java.nio.file.Files.exists(privateRoot().resolve(id + ".bin")); n++) Thread.sleep(100);
        assertThat(java.nio.file.Files.exists(privateRoot().resolve(id + ".bin"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM material_delete_request WHERE resume_id=?", String.class, root)).isEqualTo("CLEANED");
    }

    @Test
    void uploadLimitsAndParserFailuresDoNotCreateVersions() throws Exception {
        var user = new Client(a);
        assertThat(user.upload("../resume.pdf", pdf(1, true, false), UUID.randomUUID(), null).statusCode()).isEqualTo(400);
        assertThat(user.upload("fake.pdf", docx("X".repeat(100)), UUID.randomUUID(), null).statusCode()).isEqualTo(400);
        byte[] max = java.util.Arrays.copyOf(pdf(1, true, false), 10_000_000);
        var exact = user.upload("max.pdf", max, UUID.randomUUID(), null);
        assertThat(exact.statusCode()).isEqualTo(200);
        assertThat(awaitUpload(user, json(exact).get("uploadId").toString()).get("state")).isEqualTo("READY");
        assertThat(user.upload("too-large.pdf", java.util.Arrays.copyOf(max, 10_000_001), UUID.randomUUID(), null).statusCode()).isEqualTo(413);
        for (byte[] bytes : java.util.List.of(pdf(21, true, false), pdf(1, false, false), pdf(1, true, true),
                "%PDF-broken".getBytes(StandardCharsets.UTF_8), unsafeDocx())) {
            String name = bytes[0] == 'P' ? "bad.docx" : "bad.pdf";
            var accepted = user.upload(name, bytes, UUID.randomUUID(), null);
            assertThat(accepted.statusCode()).isEqualTo(200);
            String id = json(accepted).get("uploadId").toString();
            assertThat(awaitUpload(user, id).get("state")).isEqualTo("FAILED");
            assertThat(user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson("X".repeat(100)), user.token, UUID.randomUUID()).statusCode()).isEqualTo(409);
        }
        var twenty = user.upload("twenty.pdf", pdf(20, true, false), UUID.randomUUID(), null);
        assertThat(awaitUpload(user, json(twenty).get("uploadId").toString()).get("state")).isEqualTo("READY");
    }

    @Test
    void uploadConfirmationRollbackAndDeletionBarrierAreAtomic() throws Exception {
        var user = new Client(a);
        UUID root = UUID.fromString(json(user.create("Q".repeat(100), UUID.randomUUID())).get("resumeId").toString());
        var accepted = user.upload("resume.docx", docx("Y".repeat(120)), UUID.randomUUID(), root);
        String id = json(accepted).get("uploadId").toString();
        awaitUpload(user, id);
        jdbc.execute("CREATE FUNCTION app.fail_upload_confirm() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.state='CONFIRMED' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_upload_confirm BEFORE UPDATE ON material_upload FOR EACH ROW EXECUTE FUNCTION app.fail_upload_confirm()");
        UUID operation = UUID.randomUUID();
        try {
            assertThat(user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson("Z".repeat(100)), user.token, operation).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE operation_id=?", Integer.class, operation)).isZero();
            assertThat(jdbc.queryForObject("SELECT state FROM material_upload WHERE id=?::uuid", String.class, id)).isEqualTo("READY");
        } finally { jdbc.execute("DROP TRIGGER fail_upload_confirm ON material_upload"); jdbc.execute("DROP FUNCTION app.fail_upload_confirm()"); }
        assertThat(user.delete(root, true).statusCode()).isEqualTo(200);
        assertThat(user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson("Z".repeat(100)), user.token, operation).statusCode()).isEqualTo(404);
        assertThat(user.upload("late.docx", docx("Y".repeat(120)), UUID.randomUUID(), root).statusCode()).isEqualTo(404);
    }

    private static Map<String,Object> awaitUpload(Client client, String id) throws Exception {
        for (int n = 0; n < 400; n++) {
            var status = json(client.get("/api/materials/uploads/" + id));
            if (java.util.Set.of("READY", "FAILED", "CONFIRMED").contains(status.get("state"))) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("Parser status did not settle");
    }

    private static byte[] docx(String body) throws Exception {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var out = new java.io.ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(body);
            doc.write(out); return out.toByteArray();
        }
    }
    private static byte[] unsafeDocx() throws Exception {
        try (var out = new java.io.ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("../escape")); zip.write(new byte[100]); zip.closeEntry();
            zip.finish(); return out.toByteArray();
        }
    }

    @Test
    void docxHasNoPdfPageCapAndExternalXmlOrZipBombCannotFetchResources() throws Exception {
        var user = new Client(a);
        byte[] multipage;
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var out = new java.io.ByteArrayOutputStream()) {
            for (int n = 0; n < 25; n++) {
                var run = doc.createParagraph().createRun(); run.setText("Synthetic page " + n); run.addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE);
            }
            doc.write(out); multipage = out.toByteArray();
        }
        String accepted = json(user.upload("multipage.docx", multipage, UUID.randomUUID(), null)).get("uploadId").toString();
        assertThat(awaitUpload(user, accepted).get("state")).isEqualTo("READY");
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external", exchange -> { hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        try {
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
            for (String attack : java.util.List.of("relation", "entity", "bomb")) {
                byte[] malicious;
                try (var out = new java.io.ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(out);
                        var source = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(docx("Synthetic safe text ".repeat(10))))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = source.getNextEntry()) != null) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                        byte[] data = source.readAllBytes();
                        if (entry.getName().equals("word/document.xml") && attack.equals("entity")) {
                            data = ("<?xml version=\"1.0\"?><!DOCTYPE doc [<!ENTITY xxe SYSTEM \"" + target + "\">]><doc>&xxe;</doc>").getBytes(StandardCharsets.UTF_8);
                        }
                        if (entry.getName().equals("word/document.xml") && attack.equals("bomb")) data = "X".repeat(500_000).getBytes(StandardCharsets.UTF_8);
                        zip.write(data); zip.closeEntry();
                    }
                    if (attack.equals("relation")) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("word/_rels/extra.rels"));
                        zip.write(("<r:Relationships xmlns:r=\"http://schemas.openxmlformats.org/package/2006/relationships\"><r:Relationship Id=\"rId999\" TargetMode=\"External\" Target=\"" + target + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\"/></r:Relationships>").getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                    zip.finish(); malicious = out.toByteArray();
                }
                String id = json(user.upload(attack + ".docx", malicious, UUID.randomUUID(), null)).get("uploadId").toString();
                assertThat(awaitUpload(user, id).get("state")).isEqualTo("FAILED");
            }
            assertThat(hits.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test
    void resetBeforeUploadOrConfirmationAccountLockRejectsTheOldSession() throws Exception {
        for (boolean confirming : new boolean[] {false, true}) {
            Account owner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
            var user = new Client(owner);
            UUID op = UUID.randomUUID();
            if (confirming) {
                String id = json(user.upload("revoked.docx", docx("Synthetic old session ".repeat(10)), op, null)).get("uploadId").toString();
                awaitUpload(user, id);
                assertLateWriteRevoked(owner, () -> user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson("V".repeat(100)), user.token, UUID.randomUUID()));
                assertThat(jdbc.queryForObject("SELECT version_id FROM material_upload WHERE id=?::uuid", UUID.class, id)).isNull();
            } else {
                byte[] bytes = docx("Synthetic late upload ".repeat(10));
                assertLateWriteRevoked(owner, () -> user.upload("revoked.docx", bytes, op, null));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM material_upload WHERE owner_account_id=?", Integer.class, owner.id())).isZero();
            }
        }
    }

    @Test
    void uploadConfirmationAndDeletionSerializeInBothOrders() throws Exception {
        for (boolean deleteFirst : new boolean[] {true, false}) {
            Account owner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
            var user = new Client(owner);
            UUID root = UUID.fromString(json(user.create("B".repeat(100), UUID.randomUUID())).get("resumeId").toString());
            String id = json(user.upload("race.docx", docx("Synthetic concurrent resume ".repeat(10)), UUID.randomUUID(), root)).get("uploadId").toString();
            awaitUpload(user, id);
            MaterialWrite confirm = () -> user.send("POST", "/api/materials/uploads/" + id + "/confirmation", user.bodyJson("V".repeat(100)), user.token, UUID.randomUUID());
            try (var locked = DriverManager.getConnection(System.getenv("DB_URL") + "?currentSchema=app", System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
                    var executor = Executors.newFixedThreadPool(2)) {
                locked.setAutoCommit(false);
                try (var s = locked.prepareStatement("SELECT id FROM identity_account WHERE id=? FOR UPDATE")) {
                    s.setObject(1, owner.id()); s.executeQuery().close();
                }
                var first = executor.submit(() -> deleteFirst ? user.delete(root, true).statusCode() : confirm.send().statusCode());
                awaitAccountLockWaiters(1);
                var second = executor.submit(() -> deleteFirst ? confirm.send().statusCode() : user.delete(root, true).statusCode());
                awaitAccountLockWaiters(2);
                locked.commit();
                assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(deleteFirst ? 404 : 200);
            }
            assertThat(user.get("/api/materials/uploads/" + id + "/original").statusCode()).isEqualTo(404);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id=?", Integer.class, root)).isZero();
            assertThat(jdbc.queryForObject("SELECT parsed_body FROM material_upload WHERE id=?::uuid", String.class, id)).isNull();
        }
    }
    private static byte[] pdf(int pages, boolean text, boolean encrypted) throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            for (int n = 0; n < pages; n++) {
                var page = new org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page);
                if (text) try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    stream.beginText(); stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(10, 700); stream.showText("Synthetic resume ".repeat(8)); stream.endText();
                }
                else try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    var image = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
                    stream.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, image), 10, 10, 100, 100);
                }
            }
            if (encrypted) doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(UUID.randomUUID().toString(), UUID.randomUUID().toString(), new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(out); return out.toByteArray();
        }
    }

    @AfterAll
    static void close() {
        if (vite != null) {
            vite.destroy();
            try {
                if (!vite.waitFor(5, TimeUnit.SECONDS)) vite.destroyForcibly();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                vite.destroyForcibly();
            }
        }
        if (context != null) context.close();
    }

    @Test
    void realChromeCanSaveReloadAndDeletePaste() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URI frontend = URI.create("http://127.0.0.1:" + port);
        var command = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "--host",
                "127.0.0.1", "--port", Integer.toString(port), "--strictPort");
        command.directory(Path.of("../frontend").toFile());
        command.environment().put("BACKEND_URL", base.toString());
        command.redirectErrorStream(true);
        command.redirectOutput(Path.of("../.cache/p2-dt01-vite.log").toFile());
        vite = command.start();
        boolean ready = false;
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!vite.isAlive()) throw new IllegalStateException("Vite exited before browser verification.");
            try {
                var connection = (HttpURLConnection) frontend.resolve("/login").toURL()
                        .openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                ready = connection.getResponseCode() == 200;
                connection.disconnect();
                if (ready) break;
            } catch (java.io.IOException ignored) {
                // The loopback server may not have bound its port yet.
            }
            Thread.sleep(100);
        }
        assertThat(ready).as("Vite did not become ready").isTrue();
        var browserCommand = new ProcessBuilder("node", Path.of("../scripts/Verify-P2ResumeBrowser.mjs")
                .toAbsolutePath().normalize().toString());
        browserCommand.environment().put("P2_FRONTEND_BASE", frontend.toString());
        browserCommand.environment().put("P2_BROWSER_USER", a.login());
        browserCommand.environment().put("P2_BROWSER_PASSWORD", a.password());
        browserCommand.environment().put("P2_BROWSER_OTHER_USER", b.login());
        browserCommand.environment().put("P2_BROWSER_OTHER_PASSWORD", b.password());
        Path uploadFixture = privateRoot().getParent().resolve("synthetic.docx");
        java.nio.file.Files.write(uploadFixture, docx("Browser synthetic resume ".repeat(10)));
        browserCommand.environment().put("P2_UPLOAD_FIXTURE", uploadFixture.toString());
        Path failedFixture = privateRoot().getParent().resolve("broken.pdf");
        java.nio.file.Files.writeString(failedFixture, "%PDF-broken synthetic");
        browserCommand.environment().put("P2_FAILED_UPLOAD_FIXTURE", failedFixture.toString());
        browserCommand.redirectErrorStream(true);
        Process browser = browserCommand.start();
        boolean finished = browser.waitFor(90, TimeUnit.SECONDS);
        if (!finished) browser.destroyForcibly();
        String output = new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("Chrome material verification timed out").isTrue();
        assertThat(browser.exitValue()).as(output).isZero();
        assertThat(output).contains("P2-DT-01 real Chrome material checks passed.")
                .doesNotContain(a.password());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version v "
                + "JOIN material_resume r ON r.id = v.resume_id "
                + "WHERE r.owner_account_id = ? AND v.body = ?", Integer.class,
                b.id(), "账号切换期间的合成草稿" + "R".repeat(110))).isZero();
    }

    @Test
    void pasteBoundaryIdempotencyAndVersionFacts() throws Exception {
        Client user = new Client(a);
        UUID operation = UUID.randomUUID();
        String body = "\r\n" + "😀".repeat(100) + "\r\n";
        HttpResponse<String> created = user.create(body, operation);
        assertThat(created.statusCode()).isEqualTo(201);
        Map<String, Object> first = json(created);
        UUID root = UUID.fromString((String) first.get("resumeId"));
        UUID version = UUID.fromString((String) first.get("versionId"));
        assertThat(first.get("codepointCount")).isEqualTo(100);
        assertThat(first.get("body")).isEqualTo("😀".repeat(100));
        assertThat(json(user.create(body, operation)).get("versionId")).isEqualTo(version.toString());
        assertThat(json(user.get("/api/materials/resumes/operations/" + operation))
                .get("versionId")).isEqualTo(version.toString());
        assertThat(user.create("a".repeat(99), UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(user.create("a".repeat(20_001), UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(user.create("a".repeat(20_000), UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isEqualTo(1);
        assertThat(user.create("b".repeat(100), operation).statusCode()).isEqualTo(409);
        assertThat(user.get("/api/materials/resumes/" + root + "/versions/" + version).statusCode())
                .isEqualTo(200);
    }

    @Test
    void appendKeepsOriginalVersionAndUnknownResponseCanBeChecked() throws Exception {
        Client user = new Client(a);
        UUID createOperation = UUID.randomUUID();
        UUID root = UUID.fromString((String) user.createWithoutReadingBody("v".repeat(100),
                createOperation).get("resumeId"));
        String basePath = "/api/materials/resumes/" + root;
        UUID firstVersion = UUID.fromString((String) json(user.get(basePath)).get("versionId"));
        UUID appendOperation = UUID.randomUUID();
        Map<String, Object> second = json(user.addVersion(root, "n".repeat(120), appendOperation));
        assertThat(second.get("versionNo")).isEqualTo(2);
        assertThat(json(user.addVersion(root, "n".repeat(120), appendOperation))
                .get("versionId")).isEqualTo(second.get("versionId"));
        assertThat(json(user.get(basePath + "/versions/" + firstVersion)).get("body"))
                .isEqualTo("v".repeat(100));
        assertThat(json(user.get(basePath)).get("body")).isEqualTo("n".repeat(120));
        Map<String, Object> replayed = json(user.create("v".repeat(100), createOperation));
        assertThat(replayed.get("versionId"))
                .isEqualTo(firstVersion.toString());
        assertThat(replayed.get("body")).isEqualTo("v".repeat(100));
        Map<String, Object> original = json(user.get("/api/materials/resumes/operations/"
                + createOperation));
        assertThat(original.get("versionId")).isEqualTo(firstVersion.toString());
        assertThat(original.get("body")).isEqualTo("v".repeat(100));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isEqualTo(2);
    }

    @Test
    void removedSessionRejectsStaleCsrfBeforeMaterialWriteAndFreshLoginCanSave() throws Exception {
        Account owner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        Client expired = new Client(owner);
        assertThat(jdbc.update("DELETE FROM spring_session "
                + "WHERE principal_name = ?", owner.id().toString())).isEqualTo(1);
        assertThat(expired.create("s".repeat(100), UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(expired.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume WHERE owner_account_id = ?",
                Integer.class, owner.id())).isZero();
        Client renewed = new Client(owner);
        assertThat(renewed.create("s".repeat(100), UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    @Test
    void failedCleanupInsertRollsBackBarrierAndBodyThenRetrySucceeds() throws Exception {
        Client user = new Client(a);
        UUID root = UUID.fromString((String) json(user.create("r".repeat(100), UUID.randomUUID()))
                .get("resumeId"));
        jdbc.execute("CREATE FUNCTION app.fail_p2_cleanup() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'synthetic cleanup failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_p2_cleanup BEFORE INSERT ON material_cleanup_item "
                + "FOR EACH ROW EXECUTE FUNCTION app.fail_p2_cleanup()");
        try {
            assertThat(user.delete(root, true).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT state FROM material_resume WHERE id = ?",
                    String.class, root)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                    Integer.class, root)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_delete_request WHERE resume_id = ?",
                    Integer.class, root)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_p2_cleanup ON material_cleanup_item");
            jdbc.execute("DROP FUNCTION app.fail_p2_cleanup()");
        }
        assertThat(user.delete(root, true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isZero();
    }

    @Test
    void directAccessCsrfAndDeletionStayWithinOwner() throws Exception {
        Client alice = new Client(a);
        Client bob = new Client(b);
        Client keeper = new Client(maintainer);
        Client anonymous = new Client();
        anonymous.csrf();
        UUID op = UUID.randomUUID();
        UUID root = UUID.fromString((String) json(alice.create("a".repeat(100), op))
                .get("resumeId"));
        UUID bobRoot = UUID.fromString((String) json(bob.create("b".repeat(100), UUID.randomUUID()))
                .get("resumeId"));
        String path = "/api/materials/resumes/" + root;
        assertThat(anonymous.get(path).statusCode()).isEqualTo(401);
        assertThat(anonymous.create("a".repeat(100), UUID.randomUUID()).statusCode()).isEqualTo(401);
        assertThat(bob.get(path).statusCode()).isEqualTo(404);
        assertThat(bob.get("/api/materials/resumes").body()).doesNotContain(root.toString());
        assertThat(bob.addVersion(root, "z".repeat(100), UUID.randomUUID()).statusCode())
                .isEqualTo(404);
        assertThat(bob.delete(root, true).statusCode()).isEqualTo(404);
        assertThat(keeper.get(path).statusCode()).isEqualTo(403);
        assertThat(keeper.get("/api/materials/resumes").statusCode()).isEqualTo(403);
        assertThat(keeper.create("k".repeat(100), UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(alice.delete(root, false).statusCode()).isEqualTo(403);
        assertThat(alice.get(path).statusCode()).isEqualTo(200);
        assertThat(json(alice.get(path + "/deletion-impact")).get("versionCount")).isEqualTo(1);

        HttpResponse<String> deleted = alice.delete(root, true);
        assertThat(deleted.statusCode()).isEqualTo(200);
        String requestId = (String) json(deleted).get("requestId");
        assertThat(json(alice.delete(root, true)).get("requestId")).isEqualTo(requestId);
        assertThat(alice.get(path).statusCode()).isEqualTo(404);
        assertThat(bob.get(path + "/deletion").statusCode()).isEqualTo(404);
        assertThat(json(alice.get(path + "/deletion")).get("state")).isEqualTo("CLEANED");
        assertThat(alice.create("a".repeat(100), op).statusCode()).isEqualTo(404);
        assertThat(bob.get("/api/materials/resumes/" + bobRoot).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_cleanup_item "
                + "WHERE delete_request_id = ?", Integer.class, UUID.fromString(requestId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM material_resume WHERE id = ?",
                String.class, root)).isEqualTo("DELETED");
    }

    @Test
    void changedAccountCannotAcceptOldAccountsCreateOperation() throws Exception {
        Client current = new Client(b);
        current.expectedAccountId = a.id();
        UUID operationId = UUID.randomUUID();
        assertThat(current.create("a".repeat(100), operationId).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume WHERE owner_account_id = ? "
                + "AND create_operation_id = ?", Integer.class, b.id(), operationId)).isZero();
        current.expectedAccountId = b.id();
        assertThat(current.create("b".repeat(100), operationId).statusCode()).isEqualTo(201);
    }

    @Test
    void concurrentAppendAndDeleteCannotLeaveUntrackedBody() throws Exception {
        Client first = new Client(a);
        Client second = new Client(a);
        UUID root = UUID.fromString((String) json(first.create("x".repeat(100), UUID.randomUUID()))
                .get("resumeId"));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var append = executor.submit(() -> {
                gate.await();
                return first.addVersion(root, "y".repeat(100), UUID.randomUUID()).statusCode();
            });
            var delete = executor.submit(() -> {
                gate.await();
                return second.delete(root, true).statusCode();
            });
            gate.countDown();
            assertThat(append.get(30, TimeUnit.SECONDS)).isIn(201, 404);
            assertThat(delete.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(first.get("/api/materials/resumes/" + root).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, root)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_cleanup_item i "
                + "JOIN material_delete_request d ON d.id = i.delete_request_id "
                + "WHERE d.resume_id = ?", Integer.class, root)).isBetween(1, 2);
    }

    @Test
    void resetCommittedBeforeOldWriteGetsAccountLockRejectsCreateAppendAndDelete() throws Exception {
        Account createOwner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        Client createClient = new Client(createOwner);
        assertLateWriteRevoked(createOwner, () -> createClient.create("c".repeat(100), UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume WHERE owner_account_id = ?",
                Integer.class, createOwner.id())).isZero();

        Account appendOwner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        Client appendClient = new Client(appendOwner);
        UUID appendRoot = UUID.fromString((String) json(appendClient.create("a".repeat(100),
                UUID.randomUUID())).get("resumeId"));
        assertLateWriteRevoked(appendOwner, () -> appendClient.addVersion(appendRoot,
                "b".repeat(100), UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, appendRoot)).isEqualTo(1);

        Account deleteOwner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        Client deleteClient = new Client(deleteOwner);
        UUID deleteRoot = UUID.fromString((String) json(deleteClient.create("d".repeat(100),
                UUID.randomUUID())).get("resumeId"));
        assertLateWriteRevoked(deleteOwner, () -> deleteClient.delete(deleteRoot, true));
        assertThat(jdbc.queryForObject("SELECT state FROM material_resume WHERE id = ?",
                String.class, deleteRoot)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, deleteRoot)).isEqualTo(1);
    }

    @Test
    void jdBoundariesHistoryAndOriginalOperationRemainFixed() throws Exception {
        Client user = new Client(a);
        UUID root = UUID.fromString((String) json(user.create("r".repeat(100), UUID.randomUUID())).get("resumeId"));
        String path = "/api/materials/resumes/" + root;
        for (String invalid : new String[] {"j".repeat(49), "j".repeat(10_001), " \r\n "}) {
            assertThat(user.addJd(root, invalid, UUID.randomUUID()).statusCode()).isEqualTo(400);
        }
        assertThat(user.send("POST", path + "/jd-versions", "{\"body\":null}", user.token,
                UUID.randomUUID()).statusCode()).isEqualTo(400);
        UUID operation = UUID.randomUUID();
        String firstBody = "😀".repeat(48) + "\r\nJ";
        var firstResponse = user.addJd(root, "  " + firstBody + "\r\n", operation);
        assertThat(firstResponse.statusCode()).isEqualTo(201);
        var first = json(firstResponse);
        assertThat(first.get("codepointCount")).isEqualTo(50);
        assertThat(first.get("body")).isEqualTo("😀".repeat(48) + "\nJ");
        assertThat(first.get("versionNo")).isEqualTo(1);
        assertThat(user.addJd(root, "z".repeat(50), operation).statusCode()).isEqualTo(409);
        var second = json(user.addJd(root, "J".repeat(10_000), UUID.randomUUID()));
        assertThat(second.get("versionNo")).isEqualTo(2);
        assertThat(second.get("codepointCount")).isEqualTo(10_000);
        assertThat(json(user.addJd(root, firstBody, operation))).isEqualTo(first);
        assertThat(json(user.get(path + "/jd-operations/" + operation))).isEqualTo(first);
        assertThat(json(user.get(path + "/jd-versions/" + first.get("versionId")))).isEqualTo(first);
        var history = JsonParserFactory.getJsonParser().parseList(user.get(path + "/jd-versions").body());
        assertThat(history).extracting(value -> (Integer) ((Map<?, ?>) value).get("versionNo")).containsExactly(2, 1);
        assertThat(user.get(path + "/versions").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_jd_version WHERE resume_id = ?",
                Integer.class, root)).isEqualTo(2);
    }

    @Test
    void jdAndVersionEndpointsEnforceOwnerCsrfAndExpectedIdentity() throws Exception {
        Client owner = new Client(a);
        Client other = new Client(b);
        Client keeper = new Client(maintainer);
        Client anonymous = new Client();
        anonymous.csrf();
        var created = json(owner.create("r".repeat(100), UUID.randomUUID()));
        UUID root = UUID.fromString((String) created.get("resumeId"));
        UUID operation = UUID.randomUUID();
        var jd = json(owner.addJd(root, "j".repeat(50), operation));
        String path = "/api/materials/resumes/" + root;
        for (String suffix : new String[] {"/versions", "/versions/" + created.get("versionId"),
                "/jd-versions", "/jd-versions/" + jd.get("versionId"), "/jd-operations/" + operation}) {
            assertThat(anonymous.get(path + suffix).statusCode()).isEqualTo(401);
            assertThat(other.get(path + suffix).statusCode()).isEqualTo(404);
            assertThat(keeper.get(path + suffix).statusCode()).isEqualTo(403);
        }
        assertThat(other.addJd(root, "b".repeat(50), UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(keeper.addJd(root, "k".repeat(50), UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(anonymous.addJd(root, "x".repeat(50), UUID.randomUUID()).statusCode()).isEqualTo(401);
        assertThat(owner.send("POST", path + "/jd-versions", owner.bodyJson("j".repeat(50)),
                null, UUID.randomUUID()).statusCode()).isEqualTo(403);
        owner.expectedAccountId = b.id();
        assertThat(owner.addJd(root, "j".repeat(50), UUID.randomUUID()).statusCode()).isEqualTo(409);
        owner.expectedAccountId = a.id();
        UUID otherRoot = UUID.fromString((String) json(owner.create("x".repeat(100), UUID.randomUUID())).get("resumeId"));
        assertThat(owner.get("/api/materials/resumes/" + otherRoot + "/jd-versions/" + jd.get("versionId"))
                .statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_jd_version WHERE resume_id = ?",
                Integer.class, root)).isEqualTo(1);
    }

    @Test
    void concurrentJdConfirmationAllocatesOneVersionPerOperation() throws Exception {
        Client first = new Client(a);
        Client second = new Client(a);
        UUID root = UUID.fromString((String) json(first.create("r".repeat(100), UUID.randomUUID())).get("resumeId"));
        UUID sameOperation = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> first.addJd(root, "j".repeat(50), sameOperation));
            var two = executor.submit(() -> second.addJd(root, "j".repeat(50), sameOperation));
            assertThat(json(one.get(30, TimeUnit.SECONDS))).isEqualTo(json(two.get(30, TimeUnit.SECONDS)));
            var three = executor.submit(() -> first.addJd(root, "a".repeat(50), UUID.randomUUID()));
            var four = executor.submit(() -> second.addJd(root, "b".repeat(50), UUID.randomUUID()));
            assertThat(three.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(four.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }
        assertThat(jdbc.queryForList("SELECT version_no FROM material_jd_version WHERE resume_id = ? ORDER BY version_no",
                Integer.class, root)).containsExactly(1, 2, 3);
    }

    @Test
    void jdCleanupFailureRollsBackAllFactsAndRetryCleansBothKinds() throws Exception {
        Client user = new Client(a);
        UUID root = UUID.fromString((String) json(user.create("r".repeat(100), UUID.randomUUID())).get("resumeId"));
        var jd = json(user.addJd(root, "j".repeat(50), UUID.randomUUID()));
        UUID untouchedRoot = UUID.fromString((String) json(new Client(b).create("b".repeat(100), UUID.randomUUID())).get("resumeId"));
        assertThat(json(user.get("/api/materials/resumes/" + root + "/deletion-impact")))
                .containsEntry("versionCount", 1).containsEntry("jdVersionCount", 1);
        jdbc.execute("CREATE FUNCTION app.fail_jd_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.target_type = 'JD_VERSION' THEN RAISE EXCEPTION 'synthetic JD cleanup failure'; END IF; "
                + "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_jd_cleanup BEFORE INSERT ON material_cleanup_item FOR EACH ROW EXECUTE FUNCTION app.fail_jd_cleanup()");
        try {
            assertThat(user.delete(root, true).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT state FROM material_resume WHERE id = ?", String.class, root)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_jd_version WHERE resume_id = ?", Integer.class, root)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?", Integer.class, root)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_delete_request WHERE resume_id = ?", Integer.class, root)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_jd_cleanup ON material_cleanup_item");
            jdbc.execute("DROP FUNCTION app.fail_jd_cleanup()");
        }
        var deleted = json(user.delete(root, true));
        assertThat(json(user.delete(root, true))).isEqualTo(deleted);
        assertThat(user.get("/api/materials/resumes/" + root + "/jd-versions/" + jd.get("versionId")).statusCode()).isEqualTo(404);
        assertThat(user.addJd(root, "x".repeat(50), UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_jd_version WHERE resume_id = ?", Integer.class, root)).isZero();
        assertThat(jdbc.queryForList("SELECT target_type FROM material_cleanup_item WHERE delete_request_id = ? ORDER BY target_type",
                String.class, UUID.fromString((String) deleted.get("requestId")))).containsExactly("JD_VERSION", "RESUME_VERSION");
        assertThat(new Client(b).get("/api/materials/resumes/" + untouchedRoot).statusCode()).isEqualTo(200);
    }

    @Test
    void jdAndDeleteAreSerializedInBothOrders() throws Exception {
        for (boolean jdFirst : new boolean[] {true, false}) {
            Account owner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
            Client writeClient = new Client(owner);
            Client deleteClient = new Client(owner);
            UUID root = UUID.fromString((String) json(writeClient.create("r".repeat(100), UUID.randomUUID())).get("resumeId"));
            try (var locked = DriverManager.getConnection(System.getenv("DB_URL") + "?currentSchema=app",
                    System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD")); var executor = Executors.newFixedThreadPool(2)) {
                locked.setAutoCommit(false);
                try (var statement = locked.prepareStatement("SELECT id FROM identity_account WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, owner.id());
                    assertThat(statement.executeQuery().next()).isTrue();
                }
                MaterialWrite add = () -> writeClient.addJd(root, "j".repeat(50), UUID.randomUUID());
                MaterialWrite remove = () -> deleteClient.delete(root, true);
                var first = executor.submit(() -> (jdFirst ? add : remove).send().statusCode());
                awaitAccountLockWaiters(1);
                var second = executor.submit(() -> (jdFirst ? remove : add).send().statusCode());
                awaitAccountLockWaiters(2);
                locked.commit();
                assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(jdFirst ? 201 : 200);
                assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(jdFirst ? 200 : 404);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_jd_version WHERE resume_id = ?", Integer.class, root)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_cleanup_item i JOIN material_delete_request d ON d.id=i.delete_request_id "
                    + "WHERE d.resume_id=? AND i.target_type='JD_VERSION'", Integer.class, root)).isEqualTo(jdFirst ? 1 : 0);
        }
    }

    @Test
    void resetBeforeJdWriteObtainsLockRevokesLateRequest() throws Exception {
        Account owner = insertAccount(Set.of(AccountAuthority.TRIAL_USER));
        Client user = new Client(owner);
        UUID root = UUID.fromString((String) json(user.create("r".repeat(100), UUID.randomUUID())).get("resumeId"));
        assertLateWriteRevoked(owner, () -> user.addJd(root, "j".repeat(50), UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_jd_version WHERE resume_id = ?", Integer.class, root)).isZero();
    }

    private static void assertLateWriteRevoked(Account owner, MaterialWrite write) throws Exception {
        String url = System.getenv("DB_URL") + "?currentSchema=app";
        try (var locked = DriverManager.getConnection(url, System.getenv("DB_USERNAME"),
                System.getenv("DB_PASSWORD")); var executor = Executors.newFixedThreadPool(2)) {
            locked.setAutoCommit(false);
            try (var statement = locked.prepareStatement(
                    "SELECT id FROM identity_account WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, owner.id());
                assertThat(statement.executeQuery().next()).isTrue();
            }
            var reset = executor.submit(() -> context.getBean(TrialAccountPasswordResetter.class)
                    .reset(owner.id()));
            awaitAccountLockWaiters(1);
            var oldWrite = executor.submit(() -> write.send().statusCode());
            awaitAccountLockWaiters(2);
            locked.commit();
            assertThat(reset.get(30, TimeUnit.SECONDS).credentialVersion()).isEqualTo(2);
            assertThat(oldWrite.get(30, TimeUnit.SECONDS)).isEqualTo(401);
        }
    }

    private static void awaitAccountLockWaiters(int expected) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                    + "AND query LIKE '%identity_account%FOR UPDATE%'", Integer.class);
            if (waiting != null && waiting >= expected) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for account-row lock contention.");
    }

    @FunctionalInterface
    private interface MaterialWrite {
        HttpResponse<String> send() throws Exception;
    }

    private static Map<String, Object> json(HttpResponse<String> response) {
        return JsonParserFactory.getJsonParser().parseMap(response.body());
    }

    private record Account(UUID id, String login, String password) {
    }

    private static final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        private String token;
        private UUID expectedAccountId;

        Client() {
        }

        Client(Account account) throws Exception {
            expectedAccountId = account.id();
            String before = csrf();
            String form = "username=" + URLEncoder.encode(account.login(), StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(account.password(), StandardCharsets.UTF_8);
            assertThat(send("POST", "/api/auth/login", form, before, null).statusCode()).isEqualTo(200);
            token = csrf();
        }

        String csrf() throws Exception {
            String result = (String) json(get("/api/auth/csrf")).get("token");
            token = result;
            return result;
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, null, null);
        }

        HttpResponse<String> upload(String name, byte[] bytes, UUID operation, UUID root) throws Exception {
            String boundary = "----p2syntheticboundary";
            var out = new java.io.ByteArrayOutputStream();
            if (root != null) out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"resumeId\"\r\n\r\n" + root + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return http.send(HttpRequest.newBuilder(base.resolve("/api/materials/uploads"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-CSRF-TOKEN", token).header("X-Expected-Account-Id", expectedAccountId.toString())
                    .header("X-Operation-Id", operation.toString()).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> create(String body, UUID operation) throws Exception {
            return send("POST", "/api/materials/resumes", bodyJson(body), token, operation);
        }

        Map<String, Object> createWithoutReadingBody(String body, UUID operation) throws Exception {
            var builder = HttpRequest.newBuilder(base.resolve("/api/materials/resumes"))
                    .header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", token)
                    .header("X-Operation-Id", operation.toString())
                    .header("X-Expected-Account-Id", expectedAccountId.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson(body)));
            assertThat(http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(201);
            return json(get("/api/materials/resumes/operations/" + operation));
        }

        HttpResponse<String> addVersion(UUID root, String body, UUID operation) throws Exception {
            return send("POST", "/api/materials/resumes/" + root + "/versions",
                    bodyJson(body), token, operation);
        }

        HttpResponse<String> addJd(UUID root, String body, UUID operation) throws Exception {
            return send("POST", "/api/materials/resumes/" + root + "/jd-versions", bodyJson(body), token, operation);
        }

        HttpResponse<String> delete(UUID root, boolean withCsrf) throws Exception {
            return send("DELETE", "/api/materials/resumes/" + root, "", withCsrf ? token : null, null);
        }

        private String bodyJson(String body) {
            return "{\"body\":\"" + body.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", "\\r").replace("\n", "\\n") + "\"}";
        }

        private HttpResponse<String> send(String method, String path, String body,
                String csrf, UUID operation) throws Exception {
            var builder = HttpRequest.newBuilder(base.resolve(path)).header("Accept", "application/json");
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (operation != null) builder.header("X-Operation-Id", operation.toString());
            if (expectedAccountId != null && path.startsWith("/api/materials/")
                    && (method.equals("POST") || method.equals("DELETE"))) {
                builder.header("X-Expected-Account-Id", expectedAccountId.toString());
            }
            if (method.equals("POST")) {
                builder.header("Content-Type", path.equals("/api/auth/login")
                        ? "application/x-www-form-urlencoded" : "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else if (method.equals("DELETE")) {
                builder.DELETE();
            } else {
                builder.GET();
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
