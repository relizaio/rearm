/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Map;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.SignatureVerificationData;
import io.reliza.model.WhoUpdated;

/**
 * Cryptographic signature verifier — shared across SAAS and CE
 * deployments. Implementation in {@link SignatureVerifierImpl}.
 *
 * <p>Orchestrates: pull signature + payload bytes from the artifact
 * store, build the format-appropriate trust store from enrolled
 * signing keys (scope narrowed from the SCE — see
 * ai-plans/agentic/README.md §12.6), call rebom-backend's GraphQL
 * verifySignature mutation, resolve the matched fingerprint to a
 * signing-key owner, and persist the verdict to
 * signature_verifications.
 *
 * <p>CE deployments get the same verification, persistence, and UI
 * verdict badges. CEL policy enforcement on top of the verdict is
 * SAAS-only (the CEL evaluators live in {@code service.saas}); CE
 * shows the badge but has no policy layer to react to it.
 */
public interface SignatureVerifier {
	SignatureVerificationData verify(Map<String, Object> input, WhoUpdated wu) throws RelizaException;
}
