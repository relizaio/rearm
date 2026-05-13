/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AnalysisScope;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.MatchCandidate;

/**
 * Maps user-picked scope + resolved releases to concrete (scope, scopeUuid) targets.
 *
 * <ul>
 *   <li>ORG — one candidate (ORG, orgUuid) regardless of release coverage. The user's
 *       authority overrides relevance: vendor VEX about a component the org hasn't
 *       deployed yet still produces a proposal for the future.</li>
 *   <li>RELEASE — one candidate per resolved release, intersected with {@code restrictToRelease}
 *       (provenance binding from the v1.1 dispatch path) when set.</li>
 *   <li>BRANCH — one candidate per distinct branch among the resolved releases.</li>
 *   <li>COMPONENT — one candidate per distinct component among the resolved releases.</li>
 * </ul>
 *
 * Scope semantics: see ai-plans/vex_imports/09_scope_and_conflict.md §3.A.
 */
@Service
public class ScopeTargetResolver {

    @Autowired SharedReleaseService sharedReleaseService;

    public List<MatchCandidate> targets(UUID org, AnalysisScope userScope,
                                        Set<UUID> resolvedReleases, UUID restrictToRelease) {
        AnalysisScope effective = userScope == null ? AnalysisScope.RELEASE : userScope;
        switch (effective) {
            case ORG:
                return List.of(new MatchCandidate(AnalysisScope.ORG, org));
            case RELEASE:
                List<MatchCandidate> out = new ArrayList<>(resolvedReleases.size());
                for (UUID r : resolvedReleases) {
                    if (restrictToRelease != null && !restrictToRelease.equals(r)) continue;
                    out.add(new MatchCandidate(AnalysisScope.RELEASE, r));
                }
                return out;
            case BRANCH:
                return distinctScopeFromReleases(resolvedReleases, org, AnalysisScope.BRANCH);
            case COMPONENT:
                return distinctScopeFromReleases(resolvedReleases, org, AnalysisScope.COMPONENT);
            case RESOURCE_GROUP:
            default:
                throw new IllegalArgumentException("Unsupported VEX scope: " + effective);
        }
    }

    private List<MatchCandidate> distinctScopeFromReleases(Set<UUID> releases, UUID org, AnalysisScope target) {
        if (releases.isEmpty() || sharedReleaseService == null) return List.of();
        Set<UUID> seen = new LinkedHashSet<>();
        List<MatchCandidate> out = new ArrayList<>();
        for (UUID releaseUuid : releases) {
            Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(releaseUuid, org);
            if (rd.isEmpty()) continue;
            UUID id = (target == AnalysisScope.BRANCH) ? rd.get().getBranch() : rd.get().getComponent();
            if (id != null && seen.add(id)) {
                out.add(new MatchCandidate(target, id));
            }
        }
        return out;
    }
}
