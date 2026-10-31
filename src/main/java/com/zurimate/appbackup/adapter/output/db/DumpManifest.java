package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.DBConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/** Human readable MANIFEST.txt shipped inside every database archive. */
public final class DumpManifest {
    public static final String FILE_NAME = "MANIFEST.txt";

    private DumpManifest() {
    }

    /**
     * @param entries object name to "count  file" description; a null value marks a failed object
     */
    public static void write(Path file, DBConfig config, Instant startedAt, String objectKind,
                             Map<String, String> entries, long totalRecords,
                             Map<String, String> failures, String restoreInstructions) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("AppBackup database dump\n")
                .append("database:   ").append(config.dbType()).append(' ').append(config.dbName()).append('\n')
                .append("host:       ").append(config.hostOrDefault()).append(':').append(config.port()).append('\n')
                .append("started:    ").append(startedAt).append('\n')
                .append("finished:   ").append(Instant.now()).append('\n')
                .append(objectKind).append(": ").append(entries.size()).append('\n')
                .append("records:    ").append(totalRecords).append('\n')
                .append("failures:   ").append(failures.size()).append('\n')
                .append("status:     ").append(failures.isEmpty() ? "COMPLETE" : "INCOMPLETE").append("\n\n")
                .append("How to restore:\n").append(restoreInstructions.strip().indent(2)).append('\n')
                .append(objectKind).append(" (records  file):\n");
        entries.forEach((name, description) -> sb.append("  ")
                .append(description == null ? "FAILED  " + name : description).append('\n'));
        if (!failures.isEmpty()) {
            sb.append("\nFailures:\n");
            failures.forEach((name, reason) -> sb.append("  ").append(name).append(": ").append(reason).append('\n'));
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
