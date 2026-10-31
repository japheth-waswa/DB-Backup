package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static com.zurimate.appbackup.adapter.output.db.DumpTestSupport.exec;
import static com.zurimate.appbackup.adapter.output.db.DumpTestSupport.rows;
import static com.zurimate.appbackup.adapter.output.db.DumpTestSupport.single;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MssqlDumpRestoreTest {
    private static final String OPTIONS = "encrypt=true;trustServerCertificate=true";

    /** Columns compared per table; rowversion values are regenerated on insert by design. */
    private static final Map<String, String> TABLES = Map.of(
            "dbo.customers", "SELECT id, email, name, notes, balance, ratio, created, legacy, offset_at, birthday, at_time, "
                    + "active, ext, avatar, CAST(doc AS nvarchar(max)), price, total FROM dbo.customers ORDER BY id",
            "sales.Order Items", "SELECT * FROM sales.[Order Items] ORDER BY id",
            "dbo.empty_table", "SELECT * FROM dbo.empty_table");

    @org.testcontainers.junit.jupiter.Container
    static final MSSQLServerContainer<?> DB = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection master = connect("master")) {
            exec(master, "CREATE DATABASE shop", "ALTER DATABASE shop SET ALLOW_SNAPSHOT_ISOLATION ON", "CREATE DATABASE restored");
        }
        try (Connection c = connect("shop")) {
            exec(c,
                    "CREATE SCHEMA sales",
                    "CREATE SEQUENCE dbo.invoice_seq AS bigint START WITH 100 INCREMENT BY 5",
                    """
                    CREATE TABLE dbo.customers (
                      id int IDENTITY(10, 2) PRIMARY KEY,
                      email nvarchar(200) NOT NULL CONSTRAINT uq_email UNIQUE,
                      name nvarchar(max),
                      notes varchar(100) COLLATE Latin1_General_CI_AS,
                      balance decimal(12, 2) CONSTRAINT df_balance DEFAULT 0 CONSTRAINT ck_balance CHECK (balance >= -1000),
                      ratio float,
                      created datetime2(7),
                      legacy datetime,
                      offset_at datetimeoffset(3),
                      birthday date,
                      at_time time(3),
                      active bit,
                      ext uniqueidentifier,
                      avatar varbinary(max),
                      doc xml,
                      price money,
                      rv rowversion,
                      total AS (balance * 2) PERSISTED)""",
                    """
                    CREATE TABLE sales.[Order Items] (
                      id bigint IDENTITY PRIMARY KEY,
                      customer_id int NOT NULL CONSTRAINT fk_items_customer REFERENCES dbo.customers(id) ON DELETE CASCADE,
                      qty int,
                      note nvarchar(100))""",
                    "CREATE TABLE dbo.empty_table (id int)",
                    "CREATE INDEX ix_note ON sales.[Order Items](note) INCLUDE (qty) WHERE note IS NOT NULL",
                    "CREATE VIEW dbo.v_active AS SELECT id, email FROM dbo.customers WHERE active = 1",
                    "CREATE PROCEDURE dbo.p_count AS SELECT COUNT(*) FROM dbo.customers",
                    """
                    CREATE TRIGGER sales.trg_items ON sales.[Order Items] AFTER INSERT AS
                    BEGIN SET NOCOUNT ON; UPDATE i SET note = N'from trigger' FROM sales.[Order Items] i
                    JOIN inserted n ON n.id = i.id WHERE i.note IS NULL; END""",
                    """
                    INSERT INTO dbo.customers (email, name, notes, balance, ratio, created, legacy, offset_at, birthday, at_time,
                                               active, ext, avatar, doc, price)
                    VALUES (N'a@x.io', N'O''Brien \\ "q" 😀' + CHAR(13) + CHAR(10) + N'line two', 'plain', -12.5, 1.5E-7,
                            '2026-06-03T23:56:18.4511372', '2024-02-29T13:45:01.250', '2026-06-03T23:56:18.451+03:00',
                            '1990-02-28', '13:45:01.250', 1, NEWID(), 0x00FF0D0A27, N'<a k="v''s">text</a>', 12.3456),
                           (N'b@x.io', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, NULL)""",
                    """
                    INSERT INTO sales.[Order Items] (customer_id, qty, note)
                    SELECT TOP 2500 10, ROW_NUMBER() OVER (ORDER BY (SELECT 1)), N'n'
                    FROM sys.all_objects a CROSS JOIN sys.all_objects b""",
                    "SELECT NEXT VALUE FOR dbo.invoice_seq");
        }
    }

    @Test
    void dumpRestoresToIdenticalDatabase(@TempDir Path tmp) throws Exception {
        DBConfig config = new DBConfig(DBType.MSSQL, DB.getHost(), DB.getFirstMappedPort(), DB.getUsername(), DB.getPassword(),
                "shop", 3, OPTIONS);
        DumpReport report = DumpTestSupport.dumper(new MssqlDumpStrategy(), DumpTestSupport.properties(3, 400))
                .dump(config, tmp.resolve("db"));

        assertThat(report.failures()).isEmpty();
        assertThat(report.objectsTotal()).isEqualTo(TABLES.size());
        try (Connection master = connect("master")) {
            assertThat(single(master, "SELECT count(*) FROM sys.dm_exec_sessions WHERE program_name = 'AppBackup'"))
                    .as("connections left open after the dump").isEqualTo("0");
        }

        String sqlcmd = DB.execInContainer("sh", "-c", "ls /opt/mssql-tools*/bin/sqlcmd | tail -1").getStdout().strip();
        int n = 0;
        for (Path file : DumpTestSupport.restoreOrder(tmp.resolve("db"))) {
            String target = "/tmp/r%03d.sql".formatted(n++);
            DB.copyFileToContainer(MountableFile.forHostPath(file), target);
            Container.ExecResult result = DB.execInContainer(sqlcmd, "-S", "localhost", "-U", DB.getUsername(),
                    "-P", DB.getPassword(), "-d", "restored", "-b", "-C", "-i", target);
            assertThat(result.getExitCode()).as("restore of %s: %s %s", file.getFileName(), result.getStdout(), result.getStderr())
                    .isZero();
        }

        try (Connection source = connect("shop"); Connection restored = connect("restored")) {
            for (Map.Entry<String, String> table : TABLES.entrySet()) {
                assertThat(rows(restored, table.getValue())).as(table.getKey()).isEqualTo(rows(source, table.getValue()));
            }
            assertThat(single(restored, "SELECT count(*) FROM dbo.v_active")).isEqualTo("1");
            assertThat(single(restored, "SELECT NEXT VALUE FOR dbo.invoice_seq")).as("sequence continues").isEqualTo("105");
            exec(restored, "INSERT INTO dbo.customers (email) VALUES (N'new@x.io')");
            assertThat(single(restored, "SELECT max(id) FROM dbo.customers")).as("identity continues").isEqualTo("14");
            exec(restored, "INSERT INTO sales.[Order Items] (customer_id, qty) VALUES (10, 1)");
            assertThat(single(restored, "SELECT TOP 1 note FROM sales.[Order Items] ORDER BY id DESC")).isEqualTo("from trigger");
            assertThat(single(restored, "SELECT count(*) FROM sys.indexes WHERE name = 'ix_note'")).isEqualTo("1");
            assertThat(single(restored, "SELECT count(*) FROM sys.procedures WHERE name = 'p_count'")).isEqualTo("1");
            assertThatThrownBy(() -> exec(restored, "INSERT INTO sales.[Order Items] (customer_id) VALUES (999)"))
                    .hasMessageContaining("FOREIGN KEY");
        }
    }

    private static Connection connect(String database) throws SQLException {
        String url = "jdbc:sqlserver://%s:%d;databaseName=%s;%s".formatted(DB.getHost(), DB.getFirstMappedPort(), database, OPTIONS);
        return DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
    }
}
