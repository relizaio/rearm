/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.UUID;

import lombok.Data;

/**
 * The release SBOM head of one (release, SBOM profile name) as the service reads it (FDAW-1 D1): the
 * uuid is the CycloneDX serialNumber. Its record carries nothing yet; every field is a column.
 */
@Data
public class ReleaseSbomData {

	private UUID uuid;
	private UUID org;
	private UUID release;
	private String profileName;
	private UUID artifact;
	private int latestVersion;
	private ZonedDateTime createdDate;
	private ZonedDateTime lastUpdatedDate;

	public static ReleaseSbomData dataFromRecord(ReleaseSbom h) {
		if (h.getSchemaVersion() != 0) {
			throw new IllegalStateException("release SBOM schema version is " + h.getSchemaVersion()
					+ ", which is not currently supported");
		}
		ReleaseSbomData d = new ReleaseSbomData();
		d.setUuid(h.getUuid());
		d.setOrg(h.getOrg());
		d.setRelease(h.getRelease());
		d.setProfileName(h.getProfileName());
		d.setArtifact(h.getArtifact());
		d.setLatestVersion(h.getLatestVersion());
		d.setCreatedDate(h.getCreatedDate());
		d.setLastUpdatedDate(h.getLastUpdatedDate());
		return d;
	}
}
