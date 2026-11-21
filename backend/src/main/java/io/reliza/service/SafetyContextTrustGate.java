/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import io.reliza.model.IssuerClass;
import io.reliza.model.TrustAction;

/**
 * Trust gate for importing an externally authored safety-context record.
 *
 * <p>AUTO_ACCEPT means ReARM may ingest a matched record under the selected
 * trust policy. It never changes the source assertion's outcome, classification,
 * review, or approval state. Unknown or non-self issuers are staged by default.
 */
public final class SafetyContextTrustGate {
    private SafetyContextTrustGate() {}

    public static TrustAction effectiveAction(TrustAction requested, IssuerClass issuerClass) {
        if (requested == TrustAction.REJECT) return TrustAction.REJECT;
        if (requested == TrustAction.STAGE) return TrustAction.STAGE;
        if (requested == TrustAction.AUTO_ACCEPT && issuerClass == IssuerClass.SELF) {
            return TrustAction.AUTO_ACCEPT;
        }
        return TrustAction.STAGE;
    }
}
