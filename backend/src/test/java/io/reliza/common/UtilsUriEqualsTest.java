/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Utils#uriEquals}.
 *
 * <p>The method used to compare {@code uri1} against itself (copy-paste bug)
 * and always returned true, leaving the VCS mismatch check in
 * {@code SourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit} dead.
 * These tests pin both directions of the fixed contract: different
 * repositories compare unequal, while every spelling of the same repository
 * (http/https/ssh scheme, user@, git@, trailing .git, scp-style colon,
 * trailing slash, case) compares equal -- the latter is what keeps existing
 * addrelease flows, whose CI-supplied URIs rarely match the stored clean form
 * byte-for-byte, from tripping the mismatch warn once the check is live.
 */
class UtilsUriEqualsTest {

	private static final String CLEAN = "github.com/example/repo";

	@Test
	void differentRepositoriesAreNotEqual() {
		assertFalse(Utils.uriEquals(CLEAN, "github.com/example/other-repo"));
		assertFalse(Utils.uriEquals("https://github.com/example/repo", "https://gitlab.com/example/repo"));
	}

	@Test
	void sameRepositorySpellingsAreEqual() {
		assertTrue(Utils.uriEquals(CLEAN, CLEAN));
		assertTrue(Utils.uriEquals("https://github.com/example/repo", CLEAN));
		assertTrue(Utils.uriEquals("http://github.com/example/repo", CLEAN));
		assertTrue(Utils.uriEquals("https://github.com/example/repo.git", CLEAN));
		assertTrue(Utils.uriEquals("git@github.com:example/repo.git", CLEAN));
		assertTrue(Utils.uriEquals("https://user@github.com/example/repo.git", CLEAN));
		assertTrue(Utils.uriEquals("ssh://git@github.com/example/repo.git", CLEAN));
		assertTrue(Utils.uriEquals("github.com/example/repo/", CLEAN));
		assertTrue(Utils.uriEquals("GitHub.com/Example/Repo", CLEAN));
	}

	@Test
	void dirtySpellingsOfSameRepositoryAreEqualToEachOther() {
		assertTrue(Utils.uriEquals("git@github.com:example/repo.git", "https://github.com/example/repo.git"));
	}
}
