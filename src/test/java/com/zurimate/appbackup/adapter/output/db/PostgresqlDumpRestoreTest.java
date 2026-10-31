package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.service.backup.DatabaseDumper;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.zurimate.appbackup.adapter.output.db.DumpTestSupport.exec;
import static com.zurimate.appbackup.adapter.output.db.DumpTestSupport.single;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dumps a database that exercises the awkward cases (identity, serial, enums, arrays, bytea,
 * generated columns, PostGIS, quoting, NaN, self references) and restores it with psql.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlDumpRestoreTest {
    private static final List<String> TABLES = List.of("customers", "Order Items", "self_ref", "empty_table");

    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = connect("test")) {
            exec(c,
                    "CREATE EXTENSION IF NOT EXISTS postgis",
                    "CREATE TYPE mood AS ENUM ('happy', 'sad', 'it''s complicated')",
                    "CREATE SEQUENCE invoice_seq START 1000",
                    """
                    CREATE TABLE customers (
                      id serial PRIMARY KEY,
                      email text UNIQUE NOT NULL,
                      name text,
                      mood mood,
                      tags text[],
                      profile jsonb,
                      avatar bytea,
                      balance numeric(12,2) CHECK (balance >= -1000),
                      ratio double precision,
                      created_at timestamptz DEFAULT now(),
                      birthday date,
                      active boolean,
                      ext_id uuid,
                      location geometry(Point, 4326))""",
                    """
                    CREATE TABLE "Order Items" (
                      id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                      customer_id int NOT NULL REFERENCES customers(id),
                      qty int,
                      price numeric,
                      total numeric GENERATED ALWAYS AS (qty * price) STORED,
                      invoice_no bigint DEFAULT nextval('invoice_seq'),
                      note text)""",
                    "CREATE INDEX idx_items_note ON \"Order Items\"(note)",
                    "CREATE TABLE self_ref (id int PRIMARY KEY, parent_id int REFERENCES self_ref(id))",
                    "CREATE TABLE empty_table (id int)",
                    "CREATE VIEW active_customers AS SELECT id, email FROM customers WHERE active",
                    """
                    INSERT INTO customers (email, name, mood, tags, profile, avatar, balance, ratio, birthday, active, ext_id, location)
                    VALUES ('a@x.io', 'O''Brien \\ "quoted"' || chr(10) || 'line two 😀', 'it''s complicated',
                            ARRAY['a,b', 'c"d', NULL], '{"k": "v''s", "n": [1, 2]}', '\\x00ff10'::bytea, -12.50, 'NaN',
                            '1990-02-28', true, gen_random_uuid(), ST_SetSRID(ST_MakePoint(36.82, -1.29), 4326)),
                           ('b@x.io', NULL, NULL, '{}', NULL, NULL, NULL, 'Infinity', NULL, false, NULL, NULL)""",
                    """
                    INSERT INTO customers (email, name, balance, ratio, active)
                    SELECT 'bulk' || g || '@x.io', 'bulk ' || g, g % 900, g / 7.0, g % 2 = 0 FROM generate_series(1, 2500) g""",
                    """
                    INSERT INTO "Order Items" (customer_id, qty, price, note)
                    SELECT 1 + (g % 2500), g % 9, g * 1.25, CASE WHEN g % 3 = 0 THEN NULL ELSE 'note ' || g END
                    FROM generate_series(1, 1200) g""",
                    "INSERT INTO self_ref VALUES (1, NULL), (2, 1), (3, 2)");
        }
    }

    @Test
    void dumpRestoresToIdenticalDatabaseAndReleasesEveryConnection(@TempDir Path tmp) throws Exception {
        DatabaseDumper dumper = DumpTestSupport.dumper(new PostgresqlDumpStrategy(), DumpTestSupport.properties(3, 100));
        DBConfig config = new DBConfig(DBType.POSTGRESQL, PG.getHost(), PG.getFirstMappedPort(),
                PG.getUsername(), PG.getPassword(), "test", 3);

        AtomicInteger peak = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofVirtual().start(() -> {
            try (Connection c = connect("postgres")) {
                while (sampling.get()) {
                    peak.accumulateAndGet(appBackupConnections(c), Math::max);
                    Thread.sleep(5);
                }
            } catch (Exception ignored) {
                // sampling is diagnostic only
            }
        });

        Path out = tmp.resolve("db");
        com.zurimate.appbackup.ports.output.DumpReport report = dumper.dump(config, out);
        sampling.set(false);
        sampler.join();

        assertThat(report.failures()).isEmpty();
        assertThat(report.objectsTotal()).as("postgis spatial_ref_sys must not be dumped").isEqualTo(TABLES.size());
        assertThat(peak.get()).as("connections held during the dump").isLessThanOrEqualTo(3);
        try (Connection c = connect("postgres")) {
            assertThat(appBackupConnections(c)).as("connections left open after the dump").isZero();
        }

        restore(out, "restored");

        try (Connection source = connect("test"); Connection restored = connect("restored")) {
            for (String table : TABLES) {
                assertThat(fingerprint(restored, table)).as("data of " + table).isEqualTo(fingerprint(source, table));
            }
            assertThat(single(restored, "SELECT count(*) FROM active_customers"))
                    .isEqualTo(single(source, "SELECT count(*) FROM active_customers"));
            // sequences continue where the source left off
            assertThat(single(restored, "SELECT nextval('invoice_seq')")).isEqualTo(single(source, "SELECT nextval('invoice_seq')"));
            exec(restored, "INSERT INTO customers (email) VALUES ('new@x.io')",
                    "INSERT INTO \"Order Items\" (customer_id, qty, price) VALUES (1, 1, 1)");
            assertThat(single(restored, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_items_note'")).isEqualTo("1");
            assertThatThrownBy(() -> exec(restored, "INSERT INTO self_ref VALUES (9, 999)"))
                    .as("foreign keys are restored").hasMessageContaining("foreign key");
        }
    }

    private static void restore(Path dumpDir, String database) throws Exception {
        try (Connection c = connect("postgres")) {
            exec(c, "CREATE DATABASE \"" + database + "\"");
        }
        List<Path> files = DumpTestSupport.restoreOrder(dumpDir);
        for (Path file : files) {
            String target = "/tmp/restore-" + file.getFileName().toString().replace(' ', '_');
            PG.copyFileToContainer(MountableFile.forHostPath(file), target);
            Container.ExecResult result = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q",
                    "-U", PG.getUsername(), "-d", database, "-f", target);
            assertThat(result.getExitCode()).as("restore of %s: %s", file.getFileName(), result.getStderr()).isZero();
        }
    }

    private static String fingerprint(Connection c, String table) throws SQLException {
        String ident = "\"" + table.replace("\"", "\"\"") + "\"";
        return single(c, "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, E'\\n' ORDER BY t::text)), '') FROM " + ident + " t");
    }

    private static int appBackupConnections(Connection c) throws SQLException {
        return Integer.parseInt(single(c, "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'AppBackup'"));
    }

    private static Connection connect(String database) throws SQLException {
        String url = "jdbc:postgresql://%s:%d/%s".formatted(PG.getHost(), PG.getFirstMappedPort(), database);
        return DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
    }
}
