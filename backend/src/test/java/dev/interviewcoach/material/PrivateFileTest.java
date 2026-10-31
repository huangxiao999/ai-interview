package dev.interviewcoach.material;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PrivateFileTest {
    @Test
    void delayedWorkerPastItsParentDeadlineCannotRecreateOutputs() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("../.cache/tmp").toAbsolutePath()), "expired-worker-");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ParserWorker.class.getName(),
                root.resolve("never-opened.pdf").toString(), root.resolve("text.txt").toString(), root.resolve("result.txt").toString(), "PDF");
        command.environment().put("P2_PARSE_DEADLINE_MILLIS", Long.toString(System.currentTimeMillis() - 1000));
        var child = command.start();
        assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isEqualTo(124);
        try (var files = Files.list(root)) { assertThat(files.count()).isZero(); }
    }

    @Test
    void actualHungChildIsDeadBeforeTheThirtySecondPermitReturns() throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), SleepingWorker.class.getName())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        long began = System.nanoTime();
        assertThat(DocumentParser.waitForDeadline(process)).isFalse();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - began).toSeconds()).isBetween(29L, 45L);
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void durableKeysRejectTraversalAndActualWindowsJunction() throws Exception {
        Path base = Files.createTempDirectory(Files.createDirectories(Path.of("../.cache/tmp").toAbsolutePath()), "private-file-test-");
        var store = new PrivateFiles(base.resolve("private").toString());
        UUID id = UUID.randomUUID();
        store.stage(id, new byte[] {1, 2, 3});
        assertThat(store.read(id)).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> store.path(id, "../../escape")).isInstanceOf(java.io.IOException.class);
        Path target = Files.createDirectory(base.resolve("target"));
        Files.writeString(target.resolve("sentinel"), "unchanged");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var command = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path $env:P2_LINK -Target $env:P2_TARGET | Out-Null");
        command.environment().put("P2_LINK", base.resolve("junction").toString());
        command.environment().put("P2_TARGET", target.toString());
        var child = command.start();
            assertThat(child.waitFor()).isZero();
        } else {
            Files.createSymbolicLink(base.resolve("junction"), target);
        }
        assertThatThrownBy(() -> new PrivateFiles(base.resolve("junction").toString())).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(target.resolve("sentinel"))).isEqualTo("unchanged");
        store.remove(id);
        assertThat(Files.exists(store.path(id, "bin"))).isFalse();
        // 留存隔离目录及 junction 证据，不递归删除任何测试目标。
    }

    public static final class SleepingWorker {
        public static void main(String[] args) throws Exception { Thread.sleep(90_000); }
    }
}
