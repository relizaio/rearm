/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import graphql.language.Document;
import graphql.language.Node;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;

/**
 * schema.graphqls is valid GraphQL SYNTAX.
 *
 * <p>The gap is not obvious. The enum-sync tests used to read the schema as TEXT (a regex
 * per enum block), so a file that no longer parsed passed every one of them.
 * {@code GraphQlSchemaEnumSyncTest} now parses it with graphql-java, but a syntax error there
 * is a setup exception about enums, not this named failure. The Java compiler never reads it
 * either, and {@code validate-graphql} in the UI validates DOCUMENTS against a schema it
 * assumes is well-formed.
 *
 * <p>So the only thing that noticed a broken schema was starting a Spring context, where the
 * parser failure propagates through {@code executionGraphQlService} into every
 * {@code @SpringBootTest} in the suite as "ApplicationContext failure threshold exceeded".
 * That is 479 errors, twelve minutes into a full run, none of which name the schema.
 *
 * <p>This test exists to turn that into one named failure in milliseconds. It is deliberately
 * a PLAIN JUnit test with no Spring context: a syntax break must be reportable without
 * starting the thing the syntax break prevents from starting.
 *
 * <p>It checks SYNTAX ONLY. Type-level validity -- an unknown type in a field, a field on a
 * type that does not exist -- is a different failure that the context still catches; this is
 * the cheap gate that catches the stupid one, which is the one that actually happened: an
 * enum relocated into the middle of a neighbouring docstring, truncating it and leaving a
 * dangling triple quote.
 */
class SchemaParsesTest {

	/**
	 * The parser's DoS ceiling, raised for this check only.
	 *
	 * <p>graphql-java caps a parse at 15,000 grammar tokens by default, which is a defence
	 * against hostile INPUT. This schema is our own, it is far larger than that, and the
	 * production path raises the ceiling for the same reason -- so parsing it with the default
	 * fails on the limit rather than on anything wrong with the file. Left unraised, this test
	 * would fail permanently and be deleted by whoever hit it next, which is worse than not
	 * having it.
	 */
	private static final int MAX_TOKENS = 1_000_000;

	@Test
	void schemaGraphqlsIsSyntacticallyValid() {
		String schema = readSchema();
		ParserOptions options = ParserOptions.newParserOptions()
				.maxTokens(MAX_TOKENS)
				.maxCharacters(Integer.MAX_VALUE)
				.build();
		try {
			assertNotNull(new Parser().parseDocument(ParserEnvironment.newParserEnvironment()
							.document(schema).parserOptions(options).build()),
					"parseDocument returned null for schema.graphqls");
		} catch (Exception e) {
			// The parser's message carries the line and the offending token, which is the
			// whole point of failing here rather than in a context load.
			fail("schema.graphqls is not valid GraphQL: " + e.getMessage()
					+ "\n\nThis breaks EVERY @SpringBootTest in the suite via"
					+ " executionGraphQlService, reported only as 'ApplicationContext failure"
					+ " threshold exceeded' with no mention of the schema.");
		}
	}

	private static String readSchema() {
		try (InputStream in = SchemaParsesTest.class.getResourceAsStream("/schema/schema.graphqls")) {
			if (in == null) throw new IllegalStateException("schema.graphqls not on test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * The three schema files together contain no UNREACHABLE type: every type defined is
	 * reachable from Query, Mutation or Subscription by following field, argument, union
	 * and interface references.
	 *
	 * <p>An unreachable type is not a style complaint. GraphQL has no way to query one, so a
	 * type that nothing points at is a feature with no read path -- the backend stores the
	 * data, the client cannot ask for it, and nothing fails. That is exactly what happened
	 * here: {@code DocumentRef} was defined in full, with docstrings, while {@code Release}
	 * never got the {@code document} field that returns it. The Java compiled, the schema
	 * parsed, the suite was green, and the UI query that read {@code release.document} was
	 * rejected by the server at runtime.
	 *
	 * <p>Checked as TEXT across all three files rather than against the runtime schema,
	 * because the runtime schema is assembled by a Spring context this test deliberately
	 * does not start (see the class docs).
	 */
	@Test
	void everyDefinedTypeIsReachable() {
		Map<String,String> defined = new LinkedHashMap<>();
		Set<String> referenced = new LinkedHashSet<>();
		for (String file : SCHEMA_FILES) {
			Document doc = parse(readResource("/schema/" + file), file);
			for (var def : doc.getDefinitions()) {
				if (def instanceof TypeDefinition<?> td) defined.put(td.getName(), file);
				collectTypeNames(def, referenced);
			}
		}
		Set<String> unreachable = new TreeSet<>(defined.keySet());
		unreachable.removeAll(referenced);
		unreachable.removeAll(ROOTS);
		unreachable.removeAll(KNOWN_DEAD);
		if (!unreachable.isEmpty()) {
			StringBuilder sb = new StringBuilder("Type(s) defined but reachable from nothing:\n");
			unreachable.forEach(n -> sb.append("  ").append(n).append("  (").append(defined.get(n)).append(")\n"));
			fail(sb + "\nNo client can query these. Either give them a field on a reachable type"
					+ " -- which is usually the whole point of having defined them -- or delete them."
					+ " If a type is genuinely dead and staying, add it to KNOWN_DEAD with a reason.");
		}
	}

	/**
	 * Types reachable from nothing BEFORE this check existed. Listed rather than deleted: each
	 * is unqueryable today whether it is in the schema or not, so removing them is a separate,
	 * unrelated change. Nothing may be added here to make a new failure go away -- a newly
	 * unreachable type is a missing field, which is the defect this test exists to name.
	 */
	private static final Set<String> KNOWN_DEAD = Set.of(
			"ApprovalEntryState", "ExternalBom", "ExternalBomInput",
			"ReleaseSbomExportInput", "ReleaseStatus");

	private static final Set<String> ROOTS = Set.of("Query", "Mutation", "Subscription");

	private static final List<String> SCHEMA_FILES =
			List.of("schema.graphqls", "user.graphqls", "programmatic.graphqls");

	/** Every TypeName anywhere under this node: field types, argument types, union members, implements. */
	private static void collectTypeNames(Node<?> root, Set<String> into) {
		Deque<Node<?>> stack = new ArrayDeque<>();
		stack.push(root);
		while (!stack.isEmpty()) {
			Node<?> n = stack.pop();
			if (n instanceof TypeName tn) into.add(tn.getName());
			for (Node<?> child : n.getChildren()) stack.push(child);
		}
	}

	private static Document parse(String sdl, String name) {
		ParserOptions options = ParserOptions.newParserOptions()
				.maxTokens(MAX_TOKENS)
				.maxCharacters(Integer.MAX_VALUE)
				.build();
		try {
			return new Parser().parseDocument(ParserEnvironment.newParserEnvironment()
					.document(sdl).parserOptions(options).build());
		} catch (Exception e) {
			throw new IllegalStateException(name + " is not valid GraphQL: " + e.getMessage(), e);
		}
	}

	private static String readResource(String path) {
		try (InputStream in = SchemaParsesTest.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException(path + " not on test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Every type a field refers to is defined somewhere in the three files.
	 *
	 * <p>The reachability check above looks the other way -- a type nothing points at -- and a
	 * syntax check sees neither. A field whose type does not exist parses cleanly and then fails
	 * the Spring context at startup, twelve minutes into a suite, reported as
	 * "ApplicationContext failure threshold exceeded" with no mention of the schema. This is the
	 * same defect the file-level parse test exists to name, one level up.
	 *
	 * <p>It caught {@code questionStack: [AgentQuestionFrame]} pointing at a type a silent
	 * find-and-replace had failed to insert.
	 */
	@Test
	void everyReferencedTypeIsDefined() {
		Set<String> defined = new TreeSet<>(BUILT_IN_SCALARS);
		Set<String> referenced = new LinkedHashSet<>();
		for (String file : SCHEMA_FILES) {
			Document doc = parse(readResource("/schema/" + file), file);
			for (var def : doc.getDefinitions()) {
				if (def instanceof TypeDefinition<?> td) defined.add(td.getName());
				collectTypeNames(def, referenced);
			}
		}
		Set<String> undefined = new TreeSet<>(referenced);
		undefined.removeAll(defined);
		if (!undefined.isEmpty()) {
			fail("Field(s) refer to type(s) nothing defines: " + undefined
					+ "\n\nThis parses and then fails the Spring context at startup, where the"
					+ " message names neither the type nor the schema.");
		}
	}

	/** Defined by the GraphQL spec rather than by these files. */
	private static final Set<String> BUILT_IN_SCALARS =
			Set.of("String", "Int", "Float", "Boolean", "ID");
}
