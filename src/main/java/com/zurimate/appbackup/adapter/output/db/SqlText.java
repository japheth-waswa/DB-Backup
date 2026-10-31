package com.zurimate.appbackup.adapter.output.db;

import java.sql.Types;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Literal and identifier escaping for the generated SQL files. */
final class SqlText {
    private static final HexFormat HEX = HexFormat.of();

    private SqlText() {
    }

    static String pgIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** Standard SQL string literal; correct while standard_conforming_strings is on. */
    static String pgString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String mysqlIdent(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /** MySQL string literal that is safe in the default sql_mode where backslash is an escape character. */
    static String mysqlString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\0' -> sb.append("\\0");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\u001A' -> sb.append("\\Z");
                default -> sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    static boolean isNumeric(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
            default -> false;
        };
    }

    static boolean isBinary(int jdbcType) {
        return switch (jdbcType) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> true;
            default -> false;
        };
    }

    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?\\d+(\\.\\d*)?([eE][+-]?\\d+)?");

    /**
     * True when the text can be written as a bare numeric literal. NaN, Infinity and
     * locale formatted values (e.g. PostgreSQL money) are quoted instead.
     */
    static boolean isPlainNumber(String text) {
        return PLAIN_NUMBER.matcher(text).matches();
    }
}
