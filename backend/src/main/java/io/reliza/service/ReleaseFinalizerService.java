/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.reliza.model.ReleaseData;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ReleaseFinalizerService {

    @Autowired
    private AcollectionService acollectionService;

    @Autowired
    private SharedReleaseService sharedReleaseService;

    @Autowired
    private ScheduledExecutorService scheduledExecutorService;

    public void scheduleFinalizeRelease(UUID releaseId) {
        log.info("RGDEBUG: scheduling finalize release for {}", releaseId);
        scheduledExecutorService.schedule(() -> {
            try {
                finalizeRelease(releaseId);
            } catch (Exception e) {
                log.error("Error finalizing release {}", releaseId, e);
            }
        }, 300, TimeUnit.SECONDS);
    }

    public void finalizeRelease(UUID releaseUuid) {
        log.info("RGDEBUG: finalizing release {}", releaseUuid);
        Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
        if (ord.isPresent()) {
            ReleaseData rd = ord.get();
            UUID branch = rd.getBranch();
            UUID org = rd.getOrg();
            acollectionService.releaseBomChangelogRoutine(releaseUuid, branch, org);
            // Add more finalization steps here as needed
        }
        // else: log or handle release not found
    }
}