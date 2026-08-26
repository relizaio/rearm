/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * The output of an arm's pairing step: which predecessor BOM feeds which successor BOM, plus why the
 * rest were declined.
 *
 * <p>This type exists to split the ONE thing that genuinely differs per arm -- how you decide that
 * two BOMs are the same thing across a rebuild -- from everything that follows, which is identical
 * for every arm and was previously written out once per arm. That duplication was the generator of
 * this change's dominant defect: five separate times a fix landed on one arm and not its twin (the
 * seam itself, the seeded-vs-paired count, the duplicate-name guard, the ERROR-to-INFO log level,
 * and the no-op exclusion from the counters). With the tail shared, those fixes land everywhere by
 * construction.
 *
 * <p>{@code noBom} is deliberately separate from {@code bomCountAmbiguous}. "This side carries no
 * BOM at all" is an ordinary shape -- attestation-only or VEX-only artifacts, deliverables whose
 * SBOM hangs off a sibling -- and means there is simply nothing to do. "Several BOMs and no way to
 * choose" is a real declined pairing worth an operator's attention. Collapsing them (both were
 * {@code Optional.empty()} from a single sole-BOM lookup) put ordinary traffic into the counters an
 * operator reads, so healthy builds looked like failures and the numbers could not be trusted.
 * (There is no ERROR gate on this seam -- see design doc section 4.3.)
 *
 * @param paired the pairs to seed; may be empty
 * @param candidates how many successors were considered, the denominator for the rest
 * @param unpaired successors with no predecessor counterpart
 * @param purlConflict same name on both sides but demonstrably different components
 * @param bomCountAmbiguous several BOMs on one side with no way to choose -- a real decline
 * @param noBom no BOM on one side at all -- ordinary, nothing to carry, NOT a failure
 */
public record CarryForwardPairing(List<BomPair> paired, int candidates, int unpaired,
		int purlConflict, int bomCountAmbiguous, int noBom) {

	/** One predecessor BOM and the successor BOM that replaces it. */
	public record BomPair(UUID predecessorBom, UUID successorBom) {}

	public static final CarryForwardPairing NOTHING =
			new CarryForwardPairing(List.of(), 0, 0, 0, 0, 0);

}
