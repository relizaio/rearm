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
 * <p>Branches are batched the same way, via
 * {@link BranchService#getBranchDataList(Iterable)}.
 *
 * <p>Every resolved object is checked to belong to the proposal's own org
 * before its name is used. Scope targets are set server-side today and cannot
 * point across orgs, so this is defence-in-depth for future write paths: a
 * cross-org mismatch is treated as unresolvable rather than leaking another
 * org's component / branch / release names to this org's viewers.
 *
 * <p>Resolution is best-effort display data: a scope pointing at a deleted,
 * inaccessible or cross-org object simply leaves the fields null and the UI
 * falls back to the raw uuid. It never fails the query.
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
		// RELEASE is the dominant scope kind and distinct releases mean distinct
		// branches, so this MUST be batched -- resolving one at a time here would
		// reinstate the per-proposal lookup this class exists to avoid.
		Map<UUID, BranchData> branches = new HashMap<>();
		Set<UUID> branchesToLoad = new LinkedHashSet<>(branchUuids);
		releases.values().stream()
				.map(ReleaseData::getBranch)
				.filter(java.util.Objects::nonNull)
				.forEach(branchesToLoad::add);
		if (!branchesToLoad.isEmpty()) {
			for (BranchData bd : branchService.getBranchDataList(branchesToLoad)) {
				branches.put(bd.getUuid(), bd);
			}
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
			// null => deleted / not visible; org mismatch => never surface another
			// org's names here. Both leave nulls and the UI shows the raw uuid.
			if (rd == null || !sameOrg(dto, rd.getOrg())) return;
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
			if (bd != null && sameOrg(dto, bd.getOrg())) {
				dto.setScopeBranchUuid(branchUuid);
				dto.setScopeBranchName(bd.getName());
				// A release's component is denormalized onto the release, but a
				// BRANCH-scoped proposal only learns its component here.
				if (componentUuid == null) componentUuid = bd.getComponent();
			}
		}
		if (componentUuid != null) {
			Optional<ComponentData> ocd = Optional.ofNullable(components.get(componentUuid));
			if (ocd.isPresent() && sameOrg(dto, ocd.get().getOrg())) {
				dto.setScopeComponentUuid(componentUuid);
				dto.setScopeComponentName(ocd.get().getName());
			}
		}
	}

	/**
	 * Guards every resolved level against cross-org leakage. Scope targets are
	 * assigned server-side from the org's own objects today, so a mismatch is
	 * not currently reachable -- this keeps it unreachable if a future write
	 * path ever accepts a caller-supplied scopeUuid.
	 */
	private static boolean sameOrg(VexStatementProposalWebDto dto, UUID resolvedOrg) {
		return dto.getOrg() != null && dto.getOrg().equals(resolvedOrg);
	}
}
