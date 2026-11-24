/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

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
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.ExecutionResult;
import graphql.GraphQLContext;
import graphql.language.OperationDefinition.Operation;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.ws.AgentTaskDataFetcher;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;
import io.reliza.ws.ProgrammaticSchemaRegistry;

/**
 * Task TPF-171, built by TPF-172 (D6, D10; threats 2 and 3): the two verdict mutations through the real resolvers, with
 * the principal the programmatic filter leaves on the request, modelled on {@code AgentCoordinatorHoldProgrammaticTest}.
 * Rows T-16 (c) and T-17 (d). Kept beside the rig it reuses, as {@code AgentCarriedDesignProgrammaticTest} is, rather
 * than in {@code io.reliza.ws}: the rig is package-private.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentAnswerConfirmationProgrammaticTest extends AgentAnswerRoutingTestBase {

	static final String IP = "198.51.100.17";

	@Autowired AgentTaskDataFetcher fetcher;
	@Autowired LicenseStatus licenseStatus;
	@Autowired ApplicationContext applicationContext;
	@Autowired DgsQueryExecutor dgsQueryExecutor;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the authorization under test refuses both first. */
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

	/** The seat on the rig's board: one session of the key's agent holds it, another does not. */
	record Seat(ApiKey key, AgentSessionData holder, AgentSessionData stranger) {}

	Seat seat(Rig r) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(r.org().getUuid(), ApiTypeEnum.FREEFORM, r.org().getUuid(),
				UUID.randomUUID().toString(), "answer confirmation programmatic", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(r.org().getUuid(),
				PermissionScope.ORGANIZATION, r.org().getUuid(), PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT,
				PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_WRITE),
				null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(r.org().getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(r.org().getUuid(), identity,
				"coordinator-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData holder = agentSessionService.initialize(r.org().getUuid(), agent.getUuid(), key.getUuid(),
				"seat-" + UUID.randomUUID(), "the seat", null, null, WU);
		AgentSessionData stranger = agentSessionService.initialize(r.org().getUuid(), agent.getUuid(), key.getUuid(),
				"other-" + UUID.randomUUID(), "not the seat", null, null, WU);
		agentBoardService.claimCoordinatorSeat(r.board().getUuid(), holder.getUuid(), agent.getUuid(), WU);
		return new Seat(apiKeyService.getApiKey(key.getUuid()).orElseThrow(), holder, stranger);
	}

	/** The programmatic request environment, after the filter, with the verdict as the mutation's argument. */
	DgsDataFetchingEnvironment as(ApiKey key, String verdict) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setRemoteAddr(IP);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, IP));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of("verdict", verdict)).build(), applicationContext);
	}

	/** The coder asked, the architect answered --no-change: the task waits on the verdict. */
	AgentTaskData pendingAnswer(Rig r, String title) throws RelizaException {
		AgentTaskData t = ordinaryAtCoder(r, title);
		coderAsks(r, t, ARCH);
		answer(r, t, Boolean.TRUE, false, ARCH);
		assertEquals(AnswerConfirmationState.PENDING, entry(t).state());
		return reload(t);
	}

	void assertStillPending(AgentTaskData t) {
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(t).getStatus());
		assertEquals(AnswerConfirmationState.PENDING, entry(t).state());
		assertEquals(1, reload(t).getAnswerConfirmations().size());
	}

	/** Both calls refuse the same way: the same exception type and message. */
	static void assertSameRefusal(Executable expected, Executable actual) {
		Throwable a = assertThrows(Throwable.class, expected);
		Throwable b = assertThrows(Throwable.class, actual);
		assertEquals(a.getClass(), b.getClass(), b.toString());
		assertEquals(a.getMessage(), b.getMessage());
	}

	/** T-17 (d), the seat mutation: a worker session that does not hold the seat, and a key of another org. */
	@Test
	public void onlyTheSeatOfTheBoardsOrgGivesTheSeatsVerdict() throws Exception {
		Rig r = reviewedTpf();
		Seat s = seat(r);
		AgentTaskData t = pendingAnswer(r, "T-17d seat");

		AccessDeniedException stranger = assertThrows(AccessDeniedException.class, () -> fetcher
				.agentTaskConfirmAnswerProgrammatic(t.getUuid(), s.stranger().getUuid(), null, as(s.key(), "CONFIRMED")));
		assertTrue(stranger.getMessage().contains("Session does not hold the coordinator seat on board"),
				stranger.getMessage());
		assertStillPending(t);

		Organization other = testInitializer.obtainOrganization();
		ApiKey foreign = freeformKey(other);
		assertSameRefusal(() -> fetcher.agentTaskLiftHoldProgrammatic(t.getUuid(), s.holder().getUuid(), null, null,
				as(foreign, "CONFIRMED")), () -> fetcher.agentTaskConfirmAnswerProgrammatic(t.getUuid(),
						s.holder().getUuid(), null, as(foreign, "CONFIRMED")));
		assertStillPending(t);

		AgentTaskData confirmed = fetcher.agentTaskConfirmAnswerProgrammatic(t.getUuid(), s.holder().getUuid(), "same text",
				as(s.key(), "CONFIRMED"));
		assertQueuedFor(confirmed, "coder");
		assertEquals(AgentActor.ofSession(s.holder().getUuid()), entry(t).settledBy());
		assertEquals("same text", entry(t).note());
		assertTrue(new ProgrammaticSchemaRegistry().allows(Operation.MUTATION, "agentTaskConfirmAnswerProgrammatic"));
		assertTrue(new ProgrammaticSchemaRegistry().allows(Operation.MUTATION, "agentTaskConfirmAnswer"));
	}

	/** T-17 (d), the person mutation: a FREEFORM key is no person; a USER key without board write is refused. */
	@Test
	public void onlyAPersonWithBoardWriteGivesThePersonsVerdict() throws Exception {
		Rig r = reviewedTpf();
		AgentTaskData t = pendingAnswer(r, "T-17d person");

		// PERSON_ONLY, as agentTaskAuthorize answers a FREEFORM key (the constant is package-private to the fetcher)
		ApiKey freeform = freeformKey(r.org());
		assertSameRefusal(() -> fetcher.agentTaskAuthorize(t.getUuid(), "coder", null, null, null, null,
				as(freeform, "CONFIRMED")), () -> fetcher.agentTaskConfirmAnswer(t.getUuid(), null, as(freeform, "CONFIRMED")));
		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.agentTaskConfirmAnswer(t.getUuid(), null,
				as(freeform, "CONFIRMED")));
		assertTrue(e.getMessage().contains("person"), e.getMessage());
		assertStillPending(t);

		User reader = person(r.org(), PermissionType.READ_ONLY);
		ApiKey readerKey = personalKey(r.org(), reader, PermissionType.READ_ONLY);
		assertSameRefusal(() -> fetcher.agentTaskAuthorize(t.getUuid(), "coder", null, null, null, null,
				as(readerKey, "CONFIRMED")), () -> fetcher.agentTaskConfirmAnswer(t.getUuid(), null,
						as(readerKey, "CONFIRMED")));
		assertStillPending(t);
	}

	/** 6.1 (D15): the task read serves the record, its rounds and the verdict's actor through the schema. */
	@SuppressWarnings("unchecked")
	@Test
	public void theTaskReadServesTheConfirmations() throws Exception {
		Rig r = reviewedTpf();
		Seat s = seat(r);
		AgentTaskData t = pendingAnswer(r, "6.1 the read");
		fetcher.agentTaskConfirmAnswerProgrammatic(t.getUuid(), s.holder().getUuid(), "wording only",
				as(s.key(), "CONFIRMED"));
		String query = "query($t: ID!) { agentTaskProgrammatic(taskUuid: $t) { answerConfirmations { answeredAt role"
				+ " roleUuid session agent askingRole questionsRelease state settledAt note settledBy { kind uuid }"
				+ " rounds { release specification round version path previous previousRound previousVersion previousPath } }"
				+ " statusHistory { trigger } } }";
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(s.key(), 1, IP));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		ExecutionResult er = dgsQueryExecutor.execute(query, Map.of("t", t.getUuid().toString()), Map.of(), headers, null,
				new ServletWebRequest(request));
		assertTrue(er.getErrors().isEmpty(), er.getErrors().toString());
		Map<String, Object> task = (Map<String, Object>) ((Map<String, Object>) er.getData()).get("agentTaskProgrammatic");
		List<Map<String, Object>> entries = (List<Map<String, Object>>) task.get("answerConfirmations");
		assertEquals(1, entries.size());
		Map<String, Object> e = entries.get(0);
		assertEquals("CONFIRMED", e.get("state"));
		assertEquals("architect", e.get("role"));
		assertEquals("wording only", e.get("note"));
		assertEquals(Map.of("kind", "SESSION", "uuid", s.holder().getUuid().toString()), e.get("settledBy"));
		Map<String, Object> round = ((List<Map<String, Object>>) e.get("rounds")).get(0);
		assertEquals("ARCHITECTURE", round.get("specification"));
		assertEquals(entry(t).rounds().get(0).previous().toString(), round.get("previous"));
		assertTrue(((List<Map<String, Object>>) task.get("statusHistory")).stream()
				.anyMatch(h -> "ANSWER_CONFIRMED".equals(h.get("trigger"))), task.toString());
	}

	/**
	 * T-16 (c): the person mutation with a second personal key of the user whose key the answering agent runs on is
	 * refused CONFIRMED by owner, and may refuse.
	 */
	@Test
	public void theAnswerersOwnerCannotConfirmWithAnotherOfTheirKeys() throws Exception {
		Rig base = reviewedTpf();
		User owner = person(base.org(), PermissionType.ADMIN);
		Rig r = architectOn(base, personalKey(base.org(), owner, PermissionType.ADMIN));
		ApiKey second = personalKey(base.org(), owner, PermissionType.ADMIN);
		AgentTaskData t = pendingAnswer(r, "T-16c second key");

		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.agentTaskConfirmAnswer(t.getUuid(), null,
				as(second, "CONFIRMED")));
		assertEquals("The agent that answered cannot confirm its own answer on task " + t.label()
				+ "; refuse it, or leave it to the coordinator", e.getMessage());
		assertStillPending(t);

		AgentTaskData refused = fetcher.agentTaskConfirmAnswer(t.getUuid(), null, as(second, "REFUSED"));
		assertQueuedFor(refused, "functional_reviewer");
		assertEquals(AnswerConfirmationState.REFUSED, entry(t).state());
		assertEquals(owner.getUuid(), entry(t).settledBy().uuid(), "given as the person the key belongs to");
	}

	/** The resolvers read the verdict once; a word that is neither is refused with the word named, before anything is read. */
	@Test
	public void aVerdictThatIsNeitherWordIsRefusedByBothResolvers() throws Exception {
		Rig r = reviewedTpf();
		Seat s = seat(r);
		AgentTaskData t = pendingAnswer(r, "edge: a bad verdict");
		RelizaException seatRefusal = assertThrows(RelizaException.class, () -> fetcher.agentTaskConfirmAnswerProgrammatic(
				t.getUuid(), s.holder().getUuid(), null, as(s.key(), "MAYBE")));
		assertEquals("verdict is CONFIRMED or REFUSED, not MAYBE", seatRefusal.getMessage());
		User person = person(r.org(), PermissionType.ADMIN);
		ApiKey personal = personalKey(r.org(), person, PermissionType.ADMIN);
		RelizaException personRefusal = assertThrows(RelizaException.class, () -> fetcher.agentTaskConfirmAnswer(t.getUuid(),
				null, as(personal, "MAYBE")));
		assertEquals("verdict is CONFIRMED or REFUSED, not MAYBE", personRefusal.getMessage());
		assertStillPending(t);
	}

	/** T-16 (a) through the seat's resolver: the seat of the agent that answered may refuse, and not confirm. */
	@Test
	public void theSeatOfTheAnsweringAgentCannotConfirmItsOwnAnswerThroughTheResolver() throws Exception {
		Rig base = reviewedTpf();
		ApiKey key = apiKeyService.createObjectApiKey(base.org().getUuid(), ApiTypeEnum.FREEFORM, base.org().getUuid(),
				UUID.randomUUID().toString(), "answerer on the seat", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(base.org().getUuid(),
				PermissionScope.ORGANIZATION, base.org().getUuid(), PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT,
				PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_WRITE),
				null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(base.org().getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(base.org().getUuid(), identity,
				"architect-and-seat-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData worker = agentSessionService.initialize(base.org().getUuid(), agent.getUuid(), null,
				"architect-" + UUID.randomUUID(), "answers", null, null, WU);
		AgentSessionData holder = agentSessionService.initialize(base.org().getUuid(), agent.getUuid(), key.getUuid(),
				"seat-" + UUID.randomUUID(), "the seat", null, null, WU);
		agentBoardService.claimCoordinatorSeat(base.board().getUuid(), holder.getUuid(), agent.getUuid(), WU);
		Rig r = new Rig(base.org(), base.board(), new Worker(agent, worker), base.functional(), base.security(), base.coder(),
				base.tester());
		AgentTaskData t = pendingAnswer(r, "edge: the seat answered");
		ApiKey seatKey = apiKeyService.getApiKey(key.getUuid()).orElseThrow();

		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.agentTaskConfirmAnswerProgrammatic(t.getUuid(),
				holder.getUuid(), null, as(seatKey, "CONFIRMED")));

		assertEquals("The agent that answered cannot confirm its own answer on task " + t.label()
				+ "; refuse it, or leave it to the coordinator", e.getMessage());
		assertStillPending(t);
		AgentTaskData refused = fetcher.agentTaskConfirmAnswerProgrammatic(t.getUuid(), holder.getUuid(), null,
				as(seatKey, "REFUSED"));
		assertQueuedFor(refused, "functional_reviewer");
	}

	/** The request environment of a browser session: the verdict as the argument, no key principal on the request. */
	DgsDataFetchingEnvironment asBrowser(String verdict) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setRemoteAddr(IP);
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), new HttpHeaders(), new ServletWebRequest(request)))
				.accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of("verdict", verdict)).build(), applicationContext);
	}

	/**
	 * T-16 (e) through the resolver (D10, threat 3): a browser session is not compared with the answerer's key owner, so
	 * the person whose personal key the answering agent runs on confirms from the browser. The seat and a personal key
	 * of the same person are refused, as the other tests show.
	 */
	@Test
	public void theOwnerOfTheAnswerersKeyConfirmsFromTheBrowser() throws Exception {
		Rig base = reviewedTpf();
		String sub = "answer-browser-" + UUID.randomUUID();
		String email = "browser-" + UUID.randomUUID().toString().substring(0, 8) + "@answertest.io";
		User owner = userService.createUser("Browser owner", email, true, List.of(base.org().getUuid()), sub,
				OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(owner.getUuid(), base.org().getUuid(), PermissionScope.ORGANIZATION, base.org().getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		Rig r = architectOn(base, personalKey(base.org(), owner, PermissionType.ADMIN));
		AgentTaskData t = pendingAnswer(r, "T-16e browser");
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject(sub)
				.claim("sub", sub).claim("email", email).claim("name", "Browser owner").build()));
		SecurityContextHolder.setContext(ctx);

		AgentTaskData confirmed = fetcher.agentTaskConfirmAnswer(t.getUuid(), "read both rounds", asBrowser("CONFIRMED"));

		assertQueuedFor(confirmed, "coder");
		assertEquals(AnswerConfirmationState.CONFIRMED, entry(t).state());
		assertEquals(owner.getUuid(), entry(t).settledBy().uuid(), "given as the person who signed in");
	}
}
