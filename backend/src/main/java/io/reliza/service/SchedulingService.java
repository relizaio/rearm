/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.service.oss.OssAnalyticsMetricsService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SchedulingService {

    /**
     * Per-task batch cap used by the every-minute scheduler tick. The point isn't to
     * gate throughput but to bound the in-memory working set so a backlog (thousands of
     * stale releases / pending BOMs) can't pull everything into one pass and OOM.
     * Each task picks the {@value} oldest unprocessed rows; the next tick grabs the
     * next batch. ORDER BY clauses on each underlying query guarantee forward progress.
     *
     * <p>Two tasks intentionally use different limits because they were already tuned:
     * {@code submitPendingArtifactsToDependencyTrack} caps at 20 per-org in SQL, and
     * {@code processPendingReconciles(50)} processes 50 sbom-component reconciles per
     * tick — both kept as-is.
     */
    private static final int SCHEDULER_TICK_BATCH_LIMIT = 20;

    @Autowired
    private DataSource dataSource;

    private final ConcurrentHashMap<AdvisoryLockKey, Connection> lockConnections = new ConcurrentHashMap<>();

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

    @Autowired
    SbomComponentService sbomComponentService;


    @Value("${relizaprops.enableDtrackCleanupScheduler}")
    private boolean enableDtrackCleanupScheduler;
    
    private Boolean getLock (AdvisoryLockKey alk) {
        try {
            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(true);
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, alk.getQueryVal());
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    boolean acquired = rs.getBoolean(1);
                    if (acquired) {
                        lockConnections.put(alk, conn);
                    } else {
                        conn.close();
                    }
                    return acquired;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire advisory lock " + alk, e);
        }
    }
    
    private void releaseLock (AdvisoryLockKey alk) {
        Connection conn = lockConnections.remove(alk);
        if (conn == null) return;
        try (conn; PreparedStatement stmt = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            stmt.setLong(1, alk.getQueryVal());
            stmt.execute();
        } catch (SQLException e) {
            log.error("Failed to release advisory lock {}", alk, e);
        }
    }

    @Scheduled(fixedRateString = "${relizaprops.rejectPendingReleasesRate}")
    public void scheduleCanceltPendingReleases(){
        try {
            Boolean lock = getLock(AdvisoryLockKey.REJECT_PENDING_RELEASE);
            log.debug("release reject lock acquired {}", lock);
			if (lock) {
				try {
                    releaseService.rejectPendingReleases(SCHEDULER_TICK_BATCH_LIMIT);
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
    
    /**
     * Every-minute drain. Originally just dependency-track work, now also
     * runs the SBOM-component reconcile queue — both kinds of work share
     * the {@code RESOLVE_DEPENDENCY_TRACK_STATUS} advisory lock so that at
     * most one replica processes them at any moment, and they run serially
     * within a tick.
     */
    @Scheduled(fixedRateString = "PT1M")
    public void scheduleResolveDependencyTrackStatus () {
        try {
            Boolean lock = getLock(AdvisoryLockKey.RESOLVE_DEPENDENCY_TRACK_STATUS);
            log.debug("resolve dependency track lock acquired {}", lock);
			if (lock) {
				try {
					log.debug("starting resolve DTrack loop");
					// Each step is isolated so one failing path doesn't skip the
					// rest. Without this, a transient error in (say) the metrics
					// compute would silently delay every other step's drain by a
					// full minute, and the SBOM reconcile queue could back up
					// indefinitely behind an unrelated upstream fault.

					// submitPendingArtifactsToDependencyTrack caps per-org at 20 inside SQL
					// (LIST_ARTIFACTS_PENDING_DTRACK_SUBMISSION) — already batch-bounded.
					try {
						artifactService.submitPendingArtifactsToDependencyTrack();
					} catch (Exception e) {
						log.error("submitPendingArtifactsToDependencyTrack failed", e);
					}

					try {
						artifactService.initialProcessArtifactsOnDependencyTrack(SCHEDULER_TICK_BATCH_LIMIT);
					} catch (Exception e) {
						log.error("initialProcessArtifactsOnDependencyTrack failed", e);
					}

					try {
						releaseService.computeMetricsForAllUnprocessedReleases(SCHEDULER_TICK_BATCH_LIMIT);
					} catch (Exception e) {
						log.error("computeMetricsForAllUnprocessedReleases failed", e);
					}

					// processPendingReconciles already takes an explicit batch limit; tuned higher (50)
					// because each row is a cheap dedupe and the operation is non-IO-bound.
					try {
						sbomComponentService.processPendingReconciles(50);
					} catch (Exception e) {
						log.error("processPendingReconciles failed", e);
					}
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
        if (!enableDtrackCleanupScheduler) {
            log.debug("DTrack project cleanup scheduler is disabled");
            return;
        }
        
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

    @Autowired
    ApiKeyAccessService apiKeyAccessService;

    /**
     * Daily retention sweep for the {@code api_key_access} audit table.
     * CI/CD pipelines polling against an org's keys can drive this table
     * to multi-GB scale; the per-request dedupe in
     * {@code ApiKeyAccessService.recordApiKeyAccess} caps inserts to ≤1
     * per (key, ip) per hour, and this sweep caps the historical tail
     * at 90 days. Runs at 04:30 UTC — well separated from the other
     * daily crons (00:00 analytics, 03:00 DTrack-project cleanup,
     * 21:50 DTrack sync) so the single-transaction DELETE doesn't
     * contend with them.
     */
    @Scheduled(cron="0 30 4 * * *")
    public void purgeOldApiKeyAccessRows() {
        try {
            Boolean lock = getLock(AdvisoryLockKey.PURGE_OLD_API_KEY_ACCESS);
            log.debug("api_key_access purge lock acquired {}", lock);
            if (lock) {
                try {
                    apiKeyAccessService.purgeOldAccessRows();
                } catch (Exception e) {
                    log.error("Exception in api_key_access retention sweep", e);
                } finally {
                    releaseLock(AdvisoryLockKey.PURGE_OLD_API_KEY_ACCESS);
                }
            }
        } catch (Exception e) {
            log.error("api_key_access retention sweep failed with an error", e);
        }
    }

}
