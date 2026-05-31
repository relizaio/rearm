/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import lombok.extern.slf4j.Slf4j;

/**
 * "Finalize" used to resolve the acollection snapshot and compute the SBOM
 * components changelog for a release. As of the reconcile-driven BOM-diff work,
 * both of those happen on the SBOM-component reconcile drain
 * ({@code SbomComponentService.postReconcileBomDiff}: snapshot resolve +
 * changelog cache + once-per-release notification).
 *
 * <p>The finalizer GraphQL mutations are intentionally retained for backward
 * compatibility, so this service is now a thin compatibility shim: it just
 * enqueues a reconcile, letting the drain run the pipeline. The methods are
 * kept (rather than removed) so existing CI / CLI callers keep working.
 */
@Service
@Slf4j
public class ReleaseFinalizerService {

    @Autowired
    private SharedReleaseService sharedReleaseService;

    @Autowired
    private ComponentService componentService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private SbomComponentService sbomComponentService;

    @Autowired
    private ScheduledExecutorService scheduledExecutorService;

    public void scheduleFinalizeRelease(UUID releaseId) {
        scheduledExecutorService.schedule(() -> {
            try {
                finalizeRelease(releaseId);
            } catch (Exception e) {
                log.error("Error finalizing release {}", releaseId, e);
            }
        }, 180, TimeUnit.SECONDS);
    }

    /**
     * Compatibility shim — enqueues an SBOM-component reconcile so the drain
     * resolves the acollection snapshot, refreshes the changelog cache, and
     * fires the once-per-release notification. Idempotent
     * ({@code markSbomReconcileRequested} no-ops if a request is already pending).
     */
    public void finalizeRelease(UUID releaseUuid) {
        sbomComponentService.requestReconcile(releaseUuid);
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

    /**
     * Admin back-fill: enqueue an SBOM-component reconcile for every release so
     * the drain re-resolves each acollection snapshot + changelog cache. The
     * old pairwise rebom-diff walk is gone — the reconcile drain handles the
     * prev/next changelog recompute itself.
     */
    private void doFinalizeAllReleases() {
        log.info("FINALIZE_ALL: enqueuing SBOM reconcile for all releases");
        int componentCount = 0;
        int branchCount = 0;
        int releaseCount = 0;
        var allComponents = componentService.listAllComponentData();
        for (ComponentData cd : allComponents) {
            componentCount++;
            var branches = branchService.listBranchDataOfComponent(cd.getUuid(), null);
            for (BranchData bd : branches) {
                branchCount++;
                try {
                    List<ReleaseData> releases =
                            sharedReleaseService.listReleaseDataOfBranch(bd.getUuid(), (Integer) null, true);
                    for (ReleaseData rd : releases) {
                        sbomComponentService.requestReconcile(rd.getUuid());
                        releaseCount++;
                    }
                } catch (Exception e) {
                    log.warn("FINALIZE_ALL: error enqueuing reconcile for branch {} of component {}: {}",
                            bd.getName(), cd.getName(), e.getMessage());
                }
            }
        }
        log.info("FINALIZE_ALL: Completed. {} components, {} branches, {} releases enqueued",
                componentCount, branchCount, releaseCount);
    }
}
