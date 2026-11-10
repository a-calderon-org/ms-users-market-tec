package com.markettec.users.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The runtime identity is granted SELECT/INSERT/UPDATE on dbo and nothing else
 * (scripts/azure/sql/grant-runtime-identity.sql). Runtime SQL that deletes, merges or changes the schema would fail
 * in Azure while passing SQL tests that run as an administrator, so it is rejected here.
 */
class RuntimeSqlPrivilegeTest {
    private static final Path RUNTIME_SQL = Path.of("src/main/java/com/markettec/users/adapters/out/sql");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\"[^\"]*\\b(DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE)\\s", Pattern.CASE_INSENSITIVE);
    /** Operator-only code paths: never run with the runtime identity. */
    private static final List<String> OPERATOR_ONLY = List.of("SqlMigrationRunner.java");

    @Test
    void runtimeSqlAdaptersNeedNoPrivilegeBeyondSelectInsertUpdate() throws IOException {
        assertTrue(Files.isDirectory(RUNTIME_SQL), "run from the module root");
        List<String> offending;
        try (Stream<Path> files = Files.list(RUNTIME_SQL)) {
            offending = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !OPERATOR_ONLY.contains(p.getFileName().toString()))
                    .flatMap(RuntimeSqlPrivilegeTest::forbiddenLines).toList();
        }
        assertEquals(List.of(), offending);
    }

    private static Stream<String> forbiddenLines(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            return java.util.stream.IntStream.range(0, lines.size())
                    .filter(i -> FORBIDDEN.matcher(lines.get(i)).find())
                    .mapToObj(i -> file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).strip());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
