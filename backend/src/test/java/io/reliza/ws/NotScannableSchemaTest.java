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
 * TPF-126 mutation round: the shape of the not-scannable contract in the schema, which the UI (TPF-125
 * part 2) is written against: the marker's fields and what may be null, where it hangs off the artifact
 * metrics, and the explainer's notScannableAt. The enum is pinned by GraphQlSchemaEnumSyncTest.
 */
public class NotScannableSchemaTest {

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
	public void theMarkerHasItsReasonItsInstantAndTheFourCounts() throws Exception {
		assertEquals(Map.of("reason", "NoMatchableComponentsReason!", "at", "DateTime", "components", "Int!",
				"root", "Int!", "terminal", "Int!", "unmatchable", "Int!"),
				fieldsOf(registry("schema.graphqls"), "ArtifactNotScannable"));
	}

	@Test
	public void theMarkerHangsOffTheArtifactMetricsAsAnOptionalField() throws Exception {
		assertEquals("ArtifactNotScannable", fieldsOf(registry("schema.graphqls"), "DependencyTrackMetrics").get("notScannable"));
	}

	@Test
	public void theExplainerCarriesTheInstantTheMarkerWasDerivedAt() throws Exception {
		assertEquals("DateTime", fieldsOf(registry("user.graphqls"), "ArtifactScanStatus").get("notScannableAt"));
		assertEquals("NoMatchableComponentsReason", fieldsOf(registry("user.graphqls"), "ArtifactScanStatus").get("noMatchableComponentsReason"));
	}
}
