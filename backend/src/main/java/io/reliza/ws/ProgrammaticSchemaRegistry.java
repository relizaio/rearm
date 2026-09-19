/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;

/**
 * Knows which root fields belong to the programmatic (API-key) half of the schema.
 * The source of truth is {@code schema/programmatic.graphqls}: every field it declares
 * on {@code extend type Query} / {@code extend type Mutation} is allowed on the
 * programmatic endpoint; everything else is refused there. Loaded once at startup, so
 * adding a programmatic operation is a schema edit, not a code change.
 */
@Component
public class ProgrammaticSchemaRegistry {

	public static final String PROGRAMMATIC_SCHEMA = "schema/programmatic.graphqls";

	/** Introspection fields every endpoint answers. */
	private static final Set<String> INTROSPECTION = Set.of("__schema", "__type", "__typename");

	private final Set<String> queryFields;
	private final Set<String> mutationFields;

	public ProgrammaticSchemaRegistry() throws IOException {
		this(new String(new ClassPathResource(PROGRAMMATIC_SCHEMA).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	ProgrammaticSchemaRegistry(String programmaticSdl) {
		Map<String, Set<String>> fields = rootFieldsOfExtensions(programmaticSdl);
		this.queryFields = Collections.unmodifiableSet(fields.getOrDefault("Query", Set.of()));
		this.mutationFields = Collections.unmodifiableSet(fields.getOrDefault("Mutation", Set.of()));
	}

	public Set<String> queryFields() { return queryFields; }
	public Set<String> mutationFields() { return mutationFields; }

	/** True when the root field is declared in the programmatic schema (or is introspection). */
	public boolean allows(OperationDefinition.Operation op, String rootField) {
		if (INTROSPECTION.contains(rootField)) return true;
		return switch (op) {
			case QUERY -> queryFields.contains(rootField);
			case MUTATION -> mutationFields.contains(rootField);
			case SUBSCRIPTION -> false;
		};
	}

	/**
	 * Root fields the request would execute: the selected operation's top-level selections,
	 * with fragment spreads and inline fragments followed. Returns the operation and its root
	 * field names; empty when the document does not parse (the executor will report that).
	 */
	public static Optional<RootSelection> rootSelection(String document, String operationName) {
		Document doc;
		try {
			doc = new Parser().parseDocument(document);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
		Map<String, FragmentDefinition> fragments = new java.util.HashMap<>();
		List<OperationDefinition> ops = new java.util.ArrayList<>();
		for (Definition<?> d : doc.getDefinitions()) {
			if (d instanceof FragmentDefinition f) fragments.put(f.getName(), f);
			if (d instanceof OperationDefinition o) ops.add(o);
		}
		OperationDefinition chosen = null;
		for (OperationDefinition o : ops) {
			if (operationName == null || operationName.isBlank() || operationName.equals(o.getName())) { chosen = o; break; }
		}
		if (chosen == null) return Optional.empty();
		Set<String> fields = new LinkedHashSet<>();
		collect(chosen.getSelectionSet(), fragments, fields, new HashSet<>());
		return Optional.of(new RootSelection(chosen.getOperation(), fields));
	}

	public record RootSelection(OperationDefinition.Operation operation, Set<String> rootFields) {}

	private static void collect(SelectionSet set, Map<String, FragmentDefinition> fragments, Set<String> out, Set<String> seenFragments) {
		if (set == null) return;
		for (Selection<?> s : set.getSelections()) {
			if (s instanceof graphql.language.Field f) {
				out.add(f.getName());
			} else if (s instanceof FragmentSpread fs) {
				if (seenFragments.add(fs.getName())) {
					FragmentDefinition fd = fragments.get(fs.getName());
					if (fd != null) collect(fd.getSelectionSet(), fragments, out, seenFragments);
				}
			} else if (s instanceof InlineFragment inl) {
				collect(inl.getSelectionSet(), fragments, out, seenFragments);
			}
		}
	}

	// ------------------------------------------------------------------ programmatic SDL bundle

	public static final String SHARED_SCHEMA = "schema/schema.graphqls";

	private volatile String bundle;

	/**
	 * The programmatic API contract as one self-contained SDL document: {@code type Query} /
	 * {@code type Mutation} holding exactly the programmatic root fields, plus every type,
	 * input, enum, interface, union, scalar and directive reachable from them (walked from the
	 * shared schema). Nothing user-facing leaks in. This is what client generators consume
	 * (rearm-client-go, the Terraform provider) and what GET /api/programmatic/schema serves.
	 */
	public String bundleSdl() {
		String b = bundle;
		if (b == null) {
			synchronized (this) {
				if (bundle == null) {
					try {
						bundle = buildBundle(
								new String(new ClassPathResource(SHARED_SCHEMA).getInputStream().readAllBytes(), StandardCharsets.UTF_8),
								new String(new ClassPathResource(PROGRAMMATIC_SCHEMA).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
					} catch (IOException e) {
						throw new IllegalStateException("cannot read schema files", e);
					}
				}
				b = bundle;
			}
		}
		return b;
	}

	static String buildBundle(String sharedSdl, String programmaticSdl) {
		Document shared = new Parser().parseDocument(sharedSdl);
		Document prog = new Parser().parseDocument(programmaticSdl);
		Map<String, List<Definition<?>>> byName = new java.util.HashMap<>();
		List<graphql.language.DirectiveDefinition> directives = new java.util.ArrayList<>();
		// BOTH documents. A type declared in programmatic.graphqls is part of the programmatic
		// contract by definition -- indexing only the shared schema made such a type invisible to
		// the bundle while the server itself was perfectly happy with it, so the breakage showed
		// up nowhere until somebody generated a client.
		for (Document doc : List.of(shared, prog)) {
			for (Definition<?> d : doc.getDefinitions()) {
				if (d instanceof ObjectTypeExtensionDefinition) continue; // root extensions are handled below
				if (d instanceof graphql.language.TypeDefinition<?> td) byName.computeIfAbsent(td.getName(), k -> new java.util.ArrayList<>()).add(d);
				else if (d instanceof graphql.language.DirectiveDefinition dd) directives.add(dd);
			}
		}
		// interface -> implementing object types, so a reachable interface brings its implementations
		Map<String, List<String>> implementations = new java.util.HashMap<>();
		for (Document doc : List.of(shared, prog)) {
			for (Definition<?> d : doc.getDefinitions()) {
				if (d instanceof ObjectTypeDefinition o && !(d instanceof ObjectTypeExtensionDefinition)) {
					for (graphql.language.Type<?> t : o.getImplements()) implementations.computeIfAbsent(baseName(t), k -> new java.util.ArrayList<>()).add(o.getName());
				}
			}
		}
		Set<String> reachable = new LinkedHashSet<>();
		java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
		List<FieldDefinition> queryFields = new java.util.ArrayList<>();
		List<FieldDefinition> mutationFields = new java.util.ArrayList<>();
		for (Definition<?> d : prog.getDefinitions()) {
			if (d instanceof ObjectTypeExtensionDefinition ext) {
				List<FieldDefinition> target = "Query".equals(ext.getName()) ? queryFields : "Mutation".equals(ext.getName()) ? mutationFields : null;
				if (target == null) continue;
				for (FieldDefinition fd : ext.getFieldDefinitions()) {
					target.add(fd);
					queue.add(baseName(fd.getType()));
					for (graphql.language.InputValueDefinition iv : fd.getInputValueDefinitions()) queue.add(baseName(iv.getType()));
				}
			}
		}
		while (!queue.isEmpty()) {
			String name = queue.poll();
			if (name == null || !reachable.add(name)) continue;
			for (Definition<?> d : byName.getOrDefault(name, List.of())) {
				if (d instanceof ObjectTypeDefinition o) {
					for (FieldDefinition fd : o.getFieldDefinitions()) {
						queue.add(baseName(fd.getType()));
						for (graphql.language.InputValueDefinition iv : fd.getInputValueDefinitions()) queue.add(baseName(iv.getType()));
					}
					for (graphql.language.Type<?> t : o.getImplements()) queue.add(baseName(t));
				} else if (d instanceof graphql.language.InterfaceTypeDefinition i) {
					for (FieldDefinition fd : i.getFieldDefinitions()) {
						queue.add(baseName(fd.getType()));
						for (graphql.language.InputValueDefinition iv : fd.getInputValueDefinitions()) queue.add(baseName(iv.getType()));
					}
					queue.addAll(implementations.getOrDefault(name, List.of()));
				} else if (d instanceof graphql.language.UnionTypeDefinition u) {
					for (graphql.language.Type<?> t : u.getMemberTypes()) queue.add(baseName(t));
				} else if (d instanceof graphql.language.InputObjectTypeDefinition in) {
					for (graphql.language.InputValueDefinition iv : in.getInputValueDefinitions()) queue.add(baseName(iv.getType()));
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("# ReARM programmatic API contract: the root fields declared in programmatic.graphqls and\n");
		sb.append("# every type they reach, whether declared there or in the shared schema.\n");
		sb.append("# served at /api/programmatic/schema. Consumed by rearm-client-go and the Terraform provider.\n\n");
		for (graphql.language.DirectiveDefinition dd : directives) sb.append(graphql.language.AstPrinter.printAst(dd)).append("\n\n");
		sb.append(graphql.language.AstPrinter.printAst(ObjectTypeDefinition.newObjectTypeDefinition().name("Query").fieldDefinitions(queryFields).build())).append("\n\n");
		sb.append(graphql.language.AstPrinter.printAst(ObjectTypeDefinition.newObjectTypeDefinition().name("Mutation").fieldDefinitions(mutationFields).build())).append("\n\n");
		for (String name : reachable) {
			for (Definition<?> d : byName.getOrDefault(name, List.of())) sb.append(graphql.language.AstPrinter.printAst(d)).append("\n\n");
		}
		return sb.toString();
	}

	private static String baseName(graphql.language.Type<?> t) {
		while (t instanceof graphql.language.NonNullType || t instanceof graphql.language.ListType) {
			t = t instanceof graphql.language.NonNullType nn ? nn.getType() : ((graphql.language.ListType) t).getType();
		}
		return t instanceof graphql.language.TypeName tn ? tn.getName() : null;
	}

	/** Field names declared on {@code extend type Query} / {@code extend type Mutation} in an SDL string. */
	static Map<String, Set<String>> rootFieldsOfExtensions(String sdl) {
		Document doc = new Parser().parseDocument(sdl);
		Map<String, Set<String>> out = new java.util.HashMap<>();
		for (Definition<?> d : doc.getDefinitions()) {
			if (d instanceof ObjectTypeExtensionDefinition ext && ("Query".equals(ext.getName()) || "Mutation".equals(ext.getName()))) {
				Set<String> names = out.computeIfAbsent(ext.getName(), k -> new LinkedHashSet<>());
				for (FieldDefinition fd : ext.getFieldDefinitions()) names.add(fd.getName());
			}
		}
		return out;
	}
}
