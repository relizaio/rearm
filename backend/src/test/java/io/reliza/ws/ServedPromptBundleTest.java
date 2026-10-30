/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import graphql.language.AstPrinter;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * The programmatic bundle carries the served prompt by version (TPF-97 / TPF-109): the ServedPrompt type, its status
 * enum and the three by-version fields with their heldVersions argument, asserted on the parsed bundle (the printer puts
 * each field argument on its own line, so a one-line substring never occurs in it).
 */
class ServedPromptBundleTest {

	@Test
	void bundleCarriesServedPromptByVersion() throws IOException {
		String bundle = new ProgrammaticSchemaRegistry().bundleSdl();
		TypeDefinitionRegistry reg = new SchemaParser().parse(bundle);

		TypeDefinition<?> sp = reg.getType("ServedPrompt").orElseThrow(() -> new AssertionError("ServedPrompt missing"));
		assertTrue(sp instanceof ObjectTypeDefinition, sp.getClass().getName());
		List<FieldDefinition> fields = ((ObjectTypeDefinition) sp).getFieldDefinitions();
		assertEquals(List.of("version", "status", "text"), fields.stream().map(FieldDefinition::getName).toList());
		assertEquals(List.of("String!", "ServedPromptStatus!", "String"),
				fields.stream().map(f -> AstPrinter.printAst(f.getType())).toList());

		TypeDefinition<?> st = reg.getType("ServedPromptStatus")
				.orElseThrow(() -> new AssertionError("ServedPromptStatus missing"));
		assertTrue(st instanceof EnumTypeDefinition, st.getClass().getName());
		assertEquals(List.of("SERVED", "UNCHANGED"), ((EnumTypeDefinition) st).getEnumValueDefinitions().stream()
				.map(EnumValueDefinition::getName).toList());

		for (String[] tf : new String[][] { { "AgentTaskAssignment", "promptByVersion" },
				{ "AgentTaskRoleConfig", "promptByVersion" }, { "AgentBoard", "coordinatorPromptByVersion" } }) {
			String where = tf[0] + "." + tf[1];
			TypeDefinition<?> t = reg.getType(tf[0]).orElseThrow(() -> new AssertionError(tf[0] + " missing"));
			assertTrue(t instanceof ObjectTypeDefinition, where);
			List<FieldDefinition> named = ((ObjectTypeDefinition) t).getFieldDefinitions().stream()
					.filter(f -> tf[1].equals(f.getName())).toList();
			assertEquals(1, named.size(), where + " exactly once");
			FieldDefinition f = named.get(0);
			assertEquals("ServedPrompt", AstPrinter.printAst(f.getType()), where);
			List<InputValueDefinition> args = f.getInputValueDefinitions();
			assertEquals(1, args.size(), where + " takes one argument");
			assertEquals("heldVersions", args.get(0).getName(), where);
			assertEquals("[String!]", AstPrinter.printAst(args.get(0).getType()), where);
		}
	}
}
