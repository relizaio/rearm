/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.tracker;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

/**
 * Trackers whose reference grammar this codebase knows.
 *
 * <p>This is the one table of hosted defaults; {@code Utils.expandTrackerShorthand} reads it rather
 * than keeping a second copy. Naming a provider here is not a statement about which git hosts ReARM
 * works with -- any host works, and one with no entry reads as {@link #GIT}. It is a statement about
 * which reference grammars and URL rules are known.
 */
public enum TrackerProvider {

	GITHUB(TrackerKind.REPOSITORY, "github.com", true),
	GITLAB(TrackerKind.REPOSITORY, "gitlab.com", true),
	BITBUCKET(TrackerKind.REPOSITORY, "bitbucket.org", true),
	CODEBERG(TrackerKind.REPOSITORY, "codeberg.org", true),
	/**
	 * Any other git host. No issue-tracker semantics beyond a numeric key, and the project is
	 * case-PRESERVING: an unknown host may be case-sensitive, so two casings may be two repositories.
	 */
	GIT(TrackerKind.REPOSITORY, null, false),
	/** Site host is required: a Jira project key is unique per site, not globally. */
	JIRA(TrackerKind.PROJECT, null, false),
	TRELLO(TrackerKind.PROJECT, "trello.com", false);

	/**
	 * REPOSITORY trackers hang off a repository and can receive a push; PROJECT trackers cannot,
	 * which is why repository scope and discovery scope are separate on a board.
	 */
	public enum TrackerKind { REPOSITORY, PROJECT }

	private final TrackerKind kind;
	private final String defaultHost;
	private final boolean caseInsensitiveProject;

	TrackerProvider(TrackerKind kind, String defaultHost, boolean caseInsensitiveProject) {
		this.kind = kind;
		this.defaultHost = defaultHost;
		this.caseInsensitiveProject = caseInsensitiveProject;
	}

	public TrackerKind kind() { return kind; }

	/** The host implied when a reference omits one, or null when the provider requires one. */
	public String defaultHost() { return defaultHost; }

	/**
	 * Whether this provider resolves owner and repository case-insensitively, which decides
	 * whether the canonical rendering folds the project's case.
	 */
	public boolean foldsProjectCase() { return caseInsensitiveProject; }

	public boolean requiresHost() { return null == defaultHost; }

	public static Optional<TrackerProvider> byName(String raw) {
		if (StringUtils.isBlank(raw)) return Optional.empty();
		return Stream.of(values())
				.filter(p -> p.name().equalsIgnoreCase(raw.trim()))
				.findFirst();
	}

	/** The provider a host belongs to, for reading a URL-form source written before the grammar. */
	public static TrackerProvider byHost(String host) {
		if (StringUtils.isBlank(host)) return GIT;
		String h = host.trim().toLowerCase(Locale.ROOT);
		return Stream.of(values())
				.filter(p -> null != p.defaultHost && p.defaultHost.equals(h))
				.findFirst()
				.orElse(GIT);
	}

	/** Lower-case name, as it appears in a reference string. */
	public String token() { return name().toLowerCase(Locale.ROOT); }
}
