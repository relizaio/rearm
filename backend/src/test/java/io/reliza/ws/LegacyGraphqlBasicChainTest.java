/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
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
import io.reliza.service.ApiKeyService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

/**
 * TPF-29 (architecture-3 section 5.2, cases 1 to 12): on the browser chain, {@link LegacyGraphqlBasicFilter} verifies an
 * HTTP Basic key credential on the legacy {@code /graphql} endpoint behind the {@link RateLimitingFilter}, with the
 * programmatic chain's {@link BasicKeyVerifier}: a valid key of every type is admitted with the key row's principal, every
 * other non-bearer value is the programmatic lane's one 401, bearer-prefixed values stay the resource server's, an
 * anonymous call and the CSRF handshake are untouched, and no spelling outside the filter's path reaches the handler
 * without an IdP session. Driven through the real chains of the application context over mock requests, on the shared
 * context of {@link ProgrammaticBasicVerificationChainTestBase}. Never send a signed JWT through the browser chain here
 * (see {@link #idpShapedToken()}). The fetchers' own answers to an unverified parse, behind the filter, stay recorded by
 * {@link GraphqlLaneResidueTest}.
 */
public class LegacyGraphqlBasicChainTest extends ProgrammaticBasicVerificationChainTestBase {

	@Autowired private DgsQueryExecutor dgsQueryExecutor;

	private static final String PATH = LegacyGraphqlBasicFilter.PATH;

	private static final String NEW_VERSION = "mutation($c: ID) { getNewVersionProgrammatic(newVersionInput: { component: $c, branch: \"main\","
			+ " onlyVersion: true }) { version } }";

	private Run browser(MockHttpServletRequest req) throws Exception {
		return through(filterChain, req, new MockFilterChain());
	}

	/** The CSRF handshake as the UI and the Go client send it on a POST: the cookie and the equal header. */
	private static MockHttpServletRequest csrf(MockHttpServletRequest req) {
		String t = UUID.randomUUID().toString();
		req.setCookies(new Cookie("XSRF-TOKEN", t));
		req.addHeader("X-XSRF-TOKEN", t);
		return req;
	}

	private static List<String[]> graphqlGet() {
		return List.<String[]>of(new String[] {"GET", PATH});
	}

	private Run send(String[] path, String authorization) throws Exception {
		return browser(withAuthorization(path[0], path[1], freshAddress(), authorization));
	}

	private static int indexOf(List<Filter> filters, Class<?> type) {
		List<Filter> found = filters.stream().filter(type::isInstance).toList();
		assertEquals(1, found.size(), "exactly one " + type.getSimpleName() + ": " + filters);
		return filters.indexOf(found.get(0));
	}

	/** Not reached and answered by the browser chain's own entry point, not by the verifier. */
	private static void assertEntryPoint401(Run r, String what) throws Exception {
		assertEquals(401, r.response().getStatus(), what);
		assertFalse(r.reached(), what + ": not reached");
		assertNull(r.principal(), what + ": no principal");
		String challenge = r.response().getHeader(HttpHeaders.WWW_AUTHENTICATE);
		assertNotNull(challenge, what);
		assertTrue(challenge.startsWith("Bearer"), what + ": " + challenge);
		assertNotEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, challenge, what + ": not the verifier's challenge");
		String body = r.response().getContentAsString(StandardCharsets.UTF_8);
		assertNotEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, body, what + ": not the verifier's body");
		assertTrue(body.isEmpty(), what + ": the entry point writes no body, " + body);
		assertNull(r.response().getHeader("Deprecation"), what);
		assertNull(r.response().getHeader("Link"), what);
		assertNull(r.response().getHeader("X-ReARM-Deprecation"), what);
	}

	// ---- case 1: structure

	@Test
	public void theFilterRunsAfterTheLimiterAndBeforeAuthorizationOnTheBrowserChainOnly() {
		List<Filter> browserFilters = filterChain.getFilters();
		int legacy = indexOf(browserFilters, LegacyGraphqlBasicFilter.class);
		assertTrue(legacy > indexOf(browserFilters, RateLimitingFilter.class), "after the limiter: " + browserFilters);
		assertTrue(legacy > indexOf(browserFilters, BearerTokenAuthenticationFilter.class), "after the resource server: " + browserFilters);
		assertTrue(legacy < indexOf(browserFilters, AuthorizationFilter.class), "before authorization: " + browserFilters);
		assertTrue(programmaticFilterChain.getFilters().stream().noneMatch(LegacyGraphqlBasicFilter.class::isInstance),
				"not on the programmatic chain");
		assertSame(filterChain, servedBy(request("GET", PATH, freshAddress())));
		assertSame(filterChain, servedBy(request("POST", PATH, freshAddress())));
		assertSame(filterChain, servedBy(spelled("GET", "/%67raphql", freshAddress(), null)));
	}

	// ---- case 2: a valid Basic of every type is admitted with the key row's principal, verified once

	@Test
	public void aValidBasicOfEveryTypeIsAdmittedWithTheKeyRowPrincipal() throws Exception {
		for (Key k : keyPerType()) {
			String what = k.type() + " on GET " + PATH;
			String address = freshAddress();
			MockHttpServletRequest req = withAuthorization("GET", PATH, address, basic(k.id(), k.secret()));
			clearInvocations(apiKeyService);
			Run r = browser(req);
			verify(apiKeyService, times(1)).isMatchingApiKey(any());
			AuthHeaderParse p = assertAdmittedBasic(r, k, address, what);
			assertSame(p, authorizationService.authenticateProgrammatic(headersOf(req), new ServletWebRequest(req)), what + ": the resolvers see the filter's principal");
			boolean noOrg = k.type() == ApiTypeEnum.INSTANCE || k.type() == ApiTypeEnum.CLUSTER || k.type() == ApiTypeEnum.COMPONENT;
			assertEquals(noOrg ? null : k.org(), authorizationService.authenticateProgrammaticWithOrg(headersOf(req), new ServletWebRequest(req)).orgUuid(),
					what + ": the org as on the programmatic chain");
		}
	}

	// ---- case 3: the CSRF handshake is untouched

	@Test
	public void theCsrfHandshakeIsUntouched() throws Exception {
		Key k = freeformKey();
		String address = freshAddress();
		assertAdmittedBasic(browser(csrf(withAuthorization("POST", PATH, address, basic(k.id(), k.secret())))), k, address, "POST with the handshake");

		clearInvocations(apiKeyService);
		Run r = browser(withAuthorization("POST", PATH, freshAddress(), basic(k.id(), k.secret())));
		assertEquals(403, r.response().getStatus(), "no handshake: the CSRF filter's 403");
		assertFalse(r.reached());
		assertNull(r.principal());
		verify(apiKeyService, never()).isMatchingApiKey(any());
	}

	// ---- case 4: the five refusal shapes, one answer, the programmatic chain's

	@Test
	public void everyRefusalIsTheProgrammaticChainsOneAnswer() throws Exception {
		Map<String, Run> all = new LinkedHashMap<>();
		all.putAll(wrongSecrets(keyPerType(), graphqlGet(), this::send));
		all.putAll(unknownIds(graphqlGet(), this::send));
		clearInvocations(apiKeyService);
		Map<String, Run> malformed = malformedAndForeign(graphqlGet(), this::send);
		malformed.put("an empty value", send(graphqlGet().get(0), ""));
		verify(apiKeyService, never()).isMatchingApiKey(any());
		all.putAll(malformed);

		Key inactive = freeformKey();
		apiKeyService.setApiKeyStatus(inactive.uuid(), ApiKeyStatus.INACTIVE, false, WU);
		all.put("an inactive key with its right secret", send(graphqlGet().get(0), basic(inactive.id(), inactive.secret())));
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(FederatedTrustRule.Provider.GITHUB_ACTIONS);
		fi.setOwner("acme");
		fi.setRepository("acme/app-" + UUID.randomUUID());
		fi.setRepositoryUri("github.com/acme/app");
		ApiKey identity = apiKeyService.ensureFederatedIdentity(testInitializer.obtainOrganization().getUuid(), fi, WU);
		String identityId = ApiKeyService.keyIdOf(identity);
		assertTrue(identityId.startsWith("FEDERATED__"), identityId);
		all.put("a federated identity", send(graphqlGet().get(0), basic(identityId, "any")));
		Key first = freeformKey();
		freeformKey(first.org());
		all.put("the ambiguous no-order form", send(graphqlGet().get(0), basic("FREEFORM__" + first.org(), "x")));

		Run bearer = programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), "Bearer garbage"));
		assertRefused(bearer, "the programmatic chain's garbage bearer");
		Map<String, Object> reference = answerOf(bearer);
		List<String> differing = new ArrayList<>();
		for (Map.Entry<String, Run> e : all.entrySet()) {
			assertRefused(e.getValue(), e.getKey());
			if (!reference.equals(answerOf(e.getValue()))) differing.add(e.getKey() + ": " + answerOf(e.getValue()));
		}
		assertTrue(differing.isEmpty(), "reference " + reference + "; differing " + differing);
	}

	// ---- TPF-51 (architecture-1 4.3): the orderless id of an ordered type is the one 401 on /graphql, whatever the org holds

	@Test
	public void anOrderlessFreeformIdIsRefusedOnGraphqlWhateverTheOrgHolds() throws Exception {
		Key first = freeformKey();
		Key second = freeformKey(first.org());
		Key only = freeformKey();
		for (Key k : List.of(first, only)) {
			String what = "the orderless id with a real secret, " + (k == first ? "two keys" : "one key");
			clearInvocations(apiKeyService);
			assertRefused(browser(csrf(withAuthorization("POST", PATH, freshAddress(), basic("FREEFORM__" + k.org(), k.secret())))), what);
			verify(apiKeyService, times(1)).isMatchingApiKey(any());
		}
		for (Key k : List.of(first, second, only)) {
			String address = freshAddress();
			assertAdmittedBasic(browser(csrf(withAuthorization("POST", PATH, address, basic(k.id(), k.secret())))), k, address,
					"own id of " + k.uuid());
		}
	}

	// ---- case 5: bearer-prefixed values are the resource server's, before the limiter and the filter

	@Test
	public void bearerPrefixedValuesAreTheResourceServersBeforeTheLimiter() throws Exception {
		clearInvocations(apiKeyService);
		for (String authorization : List.of("Bearer", "Bearer garbage", "Bearer " + unsignedToken())) {
			String what = "'" + authorization + "'";
			Run r = browser(withAuthorization("GET", PATH, freshAddress(), authorization));
			assertEquals(401, r.response().getStatus(), what);
			String challenge = r.response().getHeader(HttpHeaders.WWW_AUTHENTICATE);
			assertNotNull(challenge, what);
			assertTrue(challenge.startsWith("Bearer "), what + ": " + challenge);
			assertNotEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, challenge, what);
			assertNotEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), what);
			assertNull(r.remaining(), what + ": the limiter sits behind the resource server and did not run");
			assertNull(r.response().getHeader("Deprecation"), what);
			assertFalse(r.reached(), what);
			assertNull(r.principal(), what);
		}
		verify(apiKeyService, never()).isMatchingApiKey(any());
	}

	// ---- case 6: anonymous calls are untouched

	@Test
	public void anonymousCallsAreUntouched() throws Exception {
		clearInvocations(apiKeyService);
		for (MockHttpServletRequest req : List.of(withAuthorization("GET", PATH, freshAddress(), null),
				csrf(withAuthorization("POST", PATH, freshAddress(), null)))) {
			String what = "anonymous " + req.getMethod();
			Run r = browser(req);
			assertTrue(r.reached(), what + ": " + r.response().getStatus());
			assertNull(r.principal(), what);
			assertNull(r.response().getHeader("Deprecation"), what);
			assertEquals("99", r.remaining(), what + ": the address bucket");
		}
		String forged = basic("FREEFORM__" + UUID.randomUUID() + "__ord__0", "x");
		for (String path : List.of("/api/manual/v1/fetchCsrf", "/api/healthCheck")) {
			Run r = browser(withAuthorization("GET", path, freshAddress(), forged));
			assertTrue(r.reached(), path + ": " + r.response().getStatus());
			assertNull(r.principal(), path);
		}
		verify(apiKeyService, never()).isMatchingApiKey(any());
	}

	// ---- case 7: spellings, and the reachability property (architecture-3 decision 9)

	@Test
	public void onlyTheFiltersPathReachesTheHandlerWithoutAnIdpSession() throws Exception {
		String forged = basic("FREEFORM__" + UUID.randomUUID() + "__ORGANIZATION__" + testInitializer.obtainOrganization().getUuid() + "__ord__0", "x");
		Key k = freeformKey();
		String valid = basic(k.id(), k.secret());

		// (a) matched by both matchers: the encoded spelling is the verifier's
		assertRefused(browser(spelled("GET", "/%67raphql", freshAddress(), forged)), "forged on /%67raphql");
		String address = freshAddress();
		assertAdmittedBasic(browser(spelled("GET", "/%67raphql", address, valid)), k, address, "valid on /%67raphql");

		// (b) matched by neither: refused by authorization, never verified, with a forged or a valid key
		clearInvocations(apiKeyService);
		for (String path : List.of(PATH + "/", PATH + "x", "/GRAPHQL")) {
			assertEntryPoint401(browser(withAuthorization("GET", path, freshAddress(), forged)), "forged on GET " + path);
			Run r = browser(withAuthorization("GET", path, freshAddress(), valid));
			assertEntryPoint401(r, "valid on GET " + path);
			assertNotNull(r.remaining(), path + ": counted before authorization");
		}
		Run post = browser(csrf(withAuthorization("POST", PATH + "x", freshAddress(), valid)));
		assertEntryPoint401(post, "valid on POST " + PATH + "x with the handshake");
		assertNotNull(post.remaining());
		verify(apiKeyService, never()).isMatchingApiKey(any());
	}

	// ---- case 8: the limiter runs before the Argon2 on this chain

	@Test
	public void theLimiterCountsEveryBasicBeforeItIsVerified() throws Exception {
		Key k = freeformKey();
		clearInvocations(apiKeyService);
		for (int i = 0; i < 100; i++) {
			Run r = browser(withAuthorization("GET", PATH, freshAddress(), basic(k.id(), k.secret() + i)));
			assertInvalidToken(r, String.valueOf(99 - i), "wrong secret " + (i + 1));
		}
		assertLimited(browser(withAuthorization("GET", PATH, freshAddress(), basic(k.id(), k.secret() + "x"))), "the 101st");
		assertLimited(browser(withAuthorization("GET", PATH, freshAddress(), basic(k.id(), k.secret()))), "the holder shares the row's bucket");
		verify(apiKeyService, times(100)).isMatchingApiKey(any());

		String unknown = "FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID();
		clearInvocations(apiKeyService);
		for (int i = 0; i < 100; i++) {
			Run r = browser(csrf(withAuthorization("POST", PATH, freshAddress(), basic(unknown, "any"))));
			assertInvalidToken(r, String.valueOf(99 - i), "unknown id " + (i + 1));
		}
		assertLimited(browser(csrf(withAuthorization("POST", PATH, freshAddress(), basic(unknown, "any")))), "the 101st unknown id");
		verify(apiKeyService, times(100)).isMatchingApiKey(any());
	}

	// ---- case 9: a forged ORGANIZATION_RW id never reaches an org or component-by-name lookup through /graphql

	@Test
	public void aForgedOrganizationKeyIsRefusedAtTheChainAndAVerifiedOneIsAccepted() throws Exception {
		Key component = componentKey();
		Key orgRw = objectKey(component.org(), ApiTypeEnum.ORGANIZATION_RW, component.org());
		clearInvocations(apiKeyService);
		assertRefused(browser(csrf(withAuthorization("POST", PATH, freshAddress(), basic(orgRw.id(), "any")))), "forged ORGANIZATION_RW");
		verify(apiKeyService, times(1)).isMatchingApiKey(any());
		verify(apiKeyService, never()).getApiKey(any());

		MockHttpServletRequest req = csrf(withAuthorization("POST", PATH, freshAddress(), basic(orgRw.id(), orgRw.secret())));
		Run r = browser(req);
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
	}

	// ---- case 10: the alias multiplier is bounded to one secret check per request

	private static final int ALIASES = 25;

	private static String aliased() {
		StringBuilder sb = new StringBuilder("mutation($c: ID) {");
		for (int i = 1; i <= ALIASES; i++) {
			sb.append(" a").append(i).append(": getNewVersionProgrammatic(newVersionInput: { component: $c, branch: \"main\", onlyVersion: true }) { version }");
		}
		return sb.append(" }").toString();
	}

	@Test
	public void oneRequestOfManyAliasesChecksTheSecretOnce() throws Exception {
		Key component = componentKey();
		Key orgRw = objectKey(component.org(), ApiTypeEnum.ORGANIZATION_RW, component.org());

		MockHttpServletRequest req = csrf(withAuthorization("POST", PATH, freshAddress(), basic(orgRw.id(), orgRw.secret())));
		clearInvocations(apiKeyService);
		Run r = browser(req);
		assertTrue(r.reached(), "status " + r.response().getStatus());
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		ExecutionResult er = dgsQueryExecutor.execute(aliased(), Map.of("c", component.object().toString()), Map.of(), headersOf(req), null,
				new ServletWebRequest(req));
		assertTrue(er.getErrors().isEmpty(), String.valueOf(er.getErrors()));
		Map<String, Object> data = er.getData();
		for (int i = 1; i <= ALIASES; i++) {
			@SuppressWarnings("unchecked")
			String version = (String) ((Map<String, Object>) data.get("a" + i)).get("version");
			assertNotNull(version, "a" + i);
			assertFalse(version.isEmpty(), "a" + i);
		}
		ArgumentCaptor<AuthHeaderParse> seen = ArgumentCaptor.forClass(AuthHeaderParse.class);
		verify(apiKeyService, atLeast(1 + ALIASES)).isMatchingApiKey(seen.capture());
		List<AuthHeaderParse> calls = seen.getAllValues();
		assertEquals(orgRw.secret(), calls.get(0).getApiKey(), "the filter's call carries the presented secret");
		assertNull(calls.get(0).getVerifiedKeyUuid(), "the filter's call is the verification");
		for (int i = 1; i < calls.size(); i++) {
			assertEquals(orgRw.uuid(), calls.get(i).getVerifiedKeyUuid(), "call " + i + " carries the verified key");
			assertNull(calls.get(i).getApiKey(), "call " + i + " carries no secret: no cache lookup, no Argon2");
		}

		clearInvocations(apiKeyService);
		assertRefused(browser(csrf(withAuthorization("POST", PATH, freshAddress(), basic(orgRw.id(), orgRw.secret() + "x")))),
				"the same request with a wrong secret");
		verify(apiKeyService, times(1)).isMatchingApiKey(any());
	}

	// ---- case 11: the count is unchanged (TPF-28)

	@Test
	public void theCountIsTheRowsCanonicalBucket() throws Exception {
		Key k = freeformKey();
		Run first = browser(withAuthorization("GET", PATH, freshAddress(), basic(k.id(), k.secret() + "x")));
		assertRefused(first, "first wrong secret");
		assertEquals("99", first.remaining());
		Run second = browser(withAuthorization("GET", PATH, freshAddress(), basic(k.id(), k.secret() + "y")));
		assertRefused(second, "second wrong secret, another address");
		assertEquals("98", second.remaining(), "the row's bucket, whatever the address");
		String address = freshAddress();
		Run ok = browser(withAuthorization("GET", PATH, address, basic(k.id(), k.secret())));
		assertAdmittedBasic(ok, k, address, "the holder");
		assertEquals("97", ok.remaining(), "an admitted Basic carries the count too");
	}

	// ---- case 12: no access row; the successful match touches the secret

	@Test
	public void theFilterWritesNoAccessRowAndTouchesTheSecret() throws Exception {
		Key k = freeformKey();
		clearInvocations(apiKeyAccessService);
		int before = keyAccessCount(k.uuid());
		String address = freshAddress();
		assertAdmittedBasic(browser(withAuthorization("GET", PATH, address, basic(k.id(), k.secret()))), k, address, "valid FREEFORM");
		assertEquals(before, keyAccessCount(k.uuid()), "no access row from the filter");
		verify(apiKeyAccessService, never()).recordApiKeyAccess(any(), any(), any(), any());
		ApiKey row = apiKeyService.getApiKey(k.uuid()).orElseThrow();
		ApiKeySecret secret = ApiKeyData.dataFromRecord(row).effectiveSecrets(row.getApiKey()).get(0);
		assertNotNull(secret.getLastUsedDate(), "the successful match touched the secret");
	}
}
