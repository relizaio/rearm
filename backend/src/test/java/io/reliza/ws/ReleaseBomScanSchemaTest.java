/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import graphql.language.AstPrinter;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * TPF-132 architecture-4 section 6 test 3: the shape of the served BOM scan tally, which the UI (TPF-133,
 * TPF-127's releaseBomScanState) reads by name: where it hangs off the release metrics, and its four fields
 * with their nullability. The BomScanState values are pinned by GraphQlSchemaEnumSyncTest.
 */
public class ReleaseBomScanSchemaTest {

	private TypeDefinitionRegistry registry(String file) throws Exception {
		try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/schema/" + file), StandardCharsets.UTF_8)) {
			return new SchemaParser().parse(r);
		}
	}

	private static Map<String, String> fieldsOf(TypeDefinitionRegistry reg, String name) {
		ObjectTypeDefinition t = reg.getTypes(ObjectTypeDefinition.class).stream().filter(x -> x.getName().equals(name))
				.findFirst().orElseThrow(() -> new AssertionError("no type " + name));
		return t.getFieldDefinitions().stream().collect(Collectors.toMap(FieldDefinition::getName,
				f -> AstPrinter.printAst(f.getType()), (a, b) -> a, LinkedHashMap::new));
	}

	@Test
	public void theTallyHangsOffTheMetricsAsAnOptionalField() throws Exception {
		assertEquals("ReleaseBomScan", fieldsOf(registry("schema.graphqls"), "DependencyTrackMetrics").get("bomScan"));
	}

	@Test
	public void theTallyHasItsStateTheTwoCountsAndTheReasons() throws Exception {
		assertEquals(Map.of("state", "BomScanState!", "scannableBoms", "Int!", "notScannableBoms", "Int!",
				"reasons", "[NoMatchableComponentsReason!]!"),
				fieldsOf(registry("schema.graphqls"), "ReleaseBomScan"));
	}
}
