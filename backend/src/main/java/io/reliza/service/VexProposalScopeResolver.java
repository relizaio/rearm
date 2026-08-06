/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AnalysisScope;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.VexStatementProposalWebDto;

/**
 * Fills the human-readable scope fields on {@link VexStatementProposalWebDto}.
 *
 * <p>A proposal stores only {@code scope} + {@code scopeUuid}, which renders in
 * the UI as a bare UUID. Resolving it client-side would cost one to three
 * queries PER proposal, and the VEX inbox is an org-wide list -- so resolution
 * happens here, batched across the whole page:
 *
 * <ol>
 *   <li>partition the distinct scope uuids by scope kind;</li>
 *   <li>one batch read for releases (light: no metrics jsonb) and one for
 *       components;</li>
 *   <li>a second component batch for the parents discovered via releases and
 *       branches, so nothing is fetched twice.</li>
 * </ol>
 *
 * <p>Branches have no batch reader, so they are fetched individually behind a
 * per-call cache -- bounded by the number of DISTINCT branch-bearing scopes on
 * the page, not by the proposal count.
 *
 * <p>Resolution is best-effort display data: a scope pointing at a deleted or
 * inaccessible object simply leaves the fields null and the UI falls back to
 * the raw uuid. It never fails the query.
 */
@Service
public class VexProposalScopeResolver {

	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;

	/** Convenience for the single-proposal read paths. */
	public void resolveScopeNames(VexStatementProposalWebDto dto) {
		if (dto != null) resolveScopeNames(List.of(dto));
	}

	/**
	 * Fills scope display fields on every dto in the list, in batch.
	 * Safe on an empty or null list.
	 */
	public void resolveScopeNames(Collection<VexStatementProposalWebDto> dtos) {
		if (dtos == null || dtos.isEmpty()) return;

		Set<UUID> releaseUuids = new LinkedHashSet<>();
		Set<UUID> branchUuids = new LinkedHashSet<>();
		Set<UUID> componentUuids = new LinkedHashSet<>();
		for (VexStatementProposalWebDto dto : dtos) {
			UUID scopeUuid = dto.getScopeUuid();
			if (scopeUuid == null || dto.getScope() == null) continue;
			switch (dto.getScope()) {
				case RELEASE -> releaseUuids.add(scopeUuid);
				case BRANCH -> branchUuids.add(scopeUuid);
				case COMPONENT -> componentUuids.add(scopeUuid);
				// ORG / RESOURCE_GROUP have no component-level identity to show.
				default -> { }
			}
		}
		if (releaseUuids.isEmpty() && branchUuids.isEmpty() && componentUuids.isEmpty()) return;

		Map<UUID, ReleaseData> releases = new HashMap<>();
		for (ReleaseData rd : sharedReleaseService.getReleaseDataListLight(releaseUuids)) {
			releases.put(rd.getUuid(), rd);
		}

		// Branches: the scope-level ones, plus every branch a resolved release
		// points at (so a RELEASE-scoped proposal can show its branch too).
		Map<UUID, BranchData> branches = new HashMap<>();
		Set<UUID> branchesToLoad = new LinkedHashSet<>(branchUuids);
		releases.values().stream()
				.map(ReleaseData::getBranch)
				.filter(java.util.Objects::nonNull)
				.forEach(branchesToLoad::add);
		for (UUID branchUuid : branchesToLoad) {
			branchService.getBranchData(branchUuid).ifPresent(bd -> branches.put(branchUuid, bd));
		}

		// Components: the scope-level ones, plus the parents of everything above.
		Set<UUID> componentsToLoad = new LinkedHashSet<>(componentUuids);
		releases.values().stream()
				.map(ReleaseData::getComponent)
				.filter(java.util.Objects::nonNull)
				.forEach(componentsToLoad::add);
		branches.values().stream()
				.map(BranchData::getComponent)
				.filter(java.util.Objects::nonNull)
				.forEach(componentsToLoad::add);
		Map<UUID, ComponentData> components = new HashMap<>();
		for (ComponentData cd : getComponentService.getListOfComponentData(componentsToLoad)) {
			components.put(cd.getUuid(), cd);
		}

		for (VexStatementProposalWebDto dto : dtos) {
			applyResolution(dto, releases, branches, components);
		}
	}

	private void applyResolution(VexStatementProposalWebDto dto, Map<UUID, ReleaseData> releases,
			Map<UUID, BranchData> branches, Map<UUID, ComponentData> components) {
		UUID scopeUuid = dto.getScopeUuid();
		AnalysisScope scope = dto.getScope();
		if (scopeUuid == null || scope == null) return;

		UUID branchUuid = null;
		UUID componentUuid = null;

		if (scope == AnalysisScope.RELEASE) {
			ReleaseData rd = releases.get(scopeUuid);
			if (rd == null) return; // deleted / not visible -- leave nulls, UI shows the uuid
			dto.setScopeReleaseUuid(rd.getUuid());
			dto.setScopeReleaseVersion(rd.getVersion());
			branchUuid = rd.getBranch();
			componentUuid = rd.getComponent();
		} else if (scope == AnalysisScope.BRANCH) {
			branchUuid = scopeUuid;
		} else if (scope == AnalysisScope.COMPONENT) {
			componentUuid = scopeUuid;
		} else {
			return;
		}

		if (branchUuid != null) {
			BranchData bd = branches.get(branchUuid);
			if (bd != null) {
				dto.setScopeBranchUuid(branchUuid);
				dto.setScopeBranchName(bd.getName());
				// A release's component is denormalized onto the release, but a
				// BRANCH-scoped proposal only learns its component here.
				if (componentUuid == null) componentUuid = bd.getComponent();
			}
		}
		if (componentUuid != null) {
			Optional<ComponentData> ocd = Optional.ofNullable(components.get(componentUuid));
			if (ocd.isPresent()) {
				dto.setScopeComponentUuid(componentUuid);
				dto.setScopeComponentName(ocd.get().getName());
			}
		}
	}
}
