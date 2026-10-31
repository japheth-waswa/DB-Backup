package com.zurimate.appbackup.adapter.output.nosql;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.CreateViewOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.ports.output.DumpSettings;
import com.zurimate.appbackup.utils.DBType;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MongoDumpRestoreTest {

    @org.testcontainers.junit.jupiter.Container
    static final MongoDBContainer DB = new MongoDBContainer("mongo:7.0");

    @BeforeAll
    static void seed() {
        try (MongoClient client = MongoClients.create(DB.getConnectionString())) {
            MongoDatabase db = client.getDatabase("shop");
            db.getCollection("customers").insertMany(List.of(
                    new Document("_id", new ObjectId()).append("email", "a@x.io")
                            .append("name", "O'Brien \"q\" 😀\nline two")
                            .append("balance", new Decimal128(new java.math.BigDecimal("-12.50")))
                            .append("created", new Date(1_780_000_000_000L))
                            .append("tags", List.of("a", "b"))
                            .append("avatar", new Binary(new byte[]{0, (byte) 255, 13, 10}))
                            .append("address", new Document("city", "Nairobi").append("geo", List.of(36.82, -1.29))),
                    new Document("email", "b@x.io").append("missing", null)));
            db.getCollection("customers").createIndex(Indexes.ascending("email"), new IndexOptions().unique(true).name("uq_email"));
            db.getCollection("customers").createIndex(Indexes.compoundIndex(Indexes.ascending("address.city"), Indexes.descending("created")));
            db.getCollection("sessions").createIndex(Indexes.ascending("seenAt"), new IndexOptions().expireAfter(3600L, TimeUnit.SECONDS));
            List<Document> events = new ArrayList<>();
            for (int i = 0; i < 2500; i++) events.add(new Document("n", i).append("payload", "event " + i));
            db.createCollection("events", new CreateCollectionOptions().capped(true).sizeInBytes(10_000_000));
            db.getCollection("events").insertMany(events);
            db.createView("rich_customers", "customers", List.of(new Document("$match", new Document("balance", new Document("$gt", 0)))),
                    new CreateViewOptions());
        }
    }

    @Test
    void dumpRestoresWithMongorestore(@TempDir Path tmp) throws Exception {
        DBConfig config = new DBConfig(DBType.MONGODB, DB.getHost(), DB.getFirstMappedPort(), null, null, "shop", 2);
        Path out = tmp.resolve("db");
        DumpReport report = new MongoDumpStrategy().dump(config, Files.createDirectories(out), settings());

        assertThat(report.failures()).isEmpty();
        assertThat(report.objectsTotal()).isEqualTo(4);
        assertThat(report.recordsDumped()).isEqualTo(2 + 2500);

        try (Stream<Path> files = Files.list(out.resolve("shop"))) {
            for (Path file : files.toList()) {
                DB.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/dump/shop/" + file.getFileName());
            }
        }
        Container.ExecResult result = DB.execInContainer("mongorestore", "--quiet", "--nsFrom", "shop.*", "--nsTo", "restored.*",
                "--dir", "/tmp/dump");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();

        try (MongoClient client = MongoClients.create(DB.getConnectionString())) {
            MongoDatabase source = client.getDatabase("shop");
            MongoDatabase restored = client.getDatabase("restored");
            for (String name : List.of("customers", "events", "sessions")) {
                assertThat(documents(restored, name)).as(name).isEqualTo(documents(source, name));
                assertThat(indexes(restored, name)).as("indexes of " + name).isEqualTo(indexes(source, name));
            }
            Document options = restored.listCollections().filter(new Document("name", "events")).first();
            assertThat(options.get("options", Document.class).getBoolean("capped")).isTrue();
            assertThat(restored.getCollection("rich_customers").countDocuments()).isZero();
            assertThat(restored.listCollections().filter(new Document("name", "rich_customers")).first().getString("type"))
                    .isEqualTo("view");
        }
    }

    private static List<BsonDocument> documents(MongoDatabase db, String collection) {
        return db.getCollection(collection, BsonDocument.class).find().sort(new Document("_id", 1)).into(new ArrayList<>());
    }

    private static List<Map<String, Object>> indexes(MongoDatabase db, String collection) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document index : db.getCollection(collection).listIndexes()) {
            result.add(Map.of("name", index.getString("name"), "key", index.get("key").toString(),
                    "unique", index.getBoolean("unique", false), "ttl", String.valueOf(index.get("expireAfterSeconds"))));
        }
        result.sort((a, b) -> a.get("name").toString().compareTo(b.get("name").toString()));
        return result;
    }

    private static DumpSettings settings() {
        return new DumpSettings(100, 500, 10, 2);
    }
}
