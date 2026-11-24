/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.CarriedRole;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.ResolvedInput;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;

/**
 * Helpers the carried-design tests share (task TPF-161, built by TPF-162): a parent at the design checkpoint split
 * into a child marked {@code designFromParent} and a plain one, and the routing reads the rows assert. No tests.
 */
abstract class AgentCarriedDesignTestBase extends AgentSplitRequestTestBase {

	static final List<String> DESIGN_ROLES = List.of("architect", "functional_reviewer", "security_reviewer");

	@Autowired AgentRoutingService routingService;
	@Autowired AgentTaskInputService agentTaskInputService;

	/** A parent split into a flagged and a plain child, with the design it carried. */
	record Carried(AgentTaskData parent, List<ReleaseData> design, AgentTaskData flagged, AgentTaskData plain) {}

	/** A child the split marks {@code designFromParent}. */
	static SplitChild flagged(String title, Integer... dependsOn) {
		return new SplitChild(title, null, null, null, null, List.of(dependsOn), null, null, null, Boolean.TRUE);
	}

	/** The parent's design passes, the task stops at the checkpoint, and the seat splits [flagged, plain]. */
	Carried carried(Rig r, String title) throws RelizaException {
		AgentTaskData parent = task(r, title);
		atCheckpoint(r, parent);
		List<ReleaseData> design = parentDesign(parent);
		List<AgentTaskData> children = split(r, parent, flagged("part 1"), child("part 2"));
		return new Carried(reload(parent), design, reload(children.get(0)), reload(children.get(1)));
	}

	/** The parent's two design rounds, ARCHITECTURE then THREAT_MODEL, as its checkpoint record names them. */
	List<ReleaseData> parentDesign(AgentTaskData parent) {
		List<ReleaseData> out = new ArrayList<>();
		for (UUID u : reload(parent).getDesignCheckpoint().design()) out.add(release(u));
		assertEquals(List.of(ARCH, TM), out.stream().map(rd -> rd.getDocument().specification()).toList());
		return out;
	}

	/** {@link #carried}, then the seat authorizes the flagged child for the coder: QUEUED for the coder. */
	Carried authorizedForCoder(Rig r, String title) throws RelizaException {
		Carried c = carried(r, title);
		AgentTaskData queued = authorize(r, c.flagged(), "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus(), queued.getStatusHistory().toString());
		assertEquals("coder", queued.getRole());
		return new Carried(c.parent(), c.design(), reload(c.flagged()), c.plain());
	}

	/** {@link #authorizedForCoder}, then the coder takes the flagged child (T-5). */
	Carried coderHolds(Rig r, String title) throws RelizaException {
		Carried c = authorizedForCoder(r, title);
		assign(r, c.flagged(), r.coder());
		return new Carried(c.parent(), c.design(), reload(c.flagged()), c.plain());
	}

	/** The coder, holding the task, rejects with one P1 review item about {@code about}. */
	AgentTaskData coderRejects(Rig r, AgentTaskData t, RearmSpecificationType about) throws RelizaException {
		ReleaseData items = index(r.coder(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, "REJECTED", about,
				"C-1", 1, "OPEN");
		return signOff(r.coder(), t, SignOffOutcome.REJECTED, "the design misses a case", List.of(items.getUuid()));
	}

	AgentTaskRoleConfigData roleConfig(Rig r, String name) {
		return agentBoardService.getRoleConfig(r.board().getUuid(), name).orElseThrow();
	}

	List<AgentTaskRoleConfigData> roles(Rig r) {
		return agentBoardService.listRoleConfigs(r.board().getUuid());
	}

	boolean carryLive(Rig r, AgentTaskData t, String role) {
		return routingService.carryLive(reload(t), roleConfig(r, role));
	}

	boolean checkpointStands(Rig r, AgentTaskData t) {
		return routingService.checkpointStands(reload(t), roles(r), roleConfig(r, "coder"));
	}

	/** What the role's requirement of {@code spec} binds on the task now: the release, or null when unmet. */
	UUID binding(Rig r, AgentTaskData t, String role, RearmSpecificationType spec) {
		return agentTaskInputService.bindings(board(r), roleConfig(r, role), reload(t)).stream()
				.filter(ri -> spec == ri.specification()).map(ResolvedInput::release).findFirst().orElse(null);
	}

	/** The carried role of the child's record. */
	static CarriedRole carriedRole(AgentTaskData child, String role) {
		CarriedRole cr = child.getCarriedDesign().roleFor(null, role);
		assertNotNull(cr, role);
		return cr;
	}

	/** The parent's last sign-off of the role, as stored. */
	SignOff lastOf(AgentTaskData t, String role) {
		return reload(t).lastSignOffForRole(null, role);
	}

	/** A second session of the architect's agent: another session of the same agent, as an advisory round needs. */
	Worker otherArchitectSession(Rig r) throws RelizaException {
		AgentSessionData s = agentSessionService.initialize(r.org().getUuid(), r.architect().agentUuid(), null,
				"s-" + UUID.randomUUID(), "architect, elsewhere", null, null, WU);
		return new Worker(r.architect().agent(), s);
	}

	/** The refusal's message starts with the child's title and names the rule. */
	static void assertRefusal(RelizaException e, String title, String rest) {
		assertEquals("child \"" + title + "\": designFromParent needs " + rest, e.getMessage());
	}

	/** No child was registered by a refused split. */
	void assertNoChildren(AgentTaskData parent) {
		AgentTaskData p = reload(parent);
		assertTrue(null == p.getChildTasks() || p.getChildTasks().isEmpty(), String.valueOf(p.getChildTasks()));
	}
}
