/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.Utils;
import io.reliza.exceptions.SbomSigningException;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Signs a merged CycloneDX JSON export at egress with the exporting organization's CURRENT SBOM
 * signing key (SCORE-31 ADR-1, ADR-2, ADR-6, ADR-8; SCORE-33 section 3.4).
 *
 * <p>The document is stamped with {@code reliza:export:signed} and its companion on
 * {@code metadata.properties}, then gets a root JSF {@code signature} (last member: {@code algorithm},
 * {@code keyId}, {@code publicKey}, {@code value}) over the RFC 8785 canonical form of the whole
 * document with {@code value} not yet set, so the properties and the signature's own members are
 * covered. The served bytes are the compact serialisation of that tree, not the canonical form: the
 * verifier canonicalises what it reads.
 *
 * <p>The key is only ever {@link SbomSigningKeyService#currentKey} of the organization the caller
 * names, which must be the org of the exported release. An org without a key gets an unsigned export
 * that says {@link UnsignedReason#NO_KEY}; a failure to sign gets one that says
 * {@link UnsignedReason#SIGNING_ERROR}. An export is never failed over the signature.
 */
@Service
@Slf4j
public class SbomExportSigningService {

	/** Why a merged JSON export is unsigned, written by name as {@code reliza:export:unsignedReason}. */
	public enum UnsignedReason {
		/** The organization has no CURRENT signing key (none made, or the current one revoked). */
		NO_KEY,
		/** The organization has a key and signing failed; the ERROR log line says why. */
		SIGNING_ERROR
	}

	static final String SIGNATURE = "signature";
	static final String SIGNATURE_ALGORITHM = "algorithm";
	static final String SIGNATURE_KEY_ID = "keyId";
	static final String SIGNATURE_PUBLIC_KEY = "publicKey";
	static final String SIGNATURE_VALUE = "value";
	private static final String METADATA = "metadata";
	private static final String PROPERTIES = "properties";

	/**
	 * Signings that may run at once. Canonicalising a 50 MB export holds about 1 GB of transient heap
	 * for about 2 s (SCORE-33 T-13), so concurrent exports are bounded here rather than left to
	 * exhaust the heap; an export that cannot get a permit within {@link #PERMIT_WAIT_MILLIS} is
	 * served unsigned with {@link UnsignedReason#SIGNING_ERROR} (cause {@code BUSY}).
	 */
	static final int SIGNING_PERMITS = 2;
	static final long PERMIT_WAIT_MILLIS = 30_000L;
	/**
	 * The largest document signed, in characters of its serialisation: rebom's read limit
	 * ({@link RebomService#REBOM_RESPONSE_LIMIT}) plus a quarter for the egress additions. A larger
	 * one is served unsigned with {@link UnsignedReason#SIGNING_ERROR} (cause {@code TOO_LARGE}).
	 */
	static final long MAX_SIGNED_CHARS = RebomService.REBOM_RESPONSE_LIMIT + RebomService.REBOM_RESPONSE_LIMIT / 4L;

	/** Why signing was not attempted although a key exists; the cause the ERROR line names. */
	enum Refusal { BUSY, TOO_LARGE }

	private static final class SigningRefused extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final Refusal refusal;

		SigningRefused(Refusal refusal, String message) {
			super(message);
			this.refusal = refusal;
		}
	}

	private final SbomSigningKeyService sbomSigningKeyService;
	private final SbomSigner sbomSigner;
	private final Semaphore permits;
	private final long permitWaitMillis;
	private final long maxSignedChars;

	@Autowired
	SbomExportSigningService(SbomSigningKeyService sbomSigningKeyService, SbomSigner sbomSigner) {
		this(sbomSigningKeyService, sbomSigner, SIGNING_PERMITS, PERMIT_WAIT_MILLIS, MAX_SIGNED_CHARS);
	}

	/** With other bounds, for the tests of the bounds. */
	SbomExportSigningService(SbomSigningKeyService sbomSigningKeyService, SbomSigner sbomSigner, int signingPermits,
			long permitWaitMillis, long maxSignedChars) {
		this.sbomSigningKeyService = sbomSigningKeyService;
		this.sbomSigner = sbomSigner;
		this.permits = new Semaphore(signingPermits);
		this.permitWaitMillis = permitWaitMillis;
		this.maxSignedChars = maxSignedChars;
	}

	/**
	 * Stamps and, when {@code org} has a CURRENT key, signs {@code bom} in place, and returns the
	 * bytes to serve.
	 *
	 * @param bom the merged document as every JSON reader receives it, after every other egress edit
	 * @param org the organization of the exported release: its key, and only its key, signs
	 * @param releaseUuid the exported release, for the log lines
	 */
	public String stampAndSign(JsonNode bom, UUID org, UUID releaseUuid) {
		if (!(bom instanceof ObjectNode doc)) {
			// nowhere to state anything: an array or a scalar has no metadata (rebom never serves one)
			log.warn("SBOM_EXPORT_NOT_STAMPED release {} org {}: the merged document is not a JSON object", releaseUuid, org);
			return String.valueOf(bom);
		}
		SupportBomInjector.stripPropertiesNamedEverywhere(doc, SupportBomInjector.SIGNING_RECORD_PROPERTIES);
		doc.remove(SIGNATURE);
		JsonNode existingMetadata = doc.get(METADATA);
		if (null != existingMetadata && !existingMetadata.isObject()) {
			// CycloneDX metadata is an object; one that is not cannot carry the record, and an export
			// without the record would read as one that predates signing. Replaced in place.
			ObjectNode replaced = doc.putObject(METADATA);
			stampUnsigned(replaced, UnsignedReason.SIGNING_ERROR);
			log.error("SBOM_EXPORT_UNSIGNED release {} org {} reason {} keyId {} cause {}: {}", releaseUuid, org,
					UnsignedReason.SIGNING_ERROR, null, "METADATA_NOT_OBJECT",
					"the merged document's metadata is not a JSON object and was replaced");
			return doc.toString();
		}
		ObjectNode metadata = null == existingMetadata ? doc.putObject(METADATA) : (ObjectNode) existingMetadata;

		SbomSigningKeyData key = null;
		ArrayNode properties = null;
		try {
			Optional<SbomSigningKeyData> current = sbomSigningKeyService.currentKey(org);
			if (current.isEmpty()) {
				stampUnsigned(metadata, UnsignedReason.NO_KEY);
				log.info("SBOM_EXPORT_UNSIGNED release {} org {} reason {}", releaseUuid, org, UnsignedReason.NO_KEY);
				return doc.toString();
			}
			key = current.get();
			requireCurrentKeyOf(key, org);
			SupportBomInjector.appendProperty(metadata, SupportBomInjector.SIGNED_PROPERTY, "true");
			SupportBomInjector.appendProperty(metadata, SupportBomInjector.SIGNATURE_KEY_ID_PROPERTY, key.getKeyId());
			properties = (ArrayNode) metadata.get(PROPERTIES);
			ObjectNode signature = doc.putObject(SIGNATURE);
			signature.put(SIGNATURE_ALGORITHM, sbomSigner.algorithm(key));
			signature.put(SIGNATURE_KEY_ID, key.getKeyId());
			signature.set(SIGNATURE_PUBLIC_KEY, Utils.OM.valueToTree(SbomSigningKeyService.publicJwk(key)));
			JsfCanonicalizer.requireExactNumbers(doc);
			String unsignedText = doc.toString();
			if (unsignedText.length() > maxSignedChars) {
				throw new SigningRefused(Refusal.TOO_LARGE, "the document has " + unsignedText.length()
						+ " characters, over the signing limit of " + maxSignedChars);
			}
			byte[] value = canonicaliseAndSign(unsignedText, key);
			signature.put(SIGNATURE_VALUE, Base64.getUrlEncoder().withoutPadding().encodeToString(value));
			String emitted = doc.toString();
			log.debug("SBOM_EXPORT_SIGNED release {} org {} keyId {}", releaseUuid, org, key.getKeyId());
			return emitted;
		} catch (RuntimeException e) {
			doc.remove(SIGNATURE);
			if (null != properties) {
				// the two entries stamped above are the array's last two; the array keeps its place
				properties.remove(properties.size() - 1);
				properties.remove(properties.size() - 1);
			}
			stampUnsigned(metadata, UnsignedReason.SIGNING_ERROR);
			log.error("SBOM_EXPORT_UNSIGNED release {} org {} reason {} keyId {} cause {}: {}", releaseUuid, org,
					UnsignedReason.SIGNING_ERROR, null == key ? null : key.getKeyId(), causeOf(e), e.getMessage());
			log.debug("SBOM_EXPORT_UNSIGNED release {} stack trace", releaseUuid, e);
			return doc.toString();
		}
	}

	/** The canonical form and the signature over it, holding one of the {@link #permits}. */
	private byte[] canonicaliseAndSign(String unsignedText, SbomSigningKeyData key) {
		boolean acquired;
		try {
			acquired = permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			acquired = false;
		}
		if (!acquired) {
			throw new SigningRefused(Refusal.BUSY, "no signing permit within " + permitWaitMillis + " ms");
		}
		try {
			byte[] canonical = JsfCanonicalizer.canonicalize(unsignedText);
			return sbomSigner.sign(canonical, key);
		} finally {
			permits.release();
		}
	}

	private static String causeOf(RuntimeException e) {
		if (e instanceof SbomSigningException se && null != se.getReason()) return se.getReason().name();
		if (e instanceof SigningRefused sr) return sr.refusal.name();
		return e.getClass().getSimpleName();
	}

	/**
	 * Defence in depth over {@link SbomSigningKeyService#currentKey}: the key must be the CURRENT key
	 * of the exporting org and name itself, or nothing is signed with it.
	 */
	private static void requireCurrentKeyOf(SbomSigningKeyData key, UUID org) {
		if (null == org || !org.equals(key.getOrg()) || SbomSigningKeyStatus.CURRENT != key.getStatus()
				|| null == key.getKeyId() || key.getKeyId().isBlank()) {
			throw new IllegalStateException("SBOM signing key " + key.getKeyId()
					+ " is not the CURRENT key of the exporting organization");
		}
	}

	private static void stampUnsigned(ObjectNode metadata, UnsignedReason reason) {
		SupportBomInjector.appendProperty(metadata, SupportBomInjector.SIGNED_PROPERTY, "false");
		SupportBomInjector.appendProperty(metadata, SupportBomInjector.UNSIGNED_REASON_PROPERTY, reason.name());
	}
}
