package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Pins the schema split: the three SDL files merge into one registry, the Query / Mutation
 * base types live in user.graphqls, programmatic.graphqls only extends them, and no root field
 * is declared twice. Also covers the root-field extraction the programmatic endpoint relies on.
 */
public class ProgrammaticSchemaSplitTest {

	private static String read(String path) throws IOException {
		return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	@Test
	void threeFilesMergeIntoOneRegistry() throws IOException {
		SchemaParser parser = new SchemaParser();
		TypeDefinitionRegistry registry = parser.parse(read("schema/schema.graphqls"));
		registry.merge(parser.parse(read("schema/user.graphqls")));
		registry.merge(parser.parse(read("schema/programmatic.graphqls")));
		assertTrue(registry.getType("Query").isPresent(), "Query base type must exist");
		assertTrue(registry.getType("Mutation").isPresent(), "Mutation base type must exist");
		assertTrue(registry.getType("Component").isPresent(), "shared types stay in schema.graphqls");
	}

	@Test
	void rootFieldsAreDeclaredExactlyOnce() throws IOException {
		Map<String, Set<String>> user = baseRootFields(read("schema/user.graphqls"));
		Map<String, Set<String>> prog = ProgrammaticSchemaRegistry.rootFieldsOfExtensions(read("schema/programmatic.graphqls"));
		assertFalse(prog.get("Query").isEmpty());
		assertFalse(prog.get("Mutation").isEmpty());
		for (String root : new String[] {"Query", "Mutation"}) {
			Set<String> overlap = new HashSet<>(user.get(root));
			overlap.retainAll(prog.get(root));
			assertTrue(overlap.isEmpty(), root + " fields declared in both files: " + overlap);
		}
		// shared file must not declare root types at all
		Document shared = new Parser().parseDocument(read("schema/schema.graphqls"));
		for (Definition<?> d : shared.getDefinitions()) {
			if (d instanceof ObjectTypeDefinition o) {
				assertFalse("Query".equals(o.getName()) || "Mutation".equals(o.getName()), "root types belong to user.graphqls");
			}
		}
	}

	@Test
	void registryKnowsProgrammaticFields() throws IOException {
		ProgrammaticSchemaRegistry r = new ProgrammaticSchemaRegistry();
		assertTrue(r.allows(OperationDefinition.Operation.MUTATION, "addReleaseProgrammatic"));
		assertTrue(r.allows(OperationDefinition.Operation.QUERY, "getLatestReleaseProgrammatic"));
		assertTrue(r.allows(OperationDefinition.Operation.QUERY, "__schema"));
		assertFalse(r.allows(OperationDefinition.Operation.MUTATION, "createComponent"), "JWT-only mutation");
		assertFalse(r.allows(OperationDefinition.Operation.QUERY, "instancesOfOrganization"), "JWT-only query");
	}

	@Test
	void rootSelectionFollowsFragmentsAndOperationName() {
		String doc = "fragment R on Release { uuid } query A { release(releaseUuid: \"x\") { ...R } } "
				+ "mutation B { createComponent(component: {name: \"n\", type: COMPONENT}) { uuid } ...F } fragment F on Mutation { addReleaseProgrammatic(release: {}) { uuid } }";
		var a = ProgrammaticSchemaRegistry.rootSelection(doc, "A").orElseThrow();
		assertEquals(OperationDefinition.Operation.QUERY, a.operation());
		assertEquals(Set.of("release"), a.rootFields());
		var b = ProgrammaticSchemaRegistry.rootSelection(doc, "B").orElseThrow();
		assertEquals(OperationDefinition.Operation.MUTATION, b.operation());
		assertEquals(Set.of("createComponent", "addReleaseProgrammatic"), b.rootFields());
		assertTrue(ProgrammaticSchemaRegistry.rootSelection("not graphql {", null).isEmpty());
	}

	@Test
	void bundleIsSelfContainedAndProgrammaticOnly() throws IOException {
		String bundle = new ProgrammaticSchemaRegistry().bundleSdl();
		TypeDefinitionRegistry reg = new SchemaParser().parse(bundle);
		// every type referenced by the bundle is defined in it (plus built-in scalars)
		Set<String> builtins = Set.of("String", "Int", "Float", "Boolean", "ID");
		Document doc = new Parser().parseDocument(bundle);
		for (Definition<?> d : doc.getDefinitions()) {
			if (d instanceof ObjectTypeDefinition o) {
				for (FieldDefinition f : o.getFieldDefinitions()) {
					String name = unwrap(f.getType());
					assertTrue(builtins.contains(name) || reg.getType(name).isPresent(), o.getName() + "." + f.getName() + " references undefined type " + name);
					// ARGUMENTS too. This is the hole ComponentRefInput fell through: it was an
					// argument of a root field, declared in programmatic.graphqls, and the bundle
					// indexed only the shared schema -- so the contract shipped with a type no
					// client could resolve while every return type checked out.
					for (graphql.language.InputValueDefinition iv : f.getInputValueDefinitions()) {
						String argType = unwrap(iv.getType());
						assertTrue(builtins.contains(argType) || reg.getType(argType).isPresent(),
								o.getName() + "." + f.getName() + "(" + iv.getName() + ") references undefined type " + argType);
					}
				}
			} else if (d instanceof graphql.language.InputObjectTypeDefinition in) {
				for (graphql.language.InputValueDefinition iv : in.getInputValueDefinitions()) {
					String name = unwrap(iv.getType());
					assertTrue(builtins.contains(name) || reg.getType(name).isPresent(),
							in.getName() + "." + iv.getName() + " references undefined type " + name);
				}
			}
		}
		// A type declared in programmatic.graphqls belongs to the programmatic contract: that is
		// where anybody writing their own client is told to look, so it has to be in the bundle.
		assertTrue(reg.getType("ComponentRefInput").isPresent(),
				"a type declared in programmatic.graphqls must reach the published contract");
		ObjectTypeDefinition q = (ObjectTypeDefinition) reg.getType("Query").orElseThrow();
		ObjectTypeDefinition m = (ObjectTypeDefinition) reg.getType("Mutation").orElseThrow();
		assertTrue(m.getFieldDefinitions().stream().anyMatch(f -> f.getName().equals("addReleaseProgrammatic")));
		assertTrue(q.getFieldDefinitions().stream().noneMatch(f -> f.getName().equals("instancesOfOrganization")), "user-only field leaked into the bundle");
		assertFalse(reg.getType("SettingsInput").isPresent(), "types only the user surface needs must not be in the bundle");
	}

	private static String unwrap(graphql.language.Type<?> t) {
		while (t instanceof graphql.language.NonNullType || t instanceof graphql.language.ListType) {
			t = t instanceof graphql.language.NonNullType nn ? nn.getType() : ((graphql.language.ListType) t).getType();
		}
		return ((graphql.language.TypeName) t).getName();
	}

	private static Map<String, Set<String>> baseRootFields(String sdl) {
		Document doc = new Parser().parseDocument(sdl);
		Map<String, Set<String>> out = new java.util.HashMap<>();
		for (Definition<?> d : doc.getDefinitions()) {
			if (d instanceof ObjectTypeDefinition o && !(d instanceof ObjectTypeExtensionDefinition)
					&& ("Query".equals(o.getName()) || "Mutation".equals(o.getName()))) {
				Set<String> names = out.computeIfAbsent(o.getName(), k -> new HashSet<>());
				for (FieldDefinition f : o.getFieldDefinitions()) names.add(f.getName());
			}
		}
		return out;
	}
}
