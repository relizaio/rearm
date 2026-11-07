/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanState;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanTally;

/**
 * Whether the vulnerability analysis a VDR or VEX reports on has happened (task FDAW-2, B2). Written
 * on every CycloneDX VDR and VEX as {@link VdrMetadataProperty#VDR_ANALYSIS_STATE}, so an empty
 * vulnerabilities list never reads as "no vulnerabilities" when nothing was scanned.
 *
 * <p>Derived per <b>release unit</b>: a leaf release, or a product's own BOMs when it has any. A
 * product is derived from its scannable children, never from its own stored firstScanned, which
 * mixes real scans with the no-BOM anchor (createdDate) of SBOM-less children. completedAt is only
 * ever a real first scan.
 *
 * <ul>
 *   <li>{@link #COMPLETED}: every release unit is analysed.</li>
 *   <li>{@link #PARTIAL}: some release units analysed, some carry no scannable SBOM; the counts say
 *       how many.</li>
 *   <li>{@link #NOT_ANALYSED}: nothing is analysed yet, or there is nothing to analyse; the
 *       {@link Reason} says which.</li>
 * </ul>
 */
public enum VdrAnalysisState {
	COMPLETED,
	PARTIAL,
	NOT_ANALYSED;

	/** Why a document is {@link VdrAnalysisState#NOT_ANALYSED}. */
	public enum Reason {
		/** A scannable SBOM still awaits its first scan; a scan will come. */
		SCAN_PENDING,
		/** No SBOM at all: nothing to analyse. */
		NO_SBOM,
		/** SBOMs exist but none is scannable (no matchable components): nothing to analyse. */
		SBOM_NOT_SCANNABLE
	}

	/**
	 * One release's own BOMs: the tally, the latest real artifact firstScanned among its scannable
	 * BOMs, and whether any scannable BOM lacks one.
	 */
	public record OwnBomFacts(BomScanTally tally, ZonedDateTime latestFirstScanned, boolean anyScannableUnscanned) {}

	/**
	 * The derivation: state; reason only when {@link VdrAnalysisState#NOT_ANALYSED}; completedAt only
	 * when {@link VdrAnalysisState#COMPLETED} or {@link VdrAnalysisState#PARTIAL}; the release-unit
	 * counts always.
	 */
	public record Derived(VdrAnalysisState state, Reason reason, ZonedDateTime completedAt,
			int analysedReleases, int notAnalysedReleases, int pendingReleases) {

		/** Every release unit counted: analysed, not analysed and pending. */
		public int totalReleases() {
			return analysedReleases + notAnalysedReleases + pendingReleases;
		}
	}

	/**
	 * A leaf release: (1) no scannable BOM -> NOT_ANALYSED (SBOM_NOT_SCANNABLE when it has BOMs that
	 * are not scannable, else NO_SBOM), whatever anchor firstScanned the metrics carry; (2) a
	 * firstScanned -> COMPLETED at it (real: a leaf's firstScanned is only an anchor when rule 1
	 * fires); (3) otherwise NOT_ANALYSED, SCAN_PENDING.
	 */
	public static Derived deriveLeaf(ReleaseMetricsDto metrics, BomScanTally tally) {
		Objects.requireNonNull(tally, "tally");
		if (tally.scannableBoms() == 0) {
			Reason reason = tally.notScannableBoms() > 0 ? Reason.SBOM_NOT_SCANNABLE : Reason.NO_SBOM;
			return new Derived(NOT_ANALYSED, reason, null, 0, 1, 0);
		}
		if (null != metrics && null != metrics.getFirstScanned()) {
			return new Derived(COMPLETED, null, metrics.getFirstScanned(), 1, 0, 0);
		}
		return new Derived(NOT_ANALYSED, Reason.SCAN_PENDING, null, 0, 0, 1);
	}

	/**
	 * A release with children: its own BOMs (null when it has none) as one release unit, plus every
	 * child's derivation. Any pending unit -> SCAN_PENDING; no analysed unit -> NOT_ANALYSED
	 * (SBOM_NOT_SCANNABLE when the own unit or a direct child says so, else NO_SBOM); some units not
	 * analysed -> PARTIAL; else COMPLETED. completedAt is the latest real scan among the analysed
	 * units.
	 */
	public static Derived combine(OwnBomFacts own, List<Derived> children) {
		int analysed = 0;
		int notAnalysed = 0;
		int pending = 0;
		ZonedDateTime completedAt = null;
		boolean notScannable = false;
		if (null != own && null != own.tally() && own.tally().state() != BomScanState.NO_BOMS) {
			if (own.tally().scannableBoms() > 0) {
				if (own.anyScannableUnscanned()) {
					pending++;
				} else {
					analysed++;
					completedAt = later(completedAt, own.latestFirstScanned());
				}
			} else {
				notAnalysed++;
				notScannable = true;
			}
		}
		for (Derived child : null == children ? List.<Derived>of() : children) {
			analysed += child.analysedReleases();
			notAnalysed += child.notAnalysedReleases();
			pending += child.pendingReleases();
			completedAt = later(completedAt, child.completedAt());
			if (child.reason() == Reason.SBOM_NOT_SCANNABLE) notScannable = true;
		}
		if (pending > 0) {
			return new Derived(NOT_ANALYSED, Reason.SCAN_PENDING, null, analysed, notAnalysed, pending);
		}
		if (analysed == 0) {
			return new Derived(NOT_ANALYSED, notScannable ? Reason.SBOM_NOT_SCANNABLE : Reason.NO_SBOM, null,
					analysed, notAnalysed, pending);
		}
		VdrAnalysisState state = notAnalysed > 0 ? PARTIAL : COMPLETED;
		return new Derived(state, null, completedAt, analysed, notAnalysed, pending);
	}

	private static ZonedDateTime later(ZonedDateTime a, ZonedDateTime b) {
		if (null == a) return b;
		if (null == b) return a;
		return b.isAfter(a) ? b : a;
	}
}
