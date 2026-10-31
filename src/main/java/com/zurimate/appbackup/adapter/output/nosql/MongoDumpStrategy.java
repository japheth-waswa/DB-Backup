package com.zurimate.appbackup.adapter.output.nosql;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.zurimate.appbackup.adapter.output.db.DumpManifest;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DatabaseDumpStrategy;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.ports.output.DumpSettings;
import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.DbConnectionFactory;
import com.zurimate.appbackup.utils.Helpers;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonBinary;
import org.bson.BsonBinarySubType;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB dump in the on-disk layout of {@code mongodump}: {@code <db>/<collection>.bson} holding
 * the raw documents and {@code <collection>.metadata.json} holding options and indexes. The result
 * restores with the standard {@code mongorestore} tool.
 * <p>
 * Like {@code mongodump} without {@code --oplog}, collections are read one after another and are
 * not a single point-in-time snapshot of the database.
 */
@Slf4j
@Component
class MongoDumpStrategy implements DatabaseDumpStrategy {
    private static final JsonWriterSettings CANONICAL = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

    @Override
    public Set<DBType> dbTypes() {
        return Set.of(DBType.MONGODB);
    }

    @Override
    public DumpReport dump(DBConfig config, Path outputDir, DumpSettings settings) throws Exception {
        Instant startedAt = Instant.now();
        Path dbDir = Files.createDirectories(outputDir.resolve(Helpers.dumpFileName(config.dbName()).replaceAll("\\.sql$", "")));
        int workers = Math.max(1, Math.min(settings.maxWorkers(), config.parallelism()));

        try (MongoClient client = MongoClients.create(clientSettings(config, settings, workers))) {
            MongoDatabase database = client.getDatabase(config.dbName());
            List<BsonDocument> collections = new ArrayList<>();
            for (BsonDocument info : database.listCollections(BsonDocument.class)) {
                String name = info.getString("name").getValue();
                if (!name.startsWith("system.")) collections.add(info);
            }
            collections.sort((a, b) -> a.getString("name").getValue().compareTo(b.getString("name").getValue()));
            log.info("Dumping {} collections from MongoDB {}:{}/{}", collections.size(), config.hostOrDefault(), config.port(), config.dbName());

            SortedMap<String, String> failures = new ConcurrentSkipListMap<>();
            Map<String, Long> counts = new ConcurrentSkipListMap<>();
            ConcurrentLinkedQueue<BsonDocument> queue = new ConcurrentLinkedQueue<>(collections);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < Math.min(workers, Math.max(1, collections.size())); i++) {
                    executor.submit(() -> {
                        BsonDocument info;
                        while ((info = queue.poll()) != null) {
                            String name = info.getString("name").getValue();
                            try {
                                counts.put(name, dumpCollection(database, info, dbDir, settings));
                            } catch (Exception e) {
                                log.error("Failed to dump collection {}", name, e);
                                failures.put(name, Helpers.rootMessage(e));
                            }
                        }
                    });
                }
            }

            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            Map<String, String> entries = new LinkedHashMap<>();
            for (BsonDocument info : collections) {
                String name = info.getString("name").getValue();
                Long count = counts.get(name);
                entries.put(name, count == null ? null : count + "  " + dbDir.getFileName() + "/" + fileBase(name) + ".bson");
            }
            DumpManifest.write(outputDir.resolve(DumpManifest.FILE_NAME), config, startedAt, "collections", entries, total,
                    failures, """
                            mongorestore --uri "mongodb://<user>:<password>@<host>:<port>/?authSource=admin" --dir . --drop
                            # into another database:  --nsFrom '%1$s.*' --nsTo '<target>.*'
                            """.formatted(config.dbName()));
            return new DumpReport(collections.size(), total, failures);
        }
    }

    private long dumpCollection(MongoDatabase database, BsonDocument info, Path dbDir, DumpSettings settings) throws IOException {
        String name = info.getString("name").getValue();
        String type = info.getString("type", new BsonString("collection")).getValue();
        BsonDocument options = info.getDocument("options", new BsonDocument());
        if ("timeseries".equals(type) || options.containsKey("timeseries")) {
            throw new UnsupportedOperationException("time-series collections are not supported yet; use mongodump for them");
        }

        BsonDocument metadata = new BsonDocument()
                .append("options", options)
                .append("collectionName", new BsonString(name))
                .append("type", new BsonString(type));
        BsonValue uuid = info.getDocument("info", new BsonDocument()).get("uuid");
        if (uuid instanceof BsonBinary binary && binary.getType() == BsonBinarySubType.UUID_STANDARD.getValue()) {
            metadata.append("uuid", new BsonString(HexFormat.of().formatHex(binary.getData())));
        }

        long count = 0;
        if ("view".equals(type)) {
            metadata.append("indexes", new org.bson.BsonArray());
        } else {
            org.bson.BsonArray indexes = new org.bson.BsonArray();
            database.getCollection(name, BsonDocument.class).listIndexes(BsonDocument.class).forEach(indexes::add);
            metadata.append("indexes", indexes);

            Path bsonFile = dbDir.resolve(fileBase(name) + ".bson");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(bsonFile), 1 << 16);
                 MongoCursor<RawBsonDocument> cursor = database.getCollection(name, RawBsonDocument.class)
                         .find().batchSize(settings.fetchSize()).noCursorTimeout(true).iterator()) {
                while (cursor.hasNext()) {
                    ByteBuffer bytes = cursor.next().getByteBuffer().asNIO();
                    if (bytes.hasArray()) {
                        out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
                    } else {
                        byte[] copy = new byte[bytes.remaining()];
                        bytes.get(copy);
                        out.write(copy);
                    }
                    count++;
                }
            }
        }
        Files.writeString(dbDir.resolve(fileBase(name) + ".metadata.json"), metadata.toJson(CANONICAL), StandardCharsets.UTF_8);
        log.debug("Dumped collection {} ({} documents)", name, count);
        return count;
    }

    /** Same escaping as mongodump: names are percent-encoded only where the file system needs it. */
    static String fileBase(String collection) {
        StringBuilder sb = new StringBuilder();
        for (char c : collection.toCharArray()) {
            if (c == '/' || c == '\\' || c == '%' || c == ':' || c < 0x20) {
                sb.append(URLEncoder.encode(String.valueOf(c), StandardCharsets.UTF_8));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static MongoClientSettings clientSettings(DBConfig config, DumpSettings settings, int workers) {
        StringBuilder uri = new StringBuilder("mongodb://");
        if (config.username() != null && !config.username().isBlank()) {
            uri.append(URLEncoder.encode(config.username(), StandardCharsets.UTF_8));
            if (config.password() != null) uri.append(':').append(URLEncoder.encode(config.password(), StandardCharsets.UTF_8));
            uri.append('@');
        }
        uri.append(config.hostOrDefault()).append(':').append(config.port()).append("/?");
        Map<String, String> options = DbConnectionFactory.parseOptions(config.options());
        options.putIfAbsent("directConnection", "true");
        options.forEach((k, v) -> uri.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));

        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri.toString()))
                .applicationName(DbConnectionFactory.APPLICATION_NAME)
                .applyToSocketSettings(s -> s.connectTimeout(settings.connectTimeoutSeconds(), TimeUnit.SECONDS))
                .applyToClusterSettings(s -> s.serverSelectionTimeout(settings.connectTimeoutSeconds(), TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(s -> s.maxSize(workers + 1).minSize(0))
                .build();
    }
}
