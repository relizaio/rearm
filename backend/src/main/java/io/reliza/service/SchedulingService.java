/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.reliza.common.AdvisoryLockKey;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SchedulingService {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    ReleaseService releaseService;

    @Autowired
	SystemInfoService systemInfoService;
    
    @Autowired
	ArtifactService artifactService;
    
    @Autowired
    AnalyticsMetricsService analyticsMetricsService;
    
    private Boolean getLock (AdvisoryLockKey alk) {
    	String query = "SELECT pg_try_advisory_lock(" + alk.getQueryVal() + ")";
        return (Boolean) entityManager.createNativeQuery(query).getSingleResult();
    }
    
    private void releaseLock (AdvisoryLockKey alk) {
    	String query = "SELECT pg_advisory_unlock(" + alk.getQueryVal() + ")";
        entityManager.createNativeQuery(query).getSingleResult();
    }

    @Scheduled(fixedRateString = "${relizaprops.rejectPendingReleasesRate}")
    public void scheduleCanceltPendingReleases(){
        try {
            Boolean lock = getLock(AdvisoryLockKey.REJECT_PENDING_RELEASE);
            log.debug("release reject lock acquired {}", lock);
			if (lock) {
				try {
                    releaseService.rejectPendingReleases();
				} catch (Exception e) {
					log.error("Exception in rejecting pending releases", e);
				} finally {
					releaseLock(AdvisoryLockKey.REJECT_PENDING_RELEASE);
				}
			}
		} catch (Exception e) {
			log.error("Cancel pending releases run failed with an error", e);
		}
    }
    
    @Scheduled(fixedRateString = "PT1M")
    public void scheduleResolveDependencyTrackStatus () {
        try {
            Boolean lock = getLock(AdvisoryLockKey.RESOLVE_DEPENDENCY_TRACK_STATUS);
            log.debug("resolve dependency track lock acquired {}", lock);
			if (lock) {
				try {
					artifactService.initialProcessArtifactsOnDependencyTrack();
					releaseService.computeMetricsForAllUnprocessedReleases();
				} catch (Exception e) {
					log.error("Exception in resolving dependency track", e);
				} finally {
					releaseLock(AdvisoryLockKey.RESOLVE_DEPENDENCY_TRACK_STATUS);
				}
			}
		} catch (Exception e) {
			log.error("Resolve dependency track run failed with an error", e);
		}
    }
    
    @Scheduled(cron="0 50 23 * * *") // once daily at the end of day
    public void computeAnalyticsMetrics () {
        try {
            Boolean lock = getLock(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS);
            log.debug("compute analytics metrics lock acquired {}", lock);
			if (lock) {
				try {
					analyticsMetricsService.computeAndRecordAnalyticsMetricsForAllOrgs();
				} catch (Exception e) {
					log.error("Exception in computing analytics metrics", e);
				} finally {
					releaseLock(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS);
				}
			}
		} catch (Exception e) {
			log.error("Compute analytics metrics run failed with an error", e);
		}
    }

}
