/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ReleaseFinalizerService {

    @Autowired
    private AcollectionService acollectionService;

    @Autowired
    private SharedReleaseService sharedReleaseService;

    @Autowired
    private ComponentService componentService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private ScheduledExecutorService scheduledExecutorService;

    public void scheduleFinalizeRelease(UUID releaseId) {
        // log.info("RGDEBUG: scheduling finalize release for {}", releaseId);
        scheduledExecutorService.schedule(() -> {
            try {
                finalizeRelease(releaseId);
            } catch (Exception e) {
                log.error("Error finalizing release {}", releaseId, e);
            }
        }, 180, TimeUnit.SECONDS);
    }

    public void finalizeRelease(UUID releaseUuid) {

        Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
        if (ord.isPresent()) {
            ReleaseData rd = ord.get();
            UUID branch = rd.getBranch();
            UUID org = rd.getOrg();

            acollectionService.resolveReleaseCollection(releaseUuid, WhoUpdated.getAutoWhoUpdated());
            
            // Then calculate the BOM changelog
            acollectionService.releaseBomChangelogRoutine(releaseUuid, branch, org);
            // Add more finalization steps here as needed
        } else {
            log.warn("SBOM_CHANGELOG: Release not found for UUID: {}", releaseUuid);
        }
    }

    public void finalizeAllReleases() {
        scheduledExecutorService.schedule(() -> {
            try {
                doFinalizeAllReleases();
            } catch (Exception e) {
                log.error("Error in finalizeAllReleases", e);
            }
        }, 0, TimeUnit.SECONDS);
    }

    private void doFinalizeAllReleases() {
        log.info("FINALIZE_ALL: Starting finalizeAllReleases");
        var allComponents = componentService.listAllComponentData();
        int componentCount = 0;
        int branchCount = 0;
        int pairCount = 0;
        int errorCount = 0;
        Map<UUID, Optional<UUID>> baseBranchCache = new HashMap<>();

        for (ComponentData cd : allComponents) {
            if (cd.getType() != ComponentType.COMPONENT) {
                continue;
            }
            componentCount++;
            var branches = branchService.listBranchDataOfComponent(cd.getUuid(), null);
            log.debug("FINALIZE_ALL: === Component: {} ({}) - {} branches ===", cd.getName(), cd.getUuid(), branches.size());
            for (BranchData bd : branches) {
                try {
                    branchCount++;
                    // Get all releases for this branch (no limit), sorted by version descending
                    List<ReleaseData> releasesDesc = sharedReleaseService.listReleaseDataOfBranch(bd.getUuid(), null, null, true);
                    int totalReleases = releasesDesc.size();
                    // Filter out CANCELLED and REJECTED releases, reverse to ascending version order
                    List<ReleaseData> releases = new ArrayList<>(releasesDesc.size());
                    for (int i = releasesDesc.size() - 1; i >= 0; i--) {
                        ReleaseData rd = releasesDesc.get(i);
                        if (rd.getLifecycle() != ReleaseLifecycle.CANCELLED
                                && rd.getLifecycle() != ReleaseLifecycle.REJECTED) {
                            releases.add(rd);
                        }
                    }

                    if (releases.isEmpty()) {
                        continue;
                    }

                    log.debug("FINALIZE_ALL:   Branch: {} - {} active releases ({} total, {} skipped)",
                            bd.getName(), releases.size(), totalReleases, totalReleases - releases.size());
                    for (ReleaseData rd : releases) {
                        log.debug("FINALIZE_ALL:     Release: {} ({})", rd.getVersion(), rd.getUuid());
                    }

                    // Handle first release: find its previous via fork point for non-base branches
                    ReleaseData firstRelease = releases.get(0);
                    if (bd.getType() != BranchData.BranchType.BASE) {
                        UUID prevForFirst = sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                                bd.getUuid(), firstRelease.getUuid(), firstRelease, cd, baseBranchCache);
                        if (prevForFirst != null) {
                            log.debug("FINALIZE_ALL:     Diff: fork-point {} -> {}", prevForFirst, firstRelease.getVersion());
                            acollectionService.resolveBomDiff(firstRelease.getUuid(), prevForFirst, firstRelease.getOrg(), true);
                            pairCount++;
                        }
                    }

                    // Walk consecutive pairs
                    for (int i = 0; i < releases.size() - 1; i++) {
                        ReleaseData prev = releases.get(i);
                        ReleaseData curr = releases.get(i + 1);
                        log.debug("FINALIZE_ALL:     Diff: {} -> {}", prev.getVersion(), curr.getVersion());
                        acollectionService.resolveBomDiff(curr.getUuid(), prev.getUuid(), curr.getOrg(), true);
                        pairCount++;
                    }
                } catch (Exception e) {
                    errorCount++;
                    log.error("FINALIZE_ALL: Error processing branch {} of component {}", bd.getUuid(), cd.getUuid(), e);
                }
            }
        }
        log.info("FINALIZE_ALL: Completed. {} components, {} branches, {} release pairs processed, {} errors",
                componentCount, branchCount, pairCount, errorCount);
    }
}