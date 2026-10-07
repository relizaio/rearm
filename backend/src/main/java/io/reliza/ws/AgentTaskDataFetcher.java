/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.reliza.common.CommonVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import graphql.execution.DataFetcherResult;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.UserData;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.common.Utils;
import tools.jackson.core.JacksonException;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskData;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.AgentAuditReadService;
import io.reliza.service.AgentAuditReadService.AgentBoardRevision;
import io.reliza.service.AgentAuditReadService.AgentRoleConfigRevision;
import io.reliza.service.AgentAuditReadService.AgentTaskRevision;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.AgentDocumentService;
import io.reliza.service.AgentSessionUsageService;
import io.reliza.service.AgentTaskInputService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.DeclarativeConfigService;
import io.reliza.service.UserService;
import io.reliza.service.SharedReleaseService;

/**
 * GraphQL surface of the agent task boards. Programmatic entries
 * authenticate a FREEFORM key ({@code PermissionFunction.AGENT}, org
 * scope) and derive the calling agent from the supplied session,
 * verified against the key's identity — an agent cannot act as
 * somebody else's session. Coordinator hub operations additionally
 * verify the session holds the board's singleton seat. Operator
 * entries are JWT org-admin. Design:
 * backend/ai-plans/agentic/task-boards.md.
 */
@DgsComponent
public class AgentTaskDataFetcher {

	@Autowired private io.reliza.service.AgentBudgetService agentBudgetService;
	@Autowired private io.reliza.service.BoardAgentsService boardAgentsService;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private io.reliza.service.AgentTaskGroupService agentTaskGroupService;
	@Autowired private io.reliza.service.GetComponentService getComponentService;
	@Autowired private io.reliza.service.BoardPerspectiveService boardPerspectiveService;
	@Autowired private AgentAuditReadService agentAuditReadService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentService agentService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private AgentTaskInputService agentTaskInputService;
	@Autowired private AgentSessionUsageService agentSessionUsageService;
	@Autowired private io.reliza.service.AgentDeliveryService agentDeliveryService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SharedReleaseService sharedReleaseService;

	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private UserService userService;
	@Autowired private io.reliza.service.AgentSessionVisibilityService agentSessionVisibilityService;

	/**
	 * The calling key and what the operation being served needs on a board (board-permissions.md §4.1).
	 * {@code gate} is looked up from the field name, so the board checks below cannot be told a
	 * weaker function than the field's class.
	 */
	private record ProgKeyContext(UUID orgUuid, UUID apiKeyUuid, WhoUpdated wu,
			io.reliza.common.CommonVariables.AuthHeaderParse ahp, BoardGate gate, String field) {}

	/** What a board operation needs: every function, at one call type (its tier floor, §2.2). */
	record BoardGate(List<PermissionFunction> functions, CallType callType) {
		static BoardGate of(CallType ct, PermissionFunction... fns) { return new BoardGate(List.of(fns), ct); }
	}

	private static final BoardGate READ = BoardGate.of(CallType.ESSENTIAL_READ, PermissionFunction.BOARD_READ);
	private static final BoardGate AGENT_VERB = BoardGate.of(CallType.ESSENTIAL_READ, PermissionFunction.BOARD_AGENT);
	private static final BoardGate WRITE = BoardGate.of(CallType.WRITE, PermissionFunction.BOARD_WRITE);
	/**
	 * A verb two kinds of caller share, each with its own board function (task RD4-5): the floor both clear.
	 * The fetcher checks the rest per caller -- BOARD_WRITE for the coordinator seat, BOARD_AGENT for the
	 * session working the task -- so neither is weaker than its own verb. Not {@link #READ}: the organization's
	 * AGENT gate still applies, since only an agent calls it.
	 */
	private static final BoardGate SEAT_OR_HOLDER = BoardGate.of(CallType.ESSENTIAL_READ, PermissionFunction.BOARD_READ);
	/**
	 * A commission (task RD4-12) has two callers as well: a session commissioning from the task it holds, which
	 * needs BOARD_AGENT, and a key commissioning as a person would, which needs BOARD_WRITE. The fetcher checks
	 * the rest per caller.
	 */
	private static final BoardGate SESSION_OR_WRITER = BoardGate.of(CallType.ESSENTIAL_READ, PermissionFunction.BOARD_READ);

	/**
	 * Every programmatic field of this fetcher and what it needs on the board it touches (architecture
	 * d8e7bd7e §3.1). A field missing here is refused at run time, and a test fails the build on one.
	 * The list reads (agentBoardsProgrammatic, agentTasksByUuidProgrammatic, and agentTaskNextProgrammatic
	 * without a board) filter by the same gate instead of refusing.
	 */
	static final Map<String, BoardGate> PROGRAMMATIC_BOARD_GATES = Map.ofEntries(
			Map.entry("agentBoardsProgrammatic", READ),
			Map.entry("agentBoardProgrammatic", READ),
			Map.entry("agentBoardSnapshotProgrammatic", READ),
			Map.entry("agentBoardEventsProgrammatic", READ),
			Map.entry("agentTaskProgrammatic", READ),
			Map.entry("agentTaskByKeyProgrammatic", READ),
			Map.entry("agentTasksProgrammatic", READ),
			Map.entry("agentTasksByUuidProgrammatic", READ),
			Map.entry("agentTaskRoleConfigsProgrammatic", READ),
			Map.entry("agentTaskNextProgrammatic", AGENT_VERB),
			Map.entry("agentTaskAssignProgrammatic", AGENT_VERB),
			Map.entry("agentTaskSignOffProgrammatic", AGENT_VERB),
			Map.entry("agentTaskReturnProgrammatic", AGENT_VERB),
			Map.entry("agentDocumentPublishProgrammatic", AGENT_VERB),
			Map.entry("agentElementCheckRunProgrammatic", AGENT_VERB),
			Map.entry("agentElementCheckReportProgrammatic", AGENT_VERB),
			Map.entry("agentElementCheckPreviewProgrammatic", AGENT_VERB),
			Map.entry("agentTaskLinkPrProgrammatic", AGENT_VERB),
			Map.entry("agentTaskLinkReleaseProgrammatic", AGENT_VERB),
			Map.entry("agentTaskDeclareDeliveryProgrammatic", AGENT_VERB),
			Map.entry("agentTaskSupersedePullRequestProgrammatic", AGENT_VERB),
			Map.entry("agentTaskBindExternalRefProgrammatic", AGENT_VERB),
			Map.entry("agentBoardCoordinateProgrammatic", WRITE),
			Map.entry("agentBoardCoordinatorPauseProgrammatic", WRITE),
			Map.entry("agentTaskRegisterProgrammatic", WRITE),
			Map.entry("agentTaskCommissionProgrammatic", SESSION_OR_WRITER),
			Map.entry("agentTaskAuthorizeProgrammatic", WRITE),
			Map.entry("agentTaskOrderProgrammatic", WRITE),
			Map.entry("agentTaskSetWorkLevelProgrammatic", WRITE),
			Map.entry("agentTaskUnassignProgrammatic", WRITE),
			Map.entry("agentBoardGroupSetProgrammatic", WRITE),
			Map.entry("agentTaskSetGroupProgrammatic", WRITE),
			Map.entry("agentTaskSetTagsProgrammatic", WRITE),
			Map.entry("agentTaskSplitProgrammatic", WRITE),
			Map.entry("agentTaskCompleteProgrammatic", WRITE),
			Map.entry("agentTaskCancelProgrammatic", WRITE),
			Map.entry("agentTaskReopenProgrammatic", WRITE),
			Map.entry("agentTaskHoldProgrammatic", SEAT_OR_HOLDER),
			Map.entry("agentTaskLiftHoldProgrammatic", WRITE),
			Map.entry("agentTaskEscalateHoldProgrammatic", WRITE),
			Map.entry("agentTaskRequireHumanReviewProgrammatic", WRITE),
			Map.entry("agentBoardPostEventProgrammatic", WRITE),
			Map.entry("agentTaskRoleConfigSetProgrammatic",
					BoardGate.of(CallType.WRITE, PermissionFunction.CONFIGURATION_WRITE, PermissionFunction.BOARD_WRITE)),
			Map.entry("agentBoardSpecProgrammatic",
					BoardGate.of(CallType.ESSENTIAL_READ, PermissionFunction.CONFIGURATION_READ, PermissionFunction.BOARD_READ)));

	private ProgKeyContext authorizeProgrammaticOrg(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID orgUuid = authCtx.orgUuid();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		// Any RBAC key: FREEFORM, a personal USER key from a browser login, or a FEDERATED
		// identity from a CI run. The permission check below is the same for all three -- a USER
		// key is additionally clamped to its owner -- and the rest of the agent surface accepts
		// them, so refusing them here would leave a session able to open but not to take work.
		if (!ahp.isRbacKey()) {
			throw new AccessDeniedException("An organization RBAC API key is required for agent task operations");
		}
		if (orgUuid == null) throw new AccessDeniedException("Could not resolve org for key");
		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found: " + orgUuid));
		String field = fieldName(dfe);
		BoardGate gate = PROGRAMMATIC_BOARD_GATES.get(field);
		// A board read needs the board function on the board it reads, and nothing at the organization
		// (task RD3-3): BOARD_READ, which BOARD_WRITE and BOARD_AGENT satisfy, is checked per board by
		// requireCovered and the list filters. So a key that writes a board -- Terraform's, holding
		// BOARD_WRITE and CONFIGURATION_WRITE and no AGENT -- reads it. Every other operation keeps the
		// organization's AGENT gate ("I am an agent") before its board gate.
		FreeformKeyVerification fkv = READ == gate
				? authorizationService.rbacKeyOfOrg(ahp, orgUuid, CallType.ESSENTIAL_READ)
				: authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.AGENT,
						PermissionScope.ORGANIZATION, orgUuid, List.of(od), CallType.ESSENTIAL_READ);
		return new ProgKeyContext(orgUuid, fkv.apiKeyUuid(), fkv.whoUpdated(), ahp, gate, field);
	}

	/**
	 * The field being served: from the environment, or, when a caller built the environment without
	 * one (a test calling a fetcher method directly), from the @DgsData of the fetcher method on the
	 * stack -- the same name either way, since the gate map is keyed by it.
	 */
	private static String fieldName(DgsDataFetchingEnvironment dfe) {
		try {
			if (null != dfe.getField()) return dfe.getField().getName();
		} catch (RuntimeException noField) {
			// no merged field on a hand-built environment; read it from the method below
		}
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
				.filter(f -> f.getDeclaringClass() == AgentTaskDataFetcher.class)
				.map(f -> java.util.Arrays.stream(AgentTaskDataFetcher.class.getDeclaredMethods())
						.filter(m -> m.getName().equals(f.getMethodName()) && m.isAnnotationPresent(DgsData.class))
						.map(m -> m.getAnnotation(DgsData.class).field()).findFirst().orElse(null))
				.filter(java.util.Objects::nonNull).findFirst().orElse(null));
	}

	/**
	 * Whether the calling key holds what the operation needs on this board (board-permissions.md
	 * §4.1): every function of its gate. AGENT at the organization, checked above, is only the "I
	 * am an agent" gate; it clears nothing on a board. A field with no gate covers nothing.
	 */
	private boolean covers(ProgKeyContext ctx, AgentBoardData bd) throws RelizaException {
		if (null == ctx.gate()) return false;
		return null != authorizationService.keyOnBoard(ctx.ahp(), ctx.orgUuid(), bd.getUuid(),
				ctx.gate().functions(), ctx.gate().callType());
	}

	/** Refuse an operation on a board its key does not cover, naming the board and the functions. */
	private AgentBoardData requireCovered(ProgKeyContext ctx, AgentBoardData bd) throws RelizaException {
		if (!covers(ctx, bd)) {
			throw new RelizaException(null == ctx.gate()
					? "Operation " + ctx.field() + " has no board permission class; it is refused"
					: "The key lacks " + ctx.gate().functions().stream().map(Enum::name)
							.collect(Collectors.joining(" and ")) + " on board " + bd.getName());
		}
		return bd;
	}

	/** Resolve the calling agent from a session: org-checked and identity-verified against the key. */
	private AgentData resolveCallingAgent(ProgKeyContext ctx, UUID sessionUuid) throws RelizaException {
		if (sessionUuid == null) throw new RelizaException("sessionUuid is required to identify the calling agent");
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		if (!ctx.orgUuid().equals(sd.getOrg())) {
			throw new AccessDeniedException("Session is not in the calling key's org");
		}
		if (sd.getStatus() != AgentSessionData.SessionStatus.OPEN) {
			throw new RelizaException("Session " + sessionUuid + " is " + sd.getStatus() + ", not OPEN");
		}
		AgentData agent = agentService.getAgentData(sd.getAgent())
				.orElseThrow(() -> new RelizaException("Agent not found for session: " + sessionUuid));
		Optional<AgentIdentityData> identity = agentIdentityService.findByCredential(
				AgentIdentityCredential.IdentityType.REARM_API_KEY, ctx.apiKeyUuid().toString());
		if (identity.isEmpty() || !identity.get().getUuid().equals(agent.getAgentIdentity())) {
			throw new AccessDeniedException("Calling key's identity does not own the session's agent");
		}
		return agent;
	}

	/** A board of the calling key's organization that the key covers for the operation being served. */
	private AgentBoardData requireOrgBoard(ProgKeyContext ctx, UUID boardUuid) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		if (!ctx.orgUuid().equals(bd.getOrg())) {
			throw new AccessDeniedException("Board is not in the calling org");
		}
		return requireCovered(ctx, bd);
	}

	/** A task of the calling key's organization on a board the key covers for the operation. */
	private AgentTaskData requireOrgTask(ProgKeyContext ctx, UUID taskUuid) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		if (!ctx.orgUuid().equals(td.getOrg())) {
			throw new AccessDeniedException("Task is not in the calling key's org");
		}
		requireCovered(ctx, agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard())));
		return td;
	}

	/** Whether the key covers the board of this task for the operation: for list reads that filter. */
	private boolean coversTask(ProgKeyContext ctx, AgentTaskData td) throws RelizaException {
		Optional<AgentBoardData> bd = agentBoardService.getBoardData(td.getBoard());
		return bd.isPresent() && covers(ctx, bd.get());
	}

	/** Coordinator hub operations: verify identity AND that the session holds the board's seat. */
	private AgentData requireSeatHolder(ProgKeyContext ctx, AgentBoardData board, UUID sessionUuid)
			throws RelizaException {
		AgentData agent = resolveCallingAgent(ctx, sessionUuid);
		if (!agentBoardService.isSeatHolder(board, sessionUuid)) {
			throw new AccessDeniedException("Session does not hold the coordinator seat on board " + board.getName());
		}
		return agent;
	}

	// ---------- Programmatic queries ----------

	@DgsData(parentType = "Query", field = "agentBoardsProgrammatic")
	public List<AgentBoardData> agentBoardsProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		List<AgentBoardData> covered = new java.util.ArrayList<>();
		for (AgentBoardData bd : agentBoardService.listByOrg(ctx.orgUuid())) {
			if (covers(ctx, bd)) covered.add(bd);
		}
		return covered;
	}

	/**
	 * One board's whole state for an agent: tasks, holders, dependencies, documents, questions.
	 *
	 * <p>Org-agent access like the task list, and read-only. The point is latency, not privilege:
	 * an agent that needs N calls to learn what the others are doing does not make them.
	 */
	@DgsData(parentType = "Query", field = "agentBoardSnapshotProgrammatic")
	public AgentTaskService.BoardSnapshot agentBoardSnapshotProgrammatic(
			@InputArgument("boardUuid") UUID boardUuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		numbered(requireOrgBoard(ctx, boardUuid).getUuid());
		AgentTaskService.BoardSnapshot snap = agentTaskService.snapshot(requireOrgBoard(ctx, boardUuid));
		snap.tasks().forEach(ts -> agentTaskService.forReader(ts.task(), ctx.apiKeyUuid()));
		return snap;
	}

	@DgsData(parentType = "Query", field = "agentBoardProgrammatic")
	public AgentBoardData agentBoardProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		numbered(requireOrgBoard(ctx, boardUuid).getUuid());
		return requireOrgBoard(ctx, boardUuid);
	}

	/**
	 * A board read numbers the tasks of a board from before keys (board-documents.md D14), once,
	 * before it reads them. Here at the read entry points and nowhere inside the services: it takes
	 * the board lock, which a caller already holding a task lock must not.
	 */
	private void numbered(UUID boardUuid) throws RelizaException {
		agentTaskService.ensureNumbered(boardUuid, WhoUpdated.getAutoWhoUpdated());
	}

	@DgsData(parentType = "Query", field = "agentBoardEventsProgrammatic")
	public AgentBoardService.EventPage agentBoardEventsProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("after") Long after, @InputArgument("since") ZonedDateTime since,
			@InputArgument("limit") Integer limit, DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentBoardData bd = requireOrgBoard(ctx, boardUuid);
		return agentBoardService.eventsOf(bd.getUuid(), after, since, limit);
	}

	@DgsData(parentType = "Query", field = "agentTaskNextProgrammatic")
	public WorkerAssignment agentTaskNextProgrammatic(@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("roles") List<String> roles,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentData agent = resolveCallingAgent(ctx, sessionUuid);
		List<AgentBoardData> boards = new java.util.ArrayList<>();
		if (boardUuid != null) {
			boards.add(requireOrgBoard(ctx, boardUuid));
		} else {
			// Only the boards the key works on: a board it does not cover offers nothing.
			for (AgentBoardData bd : agentBoardService.listByOrg(ctx.orgUuid())) {
				if (covers(ctx, bd)) boards.add(bd);
			}
		}
		return agentTaskService.next(boards, agent.getUuid(), sessionUuid, roles).orElse(null);
	}

	@DgsData(parentType = "Query", field = "agentTaskProgrammatic")
	public AgentTaskData agentTaskProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		numbered(requireOrgTask(ctx, taskUuid).getBoard());
		return agentTaskService.forReader(requireOrgTask(ctx, taskUuid), ctx.apiKeyUuid());
	}

	/** One task by its key in the calling key's organization (board-documents.md §4.3); null when none. */
	@DgsData(parentType = "Query", field = "agentTaskByKeyProgrammatic")
	public AgentTaskData agentTaskByKeyProgrammatic(@InputArgument("key") String key,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		Optional<AgentTaskData> td = agentTaskService.resolveKey(ctx.orgUuid(), key);
		if (td.isEmpty()) return null;
		return agentTaskService.forReader(requireOrgTask(ctx, td.get().getUuid()), ctx.apiKeyUuid());
	}

	/** A person's read of one task by its key in an organization; null when none. */
	@DgsData(parentType = "Query", field = "agentTaskByKey")
	public AgentTaskData agentTaskByKey(@InputArgument("orgUuid") UUID orgUuid, @InputArgument("key") String key,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		Optional<AgentTaskData> td = agentTaskService.resolveKey(orgUuid, key);
		if (td.isEmpty()) {
			// Nothing to read; still only a person may ask.
			if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) personalKey(dfe, orgUuid);
			return null;
		}
		authorizePersonOnTask(dfe, td.get(), PERSON_READ);
		return td.get();
	}

	/** Without the group and tag filters, as callers from before them read. */
	public List<AgentTaskData> agentTasksProgrammatic(UUID boardUuid, String status, ZonedDateTime changedSince,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		return agentTasksProgrammatic(boardUuid, status, changedSince, null, null, dfe);
	}

	@DgsData(parentType = "Query", field = "agentTasksProgrammatic")
	public List<AgentTaskData> agentTasksProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("status") String status, @InputArgument("changedSince") ZonedDateTime changedSince,
			@InputArgument("group") String group, @InputArgument("tag") List<String> tags,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentBoardData board = requireOrgBoard(ctx, boardUuid);
		numbered(boardUuid);
		return inGroupWithTags(board, agentTaskService.listByBoard(boardUuid, status, changedSince), group, tags)
				.stream()
				.map(td -> agentTaskService.forReader(td, ctx.apiKeyUuid()))
				.toList();
	}

	@DgsData(parentType = "Query", field = "agentTasksByUuidProgrammatic")
	public List<AgentTaskData> agentTasksByUuidProgrammatic(@InputArgument("taskUuids") List<UUID> taskUuids,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		// Another organization's task is absent, as an unknown one is: the read says nothing about it.
		// So is one on a board the key does not cover (§4.1): dropped, not refused.
		List<AgentTaskData> out = new java.util.ArrayList<>();
		for (AgentTaskData td : agentTaskService.getTasksData(taskUuids)) {
			if (ctx.orgUuid().equals(td.getOrg()) && coversTask(ctx, td)) {
				out.add(agentTaskService.forReader(td, ctx.apiKeyUuid()));
			}
		}
		return out;
	}

	@DgsData(parentType = "Query", field = "agentTaskRoleConfigsProgrammatic")
	public List<AgentTaskRoleConfigData> agentTaskRoleConfigsProgrammatic(
			@InputArgument("boardUuid") UUID boardUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		requireOrgBoard(ctx, boardUuid);
		return agentBoardService.listRoleConfigs(boardUuid);
	}

	// ---------- Programmatic mutations: coordinator seat + locks ----------

	@DgsData(parentType = "Mutation", field = "agentBoardCoordinateProgrammatic")
	public AgentBoardData agentBoardCoordinateProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentData agent = resolveCallingAgent(ctx, sessionUuid);
		requireOrgBoard(ctx, boardUuid);
		return agentBoardService.claimCoordinatorSeat(boardUuid, sessionUuid, agent.getUuid(), ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentBoardCoordinatorPauseProgrammatic")
	public AgentBoardData agentBoardCoordinatorPauseProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("pause") Boolean pause,
			@InputArgument("reason") String reason,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentBoardData board = requireOrgBoard(ctx, boardUuid);
		requireSeatHolder(ctx, board, sessionUuid);
		return agentBoardService.setPause(boardUuid, BoardPauseLevel.COORDINATOR,
				Boolean.TRUE.equals(pause), reason, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskRoleConfigSetProgrammatic")
	public AgentTaskRoleConfigData agentTaskRoleConfigSetProgrammatic(
			@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentBoardData board = requireOrgBoard(ctx, boardUuid);
		requireSeatHolder(ctx, board, sessionUuid);
		// v1.1 prompt governance: the seat can tune existing roles but
		// cannot create roles or touch prompts/capabilities.
		return upsertRoleConfigFromInput(board, dfe.getArgument("input"), false, ctx.wu());
	}

	// ---------- Programmatic mutations: intake ----------

	@DgsData(parentType = "Mutation", field = "agentTaskRegisterProgrammatic")
	public AgentTaskData agentTaskRegisterProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		Map<String, Object> input = dfe.getArgument("input");
		UUID boardUuid = parseUuid((String) input.get("boardUuid"), "boardUuid");
		if (boardUuid == null) throw new RelizaException("boardUuid is required");
		AgentBoardData board = requireOrgBoard(ctx, boardUuid);
		UUID sessionUuid = parseUuid((String) input.get("sessionUuid"), "sessionUuid");
		if (sessionUuid != null) resolveCallingAgent(ctx, sessionUuid);
		UUID parentTask = parseUuid((String) input.get("parentTask"), "parentTask");
		return agentTaskService.register(board,
				(String) input.get("externalRef"),
				(String) input.get("title"),
				(String) input.get("description"),
				(String) input.get("sourceUrl"),
				sessionUuid, parentTask,
				parseUuid((String) input.get("producesComponent"), "producesComponent"),
				input.get("workLevel") instanceof Number lvl ? lvl.intValue() : null,
				refuseQuestions(parseRequiredInputs(input.get("requiredInputs")), "requiredInputs"),
				null != sessionUuid ? AgentActor.ofSession(sessionUuid) : null, false,
				(String) input.get("group"), parseTags(input.get("tags")),
				ctx.wu());
	}

	/**
	 * Commission an investigation through an API key (task RD4-12). With a session, the session commissions from the
	 * task it holds, in that task's role (BOARD_AGENT); without one, the key commissions as a person does
	 * (BOARD_WRITE).
	 */
	@DgsData(parentType = "Mutation", field = "agentTaskCommissionProgrammatic")
	public AgentTaskData agentTaskCommissionProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		Map<String, Object> input = dfe.getArgument("input");
		UUID boardUuid = parseUuid((String) input.get("boardUuid"), "boardUuid");
		if (boardUuid == null) throw new RelizaException("boardUuid is required");
		AgentBoardData board = requireOrgBoard(ctx, boardUuid);
		UUID sessionUuid = parseUuid((String) input.get("sessionUuid"), "sessionUuid");
		AgentActor actor;
		if (null != sessionUuid) {
			resolveCallingAgent(ctx, sessionUuid);
			requireKeyOnBoard(ctx, board, PermissionFunction.BOARD_AGENT, CallType.ESSENTIAL_READ);
			actor = AgentActor.ofSession(sessionUuid);
		} else {
			requireKeyOnBoard(ctx, board, PermissionFunction.BOARD_WRITE, CallType.WRITE);
			// No session and no person row behind a key: the key is who commissioned it.
			actor = AgentActor.system("api key " + ctx.apiKeyUuid());
		}
		return agentTaskService.commission(board, commissionFromInput(input), sessionUuid, actor, ctx.wu());
	}

	/** A commission's request from its input (task RD4-12). */
	static AgentTaskService.CommissionRequest commissionFromInput(Map<String, Object> input) throws RelizaException {
		List<UUID> inputs = new ArrayList<>();
		if (input.get("inputs") instanceof List<?> raw) {
			for (Object o : raw) {
				UUID u = parseUuid(null == o ? null : o.toString(), "inputs");
				if (null != u) inputs.add(u);
			}
		}
		Object deadline = input.get("deadline");
		ZonedDateTime due = deadline instanceof java.time.OffsetDateTime odt ? odt.toZonedDateTime()
				: deadline instanceof ZonedDateTime zdt ? zdt
				: deadline instanceof String str && !str.isBlank() ? parseDeadline(str) : null;
		return new AgentTaskService.CommissionRequest(
				(String) input.get("role"),
				(String) input.get("title"),
				(String) input.get("brief"),
				parseUuid((String) input.get("fromTask"), "fromTask"),
				inputs,
				input.get("budgetMicros") instanceof Number n ? n.longValue() : null,
				due,
				(String) input.get("review"),
				parseEnum(input.get("returnTo"), AgentTaskData.ReturnTo.class, "returnTo"),
				input.get("workLevel") instanceof Number lvl ? lvl.intValue() : null,
				(String) input.get("group"),
				parseTags(input.get("tags")));
	}

	private static ZonedDateTime parseDeadline(String raw) throws RelizaException {
		try {
			return ZonedDateTime.parse(raw.strip());
		} catch (java.time.format.DateTimeParseException e) {
			throw new RelizaException("deadline is not a date and time (RFC 3339): " + raw);
		}
	}

	// ---------- Programmatic mutations: coordinator hub operations ----------

	@DgsData(parentType = "Mutation", field = "agentTaskAuthorizeProgrammatic")
	public AgentTaskData agentTaskAuthorizeProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("role") String role,
			@InputArgument("orderIndex") Integer orderIndex,
			@InputArgument("dependsOn") List<String> dependsOnRaw,
			@InputArgument("producesComponent") String producesComponentRaw,
			@InputArgument("workLevel") Integer workLevel,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		UUID producesComponent = parseUuid(producesComponentRaw, "producesComponent");
		List<UUID> dependsOn = null;
		if (dependsOnRaw != null) {
			dependsOn = new java.util.ArrayList<>();
			for (String d : dependsOnRaw) dependsOn.add(parseUuid(d, "dependsOn"));
		}
		// Presence, not value: left out leaves the task's requirement alone, sent as null clears it.
		boolean setStrength = dfe.getArguments().containsKey("requiredStrength");
		Object rawStrength = dfe.getArgument("requiredStrength");
		// The coordinator seeds a budget, never changes one (task 6f1b348d); refused before anything is written.
		boolean seedBudget = dfe.getArguments().containsKey("budgetMicros");
		Object rawBudget = dfe.getArgument("budgetMicros");
		Long budget = rawBudget instanceof Number b ? b.longValue() : null;
		if (seedBudget) agentTaskService.checkBudgetSeed(taskUuid, budget);
		AgentTaskData authorized = agentTaskService.authorize(taskUuid, board, role, orderIndex, dependsOn,
				producesComponent, workLevel,
				refuseQuestions(parseRequiredInputs(dfe.getArgument("requiredInputs")), "requiredInputs"),
				setStrength, rawStrength instanceof Number n ? n.doubleValue() : null,
				AgentActor.ofSession(sessionUuid), ctx.wu());
		return seedBudget ? agentTaskService.setBudget(taskUuid, budget, AgentActor.ofSession(sessionUuid), ctx.wu())
				: authorized;
	}

	@DgsData(parentType = "Mutation", field = "agentTaskOrderProgrammatic")
	public AgentTaskData agentTaskOrderProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("orderIndex") Integer orderIndex,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		return agentTaskService.setOrder(taskUuid, orderIndex, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	/** The coordinator seat sets or clears a task's level (RD2-1); BOARD_WRITE through the gate map. */
	@DgsData(parentType = "Mutation", field = "agentTaskSetWorkLevelProgrammatic")
	public AgentTaskData agentTaskSetWorkLevelProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		Object raw = dfe.getArgument("workLevel");
		return agentTaskService.setWorkLevel(taskUuid, raw instanceof Number n ? n.intValue() : null,
				AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	/** The coordinator seat releases a stalled assignment back to the queue (task RD3-4). */
	@DgsData(parentType = "Mutation", field = "agentTaskUnassignProgrammatic")
	public AgentTaskData agentTaskUnassignProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, @InputArgument("reason") String reason,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		return agentTaskService.unassign(taskUuid, reason, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskSplitProgrammatic")
	public List<AgentTaskData> agentTaskSplitProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		List<Map<String, Object>> raw = dfe.getArgument("children");
		return agentTaskService.split(taskUuid, board, toSplitChildren(raw),
				AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	/**
	 * Parse split children at the boundary, where wire shapes belong.
	 *
	 * <p>The service used to take the raw maps and pick them apart itself -- casting Object to
	 * String, testing whether a value happened to be a Number. That put wire knowledge in the
	 * place least able to report a useful error about it, and made the accepted shape invisible
	 * to anyone reading the method.
	 *
	 * <p>Converted the way every other input object in this class is (see the RequiredInput
	 * conversion below): Jackson reads the record, so String-to-UUID and the integer coercions
	 * come for free and a bad field is named in the message rather than surfacing as a
	 * ClassCastException from inside the service.
	 */
	// Package-private rather than private so the conversion has a test of its own: moving the
	// parse out of the service took it out of the integration tests' path, which construct the
	// record directly. A conversion nothing exercises is a conversion that breaks quietly.
	static List<SplitChild> toSplitChildren(List<Map<String, Object>> raw)
			throws RelizaException {
		if (null == raw) return List.of();
		try {
			return raw.stream().map(m -> Utils.OM.convertValue(m, SplitChild.class)).toList();
		} catch (JacksonException e) {
			// Jackson 3 throws its own unchecked JacksonException, NOT the IllegalArgumentException
			// Jackson 2 raised from convertValue. Catching the wrong one let an InvalidFormatException
			// escape to the client verbatim -- the same leak this method exists to stop, wearing a
			// different coat. SplitChildConversionTest is what noticed.
			throw new RelizaException("Could not read split children: " + e.getMessage());
		}
	}

	@DgsData(parentType = "Mutation", field = "agentTaskCompleteProgrammatic")
	public AgentTaskData agentTaskCompleteProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("note") String note,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		return agentTaskService.complete(taskUuid, note, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskCancelProgrammatic")
	public AgentTaskData agentTaskCancelProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("note") String note,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		// The session that registered a task withdraws it while it waits on intake (task RD4-5); the seat
		// cancels, as before, whoever registered it.
		if (null != sessionUuid && sessionUuid.equals(td.getRegisteredBySession())
				&& !agentBoardService.isSeatHolder(board, sessionUuid)) {
			resolveCallingAgent(ctx, sessionUuid);
			return agentTaskService.withdraw(taskUuid, sessionUuid, note, ctx.wu());
		}
		resolveCallingAgent(ctx, sessionUuid);
		if (!agentBoardService.isSeatHolder(board, sessionUuid)) {
			throw new RelizaException("Session does not hold the coordinator seat on board " + board.getName()
					+ ", and did not register " + td.keyOrUuid() + ": only the seat cancels a task, and only its"
					+ " registrant withdraws it while it waits on intake");
		}
		return agentTaskService.cancel(taskUuid, note, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskReopenProgrammatic")
	public AgentTaskData agentTaskReopenProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("role") String role,
			@InputArgument("reason") String reason,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		return agentTaskService.reopen(taskUuid, role, reason, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	// ---------- Programmatic mutations: worker operations ----------

	@DgsData(parentType = "Mutation", field = "agentTaskAssignProgrammatic")
	public WorkerAssignment agentTaskAssignProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("roles") List<String> roles,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentData agent = resolveCallingAgent(ctx, sessionUuid);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		return agentTaskService.assign(taskUuid, board, agent.getUuid(), sessionUuid, roles, ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskSignOffProgrammatic")
	public AgentTaskData agentTaskSignOffProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("outcome") String outcome,
			@InputArgument("note") String note,
			@InputArgument("outputs") List<UUID> outputs,
			@InputArgument("seenInputs") List<UUID> seenInputs,
			@InputArgument("noChange") Boolean noChange,
			@InputArgument("noCode") Boolean noCode,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		resolveCallingAgent(ctx, sessionUuid);
		requireOrgTask(ctx, taskUuid);
		SignOffOutcome oc;
		try {
			oc = SignOffOutcome.valueOf(outcome);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new RelizaException("Unrecognized sign-off outcome: " + outcome);
		}
		// seenInputs null (an older client) skips the check for documents published since the assignment (RD2-34).
		return agentTaskService.signOff(taskUuid, sessionUuid, oc, note,
				null != outputs ? outputs : List.of(), seenInputs, noChange, noCode, ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskReturnProgrammatic")
	public AgentTaskData agentTaskReturnProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("reason") String reason,
			@InputArgument("description") String description,
			@InputArgument("outputs") List<UUID> outputs,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		resolveCallingAgent(ctx, sessionUuid);
		requireOrgTask(ctx, taskUuid);
		TaskReturnReason rr;
		try {
			rr = TaskReturnReason.valueOf(reason);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new RelizaException("Unrecognized return reason: " + reason);
		}
		if (rr == TaskReturnReason.SESSION_CLOSED) {
			throw new RelizaException("SESSION_CLOSED returns are system-generated only");
		}
		return agentTaskService.returnTask(taskUuid, sessionUuid, rr, description,
				null != outputs ? outputs : List.of(), ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskBindExternalRefProgrammatic")
	public AgentTaskData agentTaskBindExternalRefProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("externalRef") String externalRef,
			@InputArgument("sourceUrl") String sourceUrl,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		return agentTaskService.bindExternalRef(taskUuid, board, externalRef, sourceUrl, ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskLinkPrProgrammatic")
	public AgentTaskData agentTaskLinkPrProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("prUrl") String prUrl,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		requireOrgTask(ctx, taskUuid);
		return agentTaskService.linkPr(taskUuid, prUrl, linkerOf(ctx), ctx.wu());
	}

	/**
	 * How a link names who made it (task RD4-19): the link takes a key, not a session, so it is the key's agent, by
	 * its display name and uuid, when the key's identity has exactly one; else the identity's name; else the key.
	 */
	private String linkerOf(ProgKeyContext ctx) {
		Optional<AgentIdentityData> identity = agentIdentityService.findByCredential(
				AgentIdentityCredential.IdentityType.REARM_API_KEY, ctx.apiKeyUuid().toString());
		if (identity.isEmpty()) return "API key " + ctx.apiKeyUuid();
		List<AgentData> roots = agentService.listRootsByAgentIdentity(ctx.orgUuid(), identity.get().getUuid());
		if (1 == roots.size()) {
			AgentData a = roots.get(0);
			String name = StringUtils.firstNonBlank(a.getDisplayName(), a.getName());
			return (null == name ? "agent" : name + " (agent") + " " + a.getUuid().toString().substring(0, 8)
					+ (null == name ? "" : ")");
		}
		return StringUtils.isNotBlank(identity.get().getName()) ? identity.get().getName() : "API key " + ctx.apiKeyUuid();
	}

	@DgsData(parentType = "Mutation", field = "agentTaskLinkReleaseProgrammatic")
	public AgentTaskData agentTaskLinkReleaseProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("releaseUuid") UUID releaseUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		requireOrgTask(ctx, taskUuid);
		return agentTaskService.linkRelease(taskUuid, releaseUuid, ctx.wu());
	}

	// ---------- JWT operator surface ----------

	@DgsData(parentType = "Query", field = "agentBoardsOfOrg")
	public List<AgentBoardData> agentBoardsOfOrg(@InputArgument("orgUuid") UUID orgUuid, DgsDataFetchingEnvironment dfe) throws RelizaException {
		// Only the boards the person may read (board-permissions.md §4.4): none without a board function.
		if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) personalKey(dfe, orgUuid);
		List<AgentBoardData> readable = new java.util.ArrayList<>();
		for (AgentBoardData bd : agentBoardService.listByOrg(orgUuid)) {
			if (personReads(dfe, bd)) readable.add(bd);
		}
		return readable;
	}

	@DgsData(parentType = "Query", field = "agentBoard")
	public AgentBoardData agentBoard(@InputArgument("uuid") UUID uuid, DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(uuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + uuid));
		authorizePersonOnBoard(dfe, bd, PERSON_READ);
		numbered(uuid);
		return agentBoardService.getBoardData(uuid).orElse(bd);
	}

	@DgsData(parentType = "Query", field = "agentBoardEvents")
	public AgentBoardService.EventPage agentBoardEvents(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("after") Long after, @InputArgument("since") ZonedDateTime since,
			@InputArgument("limit") Integer limit, DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		authorizePersonOnBoard(dfe, bd, PERSON_READ);
		return agentBoardService.eventsOf(bd.getUuid(), after, since, limit);
	}

	@DgsData(parentType = "Query", field = "agentTasksByUuid")
	public List<AgentTaskData> agentTasksByUuid(@InputArgument("taskUuids") List<UUID> taskUuids,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		List<AgentTaskData> found = agentTaskService.getTasksData(taskUuids);
		// Read access per board among what was found (board-permissions.md §4.4); tasks on a board the
		// person cannot read are absent, as unknown ones are. A credential that is not a person is
		// still refused.
		Set<UUID> readable = new HashSet<>();
		for (UUID board : found.stream().map(AgentTaskData::getBoard).distinct().toList()) {
			Optional<AgentBoardData> bd = agentBoardService.getBoardData(board);
			if (bd.isPresent() && personReads(dfe, bd.get())) readable.add(board);
		}
		return found.stream().filter(td -> readable.contains(td.getBoard())).toList();
	}

	/** Without the group and tag filters, as callers from before them read. */
	public List<AgentTaskData> agentTasksOfBoard(UUID boardUuid, String status, ZonedDateTime changedSince,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		return agentTasksOfBoard(boardUuid, status, changedSince, null, null, dfe);
	}

	@DgsData(parentType = "Query", field = "agentTasksOfBoard")
	public List<AgentTaskData> agentTasksOfBoard(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("status") String status, @InputArgument("changedSince") ZonedDateTime changedSince,
			@InputArgument("group") String group, @InputArgument("tag") List<String> tags,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		authorizePersonOnBoard(dfe, bd, PERSON_READ);
		numbered(boardUuid);
		return inGroupWithTags(bd, agentTaskService.listByBoard(boardUuid, status, changedSince), group, tags);
	}

	/**
	 * The tasks in the group by key and carrying any of the tags (task RD2-29); either filter absent
	 * takes every task. A key the board has no group for is refused, so a typo is not an empty list.
	 */
	static List<AgentTaskData> inGroupWithTags(AgentBoardData board, List<AgentTaskData> tasks, String group,
			List<String> tags) throws RelizaException {
		UUID groupUuid = null;
		if (StringUtils.isNotBlank(group)) {
			String k = group.strip().toLowerCase(java.util.Locale.ROOT);
			groupUuid = board.groupByKey(k).map(AgentBoardData.TaskGroup::uuid)
					.orElseThrow(() -> new RelizaException("no group " + k + " on board " + board.getName()));
		}
		Set<String> wanted = new HashSet<>();
		if (null != tags) for (String t : tags) if (StringUtils.isNotBlank(t)) wanted.add(t.strip().toLowerCase(java.util.Locale.ROOT));
		final UUID g = groupUuid;
		return tasks.stream()
				.filter(t -> null == g || g.equals(t.getGroup()))
				.filter(t -> wanted.isEmpty() || (null != t.getTags()
						&& t.getTags().stream().anyMatch(x -> wanted.contains(x.key()))))
				.toList();
	}

	/** Tags from their input maps; null when the field was not sent. */
	static List<CommonVariables.TagRecord> parseTags(Object raw) throws RelizaException {
		if (null == raw) return null;
		if (!(raw instanceof List<?> list)) throw new RelizaException("tags must be a list");
		List<CommonVariables.TagRecord> out = new ArrayList<>();
		for (Object o : list) {
			if (!(o instanceof Map<?, ?> m)) throw new RelizaException("Invalid tag: " + o);
			out.add(new CommonVariables.TagRecord((String) m.get("key"), (String) m.get("value")));
		}
		return out;
	}

	/** A group input from its map (task RD2-29): a key that is sent as null for defaultWorkLevel clears it. */
	static io.reliza.service.AgentTaskGroupService.GroupInput groupInputOf(Map<String, Object> m) throws RelizaException {
		if (null == m) throw new RelizaException("group is required");
		Object deps = m.get("dependsOn");
		Object status = m.get("status");
		return new io.reliza.service.AgentTaskGroupService.GroupInput(
				parseUuid((String) m.get("uuid"), "uuid"), (String) m.get("key"), (String) m.get("name"),
				(String) m.get("description"), m.get("order") instanceof Number n ? n.intValue() : null,
				deps instanceof List<?> l ? l.stream().map(x -> null == x ? null : x.toString()).toList() : null,
				m.get("defaultWorkLevel") instanceof Number lv ? lv.intValue() : null, m.containsKey("defaultWorkLevel"),
				null == status ? null : AgentBoardData.GroupStatus.valueOf(status.toString()));
	}

	// ---------- Groups and tags (task-groups-and-tags.md §2-§3, task RD2-29) ----------

	/** A person creates or edits a group: board configuration, CONFIGURATION_WRITE and BOARD_WRITE. */
	@DgsData(parentType = "Mutation", field = "agentBoardGroupSet")
	public GroupView agentBoardGroupSet(@InputArgument("boardUuid") UUID boardUuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		PersonContext jc = authorizePersonOnBoard(dfe, bd, PERSON_CONFIGURE);
		agentTaskGroupService.setGroup(boardUuid, groupInputOf(dfe.getArgument("group")), jc.wu());
		return groupViewAfter(boardUuid, dfe.getArgument("group"));
	}

	/** A person deletes an empty group. */
	@DgsData(parentType = "Mutation", field = "agentBoardGroupDelete")
	public Boolean agentBoardGroupDelete(@InputArgument("boardUuid") UUID boardUuid, @InputArgument("key") String key,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		PersonContext jc = authorizePersonOnBoard(dfe, bd, PERSON_CONFIGURE);
		agentTaskGroupService.deleteGroup(boardUuid, key, jc.wu());
		return true;
	}

	/** The coordinator seat creates or edits a group of its board. */
	@DgsData(parentType = "Mutation", field = "agentBoardGroupSetProgrammatic")
	public GroupView agentBoardGroupSetProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentBoardData board = requireOrgBoard(ctx, boardUuid);
		requireSeatHolder(ctx, board, sessionUuid);
		agentTaskGroupService.setGroup(boardUuid, groupInputOf(dfe.getArgument("group")), ctx.wu());
		return groupViewAfter(boardUuid, dfe.getArgument("group"));
	}

	private GroupView groupViewAfter(UUID boardUuid, Map<String, Object> input) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		String key = io.reliza.service.AgentTaskGroupService.checkedGroupKey((String) input.get("key"));
		AgentBoardData.TaskGroup g = bd.groupByKey(key).orElseThrow(() -> new RelizaException("No group " + key));
		return groupViews(bd, agentTaskService.listByBoard(boardUuid, null), null).stream()
				.filter(v -> v.uuid().equals(g.uuid())).findFirst().orElseThrow();
	}

	/** A person moves a task into a group by key, or out of every group with null. */
	@DgsData(parentType = "Mutation", field = "agentTaskSetGroup")
	public AgentTaskData agentTaskSetGroup(@InputArgument("taskUuid") UUID taskUuid, @InputArgument("group") String group,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.setGroup(taskUuid, group, jc.actor(), jc.wu());
	}

	/** The coordinator seat moves a task into a group, or out of every group. */
	@DgsData(parentType = "Mutation", field = "agentTaskSetGroupProgrammatic")
	public AgentTaskData agentTaskSetGroupProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, @InputArgument("group") String group,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		return agentTaskService.setGroup(taskUuid, group, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	/** A person replaces a task's tags. */
	@DgsData(parentType = "Mutation", field = "agentTaskSetTags")
	public AgentTaskData agentTaskSetTags(@InputArgument("taskUuid") UUID taskUuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		List<CommonVariables.TagRecord> tags = parseTags(dfe.getArgument("tags"));
		return agentTaskService.setTags(taskUuid, null == tags ? List.of() : tags, jc.actor(), jc.wu());
	}

	/** The coordinator seat replaces a task's tags. */
	@DgsData(parentType = "Mutation", field = "agentTaskSetTagsProgrammatic")
	public AgentTaskData agentTaskSetTagsProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		List<CommonVariables.TagRecord> tags = parseTags(dfe.getArgument("tags"));
		return agentTaskService.setTags(taskUuid, null == tags ? List.of() : tags, AgentActor.ofSession(sessionUuid),
				ctx.wu());
	}

	/** How a group reads (task RD2-29): its dependencies by key, its progress, and what its tasks spent. */
	public record GroupView(UUID uuid, String key, String name, String description, int order, List<String> dependsOn,
			Integer defaultWorkLevel, AgentBoardData.GroupStatus status, ZonedDateTime createdAt, GroupProgress progress,
			Long spentMicros) {}

	public record GroupProgress(int total, int done, int open, boolean complete) {}

	/** The group a task belongs to, as a task reads it. */
	public record GroupRef(UUID uuid, String key, String name) {}

	/**
	 * The board's groups in display order. {@code spend} is the board's priced rows, or null when the
	 * reader did not ask what the groups spent.
	 */
	static List<GroupView> groupViews(AgentBoardData bd, List<AgentTaskData> tasks,
			io.reliza.service.AgentBudgetService.BoardSpend spend) {
		if (null == bd.getGroups()) return List.of();
		List<GroupView> out = new ArrayList<>();
		for (AgentBoardData.TaskGroup g : bd.getGroups().stream()
				.sorted(java.util.Comparator.comparingInt(AgentBoardData.TaskGroup::order)
						.thenComparing(AgentBoardData.TaskGroup::key)).toList()) {
			List<AgentTaskData> mine = tasks.stream().filter(t -> g.uuid().equals(t.getGroup())).toList();
			int done = (int) mine.stream().filter(t -> t.getStatus() == AgentTaskData.TaskStatus.COMPLETED
					|| t.getStatus() == AgentTaskData.TaskStatus.CANCELLED).count();
			Long spent = null;
			if (null != spend) {
				long sum = 0;
				for (AgentTaskData t : mine) sum += spend.spentOn(t);
				spent = sum;
			}
			List<String> deps = g.dependsOn().stream()
					.map(u -> bd.groupByUuid(u).map(AgentBoardData.TaskGroup::key).orElse(u.toString())).toList();
			out.add(new GroupView(g.uuid(), g.key(), g.name(), g.description(), g.order(), deps, g.defaultWorkLevel(),
					g.status(), g.createdAt(), new GroupProgress(mine.size(), done, mine.size() - done,
							!mine.isEmpty() && done == mine.size()), spent));
		}
		return out;
	}

	/**
	 * The Usage tab's breakdown (task RD2-8). The board was read by a caller allowed to read it, and
	 * its spend reads with it (d8e7bd7e T-1), as agentBoardUsage does.
	 */
	@DgsData(parentType = "AgentBoard", field = "spendBreakdown")
	public io.reliza.service.AgentBudgetService.SpendBreakdown boardSpendBreakdown(DgsDataFetchingEnvironment dfe,
			@InputArgument("from") java.time.ZonedDateTime from, @InputArgument("to") java.time.ZonedDateTime to) {
		AgentBoardData board = dfe.getSource();
		if (null == board) return null;
		// Bound as agentBoardUsage binds them: the DateTime scalar yields an OffsetDateTime, which
		// dfe.getArgument cast to ZonedDateTime and threw for every window (RD2-8 tester run 1, T-1).
		return agentBudgetService.breakdown(board, from, to);
	}

	/**
	 * The Agents tab (task RD3-5): who works the board, read with the board by a caller allowed to read it, as
	 * its spend is.
	 */
	@DgsData(parentType = "AgentBoard", field = "agents")
	public List<io.reliza.service.BoardAgentsService.AgentRow> boardAgents(DgsDataFetchingEnvironment dfe,
			@InputArgument("from") java.time.ZonedDateTime from, @InputArgument("to") java.time.ZonedDateTime to) {
		AgentBoardData board = dfe.getSource();
		if (null == board) return null;
		return boardAgentsService.agents(board, from, to, java.time.ZonedDateTime.now());
	}

	/**
	 * The board's newest events, from the log (task RD3-2): the row no longer carries them. Newest 50,
	 * oldest first, the shape the row's window had, so the panel's feed and board show read as before.
	 * Batched: the boards list asks for every board's feed, and the loader reads the log once for all.
	 */
	@DgsData(parentType = "AgentBoard", field = "events")
	public java.util.concurrent.CompletionStage<List<AgentBoardData.BoardEvent>> agentBoardEvents(
			DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		if (null == bd || null == bd.getUuid()) return java.util.concurrent.CompletableFuture.completedFuture(List.of());
		org.dataloader.DataLoader<UUID, List<AgentBoardData.BoardEvent>> loader = dfe.getDataLoader("boardRecentEventsLoader");
		return loader.load(bd.getUuid());
	}

	/** Its own bean, with its own dependency: the fetcher it sits in is proxied. */
	@com.netflix.graphql.dgs.DgsDataLoader(name = "boardRecentEventsLoader")
	public static class BoardRecentEventsBatchLoader implements org.dataloader.BatchLoader<UUID, List<AgentBoardData.BoardEvent>> {
		@Autowired private AgentBoardService agentBoardService;

		@Override
		public java.util.concurrent.CompletionStage<List<List<AgentBoardData.BoardEvent>>> load(List<UUID> boards) {
			Map<UUID, List<AgentBoardData.BoardEvent>> recent = agentBoardService.recentEvents(boards);
			List<List<AgentBoardData.BoardEvent>> out = new ArrayList<>(boards.size());
			for (UUID b : boards) out.add(recent.getOrDefault(b, List.of()));
			return java.util.concurrent.CompletableFuture.completedFuture(out);
		}
	}

	/**
	 * A role's prompt as it is served (task RD3-9): the prompt, then the board's routing rules -- the same
	 * composition assign hands over, read without taking a task. Whoever may read the role config reads it.
	 */
	@DgsData(parentType = "AgentTaskRoleConfig", field = "servedPrompt")
	public String roleServedPrompt(DgsDataFetchingEnvironment dfe) {
		AgentTaskRoleConfigData rc = dfe.getSource();
		if (null == rc || null == rc.getBoard()) return null;
		return agentBoardService.getBoardData(rc.getBoard())
				.map(bd -> agentBoardService.servedPromptFor(bd, rc.getPrompt())).orElse(null);
	}

	@DgsData(parentType = "AgentTaskRoleConfig", field = "promptVersion")
	public String rolePromptVersion(DgsDataFetchingEnvironment dfe) {
		AgentTaskRoleConfigData rc = dfe.getSource();
		if (null == rc) return null;
		// On a board with a ladder the version covers the section (task RD3-6), as a hop records it.
		AgentBoardData bd = null == rc.getBoard() ? null : agentBoardService.getBoardData(rc.getBoard()).orElse(null);
		return AgentBoardService.promptVersion(bd, rc.getPrompt());
	}

	@DgsData(parentType = "AgentBoard", field = "groups")
	public List<GroupView> agentBoardGroups(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		if (null == bd || null == bd.getGroups() || bd.getGroups().isEmpty()) return List.of();
		// The spend is a read of the board's usage rows; only when the reader asked for it.
		io.reliza.service.AgentBudgetService.BoardSpend spend = dfe.getSelectionSet().contains("spentMicros")
				? agentBudgetService.boardSpend(bd) : null;
		return groupViews(bd, agentTaskService.listByBoard(bd.getUuid(), null), spend);
	}

	@DgsData(parentType = "AgentTask", field = "group")
	public GroupRef agentTaskGroup(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		if (null == td || null == td.getGroup()) return null;
		AgentBoardData board = boardOfTask(dfe, td);
		return null == board ? null : board.groupByUuid(td.getGroup())
				.map(g -> new GroupRef(g.uuid(), g.key(), g.name())).orElse(null);
	}

	@DgsData(parentType = "AgentTask", field = "waitingOnGroups")
	public List<String> agentTaskWaitingOnGroups(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		if (null == td || null == td.getGroup()) return List.of();
		AgentBoardData board = boardOfTask(dfe, td);
		if (null == board) return List.of();
		// The board's tasks once per request, however many of its tasks the reply carries.
		Map<UUID, List<AgentTaskData>> tasks = dfe.getGraphQlContext()
				.computeIfAbsent("waitingOnGroupsTasks", k -> new java.util.concurrent.ConcurrentHashMap<>());
		return agentTaskService.waitingOnGroups(td, board,
				tasks.computeIfAbsent(board.getUuid(), b -> agentTaskService.listByBoard(b, null)));
	}

	/** A task's board, read once per request (the same cache effectiveWorkLevel uses). */
	private AgentBoardData boardOfTask(DgsDataFetchingEnvironment dfe, AgentTaskData td) {
		Map<UUID, Optional<AgentBoardData>> boards = dfe.getGraphQlContext()
				.computeIfAbsent("effectiveWorkLevelBoards", k -> new java.util.concurrent.ConcurrentHashMap<>());
		return boards.computeIfAbsent(td.getBoard(), agentBoardService::getBoardData).orElse(null);
	}

	/** When the task last changed (task 9540d3b6): the row's update time, moved by every save. */
	@DgsData(parentType = "AgentTask", field = "updatedAt")
	public ZonedDateTime taskUpdatedAt(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return null == td ? null : td.getUpdatedDate();
	}

	/**
	 * One task, so the task page loads from a deep link with nothing but the uuid. Gated on the
	 * task's org exactly as {@link #agentBoard} is on the board's.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentTask")
	public AgentTaskData agentTask(@InputArgument("uuid") UUID uuid) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(uuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + uuid));
		authorizePersonOnTask(null, td, PERSON_READ);
		numbered(td.getBoard());
		return agentTaskService.getTaskData(uuid).orElse(td);
	}

	@DgsData(parentType = "Query", field = "agentTaskRoleConfigsOfBoard")
	public List<AgentTaskRoleConfigData> agentTaskRoleConfigsOfBoard(
			@InputArgument("boardUuid") UUID boardUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		authorizePersonOnBoard(dfe, bd, PERSON_READ);
		return agentBoardService.listRoleConfigs(boardUuid);
	}

	/**
	 * The earlier revisions of a task, board or role config (22ddc644). Admin-only, as the board's
	 * settings are (D11): a revision carries the prompts, notes and holds the task had then. Gated
	 * on the live row's org; an unknown uuid is not found and checks nothing.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentTaskHistory")
	public List<AgentTaskRevision> agentTaskHistory(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("limit") Integer limit, @InputArgument("offset") Integer offset) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		authorizePersonOnTask(null, td, PERSON_READ);
		return agentAuditReadService.taskHistory(taskUuid, limit, offset);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentBoardHistory")
	public List<AgentBoardRevision> agentBoardHistory(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("limit") Integer limit, @InputArgument("offset") Integer offset) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		authorizePersonOnBoard(null, bd, PERSON_READ);
		return agentAuditReadService.boardHistory(boardUuid, limit, offset);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentRoleConfigHistory")
	public List<AgentRoleConfigRevision> agentRoleConfigHistory(
			@InputArgument("roleConfigUuid") UUID roleConfigUuid,
			@InputArgument("limit") Integer limit, @InputArgument("offset") Integer offset) throws RelizaException {
		AgentTaskRoleConfigData rcd = agentBoardService.getRoleConfigData(roleConfigUuid)
				.orElseThrow(() -> new RelizaException("Role config not found: " + roleConfigUuid));
		// A board's role reads with the board; an organization's preset is an org object and keeps its check.
		if (null != rcd.getBoard()) {
			authorizePersonOnBoard(null, agentBoardService.getBoardData(rcd.getBoard())
					.orElseThrow(() -> new RelizaException("Board not found: " + rcd.getBoard())), PERSON_READ);
		} else {
			authorizeJwtOrg(rcd.getOrg(), CallType.ADMIN);
		}
		return agentAuditReadService.roleConfigHistory(roleConfigUuid, limit, offset);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentBoardCreate")
	public AgentBoardData agentBoardCreate(@InputArgument("orgUuid") UUID orgUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> input = dfe.getArgument("input");
		// Who may create a board (architecture d8e7bd7e §3.2): in perspectives, the consent rule checks
		// each one when the board is created; in none, BOARD_WRITE and CONFIGURATION_WRITE at the
		// organization, since nothing narrower covers a board that hangs off nothing.
		List<UUID> inPerspectives = uuidList(input.get("perspectives"));
		WhoUpdated wu;
		if (null == inPerspectives || inPerspectives.isEmpty()) {
			authorizeJwtOrgCtx(orgUuid, CallType.WRITE, PermissionFunction.CONFIGURATION_WRITE);
			wu = authorizeJwtOrgCtx(orgUuid, CallType.WRITE, PermissionFunction.BOARD_WRITE).wu();
		} else {
			wu = authorizeJwtOrg(orgUuid, CallType.READ);
		}
		// Settings are checked before the board exists: refused, nothing is created (T-2).
		if (input.get("settings") instanceof Map<?, ?> settings) {
			agentBoardService.checkSettingsInput(orgUuid, (String) input.get("name"), stringKeyed(settings));
		}
		// And the default level against the ladder the board will have (task RD3-6).
		agentBoardService.checkDefaultOnLadderInput(null,
				input.get("settings") instanceof Map<?, ?> settings ? stringKeyed(settings) : null,
				true, (Integer) input.get("defaultWorkLevel"));
		// So is the merge procedure, against the roles the board will be seeded with (task 71a3dd22).
		agentBoardService.checkDeliveryInput(orgUuid, null, input.get("deliveryPolicy") instanceof Map<?, ?>,
				deliveryPolicyOf(input.get("deliveryPolicy")), coordinatorCapabilitiesPatch(input),
				Boolean.TRUE.equals(input.get("seedFromPresets")));
		AgentBoardData created;
		try {
			created = agentBoardService.createBoard(orgUuid,
					(String) input.get("name"),
					(String) input.get("description"),
					stringList(input.get("sources")),
					(String) input.get("coordinatorPrompt"),
					(Integer) input.get("perAgentWipLimit"),
					parsePriorityType(input.get("priorityType")),
					parseUuid((String) input.get("target"), "target"),
					(Integer) input.get("defaultWorkLevel"),
					(String) input.get("taskPrefix"),
					uuidList(input.get("perspectives")),
					personConsent(orgUuid),
					wu);
		} catch (AgentBoardService.TaskPrefixTaken taken) {
			// A prefix of a board the person does not see is refused without naming it (review S-1, #765).
			throw taken.forCaller(personAccess(orgUuid));
		}
		if (Boolean.TRUE.equals(input.get("seedFromPresets"))) {
			created = agentBoardService.seedFromPresets(created, wu);
		}
		if (input.get("elementFamilies") instanceof Map<?, ?> families) {
			created = agentBoardService.setElementFamilies(created.getUuid(), objectMap(families), wu);
		}
		if (input.get("elementCheckPolicy") instanceof Map<?, ?> policy) {
			created = agentBoardService.setElementCheckPolicy(created.getUuid(), elementCheckPolicyOf(policy), wu);
		}
		if (input.get("deliveryPolicy") instanceof Map<?, ?> delivery) {
			created = agentBoardService.setDeliveryPolicy(created.getUuid(), deliveryPolicyOf(delivery), wu);
		}
		if (input.get("documents") instanceof Map<?, ?> documents) {
			created = agentBoardService.setDocumentsBlock(created.getUuid(), stringKeyed(documents), wu);
		}
		List<AgentTaskRoleConfigData.AgentCapability> coordinatorCaps = coordinatorCapabilitiesPatch(input);
		if (null != coordinatorCaps) {
			created = agentBoardService.setCoordinatorCapabilities(created.getUuid(), coordinatorCaps, wu);
		}
		// A board created from the form with its budget and stops from the start (task 40f270be).
		if (input.get("settings") instanceof Map<?, ?> settings) {
			created = agentBoardService.updateSettingsFromInput(created.getUuid(), stringKeyed(settings), wu);
		}
		// And with its groups (task RD2-30); a new board has none to clear.
		if (input.get("groups") instanceof List<?> groups && !groups.isEmpty()) {
			created = agentBoardService.setGroupsFromInput(created.getUuid(), groupsInput(groups), wu);
		}
		return created;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentBoardUpdate")
	public AgentBoardData agentBoardUpdate(@InputArgument("boardUuid") UUID boardUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		PersonContext person = authorizePersonOnBoard(dfe, bd, PERSON_CONFIGURE);
		WhoUpdated wu = person.wu();
		Map<String, Object> input = dfe.getArgument("input");
		// Settings are checked before the rest of the form is written: refused, nothing changes (T-2).
		if (input.get("settings") instanceof Map<?, ?> settings) {
			agentBoardService.checkSettingsInput(bd.getOrg(), bd.getName(), stringKeyed(settings));
		}
		// And the default level against the ladder, each as the board will have it (task RD3-6).
		agentBoardService.checkDefaultOnLadderInput(bd,
				input.get("settings") instanceof Map<?, ?> settings ? stringKeyed(settings) : null,
				input.containsKey("defaultWorkLevel"), (Integer) input.get("defaultWorkLevel"));
		// So is the merge procedure with the capabilities it needs, as the board will have them (task 71a3dd22).
		agentBoardService.checkDeliveryInput(bd.getOrg(), bd, input.containsKey("deliveryPolicy"),
				deliveryPolicyOf(input.get("deliveryPolicy")), coordinatorCapabilitiesPatch(input), false);
		AgentBoardData.BoardStatus status = null;
		if (input.get("status") instanceof String s && StringUtils.isNotBlank(s)) {
			status = AgentBoardData.BoardStatus.valueOf(s);
		}
		AgentBoardData updated = agentBoardService.updateBoard(boardUuid,
				(String) input.get("description"),
				stringList(input.get("sources")),
				(String) input.get("coordinatorPrompt"),
				(Integer) input.get("perAgentWipLimit"),
				parsePriorityType(input.get("priorityType")),
				status,
				parseUuid((String) input.get("target"), "target"),
				null, // defaultWorkLevel below: sent as null it clears (RD2-1)
				wu);
		if (input.containsKey("defaultWorkLevel")) {
			updated = agentBoardService.setDefaultWorkLevel(boardUuid, (Integer) input.get("defaultWorkLevel"), wu);
		}
		// Applied AFTER the sources patch in the same call, so an operator can add the repository
		// to sources and name it as the documents repo in one request. Ordered the other way, the
		// "must be one of sources" check would refuse the very patch that satisfies it.
		// Partial patch: absent leaves the families, {} restores the defaults.
		if (input.get("elementFamilies") instanceof Map<?, ?> families) {
			updated = agentBoardService.setElementFamilies(boardUuid, objectMap(families), wu);
		}
		// Partial patch: absent leaves the policy, null restores the defaults.
		if (input.containsKey("elementCheckPolicy")) {
			updated = agentBoardService.setElementCheckPolicy(boardUuid, elementCheckPolicyOf(input.get("elementCheckPolicy")), wu);
		}
		// Partial patch too: absent leaves how the board proves delivery, null restores PR_ROWS.
		if (input.containsKey("deliveryPolicy")) {
			updated = agentBoardService.setDeliveryPolicy(boardUuid, deliveryPolicyOf(input.get("deliveryPolicy")), wu);
		}
		// Perspectives (board-permissions.md §3): absent leaves them, null clears; the rules and the
		// caller's consent are the service's.
		if (input.containsKey("perspectives")) {
			updated = agentBoardService.setPerspectives(boardUuid, uuidList(input.get("perspectives")),
					personConsent(bd.getOrg()), wu);
		}
		// Absent leaves the task-key prefix; null derives one anew; either way old keys stay (D10).
		if (input.containsKey("taskPrefix")) {
			try {
				updated = agentBoardService.setTaskPrefix(boardUuid, (String) input.get("taskPrefix"), wu);
			} catch (AgentBoardService.TaskPrefixTaken taken) {
				throw taken.forCaller(personAccess(person, bd.getOrg()));
			}
		}
		// Partial patch, as a board file's block (D15): absent leaves it; documents or its prefix sent
		// as null restores the default.
		if (input.containsKey("documents")) {
			updated = agentBoardService.setDocumentsBlock(boardUuid,
					input.get("documents") instanceof Map<?, ?> m ? stringKeyed(m) : null, wu);
		}

		if (null != input.get("documentsRepo") || null != input.get("documentPaths")) {
			updated = agentBoardService.setDocumentsConfig(boardUuid,
					(String) input.get("documentsRepo"),
					parseDocumentPaths(input.get("documentPaths")), wu);
		}
		List<AgentTaskRoleConfigData.AgentCapability> coordinatorCaps = coordinatorCapabilitiesPatch(input);
		if (null != coordinatorCaps) {
			updated = agentBoardService.setCoordinatorCapabilities(boardUuid, coordinatorCaps, wu);
		}
		// Budget and stops from the form (task 40f270be), checked and applied as a board file's are.
		if (input.get("settings") instanceof Map<?, ?> settings) {
			updated = agentBoardService.updateSettingsFromInput(boardUuid, stringKeyed(settings), wu);
		}
		// The group list (task RD2-30), with a board file's rules: absent leaves the groups; null or []
		// deletes them, refused while one holds tasks; a group the list leaves out is closed.
		if (input.containsKey("groups")) {
			updated = agentBoardService.setGroupsFromInput(boardUuid,
					input.get("groups") instanceof List<?> groups ? groupsInput(groups) : null, wu);
		}
		return updated;
	}

	/** AgentBoardInput.groups as string-keyed maps, members sent as null kept: they clear. */
	static List<Map<String, Object>> groupsInput(List<?> groups) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object o : groups) {
			if (o instanceof Map<?, ?> m) out.add(stringKeyed(m));
		}
		return out;
	}

	/** A list of ID inputs as uuids; null is empty. */
	static List<UUID> uuidList(Object raw) throws RelizaException {
		List<UUID> out = new ArrayList<>();
		if (!(raw instanceof List<?> l)) return out;
		for (Object o : l) {
			if (null == o) continue;
			out.add(parseUuid(String.valueOf(o), "perspectives"));
		}
		return out;
	}

	/** A GraphQL input object as the string-keyed map the spec readers take; nulls kept, they clear. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> stringKeyed(Map<?, ?> m) {
		Map<String, Object> out = new LinkedHashMap<>();
		((Map<Object, Object>) m).forEach((k, v) -> out.put(String.valueOf(k), v));
		return out;
	}

	/**
	 * Partial patch of an AgentBoardInput's coordinatorCapabilities: null (absent or explicitly null)
	 * leaves the board's list unchanged, [] clears it, anything else replaces it.
	 */
	static List<AgentTaskRoleConfigData.AgentCapability> coordinatorCapabilitiesPatch(Map<String, Object> input) {
		if (!(input.get("coordinatorCapabilities") instanceof List<?> l)) return null;
		List<AgentTaskRoleConfigData.AgentCapability> out = new ArrayList<>();
		for (Object o : l) out.add(AgentTaskRoleConfigData.AgentCapability.valueOf(String.valueOf(o)));
		return out;
	}

	/** Path templates arrive as a plain object keyed by specification type name. */
	private static Map<RearmSpecificationType, String> parseDocumentPaths(Object raw) throws RelizaException {
		if (!(raw instanceof Map<?, ?> m)) return null;
		Map<RearmSpecificationType, String> out = new LinkedHashMap<>();
		for (Map.Entry<?, ?> e : m.entrySet()) {
			RearmSpecificationType spec = RearmSpecificationType.fromValue(String.valueOf(e.getKey()));
			if (null == spec) {
				throw new RelizaException("Unrecognized specification type in documentPaths: " + e.getKey());
			}
			out.put(spec, String.valueOf(e.getValue()));
		}
		return out;
	}

	@DgsData(parentType = "Mutation", field = "agentBoardOperatorPause")
	public AgentBoardData agentBoardOperatorPause(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("pause") Boolean pause,
			@InputArgument("reason") String reason,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		// Ctx rather than the plain authorize: this used to record the literal "operator", so an
		// OPERATOR lock -- the one a coordinator cannot lift -- named nobody. The level already
		// says it was an operator; the actor should say WHICH.
		PersonContext jc = authorizePersonOnBoard(dfe, bd, PERSON_WRITE);
		return agentBoardService.setPause(boardUuid, BoardPauseLevel.OPERATOR,
				Boolean.TRUE.equals(pause), reason, jc.actor(), jc.wu());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentBoardReseedCoordinatorPrompt")
	public AgentBoardData agentBoardReseedCoordinatorPrompt(
			@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("presetName") String presetName) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		PersonContext jc = authorizePersonOnBoard(null, bd, PERSON_CONFIGURE);
		return agentBoardService.reseedCoordinatorPrompt(boardUuid, presetName, jc.actor(),
				jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskSetStrength")
	public AgentTaskData agentTaskSetStrength(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("requiredStrength") Double requiredStrength,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.setRequiredStrength(taskUuid, requiredStrength, jc.actor(), jc.wu());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentSessionForceClose")
	public AgentSessionData agentSessionForceClose(@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("reason") String reason, DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		PersonContext jc = authorizeSessionForceClose(dfe, sd);
		// Who and why (task 6e7fe6fe): the person, and their reason when they gave one.
		String why = StringUtils.isBlank(reason) ? "force-closed by " + jc.actor().display()
				: "force-closed by " + jc.actor().display() + ": " + reason.strip();
		return agentSessionService.close(sessionUuid, jc.actor(), why, jc.wu());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentTaskRoleConfigSet")
	public AgentTaskRoleConfigData agentTaskRoleConfigSet(@InputArgument("boardUuid") UUID boardUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		WhoUpdated wu = authorizePersonOnBoard(dfe, bd, PERSON_CONFIGURE).wu();
		return upsertRoleConfigFromInput(bd, dfe.getArgument("input"), true, wu);
	}

	// ---------- v1.1: holds, board events, presets ----------

	@DgsData(parentType = "Mutation", field = "agentTaskHoldProgrammatic")
	public AgentTaskData agentTaskHoldProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("reason") String reason,
			@InputArgument("level") String level,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		AgentTaskData.HoldLevel lvl = holdLevelOf(level);
		resolveCallingAgent(ctx, sessionUuid);
		boolean seat = agentBoardService.isSeatHolder(board, sessionUuid);
		boolean holder = !seat && null != td.getAssignment() && sessionUuid.equals(td.getAssignment().session());
		// The session working the task parks its own hop for a person, at OPERATOR level only (task RD4-5); the
		// coordinator seat parks a task nobody is working the same way, for a decision it cannot make (RD4-17).
		if (AgentTaskData.HoldLevel.OPERATOR == lvl) {
			if (seat) {
				requireKeyOnBoard(ctx, board, PermissionFunction.BOARD_WRITE, CallType.WRITE);
				return agentTaskService.parkForOperator(taskUuid, sessionUuid, reason, ctx.wu());
			}
			if (!holder) {
				throw new RelizaException("Only the session holding the task's current assignment, or the coordinator"
						+ " seat, parks it for the operator; this session does neither on " + td.keyOrUuid());
			}
			requireKeyOnBoard(ctx, board, PermissionFunction.BOARD_AGENT, CallType.ESSENTIAL_READ);
			return agentTaskService.parkHopForOperator(taskUuid, sessionUuid, reason, ctx.wu());
		}
		if (holder) {
			throw new RelizaException("A COORDINATOR hold is the coordinator seat's; the session working "
					+ td.keyOrUuid() + " parks its hop at OPERATOR level only, with the question as the reason");
		}
		requireSeatHolder(ctx, board, sessionUuid);
		requireKeyOnBoard(ctx, board, PermissionFunction.BOARD_WRITE, CallType.WRITE);
		// A delivery waits on a merge or a decision, never on the coordinator (RD4-17): a decision about it is a
		// person's, asked with a question at OPERATOR level, which returns it to DELIVERING when answered.
		if (AgentTaskData.TaskStatus.DELIVERING == td.getStatus()) {
			throw new RelizaException("Task " + td.keyOrUuid() + " is DELIVERING: the seat parks it only for the"
					+ " operator, with the question (task hold --operator --question), and a person's answer returns it"
					+ " to DELIVERING");
		}
		// A frame with no answering role is the board saying nobody here produces what is being
		// asked about, which is the escalation the QUESTION kind exists for (D12). Holding it
		// marks the hold so a later release with words is read as the answer rather than dropped.
		if (!td.getQuestionStack().isEmpty()
				&& null == td.getQuestionStack().get(td.getQuestionStack().size() - 1)
						.answeringRole()) {
			return agentTaskService.escalateQuestion(taskUuid, AgentTaskData.HoldLevel.COORDINATOR,
					reason, AgentActor.ofSession(sessionUuid), ctx.wu());
		}
		return agentTaskService.hold(taskUuid, AgentTaskData.HoldLevel.COORDINATOR, reason,
				AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	/**
	 * A delivery declared by an agent (task 18c5c293): the coordinator seat, or a session that worked
	 * this task in a role that merges (PR_MERGE). Anyone else is refused: the declaration is what
	 * completes the task.
	 */
	@DgsData(parentType = "Mutation", field = "agentTaskDeclareDeliveryProgrammatic")
	public AgentTaskData agentTaskDeclareDeliveryProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, @InputArgument("unit") String unit,
			@InputArgument("commit") String commit, @InputArgument("outcome") String outcome,
			@InputArgument("note") String note, DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		resolveCallingAgent(ctx, sessionUuid);
		if (!agentBoardService.isSeatHolder(board, sessionUuid) && !mergedHere(td, board, sessionUuid)) {
			throw new AccessDeniedException("Only the coordinator seat, or a session that worked this task in a role"
					+ " that merges (PR_MERGE), declares its delivery");
		}
		return agentTaskService.declareDelivery(taskUuid, unit, commit, deliveryOutcomeOf(outcome), note,
				AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	/**
	 * Declare a linked PR superseded by its replacement (task RD3-13). Who may (RD3-18, the gate of an
	 * abandonment's declaration): the session holding the task in a role that pushes code (CODE_PUSH), the
	 * board's coordinator seat, or any key with BOARD_WRITE on the board -- so a task a replaced PR sent to the
	 * coordinator, which nobody holds, can be settled by whoever settles delivery.
	 */
	@DgsData(parentType = "Mutation", field = "agentTaskSupersedePullRequestProgrammatic")
	public AgentTaskData agentTaskSupersedePullRequestProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, @InputArgument("oldUrl") String oldUrl,
			@InputArgument("byUrl") String byUrl, @InputArgument("note") String note, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		resolveCallingAgent(ctx, sessionUuid);
		if (!holdsInRoleWith(td, board, sessionUuid, AgentTaskRoleConfigData.AgentCapability.CODE_PUSH)
				&& !agentBoardService.isSeatHolder(board, sessionUuid) && !keyWritesBoard(ctx, board)) {
			throw new AccessDeniedException("A PR is declared superseded by the session holding this task in a role"
					+ " that pushes code (CODE_PUSH), by the board's coordinator seat, or with a key holding BOARD_WRITE on"
					+ " the board; this call is none of the three");
		}
		return agentTaskService.supersedePullRequest(taskUuid, oldUrl, byUrl, note, AgentActor.ofSession(sessionUuid),
				ctx.wu());
	}

	/** Refuse a caller whose key lacks {@code fn} on the board: the per-caller half of {@link #SEAT_OR_HOLDER}. */
	private void requireKeyOnBoard(ProgKeyContext ctx, AgentBoardData board, PermissionFunction fn, CallType ct)
			throws RelizaException {
		if (null == authorizationService.keyOnBoard(ctx.ahp(), ctx.orgUuid(), board.getUuid(), List.of(fn), ct)) {
			throw new RelizaException("The key lacks " + fn.name() + " on board " + board.getName());
		}
	}

	/** A hold's level as the schema spells it; null is the seat's COORDINATOR level, as before the argument existed. */
	private static AgentTaskData.HoldLevel holdLevelOf(String level) throws RelizaException {
		if (StringUtils.isBlank(level)) return AgentTaskData.HoldLevel.COORDINATOR;
		try {
			return AgentTaskData.HoldLevel.valueOf(level.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new RelizaException("level is COORDINATOR or OPERATOR, not " + level);
		}
	}

	/** Whether the calling key holds BOARD_WRITE on the board, whatever the operation's own gate. */
	private boolean keyWritesBoard(ProgKeyContext ctx, AgentBoardData board) throws RelizaException {
		return null != authorizationService.keyOnBoard(ctx.ahp(), ctx.orgUuid(), board.getUuid(),
				List.of(PermissionFunction.BOARD_WRITE), CallType.WRITE);
	}

	/** Whether the session holds the task now, in a role whose capabilities include {@code capability}. */
	private boolean holdsInRoleWith(AgentTaskData td, AgentBoardData board, UUID sessionUuid,
			AgentTaskRoleConfigData.AgentCapability capability) {
		if (null == td.getAssignment() || !sessionUuid.equals(td.getAssignment().session())) return false;
		return agentBoardService.getRoleConfig(board.getUuid(), td.getAssignment().role())
				.map(rc -> null != rc.getRequiredCapabilities() && rc.getRequiredCapabilities().contains(capability))
				.orElse(false);
	}

	/** Whether the session holds, or signed off, this task in a role whose capabilities include PR_MERGE. */
	private boolean mergedHere(AgentTaskData td, AgentBoardData board, UUID sessionUuid) {
		List<String> roles = new ArrayList<>();
		if (null != td.getAssignment() && sessionUuid.equals(td.getAssignment().session())) roles.add(td.getAssignment().role());
		td.getSignOffs().stream().filter(so -> sessionUuid.equals(so.session())).map(AgentTaskData.SignOff::role)
				.forEach(roles::add);
		return roles.stream().distinct().anyMatch(r -> agentBoardService.getRoleConfig(board.getUuid(), r)
				.map(rc -> null != rc.getRequiredCapabilities()
						&& rc.getRequiredCapabilities().contains(AgentTaskRoleConfigData.AgentCapability.PR_MERGE))
				.orElse(false));
	}

	private static AgentTaskData.DeliveryOutcome deliveryOutcomeOf(String outcome) throws RelizaException {
		if (StringUtils.isBlank(outcome)) return AgentTaskData.DeliveryOutcome.DELIVERED;
		try {
			return AgentTaskData.DeliveryOutcome.valueOf(outcome.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new RelizaException("outcome is DELIVERED or ABANDONED, not " + outcome);
		}
	}

	private static AgentBoardData.DeliveryPolicy deliveryPolicyOf(Object raw) throws RelizaException {
		if (null == raw) return null;
		try {
			return Utils.OM.convertValue(raw, AgentBoardData.DeliveryPolicy.class);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Could not read deliveryPolicy: " + e.getMessage());
		}
	}

	@DgsData(parentType = "Mutation", field = "agentTaskLiftHoldProgrammatic")
	public AgentTaskData agentTaskLiftHoldProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, @InputArgument("role") String role,
			@InputArgument("note") String note,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		return agentTaskService.liftHold(taskUuid, AgentTaskData.HoldLevel.COORDINATOR,
				AgentActor.ofSession(sessionUuid), note, role, ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskEscalateHoldProgrammatic")
	public AgentTaskData agentTaskEscalateHoldProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid, @InputArgument("reason") String reason,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		return agentTaskService.escalateHold(taskUuid, reason, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskRequireHumanReviewProgrammatic")
	public AgentTaskData agentTaskRequireHumanReviewProgrammatic(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		AgentBoardData board = requireOrgBoard(ctx, td.getBoard());
		requireSeatHolder(ctx, board, sessionUuid);
		// add-only on the seat path: the coordinator can add review, never remove it
		return agentTaskService.setRequireHumanReview(taskUuid, true, false, AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	// ---------- Human-review pass: operator verdicts, human roles, two-tier holds ----------

	@DgsData(parentType = "Mutation", field = "agentTaskHumanReview")
	public AgentTaskData agentTaskHumanReview(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("accept") Boolean accept,
			@InputArgument("note") String note,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.humanReview(taskUuid, Boolean.TRUE.equals(accept), note, jc.actor(),
				toReviewItemDecisions(dfe.getArgument("reviewItems")), toAbout(dfe.getArgument("about")), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskHumanSignOff")
	public AgentTaskData agentTaskHumanSignOff(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("outcome") String outcome,
			@InputArgument("note") String note,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		AgentBoardData board = agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard()));
		SignOffOutcome oc;
		try {
			oc = SignOffOutcome.valueOf(outcome);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new RelizaException("Unrecognized sign-off outcome: " + outcome);
		}
		return agentTaskService.humanSignOff(taskUuid, board, oc, note, jc.actor(), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskOperatorHold")
	public AgentTaskData agentTaskOperatorHold(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("hold") Boolean hold,
			@InputArgument("reason") String reason,
			@InputArgument("role") String role,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		if (Boolean.TRUE.equals(hold)) {
			if (StringUtils.isNotBlank(role)) {
				throw new RelizaException("A role names where lifting the hold routes the task; placing a hold takes none");
			}
			return agentTaskService.hold(taskUuid, AgentTaskData.HoldLevel.OPERATOR, reason,
					jc.actor(), jc.wu());
		}
		// Releasing a QUESTION hold with words means answering (D5). The rule lives in the
		// service, next to what it has to know about holds and rounds.
		return agentTaskService.liftHoldOrAnswer(taskUuid, AgentTaskData.HoldLevel.OPERATOR,
				jc.actor(), reason, role, jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskAnswer")
	public AgentTaskData agentTaskAnswer(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("answers") List<Map<String, Object>> answers,
			@InputArgument("answerAll") String answerAll,
			@InputArgument("liftHold") Boolean liftHold,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		if (td.getQuestionStack().isEmpty()) {
			throw new RelizaException("Nothing is waiting on an answer on task " + taskUuid);
		}
		List<AgentDocumentService.Answer> typed = new ArrayList<>();
		if (null != answers && !answers.isEmpty()) {
			for (Map<String, Object> a : answers) {
				Object status = a.get("status");
				typed.add(new AgentDocumentService.Answer((String) a.get("id"),
						null == status ? BoardReviewItemStatus.RESOLVED
								: BoardReviewItemStatus.valueOf(status.toString()),
						(String) a.get("resolution")));
			}
		} else if (StringUtils.isNotBlank(answerAll)) {
			// One text for every id still open on the round being answered.
			BoardReviewItemIndex previous = agentDocumentService.latestIndexes(td)
					.get(RearmSpecificationType.BOARD_QUESTIONS);
			if (null == previous) {
				throw new RelizaException("Task " + taskUuid + " has no questions round to answer");
			}
			previous.openReviewItems().forEach(f -> typed.add(
					new AgentDocumentService.Answer(f.id(), BoardReviewItemStatus.RESOLVED, answerAll)));
		} else {
			throw new RelizaException("An answer needs either per-id answers or answerAll text");
		}
		return agentTaskService.answer(taskUuid, typed, jc.actor(),
				!Boolean.FALSE.equals(liftHold), jc.wu());
	}

	// ---------- Operator actions: people run a board without a coordinator (operator-actions) ----------

	@DgsData(parentType = "Mutation", field = "agentTaskRegister")
	public AgentTaskData agentTaskRegister(@InputArgument("boardUuid") UUID boardUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData board = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		PersonContext jc = authorizePersonOnBoard(dfe, board, PERSON_WRITE);
		Map<String, Object> input = dfe.getArgument("input");
		return agentTaskService.register(board,
				(String) input.get("externalRef"),
				(String) input.get("title"),
				(String) input.get("description"),
				(String) input.get("sourceUrl"),
				null,
				parseUuid((String) input.get("parentTask"), "parentTask"),
				parseUuid((String) input.get("producesComponent"), "producesComponent"),
				input.get("workLevel") instanceof Number lvl ? lvl.intValue() : null,
				refuseQuestions(parseRequiredInputs(input.get("requiredInputs")), "requiredInputs"),
				jc.actor(), true, (String) input.get("group"), parseTags(input.get("tags")), jc.wu());
	}

	/** Commission an investigation as a person with BOARD_WRITE on the board (task RD4-12). */
	@DgsData(parentType = "Mutation", field = "agentTaskCommission")
	public AgentTaskData agentTaskCommission(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> input = dfe.getArgument("input");
		UUID boardUuid = parseUuid((String) input.get("boardUuid"), "boardUuid");
		if (boardUuid == null) throw new RelizaException("boardUuid is required");
		AgentBoardData board = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		PersonContext jc = authorizePersonOnBoard(dfe, board, PERSON_WRITE);
		if (null != input.get("sessionUuid")) {
			throw new RelizaException("A person commissions without a session; a session commissions through the agent API");
		}
		return agentTaskService.commission(board, commissionFromInput(input), null, jc.actor(), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskAuthorize")
	public AgentTaskData agentTaskAuthorize(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("role") String role,
			@InputArgument("orderIndex") Integer orderIndex,
			@InputArgument("dependsOn") List<String> dependsOnRaw,
			@InputArgument("producesComponent") String producesComponentRaw,
			@InputArgument("workLevel") Integer workLevel,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		AgentBoardData board = agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard()));
		List<UUID> dependsOn = null;
		if (dependsOnRaw != null) {
			dependsOn = new ArrayList<>();
			for (String d : dependsOnRaw) dependsOn.add(parseUuid(d, "dependsOn"));
		}
		// No strength here: people set it, in either direction, with agentTaskSetStrength.
		return agentTaskService.authorize(taskUuid, board, role, orderIndex, dependsOn,
				parseUuid(producesComponentRaw, "producesComponent"), workLevel,
				refuseQuestions(parseRequiredInputs(dfe.getArgument("requiredInputs")), "requiredInputs"),
				false, null, jc.actor(), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskOrder")
	public AgentTaskData agentTaskOrder(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("orderIndex") Integer orderIndex,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.setOrder(taskUuid, orderIndex, jc.actor(), jc.wu());
	}

	/**
	 * A person sets or clears a task's budget (task 6f1b348d); org admin, like every board action.
	 * A person verb on both endpoints, as the others this branch moved (JWT, or a CLI login / USER
	 * key acting as its owner).
	 */
	@DgsData(parentType = "Mutation", field = "agentTaskSetBudget")
	public AgentTaskData agentTaskSetBudget(@InputArgument("taskUuid") UUID taskUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		Object raw = dfe.getArgument("budgetMicros");
		return agentTaskService.setBudget(taskUuid, raw instanceof Number n ? n.longValue() : null, jc.actor(), jc.wu());
	}

	/** A person sets or clears a task's level (RD2-1); BOARD_WRITE, like the budget. */
	@DgsData(parentType = "Mutation", field = "agentTaskSetWorkLevel")
	public AgentTaskData agentTaskSetWorkLevel(@InputArgument("taskUuid") UUID taskUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		Object raw = dfe.getArgument("workLevel");
		return agentTaskService.setWorkLevel(taskUuid, raw instanceof Number n ? n.intValue() : null, jc.actor(), jc.wu());
	}

	/** A person with BOARD_WRITE releases a stalled assignment back to the queue (task RD3-4). */
	@DgsData(parentType = "Mutation", field = "agentTaskUnassign")
	public AgentTaskData agentTaskUnassign(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("reason") String reason, DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.unassign(taskUuid, reason, jc.actor(), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskComplete")
	public AgentTaskData agentTaskComplete(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("note") String note,
			@InputArgument("skipRequiredRoles") Boolean skipRequiredRoles,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.complete(taskUuid, note, jc.actor(), Boolean.TRUE.equals(skipRequiredRoles),
				jc.wu());
	}

	/** A delivery declared by a person (task 18c5c293): an org admin, as the other operator actions. */
	/** A person declares a linked PR superseded by its replacement (task RD3-13). BOARD_WRITE. */
	@DgsData(parentType = "Mutation", field = "agentTaskSupersedePullRequest")
	public AgentTaskData agentTaskSupersedePullRequest(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("oldUrl") String oldUrl, @InputArgument("byUrl") String byUrl,
			@InputArgument("note") String note, DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.supersedePullRequest(taskUuid, oldUrl, byUrl, note, jc.actor(), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskDeclareDelivery")
	public AgentTaskData agentTaskDeclareDelivery(@InputArgument("taskUuid") UUID taskUuid, @InputArgument("unit") String unit,
			@InputArgument("commit") String commit, @InputArgument("outcome") String outcome,
			@InputArgument("note") String note, DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.declareDelivery(taskUuid, unit, commit, deliveryOutcomeOf(outcome), note, jc.actor(),
				jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskCancel")
	public AgentTaskData agentTaskCancel(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("note") String note,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.cancel(taskUuid, note, jc.actor(), jc.wu());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentTaskReopen")
	public AgentTaskData agentTaskReopen(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("role") String role,
			@InputArgument("reason") String reason) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(null, td, PERSON_WRITE);
		return agentTaskService.reopen(taskUuid, role, reason, jc.actor(), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentTaskDecideReviewItems")
	public AgentTaskData agentTaskDecideReviewItems(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("specification") String specification,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		RearmSpecificationType spec;
		try {
			spec = RearmSpecificationType.valueOf(specification);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new RelizaException("Unrecognized specification: " + specification);
		}
		return agentTaskService.decideReviewItems(taskUuid, spec, toReviewItemDecisions(dfe.getArgument("decisions")),
				toAbout(dfe.getArgument("about")), jc.actor(), jc.wu());
	}

	/** Review item decisions from the wire, read as the record the service takes. */
	static List<AgentDocumentService.BoardReviewItemDecision> toReviewItemDecisions(List<Map<String, Object>> raw)
			throws RelizaException {
		if (null == raw) return List.of();
		try {
			return raw.stream()
					.map(m -> Utils.OM.convertValue(m, AgentDocumentService.BoardReviewItemDecision.class))
					.toList();
		} catch (JacksonException e) {
			throw new RelizaException("Could not read review item decisions: " + e.getMessage());
		}
	}

	static BoardReviewItemIndex.About toAbout(Map<String, Object> raw) throws RelizaException {
		if (null == raw) return null;
		try {
			return Utils.OM.convertValue(raw, BoardReviewItemIndex.About.class);
		} catch (JacksonException e) {
			throw new RelizaException("Could not read about: " + e.getMessage());
		}
	}

	/** How far a hop went over its role's allowance; computed, so a refreshed snapshot carries it. */
	@DgsData(parentType = "AgentTaskSignOff", field = "overAllowanceMicros")
	public Long signOffOverAllowance(DgsDataFetchingEnvironment dfe) {
		AgentTaskData.SignOff so = dfe.getSource();
		return null == so ? null : io.reliza.service.AgentBudgetService.overAllowanceMicros(so.usage());
	}

	/** One document a sign-off reviewed, as served (task fda2c9f1). */
	public record ReviewedInput(UUID release, RearmSpecificationType specification, Integer round,
			ReleaseLifecycle promotedTo) {}

	/** A document a review would have promoted and a guard kept back, as served (task fda2c9f1). */
	public record RefusedPromotion(UUID release, RearmSpecificationType specification, Integer round, String reason) {}

	/**
	 * What a sign-off reviewed and what its review did to each (task fda2c9f1): the stored uuids,
	 * resolved per read to the document's type and round, with the lifecycle the sign-off's own
	 * promotion reached.
	 */
	@DgsData(parentType = "AgentTaskSignOff", field = "reviewedInputs")
	public List<ReviewedInput> signOffReviewedInputs(DgsDataFetchingEnvironment dfe) {
		AgentTaskData.SignOff so = dfe.getSource();
		if (null == so || null == so.reviewedInputs()) return null;
		Map<UUID, AgentTaskData.Promotion> made = promotionsOf(so);
		return so.reviewedInputs().stream().map(r -> {
			ReleaseData.DocumentRef doc = documentOf(r);
			AgentTaskData.Promotion p = made.get(r);
			return new ReviewedInput(r, null == doc ? null : doc.specification(), null == doc ? null : doc.round(),
					null == p ? null : p.promotedTo());
		}).toList();
	}

	/** What a guard kept this sign-off's review from promoting, with its reason (task fda2c9f1). */
	@DgsData(parentType = "AgentTaskSignOff", field = "refusedPromotions")
	public List<RefusedPromotion> signOffRefusedPromotions(DgsDataFetchingEnvironment dfe) {
		AgentTaskData.SignOff so = dfe.getSource();
		if (null == so) return List.of();
		return promotionsOf(so).values().stream().filter(AgentTaskData.Promotion::refused).map(p -> {
			ReleaseData.DocumentRef doc = documentOf(p.release());
			return new RefusedPromotion(p.release(), null == doc ? null : doc.specification(),
					null == doc ? null : doc.round(), p.refusedReason());
		}).toList();
	}

	private static Map<UUID, AgentTaskData.Promotion> promotionsOf(AgentTaskData.SignOff so) {
		Map<UUID, AgentTaskData.Promotion> out = new LinkedHashMap<>();
		if (null != so.promotions()) so.promotions().forEach(p -> out.put(p.release(), p));
		return out;
	}

	private ReleaseData.DocumentRef documentOf(UUID release) {
		return sharedReleaseService.getReleaseData(release).map(ReleaseData::getDocument).orElse(null);
	}

	@DgsData(parentType = "AgentTaskReturn", field = "overAllowanceMicros")
	public Long returnOverAllowance(DgsDataFetchingEnvironment dfe) {
		AgentTaskData.TaskReturn tr = dfe.getSource();
		return null == tr ? null : io.reliza.service.AgentBudgetService.overAllowanceMicros(tr.usage());
	}

	/** When a review item was decided: the creation time of the round that decided it. */
	@DgsData(parentType = "BoardReviewItem", field = "decidedAt")
	public ZonedDateTime reviewItemDecidedAt(DgsDataFetchingEnvironment dfe) {
		BoardReviewItem f = dfe.getSource();
		if (null == f || null == f.decidedIn()) return null;
		return sharedReleaseService.getReleaseData(f.decidedIn()).map(ReleaseData::getCreatedDate).orElse(null);
	}

	@DgsData(parentType = "Mutation", field = "agentTaskRequireHumanReview")
	public AgentTaskData agentTaskRequireHumanReview(@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("value") Boolean value,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentTaskData td = agentTaskService.getTaskData(taskUuid)
				.orElseThrow(() -> new RelizaException("Task not found: " + taskUuid));
		PersonContext jc = authorizePersonOnTask(dfe, td, PERSON_WRITE);
		return agentTaskService.setRequireHumanReview(taskUuid, Boolean.TRUE.equals(value), true, jc.actor(), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentBoardPostEventProgrammatic")
	public AgentBoardData agentBoardPostEventProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("kind") String kind,
			@InputArgument("message") String message,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		AgentBoardData board = requireOrgBoard(ctx, boardUuid);
		requireSeatHolder(ctx, board, sessionUuid);
		AgentBoardData.BoardEventKind k;
		try {
			k = AgentBoardData.BoardEventKind.valueOf(kind);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new RelizaException("Unrecognized event kind: " + kind);
		}
		return agentBoardService.postEvent(boardUuid, k, message,
				AgentActor.ofSession(sessionUuid), ctx.wu());
	}

	/** Delivery-minimum completeness alert, resolved per board for both JWT and programmatic reads. */
	/**
	 * The board's documents repository, resolved from the uuid it stores.
	 *
	 * <p>A field resolver rather than a stored object: the board holds the row's identity, and the
	 * uri is the row's business. Anything reading this -- the UI to show it, the CLI to match a
	 * checkout's remote -- wants the uri, and nothing has to agree about how to spell it.
	 */
	@DgsData(parentType = "AgentBoard", field = "documentsRepo")
	public VcsRepositoryData boardDocumentsRepo(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		if (null == bd.getDocumentsRepo()) return null;
		return vcsRepositoryService.getVcsRepository(bd.getDocumentsRepo())
				.map(VcsRepositoryData::dataFromRecord).orElse(null);
	}

	/**
	 * Where a new document of a type would go on this board, placeholders filled by the server
	 * (gaps §1.18). A field on a board the caller can already read; the task and the component
	 * are checked against the board.
	 */
	@DgsData(parentType = "AgentBoard", field = "documentPath")
	public String agentBoardDocumentPath(DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = dfe.getSource();
		String spec = dfe.getArgument("specification");
		String task = dfe.getArgument("task");
		String component = dfe.getArgument("component");
		return agentDocumentService.resolveDocumentPath(bd, RearmSpecificationType.valueOf(spec),
				null == task ? null : UUID.fromString(task), null == component ? null : UUID.fromString(component));
	}

	/** One of a board's document series, as AgentBoardDocumentComponent. */
	public record BoardDocumentComponent(String specification, UUID component) {}

	/**
	 * What the caller may do on this board (board-permissions.md §4.6): the board functions it holds
	 * at their floors, and the configuration functions covering the board. For the person signed in,
	 * or the key calling; the UI shows only the controls these allow.
	 */
	@DgsData(parentType = "AgentBoard", field = "myPermissions")
	public List<PermissionFunction> agentBoardMyPermissions(DgsDataFetchingEnvironment dfe) throws RelizaException {
		AgentBoardData bd = dfe.getSource();
		Set<PermissionFunction> held;
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) {
			held = authorizationService.boardFunctions(userService.getUserDataByAuth(auth).orElse(null), bd.getOrg(), bd.getUuid());
		} else {
			DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
			ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
					requestData.getHeaders(), (ServletWebRequest) requestData.getWebRequest());
			held = authorizationService.boardFunctions(authCtx.ahp(), bd.getOrg(), bd.getUuid());
		}
		return held.stream().sorted().toList();
	}

	/** Where the board's documents sit in its repository (board-documents.md D6): its root, resolved. */
	@DgsData(parentType = "AgentBoard", field = "documentsRoot")
	public String agentBoardDocumentsRoot(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		return bd.documentsRoot();
	}

	@DgsData(parentType = "AgentBoard", field = "perspectiveNames")
	public List<String> agentBoardPerspectiveNames(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		return boardPerspectiveService.names(bd);
	}

	/**
	 * A person's consent to add or remove board perspectives, from the credential they came with:
	 * a personal key's own grants intersected with its owner's, as every key check reads them and
	 * as applyBoardProgrammatic reads the same key; the person's grants for a browser session.
	 */
	/**
	 * Who may force-close a session (board-permissions.md D15): BOARD_WRITE on a board it worked. A
	 * session that never worked a board is outside the board model and keeps the org admin check.
	 */
	private PersonContext authorizeSessionForceClose(DgsDataFetchingEnvironment dfe, AgentSessionData sd)
			throws RelizaException {
		Set<UUID> worked = agentSessionVisibilityService.boardsWorked(sd);
		if (worked.isEmpty()) return authorizePersonCtx(dfe, sd.getOrg(), CallType.ADMIN);
		AccessDeniedException last = null;
		for (UUID board : worked) {
			Optional<AgentBoardData> bd = agentBoardService.getBoardData(board);
			if (bd.isEmpty()) continue;
			try {
				return authorizePersonOnBoard(dfe, bd.get(), PERSON_WRITE);
			} catch (AccessDeniedException e) {
				last = e;
			}
		}
		throw null != last ? last : new AccessDeniedException("Not authorized");
	}

	/** A person's board access: their personal key's, or their own for a browser session. */
	private io.reliza.service.BoardAccess personAccess(PersonContext jc, UUID orgUuid) throws RelizaException {
		if (null != jc.key()) return authorizationService.boardAccess(jc.key(), orgUuid);
		return authorizationService.boardAccess(userService.getUserData(jc.user()).orElse(null), orgUuid);
	}

	private io.reliza.service.BoardPerspectiveService.PerspectiveConsent personConsent(PersonContext jc, UUID orgUuid)
			throws RelizaException {
		if (null != jc.key()) return authorizationService.perspectiveConsent(jc.key(), orgUuid);
		return authorizationService.perspectiveConsent(userService.getUserData(jc.user()).orElse(null), orgUuid);
	}

	/** The signed-in person's board access: which boards they see, for a refusal that must not name the others. */
	private io.reliza.service.BoardAccess personAccess(UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return authorizationService.boardAccess(userService.getUserDataByAuth(auth).orElse(null), orgUuid);
	}

	/** The signed-in person's consent to add or remove board perspectives (board-permissions.md D9). */
	private io.reliza.service.BoardPerspectiveService.PerspectiveConsent personConsent(UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return authorizationService.perspectiveConsent(userService.getUserDataByAuth(auth).orElse(null), orgUuid);
	}

	/**
	 * The component the board builds (task RD2-4): declared on AgentBoard and answered by nothing
	 * until now, so it read null for every board. Null only when the target is gone. The board read
	 * already authorized the caller for the board, whose file names its target anyway.
	 */
	@DgsData(parentType = "AgentBoard", field = "targetDetails")
	public io.reliza.model.ComponentData agentBoardTargetDetails(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		if (null == bd || null == bd.getTarget()) return null;
		return getComponentService.getComponentData(bd.getTarget()).orElse(null);
	}

	/** The component a task produces, the same way; also declared with no resolver until now. */
	@DgsData(parentType = "AgentTask", field = "producesComponentDetails")
	public io.reliza.model.ComponentData agentTaskProducesComponentDetails(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		if (null == td || null == td.getProducesComponent()) return null;
		return getComponentService.getComponentData(td.getProducesComponent()).orElse(null);
	}

	/** What the board has produced, per document series (board-documents.md §5, task 36d0549e). */
	@DgsData(parentType = "AgentBoard", field = "documentSeries")
	public List<AgentDocumentService.DocumentSeries> agentBoardDocumentSeries(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		return agentDocumentService.documentSeries(bd);
	}

	@DgsData(parentType = "AgentBoard", field = "documentComponents")
	public List<BoardDocumentComponent> agentBoardDocumentComponents(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		if (null == bd.getDocumentComponents()) return List.of();
		return bd.getDocumentComponents().entrySet().stream()
				.map(e -> new BoardDocumentComponent(e.getKey().name(), e.getValue())).toList();
	}

	/**
	 * The element families as the board declares them (grammar 1.2, task RD4-6): a prefix with its own definedIn as
	 * {family, definedIn}, any other as its family's name.
	 */
	@DgsData(parentType = "AgentBoard", field = "elementFamilies")
	public Map<String, Object> agentBoardElementFamilies(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		return null == bd ? null : bd.getDeclaredElementFamilies();
	}

	@DgsData(parentType = "AgentBoard", field = "effectiveDocumentPaths")
	public Map<String, String> agentBoardEffectiveDocumentPaths(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		Map<String, String> out = new LinkedHashMap<>();
		agentBoardService.effectiveDocumentPaths(bd).forEach((k, v) -> out.put(k.name(), v));
		return out;
	}

	/** The delivery policy with its merge procedure resolved against the board's roles (task 71a3dd22). */
	@DgsData(parentType = "AgentBoard", field = "effectiveDeliveryPolicy")
	public AgentBoardData.DeliveryPolicy agentBoardEffectiveDeliveryPolicy(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		return null == bd ? null : agentBoardService.effectiveDeliveryPolicy(bd);
	}

	/** The coordinator seat's prompt as served: the operator's, then the board's merge procedure. */
	@DgsData(parentType = "AgentBoard", field = "servedCoordinatorPrompt")
	public String agentBoardServedCoordinatorPrompt(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		return null == bd ? null : agentBoardService.servedCoordinatorPrompt(bd);
	}

	/** Resolved only when selected: it reads the org's keys (task 5c70990d). */
	@DgsData(parentType = "AgentBoard", field = "missingCoverage")
	public List<AgentBoardService.CoverageGap> agentBoardMissingCoverage(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		return null == bd ? List.of() : agentBoardService.missingCoverage(bd);
	}

	@DgsData(parentType = "AgentBoard", field = "missingCapabilities")
	public List<String> agentBoardMissingCapabilities(DgsDataFetchingEnvironment dfe) {
		AgentBoardData bd = dfe.getSource();
		if (bd == null) return List.of();
		return agentBoardService.missingCapabilities(bd.getUuid()).stream()
				.map(Enum::name).collect(Collectors.toList());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentTaskRolePresetsOfOrg")
	public List<AgentTaskRoleConfigData> agentTaskRolePresetsOfOrg(@InputArgument("orgUuid") UUID orgUuid)
			throws RelizaException {
		authorizeJwtOrg(orgUuid, CallType.READ);
		return agentBoardService.listPresets(orgUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentTaskRolePresetSet")
	public AgentTaskRoleConfigData agentTaskRolePresetSet(@InputArgument("orgUuid") UUID orgUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		WhoUpdated wu = authorizeJwtOrg(orgUuid, CallType.ADMIN);
		Map<String, Object> input = dfe.getArgument("input");
		AgentTaskRoleConfigData preset = agentBoardService.upsertPreset(orgUuid, specFromInput(input), wu);
		return clearsHopBudget(input) ? agentBoardService.clearHopBudget(preset, wu) : preset;
	}

	// ---------- Internals ----------

	private AgentTaskRoleConfigData upsertRoleConfigFromInput(AgentBoardData board,
			Map<String, Object> input, boolean allowPromptEdit, WhoUpdated wu) throws RelizaException {
		boolean clears = clearsHopBudget(input);
		if (clears && !allowPromptEdit) {
			throw new RelizaException("The hop allowance is operator-only; the coordinator cannot remove it");
		}
		AgentTaskRoleConfigData rc = agentBoardService.upsertRoleConfig(board, specFromInput(input), allowPromptEdit, wu);
		return clears ? agentBoardService.clearHopBudget(rc, wu) : rc;
	}

	/** hopBudgetMicros sent as null: the form removes the allowance (task 40f270be). Left out, it stays. */
	static boolean clearsHopBudget(Map<String, Object> input) {
		return null != input && input.containsKey("hopBudgetMicros") && null == input.get("hopBudgetMicros");
	}

	/**
	 * The whole input, including the two lists that describe what a role consumes and produces.
	 *
	 * <p>These used to be dropped here. {@code requiredInputs} was declared in the schema and
	 * silently ignored -- a field that answered 200 and did nothing -- and {@code producesOutputs}
	 * had no input field at all, so the routing graph could not be configured through any API and
	 * every board's roles declared nothing. Questions then had no producer to go to and every one
	 * of them escalated to the coordinator, which is what every board on the sandbox was doing.
	 */
	private static AgentBoardService.RoleConfigSpec specFromInput(Map<String, Object> input)
			throws RelizaException {
		return new AgentBoardService.RoleConfigSpec(
				(String) input.get("name"),
				(String) input.get("prompt"),
				(Integer) input.get("orderIndex"),
				(Integer) input.get("wipLimit"),
				(Boolean) input.get("requireDistinctAgent"),
				(Boolean) input.get("active"),
				capabilityList(input.get("requiredCapabilities")),
				parseEnum(input.get("kind"), AgentTaskRoleConfigData.RoleKind.class, "kind"),
				parseEnum(input.get("necessity"), AgentTaskRoleConfigData.RoleNecessity.class, "necessity"),
				parseEnum(input.get("humanGate"), AgentTaskRoleConfigData.HumanGate.class, "humanGate"),
				refuseQuestions(parseRequiredInputs(input.get("requiredInputs")), "requiredInputs"),
				producedOutputList(input.get("producesOutputs")),
				strengthSpecFromInput(input),
				hopBudgetFromInput(input),
				(Boolean) input.get("blindReview"),
				commissionsFromInput(input));
	}

	/**
	 * A role's commissions from input (task RD4-12). Left out, null (unchanged); sent as null, an empty block,
	 * which the service stores as none.
	 */
	@SuppressWarnings("unchecked")
	static AgentTaskRoleConfigData.Commissions commissionsFromInput(Map<String, Object> input) throws RelizaException {
		if (null == input || !input.containsKey("commissions")) return null;
		if (!(input.get("commissions") instanceof Map<?, ?> raw)) {
			return new AgentTaskRoleConfigData.Commissions(List.of(), null, null, null);
		}
		Map<String, Object> m = (Map<String, Object>) raw;
		List<String> roles = new ArrayList<>();
		if (m.get("roles") instanceof List<?> l) l.forEach(o -> roles.add(null == o ? null : o.toString()));
		return new AgentTaskRoleConfigData.Commissions(roles,
				parseEnum(m.get("intake"), AgentTaskRoleConfigData.CommissionIntake.class, "commissions.intake"),
				m.get("defaultBudgetMicros") instanceof Number n ? n.longValue() : null,
				(String) m.get("review"));
	}

	/** A role's hop allowance from input, in micros; refused when negative, as a board file's is. */
	static Long hopBudgetFromInput(Map<String, Object> input) throws RelizaException {
		if (!(input.get("hopBudgetMicros") instanceof Number n)) return null;
		long micros = n.longValue();
		if (micros < 0) throw new RelizaException("hopBudgetMicros cannot be negative");
		return micros;
	}

	/**
	 * The strength part of a role input. A field left out is left alone; a field sent as null
	 * clears it -- the only way to take a floor off a role -- so presence is read from the map
	 * rather than from the value.
	 */
	private static AgentBoardService.StrengthSpec strengthSpecFromInput(Map<String, Object> input)
			throws RelizaException {
		boolean any = input.containsKey("requiredStrength") || input.containsKey("strengthHeadroom")
				|| input.containsKey("strengthCategory") || input.containsKey("modelStrengths");
		if (!any) return null;
		Optional<Double> required = input.containsKey("requiredStrength")
				? Optional.ofNullable(asDouble(input.get("requiredStrength"))) : null;
		Double headroom = input.containsKey("strengthHeadroom")
				? Optional.ofNullable(asDouble(input.get("strengthHeadroom"))).orElse(0d) : null;
		Optional<ModelOntologyData.RoleCategory> category = input.containsKey("strengthCategory")
				? Optional.ofNullable(parseEnum(input.get("strengthCategory"), ModelOntologyData.RoleCategory.class,
						"strengthCategory"))
				: null;
		List<AgentTaskRoleConfigData.ModelStrength> overrides = null;
		if (input.containsKey("modelStrengths")) {
			overrides = new ArrayList<>();
			if (input.get("modelStrengths") instanceof List<?> l) {
				for (Object e : l) {
					if (!(e instanceof Map<?, ?> m)) {
						throw new RelizaException("modelStrengths entries must be objects");
					}
					UUID model;
					try {
						model = UUID.fromString(String.valueOf(m.get("model")));
					} catch (IllegalArgumentException iae) {
						throw new RelizaException("modelStrengths.model is not a uuid: " + m.get("model"));
					}
					overrides.add(new AgentTaskRoleConfigData.ModelStrength(model, asDouble(m.get("strength"))));
				}
			}
		}
		return new AgentBoardService.StrengthSpec(required, headroom, category, overrides);
	}

	/** GraphQL Float arrives as Double, an integer literal as Integer; both are a strength. */
	private static Double asDouble(Object v) {
		return v instanceof Number n ? n.doubleValue() : null;
	}

	private static List<ProducedOutput> producedOutputList(Object raw) throws RelizaException {
		if (!(raw instanceof List<?> l)) return null;
		List<ProducedOutput> out = new ArrayList<>();
		for (Object e : l) {
			if (!(e instanceof Map<?, ?> m)) continue;
			RearmSpecificationType spec = specOf(m.get("specification"), "producesOutputs");
			if (null == spec) {
				throw new RelizaException("A produced output needs a specification");
			}
			out.add(new ProducedOutput(spec,
					parseEnum(m.get("scope"), InputScope.class, "scope"),
					!Boolean.FALSE.equals(m.get("required"))));
		}
		return out;
	}

	/**
	 * A specification a role may contract on.
	 *
	 * <p>BOARD_QUESTIONS is refused in both lists: any role may emit one when it cannot proceed, so it
	 * is a side channel rather than a role's product or prerequisite. Declaring it as an output
	 * would also make the asking role its own answerer.
	 */
	private static RearmSpecificationType specOf(Object raw, String field) throws RelizaException {
		RearmSpecificationType spec = parseEnum(raw, RearmSpecificationType.class, "specification");
		if (RearmSpecificationType.BOARD_QUESTIONS == spec) throw new RelizaException(questionsRefusal(field));
		if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == spec) throw new RelizaException(elementCheckReportRefusal(field));
		return spec;
	}

	private static String elementCheckReportRefusal(String field) {
		return "BOARD_ELEMENT_CHECK_REPORT cannot appear in " + field + ": the board cuts it for a document with elements,"
				+ " and its blocking checks gate that document's hand-over";
	}

	private static String questionsRefusal(String field) {
		return "BOARD_QUESTIONS cannot appear in " + field + ": a question is a side channel any role may"
				+ " emit when it cannot proceed, not a role's contracted product or prerequisite";
	}

	/**
	 * Refuse BOARD_QUESTIONS in a role's contract, whichever list it arrives in.
	 *
	 * <p>Applied over the shared parser's result rather than inside a second parser of its own:
	 * two parsers for one input type drift, and this rule is about what the values MEAN to a role,
	 * not about how they deserialise.
	 */
	private static List<RequiredInput> refuseQuestions(List<RequiredInput> inputs, String field)
			throws RelizaException {
		if (null == inputs) return null;
		for (RequiredInput ri : inputs) {
			if (RearmSpecificationType.BOARD_QUESTIONS == ri.specification()) {
				throw new RelizaException(questionsRefusal(field));
			}
			if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == ri.specification()) {
				throw new RelizaException(elementCheckReportRefusal(field));
			}
		}
		return inputs;
	}

	private static <E extends Enum<E>> E parseEnum(Object raw, Class<E> type, String field)
			throws RelizaException {
		if (!(raw instanceof String s) || StringUtils.isBlank(s)) return null;
		try {
			return Enum.valueOf(type, s);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Unrecognized " + field + ": " + s);
		}
	}

	private static List<AgentCapability> capabilityList(Object raw) throws RelizaException {
		if (!(raw instanceof List<?> l)) return null;
		List<AgentCapability> caps = new java.util.ArrayList<>();
		for (Object e : l) {
			try {
				caps.add(AgentCapability.valueOf(String.valueOf(e)));
			} catch (IllegalArgumentException iae) {
				throw new RelizaException("Unrecognized capability: " + e);
			}
		}
		return caps;
	}

	private record PersonContext(WhoUpdated wu, String email, UUID user, io.reliza.common.CommonVariables.AuthHeaderParse key) {
		/** The caller as a board actor: the user row for joining, the email for reading. */
		AgentActor actor() { return AgentActor.ofUser(user, email); }
	}

	private WhoUpdated authorizeJwtOrg(UUID orgUuid, CallType callType) throws RelizaException {
		return authorizeJwtOrgCtx(orgUuid, callType).wu();
	}

	private PersonContext authorizeJwtOrgCtx(UUID orgUuid, CallType callType) throws RelizaException {
		return authorizeJwtOrgCtx(orgUuid, callType, PermissionFunction.RESOURCE);
	}

	/** As above for a call gated on a permission function; the org's admin clears every function. */
	private PersonContext authorizeJwtOrgCtx(UUID orgUuid, CallType callType, PermissionFunction function)
			throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), function,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), callType);
		return new PersonContext(WhoUpdated.getWhoUpdated(oud.get()), oud.get().getEmail(),
				oud.get().getUuid(), null);
	}

	static final String PERSON_ONLY = "This is a person's action: sign in with rearm login or use a personal key. "
			+ "Organization and federated keys act for agents, through the *Programmatic operations and a session";

	private WhoUpdated authorizePerson(DgsDataFetchingEnvironment dfe, UUID orgUuid, CallType callType)
			throws RelizaException {
		return authorizePersonCtx(dfe, orgUuid, callType).wu();
	}

	private PersonContext authorizePersonCtx(DgsDataFetchingEnvironment dfe, UUID orgUuid, CallType callType)
			throws RelizaException {
		return authorizePersonCtx(dfe, orgUuid, callType, PermissionFunction.RESOURCE);
	}

	/**
	 * A person's action from either endpoint. A browser JWT goes the way it always did. On the
	 * programmatic endpoint the caller must be a personal (USER) key, as rearm login mints: the
	 * key's own permission set and its owner's current permissions must both clear the call (the
	 * same check the browser path runs on the user), and the record names the owner as the actor.
	 * Everything else is refused: an organization key or a federated identity acts for an agent,
	 * and letting it pass as a person would put nobody's name on an operator's decision. That
	 * refusal is a RelizaException so its message reaches the caller (an AccessDeniedException
	 * reads "Not authorized" on the wire): it names the credential to use and discloses nothing.
	 * Permission failures stay AccessDeniedException.
	 */
	private PersonContext authorizePersonCtx(DgsDataFetchingEnvironment dfe, UUID orgUuid, CallType callType,
			PermissionFunction function) throws RelizaException {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken) {
			return authorizeJwtOrgCtx(orgUuid, callType, function);
		}
		var ahp = personalKey(dfe, orgUuid);
		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found: " + orgUuid));
		FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp, function,
				PermissionScope.ORGANIZATION, orgUuid, List.of(od), callType);
		UserData person = userService.getUserData(ahp.getObjUuid())
				.orElseThrow(() -> new RelizaException(PERSON_ONLY));
		return new PersonContext(fkv.whoUpdated(), person.getEmail(), person.getUuid(), ahp);
	}

	/**
	 * The personal key a person's call on the programmatic endpoint came with: a USER key, not
	 * federated, whose CLI actor (when there is one) is its owner, in this organization. Anything
	 * else is not a person and is refused with {@link #PERSON_ONLY}.
	 */
	private io.reliza.common.CommonVariables.AuthHeaderParse personalKey(DgsDataFetchingEnvironment dfe, UUID orgUuid)
			throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), (ServletWebRequest) requestData.getWebRequest());
		var ahp = authCtx.ahp();
		if (null == ahp || ahp.getType() != ApiTypeEnum.USER || ahp.isFederated()) {
			throw new RelizaException(PERSON_ONLY);
		}
		// A CLI login binds to a personal key only when the approver owns it, so the two agree;
		// refuse rather than guess if they ever do not.
		if (null != ahp.getActorUser() && !ahp.getActorUser().equals(ahp.getObjUuid())) {
			throw new RelizaException(PERSON_ONLY);
		}
		if (!orgUuid.equals(authCtx.orgUuid())) {
			throw new AccessDeniedException("The key is not in this board's organization");
		}
		return ahp;
	}

	/** What a person's board read needs (board-permissions.md §4.2). */
	static final BoardGate PERSON_READ = BoardGate.of(CallType.READ, PermissionFunction.BOARD_READ);
	/** What a person's board verb needs: every operator verb that used to need the org's ADMIN. */
	static final BoardGate PERSON_WRITE = BoardGate.of(CallType.WRITE, PermissionFunction.BOARD_WRITE);
	/** What configuring a board needs: the configuration function covering it as well. */
	static final BoardGate PERSON_CONFIGURE = BoardGate.of(CallType.WRITE,
			PermissionFunction.CONFIGURATION_WRITE, PermissionFunction.BOARD_WRITE);
	/** What reading a board as a board file needs. */
	static final BoardGate PERSON_SPEC_READ = BoardGate.of(CallType.READ,
			PermissionFunction.CONFIGURATION_READ, PermissionFunction.BOARD_READ);

	/**
	 * A person's call on a board (board-permissions.md §4.2), from either endpoint: the board
	 * functions of the gate on this board -- the person's grants and their teams' for a browser
	 * JWT, a personal key's intersected with its owner's on the programmatic endpoint -- with no
	 * organization tier in between, so a BOARD-scoped grant over a READ_ONLY membership is enough.
	 * Anything that is not a person is refused with {@link #PERSON_ONLY}. {@code dfe} null means a
	 * browser-only field.
	 */
	private PersonContext authorizePersonOnBoard(DgsDataFetchingEnvironment dfe, AgentBoardData bd, BoardGate gate)
			throws RelizaException {
		if (null == dfe || SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken) {
			JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
			UserData ud = userService.getUserDataByAuth(auth)
					.orElseThrow(() -> new AccessDeniedException("Not authorized"));
			WhoUpdated wu = authorizationService.userOnBoard(ud, bd.getOrg(), bd.getUuid(), gate.functions(), gate.callType());
			if (null == wu) throw boardRefusal(bd, gate);
			return new PersonContext(wu, ud.getEmail(), ud.getUuid(), null);
		}
		var ahp = personalKey(dfe, bd.getOrg());
		WhoUpdated wu = authorizationService.keyOnBoard(ahp, bd.getOrg(), bd.getUuid(), gate.functions(), gate.callType());
		if (null == wu) throw boardRefusal(bd, gate);
		UserData person = userService.getUserData(ahp.getObjUuid())
				.orElseThrow(() -> new RelizaException(PERSON_ONLY));
		return new PersonContext(wu, person.getEmail(), person.getUuid(), ahp);
	}

	/** As above, on the board of a task. */
	private PersonContext authorizePersonOnTask(DgsDataFetchingEnvironment dfe, AgentTaskData td, BoardGate gate)
			throws RelizaException {
		return authorizePersonOnBoard(dfe, boardOf(td), gate);
	}

	private AgentBoardData boardOf(AgentTaskData td) throws RelizaException {
		return agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard()));
	}

	/** Whether a person may read a board: for lists, which drop what they may not rather than refuse. */
	private boolean personReads(DgsDataFetchingEnvironment dfe, AgentBoardData bd) throws RelizaException {
		try {
			authorizePersonOnBoard(dfe, bd, PERSON_READ);
			return true;
		} catch (AccessDeniedException e) {
			return false;
		}
	}

	private static AccessDeniedException boardRefusal(AgentBoardData bd, BoardGate gate) {
		return new AccessDeniedException("Not authorized: this needs " + gate.functions().stream().map(Enum::name)
				.collect(Collectors.joining(" and ")) + " on board " + bd.getName());
	}

	private static AgentBoardData.PriorityType parsePriorityType(Object raw) throws RelizaException {
		if (!(raw instanceof String s) || StringUtils.isBlank(s)) return null;
		try {
			return AgentBoardData.PriorityType.valueOf(s);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Unrecognized priorityType: " + s);
		}
	}

	private static List<String> stringList(Object raw) {
		if (raw instanceof List<?> l) {
			return l.stream().filter(e -> e instanceof String).map(e -> (String) e)
					.collect(Collectors.toList());
		}
		return null;
	}

	private static UUID parseUuid(String raw, String field) throws RelizaException {
		if (StringUtils.isBlank(raw)) return null;
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			throw new RelizaException(field + " is not a valid UUID: " + raw);
		}
	}

	/**
	 * Requirements come over the wire as maps; convert with the shared mapper so the enums are
	 * validated here rather than deep in resolution.
	 */
	private static List<RequiredInput> parseRequiredInputs(Object raw) throws RelizaException {
		if (raw == null) return null;
		if (!(raw instanceof List<?> list)) throw new RelizaException("requiredInputs must be a list");
		List<RequiredInput> out = new java.util.ArrayList<>();
		for (Object o : list) {
			try {
				out.add(Utils.OM.convertValue(o, RequiredInput.class));
			} catch (RuntimeException e) {
				throw new RelizaException("Invalid requiredInput: " + o);
			}
		}
		return out;
	}


	/**
	 * The level the board reads (RD2-1): the task's own, else its board's default. The board is read
	 * once per request, however many of its tasks the reply carries.
	 */
	@DgsData(parentType = "AgentTask", field = "effectiveWorkLevel")
	public Integer effectiveWorkLevel(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		if (null == td) return null;
		if (null != td.getWorkLevel()) return td.getWorkLevel();
		Map<UUID, Optional<AgentBoardData>> boards = dfe.getGraphQlContext()
				.computeIfAbsent("effectiveWorkLevelBoards", k -> new java.util.concurrent.ConcurrentHashMap<>());
		AgentBoardData board = boards.computeIfAbsent(td.getBoard(), agentBoardService::getBoardData).orElse(null);
		return AgentTaskService.effectiveWorkLevel(td, board);
	}

	/**
	 * Inputs this task was bound to that have since moved below what its requirement asks for.
	 * Empty on anything not currently assigned. Computed per read so it never goes stale.
	 */
	@DgsData(parentType = "AgentTask", field = "regressedInputs")
	public List<String> regressedInputs(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return agentTaskInputService.regressedBindings(td);
	}

	/**
	 * Everything attributed to this task, across every hop and every session that touched it.
	 *
	 * <p>Computed per read rather than stored on the task: usage can be attributed late (a report
	 * naming a task arrives after the hop closed), and a stored rollup would then be quietly wrong
	 * with nothing to trigger a rewrite. A field resolver also means the cost is only paid by
	 * callers that ask for the field.
	 */
	/**
	 * Publish a document version against the board's documents repository.
	 *
	 * <p>Identity-verified against the calling key exactly as the task mutations are: the session
	 * must be open, in the key's org, and owned by the key's agent identity. The service then
	 * checks that the session holds the task's current assignment, since publishing is a write on
	 * behalf of the hop in progress.
	 */
	@DgsData(parentType = "Mutation", field = "agentDocumentPublishProgrammatic")
	public ReleaseData agentDocumentPublishProgrammatic(DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		Map<String, Object> input = dfe.getArgument("input");
		UUID sessionUuid = uuidArg(input.get("sessionUuid"));
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		resolveCallingAgent(ctx, sessionUuid);
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));

		RearmSpecificationType spec = RearmSpecificationType
				.fromValue((String) input.get("specification"));
		if (null == spec) {
			throw new RelizaException("Unrecognized specification type: " + input.get("specification"));
		}
		UUID taskUuid = uuidArg(input.get("taskUuid"));
		if (null != taskUuid) {
			requireOrgTask(ctx, taskUuid);
		} else {
			// A component-scoped document goes to the session's board: the key must cover it too.
			requireOrgBoard(ctx, agentDocumentService.componentScopeBoard(sd, uuidArg(input.get("component"))));
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> index = (Map<String, Object>) input.get("index");
		ReleaseLifecycle lifecycle = null;
		if (null != input.get("lifecycle")) {
			try {
				lifecycle = ReleaseLifecycle.valueOf((String) input.get("lifecycle"));
			} catch (IllegalArgumentException e) {
				throw new RelizaException("Unrecognized lifecycle: " + input.get("lifecycle"));
			}
		}
		AgentDocumentService.PublishRequest req = new AgentDocumentService.PublishRequest(
				taskUuid, spec, uuidArg(input.get("component")),
				(String) input.get("path"), (String) input.get("digest"),
				(String) input.get("mediaType"), (String) input.get("indexPath"),
				(String) input.get("indexDigest"), index,
				(String) input.get("commit"), (String) input.get("vcsUri"),
				(String) input.get("commitMessage"), timeArg(input.get("commitDate")), lifecycle,
				(String) input.get("elements"), (String) input.get("elementsDigest"),
				Boolean.TRUE.equals(input.get("advisory")));
		return agentDocumentService.publish(sd, req, ctx.wu());
	}

	/** A plain JSON object of strings, as the Object scalar hands it over. */
	/** A declared map with its keys as strings and its values as sent: a family name or {family, definedIn}. */
	private static Map<String, Object> objectMap(Map<?, ?> raw) {
		Map<String, Object> out = new LinkedHashMap<>();
		raw.forEach((k, v) -> out.put(String.valueOf(k), v));
		return out;
	}

	private static io.reliza.model.ElementCheckPolicy elementCheckPolicyOf(Object raw) throws RelizaException {
		if (null == raw) return null;
		try {
			return Utils.OM.convertValue(raw, io.reliza.model.ElementCheckPolicy.class);
		} catch (RuntimeException e) {
			throw new RelizaException("Could not read elementCheckPolicy: " + e.getMessage());
		}
	}

	/** The newest check report for each of the task's element-bearing documents (elements.md §7). */
	@DgsData(parentType = "AgentTask", field = "elementChecks")
	public List<io.reliza.model.ElementCheckReport> taskElementChecks(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return agentDocumentService.elementChecksOfTask(td);
	}

	/** Re-run the checks of a document of the session's task; the session must hold the task. */
	@DgsData(parentType = "Mutation", field = "agentElementCheckRunProgrammatic")
	public ReleaseData agentElementCheckRunProgrammatic(DgsDataFetchingEnvironment dfe,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		resolveCallingAgent(ctx, sessionUuid);
		requireOrgTaskRelease(ctx, releaseUuid);
		AgentSessionData session = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		return agentDocumentService.runElementChecks(releaseUuid, session, ctx.wu());
	}

	/**
	 * The checks a publish of these elements would run, in the task's current scope, with nothing created (task
	 * RD4-6): what {@code rearm agent doc publish --check} prints. Any session the board lets work may ask.
	 */
	@DgsData(parentType = "Query", field = "agentElementCheckPreviewProgrammatic")
	public io.reliza.model.ElementCheckReport agentElementCheckPreviewProgrammatic(DgsDataFetchingEnvironment dfe,
			@InputArgument("sessionUuid") UUID sessionUuid,
			@InputArgument("taskUuid") UUID taskUuid,
			@InputArgument("specification") String specification,
			@InputArgument("elements") String elements,
			@InputArgument("elementsDigest") String elementsDigest) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		resolveCallingAgent(ctx, sessionUuid);
		AgentTaskData td = requireOrgTask(ctx, taskUuid);
		RearmSpecificationType spec = RearmSpecificationType.fromValue(specification);
		if (null == spec) throw new RelizaException("Unknown specification: " + specification);
		return agentDocumentService.previewElementChecks(td, spec, elements, elementsDigest);
	}

	/** The catalogue: static, the same for every caller. */
	@DgsData(parentType = "Query", field = "elementCheckCatalogue")
	public List<io.reliza.service.ElementCheckCatalogueService.CatalogueEntry> elementCheckCatalogue() {
		return io.reliza.service.ElementCheckCatalogueService.CATALOGUE;
	}

	/** The newest check report about a document; null when it has none. */
	@DgsData(parentType = "Query", field = "agentElementCheckReportProgrammatic")
	public ReleaseData agentElementCheckReportProgrammatic(DgsDataFetchingEnvironment dfe,
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		requireOrgTaskRelease(ctx, releaseUuid);
		return agentDocumentService.latestElementCheckReport(releaseUuid).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "agentElementCheckRun")
	public ReleaseData agentElementCheckRun(@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("Release not found: " + releaseUuid));
		WhoUpdated wu = authorizePersonOnRelease(rd, PERSON_WRITE, CallType.WRITE);
		return agentDocumentService.runElementChecks(releaseUuid, null, wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "elementCheckReport")
	public ReleaseData elementCheckReport(@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("Release not found: " + releaseUuid));
		authorizePersonOnRelease(rd, PERSON_READ, CallType.READ);
		return agentDocumentService.latestElementCheckReport(releaseUuid).orElse(null);
	}

	/**
	 * A person's call about a document: on its task's board when it is a task's document, else at the
	 * organization, as before (a component-scoped document belongs to no one board's tasks).
	 */
	private WhoUpdated authorizePersonOnRelease(ReleaseData rd, BoardGate gate, CallType orgCallType) throws RelizaException {
		if (null != rd.getDocument() && null != rd.getDocument().task()) {
			AgentTaskData td = agentTaskService.getTaskData(rd.getDocument().task())
					.orElseThrow(() -> new RelizaException("Task not found: " + rd.getDocument().task()));
			return authorizePersonOnTask(null, td, gate).wu();
		}
		return authorizeJwtOrg(rd.getOrg(), orgCallType);
	}

	/** A task's document of the calling key's organization, on a board the key covers for the operation. */
	private ReleaseData requireOrgTaskRelease(ProgKeyContext ctx, UUID releaseUuid) throws RelizaException {
		ReleaseData rd = requireOrgRelease(ctx.orgUuid(), releaseUuid);
		if (null == rd.getDocument() || null == rd.getDocument().task()) {
			throw new RelizaException("Release " + releaseUuid + " is not a task's document");
		}
		requireOrgTask(ctx, rd.getDocument().task());
		return rd;
	}

	private ReleaseData requireOrgRelease(UUID orgUuid, UUID releaseUuid) throws RelizaException {
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("Release not found: " + releaseUuid));
		if (!orgUuid.equals(rd.getOrg())) throw new AccessDeniedException("Release is not in the calling key's org");
		return rd;
	}

	@DgsData(parentType = "AgentTask", field = "elements")
	public List<AgentDocumentService.TaskElement> taskElements(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return agentDocumentService.taskElements(td);
	}

	private static UUID uuidArg(Object o) {
		return o instanceof String s && !s.isBlank() ? UUID.fromString(s) : null;
	}

	private static ZonedDateTime timeArg(Object o) {
		return o instanceof String s && !s.isBlank() ? ZonedDateTime.parse(s) : null;
	}

	/**
	 * This task's document releases, newest first. Computed per read so a round published a
	 * moment ago is visible without any cache to invalidate.
	 */
	@DgsData(parentType = "AgentTask", field = "documents")
	public List<ReleaseData> taskDocuments(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return agentDocumentService.documentsOfTask(td);
	}

	/**
	 * Review items still open on this task, newest round of each indexed type, by priority.
	 *
	 * <p>Newest round only: every round carries forward what the previous one left open, so the
	 * newest IS the current state. Reading every round would report a review item carried across three
	 * rounds three times.
	 */
	@DgsData(parentType = "AgentTask", field = "openReviewItems")
	public DataFetcherResult<List<BoardReviewItem>> taskOpenReviewItems(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return DataFetcherResult.<List<BoardReviewItem>>newResult().data(agentDocumentService.openReviewItemsOfTask(td))
				.localContext(new BoardReviewItemContext(null, td)).build();
	}

	/**
	 * The open items of the newest BOARD_QUESTIONS round only.
	 *
	 * <p>Separate from {@code openReviewItems}, which flattens the newest round of every indexed type
	 * into one list with nothing on an item saying which round it came from. A human answering
	 * needs exactly the questions, and deriving them from the flattened list is not possible --
	 * which is why the answer form gets its own field rather than a filter the client cannot write.
	 */
	@DgsData(parentType = "AgentTask", field = "openQuestions")
	public DataFetcherResult<List<BoardReviewItem>> taskOpenQuestions(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		BoardReviewItemIndex questions = agentDocumentService.latestIndexes(td)
				.get(RearmSpecificationType.BOARD_QUESTIONS);
		return DataFetcherResult.<List<BoardReviewItem>>newResult().data(null == questions ? List.of() : questions.openReviewItems())
				.localContext(new BoardReviewItemContext(null, td)).build();
	}

	/**
	 * What a review item's element resolves against, handed down from where the review item was read: the
	 * release its round is about, else the task whose documents it is read through (elements.md §8).
	 */
	record BoardReviewItemContext(UUID aboutRelease, AgentTaskData task) {}

	/** A round's items, told which release they are about, so their elements resolve there. */
	@DgsData(parentType = "DocumentRef", field = "reviewItems")
	public DataFetcherResult<BoardReviewItemIndex> documentReviewItems(DgsDataFetchingEnvironment dfe) {
		ReleaseData.DocumentRef doc = dfe.getSource();
		BoardReviewItemIndex index = null == doc ? null : doc.reviewItems();
		UUID about = null == index || null == index.about() ? null : index.about().release();
		AgentTaskData task = null == about && null != doc && null != doc.task()
				? agentTaskService.getTaskData(doc.task()).orElse(null) : null;
		return DataFetcherResult.<BoardReviewItemIndex>newResult().data(index)
				.localContext(new BoardReviewItemContext(about, task)).build();
	}

	@DgsData(parentType = "BoardReviewItem", field = "element")
	public AgentDocumentService.TaskElement reviewItemElement(DgsDataFetchingEnvironment dfe) {
		BoardReviewItem f = dfe.getSource();
		String element = null == f || null == f.location() ? null : f.location().element();
		if (null == element) return null;
		if (!(dfe.getLocalContext() instanceof BoardReviewItemContext ctx)) return null;
		if (null != ctx.aboutRelease()) return agentDocumentService.elementOf(ctx.aboutRelease(), element).orElse(null);
		if (null == ctx.task()) return null;
		return agentDocumentService.taskElements(ctx.task()).stream().filter(e -> element.equals(e.id()))
				.findFirst().orElse(null);
	}

	@DgsData(parentType = "AgentTask", field = "dependentsOf")
	public AgentDocumentService.ElementDependents taskDependentsOf(DgsDataFetchingEnvironment dfe,
			@InputArgument("element") String element, @InputArgument("depth") Integer depth) {
		AgentTaskData td = dfe.getSource();
		return agentDocumentService.dependentsOf(td, element, depth);
	}

	@DgsData(parentType = "AgentTask", field = "elementHistory")
	public List<AgentDocumentService.ElementVersion> taskElementHistory(DgsDataFetchingEnvironment dfe,
			@InputArgument("element") String element) {
		AgentTaskData td = dfe.getSource();
		return agentDocumentService.elementHistory(td, element);
	}

	@DgsData(parentType = "AgentTask", field = "usage")
	public AgentTaskData.HopUsage taskUsage(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return agentSessionUsageService.taskUsage(td);
	}

	/** What the board charges the task (task 02bfab7c): its rows plus its coordinator share, as the budget reads it. */
	@DgsData(parentType = "AgentTask", field = "spentMicros")
	public Long taskSpentMicros(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return null == td ? null : agentBudgetService.spentOnTask(td);
	}

	/**
	 * One linked PR as the board sees it; {@code state} is null when CI never reported the URL.
	 * {@code headSce} is the row's newest commit, resolved to a sha only when {@code head} is read.
	 * {@code base} is where the task's newest round found the PR's target branch (task RD4-2), counted from only
	 * when {@code baseMovedBy} is read; null for a PR not in play.
	 */
	public record TaskPullRequestView(String url, String state, String targetBranch,
			java.time.ZonedDateTime mergedDate, boolean registered, UUID headSce, AgentTaskData.Delivery declaration,
			AgentTaskData.BaseHead base) {}

	/**
	 * The task's linked PRs (task 9af9d722). Batched: every task in one read hands its match keys to
	 * the loader below, which reads the PR table once per org rather than once per task (T-1).
	 */
	@DgsData(parentType = "AgentTask", field = "pullRequests")
	public java.util.concurrent.CompletionStage<List<TaskPullRequestView>> taskPullRequests(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		if (null == td.getPrUrls() || td.getPrUrls().isEmpty()) {
			return java.util.concurrent.CompletableFuture.completedFuture(List.of());
		}
		org.dataloader.DataLoader<TaskPrKey, java.util.Optional<io.reliza.model.PullRequestData>> loader =
				dfe.getDataLoader("taskPullRequestLoader");
		List<TaskPrKey> keys = io.reliza.service.AgentDeliveryService.matchKeys(td).stream()
				.map(k -> new TaskPrKey(td.getOrg(), k)).toList();
		return loader.loadMany(keys).thenApply(rows -> {
			Map<String, io.reliza.model.PullRequestData> byKey = new LinkedHashMap<>();
			for (int i = 0; i < keys.size(); i++) {
				if (rows.get(i).isPresent()) byKey.put(keys.get(i).key(), rows.get(i).get());
			}
			return io.reliza.service.AgentDeliveryService.pullRequestsOf(td, byKey).stream()
					.map(pr -> new TaskPullRequestView(pr.url(), null == pr.state() ? null : pr.state().name(),
							pr.targetBranch(), pr.mergedDate(), pr.registered(), pr.headSce(), pr.declaration(),
							pr.registered() ? io.reliza.service.AgentDeliveryService.roundBase(td, pr.url()) : null))
					.toList();
		});
	}

	/** The PR's newest commit sha as ReARM knows it (task 3b97ccfd); null when unregistered or unknown. */
	@DgsData(parentType = "AgentTaskPullRequest", field = "head")
	public String taskPullRequestHead(DgsDataFetchingEnvironment dfe) {
		TaskPullRequestView pr = dfe.getSource();
		if (null == pr || null == pr.headSce()) return null;
		return getSourceCodeEntryService.getSourceCodeEntryData(pr.headSce())
				.map(SourceCodeEntryData::getCommit).orElse(null);
	}

	/**
	 * How many commits ReARM recorded on the PR's target branch since the task's newest round started (task
	 * RD4-2): "base moved: N commits since your round". Null for a PR not in play, a round that recorded no base,
	 * or a branch with no entry at all. Read only when selected: one count per PR.
	 */
	@DgsData(parentType = "AgentTaskPullRequest", field = "baseMovedBy")
	public Integer taskPullRequestBaseMovedBy(DgsDataFetchingEnvironment dfe) {
		TaskPullRequestView pr = dfe.getSource();
		return null == pr ? null : agentDeliveryService.baseMovedBy(pr.base());
	}

	/** The PR heads the newest passing review or test round covered (task 3b97ccfd). */
	@DgsData(parentType = "AgentTask", field = "testedHeads")
	public List<BoardReviewItemIndex.TestedHead> taskTestedHeads(DgsDataFetchingEnvironment dfe) {
		AgentTaskData td = dfe.getSource();
		return agentDeliveryService.testedOf(td).map(io.reliza.service.AgentDeliveryService.Tested::heads).orElse(List.of());
	}

	/** A linked PR's match key within its org. */
	public record TaskPrKey(UUID org, String key) {}

	/** Its own bean, with its own dependency: the fetcher it sits in is proxied. */
	@com.netflix.graphql.dgs.DgsDataLoader(name = "taskPullRequestLoader")
	public static class TaskPullRequestBatchLoader implements org.dataloader.BatchLoader<TaskPrKey,
			java.util.Optional<io.reliza.model.PullRequestData>> {
		@Autowired private io.reliza.service.AgentDeliveryService agentDeliveryService;

		@Override
		public java.util.concurrent.CompletionStage<List<java.util.Optional<io.reliza.model.PullRequestData>>> load(
				List<TaskPrKey> keys) {
			Map<UUID, java.util.Set<String>> byOrg = new LinkedHashMap<>();
			for (TaskPrKey k : keys) byOrg.computeIfAbsent(k.org(), o -> new java.util.LinkedHashSet<>()).add(k.key());
			Map<UUID, Map<String, io.reliza.model.PullRequestData>> found = new java.util.HashMap<>();
			byOrg.forEach((org, ks) -> found.put(org, agentDeliveryService.pullRequestsByKey(org, ks)));
			List<java.util.Optional<io.reliza.model.PullRequestData>> out = new ArrayList<>(keys.size());
			for (TaskPrKey k : keys) out.add(java.util.Optional.ofNullable(found.get(k.org()).get(k.key())));
			return java.util.concurrent.CompletableFuture.completedFuture(out);
		}
	}


	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentBoardSpec")
	public AgentBoardService.BoardSpecDto agentBoardSpec(@InputArgument("boardUuid") UUID boardUuid)
			throws RelizaException {
		AgentBoardData bd = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		authorizePersonOnBoard(null, bd, PERSON_SPEC_READ);
		return agentBoardService.exportBoard(boardUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentRolePresetsSpec")
	public AgentBoardService.RolePresetsSpecDto agentRolePresetsSpec(@InputArgument("orgUuid") UUID orgUuid)
			throws RelizaException {
		authorizeJwtOrg(orgUuid, CallType.READ);
		return agentBoardService.exportRolePresets(orgUuid);
	}

	/**
	 * Apply a board file from the UI. The organization's admin, or a user holding
	 * CONFIGURATION_WRITE: the gate every declarative write in ReARM uses (declarative-boards D10).
	 */
	@DgsData(parentType = "Mutation", field = "agentBoardApplySpec")
	public DeclarativeConfigService.ApplyResult agentBoardApplySpec(@InputArgument("orgUuid") UUID orgUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		// A person of the organization; what they may apply is judged on the board the file names
		// (architecture d8e7bd7e §3.3), so a board-scoped grant is enough for its own board.
		PersonContext jc = authorizePersonCtx(dfe, orgUuid, CallType.READ);
		Map<String, Object> source = dfe.getArgument("source");
		return agentBoardService.applyBoard(orgUuid, AgentBoardService.boardSpecFromInput(dfe.getArgument("spec")),
				Boolean.TRUE.equals(dfe.getArgument("dryRun")),
				null == source ? null : Utils.OM.convertValue(source, DeclarativeConfigService.SourceDto.class),
				jc.actor(), personConsent(jc, orgUuid), personAccess(jc, orgUuid), jc.wu());
	}

	@DgsData(parentType = "Mutation", field = "agentRolePresetsApplySpec")
	public DeclarativeConfigService.ApplyResult agentRolePresetsApplySpec(@InputArgument("orgUuid") UUID orgUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		PersonContext jc = authorizePersonCtx(dfe, orgUuid, CallType.WRITE, PermissionFunction.CONFIGURATION_WRITE);
		return agentBoardService.applyRolePresets(orgUuid,
				AgentBoardService.rolePresetsSpecFromInput(dfe.getArgument("spec")),
				Boolean.TRUE.equals(dfe.getArgument("dryRun")), null, jc.wu());
	}

	@DgsData(parentType = "Query", field = "agentBoardSpecProgrammatic")
	public AgentBoardService.BoardSpecDto agentBoardSpecProgrammatic(@InputArgument("boardUuid") UUID boardUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrg(dfe);
		requireOrgBoard(ctx, boardUuid);
		return agentBoardService.exportBoard(boardUuid);
	}

}
