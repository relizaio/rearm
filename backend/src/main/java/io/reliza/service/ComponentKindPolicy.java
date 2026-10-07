/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.DtrackFetchStatus;
import io.reliza.model.ReleaseData;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.ReleaseRepository;

/**
 * Which releases the vulnerability pipeline works on (task RD4-11). A board's document round carries
 * no SBOM and never will, so a BOARD_DOCUMENT component is not scanned: no SBOM reconcile is queued for its
 * releases, the metrics compute settles them without deriving anything, product rollups leave them
 * out, and {@code Release.metrics} answers {@link DtrackFetchStatus#NOT_APPLICABLE} for them.
 *
 * <p>Decided by kind rather than by "has a BOM": a software release without a BOM yet is
 * legitimately pending, and a document never will be. A release that carries a board document is
 * treated the same whatever its component's kind, because a board that predates the document map
 * publishes onto a GENERIC-kind legacy component (RD4-10) and its rounds are just as unscannable.
 *
 * <p>Reads the two repositories directly rather than the release and component services, so every
 * pipeline service can depend on it without a construction cycle. An unknown release or component
 * is scannable: the rule only ever takes work away from what is positively a document.
 */
@Service
public class ComponentKindPolicy {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ComponentRepository componentRepository;

	/** The one predicate: every kind is scanned but BOARD_DOCUMENT. No kind is GENERIC. */
	public static boolean isScannable(ComponentKind kind) {
		return ComponentKind.BOARD_DOCUMENT != kind;
	}

	/** The same predicate for a component as the API serves it. */
	public static boolean isScannable(ComponentData component) {
		return null == component || isScannable(component.getKind());
	}

	/** The kind as stored, for the native reads; an absent or unknown value is GENERIC. */
	static ComponentKind kindOf(String stored) {
		for (ComponentKind k : ComponentKind.values()) {
			if (k.name().equals(stored)) return k;
		}
		return ComponentKind.GENERIC;
	}

	public boolean isScannableComponent(UUID component) {
		if (null == component) return true;
		List<String> kinds = componentRepository.findKindOf(component);
		return kinds.isEmpty() || isScannable(kindOf(kinds.get(0)));
	}

	/** For a release already loaded: a board document never is, else its component decides. */
	public boolean isScannableRelease(ReleaseData rd) {
		if (null == rd) return true;
		if (null != rd.getDocument()) return false;
		return isScannableComponent(rd.getComponent());
	}

	/** For a release known by uuid only (the enqueue and dequeue paths): one primary-key read. */
	public boolean isScannableRelease(UUID release) {
		if (null == release) return true;
		List<Object[]> rows = releaseRepository.findScanFactsOf(release);
		if (rows.isEmpty()) return true;
		Object[] row = rows.get(0);
		if (Boolean.TRUE.equals(row[1])) return false;
		return isScannable(kindOf((String) row[0]));
	}

	/**
	 * What {@code Release.metrics} serves for a release that is not scanned: zero counts, no scan
	 * dates, and a fetch status of NOT_APPLICABLE, so a reader hides the scan state instead of
	 * showing it pending. Whatever an earlier version stored stays in the row; it is simply not
	 * served.
	 */
	public static DependencyTrackIntegration notApplicableMetrics() {
		DependencyTrackIntegration m = new DependencyTrackIntegration();
		m.setDtrackFetchStatus(DtrackFetchStatus.NOT_APPLICABLE);
		m.setDtrackProjectDeleted(null);
		m.setDtrackSubmissionFailed(null);
		m.setDtrackSubmissionAttempts(null);
		return m;
	}
}
