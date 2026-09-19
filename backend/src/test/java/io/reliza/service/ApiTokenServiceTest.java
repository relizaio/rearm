package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.SystemInfoData.EncProps;
import io.reliza.service.ApiTokenService.TokenClaims;

public class ApiTokenServiceTest {

	private static final String TEST_ISSUER = "https://rearm.test";
	private static final String TEST_PEPPER = "pepper-for-tests";

	/** Every service in these tests derives with an issuer and a pepper, as production does. */
	private static ApiTokenService svc(EncProps enc) {
		return new ApiTokenService(enc, TEST_ISSUER, () -> TEST_PEPPER);
	}

	private static ApiKey key(String secretHash) {
		ApiKey ak = new ApiKey();
		ak.setOrg(UUID.randomUUID());
		ak.setObjectType(io.reliza.model.ApiKey.ApiTypeEnum.FREEFORM);
		ak.setObjectUuid(ak.getOrg());
		ak.setKeyOrder(UUID.randomUUID().toString());
		ak.setApiKey(secretHash);
		return ak;
	}

	private static ApiKeySecret secret(int slot, String hash) {
		return new ApiKeySecret(slot, hash, true, null, null);
	}

	@Test
	void tokenNeverOutlivesItsSecret() {
		ApiTokenService svc = svc(new EncProps("pw", "salt", null, null));
		ApiKey ak = key("argon2-hash-1");
		ApiKeySecret sec = secret(1, "argon2-hash-1");
		sec.setExpiresDate(java.time.ZonedDateTime.now().plusMinutes(10));
		String token = svc.issue(ak, sec, ApiTokenService.AUD_PROGRAMMATIC);
		TokenClaims c = svc.verify(token, ApiTokenService.AUD_PROGRAMMATIC).orElseThrow();
		assertTrue(c.expiresAt().isBefore(java.time.Instant.now().plusSeconds(11 * 60)), "exp is clipped to the secret expiry, not the one-hour TTL");
	}

	@Test
	void issueAndVerifyRoundTrip() {
		ApiTokenService svc = svc(new EncProps("pw", "salt", null, null));
		ApiKey ak = key("argon2-hash-1");
		ApiKeySecret sec = secret(2, "argon2-hash-2");
		String token = svc.issue(ak, sec, ApiTokenService.AUD_PROGRAMMATIC);
		TokenClaims c = svc.verify(token, ApiTokenService.AUD_PROGRAMMATIC).orElseThrow();
		assertEquals(ak.getUuid(), c.keyUuid());
		assertEquals(ak.getOrg(), c.org());
		assertEquals(ApiTokenService.fingerprint(sec), c.fingerprint());
		assertEquals(2, c.slot());
		assertTrue(c.expiresAt().isAfter(java.time.Instant.now().plusSeconds(ApiTokenService.TTL_SECONDS - 120)));
	}

	@Test
	void audienceIsStrict() {
		ApiTokenService svc = svc(new EncProps("pw", "salt", null, null));
		String token = svc.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_TEA);
		assertTrue(svc.verify(token, ApiTokenService.AUD_PROGRAMMATIC).isEmpty(), "a TEA token must not work on the programmatic surface");
		assertTrue(svc.verify(token, ApiTokenService.AUD_TEA).isPresent());
	}

	@Test
	void differentSecretsDoNotVerifyEachOther() {
		ApiTokenService a = svc(new EncProps("pw-a", "salt", null, null));
		ApiTokenService b = svc(new EncProps("pw-b", "salt", null, null));
		String token = a.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		assertTrue(b.verify(token, ApiTokenService.AUD_PROGRAMMATIC).isEmpty());
		assertFalse(java.util.Arrays.equals(ApiTokenService.hkdfSha256("pw-a", "salt", TEST_PEPPER), ApiTokenService.hkdfSha256("pw-b", "salt", TEST_PEPPER)));
	}

	@Test
	void rotationKeepsPreviousTokensValidViaKid() {
		ApiTokenService before = svc(new EncProps("old-pw", "old-salt", null, null));
		String issuedBeforeRotation = before.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		ApiTokenService after = svc(new EncProps("new-pw", "new-salt", "old-pw", "old-salt"));
		// the old token still verifies under the previous key material during the overlap
		assertTrue(after.verify(issuedBeforeRotation, ApiTokenService.AUD_PROGRAMMATIC).isPresent());
		// new tokens are signed with the current key and are not valid for the old service
		String issuedAfter = after.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		assertTrue(before.verify(issuedAfter, ApiTokenService.AUD_PROGRAMMATIC).isEmpty());
	}

	@Test
	void fingerprintChangesWhenTheSecretIsRegenerated() {
		assertNotEquals(ApiTokenService.fingerprint(secret(1, "hash-v1")), ApiTokenService.fingerprint(secret(1, "hash-v2")));
		assertEquals(16, ApiTokenService.fingerprint(secret(1, "hash-v1")).length());
	}

	/** RFC 5869 appendix A, test case 1: pins the derivation to the standard, not to an implementation. */
	@Test
	void hkdfMatchesRfc5869TestVector() {
		byte[] ikm = new byte[22]; java.util.Arrays.fill(ikm, (byte) 0x0b);
		byte[] salt = hex("000102030405060708090a0b0c");
		byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
		byte[] okm = ApiTokenService.hkdfSha256(ikm, salt, info, 42);
		assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", toHex(okm));
	}

	private static byte[] hex(String s) {
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
		return out;
	}

	private static String toHex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (byte x : b) sb.append(String.format("%02x", x));
		return sb.toString();
	}

	@Test
	void garbageIsRejected() {
		ApiTokenService svc = svc(new EncProps("pw", "salt", null, null));
		assertEquals(Optional.empty(), svc.verify("not.a.token", ApiTokenService.AUD_PROGRAMMATIC));
		assertEquals(Optional.empty(), svc.verify("", ApiTokenService.AUD_PROGRAMMATIC));
	}
	@Test
	void sessionTokenCarriesSessionAndActorAndDiesWithTheSession() {
		ApiTokenService svc = svc(new EncProps("pw", "salt", null, null));
		ApiKey ak = key("argon2-hash-1");
		io.reliza.model.CliSession s = new io.reliza.model.CliSession();
		s.setRefreshTokenHash("rt-hash");
		s.setUser(UUID.randomUUID());
		s.setApprovedDate(java.time.ZonedDateTime.now());
		s.setExpiresDate(java.time.ZonedDateTime.now().plusDays(30));
		String token = svc.issueForSession(ak, s, ApiTokenService.AUD_PROGRAMMATIC);
		TokenClaims c = svc.verify(token, ApiTokenService.AUD_PROGRAMMATIC).orElseThrow();
		assertEquals(ak.getUuid(), c.keyUuid());
		assertEquals(s.getUuid(), c.sessionUuid());
		assertEquals(s.getUser(), c.actorUser());
		assertEquals(ApiTokenService.fingerprint(s), c.fingerprint());
		s.setRefreshTokenHash("rotated");
		assertTrue(!ApiTokenService.fingerprint(s).equals(c.fingerprint()), "a rotated or revoked session no longer matches the token");
	}


	@Test
	void federatedTokenCarriesRulesAndIsBoundToTheirVersions() {
		ApiTokenService svc = svc(new EncProps("pw", "salt", null, null));
		ApiKey ak = key(null);
		io.reliza.model.FederatedTrustRule r1 = new io.reliza.model.FederatedTrustRule();
		r1.setVersion(3);
		io.reliza.model.FederatedTrustRule r2 = new io.reliza.model.FederatedTrustRule();
		r2.setVersion(1);
		java.util.Map<String, String> fed = new java.util.LinkedHashMap<>();
		fed.put("provider", "GITHUB_ACTIONS");
		fed.put("repository", "relizaio/rearm");
		fed.put("repositoryUri", "github.com/relizaio/rearm");
		String token = svc.issueForFederation(ak, java.util.List.of(r1, r2), fed, ApiTokenService.AUD_PROGRAMMATIC, null);
		TokenClaims c = svc.verify(token, ApiTokenService.AUD_PROGRAMMATIC).orElseThrow();
		assertEquals(ak.getUuid(), c.keyUuid());
		assertEquals(java.util.List.of(r1.getUuid(), r2.getUuid()), c.ruleUuids());
		assertEquals("relizaio/rearm", c.federation().get("repository"));
		assertEquals(ApiTokenService.fingerprint(java.util.List.of(r2, r1)), c.fingerprint(), "order of rules does not matter");
		r1.setVersion(4);
		assertTrue(!ApiTokenService.fingerprint(java.util.List.of(r1, r2)).equals(c.fingerprint()), "an edited rule changes the fingerprint");
		assertEquals(null, c.sessionUuid());
	}

	@Test
	void federatedTokenIsClippedToAnExpiringRule() {
		ApiTokenService svc = svc(new EncProps("pw", "salt", null, null));
		ApiKey ak = key(null);
		io.reliza.model.FederatedTrustRule r = new io.reliza.model.FederatedTrustRule();
		java.time.Instant clip = java.time.Instant.now().plusSeconds(600);
		String token = svc.issueForFederation(ak, java.util.List.of(r), java.util.Map.of(), ApiTokenService.AUD_PROGRAMMATIC, clip);
		TokenClaims c = svc.verify(token, ApiTokenService.AUD_PROGRAMMATIC).orElseThrow();
		assertTrue(c.expiresAt().isBefore(java.time.Instant.now().plusSeconds(11 * 60)));
	}

	// ---- installation identity ---------------------------------------------------------------

	@Test
	void twoInstallationsOnTheShippedDefaultsDoNotAcceptEachOthersTokens() {
		// The shipped password and salt are public, so both of these derive from identical
		// configuration -- which is exactly the case the pepper exists for. Each installation
		// generates its own on first use, so the signing keys differ and neither token verifies
		// on the other side.
		EncProps shipped = new EncProps("AT8P34WD8P1sHmB9Pz9pPD9pID8P2VVPz8cMj8NCg4", "b1e6ff16ef4bd0eb", null, null);
		ApiTokenService a = new ApiTokenService(shipped, TEST_ISSUER, () -> "pepper-of-installation-a");
		ApiTokenService b = new ApiTokenService(shipped, TEST_ISSUER, () -> "pepper-of-installation-b");

		String fromA = a.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		assertTrue(a.verify(fromA, ApiTokenService.AUD_PROGRAMMATIC).isPresent());
		assertTrue(b.verify(fromA, ApiTokenService.AUD_PROGRAMMATIC).isEmpty(),
				"a token signed on one installation must not verify on another that shares its configuration");
	}

	@Test
	void aClonedDatabaseOnAnotherHostDoesNotAcceptTheOriginalsTokens() {
		// The pepper travels with a database copy, so it cannot separate a clone from its original.
		// The issuer can: staging and production do not share a base URI.
		ApiTokenService production = new ApiTokenService(new EncProps("pw", "salt", null, null),
				"https://rearm.example.com", () -> "same-pepper-because-the-database-was-copied");
		ApiTokenService staging = new ApiTokenService(new EncProps("pw", "salt", null, null),
				"https://staging.rearm.example.com", () -> "same-pepper-because-the-database-was-copied");

		String fromProduction = production.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		assertTrue(staging.verify(fromProduction, ApiTokenService.AUD_PROGRAMMATIC).isEmpty(),
				"the issuer is what tells two clones apart");
		assertTrue(production.verify(fromProduction, ApiTokenService.AUD_PROGRAMMATIC).isPresent());
	}

	@Test
	void theIssuerIsTheBaseUriAndFallsBackWhenThereIsNone() {
		assertEquals("https://rearm.example.com", ApiTokenService.issuerFor("https://rearm.example.com"));
		assertEquals(ApiTokenService.ISSUER, ApiTokenService.issuerFor(""));
		assertEquals(ApiTokenService.ISSUER, ApiTokenService.issuerFor(null));
	}

	@Test
	void changingThePepperChangesTheKeyId() {
		// The kid is a digest of the derived key, so a new pepper means tokens signed under the old
		// one name a key this service no longer holds -- they are rejected rather than mis-verified.
		EncProps enc = new EncProps("pw", "salt", null, null);
		ApiTokenService before = new ApiTokenService(enc, TEST_ISSUER, () -> "pepper-one");
		ApiTokenService after = new ApiTokenService(enc, TEST_ISSUER, () -> "pepper-two");
		String old = before.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		assertTrue(after.verify(old, ApiTokenService.AUD_PROGRAMMATIC).isEmpty());
	}

	@Test
	void theKeyIsNotDerivedUntilItIsNeeded() {
		// The pepper lives in the database, so it cannot be read while the service is being
		// constructed. Constructing must therefore touch nothing.
		java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
		ApiTokenService svc = new ApiTokenService(new EncProps("pw", "salt", null, null), TEST_ISSUER,
				() -> { reads.incrementAndGet(); return TEST_PEPPER; });
		assertEquals(0, reads.get(), "constructing the service must not read the pepper");

		svc.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		svc.issue(key("h"), secret(1, "h"), ApiTokenService.AUD_PROGRAMMATIC);
		assertEquals(1, reads.get(), "the pepper is read once and the keys cached");
	}
}
