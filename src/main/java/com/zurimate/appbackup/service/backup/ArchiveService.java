package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.utils.Helpers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Zipping, checksumming and publishing of finished archives. Every failure is thrown, never swallowed. */
@Slf4j
@Component
public class ArchiveService {
    public static final String CHECKSUM_SUFFIX = ".sha256";

    /** Zips the contents of {@code sourceDir} with paths relative to it. */
    public void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile), 1 << 16))) {
            addTree(zip, sourceDir, "");
        }
    }

    /**
     * Zips several directories (or files) into one archive, each under its own top-level folder.
     *
     * @return paths that did not exist and were skipped
     */
    public List<String> zipPaths(List<String> sources, Path zipFile) throws IOException {
        List<String> missing = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile), 1 << 16))) {
            for (String source : sources) {
                Path path = Path.of(source).toAbsolutePath().normalize();
                if (Files.notExists(path)) {
                    log.warn("Directory to back up does not exist: {}", path);
                    missing.add(source);
                    continue;
                }
                Path name = path.getFileName();
                // a single file goes in as-is; a directory keeps its own name as the top-level folder
                addTree(zip, path, Files.isRegularFile(path) ? "" : (name == null ? "root" : name.toString()) + "/");
            }
        }
        return missing;
    }

    private static void addTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        if (Files.isRegularFile(root)) {
            addFile(zip, root, prefix.isEmpty() ? root.getFileName().toString() : prefix + root.getFileName());
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    addFile(zip, file, prefix + root.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // unreadable files (sockets, permission problems) should not sink the whole archive
                log.warn("Skipping unreadable file {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void addFile(ZipOutputStream zip, Path file, String entryName) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    public String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Copies the archive into {@code destinationDir} under a temporary name and renames it into
     * place, so a half-written file never looks like a finished backup. A {@code sha256sum}
     * compatible checksum file is written next to it.
     */
    public Path publish(Path archive, Path destinationDir, String sha256) throws IOException {
        Files.createDirectories(destinationDir);
        String fileName = archive.getFileName().toString();
        Path target = destinationDir.resolve(fileName);
        Path partial = destinationDir.resolve("." + fileName + ".partial");
        try {
            Files.copy(archive, partial, StandardCopyOption.REPLACE_EXISTING);
            moveIntoPlace(partial, target);
            Path checksum = destinationDir.resolve(fileName + CHECKSUM_SUFFIX);
            Path checksumPartial = destinationDir.resolve("." + fileName + CHECKSUM_SUFFIX + ".partial");
            Files.writeString(checksumPartial, sha256 + "  " + fileName + "\n", StandardCharsets.UTF_8);
            moveIntoPlace(checksumPartial, checksum);
        } finally {
            Files.deleteIfExists(partial);
        }
        return target;
    }

    private static void moveIntoPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public long usableSpaceMb(Path dir) throws IOException {
        Path existing = dir;
        while (existing != null && Files.notExists(existing)) existing = existing.getParent();
        if (existing == null) return Long.MAX_VALUE;
        return Files.getFileStore(existing).getUsableSpace() / (1024 * 1024);
    }

    public void cleanup(Path dir) {
        Helpers.deleteRecursively(dir);
    }
}
