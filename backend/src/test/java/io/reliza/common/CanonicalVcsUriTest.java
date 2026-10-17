/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Every way of writing one repository must reduce to one string.
 *
 * <p>This is not tidiness. Two references to the same repository that compare unequal make the
 * caller quietly do the wrong thing, and the failure is silent by construction: a membership check
 * that never matches finds nothing, and finding nothing looks exactly like there being nothing to
 * find. That is how the sign-off lock check came to be inert.
 */
class CanonicalVcsUriTest {

	@Test
	void everyFormOfOneRepositoryCanonicalisesTheSameWay() {
		// The forms a board, a CI job, an agent's git remote and a tracker source each produce.
		for (String form : List.of(
				"https://github.com/acme/docs",
				"http://github.com/acme/docs",
				"https://github.com/acme/docs.git",
				"git@github.com:acme/docs.git",
				"git@github.com:acme/docs",
				// ssh:// and git:// are the common shapes on self-hosted git, and the CLI sends
				// whatever the remote is configured as. The two canonicalisers have to agree
				// exactly or a publish is refused for naming the repository a different way.
				"ssh://git@github.com/acme/docs.git",
				"ssh://git@github.com/acme/docs",
				"git://github.com/acme/docs.git",
				"github:acme/docs",
				"  github:acme/docs  ")) {
			assertEquals("github.com/acme/docs", Utils.canonicalVcsUri(form),
					form + " must reduce to the same repository");
		}
	}

	@Test
	void anyGitHostWorksWithoutBeingKnown() {
		// Nothing about this is GitHub-specific. A self-hosted repository has no tracker shorthand
		// and must not need one.
		assertEquals("git.example.com/team/docs",
				Utils.canonicalVcsUri("https://git.example.com/team/docs"));
		assertEquals("git.example.com/team/docs",
				Utils.canonicalVcsUri("git@git.example.com:team/docs.git"));
		assertEquals("git.example.com/team/docs",
				Utils.canonicalVcsUri("ssh://git@git.example.com/team/docs.git"));
		assertEquals("git.example.com/team/docs",
				Utils.canonicalVcsUri("git://git.example.com/team/docs"));
		assertEquals("gitea.internal:3000/team/docs".replace(":3000", "/3000"),
				Utils.canonicalVcsUri("https://gitea.internal:3000/team/docs"));
	}

	@Test
	void theShorthandTableAcceptsBoardsItDoesNotRestrictThem() {
		assertEquals("gitlab.com/acme/docs", Utils.canonicalVcsUri("gitlab:acme/docs"));
		assertEquals("bitbucket.org/acme/docs", Utils.canonicalVcsUri("bitbucket:acme/docs"));
		assertEquals("codeberg.org/acme/docs", Utils.canonicalVcsUri("codeberg:acme/docs"));
		// An unknown prefix is not a shorthand; it is canonicalised as whatever URI it looks like,
		// rather than refused. The table exists to accept existing boards, not to gate new ones.
		assertEquals("mytracker/acme/docs", Utils.canonicalVcsUri("mytracker:acme/docs"));
	}

	@Test
	void anScpStyleHostIsNotMistakenForAShorthand() {
		// git@host:path and tracker:path are both "word colon path". The dot in the host is what
		// tells them apart; getting this wrong would mangle every ssh remote.
		assertEquals("github.com/acme/docs", Utils.canonicalVcsUri("git@github.com:acme/docs"));
		assertEquals("git.example.com/team/docs", Utils.canonicalVcsUri("git@git.example.com:team/docs"));
	}

	@Test
	void differentRepositoriesStillDiffer() {
		// The point of canonicalising is to make equal things equal, not to make everything equal.
		assertNotEquals(Utils.canonicalVcsUri("github:acme/docs"),
				Utils.canonicalVcsUri("github:acme/code"));
		assertNotEquals(Utils.canonicalVcsUri("github:acme/docs"),
				Utils.canonicalVcsUri("gitlab:acme/docs"));
	}

	@Test
	void theShorthandAndTheRealUriAgree() {
		// The specific equality the board membership rule turns on: a board whose source is a
		// tracker shorthand, and a documents repo given as the git URL an agent's remote reports.
		assertEquals(Utils.canonicalVcsUri("github:acme/widget-docs"),
				Utils.canonicalVcsUri("https://github.com/acme/widget-docs"));
		assertEquals(Utils.canonicalVcsUri("github:acme/widget-docs"),
				Utils.canonicalVcsUri("git@github.com:acme/widget-docs.git"));
	}

	@Test
	void blankAndNullSurvive() {
		assertEquals(null, Utils.canonicalVcsUri(null));
		assertEquals("", Utils.canonicalVcsUri(""));
	}
}
