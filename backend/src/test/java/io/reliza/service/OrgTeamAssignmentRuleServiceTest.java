/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.GlobalTeamAssignmentRule;
import io.reliza.model.TeamData;
import io.reliza.model.TeamStatus;

/**
 * Matching + validation for org-wide team-assignment rules (T2).
 *
 * <p>The contract mirrors {@code OrgApprovalPolicyService}: patterns are FULLY
 * ANCHORED, the {@code componentType} filter treats null/ANY as "match any", and
 * list order is priority order with first-match-wins.
 */
class OrgTeamAssignmentRuleServiceTest {

	private TeamService teamService;
	private OrgTeamAssignmentRuleService service;

	private final UUID org = UUID.randomUUID();
	private final UUID teamA = UUID.randomUUID();
	private final UUID teamB = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		teamService = mock(TeamService.class);
		service = new OrgTeamAssignmentRuleService();
		ReflectionTestUtils.setField(service, "teamService", teamService);
		ReflectionTestUtils.setField(service, "organizationService", mock(OrganizationService.class));
		stubTeam(teamA, org, TeamStatus.ACTIVE);
		stubTeam(teamB, org, TeamStatus.ACTIVE);
	}

	private void stubTeam(UUID uuid, UUID teamOrg, TeamStatus status) {
		TeamData g = mock(TeamData.class);
		when(g.getUuid()).thenReturn(uuid);
		when(g.getOrg()).thenReturn(teamOrg);
		when(g.getStatus()).thenReturn(status);
		when(teamService.getReadableTeamData(uuid)).thenReturn(Optional.of(g));
	}

	private static GlobalTeamAssignmentRule rule(String name, String pattern, ComponentType type, UUID team) {
		GlobalTeamAssignmentRule r = new GlobalTeamAssignmentRule();
		r.setName(name);
		r.setNamePattern(pattern);
		r.setComponentType(type);
		r.setOwnerTeam(team);
		return r;
	}

	private OrganizationData orgWith(GlobalTeamAssignmentRule... rules) {
		OrganizationData od = mock(OrganizationData.class);
		when(od.getUuid()).thenReturn(org);
		when(od.getGlobalTeamAssignmentRules()).thenReturn(Arrays.asList(rules));
		return od;
	}

	private static ComponentData component(String name, ComponentType type) {
		ComponentData cd = new ComponentData();
		cd.setName(name);
		cd.setType(type);
		return cd;
	}

	// ---------- matching ----------

	@Test
	void matchesAnchoredPattern() {
		var m = service.matchFor(component("rebom-backend", ComponentType.COMPONENT),
				orgWith(rule("rebom", "rebom-.*", null, teamA)));
		assertTrue(m.isPresent());
		assertEquals(teamA, m.get().team().getUuid());
	}

	@Test
	void patternIsFullyAnchoredSoAPrefixDoesNotMatchALongerName() {
		// "rebom-" would match as a substring but must NOT match the whole name --
		// same anchoring convention as DependencyPatternService.
		assertTrue(service.matchFor(component("rebom-backend", ComponentType.COMPONENT),
				orgWith(rule("r", "rebom-", null, teamA))).isEmpty());
	}

	@Test
	void firstMatchingRuleWinsInListOrder() {
		var m = service.matchFor(component("rebom-backend", ComponentType.COMPONENT),
				orgWith(rule("first", "rebom-.*", null, teamA),
						rule("second", ".*backend", null, teamB)));
		assertEquals(teamA, m.get().team().getUuid(), "list order is the priority order");
		assertEquals("first", m.get().rule().getName());
	}

	@Test
	void typeFilterRestrictsTheMatch() {
		var od = orgWith(rule("products only", ".*", ComponentType.PRODUCT, teamA));
		assertTrue(service.matchFor(component("anything", ComponentType.PRODUCT), od).isPresent());
		assertTrue(service.matchFor(component("anything", ComponentType.COMPONENT), od).isEmpty());
	}

	@Test
	void nullAndAnyTypeFilterMatchBothConcreteTypes() {
		for (ComponentType filter : new ComponentType[] { null, ComponentType.ANY }) {
			var od = orgWith(rule("any", ".*", filter, teamA));
			assertTrue(service.matchFor(component("x", ComponentType.COMPONENT), od).isPresent());
			assertTrue(service.matchFor(component("x", ComponentType.PRODUCT), od).isPresent());
		}
	}

	@Test
	void ruleWhoseTeamIsGoneIsSkippedSoLaterRulesStillApply() {
		UUID missing = UUID.randomUUID();
		when(teamService.getReadableTeamData(missing)).thenReturn(Optional.empty());
		var m = service.matchFor(component("rebom-backend", ComponentType.COMPONENT),
				orgWith(rule("stale", "rebom-.*", null, missing),
						rule("good", "rebom-.*", null, teamB)));
		assertTrue(m.isPresent(), "one stale rule must not mask every rule behind it");
		assertEquals(teamB, m.get().team().getUuid());
	}

	@Test
	void crossOrgTeamIsNotUsable() {
		UUID foreign = UUID.randomUUID();
		stubTeam(foreign, UUID.randomUUID(), TeamStatus.ACTIVE);
		assertTrue(service.matchFor(component("x", ComponentType.COMPONENT),
				orgWith(rule("foreign", ".*", null, foreign))).isEmpty());
	}

	@Test
	void badRegexOnReadIsSkippedNotThrown() {
		// Writes validate the regex; bad data on read must not kill the resolver.
		var m = service.matchFor(component("x", ComponentType.COMPONENT),
				orgWith(rule("bad", "[unclosed", null, teamA),
						rule("good", "x", null, teamB)));
		assertEquals(teamB, m.get().team().getUuid());
	}

	@Test
	void noRulesOrNullOrgYieldsNoMatch() {
		assertTrue(service.matchFor(component("x", ComponentType.COMPONENT), orgWith()).isEmpty());
		assertTrue(service.matchFor(component("x", ComponentType.COMPONENT), null).isEmpty());
	}

	// ---------- validation ----------

	@Test
	void validateAcceptsAWellFormedRule() throws Exception {
		service.validate(org, List.of(rule("ok", "rebom-.*", ComponentType.COMPONENT, teamA)));
	}

	@Test
	void validateRejectsBlankNameBlankPatternAndBadRegex() {
		assertThrows(RelizaException.class, () -> service.validate(org, List.of(rule("", "x", null, teamA))));
		assertThrows(RelizaException.class, () -> service.validate(org, List.of(rule("n", " ", null, teamA))));
		assertThrows(RelizaException.class, () -> service.validate(org, List.of(rule("n", "[unclosed", null, teamA))));
	}

	@Test
	void validateRejectsDuplicateNamesCaseInsensitively() {
		assertThrows(RelizaException.class, () -> service.validate(org,
				List.of(rule("Rebom", "a.*", null, teamA), rule("rebom", "b.*", null, teamB))));
	}

	@Test
	void validateRejectsMissingCrossOrgAndArchivedTeams() {
		assertThrows(RelizaException.class, () -> service.validate(org, List.of(rule("n", "x", null, null))));
		UUID missing = UUID.randomUUID();
		when(teamService.getReadableTeamData(missing)).thenReturn(Optional.empty());
		assertThrows(RelizaException.class, () -> service.validate(org, List.of(rule("n", "x", null, missing))));
		UUID archived = UUID.randomUUID();
		stubTeam(archived, org, TeamStatus.INACTIVE);
		assertThrows(RelizaException.class, () -> service.validate(org, List.of(rule("n", "x", null, archived))));
	}

	@Test
	void validateToleratesNullList() throws Exception {
		service.validate(org, null);
	}

	// ---------- ReDoS / cost guards ----------

	@Test
	void catastrophicBacktrackingIsBudgetedNotHung() {
		// (.*a){20} against a non-matching string does not terminate in any
		// practical time under a plain matcher. Rules run on EVERY ownership read
		// of EVERY component, so an unbounded match would let one saved rule burn
		// request threads for the whole instance. The step budget must make this
		// fail fast and report "no match" instead.
		String evil = "(.*a){20}";
		String name = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";
		ComponentData cd = component(name, ComponentType.COMPONENT);
		long start = System.nanoTime();
		var m = service.matchFor(cd, orgWith(rule("evil", evil, null, teamA)));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(m.isEmpty(), "a budget-exceeding pattern must not report a match");
		assertTrue(elapsedMs < 10_000, "match must abort on budget, took " + elapsedMs + "ms");
	}

	@Test
	void validateRejectsAnOverlongPattern() {
		String tooLong = "a".repeat(OrgTeamAssignmentRuleService.MAX_PATTERN_LENGTH + 1);
		assertThrows(RelizaException.class, () -> service.validate(org, List.of(rule("n", tooLong, null, teamA))));
	}

	// ---------- hoisted group list (N+1 avoidance) ----------

	@Test
	void hoistedOrgGroupsAreUsedInsteadOfARepositoryHit() {
		TeamData hoisted = mock(TeamData.class);
		when(hoisted.getUuid()).thenReturn(teamA);
		when(hoisted.getOrg()).thenReturn(org);
		when(hoisted.getStatus()).thenReturn(TeamStatus.ACTIVE);
		var m = service.matchFor(component("x", ComponentType.COMPONENT),
				orgWith(rule("r", "x", null, teamA)), List.of(hoisted));
		assertTrue(m.isPresent());
		// The whole point of hoisting: no per-component DB read for the team.
		verify(teamService, never()).getReadableTeamData(teamA);
	}

	@Test
	void archivedTeamIsStillReturnedSoOwnershipCanReportDegraded() {
		UUID archived = UUID.randomUUID();
		stubTeam(archived, org, TeamStatus.INACTIVE);
		var m = service.matchFor(component("x", ComponentType.COMPONENT),
				orgWith(rule("r", "x", null, archived)));
		assertTrue(m.isPresent(), "an archived team must surface as DEGRADED, not fall through");
		assertEquals(TeamStatus.INACTIVE, m.get().team().getStatus());
	}
}
