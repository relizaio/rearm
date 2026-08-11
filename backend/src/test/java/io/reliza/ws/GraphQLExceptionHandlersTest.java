/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;

/**
 * Pins the user-facing error-surfacing contract the GitHub PR_VALIDATE
 * validation depends on: a {@link RelizaException} (a business/validation error)
 * must reach the client as a BAD_REQUEST carrying its message, while any other
 * exception is deliberately opaque.
 *
 * <p>{@code OssIntegrationDataFetcher#createIntegration} lets the
 * {@link RelizaException} propagate to {@link GraphQLExceptionHandlers#handleReliza}
 * for exactly this reason. Wrapping it in a {@code RuntimeException} (the bug this
 * follow-up fixes) would route it through {@link GraphQLExceptionHandlers#handleGeneric}
 * and surface a generic "Internal server error" instead of the actionable message.
 */
public class GraphQLExceptionHandlersTest {

	private final GraphQLExceptionHandlers handlers = new GraphQLExceptionHandlers();

	@Test
	public void relizaException_surfacesMessageAsBadRequest() {
		String msg = "GitHub integration with PR_VALIDATE capability requires a "
				+ "non-empty GitHub App private key (secret); none was provided";
		GraphQLError err = handlers.handleReliza(new RelizaException(msg));
		assertEquals(msg, err.getMessage(),
				"RelizaException message must reach the client verbatim");
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()),
				"RelizaException must be classified BAD_REQUEST, not a server error");
	}

	@Test
	public void genericException_isOpaqueServerError() {
		// The trap this follow-up avoids: wrapping a validation RelizaException in a
		// RuntimeException routes it here and hides the reason from the user.
		GraphQLError err = handlers.handleGeneric(new RuntimeException("boom: secret was blank"));
		assertEquals("Internal server error", err.getMessage());
		assertFalse(err.getMessage().contains("blank"),
				"the generic handler must not leak the underlying exception message");
	}
}
