package com.zurimate.appbackup.adapter.output.nosql;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.ports.output.DumpSettings;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisDumpRestoreTest {
    private static final String PASSWORD = "s3cret-pass";

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4")
            .withCommand("redis-server", "--requirepass", PASSWORD)
            .withExposedPorts(6379);

    @BeforeAll
    static void seed() {
        try (Jedis jedis = jedis()) {
            jedis.select(0);
            jedis.set("plain", "value with spaces\r\nand CRLF");
            jedis.set("binary\u0000key".getBytes(StandardCharsets.UTF_8), new byte[]{0, (byte) 255, 13, 10});
            jedis.setex("expiring", 3600, "soon");
            jedis.hset("user:1", Map.of("name", "O'Brien", "email", "a@x.io"));
            jedis.rpush("queue", "a", "b", "c");
            jedis.sadd("tags", "x", "y");
            jedis.zadd("scores", Map.of("alice", 10.5, "bob", 3.0));
            jedis.xadd("events", redis.clients.jedis.StreamEntryID.NEW_ENTRY, Map.of("type", "created"));
            for (int i = 0; i < 2500; i++) jedis.set("bulk:" + i, "v" + i);
        }
    }

    @Test
    void dumpRestoresWithRedisCliPipe(@TempDir Path tmp) throws Exception {
        DBConfig config = new DBConfig(DBType.REDIS, REDIS.getHost(), REDIS.getFirstMappedPort(), null, PASSWORD, "0", 1);
        Path out = Files.createDirectories(tmp.resolve("db"));
        DumpReport report = new RedisDumpStrategy().dump(config, out, new DumpSettings(200, 500, 10, 1));

        assertThat(report.failures()).isEmpty();
        assertThat(report.recordsDumped()).isEqualTo(2508);

        REDIS.copyFileToContainer(MountableFile.forHostPath(out.resolve(RedisDumpStrategy.DUMP_FILE)), "/tmp/keys.resp");
        Container.ExecResult result = REDIS.execInContainer("sh", "-c",
                "redis-cli -a " + PASSWORD + " --no-auth-warning -n 1 --pipe < /tmp/keys.resp");
        assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
        assertThat(result.getStdout()).contains("errors: 0");

        try (Jedis jedis = jedis()) {
            assertThat(snapshot(jedis, 1)).isEqualTo(snapshot(jedis, 0));
            jedis.select(1);
            assertThat(jedis.ttl("expiring")).as("TTL survives").isBetween(3500L, 3600L);
            assertThat(jedis.ttl("plain")).as("persistent keys stay persistent").isEqualTo(-1L);
            assertThat(jedis.clientList()).doesNotContain("name=AppBackup");
        }
    }

    /** key -> DUMP payload; identical payloads mean identical type, encoding and value. */
    private static Map<String, String> snapshot(Jedis jedis, int db) {
        jedis.select(db);
        Map<String, String> result = new TreeMap<>();
        byte[] cursor = ScanParams.SCAN_POINTER_START_BINARY;
        do {
            ScanResult<byte[]> page = jedis.scan(cursor);
            cursor = page.getCursorAsBytes();
            for (byte[] key : page.getResult()) {
                result.put(HexFormat.of().formatHex(key), HexFormat.of().formatHex(jedis.dump(key)));
            }
        } while (!java.util.Arrays.equals(cursor, ScanParams.SCAN_POINTER_START_BINARY));
        return result;
    }

    private static Jedis jedis() {
        Jedis jedis = new Jedis(REDIS.getHost(), REDIS.getFirstMappedPort());
        jedis.auth(PASSWORD);
        return jedis;
    }
}
