/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.SessionStatus;

/**
 * Read-side aggregations for the AI Agents dashboard. Keeps the KPI
 * computation out of {@link AgentService} / {@link AgentSessionService}
 * — those own write paths and want to stay narrowly scoped. Per-agent
 * badges (counts, last activity) aggregate in SQL; the org-level KPI
 * header still streams the org's sessions in-memory and is the next
 * SQL-aggregation target if it bites.
 *
 * Lives in shared {@code service.*} — dashboards are CE. Policies
 * and verdicts (PR 4) extend KPIs with blocked / passing counters
 * behind the SAAS boundary; the shape here leaves room for that.
 */
@Service
public class AgentMonitoringService {

	@Autowired
	private AgentService agentService;

	@Autowired
	private AgentSessionService agentSessionService;

	/**
	 * Cross-cutting org-level KPIs for the AI Agents dashboard header.
	 * Mirrors the mockup's four-card row: active sessions, closed in
	 * last 30d, artifacts produced in last 7d, registered agents.
	 *
	 * Backed by full-list reads + in-memory filters. Acceptable at
	 * v1 scale; the optimisation target if it bites is a single SQL
	 * aggregation query against {@code agent_sessions} keyed by
	 * {@code (org, status, last_activity_at, closed_at)}.
	 */
	public AgentDashboardKpis computeDashboardKpis(UUID orgUuid) {
		if (orgUuid == null) {
			return new AgentDashboardKpis(0, 0, 0, 0);
		}
		List<AgentSessionData> sessions = agentSessionService.listByOrg(orgUuid, null);
		ZonedDateTime now = ZonedDateTime.now();
		ZonedDateTime since30 = now.minusDays(30);
		ZonedDateTime since7 = now.minusDays(7);

		int activeSessions = 0;
		int closedSessions30d = 0;
		int artifactsProduced7d = 0;
		for (AgentSessionData s : sessions) {
			if (s.getStatus() == SessionStatus.OPEN) {
				activeSessions++;
			}
			if (s.getStatus() == SessionStatus.CLOSED
					&& s.getClosedAt() != null
					&& s.getClosedAt().isAfter(since30)) {
				closedSessions30d++;
			}
			if (s.getLastActivityAt() != null
					&& s.getLastActivityAt().isAfter(since7)
					&& s.getArtifacts() != null) {
				artifactsProduced7d += s.getArtifacts().size();
			}
		}
		int registeredAgents = agentService.listByOrg(orgUuid).size();
		return new AgentDashboardKpis(activeSessions, closedSessions30d,
				artifactsProduced7d, registeredAgents);
	}

	/**
	 * Most-recent activity timestamp across all sessions an agent
	 * owns. Used by the dashboard "connected" pill — the threshold
	 * for "connected" is a UI choice (defaults to 30 days since
	 * last activity per §11 of the design doc).
	 *
	 * Returns null when the agent has no sessions yet (just-registered
	 * agents have a clean slate until their first init).
	 */
	public ZonedDateTime lastActivityForAgent(UUID rootAgentUuid) {
		return agentSessionService.maxLastActivityAt(rootAgentUuid);
	}

	/**
	 * Per-agent counts for the dashboard card grid. Aggregated in SQL --
	 * an agent with a deep closed-session history shouldn't cost two
	 * full list deserializations just to render its badges.
	 */
	public AgentSessionCounts countsForAgent(UUID rootAgentUuid) {
		if (rootAgentUuid == null) return new AgentSessionCounts(0, 0);
		Map<SessionStatus, Long> counts = agentSessionService.countByAgentPerStatus(rootAgentUuid);
		return new AgentSessionCounts(
				counts.getOrDefault(SessionStatus.OPEN, 0L).intValue(),
				counts.getOrDefault(SessionStatus.CLOSED, 0L).intValue());
	}

	/**
	 * Cross-cutting KPI tuple. Stays a record (immutable) so a Jackson
	 * round-trip doesn't drift from the GraphQL shape.
	 *
	 * @param activeSessions       OPEN sessions across the org
	 * @param closedSessions30d    sessions whose closedAt is within
	 *                              the last 30 days
	 * @param artifactsProduced7d  count of artifact UUIDs attached
	 *                              to sessions whose lastActivityAt is
	 *                              within the last 7 days. Sum (not
	 *                              distinct) — artifacts are 1:N to
	 *                              sessions in v1 but the conservative
	 *                              read is fine for a display KPI.
	 * @param registeredAgents     total agents in the org (ROOT + SUB)
	 */
	public record AgentDashboardKpis(int activeSessions, int closedSessions30d,
			int artifactsProduced7d, int registeredAgents) {}

	public record AgentSessionCounts(int openSessions, int closedSessions) {}
}
