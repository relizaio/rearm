/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.common.OAuthErrors;
import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.repositories.FederatedAssertionIdRepository;
import io.reliza.repositories.FederatedTrustRuleRepository;
import io.reliza.service.FederatedAssertionService.AssertionException;
import io.reliza.service.FederatedAssertionService.Refusal;
import io.reliza.service.FederatedAssertionService.VerifiedAssertion;
import io.reliza.service.FederatedTrustRuleService.ExchangeException;

/**
 * The exchange maps every refusal before a rule admits the verified identity to one wire text, and carries the
 * fixed-vocabulary reason, the presented issuer and the verified repository to the token endpoint's log line
 * (task TPF-31, architecture-2 4.1). Plain Mockito: no Spring, no network.
 */
@ExtendWith(MockitoExtension.class)
class FederatedTrustRuleExchangeTest {

	@Mock private FederatedTrustRuleRepository repository;
	@Mock private FederatedAssertionService assertionService;
	@Mock private ApiKeyService apiKeyService;
	@Mock private FederatedAssertionIdRepository assertionIds;
	@InjectMocks private FederatedTrustRuleService service;

	private static final String ASSERTION = "header.claims.signature";
	private static final String ISSUER = "https://ghes.example.test/_services/token";
	private static final String OWNER = "tpf31";

	private static FederatedTrustRule rule(UUID org) {
		FederatedTrustRule r = new FederatedTrustRule();
		r.setOrg(org);
		r.setName("tpf31-rule");
		r.setProvider(Provider.GITHUB_ACTIONS);
		r.setIssuer(ISSUER);
		FederatedMatcher m = new FederatedMatcher();
		m.setOwner(OWNER);
		r.setMatcherOf(m);
		return r;
	}

	private void oneRule() throws Exception {
		when(assertionService.peekIssuer(ASSERTION)).thenReturn(ISSUER);
		when(repository.findActiveByIssuer(ISSUER, FederatedTrustRule.Status.ACTIVE.name())).thenReturn(new ArrayList<>(List.of(rule(UUID.randomUUID()))));
	}

	private static VerifiedAssertion verified(String owner, String repository) {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("sub", "repo:" + repository + ":ref:refs/heads/main");
		claims.put("repository", repository);
		claims.put("repository_owner", owner);
		claims.put("repository_id", "3101");
		claims.put("repository_owner_id", "3100");
		String jti = UUID.randomUUID().toString();
		return new VerifiedAssertion(FederatedMatching.fromGitHub(ISSUER, claims), claims, Instant.now().plusSeconds(300), jti);
	}

	private ExchangeException refused(String clientId) {
		return assertThrows(ExchangeException.class, () -> service.exchange(ASSERTION, clientId));
	}

	private ExchangeException verifyThrows(Refusal reason, String message) throws Exception {
		oneRule();
		doThrow(new AssertionException(reason, message)).when(assertionService).verify(eq(ASSERTION), eq(ISSUER), any());
		ExchangeException e = refused(null);
		assertEquals(OAuthErrors.INVALID_GRANT, e.code());
		assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, e.getMessage());
		assertEquals(reason, e.reason());
		assertEquals(ISSUER, e.issuer());
		assertNull(e.identity());
		return e;
	}

	// row 1
	@Test
	void malformedKeepsItsTextAndIsRefusedBeforeTheLookup() throws Exception {
		when(assertionService.peekIssuer(ASSERTION)).thenThrow(new AssertionException(Refusal.MALFORMED, "malformed assertion"));
		ExchangeException e = refused(null);
		assertEquals(OAuthErrors.INVALID_GRANT, e.code());
		assertEquals("malformed assertion", e.getMessage());
		assertEquals(Refusal.MALFORMED, e.reason());
		assertNull(e.issuer());
		verifyNoInteractions(repository);
	}

	// row 2
	@Test
	void unknownIssuerReadsTheOneTextAndNothingIsVerified() throws Exception {
		when(assertionService.peekIssuer(ASSERTION)).thenReturn(ISSUER);
		when(repository.findActiveByIssuer(ISSUER, FederatedTrustRule.Status.ACTIVE.name())).thenReturn(List.of());
		ExchangeException e = refused(null);
		assertEquals(OAuthErrors.INVALID_GRANT, e.code());
		assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, e.getMessage());
		assertEquals(Refusal.UNTRUSTED_ISSUER, e.reason());
		assertEquals(ISSUER, e.issuer());
		assertNull(e.identity());
		verify(assertionService, never()).verify(anyString(), anyString(), any());
	}

	// row 3
	@Test
	void keysUnavailable() throws Exception {
		verifyThrows(Refusal.KEYS_UNAVAILABLE, "issuer keys unavailable");
	}

	// row 4
	@Test
	void notVerified() throws Exception {
		verifyThrows(Refusal.NOT_VERIFIED, "assertion rejected: signature, key or format not accepted");
	}

	// row 5
	@Test
	void audience() throws Exception {
		verifyThrows(Refusal.AUDIENCE, "audience must be https://rearm.example.com");
	}

	// row 5, second form
	@Test
	void claimsInvalid() throws Exception {
		verifyThrows(Refusal.CLAIMS_INVALID, "assertion rejected: claims not valid (expiry, issuer or audience)");
	}

	// row 6
	@Test
	void identityUnmatched() throws Exception {
		oneRule();
		when(assertionService.verify(eq(ASSERTION), eq(ISSUER), any())).thenReturn(verified("other", "other/repo"));
		ExchangeException e = refused(null);
		assertEquals(OAuthErrors.INVALID_GRANT, e.code());
		assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, e.getMessage());
		assertEquals(Refusal.IDENTITY_UNMATCHED, e.reason());
		assertEquals(ISSUER, e.issuer());
		assertEquals("other/repo", e.identity());
	}

	// row 7
	@Test
	void organizationNotNamed() throws Exception {
		oneRule();
		when(assertionService.verify(eq(ASSERTION), eq(ISSUER), any())).thenReturn(verified(OWNER, OWNER + "/app"));
		ExchangeException e = refused(UUID.randomUUID().toString());
		assertEquals(OAuthErrors.INVALID_GRANT, e.code());
		assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, e.getMessage());
		assertEquals(Refusal.ORG_NOT_NAMED, e.reason());
		assertEquals(OWNER + "/app", e.identity());
		verifyNoInteractions(assertionIds);
	}

	// row 8
	@Test
	void theRefusalsBeforeAdmissionAreOneStringCarryingNoneOfTheRetiredTexts() throws Exception {
		String text = FederatedTrustRuleService.NOT_ACCEPTED;
		assertFalse(text.contains("issuer keys"), text);
		assertFalse(text.contains("no trust rule matches"), text);
		assertFalse(text.contains("audience must be"), text);
		assertEquals("assertion not accepted: no active trust rule names its issuer, or the assertion did not verify against that issuer "
				+ "(signature, keys or claims); ask the organization administrator to check the trust rule and the server log", text);
	}

	@Test
	void rowsTwoToSevenShareTheTextAndDifferInTheReasonOnly() throws Exception {
		List<ExchangeException> all = new ArrayList<>();
		all.add(verifyThrows(Refusal.KEYS_UNAVAILABLE, "issuer keys unavailable"));
		all.add(verifyThrows(Refusal.NOT_VERIFIED, "x"));
		all.add(verifyThrows(Refusal.AUDIENCE, "audience must be https://rearm.example.com"));
		all.add(verifyThrows(Refusal.CLAIMS_INVALID, "y"));
		for (ExchangeException e : all) {
			assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, e.getMessage());
			assertEquals(OAuthErrors.INVALID_GRANT, e.code());
		}
		assertEquals(List.of(Refusal.KEYS_UNAVAILABLE, Refusal.NOT_VERIFIED, Refusal.AUDIENCE, Refusal.CLAIMS_INVALID),
				all.stream().map(ExchangeException::reason).toList());
	}

	// the refusals after admission keep their texts and codes (3.2, case 9)
	@Test
	void severalOrganizationsKeepTheirText() throws Exception {
		when(assertionService.peekIssuer(ASSERTION)).thenReturn(ISSUER);
		when(repository.findActiveByIssuer(ISSUER, FederatedTrustRule.Status.ACTIVE.name()))
				.thenReturn(new ArrayList<>(List.of(rule(UUID.randomUUID()), rule(UUID.randomUUID()))));
		when(assertionService.verify(eq(ASSERTION), eq(ISSUER), any())).thenReturn(verified(OWNER, OWNER + "/app"));
		ExchangeException e = refused(null);
		assertEquals(OAuthErrors.INVALID_REQUEST, e.code());
		assertEquals("this identity is trusted by several organizations; pass client_id=<organization uuid>", e.getMessage());
		assertEquals(Refusal.SEVERAL_ORGS, e.reason());
		ExchangeException notUuid = assertThrows(ExchangeException.class, () -> service.exchange(ASSERTION, "not-a-uuid"));
		assertEquals(OAuthErrors.INVALID_CLIENT, notUuid.code());
		assertEquals("client_id must be the organization uuid", notUuid.getMessage());
		assertEquals(Refusal.CLIENT_ID_NOT_UUID, notUuid.reason());
	}

	private static ApiKey identity(ApiKeyStatus status, String pinnedRepositoryId) {
		UUID org = UUID.randomUUID();
		ApiKey ak = new ApiKey();
		ak.setObjectType(ApiTypeEnum.FEDERATED);
		ak.setOrg(org);
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(org);
		akd.setStatus(status);
		FederatedIdentity fi = new FederatedIdentity();
		fi.setRepositoryId(pinnedRepositoryId);
		akd.setFederation(fi);
		ak.setRecordData(Utils.dataToRecord(akd));
		return ak;
	}

	/**
	 * The identity is checked again on the row ensureFederatedIdentity locked and re-read: a kill switch, or a pin
	 * to another repository, set while this exchange ran stands (task t20261013-195114-27617).
	 */
	@Test
	void anIdentityDisabledOrPinnedDuringTheExchangeIsRefused() throws Exception {
		oneRule();
		when(assertionService.verify(eq(ASSERTION), eq(ISSUER), any())).thenReturn(verified(OWNER, OWNER + "/app"));
		when(apiKeyService.getFederatedIdentity(any(), any())).thenReturn(Optional.empty());
		when(assertionIds.reserve(anyString(), anyString(), any())).thenReturn(1);
		when(apiKeyService.ensureFederatedIdentity(any(), any(), any()))
				.thenReturn(identity(ApiKeyStatus.INACTIVE, null), identity(ApiKeyStatus.ACTIVE, "9999"));

		assertEquals(Refusal.IDENTITY_DISABLED, refused(null).reason());
		assertEquals(Refusal.REPOSITORY_PIN, refused(null).reason());
	}
}
