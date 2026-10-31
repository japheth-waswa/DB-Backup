package com.zurimate.appbackup.adapter.output.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DialectLiteralTest {

    @Test
    void sqlServerQuotesIdentifiersAndUnicodeStrings() {
        assertThat(MssqlDumpStrategy.ident("Order]Items")).isEqualTo("[Order]]Items]");
        assertThat(MssqlDumpStrategy.nstring("O'Brien 😀")).isEqualTo("N'O''Brien 😀'");
    }

    @Test
    void oracleLiteralsStayBelowTheSqlPlusLineLimit() {
        String longText = "x'".repeat(1500); // 3000 chars, quotes doubled on output
        String literal = OracleDumpStrategy.literal(longText, false);

        assertThat(literal.split("\n")).allSatisfy(line -> assertThat(line.length()).isLessThan(2499));
        assertThat(literal).startsWith("'").contains(" ||\n'");
        assertThat(Arrays.stream(literal.split(" \\|\\|\n")).map(p -> p.substring(1, p.length() - 1).replace("''", "'"))
                .reduce("", String::concat)).isEqualTo(longText);
    }

    @Test
    void oracleChunksNeverSplitSurrogatePairs() {
        String text = "a".repeat(999) + "😀" + "b".repeat(10); // emoji straddles the 1000 char boundary
        assertThat(OracleDumpStrategy.chunkEnd(text, 0)).isEqualTo(999);
        String literal = OracleDumpStrategy.literal(text, false);
        assertThat(literal).contains("'" + "😀" + "b".repeat(10) + "'");
        assertThat(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(literal)).isTrue();
    }

    @Test
    void oracleEmptyStringIsNullAndNationalStringsArePrefixed() {
        assertThat(OracleDumpStrategy.literal("", false)).isEqualTo("NULL");
        assertThat(OracleDumpStrategy.literal("ünï", true)).isEqualTo("N'ünï'");
        assertThat(OracleDumpStrategy.ident("Order \"Items\"")).isEqualTo("\"Order \"\"Items\"\"\"");
    }
}
