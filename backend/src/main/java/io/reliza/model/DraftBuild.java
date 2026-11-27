/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A draft's last successful build (FDAW-1 D3). {@code fingerprintLines} are the sorted name=value lines
 * of {@code inputsFingerprint}, kept in the record and not on the wire; {@code componentRefs} is a join
 * recomputed from {@code componentKeys} at every use. {@code vendoredKeys} are the minted keys of the
 * pinned document's still-keyless vendored nodes (FDAW-15 3.8 (a)), record only, not on the wire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftBuild(ZonedDateTime builtAt, DraftActor requestedBy, String inputsFingerprint,
		List<String> fingerprintLines, List<UUID> closure, List<String> componentKeys,
		List<DraftComponentRef> componentRefs, ReleaseSbomRef releaseSbom, boolean releaseSbomNeedsBind,
		List<DraftPart> parts, SbomProfileRef sbomProfile, PackageSettingsRef settings,
		List<String> vendoredKeys) implements Serializable {

}
