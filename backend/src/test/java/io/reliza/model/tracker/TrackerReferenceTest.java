/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.tracker.TrackerProvider.TrackerKind;

/**
 * The grammar and its canonical rendering.
 *
 * <p>The rendering is the part that matters most: it is what gets stored, and the
 * idempotent-registration key is a raw-string comparison on that. If two spellings of one issue
 * render differently, a tracker re-scan registers the same issue twice -- quietly, as two tasks
 * that look legitimate.
 */
class TrackerReferenceTest {

	@Test
	void theDocumentedFormParsesAndRendersToItself() throws RelizaException {
		TrackerRef r = TrackerRef.parse("github:acme/widget#42");
		assertEquals(TrackerProvider.GITHUB, r.source().provider());
		assertEquals("github.com", r.source().host(), "the hosted default is implied");
		assertEquals("acme/widget", r.source().project());
		assertEquals("42", r.key());
		assertEquals("github:acme/widget#42", r.canonical());
	}

	@Test
	void caseAndLeadingZerosFoldToOneKey() throws RelizaException {
		// The three spellings a re-scan can produce for one GitHub issue.
		assertEquals("github:acme/widget#42", TrackerRef.parse("github:acme/widget#42").canonical());
		assertEquals("github:acme/widget#42", TrackerRef.parse("GitHub:acme/widget#42").canonical());
		assertEquals("github:acme/widget#42", TrackerRef.parse("github:Acme/Widget#042").canonical());
	}

	@Test
	void aSelfHostedProviderKeepsItsHost() throws RelizaException {
		TrackerSource s = TrackerSource.parse("gitlab:git.acme.com/platform/api/core");
		assertEquals(TrackerProvider.GITLAB, s.provider());
		assertEquals("git.acme.com", s.host());
		assertEquals("platform/api/core", s.project(), "GitLab subgroups are part of the project");
		assertEquals("gitlab:git.acme.com/platform/api/core", s.canonical());
		// and it is NOT the same source as the hosted one with the same path
		assertTrue(!s.sameAs(TrackerSource.parse("gitlab:platform/api/core")));
	}

	@Test
	void anUnknownHostIsGitAndKeepsItsCase() throws RelizaException {
		TrackerSource s = TrackerSource.parse("git:Host.example/Team/Repo");
		assertEquals(TrackerProvider.GIT, s.provider());
		assertEquals("host.example", s.host(), "DNS is case-insensitive");
		assertEquals("Team/Repo", s.project(), "an unknown host may be case-sensitive");
	}

	@Test
	void jiraKeysAreUppercaseAndBelongToTheirProject() throws RelizaException {
		TrackerRef r = TrackerRef.parse("jira:Acme.Atlassian.net/platform#platform-7");
		assertEquals(TrackerProvider.JIRA, r.source().provider());
		assertEquals(TrackerKind.PROJECT, r.source().provider().kind());
		assertEquals("acme.atlassian.net", r.source().host());
		assertEquals("PLATFORM", r.source().project());
		assertEquals("PLATFORM-7", r.key());
		assertEquals("jira:acme.atlassian.net/PLATFORM#PLATFORM-7", r.canonical());

		RelizaException wrongProject = assertThrows(RelizaException.class,
				() -> TrackerRef.parse("jira:acme.atlassian.net/PLATFORM#BILLING-7"));
		assertTrue(wrongProject.getMessage().contains("PLATFORM-"), wrongProject.getMessage());
	}

	@Test
	void aJiraSourceWithoutASiteIsRefused() {
		RelizaException e = assertThrows(RelizaException.class, () -> TrackerSource.parse("jira:PLATFORM"));
		assertTrue(e.getMessage().contains("host"), e.getMessage());
	}

	@Test
	void trelloKeepsItsShortLinkCase() throws RelizaException {
		TrackerSource s = TrackerSource.parse("trello:aBcD1234");
		assertEquals("aBcD1234", s.project(), "board short links are case-sensitive tokens");
		assertEquals("trello:aBcD1234", s.canonical());
		assertEquals("trello:aBcD1234#17", TrackerRef.parse("trello:aBcD1234#17").canonical());
	}

	@Test
	void parseOfCanonicalIsTheIdentity() throws RelizaException {
		for (String form : new String[] {
				"github:acme/widget",
				"gitlab:git.acme.com/platform/api/core",
				"git:git.example.com/team/docs",
				"jira:acme.atlassian.net/PLATFORM",
				"trello:acme-roadmap" }) {
			TrackerSource once = TrackerSource.parse(form);
			assertEquals(once, TrackerSource.parse(once.canonical()), form);
		}
		for (String form : new String[] {
				"github:acme/widget#42",
				"jira:acme.atlassian.net/PLATFORM#PLATFORM-1234" }) {
			TrackerRef once = TrackerRef.parse(form);
			assertEquals(once, TrackerRef.parse(once.canonical()), form);
		}
	}

	@Test
	void aUrlFormSourceStillReads() throws RelizaException {
		// What a board wired before this grammar holds. It has to keep working, and it has to
		// resolve to the same source its shorthand does, or the board's scope silently changes.
		TrackerSource url = TrackerSource.parse("https://github.com/Acme/Widget");
		assertEquals(TrackerProvider.GITHUB, url.provider());
		assertEquals("acme/widget", url.project());
		assertTrue(url.sameAs(TrackerSource.parse("github:acme/widget")));
		assertEquals(url.canonicalRepositoryUri(),
				TrackerSource.parse("git@github.com:acme/widget.git").canonicalRepositoryUri());
	}

	@Test
	void aProjectTrackerNamesNoRepository() throws RelizaException {
		assertNull(TrackerSource.parse("jira:acme.atlassian.net/PLATFORM").canonicalRepositoryUri(),
				"a Jira project cannot receive a push, so it contributes nothing to repository scope");
		assertEquals("github.com/acme/widget",
				TrackerSource.parse("github:acme/widget").canonicalRepositoryUri());
	}

	@Test
	void whatDoesNotParseIsRefusedWithTheForm() {
		assertTrue(assertThrows(RelizaException.class,
				() -> TrackerRef.parse("github:acme/widget")).getMessage().contains("provider:"));
		assertTrue(assertThrows(RelizaException.class,
				() -> TrackerRef.parse("github:acme/widget#")).getMessage().contains("no item key"));
		assertTrue(assertThrows(RelizaException.class,
				() -> TrackerRef.parse("github:acme/widget#abc")).getMessage().contains("digits"));
		assertThrows(RelizaException.class, () -> TrackerSource.parse("github:widget"));
		assertThrows(RelizaException.class, () -> TrackerSource.parse("github:acme/widget#42"));
		assertThrows(RelizaException.class, () -> TrackerSource.parse("  "));
	}

	@Test
	void anUnknownProviderIsRefusedRatherThanReadAsAUrl() {
		// gitea:acme/widget used to canonicalise to a GIT source with host "gitea" and project
		// "acme/widget": it parsed, it stored, and it matched nothing a board was wired to.
		RelizaException e = assertThrows(RelizaException.class,
				() -> TrackerSource.parse("gitea:acme/widget"));
		assertTrue(e.getMessage().contains("Unknown tracker provider"), e.getMessage());
		assertTrue(e.getMessage().contains("github"), "the refusal lists what is known: " + e.getMessage());

		assertThrows(RelizaException.class, () -> TrackerSource.parse("githubb:acme/widget"));
		assertThrows(RelizaException.class, () -> TrackerRef.parse("gitea:acme/widget#42"));
	}

	@Test
	void urlFormsStillReachTheLenientPath() throws RelizaException {
		// The forms a board can legitimately hold, none of which carry a provider token.
		assertEquals(TrackerProvider.GITHUB, TrackerSource.parse("https://github.com/acme/widget").provider());
		assertEquals(TrackerProvider.GITHUB, TrackerSource.parse("git@github.com:acme/widget.git").provider());
		assertEquals(TrackerProvider.GITHUB, TrackerSource.parse("github.com/acme/widget").provider());
		assertEquals(TrackerProvider.GIT, TrackerSource.parse("git://git.example.com/team/docs").provider(),
				"a git:// URL is a scheme, not the GIT provider token");
		assertEquals("git.example.com", TrackerSource.parse("git://git.example.com/team/docs").host());
	}

	@Test
	void anAbsurdlyLongKeyIsRefusedAsAReference() {
		String key = "1".repeat(40);
		RelizaException e = assertThrows(RelizaException.class,
				() -> TrackerRef.parse("github:acme/widget#" + key));
		assertTrue(e.getMessage().contains("40-digit"), e.getMessage());
		// and the same for the numeric half of a Jira key, which parses the same way
		assertThrows(RelizaException.class,
				() -> TrackerRef.parse("jira:acme.atlassian.net/PLATFORM#PLATFORM-" + key));
	}

	@Test
	void aGitlabGroupWithADotNeedsItsHostStated() throws RelizaException {
		// A first segment with a dot is read as a host, and a group whose name has one is
		// indistinguishable by shape. Two segments refuse, and the refusal says how to say it:
		RelizaException e = assertThrows(RelizaException.class,
				() -> TrackerSource.parse("gitlab:acme.io/repo"));
		assertTrue(e.getMessage().contains("gitlab:gitlab.com/acme.io/repo"), e.getMessage());

		// Three segments parse -- wrongly, as host acme.io -- which is the case the explicit host
		// form exists for. Nothing in the grammar can tell these apart.
		assertEquals("acme.io", TrackerSource.parse("gitlab:acme.io/group/repo").host());

		TrackerSource explicit = TrackerSource.parse("gitlab:gitlab.com/acme.io/repo");
		assertEquals("gitlab.com", explicit.host());
		assertEquals("acme.io/repo", explicit.project());
	}
}
