/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.reliza.model.dto.notifications.InstanceDeploymentItem;
import io.reliza.model.dto.UpdateStatus;

/**
 * Channel-agnostic rendering helpers for instance-deployment notifications, so
 * Slack / Teams / Email / Inbox / Sentinel stay consistent on the parts that
 * shouldn't diverge: the summary line and the semantic colour/tone. The
 * status indicator itself (emoji vs shortcode vs text tag) is left to each
 * formatter's native idiom.
 */
public final class InstanceDeploymentRenderSupport {

	private InstanceDeploymentRenderSupport() {}

	/** Semantic tone for the whole event; drives colour bars / accents. */
	public enum Tone { GOOD, WARNING, DANGER }

	/**
	 * DANGER on any error; GOOD when everything settled to a terminal-good state
	 * (converged / recovered); WARNING otherwise (undeployed, in-progress churn,
	 * or a mix).
	 */
	public static Tone tone(List<String> statuses) {
		// statuses are UpdateStatus.name() strings on the wire; compare against the
		// enum's own name() so a rename of the enum is a compile error, not a silent
		// no-op (matches summarize() below).
		String error = UpdateStatus.ERROR.name();
		String converged = UpdateStatus.CONVERGED.name();
		String recovered = UpdateStatus.RECOVERED.name();
		if (statuses != null && statuses.contains(error)) return Tone.DANGER;
		boolean allGood = statuses != null && !statuses.isEmpty()
				&& statuses.stream().allMatch(s -> converged.equals(s) || recovered.equals(s));
		return allGood ? Tone.GOOD : Tone.WARNING;
	}

	/** Hex colour for a tone (Slack attachment / Teams accent / email accent). */
	public static String colorHex(Tone tone) {
		return switch (tone) {
			case GOOD -> "#2eb67d";
			case DANGER -> "#e01e5a";
			case WARNING -> "#ecb22e";
		};
	}

	/**
	 * One-line takeaway, severity-ordered, e.g. "1 error, 2 converged". Empty for
	 * no items.
	 */
	public static String summarize(List<InstanceDeploymentItem> items) {
		if (items == null || items.isEmpty()) return "";
		Map<UpdateStatus, Integer> counts = new EnumMap<>(UpdateStatus.class);
		for (InstanceDeploymentItem it : items) {
			if (it != null && it.status() != null) counts.merge(it.status(), 1, Integer::sum);
		}
		UpdateStatus[] order = {
			UpdateStatus.ERROR, UpdateStatus.CONVERGED, UpdateStatus.RECOVERED,
			UpdateStatus.UNDEPLOYED, UpdateStatus.IN_PROGRESS, UpdateStatus.NEW
		};
		List<String> parts = new ArrayList<>();
		for (UpdateStatus s : order) {
			Integer c = counts.get(s);
			if (c != null && c > 0) parts.add(c + " " + s.name().toLowerCase().replace('_', ' '));
		}
		return String.join(", ", parts);
	}

	/** Plain-text version cell: "v1 -> v2" when it changed, else "v2", else empty. */
	public static String versionText(InstanceDeploymentItem item) {
		if (item == null) return "";
		String to = item.version();
		String from = item.fromVersion();
		if (to == null || to.isBlank()) return "";
		if (from != null && !from.isBlank()) return from + " -> " + to;
		return to;
	}
}
