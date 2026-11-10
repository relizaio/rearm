/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.model.ReleaseData;
import io.reliza.model.SbomComponentOverlay;
import io.reliza.model.SbomComponentOverlayAudit;
import io.reliza.model.SbomComponentOverlayPatch;
import io.reliza.repositories.SbomComponentOverlayAuditRepository;
import io.reliza.repositories.SbomComponentOverlayRepository;

/** FDAW-15 fixtures: the overlay service and its rows over the FDAW-14 base. */
abstract class SbomComponentOverlayTestBase extends ReleaseSbomJustificationTestBase {

	@Autowired SbomComponentOverlayService overlayService;
	@Autowired SbomComponentOverlayRepository overlayRepository;
	@Autowired SbomComponentOverlayAuditRepository overlayAuditRepository;

	static SbomComponentOverlayPatch supplier(String supplier) {
		return new SbomComponentOverlayPatch(supplier, null, null, null);
	}

	static SbomComponentOverlayPatch licence(String licence) {
		return new SbomComponentOverlayPatch(null, licence, null, null);
	}

	static SbomComponentOverlayPatch description(String description) {
		return new SbomComponentOverlayPatch(null, null, description, null);
	}

	/** One release of the org, for the vendored verbs, which read only its org. */
	ReleaseData releaseOfOrg() throws Exception {
		return sharedReleaseService.getReleaseData(componentReleaseIn(org, null).release()).orElseThrow();
	}

	SbomComponentOverlay overlayOf(String key) {
		return overlayRepository.findByOrgAndCanonicalPurl(org.getUuid(), key).orElseThrow();
	}

	boolean hasOverlay(String key) {
		return overlayRepository.findByOrgAndCanonicalPurl(org.getUuid(), key).isPresent();
	}

	List<SbomComponentOverlayAudit> auditOf(String key) {
		return overlayAuditRepository.findByOrgAndCanonicalPurlOrderByRevisionAsc(org.getUuid(), key);
	}

	/** Runs both writers released together, each on its own thread and so in its own transaction. */
	static void together(Runnable first, Runnable second) throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> writers = new ArrayList<>();
			for (Runnable writer : List.of(first, second)) {
				writers.add(pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					writer.run();
					return null;
				}));
			}
			for (Future<?> w : writers) w.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
	}

	static UUID random() {
		return UUID.randomUUID();
	}
}
