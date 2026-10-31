package dev.interviewcoach.material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
final class DocumentParser {
    private final PrivateFiles files;

    DocumentParser(PrivateFiles files) { this.files = files; }

    Result parse(UUID id, String type) throws IOException, InterruptedException {
        Path work = files.workDirectory(id);
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:MaxDirectMemorySize=32m", "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=" + work, "-Duser.home=" + work, "-XX:ErrorFile=" + work.resolve("jvm-error.log")));
        String classpath = System.getProperty("java.class.path");
        // 同时支持 IDE/测试的展开 classpath 与打包后的 Boot executable JAR。
        if (!classpath.contains(java.io.File.pathSeparator) && classpath.endsWith(".jar")
                && isBootJar(classpath)) {
            command.addAll(List.of("-jar", classpath, "--parser-worker"));
        } else {
            command.addAll(List.of("-cp", classpath, ParserWorker.class.getName()));
        }
        command.addAll(List.of(files.path(id, "bin").toString(), files.path(id, "txt").toString(),
                files.path(id, "result").toString(), type));
        var builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String systemRoot = System.getenv("SystemRoot");
        builder.environment().clear();
        if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
        builder.environment().put("TEMP", work.toString());
        builder.environment().put("TMP", work.toString());
        builder.environment().put("P2_PARSE_DEADLINE_MILLIS", Long.toString(System.currentTimeMillis() + 30_000));
        Process child = builder.start();
        try {
            if (!waitForDeadline(child)) return new Result("PARSE_TIMEOUT", null);
            Path result = files.path(id, "result");
            if (child.exitValue() == 124) return new Result("PARSE_TIMEOUT", null);
            if (child.exitValue() != 0 || !Files.exists(result)) return new Result("PARSE_FAILED", null);
            String code = Files.readString(result);
            return new Result(code, code.equals("READY") ? Files.readString(files.path(id, "txt")) : null);
        } finally {
            // 取消等待不是停止执行。确认 OS 子进程退出后才释放有界工作线程。
            child.destroyForcibly();
            child.waitFor();
            Files.deleteIfExists(files.path(id, "txt"));
            Files.deleteIfExists(files.path(id, "result"));
            files.removeWork(id);
        }
    }

    static boolean waitForDeadline(Process child) throws InterruptedException {
        try { return child.waitFor(30, TimeUnit.SECONDS); }
        finally {
            if (child.isAlive()) child.destroyForcibly();
            child.waitFor();
        }
    }

    private boolean isBootJar(String path) throws IOException {
        try (var jar = new java.util.jar.JarFile(path)) {
            return jar.getManifest().getMainAttributes().getValue("Main-Class")
                    .contains("springframework.boot.loader");
        }
    }

    record Result(String code, String body) { }
}
