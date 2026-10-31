package dev.interviewcoach.material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 文件名只由登记 ID 和固定后缀构造；不接收用户路径。目录须仅向应用维护者开放。 */
@Component
@Profile("local")
final class PrivateFiles {
    private final Path root;

    PrivateFiles(@Value("${material.private-root:../.local-data/private}") String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize();
        if (root.startsWith(Path.of("src/main/resources").toAbsolutePath())
                || root.toString().contains("frontend")) {
            throw new IOException("Private storage must be outside web resources");
        }
        // 创建前先检查所有已有祖先，避免经 junction 将文件写入受控目录之外。
        for (Path p = root; p != null; p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(p) || !p.toRealPath().equals(p))) {
                throw new IOException("Redirected private ancestor is forbidden");
            }
        }
        boolean fresh = !Files.exists(root);
        Files.createDirectories(root);
        checkRoot();
        if (fresh) restrictNewDirectory(root);
        verifyPermissions();
        Files.createDirectories(root.resolve("multipart"));
        check(root.resolve("multipart"));
        // 单活动应用启动前，容器异常退出遗留的 multipart 临时文件没有活跃写入者。
        try (var entries = Files.list(root.resolve("multipart"))) {
            for (Path entry : entries.toList()) {
                check(entry);
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected multipart entry");
                Files.delete(entry);
            }
        }
    }

    private void verifyPermissions() throws IOException {
        var acl = Files.getFileAttributeView(root, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            String owner = acl.getOwner().getName();
            for (var entry : acl.getAcl()) {
                String name = entry.principal().getName();
                if (entry.type() == java.nio.file.attribute.AclEntryType.ALLOW && !name.equalsIgnoreCase(owner)
                        && !name.equalsIgnoreCase("NT AUTHORITY\\SYSTEM") && !name.equalsIgnoreCase("BUILTIN\\Administrators")) {
                    throw new IOException("Private storage ACL grants another principal access");
                }
            }
        } else {
            for (var permission : Files.getPosixFilePermissions(root)) {
                if (!permission.name().startsWith("OWNER_")) throw new IOException("Private storage must be owner-only");
            }
        }
    }

    private void restrictNewDirectory(Path directory) throws IOException {
        var acl = Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            var entry = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                    .setFlags(java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT, java.nio.file.attribute.AclEntryFlag.FILE_INHERIT).build();
            acl.setAcl(java.util.List.of(entry));
        } else {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    private void checkRoot() throws IOException {
        // toRealPath 比较也拒绝 Windows junction；逐层核查避免祖先目录重定向。
        for (Path p = root; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || !p.toRealPath().equals(p.toAbsolutePath().normalize())) {
                throw new IOException("Redirected private path is forbidden");
            }
        }
    }

    Path path(UUID id, String suffix) throws IOException {
        if (!java.util.Set.of("part", "bin", "txt", "result").contains(suffix)) {
            throw new IOException("Invalid private suffix");
        }
        checkRoot();
        Path target = root.resolve(id + "." + suffix);
        check(target);
        return target;
    }

    private void check(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(path) || !path.toRealPath().equals(path))) {
            throw new IOException("Redirected private file is forbidden");
        }
    }

    void stage(UUID id, byte[] bytes) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(path(id, "part"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // 同目录原子改名仅发布文件；数据库接受是另一次独立裁决。
        Files.move(path(id, "part"), path(id, "bin"), StandardCopyOption.ATOMIC_MOVE);
    }

    byte[] read(UUID id) throws IOException {
        Path source = path(id, "bin");
        if (Files.size(source) > 10_000_000) throw new IOException("Private file exceeds registered limit");
        return Files.readAllBytes(source);
    }

    Path workDirectory(UUID id) throws IOException {
        checkRoot();
        Path directory = root.resolve(id + ".work");
        check(directory);
        Files.createDirectories(directory);
        return directory;
    }

    void removeWork(UUID id) throws IOException {
        checkRoot();
        Path directory = root.resolve(id + ".work");
        check(directory);
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            var targets = paths.limit(10_001).toList();
            if (targets.size() > 10_000) throw new IOException("Unexpected worker file count");
            for (Path target : targets.reversed()) {
                if (!target.startsWith(directory)) throw new IOException("Invalid cleanup target");
                check(target);
                Files.delete(target);
            }
        }
    }

    void remove(UUID id) throws IOException {
        for (String suffix : java.util.List.of("part", "bin", "txt", "result")) {
            Files.deleteIfExists(path(id, suffix));
        }
        removeWork(id);
    }
}
