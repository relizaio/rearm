/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;

/**
 * A programmatic call made with a session's id is activity for that session (task 6e7fe6fe), for
 * every session-bound resolver, through the engine rather than the fetcher so the instrumentation
 * is what is tested. A refused call keeps no one's session alive.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SessionActivityInstrumentationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private GraphQlSource graphQlSource;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private JdbcTemplate jdbcTemplate;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private record Caller(ApiKey key, AgentSessionData session) {}

	/** An agent key of the organization, and a session of its agent. */
	private Caller caller(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "activity test", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT),
				null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), identity, "active-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(),
				"active-" + UUID.randomUUID(), "activity test", null, null, WU);
		return new Caller(key, session);
	}

	/** The session's stamp set back an hour, as a session idle since then would read. */
	private void idleForAnHour(AgentSessionData s) {
		double epoch = ZonedDateTime.now().minusHours(1).toEpochSecond();
		jdbcTemplate.update("UPDATE rearm.agent_sessions SET record_data = jsonb_set(record_data, '{lastActivityAt}',"
				+ " to_jsonb(?::numeric)) WHERE uuid = ?", epoch, s.getUuid());
	}

	private ZonedDateTime stamp(AgentSessionData s) {
		return agentSessionService.getSessionData(s.getUuid()).orElseThrow().getLastActivityAt();
	}

	private ExecutionResult as(ApiKey key, String query, AgentSessionData session) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, Map.of("s", session.getUuid().toString()), Map.of(), headers, null,
				new ServletWebRequest(request));
	}

	private ExecutionResult taskNextAs(ApiKey key, AgentSessionData session) {
		return as(key, "query next($s: ID!) { agentTaskNextProgrammatic(sessionUuid: $s) { role } }", session);
	}

	/** An org ADMIN key: it may read any session of the org (#610). */
	private ApiKey adminKey(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "activity admin", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.ADMIN, Set.of(PermissionFunction.AGENT),
				null)), WU);
		return key;
	}

	@Test
	public void anotherKeysReadIsNotTheSessionWorking() throws RelizaException {
		// round 2, T-1: a coordinator or admin checking on a stalled holder must not keep it open
		Organization org = testInitializer.obtainOrganization();
		Caller c = caller(org);
		idleForAnHour(c.session());
		ZonedDateTime before = stamp(c.session());
		String show = "query show($s: ID!) { sessionProgrammatic(sessionUuid: $s) { uuid } }";

		ExecutionResult read = as(adminKey(org), show, c.session());
		assertTrue(read.getErrors().isEmpty(), "the admin may read it: " + read.getErrors());
		assertEquals(before.toInstant().getEpochSecond(), stamp(c.session()).toInstant().getEpochSecond(),
				"another key's read is not the session working");

		ExecutionResult own = as(c.key(), show, c.session());
		assertTrue(own.getErrors().isEmpty(), own.getErrors().toString());
		assertTrue(Duration.between(stamp(c.session()), ZonedDateTime.now()).toMinutes() < 1, "its own key's call is");
	}

	@Test
	public void aPollIsActivityForTheSessionItNames() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Caller c = caller(org);
		idleForAnHour(c.session());
		assertTrue(Duration.between(stamp(c.session()), ZonedDateTime.now()).toMinutes() >= 59, "set up idle");

		ExecutionResult r = taskNextAs(c.key(), c.session());
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		assertTrue(Duration.between(stamp(c.session()), ZonedDateTime.now()).toMinutes() < 1,
				"task next made the session active: " + stamp(c.session()));
	}

	@Test
	public void aRefusedCallKeepsNoOnesSessionAlive() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Caller owner = caller(org);
		Caller stranger = caller(testInitializer.obtainOrganization());
		idleForAnHour(owner.session());
		ZonedDateTime before = stamp(owner.session());

		ExecutionResult r = taskNextAs(stranger.key(), owner.session());
		assertFalse(r.getErrors().isEmpty(), "another organization's key is refused");
		assertEquals(before.toInstant().getEpochSecond(), stamp(owner.session()).toInstant().getEpochSecond(),
				"a refused call is not activity");
	}

	/**
	 * Every session-bound programmatic resolver is covered, by construction: the instrumentation
	 * wraps each root *Programmatic field naming a session by a sessionUuid or session argument, or
	 * by an input object's sessionUuid. A session named any other way would escape it, so none may,
	 * but for the few that need not count: a clientSessionId is an alternative lookup key beside a
	 * sessionUuid, sessionInit writes the stamp itself, and parent and provider sessions are not the
	 * caller's.
	 */
	private static final Set<String> KNOWN_NOT_ACTIVITY = Set.of("clientSessionId", "sessionInit", "parentSession",
			"providerSession");

	@Test
	public void everySessionBoundProgrammaticResolverCountsAsActivity() {
		List<String> covered = new ArrayList<>();
		List<String> escaping = new ArrayList<>();
		for (GraphQLObjectType root : List.of(graphQlSource.schema().getQueryType(), graphQlSource.schema().getMutationType())) {
			for (GraphQLFieldDefinition f : root.getFieldDefinitions()) {
				if (!f.getName().endsWith("Programmatic")) continue;
				if (SessionActivityInstrumentation.counts(root, f)) covered.add(f.getName());
				for (GraphQLArgument a : f.getArguments()) {
					List<String> names = new ArrayList<>(List.of(a.getName()));
					if (GraphQLTypeUtil.unwrapAll(a.getType()) instanceof GraphQLInputObjectType input) {
						input.getFieldDefinitions().forEach(fd -> names.add(fd.getName()));
					}
					names.stream().filter(n -> n.toLowerCase().contains("session")
							&& !SessionActivityInstrumentation.SESSION_ARGUMENTS.contains(n) && !KNOWN_NOT_ACTIVITY.contains(n))
							.forEach(n -> escaping.add(root.getName() + "." + f.getName() + ": " + n));
				}
			}
		}
		assertTrue(escaping.isEmpty(), "session arguments the instrumentation would not see: " + escaping);
		for (String expected : List.of("agentTaskNextProgrammatic", "agentTaskAssignProgrammatic",
				"agentTaskSignOffProgrammatic", "agentTaskReturnProgrammatic", "agentDocumentPublishProgrammatic",
				"attestProgrammatic", "releaseLockProgrammatic", "agentTaskRegisterProgrammatic",
				"sessionAddArtifactProgrammatic")) {
			assertTrue(covered.contains(expected), expected + " is not covered: " + covered);
		}
		assertTrue(covered.size() >= 20, "covered: " + covered);
	}

	@Test
	public void theSessionIsFoundWhereverTheCallNamesIt() {
		UUID s = UUID.randomUUID();
		assertEquals(s, SessionActivityInstrumentation.sessionNamedBy(Map.of("sessionUuid", s.toString())));
		assertEquals(s, SessionActivityInstrumentation.sessionNamedBy(Map.of("lockUuid", "x", "session", s.toString())),
				"an attestation or lock release names it session");
		assertEquals(s, SessionActivityInstrumentation.sessionNamedBy(Map.of("input", Map.of("sessionUuid", s.toString(),
				"type", "TEST_REPORT"))), "doc publish names it inside its input");
		assertEquals(null, SessionActivityInstrumentation.sessionNamedBy(Map.of("taskUuid", s.toString())),
				"a task uuid is not a session");
		assertEquals(null, SessionActivityInstrumentation.sessionNamedBy(Map.of("sessionUuid", "not-a-uuid")));
	}
}
