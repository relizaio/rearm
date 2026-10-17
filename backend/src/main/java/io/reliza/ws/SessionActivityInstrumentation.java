/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.service.AgentSessionService;
import io.reliza.service.ApiKeyService;
import lombok.extern.slf4j.Slf4j;

/**
 * Any programmatic call made with a session's id is activity for that session (task 6e7fe6fe).
 * Board work -- task next, assign, sign-off, doc publish, check runs, mergeplan -- used to leave
 * {@code lastActivityAt} untouched, so a session that polled and published all day read idle
 * since its orientation report and was closed by the idle sweep with its work mid-flight.
 *
 * <p>One place, not a line in every resolver, so a resolver added later cannot forget it: every
 * root {@code *Programmatic} field that names the caller's session is wrapped: by a
 * {@code sessionUuid} argument, the {@code session} argument attestations and lock releases use, or a
 * {@code sessionUuid} field of an input object (doc publish, task register, artifacts, usage), and only
 * when the calling key is the session's own (round 2, T-1): another key reading a session is not it working. The stamp is
 * written only after the resolver returns, so a call its resolver refused -- a session of another
 * organization, one that does not hold the task -- keeps no one's session alive. The write is
 * throttled and in its own transaction ({@link AgentSessionService#recordActivity}); a failure is
 * logged and never fails the call.
 */
@Slf4j
@Component
public class SessionActivityInstrumentation extends SimplePerformantInstrumentation {

	/** The arguments a programmatic resolver names its caller's session by. */
	public static final List<String> SESSION_ARGUMENTS = List.of("sessionUuid", "session");

	private static final Set<String> ROOT_TYPES = Set.of("Query", "Mutation");

	@Autowired @Lazy private AgentSessionService agentSessionService;
	@Autowired @Lazy private ApiKeyService apiKeyService;

	/** Whether a successful call of this field counts as activity for the session it names. */
	public static boolean counts(GraphQLObjectType parent, GraphQLFieldDefinition field) {
		if (null == parent || null == field || !ROOT_TYPES.contains(parent.getName())
				|| !field.getName().endsWith("Programmatic")) {
			return false;
		}
		for (GraphQLArgument a : field.getArguments()) {
			if (SESSION_ARGUMENTS.contains(a.getName())) return true;
			if (GraphQLTypeUtil.unwrapAll(a.getType()) instanceof GraphQLInputObjectType input
					&& null != input.getField(SESSION_ARGUMENTS.get(0))) {
				return true;
			}
		}
		return false;
	}

	/** The session a call names: a session argument, else an input object's sessionUuid. */
	static UUID sessionNamedBy(Map<String, Object> arguments) {
		for (String a : SESSION_ARGUMENTS) {
			UUID s = sessionOf(arguments.get(a));
			if (null != s) return s;
		}
		for (Object value : arguments.values()) {
			if (value instanceof Map<?, ?> input) {
				UUID s = sessionOf(input.get(SESSION_ARGUMENTS.get(0)));
				if (null != s) return s;
			}
		}
		return null;
	}

	@Override
	public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters,
			InstrumentationState state) {
		GraphQLFieldDefinition field = parameters.getExecutionStepInfo().getFieldDefinition();
		if (!counts(parameters.getExecutionStepInfo().getObjectType(), field)) return dataFetcher;
		return env -> {
			Object result = dataFetcher.get(env);
			UUID session = sessionNamedBy(env.getArguments());
			if (null == session) return result;
			Supplier<UUID> caller = () -> callerKeyOf(env);
			if (result instanceof CompletionStage<?> stage) {
				return stage.thenApply(r -> {
					record(session, caller);
					return r;
				});
			}
			record(session, caller);
			return result;
		};
	}

	/**
	 * The key that made the call (round 2, T-1): the one the programmatic filter verified, or, for a
	 * legacy basic-auth header, the key it matches. Only a session's own key makes it active.
	 */
	private UUID callerKeyOf(DataFetchingEnvironment env) {
		try {
			DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(env);
			if (null == requestData || !(requestData.getWebRequest() instanceof ServletWebRequest request)) return null;
			Object verified = request.getRequest().getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
			AuthHeaderParse ahp = verified instanceof AuthHeaderParse a ? a
					: null == requestData.getHeaders() || null == requestData.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)
							? null : AuthHeaderParse.parseAuthHeader(requestData.getHeaders(), request.getRequest().getRemoteAddr());
			return null == ahp ? null : apiKeyService.isMatchingApiKey(ahp);
		} catch (Exception e) {
			log.error("Could not tell which key made a call that names a session", e);
			return null;
		}
	}

	private static UUID sessionOf(Object raw) {
		if (raw instanceof UUID u) return u;
		if (null == raw) return null;
		try {
			return UUID.fromString(raw.toString());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private void record(UUID session, Supplier<UUID> caller) {
		try {
			agentSessionService.recordActivity(session, caller, ZonedDateTime.now());
		} catch (Exception e) {
			log.error("Could not record activity for session {}", session, e);
		}
	}
}
