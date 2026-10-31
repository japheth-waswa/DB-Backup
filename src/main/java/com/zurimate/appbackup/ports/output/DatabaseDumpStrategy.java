package com.zurimate.appbackup.ports.output;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.utils.DBType;

import java.nio.file.Path;
import java.util.Set;

/**
 * Writes a restorable logical dump of one database into a directory. Implementations are
 * stateless singletons and must release every connection they open before returning.
 */
public interface DatabaseDumpStrategy {

    Set<DBType> dbTypes();

    DumpReport dump(DBConfig config, Path outputDir, DumpSettings settings) throws Exception;
}
