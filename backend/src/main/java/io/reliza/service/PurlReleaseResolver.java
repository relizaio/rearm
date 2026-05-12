/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves a product PURL to the set of releases (within an org) whose inventory references it.
 * Pure data lookup over {@link SbomComponentService} — extracted from the v1 VexMatcher so
 * scope-target resolution can live in its own component.
 */
@Slf4j
@Service
public class PurlReleaseResolver {

    @Autowired SbomComponentService sbomComponentService;

    public Set<UUID> resolve(UUID org, String productPurl) {
        if (org == null || productPurl == null) return Set.of();
        UUID componentUuid = sbomComponentService.searchSbomComponentByPurl(productPurl, org);
        if (componentUuid == null) {
            log.debug("PurlReleaseResolver: no sbom_component found for PURL {} in org {}", productPurl, org);
            return Set.of();
        }
        return sbomComponentService.findReleaseUuidsBySbomComponents(Set.of(componentUuid), org);
    }
}
