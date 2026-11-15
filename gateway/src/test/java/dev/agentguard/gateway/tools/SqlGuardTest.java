package dev.agentguard.gateway.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Cases live in src/test/resources/sql-guard-cases.csv: one line per query, expected outcome and reason. */
class SqlGuardTest {

    static Stream<Arguments> cases() throws Exception {
        var in = SqlGuardTest.class.getResourceAsStream("/sql-guard-cases.csv");
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        return reader.lines().skip(1).filter(l -> !l.isBlank()).map(line -> {
            String[] parts = line.split("\\|", 3);
            return Arguments.of(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
        }).toList().stream();
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("cases")
    void guardDecidesAsExpected(String expected, String reasonFragment, String sql) {
        SqlGuard.Result result = SqlGuard.check(sql, 50);

        if (expected.equals("ACCEPT")) {
            assertThat(result.accepted()).as(result.reason()).isTrue();
            assertThat(result.rewrittenSql()).endsWith("FETCH FIRST 50 ROWS ONLY");
        } else {
            assertThat(result.accepted()).isFalse();
            assertThat(result.reason()).containsIgnoringCase(reasonFragment);
        }
    }

    @Test
    void rowLimitIsAlwaysForcedEvenWhenTheQueryAsksForMore() {
        SqlGuard.Result result = SqlGuard.check("SELECT id FROM supplier FETCH FIRST 100000 ROWS ONLY", 20);

        assertThat(result.accepted()).isTrue();
        assertThat(result.rewrittenSql()).startsWith("SELECT * FROM (").endsWith(") FETCH FIRST 20 ROWS ONLY");
    }
}
