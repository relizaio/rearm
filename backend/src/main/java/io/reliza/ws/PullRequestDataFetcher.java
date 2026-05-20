/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.net.URI;
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
import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.PullRequestData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.AgentService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.PullRequestService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;

@DgsComponent
public class PullRequestDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private VcsRepositoryService vcsRepositoryService;

	@Autowired
	private PullRequestService pullRequestService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;

	@Autowired
	private io.reliza.service.SharedReleaseService sharedReleaseService;

	@Autowired
	private io.reliza.service.oss.OssPullRequestAggregatorService pullRequestAggregatorService;

	@Autowired
	private AgentService agentService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "pullRequest")
	public PullRequestData getPullRequest(@InputArgument("prUuid") UUID prUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<PullRequestData> oprd = pullRequestService.getPullRequestData(prUuid);
		RelizaObject ro = oprd.isPresent() ? oprd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.ESSENTIAL_READ);
		return oprd.orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "pullRequestsOfOrg")
	public List<PullRequestData> pullRequestsOfOrg(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("states") List<String> states) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
		return pullRequestService.listByOrg(orgUuid, states);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "pullRequestsOfVcs")
	public List<PullRequestData> pullRequestsOfVcs(@InputArgument("vcs") UUID vcsUuid,
			@InputArgument("states") List<String> states) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<VcsRepositoryData> ovrd = vcsRepositoryService.getVcsRepositoryData(vcsUuid);
		RelizaObject ro = ovrd.isPresent() ? ovrd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
		return pullRequestService.listByTargetRepository(vcsUuid, states);
	}

	/**
	 * Programmatic upsert: registers (or refreshes) a PullRequest entity
	 * keyed by (targetVcsRepository, identity). Designed for CI to call
	 * unconditionally on every pull_request event so the PR row exists
	 * regardless of whether a release is also being created on the same
	 * commit. See the schema docstring on
	 * {@code Mutation.upsertPullRequestProgrammatic} for the full
	 * contract.
	 */
	@DgsData(parentType = "Mutation", field = "upsertPullRequestProgrammatic")
	public PullRequestData upsertPullRequestProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID authOrgUuid = authCtx.orgUuid();
		if (null == ahp) throw new AccessDeniedException("Invalid authorization type");

		Map<String, Object> input = dfe.getArgument("input");
		String identity = (String) input.get("identity");
		String stateStr = (String) input.get("state");
		if (StringUtils.isBlank(identity) || StringUtils.isBlank(stateStr)) {
			throw new RelizaException("identity and state are required");
		}
		// Validate state up front so a bad value fails the mutation
		// instead of silently no-opping inside applyFromInput.
		PullRequestState.valueOf(StringUtils.upperCase(stateStr));

		// Resolve the target VCS. Component is resolved via the same
		// helper addReleaseProgrammatic uses, which auto-fills it from
		// the API key for COMPONENT-typed keys (so the caller doesn't
		// need to repeat what the key already encodes). vcsUri is the
		// fallback for ORG/FREEFORM keys that don't supply --component.
		String vcsUri = (String) input.get("vcsUri");
		String repoPath = (String) input.get("repoPath");
		String vcsDisplayName = (String) input.get("vcsDisplayName");

		UUID componentId = io.reliza.common.Utils.resolveProgrammaticComponentId(
				(String) input.get("component"), ahp);

		UUID targetVcs = null;
		UUID resolvedOrg = null;
		ComponentData resolvedComponent = null;
		if (componentId != null) {
			final UUID compUuid = componentId;
			resolvedComponent = getComponentService.getComponentData(compUuid)
					.orElseThrow(() -> new RelizaException("Component not found: " + compUuid));
			targetVcs = resolvedComponent.getVcs();
			resolvedOrg = resolvedComponent.getOrg();
			if (targetVcs == null) {
				throw new RelizaException("Component " + compUuid + " has no VCS configured");
			}
		} else if (StringUtils.isNotBlank(vcsUri)) {
			if (authOrgUuid == null) throw new AccessDeniedException("Org-scoped key required to resolve VCS by URI");
			resolvedOrg = authOrgUuid;
			// Try resolve the VCS without creating it. If it exists and
			// has at least one component the key authorizes against, hoist
			// that component into resolvedComponent so the post-auth at
			// the caller does per-component auth — that lets COMPONENT- or
			// PERSPECTIVE-scope FREEFORM keys (which can't authorize the
			// whole org) drive PR upserts for VCS attached to components
			// they cover. Falls back to org-wide auth + auto-create when
			// no VCS exists yet, since auto-creating a VCS row is itself
			// an org-wide write that no narrower scope can authorize.
			Optional<VcsRepositoryData> ovd = vcsRepositoryService
					.getVcsRepositoryDataByUri(authOrgUuid, vcsUri);
			if (ovd.isPresent()) {
				targetVcs = ovd.get().getUuid();
				List<ComponentData> compsOnVcs = StringUtils.isNotBlank(repoPath)
						? getComponentService.listComponentDataByVcsAndPath(targetVcs, authOrgUuid, repoPath)
						: getComponentService.listComponentDataByVcs(targetVcs);
				for (ComponentData cd : compsOnVcs) {
					try {
						authorizeProgrammatic(ahp, cd, cd.getOrg());
						resolvedComponent = cd;
						break;
					} catch (AccessDeniedException ignored) { /* try the next candidate */ }
				}
			}
			if (resolvedComponent == null) {
				// Either no VCS yet, no components on the VCS, or none the
				// key was authorized for — fall back to org-wide auth (the
				// only scope that can authorize a fresh VCS create).
				OrganizationData od = getOrganizationService.getOrganizationData(authOrgUuid)
						.orElseThrow(() -> new RelizaException("Org not found"));
				AuthorizationResponse arPre = authorizeProgrammatic(ahp, od, authOrgUuid);
				Optional<VcsRepository> ovr = vcsRepositoryService.getVcsRepositoryByUri(
						authOrgUuid, vcsUri, vcsDisplayName, VcsType.GIT, true, arPre.getWhoUpdated());
				VcsRepository vr = ovr.orElseThrow(() -> new RelizaException("Failed to resolve VCS for uri " + vcsUri));
				targetVcs = vr.getUuid();
			}
		} else {
			throw new RelizaException("Either --component (or a COMPONENT key) or --vcsuri is required");
		}

		// Authorise (per-component when supplied, else per-org) and capture WhoUpdated.
		AuthorizationResponse ar;
		if (resolvedComponent != null) {
			ar = authorizeProgrammatic(ahp, resolvedComponent, resolvedComponent.getOrg());
		} else {
			OrganizationData od = getOrganizationService.getOrganizationData(resolvedOrg)
					.orElseThrow(() -> new RelizaException("Org not found"));
			ar = authorizeProgrammatic(ahp, od, resolvedOrg);
		}
		WhoUpdated wu = ar.getWhoUpdated();

		// Resolve optional head SCE by commit. Missing SCE is not an error
		// — the PR row gets registered without a head and the subsequent
		// addReleaseProgrammatic call advances it once the SCE is created.
		UUID headSce = null;
		String commit = (String) input.get("commit");
		if (StringUtils.isNotBlank(commit)) {
			List<SourceCodeEntry> existing = getSourceCodeEntryService
					.getSourceCodeEntriesByVcsAndCommits(targetVcs, List.of(commit));
			if (!existing.isEmpty()) {
				headSce = existing.get(0).getUuid();
			}
		}

		// Reuse the shared helper — same code path the
		// addReleaseProgrammatic flow uses, so behaviour stays
		// consistent. The helper resolves targetVcs from
		// headSce.vcs first; we pass our pre-resolved targetVcs as the
		// explicit fallback so it always wins on the
		// no-SCE-yet path. Optional.empty() only happens on
		// validation failure inside the helper, which we've already
		// guarded against above — defensive throw to surface any
		// future regression.
		Optional<PullRequestData> result = pullRequestService.applyFromInput(
				input, resolvedOrg, headSce, targetVcs, wu);
		if (result.isEmpty()) {
			throw new RelizaException("PullRequest upsert returned no result");
		}
		// Standalone PR upsert path (no release save involved). Run the
		// aggregator so the new head's verdict is computed and dispatched
		// against any releases already attributed to commits[].
		pullRequestAggregatorService.recomputeForPr(result.get().getUuid(), wu);
		return result.get();
	}

	/**
	 * Authorise an API key against a target object's owning org.
	 *
	 * Allowed key types: COMPONENT (only against a ComponentData target),
	 * FREEFORM (with appropriate scope), or ORGANIZATION_RW. Other
	 * ORGANIZATION_* types (read-only) are rejected up-front.
	 */
	private AuthorizationResponse authorizeProgrammatic(AuthHeaderParse ahp, RelizaObject ro, UUID orgUuid)
			throws RelizaException {
		if (ahp.getType() == ApiTypeEnum.FREEFORM) {
			FreeformKeyVerification fkv;
			if (ro instanceof ComponentData) {
				fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp,
						PermissionFunction.RESOURCE, PermissionScope.COMPONENT, ro.getUuid(),
						List.of(ro), CallType.WRITE);
			} else {
				fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp,
						PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid,
						List.of(ro), CallType.WRITE);
			}
			AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.ALLOW);
			ar.setWhoUpdated(fkv.whoUpdated());
			return ar;
		}
		if (ahp.getType() == ApiTypeEnum.COMPONENT) {
			// COMPONENT key always implicitly identifies its component;
			// the VCS-URI path requires an org/FREEFORM key.
			if (!(ro instanceof ComponentData)) {
				throw new AccessDeniedException(
						"COMPONENT API key cannot be used with the vcsUri path; use FREEFORM or ORGANIZATION_RW");
			}
			return authorizationService.isApiKeyAuthorized(ahp, List.of(ApiTypeEnum.COMPONENT),
					orgUuid, CallType.WRITE, ro);
		}
		if (ahp.getType() == ApiTypeEnum.ORGANIZATION_RW) {
			return authorizationService.isApiKeyAuthorized(ahp, List.of(ApiTypeEnum.ORGANIZATION_RW),
					orgUuid, CallType.WRITE, ro);
		}
		throw new AccessDeniedException(
				"upsertPullRequestProgrammatic accepts COMPONENT, FREEFORM, or ORGANIZATION_RW API keys; got " + ahp.getType());
	}

	/**
	 * PullRequest.attributedReleases resolver — defers to the aggregator's
	 * public attribution helper so the UI sees the same set of releases the
	 * verdict computation considers. Read-only side effect free; auth was
	 * already enforced when the parent PullRequest was queried.
	 */
	@DgsData(parentType = "PullRequest", field = "attributedReleases")
	public List<io.reliza.model.ReleaseData> attributedReleases(
			com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		PullRequestData prd = dfe.getSource();
		if (prd == null) return List.of();
		return pullRequestAggregatorService.attributedReleasesForPr(prd);
	}

	/**
	 * PullRequest.commitDetails resolver — hydrates the commit UUID list
	 * into SourceCodeEntryData rows so the UI can show actual git
	 * commit SHAs instead of opaque UUIDs. Order preserved (head last);
	 * missing SCEs filtered out.
	 */
	@DgsData(parentType = "PullRequest", field = "commitDetails")
	public List<io.reliza.model.SourceCodeEntryData> commitDetails(
			com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		PullRequestData prd = dfe.getSource();
		if (prd == null || prd.getCommits() == null) return List.of();
		return prd.getCommits().stream()
				.map(getSourceCodeEntryService::getSourceCodeEntryData)
				.filter(java.util.Optional::isPresent)
				.map(java.util.Optional::get)
				.collect(java.util.stream.Collectors.toList());
	}

	/**
	 * PullRequest.agents resolver — distinct ROOT agents that contributed
	 * commits to this PR. Walks commits → SCE.agent → resolveRoot so
	 * leaf SUB-agent attribution rolls up to its owning root. One PR
	 * can be attributed to multiple agents when different commits came
	 * from different sessions or different identities. Order is by
	 * first appearance in commits. Missing / orphan refs silently
	 * dropped.
	 */
	@DgsData(parentType = "PullRequest", field = "agents")
	public List<io.reliza.model.AgentData> agents(
			com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		PullRequestData prd = dfe.getSource();
		if (prd == null || prd.getCommits() == null) return List.of();
		java.util.LinkedHashSet<UUID> rootUuids = new java.util.LinkedHashSet<>();
		for (UUID sceUuid : prd.getCommits()) {
			Optional<io.reliza.model.SourceCodeEntryData> osced =
					getSourceCodeEntryService.getSourceCodeEntryData(sceUuid);
			if (osced.isEmpty()) continue;
			UUID leafAgentUuid = osced.get().getAgent();
			if (leafAgentUuid == null) continue;
			Optional<io.reliza.model.AgentData> oad = agentService.getAgentData(leafAgentUuid);
			if (oad.isEmpty()) continue;
			try {
				io.reliza.model.AgentData root = agentService.resolveRoot(oad.get());
				rootUuids.add(root.getUuid());
			} catch (RelizaException e) {
				// Orphan rootAgent pointer — skip rather than 500 the
				// whole PR view.
			}
		}
		List<io.reliza.model.AgentData> out = new java.util.ArrayList<>();
		for (UUID u : rootUuids) {
			agentService.getAgentData(u).ifPresent(out::add);
		}
		return out;
	}

	/**
	 * PullRequest.validatedReleaseDetails resolver — returns ReleaseData
	 * for every distinct release UUID referenced in releaseValidationEvents.
	 * Superset of attributedReleases: the latter is only the
	 * newest-per-component snapshot the verdict computation considers,
	 * but the UI needs to resolve historical events whose release has
	 * since been superseded.
	 */
	@DgsData(parentType = "PullRequest", field = "validatedReleaseDetails")
	public List<io.reliza.model.ReleaseData> validatedReleaseDetails(
			com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		PullRequestData prd = dfe.getSource();
		if (prd == null || prd.getReleaseValidationEvents() == null) return List.of();
		return prd.getReleaseValidationEvents().stream()
				.map(io.reliza.model.PullRequestData.ReleaseValidationEvent::release)
				.filter(java.util.Objects::nonNull)
				.distinct()
				.map(sharedReleaseService::getRelease)
				.filter(java.util.Optional::isPresent)
				.map(java.util.Optional::get)
				.map(io.reliza.model.ReleaseData::dataFromRecord)
				.collect(java.util.stream.Collectors.toList());
	}

	/**
	 * PullRequest.currentValidationState resolver — picks the latest
	 * pr_validation_event whose sourceCodeEntry matches the PR's current
	 * head SCE. Mirrors the aggregator's "latest-for-head" lookup so the
	 * UI sees the same value the SCM dispatch sees. Null when the PR has
	 * no commits / no events / no event for the current head.
	 */
	@DgsData(parentType = "PullRequest", field = "currentValidationState")
	public io.reliza.model.ValidationState currentValidationState(
			com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		PullRequestData prd = dfe.getSource();
		return pullRequestService.getCurrentValidationState(prd);
	}
}
