package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static com.zurimate.appbackup.adapter.output.db.DumpTestSupport.exec;
import static com.zurimate.appbackup.adapter.output.db.DumpTestSupport.single;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers what broke earlier ERPNext backups: table names with spaces, unquoted datetimes,
 * backslashes, binary data and OFFSET paging. Runs against MariaDB (ERPNext) and MySQL 8.
 */
@Testcontainers(disabledWithoutDocker = true)
class MysqlDumpRestoreTest {
    private static final List<String> TABLES = List.of("tabReport Filter", "parent", "child", "big");

    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:10.6");
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @BeforeAll
    static void start() {
        Stream.of(MARIADB, MYSQL).parallel().forEach(JdbcDatabaseContainer::start);
    }

    @AfterAll
    static void stop() {
        Stream.of(MARIADB, MYSQL).forEach(JdbcDatabaseContainer::stop);
    }

    static Stream<JdbcDatabaseContainer<?>> engines() {
        return Stream.of(MARIADB, MYSQL);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void dumpRestoresToIdenticalDatabase(JdbcDatabaseContainer<?> db, @TempDir Path tmp) throws Exception {
        seed(db);
        DBType type = db == MARIADB ? DBType.MARIADB : DBType.MYSQL;
        DBConfig config = new DBConfig(type, db.getHost(), db.getFirstMappedPort(), db.getUsername(), db.getPassword(),
                db.getDatabaseName(), 3);

        DumpReport report = DumpTestSupport.dumper(new MysqlDumpStrategy(), DumpTestSupport.properties(3, 100))
                .dump(config, tmp.resolve("db"));

        assertThat(report.failures()).isEmpty();
        assertThat(report.objectsTotal()).isEqualTo(TABLES.size());
        assertThat(report.recordsDumped()).isEqualTo(3 + 2 + 2 + 2500);
        try (Connection root = connect(db, "")) {
            assertThat(single(root, "SELECT count(*) FROM information_schema.PROCESSLIST WHERE USER = '" + db.getUsername() + "'"))
                    .as("connections left open after the dump").isEqualTo("0");
            exec(root, "CREATE DATABASE restored");
        }

        StringBuilder files = new StringBuilder();
        int n = 0;
        for (Path file : DumpTestSupport.restoreOrder(tmp.resolve("db"))) {
            String target = "/tmp/r%03d.sql".formatted(n++);
            db.copyFileToContainer(MountableFile.forHostPath(file), target);
            files.append(target).append(' ');
        }
        Container.ExecResult result = db.execInContainer("sh", "-c",
                "cat " + files + " | mysql -uroot -p" + db.getPassword() + " restored");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();

        try (Connection source = connect(db, db.getDatabaseName()); Connection restored = connect(db, "restored")) {
            for (String table : TABLES) {
                String quoted = "`" + table + "`";
                assertThat(single(restored, "SELECT count(*) FROM " + quoted)).as("rows of " + table)
                        .isEqualTo(single(source, "SELECT count(*) FROM " + quoted));
                assertThat(checksum(restored, quoted)).as("checksum of " + table).isEqualTo(checksum(source, quoted));
            }
            assertThat(single(restored, "SELECT full_name FROM `tabReport Filter` WHERE name = 'quote''s'"))
                    .as("virtual columns are recomputed").isEqualTo("quote's!");
            assertThat(single(restored, "SELECT count(*) FROM v_child")).isEqualTo("2");
            exec(restored, "INSERT INTO child (parent_id) VALUES (1)");
            assertThat(single(restored, "SELECT max(id) FROM child")).as("AUTO_INCREMENT continues").isEqualTo("11");
            assertThat(single(restored, "SELECT note FROM child WHERE id = 11")).as("trigger restored").isEqualTo("from trigger");
            assertThatThrownBy(() -> exec(restored, "INSERT INTO child (parent_id) VALUES (999)"))
                    .as("foreign keys are enforced").hasMessageContaining("foreign key");
        }
    }

    private static void seed(JdbcDatabaseContainer<?> db) throws SQLException {
        try (Connection c = connect(db, db.getDatabaseName())) {
            exec(c,
                    """
                    CREATE TABLE `tabReport Filter` (
                      name varchar(140) PRIMARY KEY,
                      creation datetime(6),
                      modified timestamp(6) NULL,
                      docstatus int,
                      data longtext,
                      flag tinyint(1),
                      amount decimal(18,6),
                      ratio double,
                      blobby longblob,
                      bits bit(8),
                      yr year,
                      d date,
                      t time(3),
                      j json,
                      e enum('a','b'),
                      full_name varchar(300) AS (concat(name, '!')) VIRTUAL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
                    "CREATE TABLE parent (id int PRIMARY KEY)",
                    """
                    CREATE TABLE child (id int PRIMARY KEY AUTO_INCREMENT, parent_id int, note varchar(50),
                      FOREIGN KEY (parent_id) REFERENCES parent(id)) AUTO_INCREMENT = 10""",
                    "CREATE TABLE big (id int PRIMARY KEY, label varchar(40))",
                    "CREATE VIEW v_child AS SELECT * FROM child",
                    "CREATE TRIGGER trg_child BEFORE INSERT ON child FOR EACH ROW SET NEW.note = coalesce(NEW.note, 'from trigger')",
                    """
                    INSERT INTO `tabReport Filter` (name, creation, modified, docstatus, data, flag, amount, ratio, blobby, bits, yr, d, t, j, e)
                    VALUES ('quote''s', '2026-06-03 23:56:18.451137', '2026-06-27 02:18:11.558203', 1,
                            'back\\\\slash\\nnew line\\ttab 😀 \\\\0', 1, -12.500001, 1.5e-7, x'00ff0d0a275c', b'10100101',
                            2024, '2024-02-29', '13:45:01.250', '{"k": "v''s"}', 'b'),
                           ('nulls', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                           ('empty', '1970-01-01 00:00:01', '1970-01-01 00:00:01', 0, '', 0, 0, 0, '', b'0', 1901, '1000-01-01', '00:00:00', '[]', 'a')""",
                    "INSERT INTO parent VALUES (1), (2)",
                    "INSERT INTO child (id, parent_id, note) VALUES (1, 1, 'one'), (10, 2, NULL)");
            StringBuilder big = new StringBuilder("INSERT INTO big VALUES ");
            for (int i = 1; i <= 2500; i++) big.append(i > 1 ? "," : "").append("(").append(i).append(", 'row ").append(i).append("')");
            exec(c, big.toString());
        }
    }

    private static String checksum(Connection c, String table) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery("CHECKSUM TABLE " + table)) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    private static Connection connect(JdbcDatabaseContainer<?> db, String database) throws SQLException {
        String url = "jdbc:mysql://%s:%d/%s?allowPublicKeyRetrieval=true&useSSL=false".formatted(db.getHost(), db.getFirstMappedPort(), database);
        return DriverManager.getConnection(url, "root", db.getPassword());
    }
}
