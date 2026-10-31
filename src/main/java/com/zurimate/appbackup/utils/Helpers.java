package com.zurimate.appbackup.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zurimate.appbackup.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Small stateless helpers shared across the application (formatting, file names, cleanup).
 */
@Slf4j
public final class Helpers {
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** Archive name timestamp, e.g. db-2026-10-31-06-00-00.zip (application time zone). */
    public static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter CLOUD_DIR_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm");
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
                    return "%s %s".formatted(cvPath[cvPath.length - 1], constraintViolation.getMessage());
                })
                .collect(Collectors.joining(" | "));
    }

    public static String archiveTimestamp(Instant instant) {
        return ARCHIVE_TIMESTAMP.format(instant.atZone(ZoneId.systemDefault()));
    }

    /** Remote folder name per run, kept identical to earlier releases: 2026-10-31-07-26-gmt. */
    public static String cloudDirName(Instant instant) {
        return "%s-gmt".formatted(CLOUD_DIR_TIMESTAMP.format(instant.atZone(ZoneOffset.UTC)));
    }

    /** Recursively deletes a directory; logs instead of throwing because it only runs during cleanup. */
    public static void deleteRecursively(Path dir) {
        if (dir == null || Files.notExists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to delete directory {}", dir, e);
        }
    }

    /** File name for a table dump; keeps it readable while ruling out path traversal. */
    public static String dumpFileName(String table) {
        String cleaned = table.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (cleaned.isBlank() || cleaned.startsWith(".")) cleaned = "_" + cleaned;
        return cleaned + ".sql";
    }

    public static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return root == e ? message : "%s (%s)".formatted(e.getMessage(), message);
    }
}
