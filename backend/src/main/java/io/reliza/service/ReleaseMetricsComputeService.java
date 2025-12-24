/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.oss.OssReleaseService;

/**
 * Service for computing release metrics.
 * Separated from ReleaseService to ensure @Transactional annotations are properly applied via Spring AOP proxies.
 */
@Service
public class ReleaseMetricsComputeService {

	private static final Logger log = LoggerFactory.getLogger(ReleaseMetricsComputeService.class);

	@Autowired
	private ReleaseRepository repository;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private ArtifactGatherService artifactGatherService;

	@Autowired
	private ArtifactService artifactService;

	@Autowired
	private VulnAnalysisService vulnAnalysisService;

	@Autowired
	private OssReleaseService ossReleaseService;

	@Transactional
	public Optional<Release> getReleaseWriteLocked(UUID uuid) {
		return repository.findByIdWriteLocked(uuid);
	}

	@Transactional
	protected void computeReleaseMetricsOnRescan(Release r) {
		// Acquire write lock to prevent concurrent modifications
		Optional<Release> lockedRelease = getReleaseWriteLocked(r.getUuid());
		if (lockedRelease.isEmpty()) {
			log.warn("Release {} no longer exists, skipping metrics computation", r.getUuid());
			return;
		}
		r = lockedRelease.get();
		ZonedDateTime lastScanned = ZonedDateTime.now();
		var rd = ReleaseData.dataFromRecord(r);
		var originalMetrics = null != rd.getMetrics() ? rd.getMetrics().clone() : null;
		if (null == originalMetrics || null == originalMetrics.getLastScanned() || lastScanned.isAfter(originalMetrics.getLastScanned())) {
			ReleaseMetricsDto rmd = new ReleaseMetricsDto();
			var allReleaseArts = artifactGatherService.gatherReleaseArtifacts(rd);
			allReleaseArts.forEach(aid -> {
				var ad = artifactService.getArtifactData(aid);
				if (ad.isPresent()) {
					ArtifactData artifactData = ad.get();
					ReleaseMetricsDto artifactMetrics = artifactData.getMetrics();
					if (artifactMetrics != null) {
						// Set attributedAt to artifact creation date for findings that don't have it
						artifactMetrics.setAttributedAtFallback(artifactData.getCreatedDate());
						rmd.mergeWithByContent(artifactMetrics);
					}
				}
			});
			rmd.mergeWithByContent(rollUpProductReleaseMetrics(rd));
			vulnAnalysisService.processReleaseMetricsDto(rd.getOrg(), r.getUuid(), AnalysisScope.RELEASE, rmd);
			if (null == lastScanned) lastScanned = ZonedDateTime.now();
			rmd.setLastScanned(lastScanned);
			rd.setMetrics(rmd);
			Map<String,Object> recordData = Utils.dataToRecord(rd);
			ossReleaseService.saveRelease(r, recordData, WhoUpdated.getAutoWhoUpdated());
		}
	}

	@Transactional
	protected void computeReleaseMetricsOnNonRescan(Release r) {
		// Acquire write lock to prevent concurrent modifications
		Optional<Release> lockedRelease = getReleaseWriteLocked(r.getUuid());
		if (lockedRelease.isEmpty()) {
			log.warn("Release {} no longer exists, skipping metrics computation", r.getUuid());
			return;
		}
		r = lockedRelease.get();
		var rd = ReleaseData.dataFromRecord(r);
		if (null != rd.getMetrics()) {
			ReleaseMetricsDto originalMetrics = rd.getMetrics();
			ReleaseMetricsDto clonedMetrics = originalMetrics.clone();
			vulnAnalysisService.processReleaseMetricsDto(rd.getOrg(), r.getUuid(), AnalysisScope.RELEASE, clonedMetrics);
			if (!clonedMetrics.equals(originalMetrics)) {
				rd.setMetrics(clonedMetrics);
				Map<String,Object> recordData = Utils.dataToRecord(rd);
				ossReleaseService.saveRelease(r, recordData, WhoUpdated.getAutoWhoUpdated());
			}
		}
	}

	private ReleaseMetricsDto rollUpProductReleaseMetrics(ReleaseData rd) {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rd.getParentReleases().forEach(r -> {
			try {
				ReleaseData parentRd = sharedReleaseService
						.getReleaseData(r.getRelease(), rd.getOrg()).get();
				ReleaseMetricsDto parentReleaseMetrics = parentRd.getMetrics();
				parentReleaseMetrics.enrichSourcesWithRelease(r.getRelease());
				rmd.mergeWithByContent(parentReleaseMetrics);
				rmd.computeMetricsFromFacts();
			} catch (RelizaException e) {
				log.error("Error on getting parent release", e);
			}
		});
		vulnAnalysisService.processReleaseMetricsDto(rd.getOrg(), rd.getUuid(), AnalysisScope.RELEASE, rmd);
		return rmd;
	}
}
