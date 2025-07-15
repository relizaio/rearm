/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

import io.reliza.model.ReleaseData;

@Service
public class ReleaseFinalizerService {

    @Autowired
    private AcollectionService acollectionService;

    @Autowired
    private SharedReleaseService sharedReleaseService;

    @Async
    public void finalizeRelease(UUID releaseUuid) {
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