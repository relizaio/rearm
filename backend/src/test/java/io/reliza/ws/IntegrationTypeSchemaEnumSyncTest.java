/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.model.IntegrationData.IntegrationType;

/**
 * Regression guard for the {@code IntegrationType} GraphQL/Java enum drift
 * (sibling of {@code NotificationSchemaEnumSyncTest}).
 *
 * <p>The GraphQL {@code IntegrationType} enum is deliberately a SUBSET of
 * the Java enum -- the notification-only destinations ({@code EMAIL},
 * {@code WEBHOOK}, {@code SENTINEL}) are exposed via
 * {@code NotificationChannelTypeEnum} instead, so a strict equality check
 * would be wrong here. Two weaker-but-correct invariants are pinned:
 *
 * <ol>
 *   <li>Every value declared in the schema enum is a real Java enum value
 *       (catches a stale/typo'd schema entry).</li>
 *   <li>The KEV catalog source types ({@code CISA_KEV}, {@code VULNCHECK_KEV})
 *       are present. These flow through the GraphQL {@code IntegrationType}
 *       surface in both directions -- {@code enableKevSource} /
 *       {@code disableKevSource} take them as an input arg, and
 *       {@code configuredBaseIntegrations} returns them as output (every org
 *       gets a CISA_KEV row from the V54 backfill). A missing value breaks
 *       both input coercion and output serialization. This is the exact
 *       drift the V54 per-org refactor would otherwise have shipped.</li>
 * </ol>
 */
class IntegrationTypeSchemaEnumSyncTest {

    private static final Pattern ENUM_BLOCK = Pattern.compile(
            "enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

    @Test
    void schemaIntegrationTypeValuesAreAllRealJavaValues() {
        Set<String> javaValues = Arrays.stream(IntegrationType.values()).map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> schemaValues = readSchemaEnum("IntegrationType");
        for (String v : schemaValues) {
            assertTrue(javaValues.contains(v),
                    "GraphQL enum IntegrationType declares '" + v
                            + "' which is not a Java IntegrationType value (stale/typo)");
        }
    }

    @Test
    void kevSourceTypesAreDeclaredInSchema() {
        Set<String> schemaValues = readSchemaEnum("IntegrationType");
        assertTrue(schemaValues.contains(IntegrationType.CISA_KEV.name()),
                "GraphQL enum IntegrationType is missing CISA_KEV -- enableKevSource input"
                        + " and configuredBaseIntegrations output will fail enum validation");
        assertTrue(schemaValues.contains(IntegrationType.VULNCHECK_KEV.name()),
                "GraphQL enum IntegrationType is missing VULNCHECK_KEV -- enableKevSource input"
                        + " and configuredBaseIntegrations output will fail enum validation");
    }

    private static Set<String> readSchemaEnum(String enumName) {
        String schema = readSchema();
        Matcher m = ENUM_BLOCK.matcher(schema);
        while (m.find()) {
            if (!enumName.equals(m.group(1))) continue;
            Set<String> out = new TreeSet<>();
            for (String raw : m.group(2).split("\\R")) {
                String line = raw.trim();
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash).trim();
                if (line.isEmpty()) continue;
                out.add(line);
            }
            return out;
        }
        throw new AssertionError("Did not find enum " + enumName + " in schema.graphqls");
    }

    private static String readSchema() {
        try (InputStream in = IntegrationTypeSchemaEnumSyncTest.class.getResourceAsStream(
                "/schema/schema.graphqls")) {
            if (in == null) throw new IllegalStateException("schema.graphqls not on test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
