/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserData;
import io.reliza.service.AgentSessionVisibilityService;
import io.reliza.model.AgentData;
import io.reliza.model.AgentData.AgentStatus;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ModelAssertionState;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AgentMonitoringService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionOriginService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.ModelOntologyService;
import io.reliza.service.UserService;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import io.reliza.service.AgentSessionUsageService;
import io.reliza.model.SessionUsageHosting;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingSelector.ReasoningMatch;
import io.reliza.model.PricingEntry.PricingSelector.ServiceTier;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.AgentBoardData;
import io.reliza.service.AgentBoardService;
import lombok.extern.slf4j.Slf4j;

@DgsComponent
@Slf4j
public class AgentDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private AgentService agentService;

	@Autowired
	private AgentIdentityService agentIdentityService;

	@Autowired
	private ApiKeyService apiKeyService;

	@Autowired
	private AgentSessionService agentSessionService;
	@Autowired
	private io.reliza.service.AgentSessionVisibilityService agentSessionVisibilityService;

	@Autowired
	private AgentSessionOriginService agentSessionOriginService;

	@Autowired
	private AgentSessionUsageService agentSessionUsageService;

	@Autowired
	private AgentBoardService agentBoardService;

	@Autowired
	private ModelOntologyService modelOntologyService;

	@Autowired
	private AgentMonitoringService agentMonitoringService;

	@Autowired
	private io.reliza.service.SharedReleaseService sharedReleaseService;

	@Autowired
	private io.reliza.service.PullRequestService pullRequestService;

	// ---------- Queries (JWT auth) ----------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agent")
	public AgentData getAgent(@InputArgument("uuid") UUID uuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<AgentData> oad = agentService.getAgentData(uuid);
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, Collections.singletonList(ro), CallType.READ);
		return oad.orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentsOfOrg")
	public List<AgentData> agentsOfOrg(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.READ);
		return agentService.listByOrg(orgUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "subAgentsOf")
	public List<AgentData> subAgentsOf(@InputArgument("rootAgentUuid") UUID rootAgentUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<AgentData> ad = agentService.getAgentData(rootAgentUuid);
		RelizaObject ro = ad.isPresent() ? ad.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, Collections.singletonList(ro), CallType.READ);
		return agentService.listByRoot(rootAgentUuid);
	}

	/**
	 * The sessions a person may read among these (board-permissions.md D15): one that worked boards
	 * needs BOARD_READ on one of them, one that never did keeps the organization read the caller
	 * passed already.
	 */
	private List<AgentSessionData> personReadable(UserData ud, UUID orgUuid, List<AgentSessionData> sessions) {
		if (null == ud || sessions.isEmpty()) return sessions;
		Map<UUID, Set<UUID>> worked = agentSessionVisibilityService.boardsWorked(orgUuid, sessions);
		Map<UUID, Boolean> reads = new java.util.HashMap<>();
		java.util.function.Predicate<UUID> readsBoard = board -> reads.computeIfAbsent(board,
				b -> authorizationService.boardPermission(ud, orgUuid, b, PermissionFunction.BOARD_READ, CallType.READ));
		return sessions.stream()
				.filter(sd -> AgentSessionVisibilityService.personMayRead(worked.get(sd.getUuid()), readsBoard))
				.toList();
	}

	/** As above for a field resolver: filtered for a signed-in person, as served otherwise. */
	private List<AgentSessionData> readableBySignedInPerson(UUID orgUuid, List<AgentSessionData> sessions) {
		if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth)) return sessions;
		return personReadable(userService.getUserDataByAuth(auth).orElse(null), orgUuid, sessions);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "session")
	public AgentSessionData getSession(@InputArgument("uuid") UUID uuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<AgentSessionData> osd = agentSessionService.getSessionData(uuid);
		RelizaObject ro = osd.isPresent() ? osd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, Collections.singletonList(ro), CallType.READ);
		// A session that worked boards reads with them (board-permissions.md D15); one the person may
		// not read is answered as an unknown uuid.
		return osd.filter(sd -> personReadable(oud.get(), sd.getOrg(), List.of(sd)).size() == 1).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sessionsOfOrg")
	public List<AgentSessionData> sessionsOfOrg(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("statuses") List<String> statuses) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.READ);
		return personReadable(oud.get(), orgUuid, agentSessionService.listByOrg(orgUuid, statuses));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sessionsByProviderSession")
	public List<AgentSessionData> sessionsByProviderSession(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("providerSessionId") String providerSessionId) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.READ);
		return personReadable(oud.get(), orgUuid, agentSessionService.listByProviderSession(orgUuid, providerSessionId));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sessionsOfAgent")
	public List<AgentSessionData> sessionsOfAgent(@InputArgument("rootAgentUuid") UUID rootAgentUuid,
			@InputArgument("statuses") List<String> statuses) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<AgentData> ad = agentService.getAgentData(rootAgentUuid);
		RelizaObject ro = ad.isPresent() ? ad.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, Collections.singletonList(ro), CallType.READ);
		return null == ro ? List.of() : personReadable(oud.get(), ro.getOrg(), agentSessionService.listByAgent(rootAgentUuid, statuses));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "modelOntology")
	public ModelOntologyData getModelOntology(@InputArgument("uuid") UUID uuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ModelOntologyData> omd = modelOntologyService.getModelOntologyData(uuid);
		RelizaObject ro = omd.isPresent() ? omd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, Collections.singletonList(ro), CallType.READ);
		return omd.orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "modelOntologiesOfOrg")
	public List<ModelOntologyData> modelOntologiesOfOrg(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.READ);
		return modelOntologyService.listByOrg(orgUuid);
	}

	/**
	 * Field resolver: Agent.model returns the resolved ModelOntology
	 * row (not just the uuid). Skipped when the parent agent has no
	 * model pointer.
	 */
	@DgsData(parentType = "Agent", field = "model")
	public ModelOntologyData agentModel(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null || ad.getModel() == null) return null;
		return modelOntologyService.getModelOntologyData(ad.getModel()).orElse(null);
	}

	// ---------- Monitoring read-side (PR 3) ----------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentDashboardKpis")
	public AgentMonitoringService.AgentDashboardKpis agentDashboardKpis(
			@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.READ);
		return agentMonitoringService.computeDashboardKpis(orgUuid);
	}

	@DgsData(parentType = "Agent", field = "openSessions")
	public List<AgentSessionData> agentOpenSessions(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null) return List.of();
		return readableBySignedInPerson(ad.getOrg(), agentSessionService.listByAgent(ad.getUuid(), List.of("OPEN")));
	}

	@DgsData(parentType = "Agent", field = "closedSessions")
	public List<AgentSessionData> agentClosedSessions(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null) return List.of();
		return readableBySignedInPerson(ad.getOrg(), agentSessionService.listByAgent(ad.getUuid(), List.of("CLOSED")));
	}

	/**
	 * Field resolver: Agent.effectiveDisplayName — the label the UI
	 * renders. Precedence (strongest last): name, then first bound
	 * FREEFORM key note, then displayName. So an explicit displayName
	 * wins; absent that, a note on a bound key gives a friendly label
	 * for free; absent both, the registration name. No re-auth (parent
	 * query already authorized).
	 */
	@DgsData(parentType = "Agent", field = "effectiveDisplayName")
	public String agentEffectiveDisplayName(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null) return null;
		if (StringUtils.isNotBlank(ad.getDisplayName())) return ad.getDisplayName();
		if (ad.getAgentIdentity() != null) {
			for (AgentIdentityCredential cred : agentIdentityService.listCredentials(ad.getAgentIdentity())) {
				if (!AgentIdentityCredential.IdentityType.REARM_API_KEY.name().equals(cred.getIdentityType())) continue;
				try {
					var ak = apiKeyService.getApiKeyDto(UUID.fromString(cred.getIdentityValue()));
					if (ak.isPresent() && StringUtils.isNotBlank(ak.get().getNotes())) {
						return ak.get().getNotes();
					}
				} catch (IllegalArgumentException e) {
					// non-uuid credential value — skip
				}
			}
		}
		return ad.getName();
	}

	/**
	 * Field resolver: ApiKey.boundAgents — root agents driven by this
	 * key. Resolves the key uuid through agent_identity_credentials to
	 * an AgentIdentity, then to the root agents sharing it. Empty when
	 * the key has never been used to open a session. No re-auth: the
	 * parent apiKeys query already authorized org access.
	 */
	@DgsData(parentType = "ApiKey", field = "boundAgents")
	public List<AgentData> apiKeyBoundAgents(DgsDataFetchingEnvironment dfe) {
		ApiKeyDto ak = dfe.getSource();
		if (ak == null || ak.getUuid() == null || ak.getOrg() == null) return List.of();
		Optional<AgentIdentityData> identity = agentIdentityService.findByCredential(
				AgentIdentityCredential.IdentityType.REARM_API_KEY, ak.getUuid().toString());
		if (identity.isEmpty()) return List.of();
		return agentService.listRootsByAgentIdentity(ak.getOrg(), identity.get().getUuid());
	}

	/**
	 * Field resolver: Agent.boundApiKeys — the FREEFORM key(s) bound to
	 * this agent's identity. No re-auth: the parent agent query already
	 * authorized org access.
	 */
	@DgsData(parentType = "Agent", field = "boundApiKeys")
	public List<ApiKeyDto> agentBoundApiKeys(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null || ad.getAgentIdentity() == null) return List.of();
		List<ApiKeyDto> keys = new java.util.ArrayList<>();
		for (AgentIdentityCredential cred : agentIdentityService.listCredentials(ad.getAgentIdentity())) {
			if (!AgentIdentityCredential.IdentityType.REARM_API_KEY.name().equals(cred.getIdentityType())) continue;
			try {
				apiKeyService.getApiKeyDto(UUID.fromString(cred.getIdentityValue())).ifPresent(keys::add);
			} catch (IllegalArgumentException e) {
				// non-uuid credential value (future credential types) — skip
			}
		}
		return keys;
	}

	@DgsData(parentType = "Agent", field = "sessionCounts")
	public AgentMonitoringService.AgentSessionCounts agentSessionCounts(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null) return new AgentMonitoringService.AgentSessionCounts(0, 0);
		return agentMonitoringService.countsForAgent(ad.getUuid());
	}

	@DgsData(parentType = "Agent", field = "lastActivityAt")
	public java.time.ZonedDateTime agentLastActivityAt(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null) return null;
		return agentMonitoringService.lastActivityForAgent(ad.getUuid());
	}

	/**
	 * Session.releases — distinct releases produced from this session's
	 * commits, matched on Release.sourceCodeEntry or Release.commits[]
	 * in one batched query for the whole commits list (most recently
	 * updated first). Empty list when no commits or no releases minted
	 * yet.
	 */
	@DgsData(parentType = "Session", field = "releases")
	public List<io.reliza.model.ReleaseData> sessionReleases(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null || sd.getCommits() == null || sd.getCommits().isEmpty()) return List.of();
		return sharedReleaseService.findReleaseDatasBySces(sd.getCommits(), sd.getOrg());
	}

	/**
	 * Session.pullRequests — distinct PRs whose commits[] list contains
	 * any SCE from session.commits. One DB hit using the jsonb ?|
	 * operator regardless of how many commits the session carries.
	 */
	@DgsData(parentType = "Session", field = "pullRequests")
	public List<io.reliza.model.PullRequestData> sessionPullRequests(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null || sd.getCommits() == null || sd.getCommits().isEmpty()) return List.of();
		String[] sceUuids = sd.getCommits().stream().map(UUID::toString).toArray(String[]::new);
		return pullRequestService.findByOrgAndAnyCommit(sd.getOrg(), sceUuids);
	}

	/**
	 * Field resolver: Session.boardsWorked (task RD2-5), the boards the session worked: recorded at
	 * assignment, or for a session from before, read from the tasks that list it and written once.
	 * The organization's derivation is made once per request, since a session list resolves this for
	 * every row. No re-auth -- the parent query already authorized.
	 */
	@DgsData(parentType = "Session", field = "boardsWorked")
	public List<UUID> sessionBoardsWorked(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null) return List.of();
		Map<UUID, Map<UUID, Set<UUID>>> derived = dfe.getGraphQlContext()
				.computeIfAbsent(SESSION_BOARDS_DERIVED, k -> new java.util.concurrent.ConcurrentHashMap<>());
		return List.copyOf(agentSessionVisibilityService.boardsWorked(sd,
				org -> derived.computeIfAbsent(org, agentSessionVisibilityService::boardsWorkedIn)));
	}

	private static final String SESSION_BOARDS_DERIVED = "rearm.sessionBoardsDerived";

	/**
	 * Field resolver: Session.tasksWorked (task RD2-11), for the session page: the tasks of its boards
	 * that list it, each with the role it worked as. A signed-in person sees those on boards they may
	 * read; the session itself they may read already (D15).
	 */
	/** When the idle sweep closes the session unless it calls again (task RD2-15); null once closed. */
	@DgsData(parentType = "Session", field = "idleCloseAt")
	public java.time.ZonedDateTime sessionIdleCloseAt(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		return agentSessionService.idleCloseAt(sd);
	}

	@DgsData(parentType = "Session", field = "tasksWorked")
	public List<AgentSessionVisibilityService.TaskWorked> sessionTasksWorked(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null) return List.of();
		// The reader's own board reads: a person's, or on the programmatic endpoint the key's -- a key
		// working board A reads a session that also worked B, and must not read B's tasks through it.
		java.util.function.Predicate<UUID> readsBoard = board -> false;
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) {
			UserData ud = userService.getUserDataByAuth(auth).orElse(null);
			if (null != ud) {
				readsBoard = board -> authorizationService.boardPermission(ud, sd.getOrg(), board,
						PermissionFunction.BOARD_READ, CallType.READ);
			}
		} else {
			try {
				DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
				var ahp = authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(),
						(ServletWebRequest) requestData.getWebRequest()).ahp();
				if (null != ahp) {
					readsBoard = board -> {
						try {
							return authorizationService.boardPermission(ahp, sd.getOrg(), board,
									PermissionFunction.BOARD_READ, CallType.READ);
						} catch (RelizaException e) {
							log.error("Could not read the key's grant on board {} for Session.tasksWorked", board, e);
							return false;
						}
					};
				}
			} catch (RuntimeException e) {
				log.error("Could not read the caller's board grants for Session.tasksWorked; serving none", e);
			}
		}
		return agentSessionVisibilityService.tasksWorked(sd, readsBoard);
	}

	/**
	 * Field resolver: Session.primaryModel resolves the session's model
	 * pointer to the full ModelOntology row. Null on legacy sessions
	 * written before per-session model tracking (model pointer is null).
	 * No re-auth — the parent query already authorized.
	 */
	@DgsData(parentType = "Session", field = "primaryModel")
	public ModelOntologyData sessionPrimaryModel(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null || sd.getModel() == null) return null;
		return modelOntologyService.getModelOntologyData(sd.getModel()).orElse(null);
	}

	/**
	 * Field resolver: Session.origin, with the personal fields -- hostnames, IP addresses, a
	 * federated actor -- withheld unless the reader is an org admin, the session's owner, or the
	 * key that opened it. The reader is worked out once per request, since a session list
	 * resolves this for every row.
	 */
	@DgsData(parentType = "Session", field = "origin")
	public AgentSessionOriginService.SessionOriginView sessionOrigin(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null || sd.getOrigin() == null) return null;
		AgentSessionOriginService.Viewer viewer = dfe.getGraphQlContext()
				.computeIfAbsent(SESSION_ORIGIN_VIEWER, k -> originViewer(dfe));
		return AgentSessionOriginService.view(sd.getOrigin(),
				AgentSessionOriginService.canSeePersonal(viewer, sd.getOrg(), sd.getApiKey(), sd.getOrigin()));
	}

	private static final String SESSION_ORIGIN_VIEWER = "rearm.sessionOriginViewer";

	/**
	 * Who is reading, for Session.origin. Anything that cannot be established reads as nobody,
	 * which sees the impersonal fields only -- the parent query has already decided the reader
	 * may see the session at all.
	 */
	private AgentSessionOriginService.Viewer originViewer(DgsDataFetchingEnvironment dfe) {
		try {
			var auth = SecurityContextHolder.getContext().getAuthentication();
			if (auth instanceof JwtAuthenticationToken jwt) {
				return userService.getUserDataByAuth(jwt).map(AgentSessionOriginService.Viewer::ofUser)
						.orElse(AgentSessionOriginService.Viewer.nobody());
			}
			DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
			var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
			ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
					requestData.getHeaders(), servletWebRequest);
			var ahp = authCtx.ahp();
			if (null == ahp) return AgentSessionOriginService.Viewer.nobody();
			UUID key = ahp.getVerifiedKeyUuid();
			if (null == key && null != authCtx.orgUuid()) {
				// Basic auth carries no verified key uuid; the authorization check resolves it.
				OrganizationData od = getOrganizationService.getOrganizationData(authCtx.orgUuid()).orElse(null);
				if (null != od) {
					key = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.AGENT,
							PermissionScope.ORGANIZATION, authCtx.orgUuid(), List.of(od), CallType.ESSENTIAL_READ)
							.apiKeyUuid();
				}
			}
			return AgentSessionOriginService.Viewer.ofKey(key, ahp.getActorUser());
		} catch (Exception e) {
			log.error("Could not establish who is reading Session.origin; withholding personal fields", e);
			return AgentSessionOriginService.Viewer.nobody();
		}
	}

	/**
	 * Field resolver: Session.modelAssertion — the trust level of
	 * primaryModel. Defaults to DECLARED (the data field's default), so
	 * legacy rows surface the honest value rather than null against the
	 * non-null schema field.
	 */
	@DgsData(parentType = "Session", field = "modelAssertion")
	public ModelAssertionState sessionModelAssertion(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null || sd.getModelAssertion() == null) return ModelAssertionState.DECLARED;
		return sd.getModelAssertion();
	}

	// ---------- Mutations ----------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateAgent")
	public AgentData updateAgent(@InputArgument("input") Map<String, Object> input) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID agentUuid = UUID.fromString((String) input.get("uuid"));
		AgentData existing = agentService.getAgentData(agentUuid)
				.orElseThrow(() -> new RelizaException("Agent not found: " + agentUuid));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, existing.getOrg(), List.of(existing), CallType.WRITE);
		AgentStatus status = input.get("status") != null
				? AgentStatus.valueOf((String) input.get("status"))
				: null;
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return agentService.updateAgent(
				agentUuid,
				(String) input.get("name"),
				(String) input.get("iconKind"),
				(String) input.get("color"),
				(String) input.get("notes"),
				status,
				wu);
	}

	/**
	 * Set an agent's admin-chosen display label. Org-admin only
	 * ({@code CallType.ADMIN}) — a deliberately tighter gate than the
	 * self-reported display fields on {@code updateAgent}. Never touches
	 * the registration {@code name}.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setAgentDisplayName")
	public AgentData setAgentDisplayName(@InputArgument("uuid") UUID uuid,
			@InputArgument("displayName") String displayName) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		AgentData existing = agentService.getAgentData(uuid)
				.orElseThrow(() -> new RelizaException("Agent not found: " + uuid));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, existing.getOrg(), List.of(existing), CallType.ADMIN);
		return agentService.setDisplayName(uuid, displayName, WhoUpdated.getWhoUpdated(oud.get()));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateModelOntology")
	public ModelOntologyData updateModelOntology(@InputArgument("input") Map<String, Object> input)
			throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID ontologyUuid = UUID.fromString((String) input.get("uuid"));
		ModelOntologyData existing = modelOntologyService.getModelOntologyData(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, existing.getOrg(), List.of(existing), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		ModelOntologyService.IdentityUpdate identity = new ModelOntologyService.IdentityUpdate(
				(String) input.get("name"), (String) input.get("version"),
				(String) input.get("canonicalId"), input.containsKey("canonicalId"));
		ModelOntologyData updated = modelOntologyService.updateModelOntology(
				ontologyUuid,
				(String) input.get("publisher"),
				(String) input.get("description"),
				(String) input.get("purl"),
				(String) input.get("notes"),
				strengthUpdateFromInput(input),
				identity,
				wu);
		if (input.get("tier") instanceof String tier && StringUtils.isNotBlank(tier)) {
			updated = modelOntologyService.setTier(ontologyUuid, ModelOntologyData.ModelTier.valueOf(tier), wu);
		}
		return updated;
	}

	@DgsData(parentType = "Mutation", field = "sessionInitializeProgrammatic")
	public AgentSessionData sessionInitializeProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrgWrite(dfe);
		Map<String, Object> input = dfe.getArgument("sessionInit");

		String agentName = (String) input.get("agentName");
		String agentModel = (String) input.get("agentModel");
		if (StringUtils.isBlank(agentName)) {
			throw new RelizaException("agentName is required on session initialize");
		}
		if (StringUtils.isBlank(agentModel)) {
			throw new RelizaException("agentModel is required on session initialize");
		}

		// Resolve / auto-register the model ontology first; the agent references it.
		//
		// Through resolve() rather than the older findOrRegisterModel: resolution normalises the
		// declared string, matches it against the org's aliases and the bundled catalogue, and
		// pre-fills facts and a canonical id when it recognises the model. Registering here
		// instead created a bare row with no aliases and a null resolution -- which the catalogue
		// UI shows as UNRESOLVED -- for models the bundle already knew, and left init and usage
		// reporting able to mint two different rows for one declared string.
		ModelOntologyData ontology = modelOntologyService.resolve(
				ctx.orgUuid,
				agentModel,
				(String) input.get("agentModelVersion"),
				ctx.wu).model();

		// Vendor is display-only and resolution does not take it, so it is applied after: a
		// bundled row already knows its publisher, and an operator's edit outranks a client's
		// declaration, so this only fills a blank.
		String declaredVendor = (String) input.get("agentVendor");
		if (StringUtils.isNotBlank(declaredVendor) && StringUtils.isBlank(ontology.getPublisher())) {
			ontology = modelOntologyService.updateModelOntology(ontology.getUuid(), declaredVendor,
					null, null, null, ctx.wu);
		}

		// Resolve the calling key to an AgentIdentity so the Agent row
		// is scoped to (org, identity, name) — two different keys can
		// each own a "Claude Code" without colliding.
		AgentIdentityData identity = agentIdentityService.findOrRegisterByCredential(
				ctx.orgUuid,
				AgentIdentityCredential.IdentityType.REARM_API_KEY,
				ctx.apiKeyUuid.toString(),
				ctx.wu);

		AgentData root = agentService.findOrRegisterRootAgent(
				ctx.orgUuid,
				identity.getUuid(),
				agentName,
				ontology.getUuid(),
				(String) input.get("agentIconKind"),
				(String) input.get("agentColor"),
				ctx.wu);

		UUID parentSessionUuid = null;
		Object parentRaw = input.get("parentSession");
		if (parentRaw instanceof String s && !s.isBlank()) {
			try { parentSessionUuid = UUID.fromString(s); }
			catch (IllegalArgumentException iae) {
				throw new RelizaException("parentSession is not a valid UUID: " + s);
			}
		}
		return agentSessionService.initialize(
				ctx.orgUuid,
				root.getUuid(),
				ctx.apiKeyUuid,
				(String) input.get("clientSessionId"),
				(String) input.get("title"),
				parentSessionUuid,
				ontology.getUuid(),
				providerSessionFromInput(input.get("providerSession")),
				agentSessionOriginService.resolve(ctx.ahp, ctx.apiKeyUuid, ctx.clientIp,
						deviceReportFromInput(input.get("device"))),
				ctx.wu);
	}

	@DgsData(parentType = "Mutation", field = "sessionTouchProgrammatic")
	@SuppressWarnings("unchecked")
	public AgentSessionData sessionTouchProgrammatic(@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		WhoUpdated wu = authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);
		Map<String, Object> usage = dfe.getArgument("usage");
		if (null != usage) {
			// Same path, same validation: an agent that cannot install hooks is not reporting
			// through a weaker contract, it is reporting on the back of its heartbeat.
			AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
					.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
			agentSessionUsageService.report(sd, toReport(sessionUuid, usage), wu);
		}
		return agentSessionService.touch(sessionUuid, wu);
	}

	@DgsData(parentType = "Mutation", field = "sessionReportUsageProgrammatic")
	public AgentSessionUsageService.UsageAck sessionReportUsageProgrammatic(
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> input = dfe.getArgument("input");
		return reportUsage(input, dfe);
	}

	/**
	 * Shared by the dedicated mutation and by the optional {@code usage} argument on touch: an
	 * agent that heartbeats but cannot install hooks reports the same way, through the same
	 * validation, rather than through a second-class path.
	 */
	private AgentSessionUsageService.UsageAck reportUsage(Map<String, Object> input,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		if (null == input) throw new RelizaException("Usage report input is required");
		UUID sessionUuid = resolveReportSession(input, dfe);
		WhoUpdated wu = authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		return agentSessionUsageService.report(sd, toReport(sessionUuid, input), wu);
	}

	/**
	 * {@code sessionUuid} wins when both are present. The client session id is the fallback so a
	 * hook that only knows the id the client generated can still report without a lookup of its
	 * own -- but it is resolved against the calling key's org, never across orgs.
	 */
	private UUID resolveReportSession(Map<String, Object> input, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		Object explicit = input.get("sessionUuid");
		if (null != explicit) return UUID.fromString(String.valueOf(explicit));
		String clientSessionId = (String) input.get("clientSessionId");
		if (StringUtils.isBlank(clientSessionId)) {
			throw new RelizaException("A usage report requires sessionUuid or clientSessionId");
		}
		// Resolved through the calling key's own agent, the same chain session init uses, rather
		// than by (org, clientSessionId): the id is the agent's to choose, so two agents in one
		// org may legitimately pick the same one, and the key says which agent is reporting.
		ProgKeyContext ctx = authorizeProgrammaticOrgWrite(dfe);
		AgentIdentityData identity = agentIdentityService.findOrRegisterByCredential(
				ctx.orgUuid,
				AgentIdentityCredential.IdentityType.REARM_API_KEY,
				ctx.apiKeyUuid.toString(),
				ctx.wu);
		// Read-only on purpose: reporting usage must not mint an agent. A key with no registered
		// root has no session to report against either, and says so.
		List<AgentData> roots = agentService.listRootsByAgentIdentity(ctx.orgUuid, identity.getUuid());
		return roots.stream()
				.map(root -> agentSessionService.getByClientSessionId(
						ctx.orgUuid, root.getUuid(), clientSessionId))
				.filter(java.util.Optional::isPresent)
				.map(found -> found.get().getUuid())
				.findFirst()
				.orElseThrow(() -> new RelizaException(
						"No session with clientSessionId '" + clientSessionId + "' for this agent"));
	}

	@SuppressWarnings("unchecked")
	private AgentSessionUsageService.UsageReport toReport(UUID sessionUuid, Map<String, Object> input)
			throws RelizaException {
		List<Map<String, Object>> lineInputs = (List<Map<String, Object>>) input.get("lines");
		if (null == lineInputs || lineInputs.isEmpty()) {
			throw new RelizaException("A usage report must carry at least one line");
		}
		List<AgentSessionUsageService.UsageLine> lines = new LinkedList<>();
		for (Map<String, Object> l : lineInputs) {
			lines.add(new AgentSessionUsageService.UsageLine(
					(String) l.get("model"),
					enumArg(SessionUsageHosting.class, l.get("hosting")),
					nullableLong(l.get("contextBand")),
					intArg(l.get("requests"), 0),
					longArg(l.get("inputTokens"), 0L),
					longArg(l.get("outputTokens"), 0L),
					longArg(l.get("cacheReadTokens"), 0L),
					longArg(l.get("cacheWriteTokens"), 0L),
					nullableLong(l.get("reasoningTokens")),
					nullableLong(l.get("maxRequestContextTokens")),
					nullableLong(l.get("minRequestContextTokens")),
					nullableLong(l.get("reportedCostMicros"))));
		}
		return new AgentSessionUsageService.UsageReport(
				sessionUuid,
				(String) input.get("clientSessionId"),
				longArg(input.get("clientSeq"), 0L),
				enumArg(SessionUsageSource.class, input.get("source")),
				timeArg(input.get("windowStart")),
				timeArg(input.get("windowEnd")),
				nullableInt(input.get("turns")),
				nullableInt(input.get("toolCalls")),
				nullableInt(input.get("wallSeconds")),
				(String) input.get("reasoningLevel"),
				null != input.get("taskUuid") ? UUID.fromString(String.valueOf(input.get("taskUuid"))) : null,
				(Map<String, Object>) input.get("raw"),
				lines);
	}

	private static <E extends Enum<E>> E enumArg(Class<E> type, Object raw) {
		return null == raw ? null : Enum.valueOf(type, String.valueOf(raw));
	}

	private static ZonedDateTime timeArg(Object raw) {
		return null == raw ? null : ZonedDateTime.parse(String.valueOf(raw));
	}

	private static long longArg(Object raw, long fallback) {
		return raw instanceof Number n ? n.longValue()
				: (null == raw ? fallback : Long.parseLong(String.valueOf(raw)));
	}

	private static Long nullableLong(Object raw) {
		return null == raw ? null : longArg(raw, 0L);
	}

	private static int intArg(Object raw, int fallback) {
		return raw instanceof Number n ? n.intValue()
				: (null == raw ? fallback : Integer.parseInt(String.valueOf(raw)));
	}

	private static Integer nullableInt(Object raw) {
		return null == raw ? null : intArg(raw, 0);
	}

	@DgsData(parentType = "Mutation", field = "sessionCloseProgrammatic")
	public AgentSessionData sessionCloseProgrammatic(@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		WhoUpdated wu = authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);
		return agentSessionService.close(sessionUuid, wu);
	}

	@DgsData(parentType = "Mutation", field = "sessionAddArtifactProgrammatic")
	@SuppressWarnings("unchecked")
	public AgentSessionData sessionAddArtifactProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> addArtifact = dfe.getArgument("addArtifact");
		UUID sessionUuid = UUID.fromString((String) addArtifact.get("sessionUuid"));
		List<Map<String, Object>> artifacts = (List<Map<String, Object>>) addArtifact.get("artifacts");
		if (artifacts == null || artifacts.isEmpty()) {
			throw new RelizaException("artifacts is required and must be non-empty");
		}
		WhoUpdated wu = authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);
		return agentSessionService.uploadAndAttachArtifacts(sessionUuid, artifacts, wu);
	}

	@DgsData(parentType = "Mutation", field = "sessionUpdateMetaProgrammatic")
	public AgentSessionData sessionUpdateMetaProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> updateMeta = dfe.getArgument("updateMeta");
		UUID sessionUuid = UUID.fromString((String) updateMeta.get("uuid"));
		WhoUpdated wu = authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);
		return agentSessionService.updateMeta(
				sessionUuid,
				(String) updateMeta.get("title"),
				(String) updateMeta.get("clientSessionId"),
				providerSessionFromInput(updateMeta.get("providerSession")),
				wu);
	}

	/**
	 * The strength part of a model edit. {@code strength} left out leaves it; sent as null unrates
	 * the model. {@code strengthByRole} sent replaces the per-category list.
	 */
	private static ModelOntologyService.StrengthUpdate strengthUpdateFromInput(Map<String, Object> input)
			throws RelizaException {
		boolean hasStrength = input.containsKey("strength");
		boolean hasByRole = input.containsKey("strengthByRole");
		if (!hasStrength && !hasByRole) return null;
		Object raw = input.get("strength");
		Double strength = raw instanceof Number n ? n.doubleValue() : null;
		List<ModelOntologyData.RoleStrength> byRole = null;
		if (hasByRole) {
			byRole = new ArrayList<>();
			if (input.get("strengthByRole") instanceof List<?> l) {
				for (Object e : l) {
					if (!(e instanceof Map<?, ?> m)) throw new RelizaException("strengthByRole entries must be objects");
					ModelOntologyData.RoleCategory category;
					try {
						category = ModelOntologyData.RoleCategory.valueOf(String.valueOf(m.get("category")));
					} catch (IllegalArgumentException iae) {
						throw new RelizaException("Unknown role category: " + m.get("category"));
					}
					byRole.add(new ModelOntologyData.RoleStrength(category,
							m.get("strength") instanceof Number sn ? sn.doubleValue() : null));
				}
			}
		}
		return new ModelOntologyService.StrengthUpdate(strength, hasStrength && null == strength, byRole);
	}

	private static AgentSessionOriginService.DeviceReport deviceReportFromInput(Object raw) {
		if (!(raw instanceof Map<?, ?> m)) return null;
		return new AgentSessionOriginService.DeviceReport((String) m.get("hostname"), (String) m.get("os"),
				(String) m.get("timeZone"), (String) m.get("client"));
	}

	private static AgentSessionService.ProviderSessionInput providerSessionFromInput(Object raw) {
		if (!(raw instanceof Map<?, ?> m)) return null;
		return new AgentSessionService.ProviderSessionInput((String) m.get("provider"),
				(String) m.get("id"), (String) m.get("remoteId"));
	}

	@DgsData(parentType = "Mutation", field = "spawnSubAgentProgrammatic")
	public AgentData spawnSubAgentProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrgWrite(dfe);
		Map<String, Object> input = dfe.getArgument("spawnSubAgent");
		UUID parentUuid = UUID.fromString((String) input.get("parentUuid"));

		AgentData parent = agentService.getAgentData(parentUuid)
				.orElseThrow(() -> new RelizaException("Parent agent not found: " + parentUuid));
		if (!ctx.orgUuid.equals(parent.getOrg())) {
			throw new AccessDeniedException("Parent agent does not belong to caller's org");
		}

		UUID modelOverride = null;
		String modelOverrideStr = (String) input.get("modelUuid");
		if (StringUtils.isNotBlank(modelOverrideStr)) {
			modelOverride = UUID.fromString(modelOverrideStr);
		}

		return agentService.spawnSubAgent(
				parentUuid,
				(String) input.get("name"),
				modelOverride,
				(String) input.get("iconKind"),
				(String) input.get("color"),
				ctx.wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "agentBoardUsage")
	public AgentSessionData.UsageTotals agentBoardUsage(@InputArgument("boardUuid") UUID boardUuid,
			@InputArgument("from") ZonedDateTime from, @InputArgument("to") ZonedDateTime to) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		AgentBoardData board = agentBoardService.getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		// A board's spend reads with the board (task d8e7bd7e, T-1): BOARD_READ on it, as agentBoard,
		// not an organization function -- a person refused the board is refused its spend, and one
		// working it through a board grant reads it.
		if (null == authorizationService.userOnBoard(oud.orElse(null), board.getOrg(), board.getUuid(),
				List.of(PermissionFunction.BOARD_READ), CallType.READ)) {
			throw new AccessDeniedException("Not authorized: this needs BOARD_READ on board " + board.getName());
		}
		// DateTime arguments bind as ZonedDateTime, as every other date argument does: bound as
		// String, DGS refused the scalar's OffsetDateTime and every call failed (40f270be T-1).
		return agentSessionUsageService.boardUsage(boardUuid, from, to);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "organizationAgentUsage")
	public AgentSessionData.UsageTotals organizationAgentUsage(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("from") ZonedDateTime from, @InputArgument("to") ZonedDateTime to) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.AGENT,
				PermissionScope.ORGANIZATION, orgUuid, List.of(od), CallType.READ);
		return agentSessionUsageService.orgUsage(orgUuid, from, to);
	}

	@DgsData(parentType = "ModelOntology", field = "usage")
	public ModelOntologyService.ModelUsageCount modelUsage(DgsDataFetchingEnvironment dfe) {
		ModelOntologyData mod = dfe.getSource();
		Integer days = dfe.getArgument("days");
		return modelOntologyService.usage(null == mod ? null : mod.getUuid(), null == days ? 30 : days);
	}

	@DgsData(parentType = "ModelOntology", field = "suggestedCanonicalId")
	public String modelSuggestedCanonicalId(DgsDataFetchingEnvironment dfe) {
		ModelOntologyData mod = dfe.getSource();
		return null == mod ? null : modelOntologyService.suggestedCanonicalId(mod).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "modelCatalogueBundle")
	public List<Map<String, Object>> modelCatalogueBundle() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (var bm : modelOntologyService.bundledModels()) {
			Map<String, Object> e = new java.util.LinkedHashMap<>();
			e.put("canonicalId", bm.canonicalId());
			e.put("name", bm.name());
			e.put("version", bm.version());
			e.put("publisher", bm.publisher());
			out.add(e);
		}
		return out;
	}

	@DgsData(parentType = "ModelOntology", field = "mergeCandidates")
	public List<ModelOntologyData> modelMergeCandidates(DgsDataFetchingEnvironment dfe) {
		ModelOntologyData mod = dfe.getSource();
		return null == mod ? List.of() : modelOntologyService.mergeCandidates(mod, 3);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addModelPricing")
	public ModelOntologyData addModelPricing(@InputArgument("modelOntologyUuid") UUID ontologyUuid,
			@InputArgument("entry") Map<String, Object> entry) throws RelizaException {
		WhoUpdated wu = authorizeCatalogueAdmin(ontologyUuid);
		return modelOntologyService.addModelPricing(ontologyUuid, toPricingEntry(entry), wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "expireModelPricing")
	public ModelOntologyData expireModelPricing(@InputArgument("modelOntologyUuid") UUID ontologyUuid,
			@InputArgument("entryUuid") UUID entryUuid,
			@InputArgument("effectiveTo") ZonedDateTime effectiveTo) throws RelizaException {
		WhoUpdated wu = authorizeCatalogueAdmin(ontologyUuid);
		return modelOntologyService.expireModelPricing(ontologyUuid, entryUuid, effectiveTo, wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "applyModelCataloguePreset")
	public ModelOntologyData applyModelCataloguePreset(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("canonicalId") String canonicalId) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(od), CallType.ADMIN);
		return modelOntologyService.applyModelCataloguePreset(orgUuid, canonicalId,
				WhoUpdated.getWhoUpdated(oud.get()));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "mergeModelOntology")
	public ModelOntologyData mergeModelOntology(@InputArgument("from") UUID from,
			@InputArgument("into") UUID into) throws RelizaException {
		WhoUpdated wu = authorizeCatalogueAdmin(from);
		return modelOntologyService.mergeModelOntology(from, into, wu);
	}

	/** Pricing and catalogue shape are org configuration, so ADMIN rather than WRITE. */
	private WhoUpdated authorizeCatalogueAdmin(UUID ontologyUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		ModelOntologyData existing = modelOntologyService.getModelOntologyData(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, existing.getOrg(), List.of(existing), CallType.ADMIN);
		return WhoUpdated.getWhoUpdated(oud.get());
	}

	@SuppressWarnings("unchecked")
	private PricingEntry toPricingEntry(Map<String, Object> e) throws RelizaException {
		if (null == e) throw new RelizaException("A pricing entry is required");
		Map<String, Object> sel = (Map<String, Object>) e.get("appliesTo");
		PricingEntry.PricingSelector selector = null == sel ? null : new PricingEntry.PricingSelector(
				nullableLong(sel.get("contextAboveTokens")),
				(String) sel.get("contextVariant"),
				enumArg(ServiceTier.class, sel.get("serviceTier")),
				enumArg(SessionUsageHosting.class, sel.get("hosting")),
				enumArg(ReasoningMatch.class, sel.get("reasoning")));
		return new PricingEntry(null, timeArg(e.get("effectiveFrom")), timeArg(e.get("effectiveTo")),
				(String) e.get("currency"), enumArg(PricingUnit.class, e.get("unit")),
				nullableLong(e.get("inputMicros")), nullableLong(e.get("outputMicros")),
				nullableLong(e.get("cacheReadMicros")), nullableLong(e.get("cacheWriteMicros")),
				nullableLong(e.get("reasoningMicros")), selector,
				(String) e.get("source"), (String) e.get("note"), null, null);
	}

	@DgsData(parentType = "Mutation", field = "setModelOntologyModelCardProgrammatic")
	public ModelOntologyData setModelOntologyModelCardProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		ProgKeyContext ctx = authorizeProgrammaticOrgWrite(dfe);
		Map<String, Object> input = dfe.getArgument("input");
		UUID ontologyUuid = UUID.fromString((String) input.get("modelOntologyUuid"));
		@SuppressWarnings("unchecked")
		Map<String, Object> modelCard = (Map<String, Object>) input.get("modelCard");

		ModelOntologyData existing = modelOntologyService.getModelOntologyData(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		if (!ctx.orgUuid.equals(existing.getOrg())) {
			throw new AccessDeniedException("ModelOntology does not belong to caller's org");
		}
		return modelOntologyService.setModelCard(ontologyUuid, modelCard, ctx.wu);
	}

	// ---------- Auth helpers ----------

	/** Parsed programmatic auth context — org, calling key uuid, audit stamp. */
	/**
	 * @param ahp the verified principal, for recording how a session was opened
	 * @param clientIp the client address as this server saw it
	 */
	private record ProgKeyContext(UUID orgUuid, UUID apiKeyUuid, WhoUpdated wu, AuthHeaderParse ahp,
			String clientIp) {}

	private ProgKeyContext authorizeProgrammaticOrgWrite(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID orgUuid = authCtx.orgUuid();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		if (!ahp.isRbacKey()) {
			throw new AccessDeniedException("Only FREEFORM API keys are supported for agent operations in v1");
		}
		if (orgUuid == null) throw new AccessDeniedException("Could not resolve org for key");

		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found: " + orgUuid));
		FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
				ahp, PermissionFunction.AGENT, PermissionScope.ORGANIZATION, orgUuid,
				List.of(od), CallType.ESSENTIAL_READ);
		return new ProgKeyContext(orgUuid, fkv.apiKeyUuid(), fkv.whoUpdated(), ahp,
				DeviceAuthorizationController.clientIp(servletWebRequest.getRequest()));
	}

	@DgsData(parentType = "Query", field = "sessionProgrammatic")
	public AgentSessionData sessionProgrammatic(@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		authorizeProgrammaticSessionRead(sessionUuid, dfe);
		return agentSessionService.getSessionData(sessionUuid).orElse(null);
	}

	@DgsData(parentType = "Query", field = "agentSessionInboxProgrammatic")
	public List<Map<String, Object>> agentSessionInboxProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> inboxRequest = dfe.getArgument("inboxRequest");
		if (inboxRequest == null) throw new RelizaException("agentSessionInboxProgrammatic requires inboxRequest");
		UUID sessionUuid = UUID.fromString((String) inboxRequest.get("sessionUuid"));
		String since = (String) inboxRequest.get("since");
		@SuppressWarnings("unchecked")
		List<String> kindsRaw = (List<String>) inboxRequest.get("kinds");
		Integer limit = (Integer) inboxRequest.get("limit");
		int effectiveLimit = Math.min(limit == null || limit <= 0 ? 50 : limit, 200);
		java.util.Set<String> kindFilter = (kindsRaw == null || kindsRaw.isEmpty())
				? null
				: new java.util.HashSet<>(kindsRaw);
		authorizeProgrammaticSessionRead(sessionUuid, dfe);

		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));

		java.time.ZonedDateTime sinceTs = null;
		if (StringUtils.isNotBlank(since)) {
			try {
				sinceTs = java.time.ZonedDateTime.parse(since);
			} catch (java.time.format.DateTimeParseException dtpe) {
				throw new RelizaException("`since` is not a valid ISO-8601 timestamp: " + since);
			}
		}

		List<Map<String, Object>> events = new java.util.ArrayList<>();

		// 1. Release-side events (LIFECYCLE_CHANGE, APPROVAL) for every
		//    release this session's commits touched. Same batched
		//    findReleaseDatasBySces lookup as the Session.releases
		//    resolver so we stay consistent with what the dashboard shows.
		List<io.reliza.model.ReleaseData> sessionReleases = (sd.getCommits() != null)
				? sharedReleaseService.findReleaseDatasBySces(sd.getCommits(), sd.getOrg())
				: List.of();
		for (var rd : sessionReleases) {
			if (rd.getUpdateEvents() != null) {
				for (var ue : rd.getUpdateEvents()) {
					if (ue.rus() != io.reliza.model.ReleaseData.ReleaseUpdateScope.LIFECYCLE) continue;
					Map<String, Object> ev = new java.util.HashMap<>();
					ev.put("cursor", isoOf(ue.date()));
					ev.put("occurredAt", ue.date());
					ev.put("kind", "LIFECYCLE_CHANGE");
					ev.put("release", rd);
					ev.put("oldValue", ue.oldValue());
					ev.put("newValue", ue.newValue());
					ev.put("reason", ue.message());
					ev.put("source", inferLifecycleSource(ue));
					ev.put("actorUuid", ue.wu() != null && ue.wu().getLastUpdatedBy() != null
							? ue.wu().getLastUpdatedBy().toString()
							: null);
					ev.put("actorRoleId", null);
					events.add(ev);
				}
			}
			if (rd.getApprovalEvents() != null) {
				for (var ae : rd.getApprovalEvents()) {
					Map<String, Object> ev = new java.util.HashMap<>();
					ev.put("cursor", isoOf(ae.date()));
					ev.put("occurredAt", ae.date());
					ev.put("kind", "APPROVAL");
					ev.put("release", rd);
					ev.put("oldValue", null);
					ev.put("newValue", ae.state() != null ? ae.state().name() : null);
					ev.put("reason", ae.comment());
					ev.put("source", "HUMAN");
					ev.put("actorUuid", ae.wu() != null && ae.wu().getLastUpdatedBy() != null
							? ae.wu().getLastUpdatedBy().toString()
							: null);
					ev.put("actorRoleId", ae.approvalRoleId());
					events.add(ev);
				}
			}
		}

		// 2. Session-side events (POLICY_VERDICT). The init-time verdict
		//    is included so an agent that polls right after init can see
		//    a BLOCKED status's failing policy without a second query.
		if (sd.getPolicyEvents() != null) {
			for (var pe : sd.getPolicyEvents()) {
				if (pe.state() == io.reliza.service.AgentPolicyHook.PolicyState.PASSED) continue;
				Map<String, Object> ev = new java.util.HashMap<>();
				ev.put("cursor", isoOf(pe.evaluatedAt()));
				ev.put("occurredAt", pe.evaluatedAt());
				ev.put("kind", "POLICY_VERDICT");
				ev.put("release", null);
				ev.put("oldValue", null);
				ev.put("newValue", pe.state() != null ? pe.state().name() : null);
				ev.put("reason", pe.message());
				ev.put("source", "POLICY_GATE");
				ev.put("actorUuid", null);
				ev.put("actorRoleId", null);
				events.add(ev);
			}
		}

		// Filter, sort, limit.
		final java.time.ZonedDateTime sinceFinal = sinceTs;
		events.removeIf((ev) -> {
			java.time.ZonedDateTime when = (java.time.ZonedDateTime) ev.get("occurredAt");
			if (when == null) return true;
			if (sinceFinal != null && !when.isAfter(sinceFinal)) return true;
			if (kindFilter != null && !kindFilter.contains((String) ev.get("kind"))) return true;
			return false;
		});
		events.sort((a, b) -> {
			java.time.ZonedDateTime ta = (java.time.ZonedDateTime) a.get("occurredAt");
			java.time.ZonedDateTime tb = (java.time.ZonedDateTime) b.get("occurredAt");
			return ta.compareTo(tb);
		});
		if (events.size() > effectiveLimit) {
			events = events.subList(0, effectiveLimit);
		}
		return events;
	}

	private String isoOf(java.time.ZonedDateTime t) {
		return t == null ? null : t.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	/**
	 * Map a release update event to an inbox {@code source} enum. v1
	 * heuristic: events with {@code wu.createdType = AUTO} (the trigger
	 * firing path) → POLICY_GATE; events with a non-null
	 * {@code wu.lastUpdatedBy} → HUMAN; anything else → RELEASE_AUTO.
	 */
	private String inferLifecycleSource(io.reliza.model.ReleaseData.ReleaseUpdateEvent ue) {
		if (ue.wu() == null) return "RELEASE_AUTO";
		if (ue.wu().getLastUpdatedBy() != null) return "HUMAN";
		if (ue.wu().getCreatedType() != null
				&& "AUTO".equals(ue.wu().getCreatedType().name())) return "POLICY_GATE";
		return "RELEASE_AUTO";
	}

	/**
	 * A read of a session: the org check every session call makes, then the visibility rule (task
	 * 0192a587) -- the session's own key, the org's ADMIN keys, and the key holding the seat of a
	 * board the session worked on. Anyone else gets the answer an unknown uuid gets.
	 */
	private void authorizeProgrammaticSessionRead(UUID sessionUuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		authorizeVisibleSession(sessionUuid, dfe);
	}

	/**
	 * A write on a session -- touch, update-meta, add-artifact, close, usage -- under the same rule
	 * as the read. Each of them answers with the session, so a write open to every key in the org
	 * would hand any of them the read the rule refuses, and let it rename or close another agent's
	 * session besides (task 0192a587, round 2, T-1).
	 */
	private WhoUpdated authorizeProgrammaticAgentAccessOnSession(UUID sessionUuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		return authorizeVisibleSession(sessionUuid, dfe).whoUpdated();
	}

	private FreeformKeyVerification authorizeVisibleSession(UUID sessionUuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		if (!ahp.isRbacKey()) {
			throw new AccessDeniedException("Only FREEFORM API keys are supported for agent operations in v1");
		}
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		OrganizationData od = getOrganizationService.getOrganizationData(sd.getOrg())
				.orElseThrow(() -> new RelizaException("Org not found"));
		FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
				ahp, PermissionFunction.AGENT, PermissionScope.ORGANIZATION, sd.getOrg(),
				List.of(od), CallType.ESSENTIAL_READ);
		boolean admin;
		try {
			authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.AGENT,
					PermissionScope.ORGANIZATION, sd.getOrg(), List.of(od), CallType.ADMIN);
			admin = true;
		} catch (AccessDeniedException notAdmin) {
			admin = false;
		}
		// D15: BOARD_READ on a board the session worked is the third reader, in place of org ADMIN by name.
		java.util.function.Predicate<UUID> readsBoard = board -> {
			try {
				return authorizationService.boardPermission(ahp, sd.getOrg(), board, PermissionFunction.BOARD_READ,
						CallType.ESSENTIAL_READ);
			} catch (RelizaException e) {
				return false;
			}
		};
		if (!agentSessionVisibilityService.mayRead(sd, fkv.apiKeyUuid(), admin, readsBoard)) {
			throw new RelizaException("Session not found: " + sessionUuid);
		}
		return fkv;
	}
}
