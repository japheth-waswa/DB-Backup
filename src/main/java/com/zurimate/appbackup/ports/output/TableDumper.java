package com.zurimate.appbackup.ports.output;

import java.io.IOException;
import java.io.Writer;
import java.sql.SQLException;

/**
 * One worker connection of a relational dump. Workers are not thread-safe; each is used by a
 * single thread and closed when that thread is done or after a failure.
 */
public interface TableDumper extends AutoCloseable {

    /**
     * Writes the table definition followed by its rows.
     *
     * @return number of rows written
     */
    long dumpTable(String table, Writer writer) throws SQLException, IOException;

    @Override
    void close();
}
