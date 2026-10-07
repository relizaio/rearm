/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A commit link for a self-hosted VCS is built from the stored repository URI by stripping the
 * scheme and credentials as text. It used to feed parts of the URI to {@code replaceFirst} as
 * regexes: {@code git+ssh://} never matched itself, credentials with a {@code +} stayed in the
 * link, and a hostile URI could backtrack without bound.
 */
class UtilsLinkifyCommitTest {

	private static final String COMMIT = "abc123";

	@Test
	void httpsUriKeepsItsSchemeAndPath() {
		assertEquals("https://git.example.com/org/repo/abc123", Utils.linkifyCommit("https://git.example.com/org/repo", COMMIT));
		assertEquals("https://git.example.com/org/repo/abc123", Utils.linkifyCommit("https://git.example.com/org/repo/", COMMIT));
		assertEquals("http://git.example.com:8080/org/repo/abc123", Utils.linkifyCommit("http://git.example.com:8080/org/repo", COMMIT));
		assertEquals("https://git.example.com/org/repo/abc123", Utils.linkifyCommit("git.example.com/org/repo", COMMIT));
	}

	@Test
	void azureLinksGoThroughCommit() {
		assertEquals("https://dev.azure.com/org/proj/_git/repo/commit/abc123",
				Utils.linkifyCommit("https://dev.azure.com/org/proj/_git/repo", COMMIT));
	}

	@Test
	void sshShapedUrisLoseTheUserButKeepWhatTheyHad() {
		assertEquals("ssh://git.example.com/org/repo/abc123", Utils.linkifyCommit("ssh://git@git.example.com/org/repo", COMMIT));
		assertEquals("https://git.example.com:org/repo/abc123", Utils.linkifyCommit("git@git.example.com:org/repo", COMMIT));
	}

	@Test
	void gitPlusSshNoLongerDoublesTheScheme() {
		// Was git+ssh://git+ssh://git@git.example.com/org/repo/abc123: "git+ssh://" as a regex
		// does not match the text "git+ssh://".
		assertEquals("git+ssh://git.example.com/org/repo/abc123",
				Utils.linkifyCommit("git+ssh://git@git.example.com/org/repo", COMMIT));
	}

	@Test
	void credentialsNeverReachTheLink() {
		assertEquals("https://git.example.com/org/repo/abc123", Utils.linkifyCommit("https://user:tok@git.example.com/org/repo", COMMIT));
		assertEquals("https://git.example.com/org/repo/abc123", Utils.linkifyCommit("https://user.name@git.example.com/org/repo", COMMIT));
		// Was left in place: "user+ci:t0k@" as a regex does not match its own text.
		assertEquals("https://git.example.com/org/repo/abc123", Utils.linkifyCommit("https://user+ci:t0k@git.example.com/org/repo", COMMIT));
	}

	/** {@code expected} null means no link: the caller shows the bare commit hash. */
	private static void assertNoCredential(String expected, String uri, String secret) {
		String link = Utils.linkifyCommit(uri, COMMIT);
		assertTrue(null == link || !link.contains(secret), "the credential leaked into " + link);
		assertEquals(expected, link);
	}

	@Test
	void credentialsBeforeTheFirstSlashAreStripped() {
		assertNoCredential("https://gitlab.corp/team/repo/abc123", "ci-bot@gitlab.corp/team/repo", "ci-bot");
		assertNoCredential("https://gitlab.corp/team/repo/abc123", "ci-bot:glpat-XXXX@gitlab.corp/team/repo", "glpat-XXXX");
		// An unencoded '@' in a password: everything up to the last '@' goes.
		assertNoCredential("https://gitlab.corp/team/repo/abc123", "https://ci-bot:p@ss@gitlab.corp/team/repo", "p@ss");
		String token = "t0k+(.*)[a]{2}$^|";
		assertNoCredential("https://gitlab.corp/team/repo/abc123", "https://ci-bot:" + token + "@gitlab.corp/team/repo", token);
		// A '?' or '\\' in the password ends the authority where a browser would: no link.
		assertNoCredential(null, "https://ci-bot:" + token + "?@gitlab.corp/team/repo", token);
		assertNoCredential(null, "https://ci-bot:" + token + "\\@gitlab.corp/team/repo", token);
	}

	@Test
	void theStoredFormWithCredentialsGetsNoLink() {
		// cleanVcsUri stores https://ci-bot:glpat-XXXX@gitlab.corp/team/repo as
		// ci-bot/glpat-XXXX@gitlab.corp/team/repo: the '@' is past the first '/' by then, and
		// nothing tells that '/' from one in the path.
		assertEquals("ci-bot/glpat-XXXX@gitlab.corp/team/repo",
				Utils.cleanVcsUri("https://ci-bot:glpat-XXXX@gitlab.corp/team/repo"));
		assertNoCredential(null, "ci-bot/glpat-XXXX@gitlab.corp/team/repo", "glpat-XXXX");
		assertNoCredential(null, "ci-bot/p@ss@gitlab.corp/team/repo", "p@ss");
		assertNoCredential(null, "ci-bot/t0k+(.*)[a]{2}$^|\\?@gitlab.corp/team/repo", "t0k");
	}

	@Test
	void credentialsHoldingAnAtThenASlashGetNoLink() {
		// Cut at the first '@', then at the last '@' before the next '/', these leaked: there is
		// no safe split.
		assertNoCredential(null, "jane@corp.com/glpat-SECRET@git.corp/team/repo", "SECRET");
		assertNoCredential(null, "https://ci:a@b/SECRET@git.corp/team/repo", "SECRET");
		assertNoCredential(null, "https://ci:a@b/SECRET@github.com/org/repo", "SECRET");
	}

	@Test
	void aPasswordHoldingASlashOrSchemeSeparatorGetsNoLink() {
		assertNoCredential(null, "https://ci-bot:a/SECRET@gitlab.corp/team/repo", "SECRET");
		assertNoCredential(null, "ci-bot/a/SECRET@gitlab.corp/team/repo", "SECRET");
		assertNoCredential(null, "https://ci-bot:x://SECRET@gitlab.corp/team/repo", "SECRET");
		assertNoCredential(null, "ci-bot/x://SECRET@gitlab.corp/team/repo", "SECRET");
		assertNoCredential(null, "https://ci-bot:p@a/SECRET@gitlab.corp/team/repo", "SECRET");
	}

	@Test
	void anAtSignInThePathGetsNoLinkRatherThanAnotherHost() {
		// Cut at its '@', this linked to https://evil.example/<commit>.
		assertNoCredential(null, "https://git.corp/grp/x@evil.example", "evil.example");
		assertNoCredential(null, "https://u:p@host/grp/re@po", "u:p");
		assertNoCredential(null, "git.corp/grp/x@evil.example", "evil.example");
	}

	@Test
	void theAuthorityAlsoEndsAtQueryFragmentBackslashAndWhitespace() {
		// A browser ends the host at any of these, so an '@' after one is not credentials: cut
		// there, these linked to evil.example.
		assertNoCredential(null, "https://git.corp?x@evil.example", "evil.example");
		assertNoCredential(null, "https://git.corp#@evil.example", "evil.example");
		assertNoCredential(null, "https://git.corp\\@evil.example", "evil.example");
		assertNoCredential(null, "https://git.corp @evil.example", "evil.example");
		assertNoCredential(null, "https://git.corp\t@evil.example/team/repo", "evil.example");
		assertNoCredential(null, "git.corp?@evil.example", "evil.example");
		assertNoCredential(null, "git.corp#x@evil.example/team/repo", "evil.example");
		assertNoCredential(null, "git.corp\\@evil.example/team/repo", "evil.example");
		// Credentials before any of them are still stripped.
		assertNoCredential("https://git.corp/team/repo?ref=main/abc123", "https://u:SECRET@git.corp/team/repo?ref=main",
				"SECRET");
	}

	@Test
	void providerLinksNeedTheProviderHostExactly() {
		assertEquals("https://github.com/org/repo/commit/abc123", Utils.linkifyCommit("https://github.com/Org/Repo", COMMIT));
		assertEquals("https://github.com/org/repo/commit/abc123", Utils.linkifyCommit("https://www.github.com/org/repo", COMMIT));
		assertEquals("https://github.com/org/repo/commit/abc123", Utils.linkifyCommit("github.com/org/repo", COMMIT));
		assertEquals("https://github.com/org/repo/commit/abc123", Utils.linkifyCommit("https://ci:tok@github.com/org/repo", COMMIT));
		assertEquals("https://gitlab.com/grp/repo/-/commit/abc123", Utils.linkifyCommit("https://gitlab.com/grp/repo", COMMIT));
		assertEquals("https://bitbucket.org/ws/repo/commits/abc123", Utils.linkifyCommit("https://bitbucket.org/ws/repo", COMMIT));
		// A look-alike host or a path that merely contains the provider is self-hosted.
		assertEquals("https://evilgithub.com/o/r/abc123", Utils.linkifyCommit("evilgithub.com/o/r", COMMIT));
		assertEquals("https://git.corp/mirror/github.com/o/r/abc123",
				Utils.linkifyCommit("git.corp/mirror/github.com/o/r", COMMIT));
		assertEquals("https://github.com.evil.example/o/r/abc123",
				Utils.linkifyCommit("https://github.com.evil.example/o/r", COMMIT));
	}

	@Test
	void aProviderHostWithNoRepositoryGetsNoLinkInsteadOfThrowing() {
		// Threw ArrayIndexOutOfBoundsException: nothing followed "github.com/".
		assertNull(Utils.linkifyCommit("https://github.com/", COMMIT));
		assertNull(Utils.linkifyCommit("HTTPS://github.com/", COMMIT));
		assertNull(Utils.linkifyCommit("gitlab.com/", COMMIT));
		// cleanVcsUri leaves an upper-case scheme in, as "HTTPS///github.com/": that also threw,
		// and is now an ordinary (useless, harmless) self-hosted link.
		assertEquals("HTTPS///github.com/", Utils.cleanVcsUri("HTTPS://github.com/"));
		assertEquals("https://HTTPS///github.com/abc123", Utils.linkifyCommit(Utils.cleanVcsUri("HTTPS://github.com/"), COMMIT));
	}

	@Test
	@Timeout(10)
	void aHostileUriIsJustText() {
		// The user-info part used to become a regex; (.*a){20} backtracks for minutes against the
		// rest of the URI.
		String hostile = "https://(.*a){20}@" + "a".repeat(40) + "!/";
		String hostileScheme = "(a+)+b://" + "a".repeat(40) + "/";
		long start = System.nanoTime();
		String link = Utils.linkifyCommit(hostile, COMMIT);
		String schemeLink = Utils.linkifyCommit(hostileScheme, COMMIT);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertEquals("https://" + "a".repeat(40) + "!/abc123", link);
		// Not a scheme, so not kept as one.
		assertEquals("https://(a+)+b://" + "a".repeat(40) + "/abc123", schemeLink);
	}
}
