/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.ws.App;
import io.reliza.ws.tea.TeaTokenController;

/**
 * Task TEA-3, design 4.8 cases 35 to 40 and round 2 cases 61 to 63: EXTERNAL keys through the user GraphQL API
 * against the test database -- creation and the TEA id mint, listing, the restricted grants, the holder name,
 * minting and deletion, each with the refusals a non-admin and the other key surfaces get; every org ADMIN gate
 * against an org READ_WRITE and an org READ_ONLY member; the one-way rule on lifecycle changes, its narrowing acts
 * refused to an org READ_ONLY member (run 2, T-5); the approvals surface refusing an EXTERNAL key (run 2, T-6).
 */
@SpringBootTest(classes = {App.class})
public class ExternalApiKeyGraphqlIntegrationTest extends ExternalKeyIntegrationTestBase {

	private String orgTeaUuid(UserData u, Organization org) {
		List<Map<String, Object>> orgs = ok(exec(u, "{ organizations { uuid teaUuid } }"), "organizations");
		return (String) orgs.stream().filter(o -> org.getUuid().toString().equals(o.get("uuid"))).findFirst().orElseThrow().get("teaUuid");
	}

	// 35
	@Test
	public void creationMintsTheTeaIdAndPrintsTheExternalId() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UserData member = user(org, PermissionScope.COMPONENT, component(org).getUuid());
		assertNull(orgTeaUuid(admin, org), "a fresh organization has no TEA id");
		Map<String, Object> k = createKey(admin, org, "  Acme Customer  ");
		String tea = orgTeaUuid(admin, org);
		assertEquals("EXTERNAL", k.get("type"));
		assertEquals("Acme Customer", k.get("holderName"));
		assertEquals(tea, k.get("teaOrg"));
		assertEquals("EXTERNAL__" + tea + "__ord__" + k.get("keyOrder"), k.get("keyId"));
		assertTrue(((List<?>) k.get("secrets")).isEmpty(), "created without a secret");
		assertEquals("ACTIVE", k.get("status"));
		Map<String, Object> second = createKey(admin, org, "Other party");
		assertEquals(tea, second.get("teaOrg"), "the TEA id is minted once");
		fails(exec(member, CREATE, "o", org.getUuid().toString(), "h", "x"));
		failsWith(exec(admin, CREATE, "o", org.getUuid().toString(), "h", "   "), "holder name");
	}

	// 36
	@Test
	public void theListings() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UserData member = user(org, PermissionScope.COMPONENT, component(org).getUuid());
		Map<String, Object> k = createKey(admin, org, "Acme");
		ok(exec(admin, "mutation($o: ID!) { createOrgApiKey(orgUuid: $o, apiType: FREEFORM) { uuid } }",
				"o", org.getUuid().toString()), "createOrgApiKey");
		List<Map<String, Object>> external = ok(exec(admin, EXTERNAL_KEYS, "o", org.getUuid().toString()), "externalApiKeys");
		assertEquals(List.of(k.get("uuid")), external.stream().map(e -> e.get("uuid")).toList(), "the EXTERNAL rows and nothing else");
		List<Map<String, Object>> all = ok(exec(admin, "query($o: ID!) { apiKeys(orgUuid: $o) { uuid type } }", "o", org.getUuid().toString()), "apiKeys");
		assertTrue(all.stream().anyMatch(a -> k.get("uuid").equals(a.get("uuid"))));
		assertTrue(all.stream().anyMatch(a -> "FREEFORM".equals(a.get("type"))));
		for (UserData u : List.of(admin, member)) {
			List<Map<String, Object>> mine = ok(exec(u, "{ myApiKeys { uuid } }"), "myApiKeys");
			assertTrue(mine.stream().noneMatch(m -> k.get("uuid").equals(m.get("uuid"))), "myApiKeys never lists an external key");
		}
		fails(exec(member, EXTERNAL_KEYS, "o", org.getUuid().toString()));
	}

	// 37
	@Test
	@SuppressWarnings("unchecked")
	public void grantsAreReadOnlyTeaReadAtTheThreeScopes() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		UserData member = user(org, PermissionScope.COMPONENT, c.getUuid());
		String key = (String) createKey(admin, org, "Acme").get("uuid");
		Map<String, Object> saved = ok(exec(admin, SET_GRANTS, "k", key, "t", "READ_ONLY", "p", List.of(
				grant("ORGANIZATION", org.getUuid(), "READ_ONLY", List.of()),
				grant("COMPONENT", c.getUuid(), "READ_ONLY", List.of("ARTIFACT_DOWNLOAD")))), "setPermissionsOnFreeformApiKey");
		List<Map<String, Object>> perms = (List<Map<String, Object>>) ((Map<String, Object>) saved.get("permissions")).get("permissions");
		assertEquals(2, perms.size());
		for (Map<String, Object> p : perms) {
			assertEquals("READ_ONLY", p.get("type"));
			assertEquals(List.of("TEA_READ"), p.get("functions"));
		}
		assertEquals(Set.of("ORGANIZATION", "COMPONENT"), Set.copyOf(perms.stream().map(p -> p.get("scope")).toList()));
		failsWith(exec(admin, SET_GRANTS, "k", key, "t", "READ_WRITE", "p", List.of()), "READ_ONLY");
		failsWith(exec(admin, SET_GRANTS, "k", key, "t", null, "p", List.of(grant("COMPONENT", c.getUuid(), "READ_WRITE", List.of()))), "READ_ONLY");
		failsWith(exec(admin, SET_GRANTS, "k", key, "t", null, "p", List.of(grant("INSTANCE", UUID.randomUUID(), "READ_ONLY", List.of()))), "scope");
		fails(exec(admin, SET_GRANTS, "k", key, "t", null, "p", List.of(grant("BOARD", UUID.randomUUID(), "READ_ONLY", List.of()))));
		Organization other = testInitializer.obtainOrganization();
		Component foreign = component(other);
		failsWith(exec(admin, SET_GRANTS, "k", key, "t", null, "p", List.of(grant("COMPONENT", foreign.getUuid(), "READ_ONLY", List.of()))),
				"another organization");
		fails(exec(member, SET_GRANTS, "k", key, "t", null, "p", List.of(grant("COMPONENT", c.getUuid(), "READ_ONLY", List.of()))));
		fails(exec(writer(org), SET_GRANTS, "k", key, "t", null, "p", List.of(grant("COMPONENT", c.getUuid(), "READ_ONLY", List.of()))));
		// the failed calls changed nothing
		List<Map<String, Object>> after = ok(exec(admin, "query($o: ID!) { externalApiKeys(orgUuid: $o) { uuid permissions { permissions { scope } } } }",
				"o", org.getUuid().toString()), "externalApiKeys");
		assertEquals(2, ((List<?>) ((Map<String, Object>) after.get(0).get("permissions")).get("permissions")).size());
	}

	// 38
	@Test
	public void theHolderNameAndTheSurfacesThatRefuseExternalKeys() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UserData member = user(org, PermissionScope.COMPONENT, component(org).getUuid());
		String key = (String) createKey(admin, org, "Acme").get("uuid");
		Map<String, Object> renamed = ok(exec(admin, "mutation($k: ID!, $h: String!) { setApiKeyHolderName(apiKeyUuid: $k, holderName: $h) { holderName } }",
				"k", key, "h", "Acme Corp"), "setApiKeyHolderName");
		assertEquals("Acme Corp", renamed.get("holderName"));
		fails(exec(member, "mutation($k: ID!, $h: String!) { setApiKeyHolderName(apiKeyUuid: $k, holderName: $h) { holderName } }", "k", key, "h", "x"));
		failsWith(exec(admin, "mutation($k: ID!, $h: ID) { setApiKeyHolder(apiKeyUuid: $k, holder: $h) { uuid } }", "k", key, "h",
				admin.getUuid().toString()), "outside party");
		fails(exec(admin, "mutation($k: ID!, $n: String) { declareApiKey(apiKeyUuid: $k, name: $n) { uuid } }", "k", key, "n", "tea3-" + UUID.randomUUID()));
		failsWith(exec(admin, "mutation($o: ID!) { createOrgApiKey(orgUuid: $o, apiType: EXTERNAL) { uuid } }", "o", org.getUuid().toString()),
				"Unsupported Key type");
	}

	// run 2, T-6: the approvals surface writes the org-wide grant directly, so it refuses an EXTERNAL key
	@Test
	public void theApprovalsSurfaceRefusesAnExternalKeyAndChangesNothing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		ok(exec(admin, "mutation($o: ID!, $r: ApprovalRoleInput!) { addApprovalRole(orgUuid: $o, approvalRole: $r) { uuid } }",
				"o", org.getUuid().toString(), "r", Map.of("id", "TEA3QA", "displayView", "TEA-3 QA")), "addApprovalRole");
		String key = (String) createKey(admin, org, "Acme").get("uuid");
		ok(exec(admin, SET_GRANTS, "k", key, "t", null, "p", List.of(grant("ORGANIZATION", org.getUuid(), "READ_ONLY", List.of()))),
				"setPermissionsOnFreeformApiKey");
		Map<String, Object> before = stateOf(admin, org, key);
		failsWith(exec(admin, SET_APPROVALS, "k", key, "a", List.of(), "n", "n"), ApiKeyService.EXTERNAL_APPROVALS_REFUSAL);
		failsWith(exec(admin, SET_APPROVALS, "k", key, "a", List.of("TEA3QA"), "n", "n"), ApiKeyService.EXTERNAL_APPROVALS_REFUSAL);
		assertEquals(before, stateOf(admin, org, key), "the org-wide READ_ONLY grant stays and no approval is stored");
		List<Map<String, Object>> notes = ok(exec(admin, "query($o: ID!) { externalApiKeys(orgUuid: $o) { uuid notes } }",
				"o", org.getUuid().toString()), "externalApiKeys");
		assertEquals("tea3 test", notes.get(0).get("notes"), "the notes stay as created");
		// control: the same approval is valid for a Free Form key of the org, so the refusal above is the key type's
		String freeform = (String) ((Map<?, ?>) ok(exec(admin, "mutation($o: ID!) { createOrgApiKey(orgUuid: $o, apiType: FREEFORM) { uuid } }",
				"o", org.getUuid().toString()), "createOrgApiKey")).get("uuid");
		ok(exec(admin, SET_APPROVALS, "k", freeform, "a", List.of("TEA3QA"), "n", "n"), "setApprovalsOnApiKey");
	}

	// 39
	@Test
	@SuppressWarnings("unchecked")
	public void mintingIsAnAdminsActAndTheKeyNeverBacksADeviceLogin() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UserData member = user(org, PermissionScope.COMPONENT, component(org).getUuid());
		Map<String, Object> k = createKey(admin, org, "Acme");
		String key = (String) k.get("uuid");
		Map<String, Object> minted = ok(exec(admin, ADD_SECRET, "k", key), "addApiKeySecret");
		assertTrue(((String) minted.get("apiKey")).length() > 20);
		assertEquals(k.get("keyId"), minted.get("id"), "the printed id of the minted secret is the key id");
		fails(exec(member, ADD_SECRET, "k", key));
		// a secret that leaves the organization needs ADMIN; since main #870 so does one for a holder-less
		// Free Form key, whose uuid every reader of its agent sees (TEA-14)
		UserData writer = writer(org);
		fails(exec(writer, ADD_SECRET, "k", key));
		String freeform = (String) ((Map<?, ?>) ok(exec(admin, "mutation($o: ID!) { createOrgApiKey(orgUuid: $o, apiType: FREEFORM) { uuid } }",
				"o", org.getUuid().toString()), "createOrgApiKey")).get("uuid");
		failsWith(exec(writer, ADD_SECRET, "k", freeform),
				"Only an organization administrator may mint secrets for a Free Form key that has no holder");
		ok(exec(admin, ADD_SECRET, "k", freeform), "addApiKeySecret");
		failsWith(exec(admin, "mutation($c: String!, $k: ID) { approveCliLogin(userCode: $c, apiKeyUuid: $k) { uuid } }", "c", "ABCD-EFGH", "k", key),
				"Only personal and Free Form keys back device-login sessions");
	}

	// 40
	@Test
	public void theKeyIsDeletedByItsPrintedId() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Map<String, Object> k = createKey(admin, org, "Acme");
		assertEquals(Boolean.TRUE, ok(exec(admin, "mutation($k: ID!) { deleteApiKey(apiKeyUuid: $k) }", "k", k.get("keyId")), "deleteApiKey"));
		List<Map<String, Object>> external = ok(exec(admin, EXTERNAL_KEYS, "o", org.getUuid().toString()), "externalApiKeys");
		assertTrue(external.isEmpty(), external.toString());
	}

	// ---- design round 2: the org ADMIN gates (T-3) and the one-way rule (T-2) ----

	private static final String STATE = "query($o: ID!) { externalApiKeys(orgUuid: $o) { uuid holderName status adminDisabled"
			+ " permissions { permissions { scope object type functions approvals } } secrets { slot active createdDate expiresDate } } }";
	private static final String REGENERATE = "mutation($k: ID!, $s: Int!) { regenerateApiKeySecret(apiKeyUuid: $k, slot: $s) { apiKey } }";
	private static final String HOLDER_NAME = "mutation($k: ID!, $h: String!) { setApiKeyHolderName(apiKeyUuid: $k, holderName: $h) { holderName } }";
	private static final String STATUS = "mutation($k: ID!, $s: ApiKeyStatus!) { setApiKeyStatus(apiKeyUuid: $k, status: $s) { status adminDisabled } }";
	private static final String SECRET_ACTIVE = "mutation($k: ID!, $s: Int!, $a: Boolean!) { setApiKeySecretActive(apiKeyUuid: $k, slot: $s, active: $a) { uuid } }";
	private static final String SECRET_EXPIRY = "mutation($k: ID!, $s: Int!, $e: DateTime) { setApiKeySecretExpiry(apiKeyUuid: $k, slot: $s, expiresDate: $e) { uuid } }";
	private static final String DELETE_SECRET = "mutation($k: ID!, $s: Int!) { deleteApiKeySecret(apiKeyUuid: $k, slot: $s) { uuid } }";
	private static final String DELETE_KEY = "mutation($k: ID!) { deleteApiKey(apiKeyUuid: $k) }";
	private static final String SET_APPROVALS = "mutation($k: ID!, $a: [String], $n: String) {"
			+ " setApprovalsOnApiKey(apiKeyUuid: $k, approvals: $a, notes: $n) { uuid } }";

	@Autowired private TeaTokenController tokenController;

	private List<Map<String, Object>> state(UserData admin, Organization org) {
		return ok(exec(admin, STATE, "o", org.getUuid().toString()), "externalApiKeys");
	}

	private Map<String, Object> stateOf(UserData admin, Organization org, String key) {
		return state(admin, org).stream().filter(k -> key.equals(k.get("uuid"))).findFirst().orElseThrow();
	}

	private static String in(Duration d) {
		return OffsetDateTime.now(ZoneOffset.UTC).plus(d).truncatedTo(ChronoUnit.SECONDS).toString();
	}

	private String mintSecret(UserData admin, String key) {
		return (String) ((Map<?, ?>) ok(exec(admin, ADD_SECRET, "k", key), "addApiKeySecret")).get("apiKey");
	}

	/** The token endpoint, as TeaTokenEndpointIntegrationTest drives it: the controller bean with Basic credentials. */
	private int exchange(Map<String, Object> k, String secret) {
		String teaOrg = (String) k.get("teaOrg");
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tea/" + teaOrg + "/v1.0.0/token");
		req.setRemoteAddr("192.0.2.61");
		req.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((k.get("keyId") + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		req.setParameter("grant_type", "client_credentials");
		return tokenController.token(req, teaOrg).getStatusCode().value();
	}

	// 61
	@Test
	public void everyOrgAdminGateRefusesAnOrgWriterAndAnOrgReader() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		String key = (String) createKey(admin, org, "Acme").get("uuid");
		mintSecret(admin, key);
		List<Map<String, Object>> before = state(admin, org);
		List<Map<String, Object>> grant = List.of(grant("COMPONENT", c.getUuid(), "READ_ONLY", List.of()));
		for (UserData u : List.of(writer(org), reader(org))) {
			fails(exec(u, CREATE, "o", org.getUuid().toString(), "h", "Intruder"));
			fails(exec(u, EXTERNAL_KEYS, "o", org.getUuid().toString()));
			fails(exec(u, HOLDER_NAME, "k", key, "h", "Intruder"));
			fails(exec(u, SET_GRANTS, "k", key, "t", null, "p", grant));
			fails(exec(u, ADD_SECRET, "k", key));
			fails(exec(u, REGENERATE, "k", key, "s", 1));
			assertEquals(before, state(admin, org), "a refused call changes nothing");
		}
		// the administrator passes each gate
		ok(exec(admin, CREATE, "o", org.getUuid().toString(), "h", "Second party"), "createExternalApiKey");
		assertEquals(2, ((List<?>) ok(exec(admin, EXTERNAL_KEYS, "o", org.getUuid().toString()), "externalApiKeys")).size());
		assertEquals("Acme Corp", ((Map<?, ?>) ok(exec(admin, HOLDER_NAME, "k", key, "h", "Acme Corp"), "setApiKeyHolderName")).get("holderName"));
		ok(exec(admin, SET_GRANTS, "k", key, "t", null, "p", grant), "setPermissionsOnFreeformApiKey");
		mintSecret(admin, key);
		ok(exec(admin, REGENERATE, "k", key, "s", 1), "regenerateApiKeySecret");
	}

	// 62
	@Test
	public void anOrgWriterCanWithdrawAnOutsidePartysAccessButNotRestoreIt() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UserData writer = writer(org);
		Map<String, Object> k = createKey(admin, org, "Acme");
		String key = (String) k.get("uuid");
		String s1 = mintSecret(admin, key);
		String s2 = mintSecret(admin, key);
		assertEquals(200, exchange(k, s2));
		// the key: disabled by the admin, not re-enabled by the writer
		ok(exec(admin, STATUS, "k", key, "s", "INACTIVE"), "setApiKeyStatus");
		fails(exec(writer, STATUS, "k", key, "s", "ACTIVE"));
		assertEquals("INACTIVE", stateOf(admin, org, key).get("status"));
		assertEquals(Boolean.TRUE, stateOf(admin, org, key).get("adminDisabled"));
		assertEquals(401, exchange(k, s2), "the outside party stays cut off");
		// a secret: retired by the admin, not re-enabled by the writer
		ok(exec(admin, SECRET_ACTIVE, "k", key, "s", 1, "a", false), "setApiKeySecretActive");
		fails(exec(writer, SECRET_ACTIVE, "k", key, "s", 1, "a", true));
		// an expiry: the writer may shorten it, never clear or extend it
		ok(exec(admin, SECRET_EXPIRY, "k", key, "s", 2, "e", in(Duration.ofHours(1))), "setApiKeySecretExpiry");
		fails(exec(writer, SECRET_EXPIRY, "k", key, "s", 2, "e", null));
		fails(exec(writer, SECRET_EXPIRY, "k", key, "s", 2, "e", in(Duration.ofHours(2))));
		ok(exec(writer, SECRET_EXPIRY, "k", key, "s", 2, "e", in(Duration.ofMinutes(30))), "setApiKeySecretExpiry");
		// a first expiry on a secret that never expired narrows
		ok(exec(writer, SECRET_EXPIRY, "k", key, "s", 1, "e", in(Duration.ofDays(30))), "setApiKeySecretExpiry");
		assertSecrets(stateOf(admin, org, key), Map.of(1, false, 2, true));
		// after the admin re-activates, every narrowing act of the writer passes and cuts the party off
		ok(exec(admin, STATUS, "k", key, "s", "ACTIVE"), "setApiKeyStatus");
		assertEquals(200, exchange(k, s2));
		ok(exec(writer, STATUS, "k", key, "s", "INACTIVE"), "setApiKeyStatus");
		assertEquals(401, exchange(k, s2));
		ok(exec(admin, STATUS, "k", key, "s", "ACTIVE"), "setApiKeyStatus");
		assertEquals(200, exchange(k, s2));
		ok(exec(writer, SECRET_ACTIVE, "k", key, "s", 2, "a", false), "setApiKeySecretActive");
		assertEquals(401, exchange(k, s2));
		ok(exec(writer, DELETE_SECRET, "k", key, "s", 2), "deleteApiKeySecret");
		assertEquals(401, exchange(k, s2));
		assertEquals(401, exchange(k, s1), "slot 1 stays retired");
		fails(exec(writer, DELETE_KEY, "k", key));
		assertEquals(1, state(admin, org).size(), "deleting the key is the administrator's");
	}

	// run 2, T-5: the narrowing acts stay at org WRITE, so an org READ_ONLY member can neither cut the party off nor restore it
	@Test
	public void anOrgReaderCanNeitherWithdrawNorRestoreAnOutsidePartysAccess() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UserData reader = reader(org);
		Map<String, Object> k = createKey(admin, org, "Acme");
		String key = (String) k.get("uuid");
		String s1 = mintSecret(admin, key);
		String s2 = mintSecret(admin, key);
		String expiry = in(Duration.ofHours(1));
		ok(exec(admin, SECRET_EXPIRY, "k", key, "s", 2, "e", expiry), "setApiKeySecretExpiry");
		Map<String, Object> before = stateOf(admin, org, key);
		// every narrowing act of the one-way rule
		fails(exec(reader, STATUS, "k", key, "s", "INACTIVE"));
		fails(exec(reader, SECRET_ACTIVE, "k", key, "s", 1, "a", false));
		fails(exec(reader, SECRET_EXPIRY, "k", key, "s", 2, "e", expiry));
		fails(exec(reader, SECRET_EXPIRY, "k", key, "s", 2, "e", in(Duration.ofMinutes(30))));
		fails(exec(reader, SECRET_EXPIRY, "k", key, "s", 1, "e", in(Duration.ofDays(30))));
		fails(exec(reader, DELETE_SECRET, "k", key, "s", 2));
		fails(exec(reader, DELETE_KEY, "k", key));
		assertEquals(before, stateOf(admin, org, key), "a refused narrowing changes nothing");
		assertEquals(200, exchange(k, s1), "the outside party is not cut off");
		assertEquals(200, exchange(k, s2), "the outside party is not cut off");
		// and every widening act, once the admin has withdrawn access
		ok(exec(admin, STATUS, "k", key, "s", "INACTIVE"), "setApiKeyStatus");
		ok(exec(admin, SECRET_ACTIVE, "k", key, "s", 1, "a", false), "setApiKeySecretActive");
		Map<String, Object> withdrawn = stateOf(admin, org, key);
		fails(exec(reader, STATUS, "k", key, "s", "ACTIVE"));
		fails(exec(reader, SECRET_ACTIVE, "k", key, "s", 1, "a", true));
		fails(exec(reader, SECRET_EXPIRY, "k", key, "s", 2, "e", null));
		fails(exec(reader, SECRET_EXPIRY, "k", key, "s", 2, "e", in(Duration.ofHours(2))));
		assertEquals(withdrawn, stateOf(admin, org, key), "a refused widening changes nothing");
		assertEquals(401, exchange(k, s2), "the outside party stays cut off");
	}

	// 63
	@Test
	public void theAdminRestoresAccessAndOtherKeyTypesKeepTheirGates() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UserData writer = writer(org);
		Map<String, Object> k = createKey(admin, org, "Acme");
		String key = (String) k.get("uuid");
		String s1 = mintSecret(admin, key);
		String s2 = mintSecret(admin, key);
		ok(exec(admin, STATUS, "k", key, "s", "INACTIVE"), "setApiKeyStatus");
		ok(exec(admin, SECRET_ACTIVE, "k", key, "s", 1, "a", false), "setApiKeySecretActive");
		ok(exec(admin, SECRET_EXPIRY, "k", key, "s", 2, "e", in(Duration.ofHours(1))), "setApiKeySecretExpiry");
		assertEquals(401, exchange(k, s1));
		ok(exec(admin, STATUS, "k", key, "s", "ACTIVE"), "setApiKeyStatus");
		ok(exec(admin, SECRET_ACTIVE, "k", key, "s", 1, "a", true), "setApiKeySecretActive");
		ok(exec(admin, SECRET_EXPIRY, "k", key, "s", 2, "e", null), "setApiKeySecretExpiry");
		assertEquals(200, exchange(k, s1));
		assertEquals(200, exchange(k, s2));
		Map<String, Object> restored = stateOf(admin, org, key);
		assertEquals("ACTIVE", restored.get("status"));
		assertSecrets(restored, Map.of(1, true, 2, true));
		assertTrue(secrets(restored).stream().allMatch(x -> x.get("expiresDate") == null), restored.toString());
		// a holder-less Free Form key of the same org: the admin mints (main #870); the writer's other gates are
		// as they were, and a writer's deactivation sets no adminDisabled (decision 6), so the writer re-activates
		String freeform = (String) ((Map<?, ?>) ok(exec(admin, "mutation($o: ID!) { createOrgApiKey(orgUuid: $o, apiType: FREEFORM) { uuid } }",
				"o", org.getUuid().toString()), "createOrgApiKey")).get("uuid");
		failsWith(exec(writer, ADD_SECRET, "k", freeform),
				"Only an organization administrator may mint secrets for a Free Form key that has no holder");
		ok(exec(admin, ADD_SECRET, "k", freeform), "addApiKeySecret");
		ok(exec(writer, STATUS, "k", freeform, "s", "INACTIVE"), "setApiKeyStatus");
		assertEquals("ACTIVE", ((Map<?, ?>) ok(exec(writer, STATUS, "k", freeform, "s", "ACTIVE"), "setApiKeyStatus")).get("status"));
		ok(exec(writer, SECRET_ACTIVE, "k", freeform, "s", 1, "a", false), "setApiKeySecretActive");
		ok(exec(writer, SECRET_ACTIVE, "k", freeform, "s", 1, "a", true), "setApiKeySecretActive");
		ok(exec(writer, SECRET_EXPIRY, "k", freeform, "s", 1, "e", in(Duration.ofHours(1))), "setApiKeySecretExpiry");
		ok(exec(writer, SECRET_EXPIRY, "k", freeform, "s", 1, "e", in(Duration.ofHours(5))), "setApiKeySecretExpiry");
		ok(exec(writer, SECRET_EXPIRY, "k", freeform, "s", 1, "e", null), "setApiKeySecretExpiry");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> secrets(Map<String, Object> key) {
		return (List<Map<String, Object>>) key.get("secrets");
	}

	private static void assertSecrets(Map<String, Object> key, Map<Integer, Boolean> activeBySlot) {
		Map<Integer, Boolean> actual = new TreeMap<>();
		for (Map<String, Object> x : secrets(key)) actual.put(((Number) x.get("slot")).intValue(), (Boolean) x.get("active"));
		assertEquals(new TreeMap<>(activeBySlot), actual, key.toString());
	}

	@Test
	public void aProductIsGrantedAsAComponent() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component product = component(org, ComponentType.PRODUCT);
		String key = (String) createKey(admin, org, "Acme").get("uuid");
		ok(exec(admin, SET_GRANTS, "k", key, "t", null, "p", List.of(grant("COMPONENT", product.getUuid(), "READ_ONLY", List.of()))),
				"setPermissionsOnFreeformApiKey");
	}
}
