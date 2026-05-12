/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AnalysisScope;
import io.reliza.model.dto.MatchCandidate;

/**
 * Thin coordinator: PURL → resolved releases → scope-targeted match candidates. The
 * resolver split (PurlReleaseResolver + ScopeTargetResolver) lets each axis evolve
 * independently — purl matching is a data lookup, scope targeting is pure compute.
 */
@Service
public class VexMatcher {

    @Autowired PurlReleaseResolver purlReleaseResolver;
    @Autowired ScopeTargetResolver scopeTargetResolver;

    public List<MatchCandidate> match(UUID org, String productPurl,
                                      AnalysisScope userScope, UUID restrictToRelease) {
        Set<UUID> releases = purlReleaseResolver.resolve(org, productPurl);
        return scopeTargetResolver.targets(org, userScope, releases, restrictToRelease);
    }
}
