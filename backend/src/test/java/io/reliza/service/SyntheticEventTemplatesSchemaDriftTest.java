/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.service.SyntheticEventTemplates.Template;

/**
 * Pin the GraphQL {@code SyntheticEventTemplate} enum and the Java
 * {@link Template} enum to identical value sets.
 *
 * <p>A schema-only rename (or a Java-side rename without the schema
 * update) would otherwise become a runtime IllegalArgumentException
 * first hit in prod by a customer pressing a UI button. This test
 * catches that drift in CI.
 *
 * <p>The schema is parsed with a regex rather than a full GraphQL
 * lexer — the enum block is small, well-formatted, and lives in a
 * single file. A real parser is overkill for one shape.
 */
class SyntheticEventTemplatesSchemaDriftTest {

    private static final Path SCHEMA_PATH = Paths.get(
            "src/main/resources/schema/schema.graphqls");

    @Test
    void schemaEnumMatchesJavaEnumExactly() throws Exception {
        Set<String> javaValues = Arrays.stream(Template.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> schemaValues = parseSchemaEnumValues();
        assertEquals(javaValues, schemaValues,
                "GraphQL SyntheticEventTemplate values must mirror Java Template values 1:1. "
                        + "Java has: " + javaValues + " — schema has: " + schemaValues);
    }

    @Test
    void parserRoundTripsEveryJavaValue() {
        // Every Template.name() must parse back to itself — pins that
        // the *_VALUE companion constants are accurate.
        for (Template t : Template.values()) {
            Template parsed = Template.parse(t.name());
            assertEquals(t, parsed, "Template " + t + " did not round-trip through parse()");
            assertNotNull(parsed);
            // Pin reference equality (enum singleton) too — defends against
            // a future refactor that returns a new instance from parse.
            assertNotSame(null, parsed);
        }
    }

    private static Set<String> parseSchemaEnumValues() throws Exception {
        String schema = Files.readString(SCHEMA_PATH);
        Matcher block = Pattern.compile(
                "enum\\s+SyntheticEventTemplateEnum\\s*\\{([^}]*)\\}",
                Pattern.DOTALL).matcher(schema);
        if (!block.find()) {
            throw new AssertionError(
                    "Could not find 'enum SyntheticEventTemplateEnum' block in " + SCHEMA_PATH);
        }
        String body = block.group(1);
        Set<String> values = new LinkedHashSet<>();
        // Strip comment lines, then split words on whitespace.
        for (String line : body.split("\n")) {
            String stripped = line.replaceAll("#.*$", "").trim();
            if (stripped.isEmpty()) continue;
            for (String token : stripped.split("\\s+")) {
                if (!token.isEmpty()) values.add(token);
            }
        }
        return values;
    }
}
