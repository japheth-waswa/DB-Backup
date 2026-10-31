package com.zurimate.appbackup.adapter.output.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTextTest {

    @Test
    void quotesPostgresIdentifiersAndStrings() {
        assertThat(SqlText.pgIdent("Order \"Items\"")).isEqualTo("\"Order \"\"Items\"\"\"");
        assertThat(SqlText.pgString("O'Brien \\ x")).isEqualTo("'O''Brien \\ x'");
    }

    @Test
    void escapesMysqlSpecialCharacters() {
        assertThat(SqlText.mysqlIdent("tab`Report Filter")).isEqualTo("`tab``Report Filter`");
        assertThat(SqlText.mysqlString("a'b\\c\nd\0e\u001Af\rg"))
                .isEqualTo("'a\\'b\\\\c\\nd\\0e\\Zf\\rg'");
    }

    @Test
    void onlyPlainNumbersStayUnquoted() {
        assertThat(SqlText.isPlainNumber("-12.50")).isTrue();
        assertThat(SqlText.isPlainNumber("1.5E-7")).isTrue();
        assertThat(SqlText.isPlainNumber("NaN")).isFalse();
        assertThat(SqlText.isPlainNumber("Infinity")).isFalse();
        assertThat(SqlText.isPlainNumber("$1,000.00")).isFalse();
        assertThat(SqlText.isPlainNumber("")).isFalse();
    }
}
