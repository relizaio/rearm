/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.repositories.ReleaseRepository;

/**
 * Attach-time release touches that run in their OWN transaction. They are invoked from
 * {@code afterCommit} synchronizations (see {@link io.reliza.common.TxUtils#afterCommitOrNow}),
 * where the just-completed transaction is still the ambient one: a plain {@code @Transactional}
 * (REQUIRED) modifying query would join it and fail with "No active transaction for update or
 * delete query". REQUIRES_NEW opens a fresh transaction for the touch.
 */
@Service
public class ReleaseMetricsTouchService {

	@Autowired
	private ReleaseRepository releaseRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void touchByDeliverableArtifact(String artifactUuid) {
		releaseRepository.touchReleasesByScannedDeliverableArtifact(artifactUuid);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void touchBySceArtifact(String artifactUuid) {
		releaseRepository.touchReleasesByScannedSceArtifact(artifactUuid);
	}
}
