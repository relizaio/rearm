/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.service.FederatedMatching.IdentityClaims;

public class FederatedMatchingTest {

	private static IdentityClaims github(String repo, String ref, String env, String workflowRef, String event) {
		Map<String, Object> claims = new java.util.HashMap<>();
		claims.put("sub", "repo:" + repo + ":ref:" + ref);
		claims.put("repository", repo);
		claims.put("repository_owner", repo.substring(0, repo.indexOf('/')));
		claims.put("repository_id", "123");
		claims.put("repository_owner_id", "77");
		claims.put("ref", ref);
		claims.put("workflow_ref", workflowRef);
		claims.put("environment", env);
		claims.put("event_name", event);
		claims.put("actor", "octocat");
		claims.put("run_id", "9");
		claims.put("jti", "j1");
		return FederatedMatching.fromGitHub(FederatedMatching.GITHUB_ISSUER, claims);
	}

	private static FederatedMatcher owner(String owner) {
		FederatedMatcher m = new FederatedMatcher();
		m.setOwner(owner);
		return m;
	}

	@Test
	void ownerWideRuleAcceptsAnyRepositoryOfTheOwnerOnly() {
		IdentityClaims c = github("relizaio/rearm-cli", "refs/heads/main", "", "relizaio/rearm-cli/.github/workflows/build.yml@refs/heads/main", "push");
		assertTrue(FederatedMatching.matches(owner("RelizaIO"), c), "owner compares case-insensitively");
		assertFalse(FederatedMatching.matches(owner("someone-else"), c));
	}

	@Test
	void repositoryGlobsMatchBareNameOrFullName() {
		IdentityClaims c = github("relizaio/rearm-cli", "refs/heads/main", "", "relizaio/rearm-cli/.github/workflows/build.yml@refs/heads/main", "push");
		FederatedMatcher m = owner("relizaio");
		m.setRepositories(List.of("rearm-*"));
		assertTrue(FederatedMatching.matches(m, c));
		m.setRepositories(List.of("relizaio/rearm-cli"));
		assertTrue(FederatedMatching.matches(m, c));
		m.setRepositories(List.of("other"));
		assertFalse(FederatedMatching.matches(m, c));
		m.setRepositories(List.of("rearm-*"));
		m.setExcludeRepositories(List.of("rearm-cli"));
		assertFalse(FederatedMatching.matches(m, c), "exclusions win");
	}

	@Test
	void refsEnvironmentsWorkflowsAndEventsConstrain() {
		IdentityClaims main = github("relizaio/rearm", "refs/heads/main", "prod", "relizaio/rearm/.github/workflows/release.yml@refs/heads/main", "push");
		IdentityClaims tag = github("relizaio/rearm", "refs/tags/v1.2.0", "", "relizaio/rearm/.github/workflows/release.yml@refs/tags/v1.2.0", "push");
		FederatedMatcher m = owner("relizaio");
		m.setRefs(List.of("main"));
		assertTrue(FederatedMatching.matches(m, main), "bare ref pattern matches heads");
		assertFalse(FederatedMatching.matches(m, tag));
		m.setRefs(List.of("refs/tags/v*"));
		assertTrue(FederatedMatching.matches(m, tag));
		assertFalse(FederatedMatching.matches(m, main));
		m.setRefs(List.of());
		m.setEnvironments(List.of("Prod"));
		assertTrue(FederatedMatching.matches(m, main));
		assertFalse(FederatedMatching.matches(m, tag), "no environment claim on the tag build");
		m.setEnvironments(List.of());
		m.setWorkflows(List.of("release.yml"));
		assertTrue(FederatedMatching.matches(m, main));
		m.setWorkflows(List.of(".github/workflows/other.yml"));
		assertFalse(FederatedMatching.matches(m, main));
		m.setWorkflows(List.of());
		m.setEvents(List.of("workflow_dispatch"));
		assertFalse(FederatedMatching.matches(m, main));
		m.setEvents(List.of("push"));
		assertTrue(FederatedMatching.matches(m, main));
	}

	@Test
	void subjectGlobIsAnEscapeHatch() {
		IdentityClaims c = github("relizaio/rearm", "refs/heads/main", "", "x@y", "push");
		FederatedMatcher m = owner("relizaio");
		m.setSubjects(List.of("repo:relizaio/*:ref:refs/heads/main"));
		assertTrue(FederatedMatching.matches(m, c));
		m.setSubjects(List.of("repo:relizaio/*:environment:*"));
		assertFalse(FederatedMatching.matches(m, c));
	}

	@Test
	void githubClaimsMapToRepositoryUriPerHost() {
		IdentityClaims c = github("RelizaIO/ReARM", "refs/heads/main", "", "x@y", "push");
		assertEquals("github.com/relizaio/rearm", c.repositoryUri());
		assertEquals("relizaio/rearm", c.repositoryKey());
		assertEquals("ReARM", c.repositoryName());
		IdentityClaims ghes = FederatedMatching.fromGitHub("https://ghe.example.com/_services/token", Map.of("repository", "team/app", "repository_owner", "team"));
		assertEquals("ghe.example.com/team/app", ghes.repositoryUri());
	}

	/**
	 * The regex glob() used to build, kept here as the oracle: the replacement must answer exactly
	 * as it did, for every pattern.
	 */
	private static boolean regexGlob(String pattern, String value) {
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

	private static void assertGlob(boolean expected, String pattern, String value) {
		assertEquals(expected, regexGlob(pattern, value), "the oracle disagrees with the pinned case: '"
				+ pattern + "' vs '" + value + "'");
		assertEquals(expected, FederatedMatching.glob(pattern, value), "'" + pattern + "' vs '" + value + "'");
	}

	@Test
	void globMatchesTheWholeValue() {
		assertGlob(true, "rearm-*", "rearm-cli");
		assertGlob(false, "rearm", "rearm-cli");
		assertGlob(false, "cli", "rearm-cli");
		assertGlob(true, "*cli", "rearm-cli");
		assertGlob(true, "*", "");
		assertGlob(true, "", "");
		assertGlob(false, "", "x");
	}

	@Test
	void globStarSpansAnyRunIncludingSlashAndColonButNotALineBreak() {
		assertGlob(true, "repo:relizaio/*", "repo:relizaio/rearm:ref:refs/heads/main");
		assertGlob(true, "a*b", "ab");
		assertGlob(true, "a**b", "a/x:y/b");
		assertGlob(false, "a*b", "a\nb");
		assertGlob(false, "a*b", "a\rb");
		assertGlob(false, "a*b", "a\u0085b");
		assertGlob(false, "a*b", "a\u2028b");
		assertGlob(false, "a*b", "a\u2029b");
		assertGlob(true, "a\nb", "a\nb");
	}

	@Test
	void globQuestionMarkIsExactlyOneCodePointButNotALineBreak() {
		assertGlob(true, "v?", "v1");
		assertGlob(false, "v?", "v");
		assertGlob(false, "v?", "v12");
		assertGlob(false, "v?", "v\n");
		// A supplementary character is one code point, two chars.
		assertGlob(true, "v?", "v\uD83D\uDE00");
		assertGlob(false, "v??", "v\uD83D\uDE00");
		// Written in the pattern, a supplementary character never matched: the regex quoted each
		// half on its own and compared the halves against the value's whole code point.
		assertGlob(false, "v\uD83D\uDE00", "v\uD83D\uDE00");
		assertGlob(false, "*\uD83D\uDE00", "x\uD83D\uDE00");
		assertGlob(true, "a\uD83D", "a\uD83D");
	}

	@Test
	void globFoldsCaseForAsciiLettersOnly() {
		assertGlob(true, "RelizaIO/*", "relizaio/rearm");
		assertGlob(true, "relizaio/*", "RELIZAIO/REARM");
		// CASE_INSENSITIVE without UNICODE_CASE: e-acute, dotted I, sharp s and the Kelvin sign
		// do not fold.
		assertGlob(false, "\u00e9", "\u00c9");
		assertGlob(true, "\u00e9", "\u00e9");
		assertGlob(false, "i", "\u0130");
		assertGlob(false, "k", "\u212a");
		assertGlob(false, "ss", "\u00df");
	}

	@Test
	void globHasNoEscapesAndQuotesEveryOtherCharacter() {
		// Regex metacharacters are literals; * and ? are always wildcards.
		assertGlob(true, "a.b", "a.b");
		assertGlob(false, "a.b", "axb");
		assertGlob(true, "(x)+[y]{1}$^|\\", "(x)+[y]{1}$^|\\");
		assertGlob(true, "a\\*", "a\\anything");
		assertGlob(true, "a\\?", "a\\x");
		assertGlob(true, "\\Qa\\E", "\\Qa\\E");
	}

	@Test
	void globTrimsThePatternButNotTheValue() {
		assertGlob(true, "  main  ", "main");
		assertGlob(false, "main", " main");
		assertGlob(false, "main", "main ");
		assertGlob(false, null, "main");
		assertGlob(false, "main", null);
	}

	@Test
	void globAgreesWithTheRegexItReplacedOnRandomInput() {
		// Small alphabet so patterns and values collide often; includes the case pair, a line
		// break, a non-ASCII letter, a surrogate pair and the regex metacharacters.
		String[] alphabet = {"a", "A", "b", "/", ":", ".", "*", "?", "\n", "\u00e9", "\uD83D\uDE00", "(", "\\", " "};
		Random rnd = new Random(20261002L);
		for (int n = 0; n < 20_000; n++) {
			String pattern = randomString(rnd, alphabet, 6);
			String value = randomString(rnd, alphabet, 8).replace("*", "").replace("?", "");
			assertEquals(regexGlob(pattern, value), FederatedMatching.glob(pattern, value),
					"'" + pattern + "' vs '" + value + "'");
		}
	}

	private static String randomString(Random rnd, String[] alphabet, int maxLen) {
		StringBuilder sb = new StringBuilder();
		int len = rnd.nextInt(maxLen + 1);
		for (int i = 0; i < len; i++) sb.append(alphabet[rnd.nextInt(alphabet.length)]);
		return sb.toString();
	}

	@Test
	@Timeout(10)
	void aBacktrackingGlobAnswersFast() {
		// As a regex, *a*a*a... against a long run of a's that does not end right backtracked
		// exponentially -- during the token exchange, against a claim the caller chose.
		String glob = "*a".repeat(30) + "!";
		String claim = "a".repeat(400);
		long start = System.nanoTime();
		boolean stars = FederatedMatching.glob(glob, claim);
		boolean regexShaped = FederatedMatching.glob("(.*a){20}", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!");
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
		assertFalse(stars);
		assertFalse(regexShaped);
	}

	@Test
	void trustRulesRefuseOverlongGlobsAndOverlongLists() throws RelizaException {
		FederatedTrustRuleService.checkMatcherList("refs", List.of("x".repeat(FederatedMatching.MAX_GLOB_LENGTH)), List.of());
		FederatedTrustRuleService.checkMatcherList("refs", Collections.nCopies(FederatedMatching.MAX_LIST_ENTRIES, "main"),
				List.of());
		RelizaException tooLong = assertThrows(RelizaException.class, () -> FederatedTrustRuleService
				.checkMatcherList("subjects", List.of("x".repeat(FederatedMatching.MAX_GLOB_LENGTH + 1)), List.of()));
		assertEquals("Matcher subjects entry exceeds " + FederatedMatching.MAX_GLOB_LENGTH + " characters",
				tooLong.getMessage());
		RelizaException tooMany = assertThrows(RelizaException.class, () -> FederatedTrustRuleService
				.checkMatcherList("repositories", Collections.nCopies(FederatedMatching.MAX_LIST_ENTRIES + 1, "x"), List.of()));
		assertEquals("Matcher repositories holds " + (FederatedMatching.MAX_LIST_ENTRIES + 1) + " entries; at most "
				+ FederatedMatching.MAX_LIST_ENTRIES + " are allowed", tooMany.getMessage());
	}

	@Test
	void legacyOverCapEntriesDoNotBlockAnUnrelatedEdit() throws RelizaException {
		String overCap = "x".repeat(FederatedMatching.MAX_GLOB_LENGTH + 1);
		List<String> storedRepos = new ArrayList<>(Collections.nCopies(FederatedMatching.MAX_LIST_ENTRIES + 2, "r"));
		// An unchanged over-cap glob, and an over-cap list that does not grow, save.
		FederatedTrustRuleService.checkMatcherList("subjects", List.of(overCap, "repo:*"), List.of(overCap));
		FederatedTrustRuleService.checkMatcherList("repositories", storedRepos, storedRepos);
		FederatedTrustRuleService.checkMatcherList("repositories", storedRepos.subList(1, storedRepos.size()), storedRepos);
		// While over the cap, no new entry even in place of a dropped one.
		List<String> swapped = new ArrayList<>(storedRepos.subList(1, storedRepos.size()));
		swapped.add("brand-new");
		assertThrows(RelizaException.class, () -> FederatedTrustRuleService.checkMatcherList("repositories", swapped,
				storedRepos));
		// Nor may an over-cap list keep its size by repeating an entry in place of a dropped one.
		List<String> mixed = new ArrayList<>(Collections.nCopies(FederatedMatching.MAX_LIST_ENTRIES + 1, "r"));
		mixed.add("q");
		List<String> repeated = new ArrayList<>(Collections.nCopies(FederatedMatching.MAX_LIST_ENTRIES + 2, "r"));
		assertThrows(RelizaException.class, () -> FederatedTrustRuleService.checkMatcherList("repositories", repeated,
				mixed));
		// A new over-cap glob, or a list that grows past the cap, does not.
		assertThrows(RelizaException.class, () -> FederatedTrustRuleService.checkMatcherList("subjects",
				List.of(overCap, overCap + "y"), List.of(overCap)));
		List<String> grown = new ArrayList<>(storedRepos);
		grown.add("s");
		assertThrows(RelizaException.class, () -> FederatedTrustRuleService.checkMatcherList("repositories", grown,
				storedRepos));
	}

	@Test
	void aLegacyOverCapRuleStoredDirectlyStillMatchesAtExchange() {
		// Caps are write-only: a rule saved before them -- here written straight to the entity, past
		// apply() -- goes through the same read the token exchange does (matcherOf, then matches)
		// and keeps admitting the identities it admitted.
		FederatedMatcher m = owner("relizaio");
		List<String> repos = new ArrayList<>(Collections.nCopies(FederatedMatching.MAX_LIST_ENTRIES + 6, "other"));
		repos.add("rearm");
		m.setRepositories(repos);
		m.setSubjects(List.of("*".repeat(FederatedMatching.MAX_GLOB_LENGTH + 88) + ":ref:refs/heads/main"));
		FederatedTrustRule rule = new FederatedTrustRule();
		rule.setMatcherOf(m);

		IdentityClaims c = github("relizaio/rearm", "refs/heads/main", "", "x@y", "push");
		assertTrue(FederatedMatching.matches(rule.matcherOf(), c));
		assertFalse(FederatedMatching.matches(rule.matcherOf(),
				github("relizaio/rearm", "refs/heads/dev", "", "x@y", "push")), "and still constrains");
	}
}
