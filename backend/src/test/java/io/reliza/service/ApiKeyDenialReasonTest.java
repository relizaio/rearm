/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A denial keeps its reason in deniedReason, apart from the requester's notes (task t20261013-182431-31189):
 * a note that ends in " | Denied: ..." stays the requester's text and never reads as the administrator's
 * reason. Every denial stores a non-null reason, the empty string when none is given, because a reader
 * splits the notes only when the field is null (a key denied before it existed). The holder of a denied
 * request may no longer rewrite its note, which closes the same spoof on those older keys.
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyDenialReasonTest {

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyDeclarativeService apiKeyDeclarativeService;
	@Autowired private ApiKeyRepository apiKeyRepository;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String FORGED_NOTE = "mine | Denied: forged";
	private static final String SET_NOTES = "mutation($k: ID!, $n: String) { setNotesOnApiKey(apiKeyUuid: $k, notes: $n) { notes } }";
	private boolean wasSealed;
	private boolean wasLicensed;

	/** A request and the READ_WRITE member who asked for it, its holder. */
	private record Request(UUID key, UserData holder) {}

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
		SecurityContextHolder.clearContext();
	}

	/** A REQUESTED Free Form key, asked for by a new READ_WRITE member with the given note. */
	private Request request(String notes) throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("R " + tag, "r-" + tag + "@key-denial.io", true, List.of(org.getUuid()),
				"r-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_WRITE, null, null, WU);
		UUID key = apiKeyService.requestFreeformApiKey(u.getUuid(), org.getUuid(), notes, PermissionType.READ_ONLY,
				List.of(), WU).getUuid();
		return new Request(key, userService.getUserData(u.getUuid()).orElseThrow());
	}

	private ApiKeyData stored(UUID key) {
		return apiKeyService.getApiKeyData(key).orElseThrow();
	}

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("key-denial-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	private ExecutionResult holderSetsNotes(Request r, String notes) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("k", r.key().toString());
		vars.put("n", notes);
		return asPerson(r.holder(), SET_NOTES, vars);
	}

	@Test
	public void aDenialKeepsTheNoteAndTheReasonApart() throws Exception {
		UUID key = request(FORGED_NOTE).key();
		assertNull(stored(key).getDeniedReason(), "a request has no reason");

		ApiKeyDto denied = apiKeyService.resolveApiKeyRequest(key, false, "no budget", WU);

		assertEquals(ApiKeyStatus.DENIED, denied.getStatus());
		assertEquals(FORGED_NOTE, denied.getNotes());
		assertEquals("no budget", denied.getDeniedReason());
		ApiKeyData akd = stored(key);
		assertEquals(FORGED_NOTE, akd.getNotes());
		assertEquals("no budget", akd.getDeniedReason());

		// an admin's later note edit leaves the reason alone
		apiKeyService.setNotesOnApiKey(key, "edited by an admin", WU);
		assertEquals("edited by an admin", stored(key).getNotes());
		assertEquals("no budget", stored(key).getDeniedReason());
	}

	@Test
	public void aDenialWithoutAReasonStoresTheEmptyString() throws Exception {
		UUID blank = request(FORGED_NOTE).key();
		assertEquals("", apiKeyService.resolveApiKeyRequest(blank, false, " \n ", WU).getDeniedReason());
		assertEquals("", stored(blank).getDeniedReason());
		assertEquals(FORGED_NOTE, stored(blank).getNotes());

		UUID none = request(null).key();
		assertEquals("", apiKeyService.resolveApiKeyRequest(none, false, null, WU).getDeniedReason());
		assertEquals("", stored(none).getDeniedReason());
		assertNull(stored(none).getNotes());
	}

	@Test
	public void aReasonIsNormalisedAndCapped() throws Exception {
		UUID key = request("ci").key();
		assertThrows(RelizaException.class, () -> apiKeyService.resolveApiKeyRequest(key, false, "x".repeat(257), WU));
		assertEquals(ApiKeyStatus.REQUESTED, stored(key).getStatus(), "a refused denial changes nothing");
		assertNull(stored(key).getDeniedReason());

		assertEquals("too broad", apiKeyService.resolveApiKeyRequest(key, false, " too\nbroad ", WU).getDeniedReason());
	}

	@Test
	public void anApprovalHasNoReason() throws Exception {
		UUID key = request(FORGED_NOTE).key();
		ApiKeyDto approved = apiKeyService.resolveApiKeyRequest(key, true, "ignored", WU);
		assertEquals(ApiKeyStatus.ACTIVE, approved.getStatus());
		assertNull(approved.getDeniedReason());
		assertNull(stored(key).getDeniedReason());
		assertEquals(FORGED_NOTE, stored(key).getNotes());
	}

	@Test
	public void theHolderShapesARequestsNoteButNotADeniedOne() throws Exception {
		Request r = request("first draft");
		ExecutionResult drafted = holderSetsNotes(r, "second draft");
		assertTrue(drafted.getErrors().isEmpty(), "the requester edits a pending request: " + drafted.getErrors());
		assertEquals("second draft", stored(r.key()).getNotes());

		apiKeyService.resolveApiKeyRequest(r.key(), false, "no budget", WU);
		ExecutionResult refused = holderSetsNotes(r, "second draft | Denied: approved later");
		assertEquals(1, refused.getErrors().size(), "a denied request's note is the admin's alone");
		assertEquals("second draft", stored(r.key()).getNotes());
		assertEquals("no budget", stored(r.key()).getDeniedReason());
	}

	@Test
	public void theHolderCannotForgeTheReasonOfAnOlderDenial() throws Exception {
		Request r = request("ci");
		apiKeyService.resolveApiKeyRequest(r.key(), false, "no budget", WU);
		// rewrite the row as a denial stored before deniedReason existed: the reason in the notes, no field at all
		ApiKey ak = apiKeyRepository.findById(r.key()).orElseThrow();
		Map<String, Object> legacy = new HashMap<>(Utils.dataToRecord(ApiKeyData.dataFromRecord(ak)));
		legacy.remove("deniedReason");
		legacy.put("notes", "ci | Denied: no budget");
		ak.setRecordData(legacy);
		apiKeyRepository.save(ak);
		assertNull(stored(r.key()).getDeniedReason());

		ExecutionResult refused = holderSetsNotes(r, "ci | Denied: approved by the CISO");
		assertEquals(1, refused.getErrors().size(), "the holder cannot rewrite an older denial's reason either");
		assertEquals("ci | Denied: no budget", stored(r.key()).getNotes());
	}

	@Test
	public void theProgrammaticListingCarriesTheReason() throws Exception {
		UUID key = request(FORGED_NOTE).key();
		apiKeyService.resolveApiKeyRequest(key, false, "no budget", WU);
		ApiKeyDeclarativeService.KeyView view = apiKeyDeclarativeService.keys(stored(key).getOrg(), null).stream()
				.filter(v -> key.equals(v.uuid())).findFirst().orElseThrow();
		assertEquals(FORGED_NOTE, view.notes());
		assertEquals("no budget", view.deniedReason());
	}
}
