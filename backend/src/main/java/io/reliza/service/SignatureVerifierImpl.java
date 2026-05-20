/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.SignatureArtifactTags;
import io.reliza.model.SignatureVerificationData;
import io.reliza.model.SignatureVerificationData.SignatureSubjectType;
import io.reliza.model.SignatureVerificationData.SignatureVerificationState;
import io.reliza.model.SigningKeyData;
import io.reliza.model.SigningKeyData.SignatureFormat;
import io.reliza.model.SigningKeyData.SigningKeyOwnerType;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.RebomService.SignatureVerifyResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Cryptographic signature verifier — shared across SAAS and CE.
 * Orchestrates:
 * <ol>
 *   <li>Pull signature + payload bytes from oci-artifact via
 *       {@link SharedArtifactService}.</li>
 *   <li>Derive the trust scope from the SCE — narrow to the
 *       trailer-named agent's keys when {@code sce.agent} is set,
 *       committer-only otherwise. AGENT keys are never used for
 *       non-agentic commits, and a malformed-but-present trailer
 *       short-circuits to {@code ERRORED}. See ai-plans §12.6.</li>
 *   <li>Build the format-appropriate trust store from active
 *       {@link io.reliza.model.SigningKey} rows in scope.</li>
 *   <li>Delegate the cryptographic check to rebom-backend's
 *       {@code verifySignature} GraphQL mutation
 *       ({@link RebomService#verifySignature}).</li>
 *   <li>Resolve the matched fingerprint to an enrolled-key owner.</li>
 *   <li>Persist a fresh {@code signature_verifications} row.</li>
 * </ol>
 *
 * <p>CE deployments get the same verification, persistence, and UI
 * verdict badges. CEL policy enforcement on top of the verdict is
 * SAAS-only (lives in {@code service.saas}); CE shows the badge but
 * has no policy layer to react to it.
 */
@Service
@Slf4j
public class SignatureVerifierImpl implements SignatureVerifier {

	@Autowired
	private SharedArtifactService sharedArtifactService;

	@Autowired
	private SigningKeyService signingKeyService;

	@Autowired
	private SignatureVerificationService signatureVerificationService;

	@Autowired
	private RebomService rebomService;

	@Autowired
	private io.reliza.service.ArtifactService artifactService;

	@Autowired
	private GetSourceCodeEntryService sourceCodeEntryService;

	@Override
	public SignatureVerificationData verify(Map<String, Object> input, WhoUpdated wu) throws RelizaException {
		// Parse + sanity-check the input.
		SignatureSubjectType subjectType = SignatureSubjectType.valueOf((String) input.get("subjectType"));
		UUID subjectUuid = UUID.fromString((String) input.get("subjectUuid"));
		UUID signatureArtifactUuid = UUID.fromString((String) input.get("signatureArtifactUuid"));
		UUID signedPayloadArtifactUuid = input.get("signedPayloadArtifactUuid") != null
				? UUID.fromString((String) input.get("signedPayloadArtifactUuid"))
				: null;
		String declaredFormat = (String) input.get("format");

		ArtifactData sigArt = artifactService.getArtifactData(signatureArtifactUuid)
				.orElseThrow(() -> new RelizaException("Signature artifact not found: " + signatureArtifactUuid));
		UUID orgUuid = sigArt.getOrg();

		// Format resolution — input override beats tag-derived value.
		SignatureFormat format = resolveFormat(sigArt, declaredFormat);
		if (format == SignatureFormat.X509) {
			return persistTerminal(orgUuid, subjectType, subjectUuid, signatureArtifactUuid,
					signedPayloadArtifactUuid, format, SignatureVerificationState.ERRORED,
					"X.509 verification is not implemented in v1 — see ai-plans §12.8", wu);
		}

		// Pull bytes.
		byte[] signatureBytes;
		try {
			signatureBytes = downloadArtifactBytes(sigArt);
		} catch (Exception e) {
			log.warn("Failed to download signature artifact {}: {}", signatureArtifactUuid, e.getMessage());
			return persistTerminal(orgUuid, subjectType, subjectUuid, signatureArtifactUuid,
					signedPayloadArtifactUuid, format, SignatureVerificationState.ERRORED,
					"Signature artifact unreadable: " + e.getMessage(), wu);
		}
		byte[] payloadBytes = null;
		if (signedPayloadArtifactUuid != null) {
			Optional<ArtifactData> payloadArt = artifactService.getArtifactData(signedPayloadArtifactUuid);
			if (payloadArt.isPresent()) {
				try {
					payloadBytes = downloadArtifactBytes(payloadArt.get());
				} catch (Exception e) {
					log.warn("Failed to download payload artifact {}: {}", signedPayloadArtifactUuid, e.getMessage());
				}
			}
		}
		if (payloadBytes == null) {
			// Caller must supply the payload for commit signatures — the
			// verifier subprocess has no other way to know what was signed.
			return persistTerminal(orgUuid, subjectType, subjectUuid, signatureArtifactUuid,
					signedPayloadArtifactUuid, format, SignatureVerificationState.ERRORED,
					"signedPayloadArtifactUuid required for v1 verification", wu);
		}

		// Derive trust scope from the SCE — see class javadoc + ai-plans §12.6.
		TrustScope scope = resolveTrustScope(orgUuid, subjectType, subjectUuid, format);
		if (scope.shortCircuitVerdict != null) {
			return persistTerminal(orgUuid, subjectType, subjectUuid, signatureArtifactUuid,
					signedPayloadArtifactUuid, format, scope.shortCircuitVerdict,
					scope.shortCircuitDetails, wu);
		}
		String trustStore = buildTrustStore(format, scope.keys);

		// Delegate to rebom.
		SignatureVerifyResult rebomResult;
		try {
			rebomResult = rebomService.verifySignature(
					format.name(),
					Base64.getEncoder().encodeToString(signatureBytes),
					Base64.getEncoder().encodeToString(payloadBytes),
					Base64.getEncoder().encodeToString(trustStore.getBytes()),
					null);
		} catch (RuntimeException e) {
			log.error("rebom verifySignature errored", e);
			return persistTerminal(orgUuid, subjectType, subjectUuid, signatureArtifactUuid,
					signedPayloadArtifactUuid, format, SignatureVerificationState.ERRORED,
					"Verifier subprocess failed: " + e.getMessage(), wu);
		}

		SignatureVerificationState verdict;
		SigningKeyData matchedKey = null;
		switch (rebomResult.verdict()) {
			case "VERIFIED":
				if (StringUtils.isNotBlank(rebomResult.matchedFingerprint())) {
					Optional<SigningKeyData> activeMatch = signingKeyService.findActiveByFingerprint(
							orgUuid, rebomResult.matchedFingerprint());
					if (activeMatch.isPresent()) {
						matchedKey = activeMatch.get();
						verdict = SignatureVerificationState.VERIFIED;
					} else {
						// Verifier said the cryptographic check passed against
						// some key in the trust store, but on the re-lookup
						// nothing matched — either the key was revoked between
						// trust-store build and lookup, or the fingerprint
						// normalisation drifted. Treat as KEY_REVOKED.
						verdict = SignatureVerificationState.KEY_REVOKED;
					}
				} else {
					verdict = SignatureVerificationState.ERRORED;
				}
				break;
			case "INVALID_SIGNATURE":
				verdict = SignatureVerificationState.INVALID_SIGNATURE;
				break;
			case "UNKNOWN_KEY":
				verdict = SignatureVerificationState.UNKNOWN_KEY;
				break;
			default:
				verdict = SignatureVerificationState.ERRORED;
				break;
		}

		// No post-verification WRONG_SIGNER check needed — the trust store
		// was pre-narrowed by resolveTrustScope so any VERIFIED result is
		// already scoped to the right owner. WRONG_SIGNER is kept on the
		// verdict enum as v2-reserved (image / attestation flows may need
		// attribution constraints decoupled from scope).

		SignatureVerificationData seed = new SignatureVerificationData();
		seed.setOrg(orgUuid);
		seed.setSubjectType(subjectType);
		seed.setSubjectUuid(subjectUuid);
		seed.setSignatureArtifactUuid(signatureArtifactUuid);
		seed.setSignedPayloadArtifactUuid(signedPayloadArtifactUuid);
		seed.setFormat(format);
		seed.setVerdict(verdict);
		if (matchedKey != null) {
			seed.setOwnerType(matchedKey.getOwnerType());
			seed.setOwnerUuid(matchedKey.getOwnerUuid());
			seed.setKeyFingerprint(matchedKey.getFingerprint());
		}
		seed.setDetails(rebomResult.details());
		return signatureVerificationService.persistVerdict(seed, wu);
	}

	/**
	 * Scope of enrolled keys to consult for one verification call.
	 * {@code keys} is the candidate set the cryptographic check runs
	 * against. {@code shortCircuitVerdict} is non-null when scope
	 * resolution already determined a verdict (empty agent trust store,
	 * malformed-but-present trailer) so the caller skips rebom.
	 */
	private static final class TrustScope {
		List<SigningKeyData> keys = List.of();
		SignatureVerificationState shortCircuitVerdict;
		String shortCircuitDetails;
	}

	/**
	 * Pick the right slice of enrolled keys for this subject. See class
	 * javadoc; rules:
	 * <ul>
	 *   <li>SCE with {@code sce.agent} set → narrow to that agent's
	 *       active keys of this format. Empty → short-circuit
	 *       {@code UNKNOWN_KEY}. (v2 will walk up to the root's
	 *       agentIdentity for SUB agents — see §12.8.)</li>
	 *   <li>SCE whose commit message contains {@code "ReARM-Agent:"} or
	 *       {@code "ReARM-Agentic-Session:"} but whose {@code agent}
	 *       field is null → trailer was malformed → short-circuit
	 *       {@code ERRORED}. (v1.1: replace this heuristic with a
	 *       {@code sce.trailerParseError} field written by PR 2.)</li>
	 *   <li>Everything else (SCE with no trailer, non-SCE subject) →
	 *       committer-only scope. AGENT keys are never used for
	 *       non-agentic commits.</li>
	 * </ul>
	 */
	private TrustScope resolveTrustScope(UUID orgUuid, SignatureSubjectType subjectType, UUID subjectUuid,
			SignatureFormat format) {
		TrustScope scope = new TrustScope();
		if (subjectType == SignatureSubjectType.SCE) {
			Optional<SourceCodeEntryData> osd = sourceCodeEntryService.getSourceCodeEntryData(subjectUuid);
			if (osd.isPresent()) {
				SourceCodeEntryData sced = osd.get();
				if (sced.getAgent() != null) {
					// Agentic narrow path.
					scope.keys = signingKeyService.listActiveByOwner(orgUuid,
							SigningKeyOwnerType.AGENT, sced.getAgent()).stream()
							.filter(k -> k.getFormat() == format)
							.toList();
					if (scope.keys.isEmpty()) {
						scope.shortCircuitVerdict = SignatureVerificationState.UNKNOWN_KEY;
						scope.shortCircuitDetails = "No active " + format + " keys enrolled for agent "
								+ sced.getAgent() + " — enrol via `rearm agent enrollkey` before signing";
					}
					return scope;
				}
				// No agent set — check for malformed trailer.
				String msg = sced.getCommitMessage();
				if (msg != null
						&& (msg.contains("ReARM-Agent:") || msg.contains("ReARM-Agentic-Session:"))) {
					scope.shortCircuitVerdict = SignatureVerificationState.ERRORED;
					scope.shortCircuitDetails = "Commit message carries ReARM-* trailer prefix "
							+ "but no resolved agent on the SCE — trailer is malformed. See "
							+ "ai-plans/agentic/README.md §6 for the canonical trailer format.";
					return scope;
				}
			}
		}
		// Committer-only scope (no trailer, or non-SCE subject).
		scope.keys = signingKeyService.listActiveByOrgAndOwnerType(orgUuid, SigningKeyOwnerType.COMMITTER).stream()
				.filter(k -> k.getFormat() == format)
				.toList();
		return scope;
	}

	private SignatureFormat resolveFormat(ArtifactData sigArt, String declaredFormat) throws RelizaException {
		if (StringUtils.isNotBlank(declaredFormat)) {
			return SignatureArtifactTags.formatFromTagValue(declaredFormat);
		}
		// Read from artifact tags — signatureFormat=SSH|GPG|X509.
		String fmt = sigArt.getTags() == null ? null : sigArt.getTags().stream()
				.filter(t -> SignatureArtifactTags.SIGNATURE_FORMAT.equals(t.key()))
				.map(t -> t.value())
				.findFirst()
				.orElse(null);
		if (StringUtils.isBlank(fmt)) {
			throw new RelizaException("Signature artifact has no signatureFormat tag — declare via input.format or attach the tag at upload time");
		}
		return SignatureArtifactTags.formatFromTagValue(fmt);
	}

	/**
	 * Concatenate enrolled keys into the per-format trust store wire
	 * format. The verifier subprocess gets passed the result as bytes.
	 */
	private String buildTrustStore(SignatureFormat format, List<SigningKeyData> keys) {
		StringBuilder sb = new StringBuilder();
		switch (format) {
			case SSH:
				// allowed_signers format — one line per principal,key pair.
				// See man 1 ssh-keygen "ALLOWED_SIGNERS FILE FORMAT".
				for (SigningKeyData k : keys) {
					String identity = StringUtils.defaultIfBlank(k.getIdentity(), "*");
					sb.append(identity).append(' ').append(k.getPubKey().trim()).append('\n');
				}
				break;
			case GPG:
				// Concatenated ASCII-armoured pubkeys — gpg imports them all
				// into a transient keyring inside the subprocess.
				for (SigningKeyData k : keys) {
					sb.append(k.getPubKey().trim()).append('\n');
				}
				break;
			default:
				break;
		}
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private byte[] downloadArtifactBytes(ArtifactData ad) throws Exception {
		Mono<ResponseEntity<byte[]>> mono = sharedArtifactService.downloadArtifact(ad);
		ResponseEntity<byte[]> resp = mono.block();
		if (resp == null || resp.getBody() == null) {
			throw new RelizaException("Empty response when downloading artifact " + ad.getUuid());
		}
		return resp.getBody();
	}

	private SignatureVerificationData persistTerminal(UUID orgUuid, SignatureSubjectType subjectType,
			UUID subjectUuid, UUID signatureArtifactUuid, UUID signedPayloadArtifactUuid,
			SignatureFormat format, SignatureVerificationState verdict, String details, WhoUpdated wu) throws RelizaException {
		SignatureVerificationData seed = new SignatureVerificationData();
		seed.setOrg(orgUuid);
		seed.setSubjectType(subjectType);
		seed.setSubjectUuid(subjectUuid);
		seed.setSignatureArtifactUuid(signatureArtifactUuid);
		seed.setSignedPayloadArtifactUuid(signedPayloadArtifactUuid);
		seed.setFormat(format);
		seed.setVerdict(verdict);
		seed.setDetails(details);
		return signatureVerificationService.persistVerdict(seed, wu);
	}

	@SuppressWarnings("unused")
	private static byte[] noopForUtils() { return Utils.OM.toString().getBytes(); }
}
