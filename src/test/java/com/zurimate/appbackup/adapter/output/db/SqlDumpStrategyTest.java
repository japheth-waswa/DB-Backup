package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.ports.output.DumpSession;
import com.zurimate.appbackup.ports.output.DumpSettings;
import com.zurimate.appbackup.ports.output.TableDumper;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour of the shared relational engine, driven by a scripted fake session. */
class SqlDumpStrategyTest {

    private static final DBConfig CONFIG = new DBConfig(DBType.POSTGRESQL, "h", 5432, "u", "p", "db", 4);

    /** Fake dialect: tables named "bad*" fail, "slow*" sleep so workers overlap. */
    static class FakeStrategy extends SqlDumpStrategy {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger sessionsClosed = new AtomicInteger();
        int failingOpens;
        boolean failPostData;
        List<String> tables = List.of("a", "b", "c");

        @Override
        public Set<DBType> dbTypes() {
            return Set.of(DBType.POSTGRESQL);
        }

        @Override
        protected String restoreInstructions() {
            return "restore me";
        }

        @Override
        protected DumpSession openSession(DBConfig config, DumpSettings settings) {
            return new DumpSession() {
                @Override
                public List<String> tables() {
                    return tables;
                }

                @Override
                public String fileHeader() {
                    return "-- header\n";
                }

                @Override
                public void writePreData(Writer writer) throws java.io.IOException {
                    writer.write("pre\n");
                }

                @Override
                public void writePostData(Writer writer) throws java.io.IOException, SQLException {
                    if (failPostData) throw new SQLException("post boom");
                    writer.write("post\n");
                }

                @Override
                public synchronized TableDumper openWorker() throws SQLException {
                    if (failingOpens > 0) {
                        failingOpens--;
                        throw new SQLException("too many clients already");
                    }
                    opened.incrementAndGet();
                    peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                    return new TableDumper() {
                        @Override
                        public long dumpTable(String table, Writer writer) throws java.io.IOException, SQLException {
                            writer.write("data of " + table + "\n");
                            if (table.startsWith("slow")) {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            if (table.startsWith("bad")) throw new SQLException("relation " + table + " is broken");
                            return table.length();
                        }

                        @Override
                        public void close() {
                            closed.incrementAndGet();
                            live.decrementAndGet();
                        }
                    };
                }

                @Override
                public void close() {
                    sessionsClosed.incrementAndGet();
                }
            };
        }
    }

    private static DumpSettings settings(int maxWorkers) {
        return new DumpSettings(100, 100, 5, maxWorkers);
    }

    @Test
    void writesLayoutManifestAndClosesEverything(@TempDir Path out) throws Exception {
        FakeStrategy strategy = new FakeStrategy();
        DumpReport report = strategy.dump(CONFIG, out, settings(2));

        assertThat(report.complete()).isTrue();
        assertThat(report.objectsTotal()).isEqualTo(3);
        assertThat(report.recordsDumped()).isEqualTo(3);
        assertThat(Files.readString(out.resolve(SqlDumpStrategy.PRE_DATA))).isEqualTo("-- header\npre\n");
        assertThat(Files.readString(out.resolve(SqlDumpStrategy.POST_DATA))).isEqualTo("-- header\npost\n");
        assertThat(Files.readString(out.resolve("tables/b.sql"))).isEqualTo("-- header\ndata of b\n");
        assertThat(Files.readString(out.resolve(DumpManifest.FILE_NAME))).contains("status:     COMPLETE", "restore me", "1  tables/a.sql");
        assertThat(strategy.opened.get()).isEqualTo(strategy.closed.get());
        assertThat(strategy.sessionsClosed.get()).isEqualTo(1);
    }

    @Test
    void failedTableIsReportedRemovedAndWorkerReplaced(@TempDir Path out) throws Exception {
        FakeStrategy strategy = new FakeStrategy();
        strategy.tables = List.of("a", "bad_table", "c", "d");
        DumpReport report = strategy.dump(new DBConfig(DBType.POSTGRESQL, "h", 1, "u", "p", "db", 1), out, settings(1));

        assertThat(report.failures()).containsOnlyKeys("bad_table");
        assertThat(report.failures().get("bad_table")).contains("is broken");
        assertThat(out.resolve("tables/bad_table.sql")).doesNotExist();
        assertThat(out.resolve("tables/d.sql")).exists();
        assertThat(strategy.opened.get()).as("a fresh connection after the failure").isEqualTo(2);
        assertThat(strategy.closed.get()).isEqualTo(2);
        assertThat(Files.readString(out.resolve(DumpManifest.FILE_NAME))).contains("INCOMPLETE", "FAILED  bad_table");
    }

    @Test
    void parallelismIsCappedByTheSmallestLimit(@TempDir Path out) throws Exception {
        FakeStrategy strategy = new FakeStrategy();
        strategy.tables = List.of("slow1", "slow2", "slow3", "slow4", "slow5", "slow6");
        strategy.dump(CONFIG, out, settings(2)); // tableBatchesOf=4 but only 2 workers allowed
        assertThat(strategy.peak.get()).isEqualTo(2);

        FakeStrategy single = new FakeStrategy();
        single.tables = strategy.tables;
        single.dump(new DBConfig(DBType.POSTGRESQL, "h", 1, "u", "p", "db", 1), out.resolve("x"), settings(5));
        assertThat(single.peak.get()).isEqualTo(1);
    }

    @Test
    void givesUpOnTablesWhenConnectionsCannotBeOpened(@TempDir Path out) throws Exception {
        FakeStrategy strategy = new FakeStrategy();
        strategy.failingOpens = 10;
        strategy.tables = List.of("a", "b", "c", "d", "e");
        DumpReport report = strategy.dump(new DBConfig(DBType.POSTGRESQL, "h", 1, "u", "p", "db", 1), out, settings(1));

        assertThat(report.failures()).hasSize(5);
        assertThat(report.failures().get("a")).contains("too many clients");
        assertThat(report.failures().get("e")).contains("could not keep a database connection");
        assertThat(strategy.failingOpens).as("stops retrying after three attempts").isEqualTo(7);
    }

    @Test
    void postDataFailureMarksDumpIncomplete(@TempDir Path out) throws Exception {
        FakeStrategy strategy = new FakeStrategy();
        strategy.failPostData = true;
        DumpReport report = strategy.dump(CONFIG, out, settings(2));
        assertThat(report.failures()).containsOnlyKeys("<post-data>");
    }

    @Test
    void emptyDatabaseStillProducesARestorableDump(@TempDir Path out) throws Exception {
        FakeStrategy strategy = new FakeStrategy();
        strategy.tables = List.of();
        DumpReport report = strategy.dump(CONFIG, out, settings(2));
        assertThat(report.complete()).isTrue();
        assertThat(strategy.opened.get()).isZero();
        assertThat(out.resolve(SqlDumpStrategy.POST_DATA)).exists();
    }

    @Test
    void sessionFailurePropagates(@TempDir Path out) {
        SqlDumpStrategy broken = new FakeStrategy() {
            @Override
            protected DumpSession openSession(DBConfig config, DumpSettings settings) {
                throw new IllegalStateException("connection refused");
            }
        };
        assertThatThrownBy(() -> broken.dump(CONFIG, out, settings(1))).hasMessageContaining("connection refused");
    }

    @Test
    void fileNamesAreSafeAndUnique() {
        assertThat(SqlDumpStrategy.uniqueFileNames(List.of("a/b", "a_b", "A_B", "../etc", "tabReport Filter")))
                .containsEntry("a/b", "a_b.sql")
                .containsEntry("a_b", "a_b~2.sql")
                .containsEntry("A_B", "A_B~3.sql")
                .containsEntry("../etc", "_.._etc.sql")
                .containsEntry("tabReport Filter", "tabReport Filter.sql");
    }
}
