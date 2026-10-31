package com.zurimate.appbackup.ports.output;

import java.io.IOException;
import java.io.Writer;
import java.sql.SQLException;
import java.util.List;

/**
 * A single relational dump run. Holds the coordinator connection for its whole lifetime so that
 * every worker can read from the same point in time where the engine supports it.
 */
public interface DumpSession extends AutoCloseable {

    /** Tables to dump, in a stable order. Names are as the dialect expects them in {@link TableDumper#dumpTable}. */
    List<String> tables() throws SQLException;

    /** Session settings written at the top of every generated file so each file restores on its own. */
    String fileHeader();

    /** Objects that must exist before table data is loaded (extensions, types, sequences). */
    void writePreData(Writer writer) throws SQLException, IOException;

    /** Objects created after the data is loaded (foreign keys, indexes, sequence values, views). */
    void writePostData(Writer writer) throws SQLException, IOException;

    /** Opens an extra connection for parallel table dumps. Caller closes it. */
    TableDumper openWorker() throws SQLException;

    @Override
    void close();
}
