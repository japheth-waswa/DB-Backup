package com.zurimate.appbackup.utils;

import com.zurimate.appbackup.dto.ApiResponse;
import com.zurimate.appbackup.ports.output.BackupStrategy;
import jakarta.validation.ConstraintViolationException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public final class Helpers {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final String DB_DIR = "db";
    private static final String OTHER_DIR = "other";
    private static final String ERROR_MESSAGE_PREFIX = "GlobalExceptionHandler: ";

    private Helpers() {
    }


    public static ApiResponse<Void> parseResponse(@NonNull Exception exception, String customMessage) {
        String message = customMessage != null && !customMessage.isBlank() ? customMessage : exception.getMessage();
        log.error(ERROR_MESSAGE_PREFIX + "{}", message, exception);
        return new ApiResponse<>(message, null);
    }

    public static String extractViolationsFromException(ConstraintViolationException validationException) {
        return validationException.getConstraintViolations()
                .stream()
                .map(constraintViolation -> {
                    String[] cvPath = constraintViolation.getPropertyPath().toString().split("\\.");
                    return java.lang.String.format("%s %s", cvPath[cvPath.length - 1], constraintViolation.getMessage());
                })
                .collect(Collectors.joining(" | "));
    }


    public static BackupStrategy createNewBackupStrategyInstance(ApplicationContext applicationContext, DBType dbType) {
        Map<DBType, Function<ApplicationContext, BackupStrategy>> backupInstances = new HashMap<>();
        Arrays.stream(DBType.values())
                .forEach(dbTypeEnum -> backupInstances
                        .put(dbTypeEnum,
                                (appContext) -> appContext.getBean(dbTypeEnum.toString(), BackupStrategy.class)));
        return backupInstances.get(dbType).apply(applicationContext);
    }

    public static Map<DirType, Path> finalBackupDestinationDir(String destination) {
//        var dateTimeDirName = LocalDateTime.now().format(DATE_TIME_FORMATTER);
//        Path path = Paths.get(destination, dateTimeDirName);
        Path path = Paths.get(destination);
        return Map.of(DirType.DB, path.toAbsolutePath(),
                DirType.OTHER, path.toAbsolutePath());
    }

    public static Map<DirType, Path> localTempDir() {
        var dateTimeDirName = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        String dirName = "temp-" + dateTimeDirName + "-" + UUID.randomUUID();
        return Map.of(DirType.DB, Paths.get(dirName, DB_DIR).toAbsolutePath(),
                DirType.OTHER, Paths.get(dirName, OTHER_DIR).toAbsolutePath());
    }

    public static String parseHost(String host) {
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    public static Path getZipFileName(Path dir) {
        return dir.getParent().resolve(dir.getFileName() + "-" + LocalDateTime.now().format(DATE_TIME_FORMATTER) + ".zip");
    }

    public static Path getRuntimeDir() {
        return Paths.get("").toAbsolutePath();
    }

    public static void compressDirectory(Path sourceDir, Path zipFile) {
        log.info("Zip procedure for dir: {} started", sourceDir);
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            try (var files = Files.walk(sourceDir)) {
                files.filter(path -> !Files.isDirectory(path))
                        .forEach(path -> {
                            try {
                                ZipEntry zipEntry = new ZipEntry(sourceDir.relativize(path).toString());
                                zipOutputStream.putNextEntry(zipEntry);
                                Files.copy(path, zipOutputStream);
                                zipOutputStream.closeEntry();
                            } catch (IOException e) {
                                log.error("Error performing zip", e);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Error compressing directory: {}", sourceDir, e);
        }
        log.info("Zip procedure for dir: {} done", sourceDir);
    }

    public static void copyDirectory(Path sourceDir, Path destinationDir) {
        log.info("Copy procedure for dir: {} started", sourceDir.toAbsolutePath());
        if (!Files.exists(sourceDir)) {
            log.warn("Source directory does not exist");
            return;
        }
        try {
            Path rootDir = destinationDir.resolve(sourceDir.getFileName());
            Files.createDirectories(rootDir);

            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path targetDir = rootDir.resolve(sourceDir.relativize(dir));
                    if (!Files.exists(targetDir)) {
                        Files.createDirectories(targetDir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path targetFile = rootDir.resolve(sourceDir.relativize(file));
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Copy procedure for dir: {} successful", sourceDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Copy procedure for dir: {} failed", sourceDir.toAbsolutePath(), e);
        }
    }

    public static Path destinationFile(Path destinationDir, Path filePath) {
        return destinationDir.resolve(filePath.getFileName());
    }

    public static void copyFile(Path destinationDir, Path filePath) {
        log.info("Preparing to copy the file: {}", filePath.toAbsolutePath());
        if (!Files.exists(filePath)) {
            log.warn("The file: {} does not exist", filePath.toAbsolutePath());
            return;
        }
        try {
            if (!Files.exists(destinationDir)) {
                Files.createDirectories(destinationDir);
            }

//            Path destinationPath = destinationDir.resolve(filePath.getFileName());
            Path destinationPath = Helpers.destinationFile(destinationDir, filePath);

            Files.copy(filePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Successfully copied the file: {} to: {}", filePath.toAbsolutePath(), destinationDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to copy the file: {} to: {}", filePath.toAbsolutePath(), destinationDir.toAbsolutePath());
        }
    }

    public static void deleteDirectory(Path dir) {
        log.info("Delete procedure for dir: {} started", dir.toAbsolutePath());
        if (!Files.exists(dir)) {
            log.warn("Directory to be deleted does not exist");
            return;
        }

        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Delete procedure for dir: {} successful", dir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Delete procedure for dir: {} failed", dir.toAbsolutePath(), e);
        }

    }

    public static Path createCustomFile(Path dir, String fileName) {
        try {
            Path fileResolved = dir.resolve(fileName);
            if (Files.notExists(fileResolved)) {
                Files.createFile(fileResolved);
            }
            //set file permissions to allow full access to anyone (rwxrwxrwx)
            setFullAccessPermissions(fileResolved);
            return fileResolved;
        } catch (Exception e) {
            log.error("Error creating file: {}", fileName, e);
            return null;
        }
    }

    public static void setFullAccessPermissions(Path file) {
        try {
            if (Files.notExists(file)) {
                log.warn("Cannot set file permissions on file: {}. Doesn't exist", file);
                return;
            }
            Files.setPosixFilePermissions(file,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_WRITE,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_WRITE,
                            PosixFilePermission.OTHERS_EXECUTE
                    ));
            log.info("Full access granted to file: {}", file);
        } catch (Exception e) {
            log.error("Error setting full access permissions for file: {}", file, e);
        }
    }

    public static void backupOtherDirs(List<String> otherDirsToBackup, Path tempDir, Path destinationDir, Path zipFile) {
        if (otherDirsToBackup == null || otherDirsToBackup.isEmpty()) {
            return;
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String dir : otherDirsToBackup) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        Helpers.copyDirectory(Paths.get(dir), tempDir);
                    } catch (Exception e) {
                        log.error("Failed to parse directory:{} for backup", dir);
                    }
                }, executor));
            }

            //wait for all tasks to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Error while copying directories", e);
        }

        //compress
        Helpers.compressDirectory(tempDir, zipFile);

        //copy file
        Helpers.copyFile(destinationDir, zipFile);
    }

}
