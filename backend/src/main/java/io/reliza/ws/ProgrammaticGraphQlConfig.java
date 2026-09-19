/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.webmvc.GraphQlHttpHandler;
import org.springframework.graphql.server.webmvc.GraphQlRequestPredicates;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import name.nkonev.multipart.spring.graphql.server.webmvc.MultipartGraphQlHttpHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Second GraphQL endpoint for API keys. It runs the same executor as /graphql (so every
 * data fetcher, DGS context and multipart upload path is shared) but has its own stateless
 * security filter chain (no CSRF, no session, no OAuth2 resource server; see App) and
 * {@link ProgrammaticEndpointInterceptor} restricts the root fields to the ones declared in
 * {@code programmatic.graphqls}. The path carries no version segment: the programmatic
 * schema evolves additively with deprecations, as is the GraphQL convention. /graphql is
 * unchanged, which keeps every existing client (CLI, CD, integrations) working as before.
 */
@Configuration
public class ProgrammaticGraphQlConfig {

	public static final String PATH = "/api/programmatic/graphql";

	@Bean
	public RouterFunction<ServerResponse> programmaticGraphQlRouterFunction(GraphQlHttpHandler httpHandler,
			WebGraphQlHandler webGraphQlHandler, JsonMapper jsonMapper) {
		MultipartGraphQlHttpHandler multipart = new MultipartGraphQlHttpHandler(webGraphQlHandler, new JacksonJsonHttpMessageConverter(jsonMapper));
		return RouterFunctions.route()
				.POST(PATH, RequestPredicates.contentType(MediaType.MULTIPART_FORM_DATA), multipart::handleMultipartRequest)
				.route(GraphQlRequestPredicates.graphQlHttp(PATH), httpHandler::handleRequest)
				.build();
	}
}
