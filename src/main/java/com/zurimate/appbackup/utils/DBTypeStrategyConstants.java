package com.zurimate.appbackup.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DBTypeStrategyConstants {
    public static final String MYSQL_STRATEGY = "mysqlStrategy";
    public static final String POSTGRESQL_STRATEGY = "postgresqlStrategy";
}
