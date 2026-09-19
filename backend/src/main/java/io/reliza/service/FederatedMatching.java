/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

	/** Case-insensitive glob: {@code *} is any run of characters, {@code ?} one character. */
	static boolean glob(String pattern, String value) {
		if (pattern == null || value == null) return false;
		StringBuilder re = new StringBuilder();
		for (char ch : pattern.trim().toCharArray()) {
			switch (ch) {
				case '*' -> re.append(".*");
				case '?' -> re.append('.');
				default -> re.append(Pattern.quote(String.valueOf(ch)));
			}
		}
		return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE).matcher(value).matches();
	}
}
