/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.SessionStatus;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the SQL behind the AI Agents dashboard read-side against real
 * Postgres:
 *
 * <ul>
 * <li>{@link SharedReleaseService#findReleaseDatasBySces} -- the batched
 *     session-commits -> releases lookup used by the
 *     {@code Session.releases} GraphQL resolver. Must match on the
 *     primary {@code sourceCodeEntry} AND on {@code commits[]}
 *     membership, stay org-scoped, and dedupe.</li>
 * <li>{@link SharedReleaseService#findReleaseDatasBySce} -- regression
 *     guard for the predicate rewrite from
 *     {@code jsonb_contains(record_data->'commits', ...)} to the
 *     GIN-indexable {@code record_data @> ...} form: both branches of
 *     the OR must keep matching.</li>
 * <li>{@link AgentSessionService#countByAgentPerStatus} and
 *     {@link AgentSessionService#maxLastActivityAt} -- the SQL
 *     aggregates that replaced full-list loads for the dashboard
 *     badges.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionDashboardQueriesIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentMonitoringService agentMonitoringService;

	private UUID createSce(UUID orgUuid, Branch branch, UUID vcsUuid, String commitSha)
			throws RelizaException {
		SceDto sceDto = SceDto.builder()
				.branch(branch.getUuid())
				.vcs(vcsUuid)
				.commit(commitSha)
				.organizationUuid(orgUuid)
				.build();
		return sourceCodeEntryService.createSourceCodeEntry(sceDto, WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	private UUID createRelease(UUID orgUuid, Component component, Branch branch, String version,
			UUID sourceCodeEntry, List<UUID> commits) throws RelizaException {
		ReleaseDto dto = ReleaseDto.builder()
				.component(component.getUuid())
				.branch(branch.getUuid())
				.org(orgUuid)
				.status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version(version)
				.sourceCodeEntry(sourceCodeEntry)
				.commits(commits)
				.build();
		return ossReleaseService.createRelease(dto, WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	@Test
	public void findReleasesBySces_matchesPrimarySceAndCommitsListOrgScoped() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		UUID vcsUuid = vcsRepositoryService.provisionVcsRepository(org.getUuid(),
				"github.com/example/agent-dash-" + UUID.randomUUID(), VcsType.GIT,
				WhoUpdated.getTestWhoUpdated());
		UUID sce1 = createSce(org.getUuid(), branch, vcsUuid, "1111111111111111111111111111111111111111");
		UUID sce2 = createSce(org.getUuid(), branch, vcsUuid, "2222222222222222222222222222222222222222");
		UUID sce3 = createSce(org.getUuid(), branch, vcsUuid, "3333333333333333333333333333333333333333");
		UUID sceUnrelated = createSce(org.getUuid(), branch, vcsUuid, "4444444444444444444444444444444444444444");

		UUID relByPrimarySce = createRelease(org.getUuid(), component, branch, "1.0.0", sce1, null);
		UUID relByCommitsList = createRelease(org.getUuid(), component, branch, "1.1.0", null,
				List.of(sce2, sce3));
		UUID relUnrelated = createRelease(org.getUuid(), component, branch, "1.2.0", null,
				List.of(sceUnrelated));

		List<ReleaseData> batched = sharedReleaseService.findReleaseDatasBySces(
				List.of(sce1, sce2), org.getUuid());
		Set<UUID> batchedUuids = batched.stream().map(ReleaseData::getUuid).collect(Collectors.toSet());
		assertEquals(Set.of(relByPrimarySce, relByCommitsList), batchedUuids);

		// single-SCE path: the rewritten @> predicate must still match commits[] membership
		List<ReleaseData> single = sharedReleaseService.findReleaseDatasBySce(sce2, org.getUuid());
		assertEquals(1, single.size());
		assertEquals(relByCommitsList, single.get(0).getUuid());

		// org scoping: a foreign org must see nothing for the same SCEs
		assertTrue(sharedReleaseService.findReleaseDatasBySces(
				List.of(sce1, sce2), UUID.randomUUID()).isEmpty());
		// empty input short-circuits without touching the DB
		assertTrue(sharedReleaseService.findReleaseDatasBySces(List.of(), org.getUuid()).isEmpty());
		// unrelated release only comes back for its own SCE
		List<ReleaseData> unrelated = sharedReleaseService.findReleaseDatasBySces(
				List.of(sceUnrelated), org.getUuid());
		assertEquals(1, unrelated.size());
		assertEquals(relUnrelated, unrelated.get(0).getUuid());
	}

	@Test
	public void sessionCountsAndLastActivity_aggregateInSql() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"dash-agent-" + UUID.randomUUID(), null, null, null, WhoUpdated.getTestWhoUpdated());

		AgentSessionData open = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"dash-open-1", "open session", null, null, WhoUpdated.getTestWhoUpdated());
		AgentSessionData closed1 = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"dash-closed-1", "closed session 1", null, null, WhoUpdated.getTestWhoUpdated());
		AgentSessionData closed2 = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"dash-closed-2", "closed session 2", null, null, WhoUpdated.getTestWhoUpdated());
		agentSessionService.close(closed1.getUuid(), WhoUpdated.getTestWhoUpdated());
		AgentSessionData closedLast = agentSessionService.close(closed2.getUuid(), WhoUpdated.getTestWhoUpdated());

		Map<SessionStatus, Long> counts = agentSessionService.countByAgentPerStatus(agent.getUuid());
		assertEquals(1L, counts.get(SessionStatus.OPEN));
		assertEquals(2L, counts.get(SessionStatus.CLOSED));

		var badge = agentMonitoringService.countsForAgent(agent.getUuid());
		assertEquals(1, badge.openSessions());
		assertEquals(2, badge.closedSessions());

		ZonedDateTime maxActivity = agentSessionService.maxLastActivityAt(agent.getUuid());
		assertNotNull(maxActivity);
		// closing closed2 was the most recent activity; epoch round-trip
		// through jsonb numeric must stay within a millisecond of it
		long deltaMillis = Math.abs(Duration.between(
				closedLast.getLastActivityAt(), maxActivity).toMillis());
		assertTrue(deltaMillis < 1,
				"max lastActivityAt drifted " + deltaMillis + "ms from the session stamp");
		assertNotNull(open.getStartedAt());

		// agent with no sessions: zero counts, null activity
		AgentData emptyAgent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"dash-empty-" + UUID.randomUUID(), null, null, null, WhoUpdated.getTestWhoUpdated());
		assertTrue(agentSessionService.countByAgentPerStatus(emptyAgent.getUuid()).isEmpty());
		assertNull(agentSessionService.maxLastActivityAt(emptyAgent.getUuid()));
		var emptyBadge = agentMonitoringService.countsForAgent(emptyAgent.getUuid());
		assertEquals(0, emptyBadge.openSessions());
		assertEquals(0, emptyBadge.closedSessions());
	}
}
