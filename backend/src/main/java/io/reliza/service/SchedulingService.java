/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.service.oss.OssAnalyticsMetricsService;
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
    
    @Autowired
    OssAnalyticsMetricsService ossAnalyticsMetricsService;
    
    @Autowired
    OrganizationService organizationService;
    
    @Autowired
    IntegrationService integrationService;
    
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
    
    @Scheduled(cron="1 0 0 * * *") // once daily at 00:00:01 (1 second past midnight)
    public void computeAnalyticsMetrics () {
        try {
            Boolean lock = getLock(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS);
            log.debug("compute analytics metrics lock acquired {}", lock);
			if (lock) {
				try {
					ossAnalyticsMetricsService.computeAndRecordAnalyticsMetricsForAllOrgs();
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
    
    @Scheduled(cron="0 50 21 * * *") // once daily at the end of day
    public void syncDependencyTrackData () {
        try {
            Boolean lock = getLock(AdvisoryLockKey.SYNC_DEPENDENCY_TRACK_DATA);
            log.debug("sync dependency track data lock acquired {}", lock);
			if (lock) {
				try {
					// Get all organizations and sync dependency track data for each
					var allOrgs = organizationService.listAllOrganizationData();
					log.debug("Starting dependency track sync for {} organizations", allOrgs.size());
					
					int totalProcessed = 0;
					for (var org : allOrgs) {
						try {
							int processed = artifactService.syncUnsyncedDependencyTrackData(org.getUuid());
							totalProcessed += processed;
							log.debug("Processed {} artifacts for org {}", processed, org.getUuid());
						} catch (Exception e) {
							log.error("Error syncing dependency track data for org {}: {}", org.getUuid(), e.getMessage(), e);
						}
					}
					
					log.info("Completed dependency track sync for all organizations. Total artifacts processed: {}", totalProcessed);
				} catch (Exception e) {
					log.error("Exception in syncing dependency track data", e);
				} finally {
					releaseLock(AdvisoryLockKey.SYNC_DEPENDENCY_TRACK_DATA);
				}
			}
		} catch (Exception e) {
			log.error("Sync dependency track data run failed with an error", e);
		}
    }
    
    @Scheduled(cron="0 0 3 * * *") // once daily 3:00 AM
    public void scheduleDependencyTrackProjectCleanup() {
        log.info("Initiating DTrack project cleanup scheduler");
        try {
            Boolean lock = getLock(AdvisoryLockKey.CLEANUP_DEPENDENCY_TRACK_PROJECTS);
            log.debug("DTrack project cleanup lock acquired {}", lock);
            
            if (lock) {
                try {
                    log.info("Starting scheduled DTrack project cleanup");
                    
                    var allOrgs = organizationService.listAllOrganizationData();
                    
                    int totalDeleted = 0;
                    int totalFailed = 0;
                    
                    for (var org : allOrgs) {
                        try {
                            var result = integrationService.cleanupArchivedDtrackProjects(org.getUuid());
                            
                            totalDeleted += result.projectsDeleted();
                            totalFailed += result.projectsFailed();
                            
                            log.info("Cleaned up {} DTrack projects for org {}", 
                                result.projectsDeleted(), org.getUuid());
                            
                        } catch (Exception e) {
                            log.error("Error cleaning up DTrack projects for org " + org.getUuid(), e);
                        }
                    }
                    
                    log.info("Completed DTrack project cleanup: deleted={}, failed={}", 
                        totalDeleted, totalFailed);
                    
                } catch (Exception e) {
                    log.error("Exception in DTrack project cleanup", e);
                } finally {
                    releaseLock(AdvisoryLockKey.CLEANUP_DEPENDENCY_TRACK_PROJECTS);
                }
            }
        } catch (Exception e) {
            log.error("DTrack project cleanup run failed with an error", e);
        }
    }

}
