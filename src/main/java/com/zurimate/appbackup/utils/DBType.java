package com.zurimate.appbackup.utils;

/**
 * Database engines AppBackup can dump. MYSQL and MARIADB share one strategy; the distinction is kept
 * so operators can see what they registered.
 */
public enum DBType {
    POSTGRESQL(true),
    MYSQL(true),
    MARIADB(true),
    ORACLE(true),
    MSSQL(true),
    MONGODB(false),
    REDIS(false);

    private final boolean requiresCredentials;

    DBType(boolean requiresCredentials) {
        this.requiresCredentials = requiresCredentials;
    }

    /** SQL engines always authenticate; MongoDB and Redis may run without credentials. */
    public boolean requiresCredentials() {
        return requiresCredentials;
    }
}
