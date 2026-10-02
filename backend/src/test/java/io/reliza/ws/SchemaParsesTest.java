/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

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
}
