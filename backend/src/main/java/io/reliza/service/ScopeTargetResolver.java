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
 *   <li>ORG -- one candidate (ORG, orgUuid) regardless of release coverage. The user's
 *       authority overrides relevance: vendor VEX about a component the org hasn't
 *       deployed yet still produces a proposal for the future.</li>
 *   <li>RELEASE -- one candidate per resolved release.</li>
 *   <li>BRANCH -- one candidate per distinct branch among the resolved releases.</li>
 *   <li>COMPONENT -- one candidate per distinct component among the resolved releases.</li>
 * </ul>
 *
 * When {@code restrictToRelease} is set (every artifact-upload dispatch binds the uploading
 * release), only the uploading release's own release / branch / component can be targeted:
 * <ul>
 *   <li>RELEASE -- the uploading release, if the PURL resolved to it.</li>
 *   <li>BRANCH / COMPONENT -- the uploading release's branch / component, if the PURL
 *       resolved to any release of that branch / component (an older release counts).</li>
 * </ul>
 * So a VEX uploaded on one component's release never lands on other components or branches
 * that happen to contain the same PURL; otherwise the statement is unmatched. ORG is the
 * explicit "everywhere" choice.
 *
 * Scope semantics: see ai-plans/vex_imports/09_scope_and_conflict.md section 3.A.
 */
@Service
public class ScopeTargetResolver {

    @Autowired SharedReleaseService sharedReleaseService;

    public List<MatchCandidate> targets(UUID org, AnalysisScope userScope,
                                        Set<UUID> resolvedReleases, UUID restrictToRelease) {
        AnalysisScope effective = userScope == null ? AnalysisScope.RELEASE : userScope;
        if (effective == AnalysisScope.ORG) {
            return List.of(new MatchCandidate(AnalysisScope.ORG, org));
        }
        switch (effective) {
            case RELEASE:
                List<MatchCandidate> out = new ArrayList<>(resolvedReleases.size());
                for (UUID r : resolvedReleases) {
                    if (restrictToRelease != null && !restrictToRelease.equals(r)) continue;
                    out.add(new MatchCandidate(AnalysisScope.RELEASE, r));
                }
                return out;
            case BRANCH:
            case COMPONENT:
                if (restrictToRelease != null) {
                    return boundScope(restrictToRelease, resolvedReleases, org, effective);
                }
                return distinctScopeFromReleases(resolvedReleases, org, effective);
            case RESOURCE_GROUP:
            default:
                throw new IllegalArgumentException("Unsupported VEX scope: " + effective);
        }
    }

    /**
     * The bound release's branch / component, once, when the PURL resolved to at least one
     * release of it. Empty when it did not, or the bound release is unreadable in this org --
     * never a fan-out to other branches / components.
     */
    private List<MatchCandidate> boundScope(UUID boundRelease, Set<UUID> resolvedReleases,
                                            UUID org, AnalysisScope target) {
        if (resolvedReleases.isEmpty()) return List.of();
        List<MatchCandidate> bound = distinctScopeFromReleases(Set.of(boundRelease), org, target);
        if (bound.isEmpty() || resolvedReleases.contains(boundRelease)) return bound;
        UUID boundId = bound.get(0).scopeUuid();
        for (UUID releaseUuid : resolvedReleases) {
            Optional<ReleaseData> rd = sharedReleaseService.getReleaseDataLight(releaseUuid, org);
            if (rd.isEmpty()) continue;
            UUID id = (target == AnalysisScope.BRANCH) ? rd.get().getBranch() : rd.get().getComponent();
            if (boundId.equals(id)) return bound;
        }
        return List.of();
    }

    private List<MatchCandidate> distinctScopeFromReleases(Set<UUID> releases, UUID org, AnalysisScope target) {
        if (releases.isEmpty() || sharedReleaseService == null) return List.of();
        Set<UUID> seen = new LinkedHashSet<>();
        List<MatchCandidate> out = new ArrayList<>();
        for (UUID releaseUuid : releases) {
            Optional<ReleaseData> rd = sharedReleaseService.getReleaseDataLight(releaseUuid, org);
            if (rd.isEmpty()) continue;
            UUID id = (target == AnalysisScope.BRANCH) ? rd.get().getBranch() : rd.get().getComponent();
            if (id != null && seen.add(id)) {
                out.add(new MatchCandidate(target, id));
            }
        }
        return out;
    }
}
