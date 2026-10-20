/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;

import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.exceptions.ComponentRevisionConflictException;
import io.reliza.exceptions.RebomResponseTooLargeException;

/**
 * Which handler Spring routes an exception to, through the same resolver the application wires
 * from the {@code @ControllerAdvice} (task SCORE-19, design ADR-3). Calling a handler method
 * directly does not hold its {@code @GraphQlExceptionHandler}: without it, the exception falls back
 * to {@link GraphQLExceptionHandlers#handleGeneric} and an export reads "Internal server error"
 * again.
 */
class GraphQLExceptionHandlersRegistrationTest {

	private StaticApplicationContext context;
	private DataFetcherExceptionResolver resolver;

	@BeforeEach
	void setUp() {
		context = new StaticApplicationContext();
		context.registerSingleton("graphQLExceptionHandlers", GraphQLExceptionHandlers.class);
		context.refresh();
		AnnotatedControllerConfigurer configurer = new AnnotatedControllerConfigurer();
		configurer.setApplicationContext(context);
		configurer.afterPropertiesSet();
		resolver = configurer.getExceptionResolver();
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	private GraphQLError resolve(Throwable ex) {
		DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(GraphQLContext.newContext().build())
				.build();
		List<GraphQLError> errors = resolver.resolveException(ex, env).block();
		assertEquals(1, errors.size(), String.valueOf(errors));
		return errors.get(0);
	}

	// T-4 (tests/SCORE-19/run-1.md, ADR-3)
	@Test
	void anExportTooLargeToReadIsRoutedToItsNamedError() {
		GraphQLError err = resolve(new RebomResponseTooLargeException(new IllegalStateException("cause text")));
		assertEquals(GraphQLExceptionHandlers.EXPORT_TOO_LARGE_MESSAGE, err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
	}

	// TPF-61 row 20c: the stale-save refusal reaches its own handler, not handleReliza (which sets no extensions)
	@Test
	void aComponentRevisionConflictIsRoutedToItsHandler() {
		GraphQLError err = resolve(new ComponentRevisionConflictException(4, 5, null, null, null,
				ZonedDateTime.parse("2026-10-13T10:00:00Z")));
		assertEquals("COMPONENT_REVISION_CONFLICT", err.getExtensions().get("code"));
	}

	// The fallback the test above tells apart from: what an unregistered handler leaves behind
	@Test
	void anyOtherRuntimeExceptionIsRoutedToTheOpaqueServerError() {
		String msg = resolve(new RuntimeException("cause text")).getMessage();
		assertTrue(msg.matches("Internal server error \\(ref [0-9a-f]{8}\\)"), msg);
	}
}
