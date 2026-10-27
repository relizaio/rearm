/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.CliSession;
import io.reliza.model.FederatedTrustRule;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ApiTokenServiceTestSupport;
import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.service.TeaAuthorizationService.TeaCaller;

/**
 * Task TEA-3, design 4.5: the TEA authentication filter classifies the caller and never answers. In every case the
 * chain receives the request and the status is untouched.
 */
class TeaAuthenticationFilterTest {

	private final ApiTokenService tokens = ApiTokenServiceTestSupport.create();
	private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
	private final TeaAuthenticationFilter filter = new TeaAuthenticationFilter(tokens, apiKeyService);
	private final UUID org = UUID.randomUUID();
	private final UUID teaOrg = UUID.randomUUID();
	private final String path = "/tea/" + teaOrg + "/v1.0.0/product/" + UUID.randomUUID();

	private ApiKey row(ApiTypeEnum type, ApiKeyStatus status, ApiKeySecret... secrets) {
		ApiKey ak = new ApiKey();
		ak.setObjectType(type);
		ak.setObjectUuid(org);
		ak.setOrg(org);
		ak.setKeyOrder(UUID.randomUUID().toString());
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(org);
		akd.setTeaOrg(teaOrg);
		akd.setStatus(status);
		akd.setSecrets(new LinkedList<>(List.of(secrets)));
		ak.setRecordData(Utils.dataToRecord(akd));
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
		return ak;
	}

	private static ApiKeySecret secret(int slot, String hash) {
		return new ApiKeySecret(slot, hash, true, ZonedDateTime.now(), null);
	}

	private TeaCaller run(String uri, String authorization) throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
		req.setRequestURI(uri);
		req.setRemoteAddr("198.51.100.4");
		if (authorization != null) req.addHeader("Authorization", authorization);
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(req, resp, chain);
		assertNotNull(chain.getRequest(), "the chain always receives the request");
		assertEquals(200, resp.getStatus(), "the filter never answers");
		return (TeaCaller) req.getAttribute(TeaAuthenticationFilter.CALLER_ATTRIBUTE);
	}

	private void assertInvalid(TeaCaller c, String reason) {
		assertEquals(TeaAuthState.INVALID_TOKEN, c.state());
		assertEquals(reason, c.reason());
		assertNull(c.principal());
	}

	// 23
	@Test
	void anonymousBasicAndAValidToken() throws Exception {
		assertEquals(TeaAuthState.ANONYMOUS, run(path, null).state());
		assertEquals(TeaAuthState.ANONYMOUS, run(path, "Basic aWQ6c2VjcmV0").state());
		ApiKeySecret sec = secret(2, "hash-2");
		ApiKey ak = row(ApiTypeEnum.EXTERNAL, ApiKeyStatus.ACTIVE, secret(1, "hash-1"), sec);
		TeaCaller c = run(path, "Bearer " + tokens.issue(ak, sec, ApiTokenService.AUD_TEA));
		assertEquals(TeaAuthState.AUTHENTICATED, c.state());
		assertEquals(ak.getUuid(), c.principal().keyUuid());
		assertEquals(org, c.principal().org());
		assertEquals("EXTERNAL__" + teaOrg + "__ord__" + ak.getKeyOrder(), c.principal().keyId());
		assertEquals(2, c.principal().slot());
		assertEquals("198.51.100.4", c.principal().remoteIp());
	}

	// 24
	@Test
	void aProgrammaticAnExpiredOrATamperedTokenIsInvalid() throws Exception {
		ApiKeySecret sec = secret(1, "hash-1");
		ApiKey ak = row(ApiTypeEnum.EXTERNAL, ApiKeyStatus.ACTIVE, sec);
		assertInvalid(run(path, "Bearer " + tokens.issue(ak, sec, ApiTokenService.AUD_PROGRAMMATIC)), "invalid or expired");
		ApiKeySecret expiring = secret(1, "hash-1");
		expiring.setExpiresDate(ZonedDateTime.now().minusMinutes(5)); // the token's exp is clipped to it, past the skew
		assertInvalid(run(path, "Bearer " + tokens.issue(ak, expiring, ApiTokenService.AUD_TEA)), "invalid or expired");
		String good = tokens.issue(ak, sec, ApiTokenService.AUD_TEA);
		String tampered = good.substring(0, good.length() - 3) + (good.endsWith("AAA") ? "BBB" : "AAA");
		assertInvalid(run(path, "Bearer " + tampered), "invalid or expired");
		assertInvalid(run(path, "Bearer not-a-jwt"), "invalid or expired");
	}

	// 25
	@Test
	void theRowAndTheSecretMustStillHold() throws Exception {
		ApiKeySecret sec = secret(1, "hash-1");
		ApiKey gone = new ApiKey();
		gone.setOrg(org);
		gone.setObjectUuid(org);
		gone.setObjectType(ApiTypeEnum.EXTERNAL);
		when(apiKeyService.getApiKey(gone.getUuid())).thenReturn(Optional.empty());
		assertInvalid(run(path, "Bearer " + tokens.issue(gone, sec, ApiTokenService.AUD_TEA)), "key not found");

		ApiKey freeform = row(ApiTypeEnum.FREEFORM, ApiKeyStatus.ACTIVE, sec);
		assertInvalid(run(path, "Bearer " + tokens.issue(freeform, sec, ApiTokenService.AUD_TEA)), "not an external key");

		ApiKey inactive = row(ApiTypeEnum.EXTERNAL, ApiKeyStatus.INACTIVE, sec);
		assertInvalid(run(path, "Bearer " + tokens.issue(inactive, sec, ApiTokenService.AUD_TEA)), "key inactive");
		ApiKey revoked = row(ApiTypeEnum.EXTERNAL, ApiKeyStatus.REVOKED, sec);
		assertInvalid(run(path, "Bearer " + tokens.issue(revoked, sec, ApiTokenService.AUD_TEA)), "key inactive");

		ApiKey ak = row(ApiTypeEnum.EXTERNAL, ApiKeyStatus.ACTIVE, sec);
		CliSession s = new CliSession();
		s.setRefreshTokenHash("r");
		s.setUser(UUID.randomUUID());
		s.setExpiresDate(ZonedDateTime.now().plusHours(1));
		assertInvalid(run(path, "Bearer " + tokens.issueForSession(ak, s, ApiTokenService.AUD_TEA)), "not a key token");
		FederatedTrustRule rule = new FederatedTrustRule();
		assertInvalid(run(path, "Bearer " + tokens.issueForFederation(ak, List.of(rule), Map.of("repository", "o/r"), ApiTokenService.AUD_TEA, null)),
				"not a key token");

		String before = tokens.issue(ak, sec, ApiTokenService.AUD_TEA);
		ApiKey regenerated = row(ApiTypeEnum.EXTERNAL, ApiKeyStatus.ACTIVE, secret(1, "hash-regenerated"));
		regenerated.setUuid(ak.getUuid());
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(regenerated));
		assertInvalid(run(path, "Bearer " + before), "secret retired, expired or regenerated");
	}

	// TEA-6 round 4 (T-5): the chain serves an encoded or path-parameter spelling of a TEA API path as that path, so
	// the filter classifies its bearer as on the plain spelling
	@Test
	void everySpellingOfATeaApiPathIsClassified() throws Exception {
		ApiKeySecret sec = secret(1, "hash-1");
		ApiKey ak = row(ApiTypeEnum.EXTERNAL, ApiKeyStatus.ACTIVE, sec);
		String bearer = "Bearer " + tokens.issue(ak, sec, ApiTokenService.AUD_TEA);
		for (String uri : new String[] { "/%74ea/" + teaOrg + "/v1.0.0/product/x", "/tea;x=1/" + teaOrg + "/v1.0.0/product/x",
				"/t%65a/" + teaOrg + "/v1.0.0/products", "/tea" }) {
			TeaCaller c = run(uri, bearer);
			assertNotNull(c, uri);
			assertEquals(TeaAuthState.AUTHENTICATED, c.state(), uri);
		}
	}

	@Test
	void noSpellingOfTheTokenPathTheDiscoveryDocumentOrAnotherPathIsFiltered() throws Exception {
		for (String uri : new String[] { "/tea/" + teaOrg + "/v1.0.0/t%6Fken", "/tea/" + teaOrg + "/v1.0.0/token;x=1",
				"/.well-known/tea", "/.well-known/%74ea", "/%74eapot", "/TEA/" + teaOrg + "/v1.0.0/products", "/tea/%zz" }) {
			assertNull(run(uri, "Bearer x"), uri);
		}
	}

	// 26
	@Test
	void theTokenPathAndOtherPathsAreNotFiltered() throws Exception {
		assertNull(run("/tea/" + teaOrg + "/v1.0.0/token", "Bearer x"));
		assertNull(run("/api/healthCheck", "Bearer x"));
		when(apiKeyService.getApiKey(any())).thenReturn(Optional.empty());
	}
}
