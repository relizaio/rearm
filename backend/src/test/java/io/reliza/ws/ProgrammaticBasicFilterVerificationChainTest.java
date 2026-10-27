/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.ApiKeyService;

/**
 * TPF-27 (architecture-3 section 4.2, cases 1 to 11): {@link ProgrammaticAuthenticationFilter} verifies an HTTP
 * Basic key credential once, for every key type, behind the {@link RateLimitingFilter}, stores the bearer-shaped
 * principal of the key row, and answers every credential that does not verify with one 401. Driven through the real
 * programmatic chain of the application context over mock requests; the limiter's clock is paused per case and every
 * request comes from a fresh address. Case 12 (TPF-24) pins that every spelling of the GraphQL path and the downloads the
 * chain claims is filtered as the plain one. The assertion helpers and the refusal maps live in the base, shared with
 * the /graphql lane's {@link LegacyGraphqlBasicChainTest} (TPF-29), which drives the same maps through the browser chain.
 */
public class ProgrammaticBasicFilterVerificationChainTest extends ProgrammaticBasicVerificationChainTestBase {

	@Autowired private DgsQueryExecutor dgsQueryExecutor;

	private static final String GRAPHQL = ProgrammaticGraphQlConfig.PATH;

	/** The GraphQL path (POST) and a programmatic download (GET) of a fresh artifact uuid: the filter reads neither body nor uuid. */
	private static List<String[]> paths() {
		return List.of(new String[] {"POST", GRAPHQL}, new String[] {"GET", "/api/programmatic/v1/artifact/" + UUID.randomUUID() + "/download"});
	}

	private Run call(String[] path, String authorization) throws Exception {
		return programmatic(withAuthorization(path[0], path[1], freshAddress(), authorization));
	}

	// ---- case 1: a valid Basic of every type is admitted on both paths with the key row's principal

	@Test
	public void aValidBasicOfEveryTypeIsVerifiedOnceAndAdmitted() throws Exception {
		List<Key> keys = keyPerType();
		clearInvocations(apiKeyService);
		for (Key k : keys) {
			for (String[] path : paths()) {
				for (int attempt = 1; attempt <= 2; attempt++) {
					String address = freshAddress();
					Run r = programmatic(withAuthorization(path[0], path[1], address, basic(k.id(), k.secret())));
					assertAdmittedBasic(r, k, address, k.type() + " on " + path[1] + " attempt " + attempt);
				}
			}
		}
		// one isMatchingApiKey per admitted request; the second call per path is a cache hit inside it
		verify(apiKeyService, times(2 * 7 * 2)).isMatchingApiKey(any());
	}

	// ---- case 2: a wrong secret of every type is the one 401, and writes no access row

	@Test
	public void aWrongSecretOfEveryTypeIsTheInvalidCredential401() throws Exception {
		List<Key> keys = keyPerType();
		Map<UUID, Integer> before = new LinkedHashMap<>();
		for (Key k : keys) before.put(k.uuid(), keyAccessCount(k.uuid()));
		for (Map.Entry<String, Run> e : wrongSecrets(keys, paths(), this::call).entrySet()) assertRefused(e.getValue(), e.getKey());
		for (Key k : keys) assertEquals(before.get(k.uuid()), keyAccessCount(k.uuid()), k.type() + ": no access row");
	}

	// ---- case 3: an unknown id of every type, and an APPROVAL id

	@Test
	public void anUnknownIdOfEveryTypeIsTheInvalidCredential401() throws Exception {
		for (Map.Entry<String, Run> e : unknownIds(paths(), this::call).entrySet()) assertRefused(e.getValue(), e.getKey());
	}

	// ---- case 4: malformed headers and foreign schemes through the real chain

	@Test
	public void aMalformedHeaderOrAForeignSchemeIsTheInvalidCredential401() throws Exception {
		clearInvocations(apiKeyService);
		for (Map.Entry<String, Run> e : malformedAndForeign(paths(), this::call).entrySet()) assertRefused(e.getValue(), e.getKey());
		verify(apiKeyService, never()).isMatchingApiKey(any());
	}

	// ---- case 5: every refusal is one answer, and the bearer refusal's

	@Test
	public void everyRefusalIsOneAnswer() throws Exception {
		Map<String, Run> all = new LinkedHashMap<>();
		all.putAll(wrongSecrets(keyPerType(), paths(), this::call));
		all.putAll(unknownIds(paths(), this::call));
		all.putAll(malformedAndForeign(paths(), this::call));
		Run bearer = programmatic(withAuthorization("POST", GRAPHQL, freshAddress(), "Bearer garbage"));
		assertRefused(bearer, "a garbage bearer");
		Map<String, Object> reference = answerOf(bearer);
		List<String> differing = new ArrayList<>();
		for (Map.Entry<String, Run> e : all.entrySet()) {
			if (!reference.equals(answerOf(e.getValue()))) differing.add(e.getKey() + ": " + answerOf(e.getValue()));
		}
		assertTrue(differing.isEmpty(), "reference " + reference + "; differing " + differing);
	}

	// ---- case 6: the org named in the id text is dropped

	@Test
	public void theTextOrgIsDropped() throws Exception {
		Key k = freeformKey();
		String own = "FREEFORM__" + k.object() + "__ORGANIZATION__" + k.org() + "__ord__" + k.order();
		MockHttpServletRequest req = withAuthorization("POST", GRAPHQL, freshAddress(), basic(own, k.secret()));
		Run r = programmatic(req);
		AuthHeaderParse p = assertInstanceOf(AuthHeaderParse.class, r.principal(), "admitted: " + r.response().getStatus());
		assertTrue(r.reached());
		assertEquals(k.uuid(), p.getVerifiedKeyUuid());
		assertNull(p.getOrgUuid(), "the principal carries no org for a FREEFORM key");
		ProgrammaticAuthContext ctx = authorizationService.authenticateProgrammaticWithOrg(headersOf(req), new ServletWebRequest(req));
		assertEquals(k.org(), ctx.orgUuid(), "the org comes from the key row");

		UUID another = testInitializer.obtainOrganization().getUuid();
		String foreign = "FREEFORM__" + k.object() + "__ORGANIZATION__" + another + "__ord__" + k.order();
		assertRefused(programmatic(withAuthorization("POST", GRAPHQL, freshAddress(), basic(foreign, k.secret()))),
				"the row is not found under another org");
	}

	// ---- case 7: an inactive key and a FEDERATED identity are refused

	@Test
	public void anInactiveKeyAndAFederatedIdentityAreRefused() throws Exception {
		Key inactive = freeformKey();
		apiKeyService.setApiKeyStatus(inactive.uuid(), ApiKeyStatus.INACTIVE, false, WU);
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(FederatedTrustRule.Provider.GITHUB_ACTIONS);
		fi.setOwner("acme");
		fi.setRepository("acme/app-" + UUID.randomUUID());
		fi.setRepositoryUri("github.com/acme/app");
		ApiKey identity = apiKeyService.ensureFederatedIdentity(testInitializer.obtainOrganization().getUuid(), fi, WU);
		String identityId = ApiKeyService.keyIdOf(identity);
		assertTrue(identityId.startsWith("FEDERATED__"), identityId);
		for (String[] path : paths()) {
			assertRefused(call(path, basic(inactive.id(), inactive.secret())), "inactive key on " + path[1]);
			assertRefused(call(path, basic(identityId, "any")), "federated identity on " + path[1]);
		}
	}

	// ---- case 8: the ambiguous no-order form is the one 401, not an exception

	@Test
	public void theAmbiguousNoOrderFormIsTheInvalidCredential401() throws Exception {
		Key first = freeformKey();
		freeformKey(first.org());
		Run r = assertDoesNotThrow(() -> programmatic(withAuthorization("POST", GRAPHQL, freshAddress(), basic("FREEFORM__" + first.org(), "any"))));
		assertRefused(r, "two FREEFORM keys on one org, presented without an order");
		assertEquals("99", r.remaining(), "its own bucket");
	}

	// ---- case 9: the filter writes no access row; the key row records the secret's last use

	@Test
	public void theFilterWritesNoAccessRowAndTouchesTheSecret() throws Exception {
		Key k = freeformKey();
		clearInvocations(apiKeyAccessService);
		int before = keyAccessCount(k.uuid());
		String address = freshAddress();
		assertAdmittedBasic(programmatic(withAuthorization("POST", GRAPHQL, address, basic(k.id(), k.secret()))), k, address, "valid FREEFORM");
		assertEquals(before, keyAccessCount(k.uuid()), "no access row from the filter");
		verify(apiKeyAccessService, never()).recordApiKeyAccess(any(), any(), any(), any());
		ApiKey row = apiKeyService.getApiKey(k.uuid()).orElseThrow();
		ApiKeySecret secret = ApiKeyData.dataFromRecord(row).effectiveSecrets(row.getApiKey()).get(0);
		assertNotNull(secret.getLastUsedDate(), "the successful match touched the secret");
	}

	// ---- case 10: the bearer and no-credential branches are unchanged

	@Test
	public void theBearerAndNoCredentialAnswersAreUnchanged() throws Exception {
		Key k = freeformKey();
		String token = mint(k.uuid());
		Key expiring = freeformKey();
		apiKeyService.setApiKeySecretExpiry(expiring.uuid(), firstSecret(expiring.uuid()).getSlot(), ZonedDateTime.now().minusHours(2), WU);
		String expired = mint(expiring.uuid());
		for (String[] path : paths()) {
			Run ok = call(path, "Bearer " + token);
			assertTrue(ok.reached(), path[1] + ": " + ok.response().getStatus());
			assertEquals(k.uuid(), assertInstanceOf(AuthHeaderParse.class, ok.principal()).getVerifiedKeyUuid(), path[1]);
			assertNull(ok.response().getHeader("Deprecation"), path[1] + ": a bearer is not deprecated");

			assertRefused(call(path, "Bearer " + expired), "born-expired bearer on " + path[1]);

			Run none = call(path, null);
			assertEquals(401, none.response().getStatus(), path[1]);
			assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE, none.response().getHeader(HttpHeaders.WWW_AUTHENTICATE), path[1]);
			assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_BODY, none.response().getContentAsString(StandardCharsets.UTF_8), path[1]);
			assertTrue(String.join(", ", none.response().getHeaders(HttpHeaders.CACHE_CONTROL)).contains("no-store"), path[1]);
			assertFalse(none.reached(), path[1]);
		}
	}

	// ---- case 11: a forged ORGANIZATION_RW id never reaches a resolver; a verified one does, and the resolver accepts it

	private static final String NEW_VERSION = "mutation($c: ID) { getNewVersionProgrammatic(newVersionInput: { component: $c, branch: \"main\","
			+ " onlyVersion: true }) { version } }";

	@Test
	public void aForgedOrganizationKeyNeverReachesAResolverAndAVerifiedOneIsAccepted() throws Exception {
		Key component = componentKey();
		Key orgRw = objectKey(component.org(), ApiTypeEnum.ORGANIZATION_RW, component.org());

		assertRefused(programmatic(withAuthorization("POST", GRAPHQL, freshAddress(), basic(orgRw.id(), "any"))), "forged ORGANIZATION_RW");

		MockHttpServletRequest req = withAuthorization("POST", GRAPHQL, freshAddress(), basic(orgRw.id(), orgRw.secret()));
		Run r = programmatic(req);
		assertTrue(r.reached(), "status " + r.response().getStatus());
		assertNotNull(r.principal());
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		ExecutionResult er = dgsQueryExecutor.execute(NEW_VERSION, Map.of("c", component.object().toString()), Map.of(), headersOf(req), null,
				new ServletWebRequest(req));
		assertTrue(er.getErrors().isEmpty(), String.valueOf(er.getErrors()));
		Map<String, Object> data = er.getData();
		@SuppressWarnings("unchecked")
		String version = (String) ((Map<String, Object>) data.get("getNewVersionProgrammatic")).get("version");
		assertNotNull(version);
		assertFalse(version.isEmpty());
		// the executor-level control (condition 2, the /graphql lane): ProgrammaticComponentWriteOrderTest.asBasic with the
		// forged id still answers Not authorized, in that class's own case
	}

	// ---- case 12 (TPF-24, architecture-6 6.1 item 1): every spelling of a filtered path the chain claims is filtered

	@Test
	public void everySpellingOfAFilteredPathIsFilteredAsThePlainOne() throws Exception {
		Key k = freeformKey();
		String forged = basic("FREEFORM__" + UUID.randomUUID() + "__ORGANIZATION__" + testInitializer.obtainOrganization().getUuid() + "__ord__0", "x");
		UUID artifact = UUID.randomUUID();
		String download = "/v1/artifact/" + artifact + "/download";
		String rawdownload = "/v1/artifact/" + artifact + "/rawdownload";
		// method, spelled request URI, the plain spelling of the same path, whether it is the GraphQL path
		List<String[]> spellings = List.of(
				new String[] {"POST", "/api/programmatic/%67raphql", GRAPHQL, "graphql"},
				new String[] {"POST", "/api/programmatic/graph%71l", GRAPHQL, "graphql"},
				new String[] {"POST", "/api/%70rogrammatic/graphql", GRAPHQL, "graphql"},
				new String[] {"GET", "/api/programmatic/v1/%61rtifact/" + artifact + "/download", "/api/programmatic" + download, "download"},
				new String[] {"GET", "/api/%70rogrammatic/v1/artifact/" + artifact + "/rawdownload", "/api/programmatic" + rawdownload, "download"});
		for (String[] s : spellings) {
			String method = s[0];
			String spelled = s[1];
			String what = method + " " + spelled;
			// (i) the programmatic chain claims the spelling
			assertSame(programmaticFilterChain, servedBy(spelled(method, spelled, freshAddress(), null)), what + ": served by the programmatic chain");
			// (ii) a forged org-segment id: the one 401, not reached, the same answer as on the plain spelling
			Run forgedRun = programmatic(spelled(method, spelled, freshAddress(), forged));
			assertRefused(forgedRun, what + ": forged org-segment id");
			Run forgedPlain = programmatic(withAuthorization(method, s[2], freshAddress(), forged));
			assertRefused(forgedPlain, method + " " + s[2] + ": forged org-segment id on the plain spelling");
			assertEquals(answerOf(forgedPlain), answerOf(forgedRun), what + ": the same answer as the plain spelling");
			// (iii) a real key with a wrong secret
			assertRefused(programmatic(spelled(method, spelled, freshAddress(), basic(k.id(), k.secret() + "x"))), what + ": wrong secret");
			// (iv) no credential
			Run none = programmatic(spelled(method, spelled, freshAddress(), null));
			assertEquals(401, none.response().getStatus(), what + ": no credential");
			assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE, none.response().getHeader(HttpHeaders.WWW_AUTHENTICATE), what);
			assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_BODY, none.response().getContentAsString(StandardCharsets.UTF_8), what);
			assertFalse(none.reached(), what + ": no credential is not reached");
			assertNull(none.principal(), what + ": no principal without a credential");
			// (v) the real key: admitted with the plain spelling's principal, verified once, in the filter
			clearInvocations(apiKeyService);
			String address = freshAddress();
			MockHttpServletRequest valid = spelled(method, spelled, address, basic(k.id(), k.secret()));
			assertAdmittedBasic(programmatic(valid), k, address, what + ": valid key");
			verify(apiKeyService, times(1)).isMatchingApiKey(any());
			if ("graphql".equals(s[3])) {
				AuthHeaderParse seen = authorizationService.authenticateProgrammatic(headersOf(valid), new ServletWebRequest(valid));
				assertEquals(k.uuid(), seen.getVerifiedKeyUuid(), what + ": the resolver sees the filter's principal");
			}
		}

		// (vi) a double encoding is not served programmatically: the browser chain claims it, and the firewall refuses it first
		String doubleEncoded = "/api/programmatic/%2567raphql";
		assertSame(filterChain, servedBy(spelled("POST", doubleEncoded, freshAddress(), forged)), doubleEncoded + ": not the programmatic chain");
		MockHttpServletRequest rejected = spelled("POST", doubleEncoded, freshAddress(), forged);
		Run firewall = throughAll(rejected);
		assertEquals(400, firewall.response().getStatus(), doubleEncoded + ": StrictHttpFirewall refuses %25 with 400 before any chain");
		assertFalse(firewall.reached(), doubleEncoded + ": not reached");
		assertNull(firewall.principal(), doubleEncoded + ": no principal");

		// (vii) a trailing slash is not served programmatically: no PathPattern of the chain matches it
		String trailing = GRAPHQL + "/";
		assertSame(filterChain, servedBy(spelled("POST", trailing, freshAddress(), forged)), trailing + ": not the programmatic chain");
		Run browser = throughAll(spelled("POST", trailing, freshAddress(), forged));
		assertNull(browser.principal(), trailing + ": no programmatic principal");
		String challenge = browser.response().getHeader(HttpHeaders.WWW_AUTHENTICATE);
		assertFalse(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE.equals(challenge)
				|| ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE.equals(challenge),
				trailing + ": no programmatic 401, the browser chain answered " + browser.response().getStatus() + " " + challenge);
	}

	/** Every chain, as the servlet container runs them: the firewall first, then the first matching chain. */
	private Run throughAll(MockHttpServletRequest req) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain after = new MockFilterChain();
		new FilterChainProxy(chains).doFilter(req, resp, after);
		return new Run(req, resp, after);
	}
}
