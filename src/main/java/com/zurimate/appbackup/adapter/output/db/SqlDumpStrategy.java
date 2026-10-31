package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DatabaseDumpStrategy;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.ports.output.DumpSession;
import com.zurimate.appbackup.ports.output.DumpSettings;
import com.zurimate.appbackup.ports.output.TableDumper;
import com.zurimate.appbackup.utils.Helpers;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared engine for relational dumps. Dialects only provide a {@link DumpSession}; this class
 * owns the layout, the bounded worker pool and the error handling:
 * <pre>
 *   00-pre-data.sql      objects needed before data (extensions, types, sequences, schemas)
 *   tables/&lt;name&gt;.sql   one file per table: definition + rows
 *   90-post-data.sql     indexes, foreign keys, sequence values, views, triggers
 *   MANIFEST.txt         row counts, failures and restore instructions
 * </pre>
 * Connections: one coordinator plus at most {@code maxWorkers}, all closed before returning.
 */
@Slf4j
public abstract class SqlDumpStrategy implements DatabaseDumpStrategy {
    public static final String PRE_DATA = "00-pre-data.sql";
    public static final String POST_DATA = "90-post-data.sql";
    public static final String TABLES_DIR = "tables";
    private static final int MAX_WORKER_OPENS = 3;

    protected abstract DumpSession openSession(DBConfig config, DumpSettings settings) throws SQLException;

    /** Shell commands that restore this dump, shown in the manifest. */
    protected abstract String restoreInstructions();

    @Override
    public final DumpReport dump(DBConfig config, Path outputDir, DumpSettings settings) throws Exception {
        Path tablesDir = Files.createDirectories(outputDir.resolve(TABLES_DIR));
        Instant startedAt = Instant.now();

        try (DumpSession session = openSession(config, settings)) {
            List<String> tables = session.tables();
            String header = session.fileHeader();
            log.info("Dumping {} tables from {} {}:{}/{}", tables.size(), config.dbType(), config.hostOrDefault(),
                    config.port(), config.dbName());

            writeFile(outputDir.resolve(PRE_DATA), header, session::writePreData);

            Map<String, String> fileNames = uniqueFileNames(tables);
            SortedMap<String, String> failures = new ConcurrentSkipListMap<>();
            Map<String, Long> rowCounts = new ConcurrentSkipListMap<>();
            ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>(tables);
            int workers = Math.max(1, Math.min(Math.min(settings.maxWorkers(), config.parallelism()), tables.size()));

            if (!tables.isEmpty()) {
                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int i = 0; i < workers; i++) {
                        executor.submit(() -> drain(session, queue, tablesDir, fileNames, header, rowCounts, failures));
                    }
                }
            }

            try {
                writeFile(outputDir.resolve(POST_DATA), header, session::writePostData);
            } catch (Exception e) {
                log.error("Failed to write post-data for {}", config.dbName(), e);
                failures.put("<post-data>", Helpers.rootMessage(e));
            }

            long total = rowCounts.values().stream().mapToLong(Long::longValue).sum();
            Map<String, String> entries = new LinkedHashMap<>();
            for (String table : tables) {
                Long rows = rowCounts.get(table);
                entries.put(table, rows == null ? null : rows + "  " + TABLES_DIR + "/" + fileNames.get(table));
            }
            DumpManifest.write(outputDir.resolve(DumpManifest.FILE_NAME), config, startedAt, "tables",
                    entries, total, failures, restoreInstructions());
            return new DumpReport(tables.size(), total, failures);
        }
    }

    /** Worker loop: owns at most one connection at a time and always closes it. */
    private static void drain(DumpSession session, ConcurrentLinkedQueue<String> queue, Path tablesDir,
                              Map<String, String> fileNames, String header,
                              Map<String, Long> rowCounts, Map<String, String> failures) {
        TableDumper dumper = null;
        int opens = 0;
        try {
            String table;
            while ((table = queue.poll()) != null) {
                if (dumper == null) {
                    if (opens >= MAX_WORKER_OPENS) {
                        failures.put(table, "skipped: worker could not keep a database connection");
                        continue;
                    }
                    try {
                        opens++;
                        dumper = session.openWorker();
                    } catch (Exception e) {
                        log.error("Worker could not open a connection", e);
                        failures.put(table, "cannot open connection: " + Helpers.rootMessage(e));
                        continue;
                    }
                }

                Path file = tablesDir.resolve(fileNames.get(table));
                try (Writer writer = newWriter(file)) {
                    writer.write(header);
                    long rows = dumper.dumpTable(table, writer);
                    rowCounts.put(table, rows);
                    log.debug("Dumped table {} ({} rows)", table, rows);
                } catch (Exception e) {
                    log.error("Failed to dump table {}", table, e);
                    failures.put(table, Helpers.rootMessage(e));
                    deleteQuietly(file);
                    // a failed statement can poison the transaction; continue on a fresh connection
                    dumper.close();
                    dumper = null;
                }
            }
        } finally {
            if (dumper != null) dumper.close();
        }
    }

    @FunctionalInterface
    private interface SectionWriter {
        void write(Writer writer) throws Exception;
    }

    private static void writeFile(Path file, String header, SectionWriter body) throws Exception {
        try (Writer writer = newWriter(file)) {
            writer.write(header);
            body.write(writer);
        }
    }

    static Writer newWriter(Path file) throws IOException {
        return new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), 1 << 16);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete partial dump {}", file);
        }
    }

    /** Two tables can sanitize to the same file name (e.g. "a/b" and "a_b"); number the duplicates. */
    static Map<String, String> uniqueFileNames(List<String> tables) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (String table : tables) {
            String base = Helpers.dumpFileName(table);
            String candidate = base;
            for (int n = 2; !used.add(candidate.toLowerCase()); n++) {
                candidate = base.substring(0, base.length() - 4) + "~" + n + ".sql";
            }
            result.put(table, candidate);
        }
        return result;
    }
}
