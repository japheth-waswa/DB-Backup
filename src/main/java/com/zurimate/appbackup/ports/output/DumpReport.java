package com.zurimate.appbackup.ports.output;

import java.util.SortedMap;

/**
 * @param objectsTotal  tables, collections or Redis keys found
 * @param recordsDumped rows, documents or keys written
 * @param failures      object name to reason; empty when the dump is complete
 */
public record DumpReport(int objectsTotal, long recordsDumped, SortedMap<String, String> failures) {
    public boolean complete() {
        return failures.isEmpty();
    }
}
