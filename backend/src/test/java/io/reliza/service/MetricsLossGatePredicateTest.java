/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;

/**
 * Pins the predicate that decides whether a metrics recompute pages the operator.
 *
 * <p>It has been wrong once already, in the direction that matters: it gated on "fewer findings than
 * before", which is true of ordinary remediation, of a VEX suppression, and of the alias/dedup collapse
 * that runs on the same compute. Measured against production audit history, that version would have fired
 * 4544 times without once catching the shape it exists for. On an instance whose only alerting channel is
 * ERROR, widening it back is not a cosmetic regression.
 *
 * <p>Plain unit test, no Spring: both members are package-private and take values, which is the same shape
 * {@code RepairCauseClassificationTest} uses for the sweep's verdict.
 */
class MetricsLossGatePredicateTest {

	private final ReleaseMetricsComputeService svc = new ReleaseMetricsComputeService();

	private static ReleaseMetricsDto metrics(int vulns, int weaks) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		LinkedList<VulnerabilityDto> vs = new LinkedList<>();
		for (int i = 0; i < vulns; i++) {
			vs.add(new VulnerabilityDto("pkg:npm/x@1.0.0", "CVE-2026-" + i, VulnerabilitySeverity.HIGH,
					Set.of(), Set.of(), Set.of(), null, null, ZonedDateTime.now(), null, null, null,
					null, null, false));
		}
		m.setVulnerabilityDetails(vs);
		LinkedList<WeaknessDto> ws = new LinkedList<>();
		for (int i = 0; i < weaks; i++) {
			ws.add(new WeaknessDto("CWE-" + i, "rule-1", "src/Foo.java:10", "fp-" + i,
					VulnerabilitySeverity.HIGH, Set.of(), null, null, ZonedDateTime.now()));
		}
		m.setWeaknessDetails(ws);
		return m;
	}

	@Test
	void everythingGoneFires() {
		assertTrue(svc.isTotalFindingCollapse(6, 0),
				"a release that had findings and now has none is the shape that flaps, and the flap is what "
				+ "makes the live emit misread the recovery as a first scan");
	}

	@Test
	void ordinaryRemediationDoesNotFire() {
		assertFalse(svc.isTotalFindingCollapse(9, 6),
				"a partial drop is a fix landing, a VEX suppression, or the alias/dedup collapse on this "
				+ "same compute. Paging on it is how the only alerting channel gets ignored -- the earlier "
				+ "version of this predicate did exactly that and never once caught the real shape");
	}

	@Test
	void aReleaseThatNeverHadFindingsDoesNotFire() {
		assertFalse(svc.isTotalFindingCollapse(0, 0), "nothing to lose");
		assertFalse(svc.isTotalFindingCollapse(0, 5), "gaining findings is not a loss");
	}

	@Test
	void countFindingsSumsAllThreeListsAndToleratesNulls() {
		assertEquals(0, svc.countFindings(null), "a release with no metrics yet has nothing to lose");
		assertEquals(0, svc.countFindings(new ReleaseMetricsDto()),
				"a fresh dto leaves every detail list null; that must read as zero, not throw");
		assertEquals(5, svc.countFindings(metrics(3, 2)),
				"weaknesses count too -- the release-level probe includes them, and omitting them here would "
				+ "let a weakness-only wipe pass the gate unreported");
	}

	@Test
	void weaknessOnlyWipeStillCountsAsTotalCollapse() {
		assertTrue(svc.isTotalFindingCollapse(svc.countFindings(metrics(0, 4)),
						svc.countFindings(metrics(0, 0))),
				"a release whose findings are all weaknesses can still be wiped, and it must page like any "
				+ "other total collapse");
	}

	@Test
	void listsPresentButEmptyReadAsZero() {
		assertEquals(0, svc.countFindings(metrics(0, 0)),
				"present-but-empty lists must read as zero, not as their own presence -- the gate compares "
				+ "counts, so a non-zero here would silently disable the whole probe");
	}
}
