/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.model.FederatedMatcher;
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
}
