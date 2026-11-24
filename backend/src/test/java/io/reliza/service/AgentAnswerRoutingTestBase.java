/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerVerdict;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.AgentBoardData;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentTaskService.Confirmer;

/**
 * Helpers the answer-routing tests share (task TPF-171, built by TPF-172): a question asked by the coder, answered by
 * the architect with or without {@code --no-change}, and the coordinator's verdict. No tests.
 */
abstract class AgentAnswerRoutingTestBase extends AgentCarriedDesignTestBase {

	@Autowired UserService userService;
	@Autowired ApiKeyService apiKeyService;
	@Autowired AgentIdentityService agentIdentityService;

	/** The actor that gives the verdict in the rows that do not say otherwise: a seat session of another agent. */
	static final AgentActor CONFIRMER = AgentActor.ofSession(UUID.randomUUID());
	static final RearmSpecificationType QUESTIONS = RearmSpecificationType.BOARD_QUESTIONS;
	static final AtomicInteger QID = new AtomicInteger();

	/**
	 * The coder asks about {@code about}: it takes the task when it is queued for it, publishes a questions round with
	 * one OPEN P1 item and signs off REJECTED; the task is queued for the role producing {@code about}.
	 */
	ReleaseData coderAsks(Rig r, AgentTaskData t, RearmSpecificationType about) throws RelizaException {
		if (TaskStatus.QUEUED == reload(t).getStatus()) {
			assertEquals("coder", reload(t).getRole());
			assign(r, t, r.coder());
		}
		ReleaseData q = index(r.coder(), reload(t), QUESTIONS, "REJECTED", about, "q" + QID.incrementAndGet(), 1, "OPEN");
		AgentTaskData asked = signOff(r.coder(), t, SignOffOutcome.REJECTED, "which store?", List.of(q.getUuid()));
		assertEquals(TaskStatus.QUEUED, asked.getStatus(), asked.getStatusHistory().toString());
		assertEquals("architect", asked.getRole());
		return q;
	}

	/**
	 * An ordinary task at the coder: the architect publishes both design documents and passes {@code --no-split}, both
	 * reviewers pass, and the coder takes the task.
	 */
	AgentTaskData ordinaryAtCoder(Rig r, String title) throws RelizaException {
		AgentTaskData t = task(r, title);
		designNoSplit(r, t, "fits one coder pass");
		AgentTaskData queued = reviewsPass(r, t);
		assertEquals(TaskStatus.QUEUED, queued.getStatus(), queued.getStatusHistory().toString());
		assertEquals("coder", queued.getRole());
		assign(r, t, r.coder());
		return reload(t);
	}

	/**
	 * The architect takes the task, publishes one new round of each of {@code specs} and passes, with
	 * {@code --no-change} as given and {@code --no-split} when {@code noSplit}.
	 *
	 * @return the rounds, in order
	 */
	List<ReleaseData> answer(Rig r, AgentTaskData t, Boolean noChange, boolean noSplit, RearmSpecificationType... specs)
			throws RelizaException {
		assign(r, t, r.architect());
		List<ReleaseData> rounds = new ArrayList<>();
		for (RearmSpecificationType spec : specs) rounds.add(publish(r.architect(), t, spec));
		answerWith(r, t, noChange, noSplit, rounds.stream().map(ReleaseData::getUuid).toList());
		return rounds;
	}

	/** The architect, holding the task, passes with {@code outputs} and the flags. */
	AgentTaskData answerWith(Rig r, AgentTaskData t, Boolean noChange, boolean noSplit, List<UUID> outputs)
			throws RelizaException {
		return agentTaskService.signOff(t.getUuid(), r.architect().sessionUuid(), SignOffOutcome.PASSED, "answered",
				outputs, null, noChange, null, noSplit ? Boolean.TRUE : null, WU);
	}

	/** The same session publishes {@code release}'s path again: a new release that supersedes it. */
	ReleaseData republish(Worker w, AgentTaskData t, ReleaseData release) throws RelizaException {
		return agentDocumentService.publish(w.session(), new PublishRequest(reload(t).getUuid(),
				release.getDocument().specification(), null, release.getDocument().path(), "digest-" + UUID.randomUUID(),
				"text/markdown", null, null, null, "c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "doc",
				ZonedDateTime.now(), null, null, null, false), WU);
	}

	/** The verdict, given by {@link #CONFIRMER} as the seat of another agent. */
	AgentTaskData confirm(Rig r, AgentTaskData t, AnswerVerdict verdict) throws RelizaException {
		return confirm(r, t, verdict, null, CONFIRMER, new Confirmer(UUID.randomUUID(), null));
	}

	AgentTaskData confirm(Rig r, AgentTaskData t, AnswerVerdict verdict, String note, AgentActor actor,
			Confirmer caller) throws RelizaException {
		return agentTaskService.confirmAnswer(t.getUuid(), board(r), verdict, note, actor, caller, WU);
	}

	/** The task's ARCHITECTURE releases, in the order the task holds them. */
	List<ReleaseData> archRounds(AgentTaskData t) {
		List<ReleaseData> out = new ArrayList<>();
		for (UUID u : reload(t).getReleases()) {
			ReleaseData rd = release(u);
			if (null != rd.getDocument() && ARCH == rd.getDocument().specification()) out.add(rd);
		}
		return out;
	}

	/** The task's newest confirmation entry. */
	AnswerConfirmation entry(AgentTaskData t) {
		List<AnswerConfirmation> all = reload(t).getAnswerConfirmations();
		assertTrue(!all.isEmpty(), "the task has a confirmation entry");
		return all.get(all.size() - 1);
	}

	/** The status history's row {@code fromEnd} rows before the last (0 is the last). */
	StatusChange row(AgentTaskData t, int fromEnd) {
		List<StatusChange> h = reload(t).getStatusHistory();
		return h.get(h.size() - 1 - fromEnd);
	}

	boolean hasCurrentPass(Rig r, AgentTaskData t, String role) {
		return routingService.hasCurrentPass(reload(t), board(r), roleConfig(r, role));
	}

	/** The ALERTs on the board that ask for the verdict on this task. */
	List<String> confirmationAlerts(Rig r, AgentTaskData t) {
		return messages(r, BoardEventKind.ALERT, "confirm-answer").stream().filter(m -> m.contains(t.label())).toList();
	}

	/** The INFOs on the board naming the task and containing {@code text}. */
	List<String> infos(Rig r, AgentTaskData t, String text) {
		return messages(r, BoardEventKind.INFO, text).stream().filter(m -> m.contains(t.label())).toList();
	}

	/** The task is QUEUED for {@code role}. */
	static void assertQueuedFor(AgentTaskData t, String role) {
		assertEquals(TaskStatus.QUEUED, t.getStatus(), t.getStatusHistory().toString());
		assertEquals(role, t.getRole(), t.getStatusHistory().toString());
	}

	/** A member of {@code org} at {@code level}, as a person who signs in. */
	User person(Organization org, PermissionType level) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Answer " + tag, "answer-" + tag + "@answertest.io", true, List.of(org.getUuid()),
				"answer-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level,
				List.of(), null, WU);
		return u;
	}

	/** A personal key of {@code owner}, as rearm login mints it, at {@code level} org-wide. */
	ApiKey personalKey(Organization org, User owner, PermissionType level) throws RelizaException {
		ApiKey ak = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "answer test", WU);
		apiKeyService.setPermissionsOnApiKey(ak.getUuid(), level, List.of(), WU);
		return apiKeyService.getApiKey(ak.getUuid()).orElseThrow();
	}

	/** An organization's FREEFORM key at ADMIN. */
	ApiKey freeformKey(Organization org) throws RelizaException {
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "answer test freeform", WU);
		apiKeyService.setPermissionsOnApiKey(ak.getUuid(), PermissionType.ADMIN, List.of(), WU);
		return apiKeyService.getApiKey(ak.getUuid()).orElseThrow();
	}

	/** The rig with its architect replaced by an agent whose identity runs on {@code key} (D10). */
	Rig architectOn(Rig r, ApiKey key) throws RelizaException {
		UUID identity = agentIdentityService.findOrRegisterByCredential(r.org().getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(r.org().getUuid(), identity,
				"architect-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(r.org().getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "architect on a key", null, null, WU);
		return new Rig(r.org(), r.board(), new Worker(agent, session), r.functional(), r.security(), r.coder(),
				r.tester());
	}

	/**
	 * The asker-upstream board of T-8 and T-29d: architect (10) produces ARCHITECTURE and THREAT_MODEL; coder (20),
	 * reading ARCHITECTURE when {@code coderReads}, produces DETAILED_DESIGN; reviewer (30) reads ARCHITECTURE and
	 * produces review items. The reviewer is the rig's functional worker; no checkpoint.
	 */
	Rig askerFirst(boolean coderReads) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("answer_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "answer-" + UUID.randomUUID(),
				"answer routing", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, role("architect", 10, HumanGate.NONE, List.of(),
				List.of(produces(ARCH), produces(TM)), ARCHITECT_HOP, null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("coder", 20, HumanGate.NONE,
				coderReads ? List.of(inputAt(ARCH)) : List.of(), List.of(produces(DD)), CODER_HOP, null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("reviewer", 30, HumanGate.NONE, List.of(inputAt(ARCH)),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		return new Rig(org, board, worker(org, "architect"), worker(org, "reviewer"), worker(org, "unused"),
				worker(org, "coder"), worker(org, "tester"));
	}
}
