package com.zurimate.appbackup.utils;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.exception.InternalException;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Opens plain, unpooled JDBC connections to the databases being backed up.
 * <p>
 * Backups are infrequent, so pooling buys nothing, and an unclosed pool keeps idle
 * connections on the target server forever. Every connection handed out here must be
 * closed by the caller, ideally with try-with-resources.
 */
@Slf4j
public final class DbConnectionFactory {
    public static final String APPLICATION_NAME = "AppBackup";

    private DbConnectionFactory() {
    }

    public static Connection open(DBConfig config, int connectTimeoutSeconds) throws SQLException {
        String host = config.hostOrDefault();
        Properties props = new Properties();
        if (config.username() != null) props.setProperty("user", config.username());
        if (config.password() != null) props.setProperty("password", config.password());

        String url = switch (config.dbType()) {
            case POSTGRESQL -> {
                props.setProperty("ApplicationName", APPLICATION_NAME);
                props.setProperty("connectTimeout", String.valueOf(connectTimeoutSeconds));
                props.setProperty("tcpKeepAlive", "true");
                // text transfer keeps values exactly as the server prints them
                props.setProperty("prepareThreshold", "0");
                yield "jdbc:postgresql://%s:%d/%s".formatted(host, config.port(), config.dbName());
            }
            case MYSQL, MARIADB -> {
                props.setProperty("connectTimeout", String.valueOf(connectTimeoutSeconds * 1000));
                props.setProperty("tcpKeepAlive", "true");
                props.setProperty("characterEncoding", "UTF-8");
                props.setProperty("zeroDateTimeBehavior", "CONVERT_TO_NULL");
                props.setProperty("tinyInt1isBit", "false");
                props.setProperty("yearIsDateType", "false");
                // the dump session runs in UTC (see MysqlDumpStrategy); keep the driver from shifting values
                props.setProperty("connectionTimeZone", "UTC");
                props.setProperty("connectionAttributes", "program_name:" + APPLICATION_NAME);
                yield "jdbc:mysql://%s:%d/%s".formatted(host, config.port(), config.dbName());
            }
            case ORACLE -> {
                props.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectTimeoutSeconds * 1000));
                props.setProperty("v$session.program", APPLICATION_NAME);
                yield "jdbc:oracle:thin:@//%s:%d/%s".formatted(host, config.port(), config.dbName());
            }
            case MSSQL -> {
                props.setProperty("databaseName", config.dbName());
                props.setProperty("loginTimeout", String.valueOf(connectTimeoutSeconds));
                props.setProperty("applicationName", APPLICATION_NAME);
                yield "jdbc:sqlserver://%s:%d".formatted(host, config.port());
            }
            case MONGODB, REDIS -> throw new InternalException(config.dbType() + " is not a JDBC database");
        };

        // user supplied options win over the defaults above
        parseOptions(config.options()).forEach(props::setProperty);

        log.debug("Opening {} connection to {}:{}/{}", config.dbType(), host, config.port(), config.dbName());
        return DriverManager.getConnection(url, props);
    }

    /** Parses {@code a=1&b=2} or {@code a=1;b=2}. Keys without a value are ignored. */
    public static Map<String, String> parseOptions(String options) {
        Map<String, String> result = new LinkedHashMap<>();
        if (options == null || options.isBlank()) return result;
        for (String pair : options.split("[&;]")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            result.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
        }
        return result;
    }
}
