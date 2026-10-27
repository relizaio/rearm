/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.AgentBoard;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ComponentData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * Board scope, functions and the resolver (board-permissions.md §2; task ffb564ce), over grants built
 * in memory. Three boards: {@code board} and {@code sibling} in the org, {@code onPerspective} hung
 * off {@code perspective}; {@code foreign} belongs to another org. Nothing is enforced yet: these pin
 * the vocabulary and the walk the seams will call.
 */
public class AuthorizationServiceBoardTest {

	private final UUID org = UUID.randomUUID();
	private final UUID otherOrg = UUID.randomUUID();
	private final UUID board = UUID.randomUUID();
	private final UUID sibling = UUID.randomUUID();
	private final UUID onPerspective = UUID.randomUUID();
	private final UUID foreign = UUID.randomUUID();
	private final UUID perspective = UUID.randomUUID();
	private final UUID component = UUID.randomUUID();
	private final OssPerspectiveService ossPerspectiveService = mock(OssPerspectiveService.class);

	private AgentBoard row(UUID uuid, UUID orgUuid, List<UUID> perspectives) {
		AgentBoardData bd = new AgentBoardData();
		bd.setOrg(orgUuid);
		bd.setName("b-" + uuid);
		bd.setPerspectives(new ArrayList<>(perspectives));
		AgentBoard b = new AgentBoard();
		b.setUuid(uuid);
		b.setRecordData(Utils.dataToRecord(bd));
		return b;
	}

	private AuthorizationService service() {
		AuthorizationService svc = new AuthorizationService();
		ReflectionTestUtils.setField(svc, "getComponentService", new GetComponentService(null) {
			@Override public Optional<ComponentData> getComponentData(UUID uuid) { return Optional.empty(); }
			@Override public List<ComponentData> listComponentsByPerspective(UUID perspectiveUuid) { return List.of(); }
			@Override public List<ComponentData> listComponentsByProduct(UUID productUuid) { return List.of(); }
		});
		AgentBoardRepository repo = mock(AgentBoardRepository.class);
		Map<UUID, AgentBoard> rows = Map.of(
				board, row(board, org, List.of()),
				sibling, row(sibling, org, List.of()),
				onPerspective, row(onPerspective, org, List.of(perspective)),
				foreign, row(foreign, otherOrg, List.of()));
		when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
		when(repo.findByOrg(anyString())).thenAnswer(i -> rows.values().stream()
				.filter(r -> AgentBoardData.dataFromRecord(r).getOrg().toString().equals(i.getArgument(0))).toList());
		ReflectionTestUtils.setField(svc, "agentBoardRepository", repo);
		// A PERSPECTIVE grant clears a call only on a live perspective of its org (task TEA-8, D6).
		// CE: the live perspective is a mock of CE's OssPerspectiveService.PerspectiveData, the type
		// getPerspectiveData answers on CE; Pro's copy builds a saas PerspectiveData (SCORE-72 3.6).
		OssPerspectiveService.PerspectiveData pd = mock(OssPerspectiveService.PerspectiveData.class);
		when(pd.getUuid()).thenReturn(perspective);
		when(pd.getOrg()).thenReturn(org);
		when(pd.getType()).thenReturn(PerspectiveType.PERSPECTIVE);
		when(pd.getStatus()).thenReturn(StatusEnum.ACTIVE);
		when(ossPerspectiveService.getPerspectiveData(perspective)).thenReturn(Optional.of(pd));
		ReflectionTestUtils.setField(svc, "ossPerspectiveService", ossPerspectiveService);
		return svc;
	}

	private UserPermission grant(PermissionScope scope, UUID object, PermissionType type, PermissionFunction... fns) {
		return UserPermission.permissionFactory(org, scope, object, type, List.of(fns), null);
	}

	private static final PermissionFunction READ = PermissionFunction.BOARD_READ;
	private static final PermissionFunction AGENT = PermissionFunction.BOARD_AGENT;
	private static final PermissionFunction WRITE = PermissionFunction.BOARD_WRITE;

	// ---------- 1. the enum ----------

	@Test
	void boardSitsRightAfterComponentAndIsStoredByName() throws Exception {
		assertEquals(PermissionScope.COMPONENT.ordinal() + 1, PermissionScope.BOARD.ordinal());
		assertEquals(PermissionScope.BOARD.ordinal() + 1, PermissionScope.PERSPECTIVE.ordinal());
		UserPermission g = grant(PermissionScope.BOARD, board, PermissionType.READ_ONLY, READ);
		String json = Utils.OM.writeValueAsString(g);
		assertTrue(json.contains("\"BOARD\"") && json.contains("\"BOARD_READ\""), json);
		UserPermission back = Utils.OM.readValue(json, UserPermission.class);
		assertEquals(PermissionScope.BOARD, back.getScope());
		assertEquals(Set.of(READ), back.getFunctions());
	}

	// ---------- 2. containment ----------

	@Test
	void aBoardGrantReachesNoComponentBranchOrRelease() {
		AuthorizationService svc = service();
		UserPermission g = grant(PermissionScope.BOARD, board, PermissionType.ADMIN, PermissionFunction.RESOURCE, READ);
		for (PermissionScope type : List.of(PermissionScope.COMPONENT, PermissionScope.BRANCH, PermissionScope.RELEASE)) {
			assertFalse(svc.doesPermissionAuthorize(g, org, PermissionFunction.RESOURCE, type, component, CallType.READ),
					"the ordinal gate lets it through for a " + type + "; the dispatch refuses it");
		}
	}

	@Test
	void anInstanceGrantCoversNoBoardAndAPerspectiveGrantOnlyItsBoards() {
		AuthorizationService svc = service();
		UserPermission instance = grant(PermissionScope.INSTANCE, UUID.randomUUID(), PermissionType.ADMIN, READ);
		assertFalse(svc.doesPermissionAuthorize(instance, org, READ, PermissionScope.BOARD, board, CallType.READ));

		UserPermission persp = grant(PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_ONLY, READ);
		assertFalse(svc.doesPermissionAuthorize(persp, org, READ, PermissionScope.BOARD, board, CallType.READ),
				"a board outside the perspective's set");
		assertTrue(svc.doesPermissionAuthorize(persp, org, READ, PermissionScope.BOARD, onPerspective, CallType.READ),
				"a board carrying the perspective");
	}

	@Test
	void componentSideGrantsAreRefusedAtTheGate() {
		AuthorizationService svc = service();
		for (PermissionScope scope : List.of(PermissionScope.COMPONENT, PermissionScope.BRANCH, PermissionScope.RELEASE)) {
			UserPermission g = grant(scope, board, PermissionType.ADMIN, READ);
			assertFalse(svc.doesPermissionAuthorize(g, org, READ, PermissionScope.BOARD, board, CallType.READ),
					"a " + scope + " grant, even one naming the board's uuid");
		}
	}

	// ---------- 3. positive coverage ----------

	@Test
	void aBoardGrantCoversItsBoardAndTheOrganizationEveryBoard() {
		AuthorizationService svc = service();
		UserPermission g = grant(PermissionScope.BOARD, board, PermissionType.READ_ONLY, READ);
		assertTrue(svc.boardPermission(List.of(g), org, board, READ, CallType.READ));
		assertFalse(svc.boardPermission(List.of(g), org, sibling, READ, CallType.READ), "and only its board");

		UserPermission orgWide = grant(PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY, READ);
		assertTrue(svc.boardPermission(List.of(orgWide), org, sibling, READ, CallType.READ));

		UserPermission admin = grant(PermissionScope.ORGANIZATION, org, PermissionType.ADMIN);
		for (PermissionFunction fn : List.of(READ, AGENT, WRITE)) {
			assertTrue(svc.boardPermission(List.of(admin), org, board, fn, CallType.WRITE), "org ADMIN, no functions: " + fn);
		}
		assertFalse(svc.boardPermission(List.of(orgWide), otherOrg, foreign, READ, CallType.READ),
				"a grant of one org covers no board of another");
	}

	@Test
	void theCoverageOfEachScope() {
		AuthorizationService svc = service();
		assertTrue(svc.boardsInScope(grant(PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY), org).all());
		assertEquals(Set.of(board), svc.boardsInScope(grant(PermissionScope.BOARD, board, PermissionType.READ_ONLY), org).boards());
		assertTrue(svc.boardsInScope(grant(PermissionScope.BOARD, foreign, PermissionType.READ_ONLY), org).boards().isEmpty(),
				"a board of another org is covered by no grant of this one");
		assertEquals(Set.of(onPerspective),
				svc.boardsInScope(grant(PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_ONLY), org).boards());
		assertTrue(svc.boardsInScope(grant(PermissionScope.COMPONENT, component, PermissionType.ADMIN), org).boards().isEmpty());
	}

	// ---------- 4. functions and tiers ----------

	@Test
	void eachFunctionAtItsFloorAndOneTierBelow() {
		AuthorizationService svc = service();
		assertTrue(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.READ_ONLY, READ)),
				org, board, READ, CallType.READ));
		assertFalse(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.ESSENTIAL_READ, READ)),
				org, board, READ, CallType.READ));
		assertTrue(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.READ_ONLY, AGENT)),
				org, board, AGENT, CallType.READ));
		assertFalse(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.ESSENTIAL_READ, AGENT)),
				org, board, AGENT, CallType.READ));
		assertTrue(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.READ_WRITE, WRITE)),
				org, board, WRITE, CallType.WRITE));
		assertFalse(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.READ_ONLY, WRITE)),
				org, board, WRITE, CallType.WRITE));
		assertFalse(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.ADMIN)),
				org, board, READ, CallType.READ), "a board-scoped grant without the function");
	}

	@Test
	void agentAndWriteImplyReadAndNeitherTheOther() {
		assertTrue(PermissionFunction.satisfies(AGENT, READ));
		assertTrue(PermissionFunction.satisfies(WRITE, READ));
		assertFalse(PermissionFunction.satisfies(WRITE, AGENT));
		assertFalse(PermissionFunction.satisfies(AGENT, WRITE));
		assertFalse(PermissionFunction.satisfies(READ, AGENT));
		assertFalse(PermissionFunction.satisfies(PermissionFunction.CONFIGURATION_WRITE, PermissionFunction.CONFIGURATION_READ),
				"the configuration convention stays with its callers");

		AuthorizationService svc = service();
		assertTrue(svc.boardPermission(List.of(grant(PermissionScope.BOARD, board, PermissionType.READ_WRITE, WRITE)),
				org, board, READ, CallType.READ), "the walk applies the implication");
		UserPermission agentFn = grant(PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, PermissionFunction.AGENT);
		for (PermissionFunction fn : List.of(READ, AGENT, WRITE)) {
			assertFalse(svc.boardPermission(List.of(agentFn), org, board, fn, CallType.READ),
					"the org-wide AGENT function clears no board function: " + fn);
		}
	}

	// ---------- 5. grant objects: PERSPECTIVE (TPF-15) ----------

	// CE: no PERSPECTIVE grant cases. CE has no perspectives, so a live or dead one cannot be set up
	// here (SCORE-72 3.6, as SCORE-55); Pro's copy of this test keeps them.

	@Test
	void otherScopesAreNotAskedAboutPerspectives() throws Exception {
		AuthorizationService svc = service();
		svc.assertGrantObjectExists(org, PermissionScope.COMPONENT, component);
		svc.assertGrantObjectExists(org, PermissionScope.ORGANIZATION, org);
		verifyNoInteractions(ossPerspectiveService);
	}

	// ---------- 6. the held set ----------

	@Test
	void boardFunctionsReturnsWhatIsHeld() {
		AuthorizationService svc = service();
		List<UserPermission> grants = List.of(
				grant(PermissionScope.BOARD, board, PermissionType.READ_ONLY, AGENT),
				grant(PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, PermissionFunction.CONFIGURATION_WRITE));
		assertEquals(Set.of(READ, AGENT, PermissionFunction.CONFIGURATION_READ, PermissionFunction.CONFIGURATION_WRITE),
				svc.boardFunctions(grants, org, board));
		assertEquals(Set.of(PermissionFunction.CONFIGURATION_READ, PermissionFunction.CONFIGURATION_WRITE),
				svc.boardFunctions(grants, org, sibling), "the board grant stops at its board");
	}

	// ---------- 7. the consent's TEA standing (task TEA-14, compile fix 1) ----------

	@Test
	void aGlobalAdminsConsentLacksNothingAndIsAnOrgAdmin() {
		AuthorizationService svc = service();
		io.reliza.model.UserData admin = mock(io.reliza.model.UserData.class);
		when(admin.isGlobalAdmin()).thenReturn(true);
		io.reliza.service.BoardPerspectiveService.PerspectiveConsent consent = svc.perspectiveConsent(admin, org);
		assertTrue(consent.lacking(perspective).isEmpty());
		assertTrue(consent.allows(perspective));
		assertEquals(TeaProfileService.TeaCaller.ORG_ADMIN, consent.teaCaller());
	}

	@Test
	void aKeysConsentIsNeverAnOrgAdmin() throws Exception {
		AuthorizationService svc = service();
		ApiKeyService keys = mock(ApiKeyService.class);
		ReflectionTestUtils.setField(svc, "apiKeyService", keys);
		io.reliza.common.CommonVariables.AuthHeaderParse ahp = mock(io.reliza.common.CommonVariables.AuthHeaderParse.class);
		when(ahp.isRbacKey()).thenReturn(true);
		when(ahp.getType()).thenReturn(io.reliza.model.ApiKey.ApiTypeEnum.FREEFORM);
		UUID keyUuid = UUID.randomUUID();
		when(keys.isMatchingApiKey(ahp)).thenReturn(keyUuid);
		// the key is in no API key group (TPF-91): its own grant alone decides the consent
		ApiKeyGroupService groups = mock(ApiKeyGroupService.class);
		when(groups.permissionSetsForKey(keyUuid, org)).thenReturn(List.of());
		ReflectionTestUtils.setField(svc, "apiKeyGroupService", groups);
		// an org-wide grant of both consent functions: the key consents, and is still a scope admin
		io.reliza.model.ApiKeyData akd = io.reliza.model.ApiKeyData.apiKeyDataFactory(org);
		akd.setPermission(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE,
				List.of(PermissionFunction.BOARD_WRITE, PermissionFunction.CONFIGURATION_WRITE), List.of());
		when(keys.getApiKeyData(keyUuid)).thenReturn(Optional.of(akd));
		io.reliza.service.BoardPerspectiveService.PerspectiveConsent consent = svc.perspectiveConsent(ahp, org);
		assertTrue(consent.lacking(perspective).isEmpty(), consent.lacking(perspective).toString());
		assertEquals(TeaProfileService.TeaCaller.SCOPE_ADMIN, consent.teaCaller());
	}
}
