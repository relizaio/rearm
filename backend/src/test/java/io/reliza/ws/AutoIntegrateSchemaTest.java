/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import graphql.language.AstPrinter;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * TPF-119 mutation round: the shape of the auto-integrate dry-run contract in user.graphqls, which the UI
 * (TPF-117 part 2) is written against: what is required, what may be null, and the argument. The enums are
 * pinned by GraphQlSchemaEnumSyncTest; nothing else reads the field types.
 */
public class AutoIntegrateSchemaTest {

	private TypeDefinitionRegistry registry() throws Exception {
		try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/schema/user.graphqls"), StandardCharsets.UTF_8)) {
			return new SchemaParser().parse(r);
		}
	}

	private static Map<String, String> fieldsOf(ObjectTypeDefinition t) {
		return t.getFieldDefinitions().stream().collect(Collectors.toMap(FieldDefinition::getName,
				f -> AstPrinter.printAst(f.getType()), (a, b) -> a, java.util.LinkedHashMap::new));
	}

	private static ObjectTypeDefinition type(TypeDefinitionRegistry reg, String name) {
		return reg.getTypes(ObjectTypeDefinition.class).stream().filter(t -> t.getName().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError("no type " + name));
	}

	private static FieldDefinition field(ObjectTypeDefinition t, String name) {
		FieldDefinition f = t.getFieldDefinitions().stream().filter(x -> x.getName().equals(name)).findFirst().orElse(null);
		assertNotNull(f, t.getName() + "." + name);
		return f;
	}

	@Test
	public void thePreviewIsAQueryTakingARequiredBranchAndAnsweringARequiredEvaluation() throws Exception {
		FieldDefinition f = field(type(registry(), "Query"), "autoIntegrateFeatureSetPreview");

		assertEquals("AutoIntegrateEvaluation!", AstPrinter.printAst(f.getType()));
		assertEquals(List.of("branchUuid"), f.getInputValueDefinitions().stream().map(i -> i.getName()).toList());
		assertEquals("ID!", AstPrinter.printAst(f.getInputValueDefinitions().get(0).getType()));
	}

	@Test
	public void theExplainedMutationTakesARequiredBranchAndAnswersARequiredResult() throws Exception {
		FieldDefinition f = field(type(registry(), "Mutation"), "autoIntegrateFeatureSetExplained");

		assertEquals("AutoIntegrateResult!", AstPrinter.printAst(f.getType()));
		assertEquals(List.of("branchUuid"), f.getInputValueDefinitions().stream().map(i -> i.getName()).toList());
		assertEquals("ID!", AstPrinter.printAst(f.getInputValueDefinitions().get(0).getType()));
		assertEquals("Release", AstPrinter.printAst(field(type(registry(), "Mutation"), "autoIntegrateFeatureSet").getType()),
				"the old mutation is untouched");
	}

	@Test
	public void theResultTheEvaluationTheRowAndTheReferencesHaveTheDesignedFields() throws Exception {
		TypeDefinitionRegistry reg = registry();

		assertEquals(Map.of("release", "Release", "evaluation", "AutoIntegrateEvaluation!"),
				fieldsOf(type(reg, "AutoIntegrateResult")));
		assertEquals(Map.of("outcome", "AutoIntegrateOutcome!", "existingRelease", "AutoIntegrateReleaseRef",
				"projectedVersion", "String", "dependencies", "[AutoIntegrateDependencyRow!]!", "note", "String"),
				fieldsOf(type(reg, "AutoIntegrateEvaluation")));
		assertEquals(Map.of("component", "AutoIntegrateRef!", "branch", "AutoIntegrateRef", "status", "Status!",
				"source", "AutoIntegrateReleaseSource!", "release", "AutoIntegrateReleaseRef", "note", "String!"),
				fieldsOf(type(reg, "AutoIntegrateDependencyRow")));
		assertEquals(Map.of("uuid", "ID!", "name", "String"), fieldsOf(type(reg, "AutoIntegrateRef")));
		assertEquals(Map.of("uuid", "ID!", "version", "String", "lifecycle", "ReleaseLifecycleEnum", "createdDate", "DateTime"),
				fieldsOf(type(reg, "AutoIntegrateReleaseRef")));
	}
}
