/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;

/**
 * Guards the idempotency signature used by {@code updateArtifactDti} to skip
 * redundant rewrites: the same vulnerability set yields the same signature
 * regardless of volatile fields (attributedAt), while a new CVE changes it.
 */
class SharedArtifactServiceFindingsSignatureTest {

	private static VulnerabilityDto vuln(String vulnId, ZonedDateTime attributedAt) {
		return new VulnerabilityDto("pkg:npm/x@1.0", vulnId, null, null, null, null, null,
				null, attributedAt, null, null, null, null, null);
	}

	private static DependencyTrackIntegration dtiWith(List<VulnerabilityDto> vulns) {
		DependencyTrackIntegration d = new DependencyTrackIntegration();
		d.setVulnerabilityDetails(vulns);
		return d;
	}

	@Test
	void sameFindingsSameSignatureIgnoringAttributedAt() {
		DependencyTrackIntegration a = dtiWith(List.of(vuln("CVE-1", ZonedDateTime.now())));
		DependencyTrackIntegration b = dtiWith(List.of(vuln("CVE-1", ZonedDateTime.now().minusDays(5))));
		assertEquals(SharedArtifactService.findingsSignature(a), SharedArtifactService.findingsSignature(b),
				"same vuln set must produce the same signature regardless of attributedAt");
	}

	@Test
	void newCveChangesSignature() {
		DependencyTrackIntegration a = dtiWith(List.of(vuln("CVE-1", null)));
		DependencyTrackIntegration b = dtiWith(List.of(vuln("CVE-1", null), vuln("CVE-2", null)));
		assertNotEquals(SharedArtifactService.findingsSignature(a), SharedArtifactService.findingsSignature(b),
				"a newly-added CVE must change the signature so the artifact is rewritten");
	}
}
