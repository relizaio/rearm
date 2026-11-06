/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.exceptions.ArtifactRetainedException;
import io.reliza.model.ArtifactPin;

/**
 * The retention lock (task FDAW-5, B17; operator decision O9): a file a package version pins is
 * never archived, and never unbound or replaced on the release whose version pins it. Every path
 * that archives, unbinds or replaces an inventory or generated artifact asks here before it writes
 * anything; the answer comes from the optional Pro {@link ArtifactRetentionHook}, so on CE (no
 * hook) nothing is pinned and nothing is refused. One hook call per question, never one per artifact.
 */
@Service
public class ArtifactRetentionLock {

	static final String RETAINED = "a file a package version pins is retained";

	@Autowired(required = false)
	private ArtifactRetentionHook hook;

	/** The pins one question found; {@link #NONE} when there are none. */
	public record Retention(List<ArtifactPin> pins) {

		public static final Retention NONE = new Retention(List.of());

		public Retention {
			pins = List.copyOf(pins);
		}

		/** Whether some pin names {@code artifact}. */
		public boolean retains(UUID artifact) {
			return pins.stream().anyMatch(p -> p.artifact().equals(artifact));
		}

		/** The pins of {@code artifact} as a message names them: "KIND vN of release label", joined by ", ". */
		public String describe(UUID artifact) {
			return describePins(pins.stream().filter(p -> p.artifact().equals(artifact)).toList());
		}
	}

	private static String describePins(List<ArtifactPin> pins) {
		return pins.stream().map(p -> p.kind().name() + " v" + p.version() + " of release " + p.releaseLabel())
				.collect(Collectors.joining(", "));
	}

	/** The pins of {@code artifacts} in {@code org}, no refusal: {@link Retention#NONE} without a hook or artifacts. */
	public Retention pinsOf(UUID org, Collection<UUID> artifacts) {
		if (null == hook || null == artifacts || artifacts.isEmpty()) return Retention.NONE;
		List<ArtifactPin> pins = hook.pinsOf(org, artifacts);
		return null == pins || pins.isEmpty() ? Retention.NONE : new Retention(pins);
	}

	/**
	 * Refuses when any version of {@code org} pins {@code artifact}: an archive hides the row from
	 * every release, so a pin anywhere in the organisation retains it.
	 */
	public void assertNotArchivable(UUID org, UUID artifact) {
		if (null == artifact) return;
		Retention found = pinsOf(org, List.of(artifact));
		if (!found.pins().isEmpty()) throw refusal("archived", found.pins());
	}

	/**
	 * Refuses when a version of {@code release} pins any of {@code artifacts}; a pin of another
	 * release never blocks this release's own list. When it does not refuse it returns every pin it
	 * found (all of other releases), so the caller decides the archive of what it unbinds from this
	 * one answer.
	 */
	public Retention assertNotUnbindable(UUID org, UUID release, Collection<UUID> artifacts) {
		return assertNotUnbindable(org, release, artifacts, "removed from release " + release);
	}

	/** {@link #assertNotUnbindable(UUID, UUID, Collection)} for a replace: the message says replaced. */
	public Retention assertNotReplaceable(UUID org, UUID release, Collection<UUID> artifacts) {
		return assertNotUnbindable(org, release, artifacts, "replaced on release " + release);
	}

	private Retention assertNotUnbindable(UUID org, UUID release, Collection<UUID> artifacts, String action) {
		Retention found = pinsOf(org, artifacts);
		List<ArtifactPin> own = found.pins().stream().filter(p -> p.release().equals(release)).toList();
		if (!own.isEmpty()) throw refusal(action, own);
		return found;
	}

	/**
	 * One sentence per refused artifact, in the pins' order, joined by "; "; the pins of one
	 * artifact joined by ", ".
	 */
	static ArtifactRetainedException refusal(String action, List<ArtifactPin> pins) {
		Map<UUID, List<ArtifactPin>> byArtifact = new LinkedHashMap<>();
		for (ArtifactPin p : pins) byArtifact.computeIfAbsent(p.artifact(), a -> new ArrayList<>()).add(p);
		String message = byArtifact.entrySet().stream()
				.map(e -> "artifact " + e.getKey() + " cannot be " + action + ": it is pinned by package version "
						+ describePins(e.getValue()) + "; " + RETAINED)
				.collect(Collectors.joining("; "));
		return new ArtifactRetainedException(message, pins);
	}
}
