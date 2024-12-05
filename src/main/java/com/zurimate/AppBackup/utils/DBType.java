package com.zurimate.AppBackup.utils;

public enum DBType {
    MYSQL(DBTypeStrategyConstants.MYSQL_STRATEGY);

    private final String strategyName;

    DBType(String strategyName) {
        this.strategyName = strategyName;
    }

    @Override
    public String toString() {
        return this.strategyName;
    }

    public interface DBTypeStrategyConstants {
        String MYSQL_STRATEGY = "mysqlStrategy";
    }
}
