/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Guards the resource-server {@code azp} (authorized-party) allowlist that binds
 * JWTs to known first-party Keycloak clients — preventing a token minted for a
 * different client in the same realm from being honoured.
 */
class AppAzpValidatorTest {

	private static Jwt jwtWithAzp(String azp) {
		Jwt.Builder b = Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.subject("user")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300));
		if (azp != null) b.claim("azp", azp);
		return b.build();
	}

	@Test
	void acceptsAllowedAzp() {
		OAuth2TokenValidator<Jwt> v = App.buildAzpValidator(Set.of("login-app"));
		assertFalse(v.validate(jwtWithAzp("login-app")).hasErrors(), "login-app token must pass");
	}

	@Test
	void rejectsTokenFromDifferentClient() {
		OAuth2TokenValidator<Jwt> v = App.buildAzpValidator(Set.of("login-app"));
		assertTrue(v.validate(jwtWithAzp("some-other-client")).hasErrors(),
				"a token minted for a different client must be rejected");
	}

	@Test
	void rejectsMissingAzp() {
		OAuth2TokenValidator<Jwt> v = App.buildAzpValidator(Set.of("login-app"));
		assertTrue(v.validate(jwtWithAzp(null)).hasErrors(), "a token with no azp must be rejected");
	}

	@Test
	void allowlistSupportsMultipleClients() {
		OAuth2TokenValidator<Jwt> v = App.buildAzpValidator(App.parseCsvSet(" login-app , some-service "));
		assertFalse(v.validate(jwtWithAzp("some-service")).hasErrors());
		assertFalse(v.validate(jwtWithAzp("login-app")).hasErrors());
		assertTrue(v.validate(jwtWithAzp("evil")).hasErrors());
	}

	@Test
	void parseCsvTrimsAndDropsBlanks() {
		assertEquals(Set.of("a", "b"), App.parseCsvSet(" a , b , "));
		assertEquals(Set.of(), App.parseCsvSet(""));
	}

	private static Jwt jwtWithAud(java.util.List<String> aud) {
		return Jwt.withTokenValue("token").header("alg", "RS256").subject("user")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
				.audience(aud).build();
	}

	@Test
	void audienceValidatorAcceptsWhenAudienceContainsExpected() {
		OAuth2TokenValidator<Jwt> v = App.buildAudienceValidator("rearm-backend");
		assertFalse(v.validate(jwtWithAud(java.util.List.of("account", "rearm-backend"))).hasErrors());
	}

	@Test
	void audienceValidatorRejectsWhenAudienceMissingExpected() {
		OAuth2TokenValidator<Jwt> v = App.buildAudienceValidator("rearm-backend");
		assertTrue(v.validate(jwtWithAud(java.util.List.of("account"))).hasErrors());
	}
}
