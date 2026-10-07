/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.tracker;

import java.io.Serializable;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.tracker.TrackerProvider.TrackerKind;

/**
 * Where a board's work items come from.
 *
 * <p>Parsed from {@code provider:[host/]project}. One parser owns the form; nothing else splits on
 * {@code :} or {@code /}, because the two readers that did had already drifted -- task registration
 * matched sources by raw prefix while the documents-repository check canonicalised both sides, so
 * one board answered two ways about the same repository.
 *
 * @param provider which tracker
 * @param host never null: the provider default when the string omits one
 * @param project "owner/repo" (subgroups allowed) for a REPOSITORY tracker, a project key or board
 *        id for a PROJECT one, in canonical case for the provider
 * @param vcsRepository the repository row, for REPOSITORY trackers once resolved; null in stage 1
 *        and always null for PROJECT trackers
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrackerSource(
		@JsonProperty("provider") TrackerProvider provider,
		@JsonProperty("host") String host,
		@JsonProperty("project") String project,
		@JsonProperty("vcsRepository") UUID vcsRepository) implements Serializable {

	private static final long serialVersionUID = 20260920L;

	public TrackerSource {
		if (null != provider && TrackerKind.PROJECT == provider.kind() && null != vcsRepository) {
			throw new IllegalArgumentException(
					"A " + provider + " source has no repository: a project tracker cannot receive a push");
		}
	}

	/**
	 * Parse a source reference, or refuse.
	 *
	 * <p>Accepts the shorthand form and, leniently, a bare URL -- that is what boards wired before
	 * the grammar hold, and a stored row has to keep reading. A URL maps to its host's provider
	 * where one is known and to {@link TrackerProvider#GIT} otherwise.
	 */
	public static TrackerSource parse(String raw) throws RelizaException {
		if (StringUtils.isBlank(raw)) throw new RelizaException("A tracker source cannot be blank");
		String s = raw.trim();
		if (s.contains("#")) {
			throw new RelizaException("A source names a repository or project, not one item: " + raw
					+ ". The '#' form belongs on a task's externalRef.");
		}

		int colon = s.indexOf(':');
		String providerToken = colon > 0 ? s.substring(0, colon) : null;
		var known = TrackerProvider.byName(providerToken);
		// "git://host/path" names a scheme, not the GIT provider, so a provider token followed by
		// "//" is a URL.
		boolean providerPrefix = known.isPresent() && !s.startsWith(known.get().token() + "://");
		if (!providerPrefix) {
			if (looksLikeUri(s)) {
				// A board wired before this grammar holds a URL, and those have to keep reading.
				return fromUri(s);
			}
			// An unrecognised token is a typo or a provider we do not support. Reading it as a URL
			// would mint a GIT source with the token as its host -- "gitea:acme/widget" stored as
			// host "gitea", project "acme/widget" -- which parses, stores, and then matches nothing
			// a board is wired to. Refusing says which providers exist.
			throw new RelizaException("Unknown tracker provider '" + providerToken + "' in " + raw
					+ ". Known providers: " + Stream.of(TrackerProvider.values())
							.map(TrackerProvider::token).collect(Collectors.joining(", "))
					+ "; or write the repository URL.");
		}

		TrackerProvider provider = known.get();
		String rest = s.substring(colon + 1);
		if (StringUtils.isBlank(rest)) {
			throw new RelizaException("Source " + raw + " names " + provider.token() + " and nothing else");
		}
		String host = provider.defaultHost();
		String project = rest;
		int slash = rest.indexOf('/');
		String firstSegment = slash > 0 ? rest.substring(0, slash) : rest;
		// A host is present iff the first segment looks like a DNS name. "acme/widget" has no dot
		// in its first segment; "git.acme.com/team/repo" does.
		// A first segment with a dot is a host. A GitLab top-level group whose name contains a dot
		// reads as one too, and cannot be told apart by shape -- state the host explicitly in that
		// case (gitlab:gitlab.com/acme.io/repo), which the grammar already accepts.
		if (firstSegment.contains(".") && slash > 0) {
			host = firstSegment;
			project = rest.substring(slash + 1);
		}
		if (StringUtils.isBlank(host)) {
			throw new RelizaException("Source " + raw + " needs a host: a " + provider.token()
					+ " project key is unique per site, so the site is part of its identity"
					+ " (e.g. " + provider.token() + ":acme.atlassian.net/PLATFORM)");
		}
		if (StringUtils.isBlank(project)) {
			throw new RelizaException("Source " + raw + " names a host and no project");
		}
		if (TrackerKind.REPOSITORY == provider.kind() && !project.contains("/")) {
			throw new RelizaException("Source " + raw + " is a repository tracker, so its project is"
					+ " owner/repo. A first segment containing a dot is read as a host, so an owner"
					+ " whose name has one needs the host stated: "
					+ provider.token() + ":" + provider.defaultHost() + "/" + firstSegment + "/"
					+ project);
		}
		return new TrackerSource(provider, lowerHost(host), renderProject(provider, project), null);
	}

	/**
	 * Whether this string can be read as a repository URL rather than a provider reference.
	 *
	 * <p>Deliberately narrow: only a scheme, an scp-style {@code user@host:path}, or a bare
	 * {@code host/path} whose first segment carries a dot. Anything else with a colon is a
	 * provider token, and an unknown one is refused rather than guessed at.
	 */
	private static boolean looksLikeUri(String s) {
		if (s.contains("://") || s.contains("@")) return true;
		int slash = s.indexOf('/');
		String firstSegment = slash > 0 ? s.substring(0, slash) : s;
		return s.indexOf(':') < 0 && firstSegment.contains(".") && slash > 0;
	}

	/** Read a URL-form source: the lenient path for rows written before the grammar. */
	private static TrackerSource fromUri(String uri) throws RelizaException {
		String canonical = Utils.canonicalVcsUri(uri);
		int slash = canonical.indexOf('/');
		if (slash <= 0 || slash == canonical.length() - 1) {
			throw new RelizaException("Source " + uri + " is neither provider:project nor a repository URL");
		}
		String host = canonical.substring(0, slash);
		String project = canonical.substring(slash + 1);
		TrackerProvider provider = TrackerProvider.byHost(host);
		return new TrackerSource(provider, lowerHost(host), renderProject(provider, project), null);
	}

	/**
	 * The string form, rendered from the record.
	 *
	 * <p>{@code parse(canonical(x))} equals {@code x}. This rendering, not the text a caller
	 * happened to send, is what gets stored -- which is what makes a natural key on the string one
	 * row per repository rather than one per spelling.
	 */
	public String canonical() {
		boolean hostIsDefault = null != provider.defaultHost() && provider.defaultHost().equals(host);
		return provider.token() + ":" + (hostIsDefault ? "" : host + "/") + project;
	}

	/** The comparable repository form, for a REPOSITORY tracker: {@code host/project}, canonicalised. */
	public String canonicalRepositoryUri() {
		if (TrackerKind.REPOSITORY != provider.kind()) return null;
		return Utils.canonicalVcsUri(host + "/" + project);
	}

	/** Two sources name one thing when their provider, host and rendered project agree. */
	public boolean sameAs(TrackerSource other) {
		return null != other && provider == other.provider
				&& StringUtils.equals(host, other.host)
				&& StringUtils.equals(project, other.project);
	}

	static String lowerHost(String host) {
		return null == host ? null : host.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Case folding per provider, not per kind.
	 *
	 * <p>A kind-level rule breaks on Jira, whose keys are uppercase by definition, and on Trello,
	 * whose short links are case-sensitive tokens.
	 */
	static String renderProject(TrackerProvider provider, String project) {
		String p = project.trim();
		if (provider.foldsProjectCase()) return p.toLowerCase(Locale.ROOT);
		if (TrackerProvider.JIRA == provider) return p.toUpperCase(Locale.ROOT);
		return p;
	}
}
