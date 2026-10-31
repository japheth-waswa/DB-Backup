package com.zurimate.appbackup.ports.output;

/**
 * Tuning passed to every dump strategy.
 *
 * @param fetchSize             rows/documents/keys fetched per round trip
 * @param insertBatchRows       rows grouped into one INSERT statement (SQL engines)
 * @param connectTimeoutSeconds how long to wait for the database before giving up
 * @param maxWorkers            parallel worker connections allowed next to the coordinator
 */
public record DumpSettings(int fetchSize, int insertBatchRows, int connectTimeoutSeconds, int maxWorkers) {
}
