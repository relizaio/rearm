/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

/**
 * Shared-edition hook to read a pull request's state from its tracker when no CI reported it here (task RD3-20):
 * boards whose PRs report to another instance, or a PR whose CI never ran. The CE backend has no implementation
 * bean, so the read is unavailable there. The SAAS backend reads GitHub through the organization's GitHub App
 * integration ({@code service.saas.GithubPullRequestTrackerReader}).
 *
 * <p>Wired with {@code @Autowired(required = false)}; callers treat an absent hook as a read that failed.
 */
public interface PullRequestTrackerHook {

	/**
	 * What the tracker said: {@code state} is OPEN, CLOSED (closed without merging) or MERGED and {@code source}
	 * names where it was read (for example "github.com"); or, when the read could not be made, {@code failure}
	 * says why and the state is null.
	 */
	record Read(String state, String source, String failure) {
		public static Read of(String state, String source) { return new Read(state, source, null); }
		public static Read failed(String why) { return new Read(null, null, why); }
		public boolean ok() { return null != state; }
	}

	/** Read the PR at {@code prUrl} for the organization, now. Never throws: a failure is a {@link Read}. */
	Read read(UUID orgUuid, String prUrl);
}
