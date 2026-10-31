package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.exception.InternalException;
import com.zurimate.appbackup.ports.output.DatabaseDumpStrategy;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.ports.output.DumpSettings;
import com.zurimate.appbackup.utils.DBType;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes a dump to the strategy registered for the database type. */
@Component
public class DatabaseDumper {
    private final Map<DBType, DatabaseDumpStrategy> strategies = new EnumMap<>(DBType.class);
    private final BackupProperties properties;

    public DatabaseDumper(List<DatabaseDumpStrategy> strategies, BackupProperties properties) {
        for (DatabaseDumpStrategy strategy : strategies) {
            for (DBType type : strategy.dbTypes()) {
                DatabaseDumpStrategy previous = this.strategies.put(type, strategy);
                if (previous != null) {
                    throw new IllegalStateException("Two dump strategies registered for " + type);
                }
            }
        }
        this.properties = properties;
    }

    public boolean supports(DBType type) {
        return strategies.containsKey(type);
    }

    public DumpReport dump(DBConfig config, Path outputDir) throws Exception {
        DatabaseDumpStrategy strategy = strategies.get(config.dbType());
        if (strategy == null) throw new InternalException("No dump strategy for " + config.dbType());
        Files.createDirectories(outputDir);
        DumpSettings settings = new DumpSettings(properties.fetchSize(), properties.insertBatchRows(),
                properties.connectTimeoutSeconds(), properties.maxWorkersPerBackup());
        return strategy.dump(config, outputDir, settings);
    }
}
