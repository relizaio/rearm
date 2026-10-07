/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule.Provider;

/**
 * Pure matching of a verified identity token against a rule's matcher, and the provider-specific
 * reading of the token's claims into one shape. No I/O, so it is unit-tested on its own.
 */
public final class FederatedMatching {

	private FederatedMatching() {}

	/** The claims every provider maps into; missing ones are null. */
	public record IdentityClaims(Provider provider, String issuer, String subject, String owner, String ownerId,
			String repository, String repositoryId, String host, String ref, String refType, String sha,
			String workflowRef, String environment, String event, String actor, String runId, String runAttempt,
			String jti) {

		/** owner/name lower-cased: the natural key of an identity row within an org. */
		public String repositoryKey() {
			return repository == null ? null : repository.toLowerCase();
		}

		/** host/owner/name, the cleaned VCS URI form ReARM stores for the repository. */
		public String repositoryUri() {
			return repository == null || host == null ? null : (host + "/" + repository).toLowerCase();
		}

		/** The bare repository name without the owner. */
		public String repositoryName() {
			if (repository == null) return null;
			int slash = repository.indexOf('/');
			return slash < 0 ? repository : repository.substring(slash + 1);
		}
	}

	public static final String GITHUB_ISSUER = "https://token.actions.githubusercontent.com";

	/** GitHub Actions claims (github.com or GitHub Enterprise Server, whose issuer is https://HOST/_services/token). */
	public static IdentityClaims fromGitHub(String issuer, Map<String, Object> claims) {
		String host = "github.com";
		if (issuer != null && !GITHUB_ISSUER.equals(issuer)) {
			try {
				host = java.net.URI.create(issuer).getHost();
			} catch (RuntimeException e) {
				host = issuer;
			}
		}
		return new IdentityClaims(Provider.GITHUB_ACTIONS, issuer, str(claims, "sub"), str(claims, "repository_owner"),
				str(claims, "repository_owner_id"), str(claims, "repository"), str(claims, "repository_id"), host,
				str(claims, "ref"), str(claims, "ref_type"), str(claims, "sha"), str(claims, "workflow_ref"),
				str(claims, "environment"), str(claims, "event_name"), str(claims, "actor"), str(claims, "run_id"),
				str(claims, "run_attempt"), str(claims, "jti"));
	}

	private static String str(Map<String, Object> claims, String key) {
		Object v = claims == null ? null : claims.get(key);
		return v == null ? null : String.valueOf(v);
	}

	/** Does the matcher accept these claims? Empty lists constrain nothing. */
	public static boolean matches(FederatedMatcher m, IdentityClaims c) {
		if (m == null || c == null) return false;
		if (StringUtils.isBlank(m.getOwner()) || c.owner() == null || !m.getOwner().trim().equalsIgnoreCase(c.owner())) return false;
		if (c.repository() == null) return false;
		if (!m.getRepositories().isEmpty() && !anyRepositoryMatches(m.getRepositories(), c)) return false;
		if (!m.getExcludeRepositories().isEmpty() && anyRepositoryMatches(m.getExcludeRepositories(), c)) return false;
		if (!m.getRefs().isEmpty() && !anyRefMatches(m.getRefs(), c.ref())) return false;
		if (!m.getEnvironments().isEmpty() && !containsIgnoreCase(m.getEnvironments(), c.environment())) return false;
		if (!m.getWorkflows().isEmpty() && !anyWorkflowMatches(m.getWorkflows(), c.workflowRef())) return false;
		if (!m.getEvents().isEmpty() && !containsIgnoreCase(m.getEvents(), c.event())) return false;
		if (!m.getSubjects().isEmpty() && !anyGlobMatches(m.getSubjects(), c.subject())) return false;
		return true;
	}

	private static boolean anyRepositoryMatches(List<String> patterns, IdentityClaims c) {
		for (String p : patterns) {
			if (StringUtils.isBlank(p)) continue;
			if (glob(p, c.repository()) || glob(p, c.repositoryName())) return true;
		}
		return false;
	}

	private static boolean anyRefMatches(List<String> patterns, String ref) {
		if (ref == null) return false;
		for (String p : patterns) {
			if (StringUtils.isBlank(p)) continue;
			if (p.startsWith("refs/")) {
				if (glob(p, ref)) return true;
			} else if (glob("refs/heads/" + p, ref) || glob("refs/tags/" + p, ref)) {
				return true;
			}
		}
		return false;
	}

	private static boolean anyWorkflowMatches(List<String> patterns, String workflowRef) {
		if (workflowRef == null) return false;
		// owner/repo/.github/workflows/name.yml@refs/heads/main
		String path = workflowRef.contains("@") ? workflowRef.substring(0, workflowRef.indexOf('@')) : workflowRef;
		String file = path.substring(path.lastIndexOf('/') + 1);
		for (String p : patterns) {
			if (StringUtils.isBlank(p)) continue;
			if (glob(p, workflowRef) || glob(p, path) || glob(p, file) || path.toLowerCase().endsWith("/" + p.toLowerCase())) return true;
		}
		return false;
	}

	private static boolean anyGlobMatches(List<String> patterns, String value) {
		if (value == null) return false;
		for (String p : patterns) {
			if (StringUtils.isNotBlank(p) && glob(p, value)) return true;
		}
		return false;
	}

	private static boolean containsIgnoreCase(List<String> values, String value) {
		if (value == null) return false;
		for (String v : values) {
			if (v != null && v.trim().equalsIgnoreCase(value)) return true;
		}
		return false;
	}

	/**
	 * Longest glob a matcher list entry may hold. The longest claim a glob is written against is
	 * {@code sub}: {@code repo:} + a 39-character owner + {@code /} + a 100-character repository +
	 * {@code :ref:refs/heads/} + a branch name, a little over 400 characters even with a long branch,
	 * so an exact-value glob of any real claim fits.
	 */
	public static final int MAX_GLOB_LENGTH = 512;

	/**
	 * Most entries one matcher list may hold. Matching runs during the unauthenticated token
	 * exchange, for every rule naming the token's issuer whose owner matches, and costs up to
	 * entries x glob length x claim length; real lists name a handful of repositories or refs.
	 */
	public static final int MAX_LIST_ENTRIES = 64;

	/** Marks a {@code *} or {@code ?} in a compiled glob; real chars are never negative. */
	private static final int ANY_RUN = -1;
	private static final int ANY_ONE = -2;

	/**
	 * Case-insensitive glob: {@code *} is any run of characters, {@code ?} one character.
	 *
	 * <p>Exactly what the regex it replaces did ({@code *} -> {@code .*}, {@code ?} -> {@code .},
	 * every other character quoted, the pattern trimmed, matched whole with
	 * {@code Pattern.CASE_INSENSITIVE}): the whole value must match; case folds for ASCII letters
	 * only; {@code *} and {@code ?} never match a line terminator; {@code ?} is one code point of
	 * the value, so one supplementary character, while a supplementary character written in the
	 * pattern was quoted one half at a time and so never matches; there is no escape, so {@code *}
	 * and {@code ?} are always wildcards.
	 *
	 * <p>Not a regex because the glob is admin input and the value is a claim of a token anyone
	 * with a repository can mint: {@code *a*a*a...} backtracked exponentially. Here it is one pass
	 * over the value carrying a row of pattern positions, value length x pattern length steps at
	 * most.
	 */
	static boolean glob(String pattern, String value) {
		if (pattern == null || value == null) return false;
		// The pattern by char and the value by code point, as the regex read them: see above.
		int[] p = pattern.trim().chars().map(ch -> ch == '*' ? ANY_RUN : ch == '?' ? ANY_ONE : ch).toArray();
		// at[j]: the first j glob tokens match the value read so far.
		boolean[] at = new boolean[p.length + 1];
		boolean[] next = new boolean[p.length + 1];
		at[0] = true;
		for (int j = 1; j <= p.length && p[j - 1] == ANY_RUN; j++) at[j] = true;
		for (int i = 0; i < value.length(); ) {
			int c = value.codePointAt(i);
			i += Character.charCount(c);
			boolean any = false;
			next[0] = false;
			for (int j = 1; j <= p.length; j++) {
				int t = p[j - 1];
				boolean m;
				if (t == ANY_RUN) m = next[j - 1] || (at[j] && !isLineTerminator(c));
				else if (t == ANY_ONE) m = at[j - 1] && !isLineTerminator(c);
				else m = at[j - 1] && sameIgnoringAsciiCase(t, c);
				next[j] = m;
				any |= m;
			}
			if (!any) return false;
			boolean[] swap = at;
			at = next;
			next = swap;
		}
		return at[p.length];
	}

	/** What {@code .} does not match without DOTALL or UNIX_LINES. */
	private static boolean isLineTerminator(int c) {
		return c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029;
	}

	/** CASE_INSENSITIVE without UNICODE_CASE: only A-Z and a-z fold. */
	private static boolean sameIgnoringAsciiCase(int a, int b) {
		return a == b || (a < 128 && b < 128 && Character.toLowerCase(a) == Character.toLowerCase(b));
	}
}
