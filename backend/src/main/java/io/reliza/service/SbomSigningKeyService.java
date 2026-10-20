/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
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
import java.util.UUID;

import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.SbomSigningKey;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyAlgorithm;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeySource;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.SbomSigningKeyRepository;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/**
 * The SBOM signing keys of an organization (SCORE-32; SCORE-31 ADR-3..5): generate, import, revoke,
 * and the reads the signer and the GraphQL surface use (the public JWKS endpoint is withdrawn for now).
 *
 * <p>Rotation is {@link #generate} or {@link #importKey} while a CURRENT key exists: the old key
 * becomes RETIRED, the new one CURRENT. One CURRENT key per org is enforced here and by the partial
 * unique index {@code sbom_signing_keys_org_current}; a rotation that loses a race to another is
 * reported, not merged.
 *
 * <p>The private key is held only as {@link EncryptionService} ciphertext. Nothing here logs, returns
 * or puts into an exception message any private material: log lines carry org, keyId and actor uuid.
 */
@Service
@Slf4j
public class SbomSigningKeyService {

	public static final String IMPORT_FORMAT_MESSAGE =
			"SBOM signing key import expects an Ed25519 private key as PKCS#8 PEM (BEGIN PRIVATE KEY)";
	public static final String ROTATION_RACE_MESSAGE = "SBOM signing key rotation raced another rotation; retry";
	public static final String NOT_FOUND_MESSAGE = "SBOM signing key not found";

	/** The JCA name of the one algorithm, from the enum so the stored, published and JCA names are one. */
	private static final String ED25519 = SbomSigningKeyAlgorithm.Ed25519.name();
	private static final String PRIVATE_PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
	private static final String PRIVATE_PEM_END = "-----END PRIVATE KEY-----";
	private static final String PUBLIC_PEM_BEGIN = "-----BEGIN PUBLIC KEY-----";
	private static final String PUBLIC_PEM_END = "-----END PUBLIC KEY-----";
	/** A PKCS#8 Ed25519 PEM is about 120 characters; anything far beyond is not one. */
	private static final int MAX_IMPORT_PEM_LENGTH = 4096;
	private static final int RAW_KEY_LENGTH = 32;

	private final SbomSigningKeyRepository repository;
	private final AuditService auditService;
	private final EncryptionService encryptionService;
	/** Re-reads a row once it is locked; see {@link #refreshed}. */
	private final EntityManager entityManager;

	SbomSigningKeyService(SbomSigningKeyRepository repository, AuditService auditService,
			EncryptionService encryptionService, EntityManager entityManager) {
		this.repository = repository;
		this.auditService = auditService;
		this.encryptionService = encryptionService;
		this.entityManager = entityManager;
	}

	// ---- reads -------------------------------------------------------------------------------------

	public Optional<SbomSigningKeyData> getKey(UUID keyUuid) {
		if (null == keyUuid) return Optional.empty();
		return repository.findById(keyUuid).map(SbomSigningKeyData::dataFromRecord);
	}

	/** The key that signs the org's exports, if it has one. */
	public Optional<SbomSigningKeyData> currentKey(UUID org) {
		if (null == org) return Optional.empty();
		return repository.findCurrentByOrg(org.toString()).map(SbomSigningKeyData::dataFromRecord);
	}

	/** Every key of the org: CURRENT first, then the rest newest first. */
	public List<SbomSigningKeyData> listByOrg(UUID org) {
		if (null == org) return List.of();
		return repository.findByOrg(org.toString()).stream()
				.map(SbomSigningKeyData::dataFromRecord)
				.sorted(Comparator.comparing((SbomSigningKeyData k) -> SbomSigningKeyStatus.CURRENT != k.getStatus()))
				.toList();
	}

	/** The public JWK of a key, {@code kty}, {@code crv}, {@code x} in that order, whatever order the record kept. */
	public static Map<String, String> publicJwk(SbomSigningKeyData key) {
		Map<String, String> stored = key.getPublicKeyJwk();
		Map<String, String> jwk = new LinkedHashMap<>();
		jwk.put(SbomSigningKeyData.JWK_KTY, stored.get(SbomSigningKeyData.JWK_KTY));
		jwk.put(SbomSigningKeyData.JWK_CRV, stored.get(SbomSigningKeyData.JWK_CRV));
		jwk.put(SbomSigningKeyData.JWK_X, stored.get(SbomSigningKeyData.JWK_X));
		return jwk;
	}

	/**
	 * The JWKS entries of the org's keys, every status, in {@link #listByOrg} order. Built from the
	 * public fields only. Times are UTC instants; an unset one is null.
	 */
	public List<Map<String, Object>> publicKeys(UUID org) {
		return listByOrg(org).stream().map(SbomSigningKeyService::jwksEntry).toList();
	}

	private static Map<String, Object> jwksEntry(SbomSigningKeyData k) {
		Map<String, Object> e = new LinkedHashMap<>(publicJwk(k));
		e.put(SbomSigningKeyData.JWK_KID, k.getKeyId());
		e.put(SbomSigningKeyData.JWK_USE, SbomSigningKeyData.JWK_USE_SIG);
		e.put(SbomSigningKeyData.JWK_ALG, k.getAlgorithm().name());
		e.put(SbomSigningKeyData.JWK_STATUS, k.getStatus().name());
		e.put(SbomSigningKeyData.JWK_NOT_BEFORE, instant(k.getCreatedDate()));
		e.put(SbomSigningKeyData.JWK_NOT_AFTER, instant(k.getRetiredAt()));
		e.put(SbomSigningKeyData.JWK_REVOKED_AT, instant(k.getRevokedAt()));
		return e;
	}

	private static String instant(ZonedDateTime t) {
		return null == t ? null : t.toInstant().toString();
	}

	// ---- lifecycle ---------------------------------------------------------------------------------

	/** A new Ed25519 key becomes the org's CURRENT key; a CURRENT key it had is RETIRED. */
	@Transactional(rollbackFor = RelizaException.class)
	public SbomSigningKeyData generate(UUID org, WhoUpdated wu) throws RelizaException {
		if (null == org) throw new RelizaException("SBOM signing key requires an org");
		KeyPair pair;
		try {
			pair = KeyPairGenerator.getInstance(ED25519).generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("The JDK offers no Ed25519 key pair generator", e);
		}
		byte[] spki = pair.getPublic().getEncoded();
		byte[] x = Arrays.copyOfRange(spki, spki.length - RAW_KEY_LENGTH, spki.length);
		SbomSigningKeyData seed = newKey(org, SbomSigningKeySource.GENERATED, x, spki, pair.getPrivate());
		return install(seed, wu);
	}

	/**
	 * An Ed25519 private key given as PKCS#8 PEM becomes the org's CURRENT key; a CURRENT key it had
	 * is RETIRED. Anything else is refused with {@link #IMPORT_FORMAT_MESSAGE}, never with the input
	 * or the parser's words.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public SbomSigningKeyData importKey(UUID org, String privateKeyPem, WhoUpdated wu) throws RelizaException {
		if (null == org) throw new RelizaException("SBOM signing key requires an org");
		byte[] seedBytes = ed25519Seed(privateKeyPem);
		try {
			PrivateKey privateKey = KeyFactory.getInstance(ED25519)
					.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seedBytes));
			byte[] x = new Ed25519PrivateKeyParameters(seedBytes, 0).generatePublicKey().getEncoded();
			byte[] spki = new SubjectPublicKeyInfo(new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), x)
					.getEncoded();
			// the derived public key must read back as an Ed25519 key, or nothing is stored
			KeyFactory.getInstance(ED25519).generatePublic(new X509EncodedKeySpec(spki));
			SbomSigningKeyData seed = newKey(org, SbomSigningKeySource.IMPORTED, x, spki, privateKey);
			return install(seed, wu);
		} catch (GeneralSecurityException | IOException e) {
			throw new RelizaException(IMPORT_FORMAT_MESSAGE);
		} finally {
			Arrays.fill(seedBytes, (byte) 0);
		}
	}

	/**
	 * The key is REVOKED: its {@code revokedAt} is set, {@code retiredAt} kept. Revoking the CURRENT
	 * key leaves the org without one until the next generate or import. A REVOKED key is returned as is.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public SbomSigningKeyData revoke(UUID keyUuid, WhoUpdated wu) throws RelizaException {
		SbomSigningKey k = refreshed(repository.findByIdWriteLocked(keyUuid)
				.orElseThrow(() -> new RelizaException(NOT_FOUND_MESSAGE)));
		SbomSigningKeyData kd = SbomSigningKeyData.dataFromRecord(k);
		if (SbomSigningKeyStatus.REVOKED == kd.getStatus()) return kd;
		kd.setStatus(SbomSigningKeyStatus.REVOKED);
		kd.setRevokedAt(ZonedDateTime.now());
		SbomSigningKeyData saved = SbomSigningKeyData.dataFromRecord(save(k, Utils.dataToRecord(kd), wu));
		log.info("SBOM_SIGNING_KEY revoked for org {}: keyId {} by user {}", saved.getOrg(), saved.getKeyId(),
				actor(wu));
		return saved;
	}

	// ---- internals ---------------------------------------------------------------------------------

	private SbomSigningKeyData newKey(UUID org, SbomSigningKeySource source, byte[] x, byte[] spki,
			PrivateKey privateKey) {
		SbomSigningKeyData kd = new SbomSigningKeyData();
		kd.setOrg(org);
		kd.setAlgorithm(SbomSigningKeyAlgorithm.Ed25519);
		kd.setSource(source);
		kd.setStatus(SbomSigningKeyStatus.CURRENT);
		Map<String, String> jwk = new LinkedHashMap<>();
		jwk.put(SbomSigningKeyData.JWK_KTY, SbomSigningKeyData.JWK_KTY_OKP);
		jwk.put(SbomSigningKeyData.JWK_CRV, ED25519);
		jwk.put(SbomSigningKeyData.JWK_X, Base64.getUrlEncoder().withoutPadding().encodeToString(x));
		kd.setPublicKeyJwk(jwk);
		kd.setPublicKeyPem(PUBLIC_PEM_BEGIN + "\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(spki)
				+ "\n" + PUBLIC_PEM_END + "\n");
		byte[] pkcs8 = privateKey.getEncoded();
		try {
			kd.setEncryptedPrivateKey(encryptionService.encrypt(Base64.getEncoder().encodeToString(pkcs8)));
		} finally {
			Arrays.fill(pkcs8, (byte) 0);
		}
		return kd;
	}

	/**
	 * The 32-byte Ed25519 seed of a PKCS#8 PEM, read by the JDK's Ed25519 key factory, so an EC, RSA,
	 * X25519 or Ed448 key is refused by the factory itself.
	 */
	private static byte[] ed25519Seed(String privateKeyPem) throws RelizaException {
		if (null == privateKeyPem || privateKeyPem.length() > MAX_IMPORT_PEM_LENGTH) {
			throw new RelizaException(IMPORT_FORMAT_MESSAGE);
		}
		String pem = privateKeyPem.strip();
		if (!pem.startsWith(PRIVATE_PEM_BEGIN) || !pem.endsWith(PRIVATE_PEM_END)) {
			throw new RelizaException(IMPORT_FORMAT_MESSAGE);
		}
		String body = pem.substring(PRIVATE_PEM_BEGIN.length(), pem.length() - PRIVATE_PEM_END.length());
		byte[] der = null;
		try {
			der = Base64.getMimeDecoder().decode(body);
			PrivateKey pk = KeyFactory.getInstance(ED25519).generatePrivate(new PKCS8EncodedKeySpec(der));
			if (!(pk instanceof EdECPrivateKey edec)) throw new RelizaException(IMPORT_FORMAT_MESSAGE);
			byte[] seed = edec.getBytes().orElseThrow(() -> new RelizaException(IMPORT_FORMAT_MESSAGE));
			if (RAW_KEY_LENGTH != seed.length) throw new RelizaException(IMPORT_FORMAT_MESSAGE);
			return seed;
		} catch (IllegalArgumentException | GeneralSecurityException e) {
			throw new RelizaException(IMPORT_FORMAT_MESSAGE);
		} finally {
			if (null != der) Arrays.fill(der, (byte) 0);
		}
	}

	/**
	 * Retires the org's CURRENT key, if any, and stores {@code seed} as the new CURRENT key. The
	 * CURRENT row is read under a row lock, so a second rotation waits for the first and then finds
	 * no CURRENT row; its insert meets the partial unique index and is reported as a race.
	 */
	private SbomSigningKeyData install(SbomSigningKeyData seed, WhoUpdated wu) throws RelizaException {
		try {
			Optional<SbomSigningKey> current = repository.findCurrentByOrgWriteLocked(seed.getOrg().toString())
					.map(this::refreshed);
			if (current.isPresent()) {
				SbomSigningKey ck = current.get();
				SbomSigningKeyData cd = SbomSigningKeyData.dataFromRecord(ck);
				cd.setStatus(SbomSigningKeyStatus.RETIRED);
				cd.setRetiredAt(ZonedDateTime.now());
				save(ck, Utils.dataToRecord(cd), wu);
				log.info("SBOM_SIGNING_KEY retired for org {}: keyId {} by user {}", cd.getOrg(), cd.getKeyId(),
						actor(wu));
			}
			SbomSigningKey entity = new SbomSigningKey();
			seed.setKeyId(entity.getUuid().toString());
			SbomSigningKeyData saved = SbomSigningKeyData.dataFromRecord(save(entity, Utils.dataToRecord(seed), wu));
			log.info("SBOM_SIGNING_KEY {} for org {}: keyId {} by user {}",
					SbomSigningKeySource.IMPORTED == saved.getSource() ? "imported" : "generated",
					saved.getOrg(), saved.getKeyId(), actor(wu));
			return saved;
		} catch (DataIntegrityViolationException e) {
			log.info("SBOM_SIGNING_KEY rotation for org {} raced another rotation", seed.getOrg());
			throw new RelizaException(ROTATION_RACE_MESSAGE);
		}
	}

	/**
	 * The locked row as committed. The lock is necessary but not sufficient: under open-in-view the
	 * request may already hold this row (the GraphQL revoke reads the key to authorize it), and a
	 * locking query then hands back that pre-lock instance. The VulnerabilityRecordService precedent.
	 */
	private SbomSigningKey refreshed(SbomSigningKey k) {
		entityManager.refresh(k);
		return k;
	}

	private static UUID actor(WhoUpdated wu) {
		return null == wu ? null : wu.getLastUpdatedBy();
	}

	private SbomSigningKey save(SbomSigningKey k, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<SbomSigningKey> existing = repository.findById(k.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.SBOM_SIGNING_KEYS, k);
			k.setRevision(k.getRevision() + 1);
			k.setLastUpdatedDate(ZonedDateTime.now());
		}
		k.setRecordData(recordData);
		k = (SbomSigningKey) WhoUpdated.injectWhoUpdatedData(k, wu);
		return repository.saveAndFlush(k);
	}
}
