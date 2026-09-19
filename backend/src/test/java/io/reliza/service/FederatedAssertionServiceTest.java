/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.service.FederatedAssertionService.AssertionException;
import io.reliza.service.FederatedAssertionService.VerifiedAssertion;
import io.reliza.ws.RelizaConfigProps;

public class FederatedAssertionServiceTest {

	private static KeyPair issuerKeys;
	private static final String ISSUER = FederatedMatching.GITHUB_ISSUER;
	private static final String BASE = "https://rearm.example.com";

	@BeforeAll
	static void keys() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		issuerKeys = g.generateKeyPair();
	}

	private static FederatedAssertionService service() {
		RelizaConfigProps props = new RelizaConfigProps();
		props.setBaseuri(BASE + "/");
		return new FederatedAssertionService(iss -> NimbusJwtDecoder.withPublicKey((RSAPublicKey) issuerKeys.getPublic()).build(), props);
	}

	private static String token(String aud, Instant exp, String jti, Map<String, Object> extra) throws Exception {
		JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(ISSUER).subject("repo:relizaio/rearm:ref:refs/heads/main")
				.audience(aud).issueTime(new Date()).expirationTime(Date.from(exp)).jwtID(jti)
				.claim("repository", "relizaio/rearm").claim("repository_owner", "relizaio").claim("repository_id", "42")
				.claim("repository_owner_id", "7").claim("ref", "refs/heads/main").claim("sha", "abc").claim("run_id", "1");
		extra.forEach(b::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), b.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) issuerKeys.getPrivate()));
		return jwt.serialize();
	}

	@Test
	void verifiesAudienceIssuerAndReadsGitHubClaims() throws Exception {
		FederatedAssertionService svc = service();
		String t = token(BASE, Instant.now().plusSeconds(300), UUID.randomUUID().toString(), Map.of());
		assertEquals(ISSUER, svc.peekIssuer(t));
		VerifiedAssertion va = svc.verify(t, ISSUER, Provider.GITHUB_ACTIONS);
		assertEquals("relizaio/rearm", va.identity().repository());
		assertEquals("github.com/relizaio/rearm", va.identity().repositoryUri());
		assertEquals("7", va.identity().ownerId());
		assertEquals("42", va.identity().repositoryId());
	}

	@Test
	void audienceMustBeThisInstance() throws Exception {
		FederatedAssertionService svc = service();
		String t = token("https://other.example.com", Instant.now().plusSeconds(300), UUID.randomUUID().toString(), Map.of());
		AssertionException e = assertThrows(AssertionException.class, () -> svc.verify(t, ISSUER, Provider.GITHUB_ACTIONS));
		assertTrue(e.getMessage().contains("audience"));
	}

	@Test
	void expiredAssertionIsRefusedAndVerificationItselfIsStateless() throws Exception {
		FederatedAssertionService svc = service();
		String expired = token(BASE, Instant.now().minusSeconds(600), UUID.randomUUID().toString(), Map.of());
		assertThrows(AssertionException.class, () -> svc.verify(expired, ISSUER, Provider.GITHUB_ACTIONS));
		String jti = UUID.randomUUID().toString();
		String t = token(BASE, Instant.now().plusSeconds(300), jti, Map.of());
		assertEquals(jti, svc.verify(t, ISSUER, Provider.GITHUB_ACTIONS).jti());
		// single use is enforced by the exchange against the database, not here: verifying twice is fine
		assertEquals(jti, svc.verify(t, ISSUER, Provider.GITHUB_ACTIONS).jti());
	}

	@Test
	void assertionWithoutRepositoryIdsIsRefused() throws Exception {
		FederatedAssertionService svc = service();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("repo:relizaio/rearm:ref:refs/heads/main").audience(BASE)
				.issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300))).jwtID(UUID.randomUUID().toString())
				.claim("repository", "relizaio/rearm").claim("repository_owner", "relizaio").build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) issuerKeys.getPrivate()));
		AssertionException e = assertThrows(AssertionException.class, () -> svc.verify(jwt.serialize(), ISSUER, Provider.GITHUB_ACTIONS));
		assertTrue(e.getMessage().contains("ids"));
	}

	@Test
	void customIssuersMustBePublicHttpsHosts() {
		assertEquals(null, FederatedAssertionService.issuerProblem("https://ghe.example.com/_services/token"));
		assertEquals(null, FederatedAssertionService.issuerProblem(FederatedMatching.GITHUB_ISSUER));
		assertTrue(FederatedAssertionService.issuerProblem("http://ghe.example.com/_services/token").contains("https"));
		assertTrue(FederatedAssertionService.issuerProblem("https://localhost/x") != null);
		assertTrue(FederatedAssertionService.issuerProblem("https://10.0.0.5/x") != null);
		assertTrue(FederatedAssertionService.issuerProblem("https://backend/x") != null);
		assertTrue(FederatedAssertionService.issuerProblem("https://[::1]/x") != null);
		assertTrue(FederatedAssertionService.issuerProblem("https://user:pw@ghe.example.com/x") != null);
	}

	@Test
	void wrongSignatureIsRefused() throws Exception {
		FederatedAssertionService svc = service();
		String t = token(BASE, Instant.now().plusSeconds(300), UUID.randomUUID().toString(), Map.of());
		String tampered = t.substring(0, t.lastIndexOf('.') + 1) + "AAAA";
		assertThrows(AssertionException.class, () -> svc.verify(tampered, ISSUER, Provider.GITHUB_ACTIONS));
	}
}
