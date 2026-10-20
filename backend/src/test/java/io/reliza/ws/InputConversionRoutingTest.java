/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

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
import io.reliza.common.Utils;
import io.reliza.exceptions.InvalidInputException;
import io.reliza.model.dto.ArtifactDto;
import tools.jackson.core.JacksonException;

/**
 * Which handler Spring routes a conversion failure to (task TPF-63, architecture-1 section 4 row 2): a
 * refused client input is BAD_REQUEST with its message, a stored-data failure stays the opaque server
 * error. The rig of {@link GraphQLExceptionHandlersRegistrationTest}, copied.
 */
class InputConversionRoutingTest {

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

	// row 2 case 1
	@Test
	void aRefusedInputIsABadRequestWithItsMessage() {
		String message = "artifactInput.artifact.storedIn: must be one of REARM, EXTERNALLY";
		GraphQLError err = resolve(new InvalidInputException(message));
		assertEquals(message, err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
		assertTrue(null == err.getExtensions() || err.getExtensions().isEmpty(), String.valueOf(err.getExtensions()));
	}

	// row 2 case 2
	@Test
	void aStoredDataFailureStaysTheServerError() {
		JacksonException e = assertThrows(JacksonException.class,
				() -> Utils.OM.convertValue(Map.of("storedIn", "EXTERNAL"), ArtifactDto.class));
		GraphQLError err = resolve(e);
		assertTrue(err.getMessage().matches("Internal server error \\(ref [0-9a-f]{8}\\)"), err.getMessage());
		assertTrue(String.valueOf(err.getExtensions().get("ref")).matches("[0-9a-f]{8}"),
				String.valueOf(err.getExtensions()));
		assertFalse(err.getMessage().contains("EXTERNAL"), err.getMessage());
	}
}
