/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.stereotype.Component;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorBuilder;
import graphql.language.OperationDefinition;
import io.reliza.ws.ProgrammaticSchemaRegistry.RootSelection;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Enforces the schema split on the programmatic endpoint: a request to
 * {@link ProgrammaticGraphQlConfig#PATH} may only select root fields declared in
 * {@code programmatic.graphqls}. Requests to /graphql are untouched, so existing clients
 * that send programmatic operations there keep working.
 */
@Component
@Slf4j
public class ProgrammaticEndpointInterceptor implements WebGraphQlInterceptor {

	private final ProgrammaticSchemaRegistry registry;

	public ProgrammaticEndpointInterceptor(ProgrammaticSchemaRegistry registry) {
		this.registry = registry;
	}

	@Override
	public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
		String path = request.getUri().getPath();
		if (path == null || !path.endsWith(ProgrammaticGraphQlConfig.PATH)) {
			return chain.next(request);
		}
		Optional<RootSelection> sel = ProgrammaticSchemaRegistry.rootSelection(request.getDocument(), request.getOperationName());
		if (sel.isEmpty()) {
			return chain.next(request); // unparseable or no operation: let the executor produce the standard error
		}
		Set<String> refused = sel.get().rootFields().stream()
				.filter(f -> !registry.allows(sel.get().operation(), f))
				.collect(Collectors.toCollection(java.util.LinkedHashSet::new));
		if (refused.isEmpty()) {
			return chain.next(request);
		}
		log.warn("Programmatic endpoint refused non-programmatic root field(s) {} ({} {})", refused,
				sel.get().operation(), request.getOperationName());
		ExecutionResult result = ExecutionResultImpl.newExecutionResult()
				.addError(GraphqlErrorBuilder.newError()
						.message("Not available on the programmatic endpoint: " + String.join(", ", refused)
								+ ". These operations are for signed-in users; use /graphql with a user session.")
						.build())
				.build();
		ExecutionInput input = ExecutionInput.newExecutionInput(request.getDocument())
				.operationName(request.getOperationName()).build();
		return Mono.just(new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(input, result)));
	}
}
