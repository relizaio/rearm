/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.SbomSigningKey;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyAlgorithm;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeySource;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.SbomSigningKeyRepository;
import io.reliza.service.SbomSigningTestKeys.LogCapture;
import io.reliza.service.SbomSigningTestKeys.Rfc8032Vector;
import jakarta.persistence.EntityManager;

/**
 * The SBOM signing key lifecycle (SCORE-32, T-1) over an in-memory repository, a mocked audit and a
 * real {@link EncryptionService}: generate, rotate, import, revoke, the race report, and that no
 * private material reaches a log line, a JWKS entry or the data's string form.
 */
class SbomSigningKeyServiceTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private final Map<UUID, SbomSigningKey> rows = new LinkedHashMap<>();
	private SbomSigningKeyRepository repository;
	private AuditService auditService;
	private EncryptionService encryption;
	private SbomSigningKeyService service;
	private final UUID org = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		repository = mock(SbomSigningKeyRepository.class);
		auditService = mock(AuditService.class);
		encryption = SbomSigningTestKeys.encryptionService(SbomSigningTestKeys.PASSWORD);
		service = new SbomSigningKeyService(repository, auditService, encryption, mock(EntityManager.class));
		when(repository.saveAndFlush(any())).thenAnswer(inv -> {
			SbomSigningKey k = inv.getArgument(0);
			rows.put(k.getUuid(), k);
			return k;
		});
		when(repository.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
		when(repository.findByIdWriteLocked(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
		when(repository.findCurrentByOrg(anyString())).thenAnswer(inv -> current(inv.getArgument(0)));
		when(repository.findCurrentByOrgWriteLocked(anyString())).thenAnswer(inv -> current(inv.getArgument(0)));
		when(repository.findByOrg(anyString())).thenAnswer(inv -> rows.values().stream()
				.filter(k -> inv.getArgument(0).equals(k.getRecordData().get("org")))
				.sorted(Comparator.comparing(SbomSigningKey::getCreatedDate).reversed()).toList());
	}

	private Optional<SbomSigningKey> current(String orgStr) {
		return rows.values().stream().filter(k -> orgStr.equals(k.getRecordData().get("org"))
				&& "CURRENT".equals(k.getRecordData().get("status"))).findFirst();
	}

	private static byte[] x(SbomSigningKeyData k) {
		return Base64.getUrlDecoder().decode(k.getPublicKeyJwk().get("x"));
	}

	private byte[] decryptedPkcs8(SbomSigningKeyData k) {
		return Base64.getDecoder().decode(encryption.decrypt(k.getEncryptedPrivateKey()));
	}

	private static byte[] rawPublic(PublicKey pk) {
		byte[] spki = pk.getEncoded();
		return Arrays.copyOfRange(spki, spki.length - 32, spki.length);
	}

	@Test
	void generate_storesCurrentEd25519Key() throws Exception {
		SbomSigningKeyData k = service.generate(org, WU);

		assertEquals(SbomSigningKeyStatus.CURRENT, k.getStatus());
		assertEquals(SbomSigningKeySource.GENERATED, k.getSource());
		assertEquals(SbomSigningKeyAlgorithm.Ed25519, k.getAlgorithm());
		assertEquals(org, k.getOrg());
		assertEquals(k.getUuid().toString(), k.getKeyId());
		assertEquals("OKP", k.getPublicKeyJwk().get("kty"));
		assertEquals("Ed25519", k.getPublicKeyJwk().get("crv"));
		assertFalse(k.getPublicKeyJwk().get("x").contains("="), "base64url without padding");
		assertEquals(32, x(k).length);
		assertEquals(Set.of("kty", "crv", "x"), k.getPublicKeyJwk().keySet());

		String pem = k.getPublicKeyPem();
		assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n") && pem.endsWith("-----END PUBLIC KEY-----\n"), pem);
		byte[] spki = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", ""));
		PublicKey fromPem = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
		assertArrayEquals(x(k), rawPublic(fromPem));

		byte[] pkcs8 = decryptedPkcs8(k);
		assertNotEquals(Base64.getEncoder().encodeToString(pkcs8), k.getEncryptedPrivateKey());
		KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
		byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
		assertArrayEquals(x(k), new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded());
		// a first key overwrites nothing, so it writes no audit row
		verify(auditService, never()).createAndSaveAuditRecord(any(), any());
	}

	@Test
	void generate_retiresThePreviousCurrent() throws Exception {
		SbomSigningKeyData first = service.generate(org, WU);
		SbomSigningKey firstRow = rows.get(first.getUuid());
		SbomSigningKeyData second = service.generate(org, WU);

		SbomSigningKeyData firstNow = service.getKey(first.getUuid()).orElseThrow();
		assertEquals(SbomSigningKeyStatus.RETIRED, firstNow.getStatus());
		assertNotNull(firstNow.getRetiredAt());
		assertNull(firstNow.getRevokedAt());
		assertEquals(SbomSigningKeyStatus.CURRENT, second.getStatus());
		assertEquals(second.getUuid(), service.currentKey(org).orElseThrow().getUuid());
		assertEquals(1, rows.values().stream().filter(r -> "CURRENT".equals(r.getRecordData().get("status"))).count());
		assertNotEquals(first.getPublicKeyJwk().get("x"), second.getPublicKeyJwk().get("x"));
		// the retired key's CURRENT revision is audited, the new key is not
		verify(auditService, times(1)).createAndSaveAuditRecord(eq(TableName.SBOM_SIGNING_KEYS), same(firstRow));
		verify(auditService, times(1)).createAndSaveAuditRecord(any(), any());
	}

	@Test
	void currentKey_isPerOrg() throws Exception {
		SbomSigningKeyData mine = service.generate(org, WU);
		UUID otherOrg = UUID.randomUUID();
		SbomSigningKeyData theirs = service.generate(otherOrg, WU);
		// a key of another org neither retires mine nor shows in my list
		assertEquals(SbomSigningKeyStatus.CURRENT, service.getKey(mine.getUuid()).orElseThrow().getStatus());
		assertEquals(List.of(mine.getUuid()), service.listByOrg(org).stream().map(SbomSigningKeyData::getUuid).toList());
		assertEquals(theirs.getUuid(), service.currentKey(otherOrg).orElseThrow().getUuid());
	}

	@Test
	void revoke_currentLeavesNoCurrentKey() throws Exception {
		SbomSigningKeyData k = service.generate(org, WU);
		SbomSigningKeyData revoked = service.revoke(k.getUuid(), WU);
		assertEquals(SbomSigningKeyStatus.REVOKED, revoked.getStatus());
		assertNotNull(revoked.getRevokedAt());
		assertNull(revoked.getRetiredAt());
		assertTrue(service.currentKey(org).isEmpty());
		verify(auditService, times(1)).createAndSaveAuditRecord(eq(TableName.SBOM_SIGNING_KEYS), any());
	}

	@Test
	void revoke_retiredBecomesRevoked() throws Exception {
		SbomSigningKeyData first = service.generate(org, WU);
		SbomSigningKeyData second = service.generate(org, WU);
		ZonedDateTime retiredAt = service.getKey(first.getUuid()).orElseThrow().getRetiredAt();
		SbomSigningKeyData revoked = service.revoke(first.getUuid(), WU);
		assertEquals(SbomSigningKeyStatus.REVOKED, revoked.getStatus());
		assertNotNull(revoked.getRevokedAt());
		assertEquals(retiredAt.toInstant(), revoked.getRetiredAt().toInstant(), "retiredAt is kept");
		assertEquals(second.getUuid(), service.currentKey(org).orElseThrow().getUuid());
	}

	@Test
	void revoke_revokedIsANoOp() throws Exception {
		SbomSigningKeyData k = service.generate(org, WU);
		SbomSigningKeyData once = service.revoke(k.getUuid(), WU);
		int revision = rows.get(k.getUuid()).getRevision();
		SbomSigningKeyData twice = service.revoke(k.getUuid(), WU);
		assertEquals(once.getRevokedAt(), twice.getRevokedAt());
		assertEquals(revision, rows.get(k.getUuid()).getRevision());
		verify(auditService, times(1)).createAndSaveAuditRecord(any(), any());
	}

	@Test
	void revoke_unknownKeyIsNotFound() {
		RelizaException e = assertThrows(RelizaException.class, () -> service.revoke(UUID.randomUUID(), WU));
		assertEquals(SbomSigningKeyService.NOT_FOUND_MESSAGE, e.getMessage());
	}

	@Test
	void importKey_acceptsEd25519Pkcs8() throws Exception {
		KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String pem = SbomSigningTestKeys.pem("PRIVATE KEY", pair.getPrivate().getEncoded());
		SbomSigningKeyData k = service.importKey(org, pem, WU);
		assertEquals(SbomSigningKeySource.IMPORTED, k.getSource());
		assertEquals(SbomSigningKeyStatus.CURRENT, k.getStatus());
		assertArrayEquals(rawPublic(pair.getPublic()), x(k));
		assertArrayEquals(pair.getPublic().getEncoded(), Base64.getMimeDecoder().decode(k.getPublicKeyPem()
				.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")));
		assertArrayEquals(pair.getPrivate().getEncoded(), decryptedPkcs8(k));
	}

	@Test
	void importKey_derivesTheRfc8032PublicKeys() throws Exception {
		for (Rfc8032Vector v : SbomSigningTestKeys.rfc8032Vectors()) {
			String pem = SbomSigningTestKeys.pem("PRIVATE KEY", SbomSigningTestKeys.pkcs8FromSeed(v.secretKey()));
			SbomSigningKeyData k = service.importKey(UUID.randomUUID(), pem, WU);
			assertEquals(SbomSigningTestKeys.base64Url(v.publicKey()), k.getPublicKeyJwk().get("x"), v.name());
		}
	}

	@Test
	void importKey_rotatesLikeGenerate() throws Exception {
		SbomSigningKeyData generated = service.generate(org, WU);
		String pem = SbomSigningTestKeys.pem("PRIVATE KEY",
				KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
		SbomSigningKeyData imported = service.importKey(org, pem, WU);
		assertEquals(SbomSigningKeyStatus.RETIRED, service.getKey(generated.getUuid()).orElseThrow().getStatus());
		assertEquals(imported.getUuid(), service.currentKey(org).orElseThrow().getUuid());
	}

	@Test
	void importKey_refusesEcRsaAndPublicPem() throws Exception {
		byte[] ec = KeyPairGenerator.getInstance("EC").generateKeyPair().getPrivate().getEncoded();
		byte[] rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate().getEncoded();
		byte[] x25519 = KeyPairGenerator.getInstance("X25519").generateKeyPair().getPrivate().getEncoded();
		byte[] ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair().getPrivate().getEncoded();
		KeyPair ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		List<String> refused = List.of(
				SbomSigningTestKeys.pem("PRIVATE KEY", ec),
				SbomSigningTestKeys.pem("PRIVATE KEY", rsa),
				SbomSigningTestKeys.pem("PRIVATE KEY", x25519),
				SbomSigningTestKeys.pem("PRIVATE KEY", ed448),
				SbomSigningTestKeys.pem("PUBLIC KEY", ed25519.getPublic().getEncoded()),
				SbomSigningTestKeys.pem("EC PRIVATE KEY", ec),
				SbomSigningTestKeys.pem("RSA PRIVATE KEY", rsa),
				SbomSigningTestKeys.pem("OPENSSH PRIVATE KEY", ed25519.getPrivate().getEncoded()),
				Base64.getEncoder().encodeToString(ed25519.getPrivate().getEncoded()),
				"-----BEGIN PRIVATE KEY-----\n%%%%\n-----END PRIVATE KEY-----\n",
				"-----BEGIN PRIVATE KEY-----\n" + "A".repeat(5000) + "\n-----END PRIVATE KEY-----\n",
				"");
		for (String pem : refused) {
			RelizaException e = assertThrows(RelizaException.class, () -> service.importKey(org, pem, WU));
			assertEquals(SbomSigningKeyService.IMPORT_FORMAT_MESSAGE, e.getMessage());
			assertTrue(e.getMessage().contains("PKCS#8 PEM"));
		}
		assertEquals(SbomSigningKeyService.IMPORT_FORMAT_MESSAGE,
				assertThrows(RelizaException.class, () -> service.importKey(org, null, WU)).getMessage());
		assertTrue(rows.isEmpty(), "nothing stored for a refused import");
	}

	@Test
	void rotationRace_isReportedNotSwallowed() throws Exception {
		doThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint \"sbom_signing_keys_org_current\"")).when(repository).saveAndFlush(any());
		RelizaException e = assertThrows(RelizaException.class, () -> service.generate(org, WU));
		assertEquals(SbomSigningKeyService.ROTATION_RACE_MESSAGE, e.getMessage());
		assertTrue(e.getMessage().contains("rotation"));
	}

	@Test
	void listByOrg_ordersCurrentFirstThenNewest() throws Exception {
		SbomSigningKeyData a = service.generate(org, WU);
		rows.get(a.getUuid()).setCreatedDate(ZonedDateTime.now().minusDays(3));
		SbomSigningKeyData b = service.generate(org, WU);
		rows.get(b.getUuid()).setCreatedDate(ZonedDateTime.now().minusDays(2));
		SbomSigningKeyData c = service.generate(org, WU);
		rows.get(c.getUuid()).setCreatedDate(ZonedDateTime.now().minusDays(4));
		// c is CURRENT though oldest; then b, a newest first
		assertEquals(List.of(c.getUuid(), b.getUuid(), a.getUuid()),
				service.listByOrg(org).stream().map(SbomSigningKeyData::getUuid).toList());
	}

	@Test
	void publicKeys_areJwksEntriesOfPublicFieldsOnly() throws Exception {
		SbomSigningKeyData retired = service.generate(org, WU);
		SbomSigningKeyData current = service.generate(org, WU);
		SbomSigningKeyData revoked = service.revoke(retired.getUuid(), WU);
		List<Map<String, Object>> keys = service.publicKeys(org);
		assertEquals(2, keys.size());
		Map<String, Object> c = keys.get(0);
		assertEquals(List.of("kty", "crv", "x", "kid", "use", "alg", "rearm:status", "rearm:notBefore",
				"rearm:notAfter", "rearm:revokedAt"), List.copyOf(c.keySet()));
		assertEquals(current.getKeyId(), c.get("kid"));
		assertEquals("CURRENT", c.get("rearm:status"));
		assertEquals("sig", c.get("use"));
		assertEquals("Ed25519", c.get("alg"));
		assertNull(c.get("rearm:notAfter"));
		Map<String, Object> r = keys.get(1);
		assertEquals("REVOKED", r.get("rearm:status"));
		assertEquals(revoked.getRetiredAt().toInstant().toString(), r.get("rearm:notAfter"));
		assertEquals(revoked.getRevokedAt().toInstant().toString(), r.get("rearm:revokedAt"));
		assertTrue(((String) r.get("rearm:notBefore")).endsWith("Z"), "a UTC instant");
		String text = keys.toString();
		for (SbomSigningKeyData k : List.of(current, revoked)) {
			LocalKeySignerTest.assertNoKeyMaterial(text, decryptedPkcs8(k), k.getEncryptedPrivateKey());
		}
	}

	@Test
	void privateMaterial_isNeverLoggedNorInToString() throws Exception {
		KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String pem = SbomSigningTestKeys.pem("PRIVATE KEY", pair.getPrivate().getEncoded());
		try (LogCapture logs = new LogCapture(SbomSigningKeyService.class)) {
			SbomSigningKeyData g = service.generate(org, WU);
			SbomSigningKeyData i = service.importKey(org, pem, WU);
			service.revoke(i.getUuid(), WU);
			assertThrows(RelizaException.class, () -> service.importKey(org,
					SbomSigningTestKeys.pem("PRIVATE KEY", KeyPairGenerator.getInstance("EC").generateKeyPair()
							.getPrivate().getEncoded()), WU));
			String text = logs.text();
			// the lifecycle lines are there, so the capture saw INFO
			assertTrue(text.contains("SBOM_SIGNING_KEY generated for org " + org + ": keyId " + g.getKeyId()), text);
			assertTrue(text.contains("SBOM_SIGNING_KEY retired for org " + org + ": keyId " + g.getKeyId()), text);
			assertTrue(text.contains("SBOM_SIGNING_KEY imported for org " + org + ": keyId " + i.getKeyId()), text);
			assertTrue(text.contains("SBOM_SIGNING_KEY revoked for org " + org + ": keyId " + i.getKeyId()), text);
			for (SbomSigningKeyData k : List.of(g, i)) {
				LocalKeySignerTest.assertNoKeyMaterial(text, decryptedPkcs8(k), k.getEncryptedPrivateKey());
				LocalKeySignerTest.assertNoKeyMaterial(k.toString(), decryptedPkcs8(k), k.getEncryptedPrivateKey());
			}
		}
	}

	@Test
	void reads_ofNullAreEmpty() {
		assertTrue(service.getKey(null).isEmpty());
		assertTrue(service.currentKey(null).isEmpty());
		assertTrue(service.listByOrg(null).isEmpty());
	}
}
