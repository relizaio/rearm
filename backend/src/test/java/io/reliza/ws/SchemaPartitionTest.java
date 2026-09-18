/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.parser.Parser;

/**
 * Which surface each declared type belongs to.
 *
 * <p>The three files are a contract, not a filing preference: {@code programmatic.graphqls} is
 * what somebody writing their own client is handed, so a type only that surface uses belongs
 * there and a type only the UI uses must not. {@code schema.graphqls} is what both surfaces
 * share.
 *
 * <p>The test asserts the invariant in both directions. Run with {@code -DprintPartition=true} to
 * see the classification of everything currently in the shared file, which is how the split is
 * maintained when a type stops being shared.
 */
public class SchemaPartitionTest {

	private record Schemas(Document shared, Document user, Document programmatic) {}

	private static Schemas load() throws IOException {
		return new Schemas(parse("schema/schema.graphqls"), parse("schema/user.graphqls"),
				parse("schema/programmatic.graphqls"));
	}

	private static Document parse(String resource) throws IOException {
		return new Parser().parseDocument(new String(
				new ClassPathResource(resource).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	/** Every type name reachable from the root fields a document declares. */
	private static Set<String> reachableFrom(Document roots, List<Document> all) {
		Map<String, List<TypeDefinition<?>>> byName = new HashMap<>();
		Map<String, List<String>> implementations = new HashMap<>();
		for (Document doc : all) {
			for (Definition<?> d : doc.getDefinitions()) {
				if (d instanceof ObjectTypeExtensionDefinition) continue;
				if (d instanceof TypeDefinition<?> td) {
					byName.computeIfAbsent(td.getName(), k -> new ArrayList<>()).add(td);
				}
				if (d instanceof ObjectTypeDefinition o && !(d instanceof ObjectTypeExtensionDefinition)) {
					o.getImplements().forEach(t -> implementations
							.computeIfAbsent(baseName(t), k -> new ArrayList<>()).add(o.getName()));
				}
			}
		}
		ArrayDeque<String> queue = new ArrayDeque<>();
		for (Definition<?> d : roots.getDefinitions()) {
			List<FieldDefinition> fields = null;
			if (d instanceof ObjectTypeExtensionDefinition ext
					&& (ext.getName().equals("Query") || ext.getName().equals("Mutation"))) {
				fields = ext.getFieldDefinitions();
			} else if (d instanceof ObjectTypeDefinition o && !(d instanceof ObjectTypeExtensionDefinition)
					&& (o.getName().equals("Query") || o.getName().equals("Mutation")
							|| o.getName().equals("Subscription"))) {
				fields = o.getFieldDefinitions();
			}
			if (fields == null) continue;
			for (FieldDefinition fd : fields) {
				queue.add(baseName(fd.getType()));
				fd.getInputValueDefinitions().forEach(iv -> queue.add(baseName(iv.getType())));
			}
		}
		Set<String> reachable = new LinkedHashSet<>();
		while (!queue.isEmpty()) {
			String name = queue.poll();
			if (name == null || !reachable.add(name)) continue;
			for (TypeDefinition<?> td : byName.getOrDefault(name, List.of())) {
				if (td instanceof ObjectTypeDefinition o) {
					o.getFieldDefinitions().forEach(fd -> {
						queue.add(baseName(fd.getType()));
						fd.getInputValueDefinitions().forEach(iv -> queue.add(baseName(iv.getType())));
					});
					o.getImplements().forEach(t -> queue.add(baseName(t)));
				} else if (td instanceof InterfaceTypeDefinition i) {
					i.getFieldDefinitions().forEach(fd -> queue.add(baseName(fd.getType())));
					queue.addAll(implementations.getOrDefault(name, List.of()));
				} else if (td instanceof UnionTypeDefinition u) {
					u.getMemberTypes().forEach(t -> queue.add(baseName(t)));
				} else if (td instanceof graphql.language.InputObjectTypeDefinition in) {
					in.getInputValueDefinitions().forEach(iv -> queue.add(baseName(iv.getType())));
				}
			}
		}
		return reachable;
	}

	private static Set<String> declaredIn(Document doc) {
		Set<String> out = new LinkedHashSet<>();
		for (Definition<?> d : doc.getDefinitions()) {
			if (d instanceof ObjectTypeExtensionDefinition) continue;
			if (d instanceof TypeDefinition<?> td && !td.getName().equals("Query")
					&& !td.getName().equals("Mutation") && !td.getName().equals("Subscription")) {
				out.add(td.getName());
			}
		}
		return out;
	}

	private static String baseName(graphql.language.Type<?> t) {
		while (t instanceof graphql.language.NonNullType || t instanceof graphql.language.ListType) {
			t = t instanceof graphql.language.NonNullType nn ? nn.getType()
					: ((graphql.language.ListType) t).getType();
		}
		return t instanceof graphql.language.TypeName tn ? tn.getName() : null;
	}

	@Test
	void eachFileHoldsWhatItsSurfaceOwns() throws IOException {
		Schemas s = load();
		List<Document> all = List.of(s.shared(), s.user(), s.programmatic());
		Set<String> fromProgrammatic = reachableFrom(s.programmatic(), all);
		Set<String> fromUser = reachableFrom(s.user(), all);

		Set<String> userOnlyInProgrammaticFile = new TreeSet<>();
		for (String name : declaredIn(s.programmatic())) {
			if (!fromProgrammatic.contains(name) && fromUser.contains(name)) userOnlyInProgrammaticFile.add(name);
		}
		assertTrue(userOnlyInProgrammaticFile.isEmpty(),
				"programmatic.graphqls is the contract handed to client authors; these types are the "
				+ "user surface's and do not belong in it: " + userOnlyInProgrammaticFile);

		Set<String> programmaticOnlyInUserFile = new TreeSet<>();
		for (String name : declaredIn(s.user())) {
			if (!fromUser.contains(name) && fromProgrammatic.contains(name)) programmaticOnlyInUserFile.add(name);
		}
		assertTrue(programmaticOnlyInUserFile.isEmpty(),
				"these types are only reachable from the programmatic surface and belong in "
				+ "programmatic.graphqls: " + programmaticOnlyInUserFile);

		if (Boolean.getBoolean("printPartition")) {
			Set<String> sharedFile = declaredIn(s.shared());
			Set<String> progOnly = new TreeSet<>();
			Set<String> userOnly = new TreeSet<>();
			Set<String> both = new TreeSet<>();
			Set<String> neither = new TreeSet<>();
			for (String name : sharedFile) {
				boolean p = fromProgrammatic.contains(name);
				boolean u = fromUser.contains(name);
				if (p && u) both.add(name);
				else if (p) progOnly.add(name);
				else if (u) userOnly.add(name);
				else neither.add(name);
			}
			System.out.println("PARTITION programmatic-only: " + String.join(",", progOnly));
			System.out.println("PARTITION user-only: " + String.join(",", userOnly));
			System.out.println("PARTITION shared: " + both.size() + " types");
			System.out.println("PARTITION unreachable: " + String.join(",", neither));
		}
	}
}
