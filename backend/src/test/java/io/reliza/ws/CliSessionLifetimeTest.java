/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CliSessionCodes;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.OAuthErrors;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.CliSession;
import io.reliza.model.CliSession.Status;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.CliSessionRepository;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.CliSessionService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * A key's sessionMaxMinutes is a hard end for the device-login sessions approved on it (task RD3-7).
 * Driven through the public paths: approval through GraphQL as the approving person, collection
 * and refresh through the token endpoint.
 */
@SpringBootTest(classes = {App.class})
public class CliSessionLifetimeTest {

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiTokenService apiTokenService;
	@Autowired private CliSessionService cliSessionService;
	@Autowired private CliSessionRepository cliSessionRepository;
	@Autowired private ProgrammaticTokenController tokenController;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
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

	private record World(Organization org, UserData owner, ApiKey key) {}

	private UserData member(Organization org, PermissionType level) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("L " + tag, "l-" + tag + "@lifetime.io", true, List.of(org.getUuid()),
				"l-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level, null, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A member with a personal key, its bound set as given (null: none). */
	private World world(Integer sessionMaxMinutes) throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData owner = member(org, PermissionType.READ_WRITE);
		ApiKey key = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "lifetime test", WU);
		if (sessionMaxMinutes != null) apiKeyService.setSessionMaxMinutesOnApiKey(key.getUuid(), sessionMaxMinutes, WU);
		return new World(org, owner, key);
	}

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("lifetime-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	private static final String APPROVE = "mutation($c: String!, $k: ID, $m: Int) { approveCliLogin(userCode: $c, apiKeyUuid: $k, maxMinutes: $m) "
			+ "{ uuid status approvedDate expiresDate hardExpiresDate } }";
	private static final String APPROVE_NEW = "mutation($c: String!, $o: ID, $m: Int) { approveCliLogin(userCode: $c, createKeyOrgUuid: $o, maxMinutes: $m) "
			+ "{ uuid status hardExpiresDate } }";
	private static final String SET_BOUND = "mutation($k: ID!, $m: Int) { setApiKeySessionMaxMinutes(apiKeyUuid: $k, sessionMaxMinutes: $m) "
			+ "{ uuid sessionMaxMinutes } }";

	private record Login(String deviceCode, String userCode) {}

	private Login start() {
		CliSessionService.DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo("lifetime-host", null, null, null, null));
		return new Login(d.deviceCode(), d.userCode());
	}

	private Map<String, Object> approve(World w, Login l, Integer maxMinutes) {
		Map<String, Object> vars = new java.util.HashMap<>();
		vars.put("c", l.userCode());
		vars.put("k", w.key().getUuid().toString());
		vars.put("m", maxMinutes);
		ExecutionResult r = asPerson(w.owner(), APPROVE, vars);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> data = r.getData();
		@SuppressWarnings("unchecked")
		Map<String, Object> s = (Map<String, Object>) data.get("approveCliLogin");
		return s;
	}

	private Map<String, Object> token(String grant, String deviceCode, String refreshToken) {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticTokenController.PATH);
		req.setRemoteAddr("198.51.100.7");
		Map<String, Object> body = tokenController.token(req, grant, null, null, deviceCode, refreshToken, null).getBody();
		assertNotNull(body);
		return body;
	}

	private Map<String, Object> collect(Login l) {
		Map<String, Object> body = token(OAuthErrors.GRANT_DEVICE_CODE, l.deviceCode(), null);
		assertNull(body.get("error"), String.valueOf(body));
		return body;
	}

	private CliSession row(Map<String, Object> body) {
		return cliSessionRepository.findById(UUID.fromString((String) body.get("session"))).orElseThrow();
	}

	private static long secondsBetween(ZonedDateTime a, ZonedDateTime b) {
		return Math.abs(Duration.between(a, b).getSeconds());
	}

	/** Moves a session's clock: its approval and both expiries shift back by {@code elapsed}, as if that much time had passed. */
	private void age(CliSession s, Duration elapsed) {
		s.setApprovedDate(s.getApprovedDate().minus(elapsed));
		s.setExpiresDate(s.getExpiresDate().minus(elapsed));
		if (s.getHardExpiresDate() != null) s.setHardExpiresDate(s.getHardExpiresDate().minus(elapsed));
		cliSessionRepository.saveAndFlush(s);
	}

	@Test
	public void approvalWithTheKnobFixesTheHardEndAndTheSessionExpiryIsIt() throws Exception {
		World w = world(90);
		Map<String, Object> approved = approve(w, start(), null);
		CliSession s = cliSessionRepository.findById(UUID.fromString((String) approved.get("uuid"))).orElseThrow();
		assertEquals(Status.ACTIVE, s.getStatus());
		assertEquals(s.getApprovedDate().plusMinutes(90).toInstant(), s.getHardExpiresDate().toInstant(), "approval plus the knob");
		assertEquals(s.getHardExpiresDate().toInstant(), s.getExpiresDate().toInstant(), "the sliding expiry starts at the hard end");
		assertNotNull(approved.get("hardExpiresDate"), "CliSession.hardExpiresDate is served");
	}

	@Test
	public void noKnobIsTodaysBehaviour() throws Exception {
		World w = world(null);
		Login l = start();
		approve(w, l, null);
		Map<String, Object> body = collect(l);
		CliSession s = row(body);
		assertNull(s.getHardExpiresDate());
		assertTrue(secondsBetween(s.getApprovedDate().plusDays(30), s.getExpiresDate()) < 5, "30 days sliding");
		assertNotNull(body.get("refresh_token"));
		assertEquals(3600L, ((Number) body.get("expires_in")).longValue());
		assertFalse(body.containsKey("session_hard_expiry"));
	}

	@Test
	public void atOrUnderAnHourTheLoginDeliversNoRefreshTokenAndOneClippedToken() throws Exception {
		for (int minutes : new int[] {30, 60}) {
			World w = world(minutes);
			Login l = start();
			approve(w, l, null);
			Map<String, Object> body = collect(l);
			assertFalse(body.containsKey("refresh_token"), minutes + ": no refresh token");
			long expiresIn = ((Number) body.get("expires_in")).longValue();
			assertTrue(expiresIn <= minutes * 60L && expiresIn > minutes * 60L - 10, minutes + ": expires_in " + expiresIn);
			CliSession s = row(body);
			assertEquals(s.getHardExpiresDate().toInstant().toString(), body.get("session_hard_expiry"));
			ApiTokenService.TokenClaims claims = apiTokenService.verify((String) body.get("access_token"), ApiTokenService.AUD_PROGRAMMATIC).orElseThrow();
			assertTrue(secondsBetween(s.getHardExpiresDate(), claims.expiresAt().atZone(s.getHardExpiresDate().getZone())) <= 1,
					minutes + ": the token's exp is the hard end");
			assertTrue(cliSessionService.usable(s.getUuid(), claims.fingerprint()).isPresent(),
					minutes + ": the token is usable although no refresh token was handed out");
		}
		World w = world(61);
		Login l = start();
		approve(w, l, null);
		Map<String, Object> body = collect(l);
		assertNotNull(body.get("refresh_token"), "61: the session outlives the first token");
		assertEquals(3600L, ((Number) body.get("expires_in")).longValue());
	}

	@Test
	public void aRefreshInsideTheWindowIsClippedAndOneAfterTheEndIsRefused() throws Exception {
		World w = world(90);
		Login l = start();
		approve(w, l, null);
		Map<String, Object> first = collect(l);
		assertEquals(3600L, ((Number) first.get("expires_in")).longValue(), "90: a 60-minute first token");
		CliSession s = row(first);
		ZonedDateTime hardEnd = s.getHardExpiresDate();

		// an hour on: the refresh works and its token lasts the remaining 30 minutes
		age(s, Duration.ofMinutes(60));
		Map<String, Object> refreshed = token(OAuthErrors.GRANT_REFRESH_TOKEN, null, (String) first.get("refresh_token"));
		assertNull(refreshed.get("error"), String.valueOf(refreshed));
		long expiresIn = ((Number) refreshed.get("expires_in")).longValue();
		assertTrue(expiresIn <= 30 * 60 && expiresIn > 30 * 60 - 10, "expires_in " + expiresIn);
		CliSession after = cliSessionRepository.findById(s.getUuid()).orElseThrow();
		assertEquals(after.getHardExpiresDate().toInstant(), after.getExpiresDate().toInstant(), "the refresh did not slide past the end");
		assertEquals(hardEnd.minusMinutes(60).toInstant(), after.getHardExpiresDate().toInstant(), "the hard end did not move");
		assertEquals(after.getHardExpiresDate().toInstant().toString(), refreshed.get("session_hard_expiry"));

		// past the end: refused, and the refusal says when it ended and what to do
		age(after, Duration.ofMinutes(31));
		CliSession ended = cliSessionRepository.findById(s.getUuid()).orElseThrow();
		Map<String, Object> refused = token(OAuthErrors.GRANT_REFRESH_TOKEN, null, (String) refreshed.get("refresh_token"));
		assertEquals(OAuthErrors.INVALID_GRANT, refused.get("error"));
		assertEquals("session ended at " + ended.getHardExpiresDate().toInstant() + "; run rearm login", refused.get("error_description"));
		assertEquals(Status.ACTIVE, cliSessionRepository.findById(s.getUuid()).orElseThrow().getStatus(), "an ended session is not revoked, only over");
	}

	@Test
	public void theApproverMayShortenButNotLengthen() throws Exception {
		World w = world(90);
		Map<String, Object> shorter = approve(w, start(), 30);
		CliSession s = cliSessionRepository.findById(UUID.fromString((String) shorter.get("uuid"))).orElseThrow();
		assertEquals(s.getApprovedDate().plusMinutes(30).toInstant(), s.getHardExpiresDate().toInstant());

		Login l = start();
		Map<String, Object> vars = new java.util.HashMap<>();
		vars.put("c", l.userCode());
		vars.put("k", w.key().getUuid().toString());
		vars.put("m", 91);
		ExecutionResult longer = asPerson(w.owner(), APPROVE, vars);
		assertEquals(1, longer.getErrors().size(), longer.getErrors().toString());
		assertEquals("This key bounds its sessions to 90 minutes; choose 90 or fewer", longer.getErrors().get(0).getMessage());
		assertTrue(cliSessionService.pending(l.userCode()).isPresent(), "the refused approval leaves the login pending");

		// a fresh key has no bound: the approver's lifetime is taken, up to the 90-day cap
		Login fresh = start();
		Map<String, Object> nv = new java.util.HashMap<>();
		nv.put("c", fresh.userCode());
		nv.put("o", w.org().getUuid().toString());
		nv.put("m", 45);
		ExecutionResult r = asPerson(w.owner(), APPROVE_NEW, nv);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Login tooLong = start();
		nv.put("c", tooLong.userCode());
		nv.put("m", CliSessionCodes.MAX_SESSION_MINUTES + 1);
		int keysBefore = apiKeyService.listApiKeyByOrg(w.org().getUuid()).size();
		ExecutionResult refused = asPerson(w.owner(), APPROVE_NEW, nv);
		assertEquals(1, refused.getErrors().size(), refused.getErrors().toString());
		assertEquals(keysBefore, apiKeyService.listApiKeyByOrg(w.org().getUuid()).size(), "no key is created for a refused approval");
		assertTrue(cliSessionService.pending(tooLong.userCode()).isPresent());
	}

	@Test
	public void aKnobSetAfterApprovalLeavesTheOpenSessionAlone() throws Exception {
		World w = world(90);
		Login l = start();
		approve(w, l, null);
		Map<String, Object> body = collect(l);
		ZonedDateTime hardEnd = row(body).getHardExpiresDate();
		apiKeyService.setSessionMaxMinutesOnApiKey(w.key().getUuid(), 5, WU);
		Map<String, Object> refreshed = token(OAuthErrors.GRANT_REFRESH_TOKEN, null, (String) body.get("refresh_token"));
		assertNull(refreshed.get("error"), String.valueOf(refreshed));
		assertEquals(hardEnd.toInstant(), row(body).getHardExpiresDate().toInstant());
		assertEquals(3600L, ((Number) refreshed.get("expires_in")).longValue(), "still the 90-minute session's first hour");
	}

	@Test
	public void theKnobIsValidatedAndGatedLikeTheKeysOtherEdits() throws Exception {
		World w = world(null);
		// the owner sets it on their personal key, and it reads back
		ExecutionResult r = asPerson(w.owner(), SET_BOUND, Map.of("k", w.key().getUuid().toString(), "m", 129600));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> data = r.getData();
		assertEquals(129600, ((Map<?, ?>) data.get("setApiKeySessionMaxMinutes")).get("sessionMaxMinutes"));

		for (int bad : new int[] {0, -5, 129601}) {
			ExecutionResult refused = asPerson(w.owner(), SET_BOUND, Map.of("k", w.key().getUuid().toString(), "m", bad));
			assertEquals(1, refused.getErrors().size(), bad + ": " + refused.getErrors());
			assertEquals("sessionMaxMinutes is 1 to 129600 minutes, or empty for no bound", refused.getErrors().get(0).getMessage());
		}
		Map<String, Object> lift = new java.util.HashMap<>();
		lift.put("k", w.key().getUuid().toString());
		lift.put("m", null);
		ExecutionResult lifted = asPerson(w.owner(), SET_BOUND, lift);
		assertTrue(lifted.getErrors().isEmpty(), lifted.getErrors().toString());
		assertNull(apiKeyService.getApiKeyData(w.key().getUuid()).orElseThrow().getSessionMaxMinutes(), "null lifts the bound");

		// another member who is not an admin may not touch someone's personal key; an org admin may
		UserData other = member(w.org(), PermissionType.READ_WRITE);
		ExecutionResult denied = asPerson(other, SET_BOUND, Map.of("k", w.key().getUuid().toString(), "m", 30));
		assertEquals(1, denied.getErrors().size());
		assertEquals("Not authorized", denied.getErrors().get(0).getMessage());
		UserData admin = member(w.org(), PermissionType.ADMIN);
		assertTrue(asPerson(admin, SET_BOUND, Map.of("k", w.key().getUuid().toString(), "m", 30)).getErrors().isEmpty());
		assertEquals(30, apiKeyService.getApiKeyData(w.key().getUuid()).orElseThrow().getSessionMaxMinutes());

		// a key that cannot back a device login does not take it
		ApiKey orgKey = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.ORGANIZATION, w.org().getUuid(),
				UUID.randomUUID().toString(), "org key", WU);
		ExecutionResult notForOrgKeys = asPerson(admin, SET_BOUND, Map.of("k", orgKey.getUuid().toString(), "m", 30));
		assertEquals("Only personal and Free Form keys back device-login sessions", notForOrgKeys.getErrors().get(0).getMessage());
	}
}
