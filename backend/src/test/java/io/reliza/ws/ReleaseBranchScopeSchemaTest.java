/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import graphql.language.AstPrinter;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.reliza.model.ReleaseBranchScope;

/**
 * TPF-135 architecture-2 section 4.1 test 6: the ReleaseBranchScope enum and the branchScope argument
 * with its ACTIVE_BRANCHES default on both search fields of the user schema, neither field in the
 * programmatic schema, and the CVE statement's SQL arm spelled with the enum constant's name.
 */
public class ReleaseBranchScopeSchemaTest {

	private TypeDefinitionRegistry registry(String file) throws Exception {
		try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/schema/" + file), StandardCharsets.UTF_8)) {
			return new SchemaParser().parse(r);
		}
	}

	private static FieldDefinition queryField(TypeDefinitionRegistry reg, String name) {
		return reg.getTypes(ObjectTypeDefinition.class).stream().filter(t -> "Query".equals(t.getName()))
				.flatMap(t -> t.getFieldDefinitions().stream()).filter(f -> name.equals(f.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("no Query." + name));
	}

	/** Query fields of a file, whether it defines Query or extends it (programmatic.graphqls extends it). */
	private static boolean hasQueryField(TypeDefinitionRegistry reg, String name) {
		boolean defined = reg.getTypes(ObjectTypeDefinition.class).stream().filter(t -> "Query".equals(t.getName()))
				.flatMap(t -> t.getFieldDefinitions().stream()).anyMatch(f -> name.equals(f.getName()));
		boolean extended = reg.objectTypeExtensions().getOrDefault("Query", List.of()).stream()
				.flatMap(t -> t.getFieldDefinitions().stream()).anyMatch(f -> name.equals(f.getName()));
		return defined || extended;
	}

	@Test
	public void theEnumHasExactlyTheTwoScopes() throws Exception {
		EnumTypeDefinition e = registry("user.graphqls").getType("ReleaseBranchScope", EnumTypeDefinition.class)
				.orElseThrow();
		assertEquals(List.of("ACTIVE_BRANCHES", "ALL_BRANCHES"),
				e.getEnumValueDefinitions().stream().map(EnumValueDefinition::getName).toList());
		assertEquals(List.of("ACTIVE_BRANCHES", "ALL_BRANCHES"),
				Arrays.stream(ReleaseBranchScope.values()).map(Enum::name).toList());
	}

	@Test
	public void bothFieldsTakeBranchScopeLastWithTheActiveDefault() throws Exception {
		TypeDefinitionRegistry reg = registry("user.graphqls");
		for (String field : List.of("searchReleasesByCveId", "releasesBySbomComponents")) {
			List<InputValueDefinition> args = queryField(reg, field).getInputValueDefinitions();
			InputValueDefinition last = args.get(args.size() - 1);
			assertEquals("branchScope", last.getName(), field);
			assertEquals("ReleaseBranchScope", AstPrinter.printAst(last.getType()), field);
			assertEquals("ACTIVE_BRANCHES", AstPrinter.printAst(last.getDefaultValue()), field);
		}
	}

	@Test
	public void theProgrammaticSchemaHasNeitherField() throws Exception {
		TypeDefinitionRegistry reg = registry("programmatic.graphqls");
		assertTrue(hasQueryField(reg, "exportCatalogProgrammatic"), "the probe reads the programmatic Query extension");
		assertFalse(hasQueryField(reg, "searchReleasesByCveId"));
		assertFalse(hasQueryField(reg, "releasesBySbomComponents"));
	}

	@Test
	public void theCveStatementSpellsTheSqlArmWithTheEnumName() throws Exception {
		// VariableQueries is package-private in io.reliza.repositories.
		Field f = Class.forName("io.reliza.repositories.VariableQueries").getDeclaredField("FIND_RELEASES_BY_CVE_ID");
		f.setAccessible(true);
		String sql = (String) f.get(null);
		assertTrue(sql.contains("'" + ReleaseBranchScope.ALL_BRANCHES.name() + "'"), sql);
		assertTrue(sql.contains("NOT EXISTS"), sql);
		assertTrue(sql.contains("cast(:branchScope as text)"), sql);
	}
}
