/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;

/**
 * The session writes answer only a key that may read the session (task 0192a587, round 2, T-1):
 * each of them returns the session, so a write open to every key in the org handed any of them the
 * read §3.2 refuses -- and let it rename or close another agent's session besides. Called through
 * the real resolvers with the principal the programmatic filter leaves on the request.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionWritesVisibilityTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentDataFetcher fetcher;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the authorization under test refuses both first. */
	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private ApiKey agentKey(Organization org, PermissionType level) throws RelizaException {
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "session writes test", WU);
		apiKeyService.setPermissionsOnApiKey(ak.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), level, Set.of(PermissionFunction.AGENT), null)), WU);
		return ak;
	}

	private DgsDataFetchingEnvironment as(ApiKey key, Map<String, Object> arguments) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.9"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	private AgentSessionData session(Organization org, ApiKey owner) throws RelizaException {
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"producer-" + UUID.randomUUID(), null, null, null, WU);
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), owner.getUuid(),
				"producer-" + UUID.randomUUID(), "the producer's session", null, null, WU);
	}

	private static Map<String, Object> rename(AgentSessionData s, String title) {
		return Map.of("updateMeta", Map.of("uuid", s.getUuid().toString(), "title", title));
	}

	@Test
	public void anotherKeyOfTheOrgCanNeitherTouchRenameNorCloseTheSession() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey producer = agentKey(org, PermissionType.READ_WRITE);
		ApiKey reviewer = agentKey(org, PermissionType.READ_WRITE);
		AgentSessionData s = session(org, producer);

		for (RelizaException e : List.of(
				assertThrows(RelizaException.class, () -> fetcher.sessionTouchProgrammatic(s.getUuid(), as(reviewer, Map.of()))),
				assertThrows(RelizaException.class, () -> fetcher.sessionUpdateMetaProgrammatic(as(reviewer, rename(s, "renamed by another key")))),
				assertThrows(RelizaException.class, () -> fetcher.sessionCloseProgrammatic(s.getUuid(), as(reviewer, Map.of()))))) {
			assertEquals("Session not found: " + s.getUuid(), e.getMessage(), "the answer an unknown uuid gets");
		}
		AgentSessionData after = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		assertEquals("the producer's session", after.getTitle(), "not renamed");
		assertEquals(AgentSessionData.SessionStatus.OPEN, after.getStatus(), "not closed");
	}

	@Test
	public void theSessionsOwnKeyAndAnAdminKeyStillWrite() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey producer = agentKey(org, PermissionType.READ_WRITE);
		ApiKey admin = agentKey(org, PermissionType.ADMIN);
		AgentSessionData s = session(org, producer);

		assertEquals(s.getUuid(), fetcher.sessionTouchProgrammatic(s.getUuid(), as(producer, Map.of())).getUuid());
		assertEquals("renamed by its own key",
				fetcher.sessionUpdateMetaProgrammatic(as(producer, rename(s, "renamed by its own key"))).getTitle());
		assertEquals("renamed by an admin",
				fetcher.sessionUpdateMetaProgrammatic(as(admin, rename(s, "renamed by an admin"))).getTitle());
		assertTrue(AgentSessionData.SessionStatus.OPEN != fetcher.sessionCloseProgrammatic(s.getUuid(), as(producer, Map.of())).getStatus(),
				"its own key closes it");
	}
}
