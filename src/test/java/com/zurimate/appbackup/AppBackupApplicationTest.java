package com.zurimate.appbackup;

import com.zurimate.appbackup.security.ApiKeyFilter;
import com.zurimate.appbackup.security.SecretCipher;
import com.zurimate.appbackup.service.backup.ArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the whole application against a real PostgreSQL (metadata store and backup source)
 * and drives it through the HTTP API the way an operator would.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppBackupApplicationTest {
    private static final String API_KEY = "test-api-key";
    private static final String DB_PASSWORD = "Source-Pa55";

    @TempDir
    static Path tmp;

    @ServiceConnection
    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("app.security.api-key", () -> API_KEY);
        registry.add("app.security.encryption-key", () -> Base64.getEncoder().encodeToString(key));
        registry.add("app.backup.work-dir", () -> tmp.resolve("work").toString());
        registry.add("app.backup.run-on-startup", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ArchiveService archives;

    private RestClient client(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:" + port);
        if (apiKey != null) builder.defaultHeader(ApiKeyFilter.HEADER, apiKey);
        return builder.build();
    }

    @Test
    void registersRunsAndReportsABackupEndToEnd() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            c.createStatement().execute("CREATE ROLE shop_reader LOGIN PASSWORD '" + DB_PASSWORD + "'");
            c.createStatement().execute("CREATE TABLE IF NOT EXISTS shop_items (id serial PRIMARY KEY, name text)");
            c.createStatement().execute("INSERT INTO shop_items (name) SELECT 'item ' || g FROM generate_series(1, 50) g");
            c.createStatement().execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO shop_reader");
            c.createStatement().execute("GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO shop_reader");
        }

        // health is open, everything else needs the key
        assertThat(client(null).get().uri("/actuator/health").retrieve().body(String.class)).contains("UP");
        assertThatThrownBy(() -> client(null).get().uri("/backups").retrieve().body(String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> client("wrong").get().uri("/backups").retrieve().body(String.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);

        Path destination = tmp.resolve("backups/shop");
        Map<?, ?> created = client(API_KEY).post().uri("/backup").contentType(MediaType.APPLICATION_JSON).body(Map.of(
                "schedulerType", "DAILY",
                "destination", destination.toString(),
                "dbConfig", Map.of("dbType", "POSTGRESQL", "host", PG.getHost(), "port", PG.getFirstMappedPort(),
                        "username", "shop_reader", "password", DB_PASSWORD, "dbName", PG.getDatabaseName(),
                        "tableBatchesOf", 2))).retrieve().body(Map.class);
        String id = ((Map<?, ?>) created.get("data")).get("id").toString();

        // credentials are encrypted at rest and never returned
        String stored = jdbc.queryForObject("SELECT password FROM backups WHERE id = ?::uuid", String.class, id);
        assertThat(stored).startsWith(SecretCipher.PREFIX).doesNotContain(DB_PASSWORD);
        assertThat(client(API_KEY).get().uri("/backups").retrieve().body(String.class)).doesNotContain(DB_PASSWORD).contains(id);

        Map<?, ?> run = awaitFinishedRun(id);
        assertThat(run.get("status")).as(String.valueOf(run.get("message"))).isEqualTo("SUCCESS");
        assertThat(run.get("triggeredBy") == null ? run.get("trigger") : run.get("triggeredBy")).isNotNull();
        Path archive = Path.of(run.get("dbArchive").toString());
        assertThat(archive).exists().startsWith(destination);
        assertThat(archives.sha256(archive)).isEqualTo(run.get("dbArchiveSha256"));
        assertThat(Files.readString(Path.of(archive + ".sha256"))).startsWith(run.get("dbArchiveSha256").toString());

        Integer leftover = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'AppBackup'", Integer.class);
        assertThat(leftover).as("source connections closed after the run").isZero();

        client(API_KEY).delete().uri("/backups/{id}", id).retrieve().toBodilessEntity();
        assertThat(archive).as("deleting a job keeps its archives").exists();
        assertThatThrownBy(() -> client(API_KEY).get().uri("/backups/{id}", id).retrieve().body(String.class))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> awaitFinishedRun(String id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            Map<?, ?> body = client(API_KEY).get().uri("/backups/{id}/runs", id).retrieve().body(Map.class);
            List<Map<?, ?>> runs = (List<Map<?, ?>>) body.get("data");
            if (!runs.isEmpty() && !"RUNNING".equals(runs.getFirst().get("status"))) return runs.getFirst();
            Thread.sleep(250);
        }
        throw new AssertionError("backup did not finish in time");
    }
}
