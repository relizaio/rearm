/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ProposalStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SafetyContextData;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.SafetyContextRecordData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SafetyContextEntryWebDto;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SafetyContextService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

/**
 * GraphQL for SAFETY_CONTEXT imports: the review inbox, accept and reject, and the accepted
 * records of a release or component. Gated like VEX proposals (FINDING_ANALYSIS_READ / WRITE).
 * Every by-uuid call answers a missing row and a forbidden one with the same message.
 */
@DgsComponent
public class SafetyContextDataFetcher {

	@Autowired private SafetyContextService safetyContextService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private UserService userService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ArtifactService artifactService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSafetyContextProposals")
	public List<SafetyContextEntryWebDto> getProposals(@InputArgument("org") UUID org,
			@InputArgument("status") ProposalStatus status, @InputArgument("limit") Integer limit,
			@InputArgument("offset") Integer offset) throws RelizaException {
		gateOrg(currentUser(), org, PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		return proposals(safetyContextService.listProposals(org, status, intOf(limit), intOf(offset)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSafetyContextProposalsByRelease")
	public List<SafetyContextEntryWebDto> getProposalsByRelease(@InputArgument("org") UUID org,
			@InputArgument("release") UUID release, @InputArgument("limit") Integer limit,
			@InputArgument("offset") Integer offset) throws RelizaException {
		gateRelease(currentUser(), org, release, PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		return proposals(safetyContextService.listProposalsForRelease(org, release, intOf(limit), intOf(offset)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSafetyContextProposalsBySourceArtifact")
	public List<SafetyContextEntryWebDto> getProposalsBySourceArtifact(@InputArgument("org") UUID org,
			@InputArgument("artifact") UUID artifact, @InputArgument("limit") Integer limit,
			@InputArgument("offset") Integer offset) throws RelizaException {
		UserData oud = currentUser();
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifact);
		if (oad.isPresent() && org.equals(oad.get().getOrg())) {
			authorizationService.isUserAuthorizedForObjectGraphQL(oud, PermissionFunction.FINDING_ANALYSIS_READ,
				PermissionScope.ORGANIZATION, org, List.of((RelizaObject) oad.get()), CallType.READ);
		} else {
			gateOrg(oud, org, PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		}
		return proposals(safetyContextService.listProposalsForArtifact(org, artifact, intOf(limit), intOf(offset)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSafetyContextRecordsByRelease")
	public List<SafetyContextEntryWebDto> getRecordsByRelease(@InputArgument("org") UUID org,
			@InputArgument("release") UUID release, @InputArgument("includeSuperseded") Boolean includeSuperseded,
			@InputArgument("limit") Integer limit, @InputArgument("offset") Integer offset) throws RelizaException {
		gateRelease(currentUser(), org, release, PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		return records(safetyContextService.listRecordsForRelease(org, release, Boolean.TRUE.equals(includeSuperseded),
			intOf(limit), intOf(offset)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSafetyContextRecordsByComponent")
	public List<SafetyContextEntryWebDto> getRecordsByComponent(@InputArgument("org") UUID org,
			@InputArgument("component") UUID component, @InputArgument("includeSuperseded") Boolean includeSuperseded,
			@InputArgument("limit") Integer limit, @InputArgument("offset") Integer offset) throws RelizaException {
		UserData oud = currentUser();
		Optional<ComponentData> ocd = getComponentService.getComponentData(component);
		if (ocd.isEmpty() || !org.equals(ocd.get().getOrg())) throw new AccessDeniedException(SafetyContextService.NOT_FOUND);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, PermissionFunction.FINDING_ANALYSIS_READ,
			PermissionScope.COMPONENT, component, List.of((RelizaObject) ocd.get()), CallType.READ);
		return records(safetyContextService.listRecordsForScopeTarget(org, AnalysisScope.COMPONENT.name(), component,
			Boolean.TRUE.equals(includeSuperseded), intOf(limit), intOf(offset)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSafetyContextSourceAssertion")
	public String getSourceAssertion(@InputArgument("uuid") UUID uuid) throws RelizaException {
		UserData oud = currentUser();
		Optional<SafetyContextProposalData> op = safetyContextService.getProposal(uuid);
		SafetyContextData row = op.isPresent() ? op.get()
			: safetyContextService.getRecord(uuid).orElseThrow(() -> new AccessDeniedException(SafetyContextService.NOT_FOUND));
		gateRowUniform(oud, row, PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		return row.getSourceAssertionJson();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "acceptSafetyContextProposal")
	public SafetyContextEntryWebDto accept(@InputArgument("uuid") UUID uuid,
			@InputArgument("component") UUID component, @InputArgument("comment") String comment) throws RelizaException {
		UserData oud = currentUser();
		SafetyContextProposalData d = safetyContextService.getProposal(uuid)
			.orElseThrow(() -> new AccessDeniedException(SafetyContextService.NOT_FOUND));
		gateRowUniform(oud, d, PermissionFunction.FINDING_ANALYSIS_WRITE, CallType.WRITE);
		return proposals(List.of(safetyContextService.accept(uuid, component, comment, WhoUpdated.getWhoUpdated(oud)))).get(0);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "rejectSafetyContextProposal")
	public SafetyContextEntryWebDto reject(@InputArgument("uuid") UUID uuid,
			@InputArgument("reason") String reason) throws RelizaException {
		UserData oud = currentUser();
		SafetyContextProposalData d = safetyContextService.getProposal(uuid)
			.orElseThrow(() -> new AccessDeniedException(SafetyContextService.NOT_FOUND));
		gateRowUniform(oud, d, PermissionFunction.FINDING_ANALYSIS_WRITE, CallType.WRITE);
		return proposals(List.of(safetyContextService.reject(uuid, reason, WhoUpdated.getWhoUpdated(oud)))).get(0);
	}

	private List<SafetyContextEntryWebDto> proposals(List<SafetyContextProposalData> data) {
		Enrichment e = new Enrichment();
		return data.stream().map(d -> e.apply(SafetyContextEntryWebDto.fromProposal(d))).toList();
	}

	private List<SafetyContextEntryWebDto> records(List<SafetyContextRecordData> data) {
		Enrichment e = new Enrichment();
		Map<String, Integer> highestByCoarseKey = new HashMap<>();
		for (SafetyContextRecordData d : data) {
			if (d.getStatus() != SafetyContextRecordData.RecordStatus.ACTIVE) continue;
			String key = SafetyContextService.coarseKey(d);
			if (!highestByCoarseKey.containsKey(key)) {
				// All ACTIVE levels for this component and scheme, including other releases' assets.
				Integer highest = safetyContextService.findActiveAtCoarseKey(d).stream()
					.map(SafetyContextData::getLevelRank).filter(r -> r != null).max(Integer::compare).orElse(null);
				highestByCoarseKey.put(key, highest);
			}
		}
		return data.stream().map(d -> {
			SafetyContextEntryWebDto dto = e.apply(SafetyContextEntryWebDto.fromRecord(d));
			Integer highest = highestByCoarseKey.get(SafetyContextService.coarseKey(d));
			dto.setComponentMostConservative(d.getStatus() == SafetyContextRecordData.RecordStatus.ACTIVE
				&& highest != null && highest.equals(d.getLevelRank()));
			return dto;
		}).toList();
	}

	/** Per-response caches for user display names and source-artifact state. */
	private final class Enrichment {
		private final Map<UUID, String> names = new HashMap<>();
		private final Map<UUID, Boolean> deleted = new HashMap<>();

		SafetyContextEntryWebDto apply(SafetyContextEntryWebDto dto) {
			dto.setOverrideActorName(name(dto.getOverrideActor()));
			dto.setAcceptedByName(name(dto.getAcceptedBy()));
			if (dto.getSourceArtifact() != null) {
				dto.setSourceDeleted(deleted.computeIfAbsent(dto.getSourceArtifact(), a -> artifactService.getArtifactData(a)
					.map(ad -> ad.getStatus() == StatusEnum.ARCHIVED).orElse(true)));
			}
			return dto;
		}

		private String name(UUID user) {
			if (user == null) return null;
			return names.computeIfAbsent(user, u -> userService.getUserData(u).map(UserData::getName).orElse(null));
		}
	}

	/** Gates a row at its scope; missing and forbidden both surface as NOT_FOUND. */
	private void gateRowUniform(UserData oud, SafetyContextData row, PermissionFunction perm, CallType callType) {
		try {
			if (row.getScope() == AnalysisScope.RELEASE && row.getScopeUuid() != null) {
				gateRelease(oud, row.getOrg(), row.getScopeUuid(), perm, callType);
			} else if (row.getScope() == AnalysisScope.COMPONENT && row.getScopeUuid() != null) {
				Optional<ComponentData> ocd = getComponentService.getComponentData(row.getScopeUuid());
				if (ocd.isEmpty() || !row.getOrg().equals(ocd.get().getOrg())) throw new AccessDeniedException(SafetyContextService.NOT_FOUND);
				authorizationService.isUserAuthorizedForObjectGraphQL(oud, perm, PermissionScope.COMPONENT,
					row.getScopeUuid(), List.of((RelizaObject) ocd.get()), callType);
			} else {
				gateOrg(oud, row.getOrg(), perm, callType);
			}
		} catch (AccessDeniedException | RelizaException e) {
			throw new AccessDeniedException(SafetyContextService.NOT_FOUND);
		}
	}

	private UserData currentUser() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new AccessDeniedException("Unauthenticated"));
	}

	private void gateOrg(UserData oud, UUID org, PermissionFunction perm, CallType callType) throws RelizaException {
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, perm, PermissionScope.ORGANIZATION, org,
			Collections.singletonList(ro), callType);
	}

	private void gateRelease(UserData oud, UUID org, UUID release, PermissionFunction perm, CallType callType)
			throws RelizaException {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(release, org);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, perm, PermissionScope.RELEASE, release,
			Collections.singletonList(ro), callType);
	}

	private static int intOf(Integer value) {
		return value == null ? 0 : value;
	}
}
