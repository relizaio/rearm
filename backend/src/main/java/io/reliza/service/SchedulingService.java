/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
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
     * <p>{@code processPendingReconciles(50)} intentionally uses a higher limit (50)
     * because each row is a cheap dedupe and the operation is non-IO-bound.
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

    @Autowired
    AgentSessionService agentSessionService;

    @Autowired
    SyntheticSbomService syntheticSbomService;


    // Gates the every-3h legacy per-artifact DTrack project phase-out
    // (deletes phased-out legacy projects on DTrack).
    @Value("${relizaprops.enableDtrackCleanupScheduler}")
    private boolean enableDtrackCleanupScheduler;

    // Number of legacy per-artifact DTrack projects phased out per tick. Kept low so
    // the migration doesn't flood DTrack's deletion queue; the every-3h cadence still
    // drains a meaningful volume over time.
    private static final int LEGACY_PHASEOUT_BATCH = 50;

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

					// Synthetic Dependency-Track is the only submission path. FRONT step: pull
					// BEAR-enriched licenses into sbom_components (stamps enriched_at) so the submit
					// gate can ship enriched components. No-op for non-BEAR orgs. Runs before submit
					// so newly-enriched components are eligible this same tick. The legacy per-artifact
					// submission is retired; its existing DTrack projects drain via
					// scheduleLegacyDtrackProjectPhaseOut.
					try {
						for (UUID orgUuid : integrationService.listOrgsWithDtrackIntegration()) {
							sbomComponentService.pullEnrichmentForOrg(orgUuid);
						}
					} catch (Exception e) {
						log.error("synthetic enrichment pull failed", e);
					}

					// Submit changed buckets (idempotent via content-hash), poll submitted
					// buckets, fan findings out.
					// TODO: at scale, gate submitOrg behind a per-org dirty marker to avoid the
					// every-tick matchable scan; fine for now.
					try {
						for (UUID orgUuid : integrationService.listOrgsWithDtrackIntegration()) {
							syntheticSbomService.submitOrg(orgUuid);
							syntheticSbomService.ingestOrgBuckets(orgUuid);
							syntheticSbomService.fanOutOrg(orgUuid);
						}
					} catch (Exception e) {
						log.error("synthetic DTrack submit/ingest/fan-out failed", e);
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
    
    @Scheduled(cron="0 15 3 * * *") // once daily 3:15 AM — separated from other daily crons
    public void scheduleSyntheticDtrackDailyResync() {
        try {
            Boolean lock = getLock(AdvisoryLockKey.SYNC_DEPENDENCY_TRACK_DATA);
            log.debug("synthetic DTrack daily resync lock acquired {}", lock);
            if (lock) {
                try {
                    // Re-pull findings for DTrack projects re-analysed since the last
                    // successful resync (catches newly-published advisories on stable
                    // component sets). Snapshot the cutoff before the run; advance it after.
                    java.time.ZonedDateTime since = systemInfoService.getLastDtrackSync();
                    if (since == null) since = java.time.ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);
                    java.time.ZonedDateTime runStart = java.time.ZonedDateTime.now();
                    for (UUID orgUuid : integrationService.listOrgsWithDtrackIntegration()) {
                        try {
                            syntheticSbomService.resyncOrg(orgUuid, since);
                        } catch (Exception e) {
                            log.error("Synthetic DTrack resync failed for org {}", orgUuid, e);
                        }
                    }
                    systemInfoService.setLastDtrackSync(runStart);
                } catch (Exception e) {
                    log.error("Exception in synthetic DTrack daily resync", e);
                } finally {
                    releaseLock(AdvisoryLockKey.SYNC_DEPENDENCY_TRACK_DATA);
                }
            }
        } catch (Exception e) {
            log.error("Synthetic DTrack daily resync run failed with an error", e);
        }
    }

    /**
     * Gradual phase-out of the legacy per-artifact DTrack projects now that
     * synthetic Dependency-Track is the only submission path. Each tick deletes up
     * to {@value #LEGACY_PHASEOUT_BATCH} unique legacy projects on DTrack (globally,
     * across orgs) and clears their references off the artifacts, so the old
     * one-project-per-artifact sprawl drains without flooding DTrack's deletion
     * queue. Runs every 3 hours under the cleanup advisory lock so no two replicas
     * delete concurrently.
     *
     * <p>No coverage gate: synthetic submission/fan-out migrates artifacts to the
     * bucket model far faster than this drains, so by the time a legacy project is
     * removed its components are already covered by synthetic findings.
     */
    @Scheduled(cron="0 30 */3 * * *") // every 3 hours at HH:30
    public void scheduleLegacyDtrackProjectPhaseOut() {
        if (!enableDtrackCleanupScheduler) {
            log.debug("Legacy DTrack project phase-out is disabled");
            return;
        }
        try {
            Boolean lock = getLock(AdvisoryLockKey.CLEANUP_DEPENDENCY_TRACK_PROJECTS);
            log.debug("legacy DTrack phase-out lock acquired {}", lock);
            if (lock) {
                try {
                    integrationService.phaseOutLegacyDtrackProjects(LEGACY_PHASEOUT_BATCH);
                } catch (Exception e) {
                    log.error("Exception in legacy DTrack project phase-out", e);
                } finally {
                    releaseLock(AdvisoryLockKey.CLEANUP_DEPENDENCY_TRACK_PROJECTS);
                }
            }
        } catch (Exception e) {
            log.error("Legacy DTrack project phase-out run failed with an error", e);
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

    /**
     * Autoclose OPEN agent sessions with no activity in the last 24h.
     * Sessions are heart-beated via the {@code sessionTouchProgrammatic}
     * mutation; the orientation contract instructs agents to touch every
     * 30-60s while polling. An OPEN session whose {@code lastActivityAt}
     * is older than the cutoff is either crashed, stalled, or abandoned —
     * leaving it OPEN distorts the dashboard's per-agent open-session
     * count and lets CEL gates that key on {@code session.status == "OPEN"}
     * keep firing against an agent that's no longer working.
     *
     * <p>BLOCKED sessions are intentionally skipped — they're awaiting
     * operator action, not an automatic close.
     *
     * <p>Runs at the top of every hour; advisory lock prevents concurrent
     * replicas from racing.
     */
    @Scheduled(cron="0 0 * * * *")
    public void autocloseIdleAgentSessions() {
        try {
            Boolean lock = getLock(AdvisoryLockKey.AUTOCLOSE_IDLE_AGENT_SESSIONS);
            log.debug("autoclose idle agent sessions lock acquired {}", lock);
            if (lock) {
                try {
                    java.time.ZonedDateTime cutoff = java.time.ZonedDateTime.now().minusHours(24);
                    int closed = agentSessionService.autoCloseIdleSessions(
                            cutoff, io.reliza.model.WhoUpdated.getAutoWhoUpdated());
                    if (closed > 0) {
                        log.info("Autoclosed {} idle agent session(s) (cutoff={})", closed, cutoff);
                    }
                } catch (Exception e) {
                    log.error("Exception in agent-session autoclose sweep", e);
                } finally {
                    releaseLock(AdvisoryLockKey.AUTOCLOSE_IDLE_AGENT_SESSIONS);
                }
            }
        } catch (Exception e) {
            log.error("agent-session autoclose sweep failed with an error", e);
        }
    }

}
