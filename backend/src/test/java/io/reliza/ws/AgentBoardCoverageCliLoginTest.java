/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedGrant.GrantType;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentBoardService.CoverageGap;
import io.reliza.service.CliSessionService;
import io.reliza.service.FederatedTrustRuleService;

/**
 * TPF-174 (architecture-2 section 5.1, rows T-1 to T-5 and T-8 to T-16): the board coverage banner counts a
 * key a person can sign in with through a CLI login -- a personal key by its owner, a Free Form key by its
 * holder -- beside a key with a usable secret or a bound usable trust rule. The grants are read as on a call.
 * Every test owns a fresh organization and a board with one active agent role and an empty coordinator seat.
 */
public class AgentBoardCoverageCliLoginTest extends ApiKeyGroupTestBase {

	@Autowired CliSessionService cliSessionService;
	@Autowired FederatedTrustRuleService federatedTrustRuleService;

	private static final Set<PermissionFunction> BOTH = Set.of(PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_WRITE);
	private static final Set<PermissionFunction> SEAT_ONLY = Set.of(PermissionFunction.BOARD_WRITE);

	private record Rig(Organization org, AgentBoardData bd) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org);
		agentBoardService.upsertRoleConfig(bd, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build", 10,
				null, false, true, null), true, WU);
		return new Rig(org, bd);
	}

	private Set<PermissionFunction> gaps(AgentBoardData bd) {
		AgentBoardData fresh = agentBoardService.getBoardData(bd.getUuid()).orElseThrow();
		return agentBoardService.missingCoverage(fresh).stream().map(CoverageGap::function).collect(Collectors.toSet());
	}

	private ApiKey reread(ApiKey k) {
		return apiKeyService.getApiKey(k.getUuid()).orElseThrow();
	}

	private boolean agentOn(ApiKey k, Rig r) {
		return authorizationService.boardPermission(reread(k), r.org().getUuid(), r.bd().getUuid(),
				PermissionFunction.BOARD_AGENT, CallType.ESSENTIAL_READ);
	}

	private boolean backed(ApiKey k) {
		ApiKey row = reread(k);
		return cliSessionService.backerOf(row, ApiKeyData.dataFromRecord(row)).isPresent();
	}

	/** A Free Form key with no secret and no holder, holding BOARD_AGENT on the board directly. */
	private ApiKey agentKey(Rig r) throws RelizaException {
		ApiKey k = freeform(r.org(), "tpf174 agent");
		apiKeyService.setPermissionsOnApiKey(k.getUuid(), null, List.of(boardGrant(r.org(), r.bd().getUuid(),
				PermissionType.READ_WRITE, PermissionFunction.BOARD_AGENT)), WU);
		return reread(k);
	}

	private void hold(ApiKey k, UserData holder) throws RelizaException {
		apiKeyService.setApiKeyHolder(k.getUuid(), null == holder ? null : holder.getUuid(), WU);
	}

	private static UserGroupPermissionDto boardAgent(UUID board) {
		return UserGroupPermissionDto.builder().scope(PermissionScope.BOARD).objectId(board)
				.type(PermissionType.READ_WRITE).functions(List.of(PermissionFunction.BOARD_AGENT)).build();
	}

	// ---------- T-1, T-2, T-4: a Free Form key without a secret counts through its holder ----------

	@Test
	public void aHeldKeyWithoutASecretCoversTheBoardThroughAnApiKeyGroup() throws RelizaException {
		Rig r = rig();
		ApiKey k = freeform(r.org(), "tpf174 grouped");
		ApiKeyGroupData g = group(r.org(), Set.of(k.getUuid()), boardAgent(r.bd().getUuid()));
		assertEquals(BOTH, gaps(r.bd()), "no secret, no rule, no holder: nobody can sign in with it");

		UserData h = member(r.org(), PermissionType.READ_ONLY);
		hold(k, h);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "the holder can sign in with it; the group grant counts");
		assertTrue(agentOn(k, r));

		setMembers(g);
		assertEquals(BOTH, gaps(r.bd()), "out of the group the key holds nothing on the board");
	}

	@Test
	public void aHeldKeyWithoutASecretCoversTheBoardThroughADirectGrant() throws RelizaException {
		Rig r = rig();
		ApiKey k = agentKey(r);
		assertEquals(BOTH, gaps(r.bd()), "T-4: no holder");
		assertFalse(agentOn(k, r));

		UserData h = member(r.org(), PermissionType.READ_ONLY);
		hold(k, h);
		assertEquals(SEAT_ONLY, gaps(r.bd()));
		assertTrue(agentOn(k, r));

		hold(k, null);
		assertEquals(BOTH, gaps(r.bd()), "holder cleared");
	}

	// ---------- T-3: a personal key without a secret counts through its owner, intersected ----------

	@Test
	public void aPersonalKeyWithoutASecretCountsOnlyWhereItsOwnerClears() throws RelizaException {
		Rig r = rig();
		UserData owner = member(r.org(), PermissionType.READ_ONLY);
		ApiKey k = userKey(r.org(), owner);
		apiKeyService.setPermissionsOnApiKey(k.getUuid(), null, List.of(boardGrant(r.org(), r.bd().getUuid(),
				PermissionType.READ_WRITE, PermissionFunction.BOARD_AGENT)), WU);
		assertNull(reread(k).getApiKey(), "no secret");
		assertEquals(BOTH, gaps(r.bd()), "the owner lacks BOARD_AGENT on the board");
		assertTrue(backed(k), "the owner can sign in with it; the intersection decides");

		userService.setUserPermission(owner.getUuid(), r.org().getUuid(), PermissionScope.BOARD, r.bd().getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.BOARD_AGENT), null, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "owner and key both clear");

		apiKeyService.setPermissionsOnApiKey(k.getUuid(), null, List.of(new PermissionDto(
				r.org().getUuid(), PermissionScope.ORGANIZATION, r.org().getUuid(), PermissionType.READ_ONLY,
				Set.of(PermissionFunction.AGENT), null)), WU);
		assertEquals(BOTH, gaps(r.bd()), "the key's own set no longer carries it");
	}

	// ---------- T-5: the holder or owner must be active and a member ----------

	@Test
	public void aHolderOrOwnerWhoCannotSignInCoversNothing() throws RelizaException {
		Rig r = rig();
		ApiKey k = agentKey(r);
		UserData h = member(r.org(), PermissionType.READ_ONLY);
		hold(k, h);
		assertEquals(SEAT_ONLY, gaps(r.bd()));

		// (a) deactivated, then reactivated
		userService.softDeleteUser(h.getUuid(), WU);
		assertEquals(BOTH, gaps(r.bd()), "an inactive holder cannot sign in");
		assertFalse(backed(k));
		userService.reactivateUser(h.getUuid(), WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "reactivated");

		// (b) a holder who is a member of another organization only (the service does not check membership)
		Organization elsewhere = testInitializer.obtainOrganization();
		UserData s = member(elsewhere, PermissionType.READ_WRITE);
		hold(k, s);
		assertEquals(BOTH, gaps(r.bd()), "a non-member holder cannot sign in with the org's key");
		assertFalse(backed(k));

		// (c) held by H again, then H leaves the organization: the product path clears the holder
		hold(k, h);
		assertEquals(SEAT_ONLY, gaps(r.bd()));
		userService.removeUserFromOrg(r.org().getUuid(), h.getUuid(), WU);
		assertEquals(BOTH, gaps(r.bd()));
		assertNull(ApiKeyData.dataFromRecord(reread(k)).getHolder(), "leaving the org released the key");

		// (d) a personal key whose owner holds the function, then the owner deactivated
		UserData owner = member(r.org(), PermissionType.READ_ONLY);
		ApiKey uk = userKey(r.org(), owner);
		apiKeyService.setPermissionsOnApiKey(uk.getUuid(), null, List.of(boardGrant(r.org(), r.bd().getUuid(),
				PermissionType.READ_WRITE, PermissionFunction.BOARD_AGENT)), WU);
		userService.setUserPermission(owner.getUuid(), r.org().getUuid(), PermissionScope.BOARD, r.bd().getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.BOARD_AGENT), null, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()));
		userService.softDeleteUser(owner.getUuid(), WU);
		assertEquals(BOTH, gaps(r.bd()), "an inactive owner's key holds nothing");
	}

	// ---------- T-8: an expired or retired secret, with and without a holder ----------

	@Test
	public void anExpiredOrRetiredSecretCountsOnlyWithAHolder() throws RelizaException {
		Rig r = rig();
		ApiKey k = agentKey(r);
		apiKeyService.addApiKeySecret(k.getUuid(), null, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "a usable secret");

		apiKeyService.setApiKeySecretExpiry(k.getUuid(), 1, ZonedDateTime.now().minusMinutes(1), WU);
		assertEquals(BOTH, gaps(r.bd()), "expired, no holder");
		UserData h = member(r.org(), PermissionType.READ_ONLY);
		hold(k, h);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "expired, held");

		hold(k, null);
		apiKeyService.setApiKeySecretExpiry(k.getUuid(), 1, null, WU);
		apiKeyService.setApiKeySecretActive(k.getUuid(), 1, false, WU);
		assertEquals(BOTH, gaps(r.bd()), "retired, no holder");
		hold(k, h);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "retired, held");

		apiKeyService.deleteApiKeySecret(k.getUuid(), 1, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "secret deleted, held");
	}

	// ---------- T-9 (ii): a bound trust rule counts as before; a holder counts without it ----------

	@Test
	public void aBoundTrustRuleStillCountsAndAHolderCountsWithoutIt() throws RelizaException {
		Rig r = rig();
		ApiKey k = agentKey(r);
		FederatedMatcher m = new FederatedMatcher();
		m.setOwner("tpf174");
		FederatedGrant g = new FederatedGrant();
		g.setType(GrantType.KEY);
		g.setKeyUuid(k.getUuid());
		FederatedTrustRule rule = federatedTrustRuleService.create(r.org().getUuid(), "tpf174-rule-" + UUID.randomUUID(),
				null, null, m, g, null, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "a usable rule bound to the key");

		federatedTrustRuleService.setStatus(rule.getUuid(), FederatedTrustRule.Status.DISABLED, WU);
		assertEquals(BOTH, gaps(r.bd()), "rule disabled, no holder");

		hold(k, member(r.org(), PermissionType.READ_ONLY));
		assertEquals(SEAT_ONLY, gaps(r.bd()), "rule still disabled, held");
	}

	// ---------- T-10: the BOARD_WRITE line follows the same rule ----------

	@Test
	public void theCoordinatorLineFollowsTheSameRule() throws RelizaException {
		Rig r = rig();
		ApiKey w = freeform(r.org(), "tpf174 coordinator");
		apiKeyService.setPermissionsOnApiKey(w.getUuid(), null, List.of(boardGrant(r.org(), r.bd().getUuid(),
				PermissionType.READ_WRITE, PermissionFunction.BOARD_WRITE)), WU);
		assertEquals(BOTH, gaps(r.bd()), "no holder");

		hold(w, member(r.org(), PermissionType.READ_ONLY));
		assertEquals(Set.of(PermissionFunction.BOARD_AGENT), gaps(r.bd()), "held: the seat line goes");

		apiKeyService.setPermissionsOnApiKey(w.getUuid(), null, List.of(boardGrant(r.org(), r.bd().getUuid(),
				PermissionType.READ_ONLY, PermissionFunction.BOARD_WRITE)), WU);
		assertEquals(BOTH, gaps(r.bd()), "the seat line is checked at WRITE");
	}

	// ---------- T-11: a key that is not ACTIVE covers nothing ----------

	@Test
	public void aKeyThatIsNotActiveCoversNothingWhateverItsHolder() throws RelizaException {
		Rig r = rig();
		ApiKey k = agentKey(r);
		hold(k, member(r.org(), PermissionType.READ_ONLY));
		assertEquals(SEAT_ONLY, gaps(r.bd()));

		apiKeyService.setApiKeyStatus(k.getUuid(), ApiKeyStatus.INACTIVE, true, WU);
		assertTrue(ApiKeyData.dataFromRecord(reread(k)).isAdminDisabled());
		assertEquals(BOTH, gaps(r.bd()), "switched off by an administrator");
		apiKeyService.setApiKeyStatus(k.getUuid(), ApiKeyStatus.ACTIVE, true, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()));
		apiKeyService.setApiKeyStatus(k.getUuid(), ApiKeyStatus.INACTIVE, false, WU);
		assertEquals(BOTH, gaps(r.bd()), "switched off by someone else");
		apiKeyService.setApiKeyStatus(k.getUuid(), ApiKeyStatus.ACTIVE, false, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()));

		apiKeyService.deleteApiKey(k.getUuid(), WU);
		assertEquals(BOTH, gaps(r.bd()), "deleted");
		assertTrue(apiKeyService.listApiKeyByOrg(r.org().getUuid()).stream().noneMatch(x -> x.getUuid().equals(k.getUuid())));

		UserData requester = member(r.org(), PermissionType.READ_WRITE);
		List<PermissionDto> wanted = List.of(boardGrant(r.org(), r.bd().getUuid(),
				PermissionType.READ_WRITE, PermissionFunction.BOARD_AGENT));
		ApiKey asked = apiKeyService.requestFreeformApiKey(requester.getUuid(), r.org().getUuid(), "tpf174 ask", null, wanted, WU);
		assertEquals(BOTH, gaps(r.bd()), "REQUESTED");
		ApiKey denied = apiKeyService.requestFreeformApiKey(requester.getUuid(), r.org().getUuid(), "tpf174 deny", null, wanted, WU);
		apiKeyService.resolveApiKeyRequest(denied.getUuid(), false, null, WU);
		assertEquals(BOTH, gaps(r.bd()), "DENIED");
		apiKeyService.resolveApiKeyRequest(asked.getUuid(), true, null, WU);
		assertEquals(SEAT_ONLY, gaps(r.bd()), "approved: ACTIVE, held by its requester, no secret");
	}

	// ---------- T-13: the listing and the lookup the filter uses agree on a secretless key ----------

	@Test
	public void aKeyWithoutASecretIsListedAndFoundUntilItIsDeleted() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k = freeform(org, "tpf174 listed");
		assertListedAndFound(org, k, "created without a secret");

		apiKeyService.addApiKeySecret(k.getUuid(), null, WU);
		apiKeyService.deleteApiKeySecret(k.getUuid(), 1, WU);
		assertListedAndFound(org, k, "its only secret deleted");

		apiKeyService.deleteApiKey(k.getUuid(), WU);
		assertTrue(apiKeyService.listApiKeyByOrg(org.getUuid()).stream().noneMatch(x -> x.getUuid().equals(k.getUuid())));
		assertTrue(apiKeyService.getApiKey(k.getUuid()).isEmpty(), "the filter's lookup does not find it either");
	}

	private void assertListedAndFound(Organization org, ApiKey k, String when) {
		ApiKey listed = apiKeyService.listApiKeyByOrg(org.getUuid()).stream().filter(x -> x.getUuid().equals(k.getUuid()))
				.findFirst().orElse(null);
		assertTrue(null != listed, when + ": listed");
		assertNull(listed.getApiKey(), when + ": the api_key column is null");
		assertEquals("ACTIVE", listed.getRecordData().get("status"), when + ": the stored status");
		assertTrue(apiKeyService.getApiKey(k.getUuid()).isPresent(), when + ": found");
	}

	// ---------- T-14 (a): a key of another organization covers nothing here ----------

	@Test
	public void aKeyHeldInAnotherOrganizationCoversNothingHere() throws RelizaException {
		Rig a = rig();
		Organization b = testInitializer.obtainOrganization();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Both " + tag, "tpf174-" + tag + "@coverage.io", true,
				List.of(a.org().getUuid(), b.getUuid()), "tpf174-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		ApiKey kb = freeform(b, "tpf174 other org");
		apiKeyService.setPermissionsOnApiKey(kb.getUuid(), PermissionType.ADMIN, List.of(), WU);
		apiKeyService.setApiKeyHolder(kb.getUuid(), u.getUuid(), WU);
		assertTrue(backed(kb), "U can sign in with it, in org B");

		assertEquals(BOTH, gaps(a.bd()));
		assertFalse(authorizationService.boardPermission(reread(kb), a.org().getUuid(), a.bd().getUuid(),
				PermissionFunction.BOARD_AGENT, CallType.ESSENTIAL_READ));
	}

	// ---------- T-15: other key types leave by the type guard ----------

	@Test
	public void anOrganizationKeyIsNotJudgedHere() throws RelizaException {
		Rig r = rig();
		apiKeyService.setObjectApiKey(r.org().getUuid(), ApiTypeEnum.ORGANIZATION_RW, r.org().getUuid(), null,
				"tpf174 org key", false, WU);
		ApiKey row = apiKeyService.listApiKeyByOrg(r.org().getUuid()).stream()
				.filter(x -> x.getObjectType() == ApiTypeEnum.ORGANIZATION_RW).findFirst().orElseThrow();
		assertTrue(null != row.getApiKey(), "a usable secret");
		assertEquals(BOTH, gaps(r.bd()));
		assertFalse(authorizationService.boardPermission(row, r.org().getUuid(), r.bd().getUuid(),
				PermissionFunction.BOARD_AGENT, CallType.ESSENTIAL_READ));
		assertTrue(cliSessionService.backerOf(row, ApiKeyData.dataFromRecord(row)).isEmpty());
	}

	// ---------- T-16: the panel's words are unchanged ----------

	@Test
	@SuppressWarnings("unchecked")
	public void thePanelReadsTheUnchangedLinesThroughTheEngine() throws RelizaException {
		Rig r = rig();
		ApiKey k = agentKey(r);
		signInAs(member(r.org(), PermissionType.ADMIN));
		Map<String, String> before = panel(r);
		assertEquals("no key with BOARD_AGENT covers this board; its agent roles cannot take work", before.get("BOARD_AGENT"));
		assertEquals("no key with BOARD_WRITE can coordinate this board", before.get("BOARD_WRITE"));

		hold(k, member(r.org(), PermissionType.READ_ONLY));
		Map<String, String> after = panel(r);
		assertEquals(Set.of("BOARD_WRITE"), after.keySet());
		assertEquals("no key with BOARD_WRITE can coordinate this board", after.get("BOARD_WRITE"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> panel(Rig r) {
		ExecutionResult res = dgsQueryExecutor.execute(
				"query($b: ID!) { agentBoard(uuid: $b) { missingCoverage { function message } } }",
				Map.of("b", r.bd().getUuid().toString()));
		assertTrue(res.getErrors().isEmpty(), String.valueOf(res.getErrors()));
		Map<String, Object> board = ((Map<String, Map<String, Object>>) res.getData()).get("agentBoard");
		List<Map<String, Object>> coverage = (List<Map<String, Object>>) board.get("missingCoverage");
		return coverage.stream().collect(Collectors.toMap(x -> (String) x.get("function"), x -> (String) x.get("message"),
				(x, y) -> x, LinkedHashMap::new));
	}
}
