/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.reliza.model.AnalysisScope;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.MatchCandidate;

class ScopeTargetResolverTest {

    private final ScopeTargetResolver r = new ScopeTargetResolver();

    @Test
    void releaseScopeEchoesResolvedReleases() {
        UUID org = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<MatchCandidate> out = r.targets(org, AnalysisScope.RELEASE, Set.of(a, b), null);
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(c -> c.scope() == AnalysisScope.RELEASE));
    }

    @Test
    void releaseScopeWithRestrictFiltersToBoundRelease() {
        UUID org = UUID.randomUUID();
        UUID bound = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<MatchCandidate> out = r.targets(org, AnalysisScope.RELEASE, Set.of(bound, other), bound);
        assertEquals(1, out.size());
        assertEquals(bound, out.get(0).scopeUuid());
    }

    @Test
    void releaseScopeWithRestrictNotInResolvedSetIsEmpty() {
        UUID org = UUID.randomUUID();
        UUID bound = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<MatchCandidate> out = r.targets(org, AnalysisScope.RELEASE, Set.of(other), bound);
        assertTrue(out.isEmpty());
    }

    @Test
    void orgScopeEmitsOneCandidateRegardlessOfReleases() {
        UUID org = UUID.randomUUID();
        List<MatchCandidate> emptyReleases = r.targets(org, AnalysisScope.ORG, Set.of(), null);
        assertEquals(1, emptyReleases.size());
        assertEquals(AnalysisScope.ORG, emptyReleases.get(0).scope());
        assertEquals(org, emptyReleases.get(0).scopeUuid());

        List<MatchCandidate> withReleases = r.targets(org, AnalysisScope.ORG, Set.of(UUID.randomUUID(), UUID.randomUUID()), null);
        assertEquals(1, withReleases.size());
    }

    @Test
    void orgScopeIgnoresRestrictToRelease() {
        // restrictToRelease is provenance, not applicability — for ORG scope it's a no-op.
        UUID org = UUID.randomUUID();
        UUID restrict = UUID.randomUUID();
        List<MatchCandidate> out = r.targets(org, AnalysisScope.ORG, Set.of(), restrict);
        assertEquals(1, out.size());
        assertEquals(org, out.get(0).scopeUuid());
    }

    @Test
    void nullScopeDefaultsToRelease() {
        UUID org = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        List<MatchCandidate> out = r.targets(org, null, Set.of(release), null);
        assertEquals(1, out.size());
        assertEquals(AnalysisScope.RELEASE, out.get(0).scope());
    }

    @Test
    void branchScopeCollapsesReleasesToDistinctBranches() {
        UUID org = UUID.randomUUID();
        UUID release1 = UUID.randomUUID();
        UUID release2 = UUID.randomUUID();
        UUID release3 = UUID.randomUUID();
        UUID branchA = UUID.randomUUID();
        UUID branchB = UUID.randomUUID();

        ScopeTargetResolver r2 = new ScopeTargetResolver();
        SharedReleaseService srs = Mockito.mock(SharedReleaseService.class);
        r2.sharedReleaseService = srs;
        ReleaseData rd1 = mockReleaseData(branchA, null);
        ReleaseData rd2 = mockReleaseData(branchA, null);
        ReleaseData rd3 = mockReleaseData(branchB, null);
        Mockito.when(srs.getReleaseDataLight(release1, org)).thenReturn(java.util.Optional.of(rd1));
        Mockito.when(srs.getReleaseDataLight(release2, org)).thenReturn(java.util.Optional.of(rd2));
        Mockito.when(srs.getReleaseDataLight(release3, org)).thenReturn(java.util.Optional.of(rd3));

        List<MatchCandidate> out = r2.targets(org, AnalysisScope.BRANCH, Set.of(release1, release2, release3), null);
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(c -> c.scope() == AnalysisScope.BRANCH));
    }

    @Test
    void componentScopeCollapsesReleasesToDistinctComponents() {
        UUID org = UUID.randomUUID();
        UUID release1 = UUID.randomUUID();
        UUID release2 = UUID.randomUUID();
        UUID componentA = UUID.randomUUID();

        ScopeTargetResolver r2 = new ScopeTargetResolver();
        SharedReleaseService srs = Mockito.mock(SharedReleaseService.class);
        r2.sharedReleaseService = srs;
        ReleaseData rd1 = mockReleaseData(null, componentA);
        ReleaseData rd2 = mockReleaseData(null, componentA);
        Mockito.when(srs.getReleaseDataLight(release1, org)).thenReturn(java.util.Optional.of(rd1));
        Mockito.when(srs.getReleaseDataLight(release2, org)).thenReturn(java.util.Optional.of(rd2));

        List<MatchCandidate> out = r2.targets(org, AnalysisScope.COMPONENT, Set.of(release1, release2), null);
        assertEquals(1, out.size());
        assertEquals(componentA, out.get(0).scopeUuid());
    }

    private ReleaseData mockReleaseData(UUID branch, UUID component) {
        ReleaseData rd = Mockito.mock(ReleaseData.class);
        Mockito.when(rd.getBranch()).thenReturn(branch);
        Mockito.when(rd.getComponent()).thenReturn(component);
        return rd;
    }
}
