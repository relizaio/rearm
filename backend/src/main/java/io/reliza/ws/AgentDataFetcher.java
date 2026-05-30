/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.Map;
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

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentData.AgentStatus;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey.ApiTypeEnum;
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
import io.reliza.service.AgentSessionService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.ModelOntologyService;
import io.reliza.service.UserService;

@DgsComponent
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
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
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
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
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
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
		return agentService.listByRoot(rootAgentUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "session")
	public AgentSessionData getSession(@InputArgument("uuid") UUID uuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<AgentSessionData> osd = agentSessionService.getSessionData(uuid);
		RelizaObject ro = osd.isPresent() ? osd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
		return osd.orElse(null);
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
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
		return agentSessionService.listByOrg(orgUuid, statuses);
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
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
		return agentSessionService.listByAgent(rootAgentUuid, statuses);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "modelOntology")
	public ModelOntologyData getModelOntology(@InputArgument("uuid") UUID uuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ModelOntologyData> omd = modelOntologyService.getModelOntologyData(uuid);
		RelizaObject ro = omd.isPresent() ? omd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
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
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
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
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
		return agentMonitoringService.computeDashboardKpis(orgUuid);
	}

	@DgsData(parentType = "Agent", field = "openSessions")
	public List<AgentSessionData> agentOpenSessions(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null) return List.of();
		return agentSessionService.listByAgent(ad.getUuid(), List.of("OPEN"));
	}

	@DgsData(parentType = "Agent", field = "closedSessions")
	public List<AgentSessionData> agentClosedSessions(DgsDataFetchingEnvironment dfe) {
		AgentData ad = dfe.getSource();
		if (ad == null) return List.of();
		return agentSessionService.listByAgent(ad.getUuid(), List.of("CLOSED"));
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
	 * commits. Walks session.commits → Release.sourceCodeEntry (and
	 * Release.commits[] for multi-commit releases) via the existing
	 * findReleasesBySce lookup. Empty list when no commits or no
	 * releases minted yet.
	 */
	@DgsData(parentType = "Session", field = "releases")
	public List<io.reliza.model.ReleaseData> sessionReleases(DgsDataFetchingEnvironment dfe) {
		AgentSessionData sd = dfe.getSource();
		if (sd == null || sd.getCommits() == null || sd.getCommits().isEmpty()) return List.of();
		java.util.LinkedHashMap<UUID, io.reliza.model.ReleaseData> byUuid = new java.util.LinkedHashMap<>();
		for (UUID sceUuid : sd.getCommits()) {
			for (var rd : sharedReleaseService.findReleaseDatasBySce(sceUuid, sd.getOrg())) {
				byUuid.putIfAbsent(rd.getUuid(), rd);
			}
		}
		return new java.util.ArrayList<>(byUuid.values());
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
		return modelOntologyService.updateModelOntology(
				ontologyUuid,
				(String) input.get("publisher"),
				(String) input.get("description"),
				(String) input.get("purl"),
				(String) input.get("notes"),
				wu);
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

		// Resolve / auto-register the model ontology first; the agent
		// references it. Empty modelCard until the user attaches one.
		ModelOntologyData ontology = modelOntologyService.findOrRegisterModel(
				ctx.orgUuid,
				agentModel,
				(String) input.get("agentModelVersion"),
				(String) input.get("agentVendor"),
				ctx.wu);

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
				ctx.wu);
	}

	@DgsData(parentType = "Mutation", field = "sessionTouchProgrammatic")
	public AgentSessionData sessionTouchProgrammatic(@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		WhoUpdated wu = authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);
		return agentSessionService.touch(sessionUuid, wu);
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
				wu);
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
	private record ProgKeyContext(UUID orgUuid, UUID apiKeyUuid, WhoUpdated wu) {}

	private ProgKeyContext authorizeProgrammaticOrgWrite(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID orgUuid = authCtx.orgUuid();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		if (ahp.getType() != ApiTypeEnum.FREEFORM) {
			throw new AccessDeniedException("Only FREEFORM API keys are supported for agent operations in v1");
		}
		if (orgUuid == null) throw new AccessDeniedException("Could not resolve org for key");

		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found: " + orgUuid));
		FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
				ahp, PermissionFunction.AGENT, PermissionScope.ORGANIZATION, orgUuid,
				List.of(od), CallType.ESSENTIAL_READ);
		return new ProgKeyContext(orgUuid, fkv.apiKeyUuid(), fkv.whoUpdated());
	}

	@DgsData(parentType = "Query", field = "sessionProgrammatic")
	public AgentSessionData sessionProgrammatic(@InputArgument("sessionUuid") UUID sessionUuid,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		// Reuses the session-scoped write-auth helper which already
		// verifies the calling FREEFORM key is authorized on the
		// session's org. Read-only consumers fall through the same
		// org/permissions check.
		authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);
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
		// Read-only auth path — uses ESSENTIAL_READ on the FREEFORM key
		// scoped to the session's org. Same helper the write paths use.
		authorizeProgrammaticAgentAccessOnSession(sessionUuid, dfe);

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
		//    release this session's commits touched. Walks the existing
		//    findReleasesBySce path used by the Session.releases resolver
		//    so we stay consistent with what the dashboard shows.
		java.util.LinkedHashMap<UUID, io.reliza.model.ReleaseData> releasesByUuid = new java.util.LinkedHashMap<>();
		if (sd.getCommits() != null) {
			for (UUID sceUuid : sd.getCommits()) {
				for (var rd : sharedReleaseService.findReleaseDatasBySce(sceUuid, sd.getOrg())) {
					releasesByUuid.putIfAbsent(rd.getUuid(), rd);
				}
			}
		}
		for (var rd : releasesByUuid.values()) {
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

	private WhoUpdated authorizeProgrammaticAgentAccessOnSession(UUID sessionUuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		if (ahp.getType() != ApiTypeEnum.FREEFORM) {
			throw new AccessDeniedException("Only FREEFORM API keys are supported for agent operations in v1");
		}
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		OrganizationData od = getOrganizationService.getOrganizationData(sd.getOrg())
				.orElseThrow(() -> new RelizaException("Org not found"));
		FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
				ahp, PermissionFunction.AGENT, PermissionScope.ORGANIZATION, sd.getOrg(),
				List.of(od), CallType.ESSENTIAL_READ);
		return fkv.whoUpdated();
	}
}
