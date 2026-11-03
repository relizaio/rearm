/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.cyclonedx.Version;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.model.Metadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import io.reliza.common.CdxLicenseUtil;
import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactNotScannable;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ArtifactScanState;
import io.reliza.model.AnalysisScope;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.CanonicalMapFlowControl;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentData;
import io.reliza.model.ScanBlockerEnrichmentState;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.SystemInfoData.FanOutWalkState;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactScanStatusDto;
import io.reliza.model.dto.ArtifactScanStatusDto.ScanBlockerDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository.ComponentUuidByPurl;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.service.DTrackService.SyntheticFindings;
import lombok.extern.slf4j.Slf4j;

/**
 * Synthetic Dependency-Track submission on the converged identity model.
 *
 * Replaces per-artifact DTrack projects with deduped buckets of <= {@link #BUCKET_SIZE}
 * matchable components submitted ONCE per org. sbom_components is the dedup driver;
 * bucket membership is derived from a deterministic ordering of the org's matchable
 * components, so the per-bucket content hash (over the sorted canonical_purls) is
 * stable across builds. Findings are fetched once per bucket and fanned out to the
 * artifacts that contain each component, bumping artifact lastScanned so the existing
 * BY_ARTIFACT_DIRECT release recompute attributes them up to releases.
 *
 * Fan-out candidates are change-driven (TPF-114, TPF-137): a canonical is processed when
 * no pass has stamped it yet (flow_control.fanOutAt absent on one of its map rows) or when
 * it holds a component whose findings or coverage changed at a bucket save after its stamp
 * (the bucket's flow_control.fanOutChanged), found by a resumable keyset walk over
 * artifact_sbom_components in fixed uuid-space slices. A processed candidate is stamped
 * and drops out of both candidate sets until one of its components changes again or a gate
 * writer requeues it; a canonical whose findings could not be applied is requeued, not
 * stamped. A canonical with no matchable component is marked
 * not scannable on its artifacts (metrics.notScannable, TPF-125) instead of scanned; it
 * rotates out like any processed candidate.
 *
 * See backend/ai-plans/synthetic_dtrack_identity/00_design.md for the full design.
 */
@Service
@Slf4j
public class SyntheticSbomService {

	/** Max components per synthetic SBOM / DTrack project. */
	static final int BUCKET_SIZE = 500;

	/**
	 * Fan-out candidates (canonicals) processed per org per tick. Each candidate
	 * costs two light per-canonical reads plus a metrics write per map row and one
	 * stamp, so this bounds the tick's work. A processed candidate is stamped
	 * (flow_control.fanOutAt, gate pass, gate skip or not-scannable alike) and drops out of both
	 * candidate sets until a covering bucket moves or a gate writer requeues it
	 * (TPF-114); anything left over is picked up on the next tick, because an
	 * unstamped canonical stays a candidate.
	 */
	static final int FANOUT_BATCH_LIMIT = 500;

	/**
	 * Blocker rows one artifactScanStatus answer lists (TPF-121); blockerTotal
	 * keeps the uncapped count and blockersTruncated says the list was cut.
	 */
	static final int SCAN_BLOCKER_CAP = 50;

	/**
	 * The part of a fan-out batch the never-stamped candidates (A) leave to the
	 * change-driven ones (B) when (B) has work (TPF-114): (A) takes at most
	 * FANOUT_BATCH_LIMIT - FANOUT_CHANGE_RESERVE first, (B) the room after it,
	 * and (A)'s surplus fills whatever (B) left.
	 */
	static final int FANOUT_CHANGE_RESERVE = 100;

	/**
	 * The time the change-driven walk of one fan-out pass may spend selecting
	 * (TPF-114). It is checked between slice statements (TPF-137), so it bounds
	 * the number of statements per tick, not the length of one: a statement runs
	 * to the client timeout (120 s) and, when it fails, is caught per slice
	 * (TPF-136, TPF-137). A drain is spread over ticks and resumes from the
	 * persisted cursor; a cold start with no stamped row skips the walk.
	 */
	static final Duration FANOUT_SELECT_BUDGET = Duration.ofSeconds(20);

	/**
	 * The largest changed-component set a drain binds as the slice statement's
	 * uuid[] (TPF-137): twenty buckets' worth of entries. Above it the drain walks
	 * in full mode (every canonical of each slice goes to the stamp check), which
	 * the slice bounds the same way.
	 */
	static final int FANOUT_CHANGED_SET_CAP = 10_000;

	/**
	 * A matchable component unbucketed for longer than this is treated as a
	 * stall, not transience: bucket assignment is local and immediate, so the
	 * only way to stay unbucketed is for submitOrg to keep excluding it.
	 */
	static final Duration STALL_COMPONENT_AGE = Duration.ofHours(2);

	/** A bucket that has not reached INGESTED in this long is stalled too. */
	static final Duration STALL_BUCKET_AGE = Duration.ofHours(2);

	/** Minimum gap between stall reports for the same org, so a long-running
	 *  stall produces a periodic signal rather than one line per tick. */
	static final Duration STALL_LOG_INTERVAL = Duration.ofHours(1);

	private final Map<UUID, Instant> lastStallReport = new ConcurrentHashMap<>();

	/**
	 * Per-replica cache of the per-org fan-out drained-at watermark (TPF-114),
	 * persisted in SystemInfoData.fanOutDrainedAt. A ConcurrentHashMap only
	 * because different orgs run on different threads: every fanOutOrg caller
	 * holds the org's cycle lock.
	 */
	private final Map<UUID, ZonedDateTime> fanOutDrainedAt = new ConcurrentHashMap<>();

	/** Clock for FANOUT_SELECT_BUDGET; a field so a test can drive it. */
	private LongSupplier nanoTime = System::nanoTime;

	/**
	 * Orgs whose BEAR config could not be read on their last submit pass. The
	 * WARN is logged when an org enters the set, not on every tick of an
	 * outage; the org leaves it on the next successful read.
	 */
	private final Set<UUID> bearConfigUnknown = ConcurrentHashMap.newKeySet();

	/**
	 * Orgs whose change-driven walk is paused because the metrics compute backlog
	 * is at or above ReleaseService.METRICS_BACKLOG_PRESSURE_THRESHOLD (TPF-137):
	 * one INFO line when an org enters the set and one when it leaves it.
	 */
	private final Set<UUID> walkPausedForBacklog = ConcurrentHashMap.newKeySet();

	/** Cap on CPEs emitted per component (cpe[0] on the primary; companions TODO). */
	static final int MAX_CPES = 3;

	/**
	 * How long a resync or force re-upload waits for the org's cycle lock. The
	 * tick holds it for one org's submit, ingest and fan-out, and another resync
	 * for its DTrack re-pull, so a wait this long means a holder is stuck.
	 */
	static final Duration ORG_LOCK_WAIT = Duration.ofMinutes(15);

	/**
	 * Orgs with a manual resync or force re-upload running on this node. A repeat
	 * click drops out instead of parking one of the few async threads on the
	 * org's cycle lock for the length of the first run.
	 */
	private final Set<UUID> manualResyncInFlight = ConcurrentHashMap.newKeySet();
	private final Set<UUID> forceReuploadInFlight = ConcurrentHashMap.newKeySet();

	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SyntheticDtrackBucketRepository bucketRepository;
	@Autowired private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired @org.springframework.context.annotation.Lazy private SbomComponentService sbomComponentService;
	@Autowired private SharedArtifactService sharedArtifactService;
	@Autowired private DTrackService dTrackService;
	@Autowired private RebomService rebomService;
	@Autowired @Lazy private IntegrationService integrationService;
	@Autowired @Lazy private VulnAnalysisService vulnAnalysisService;
	@Autowired private SyntheticOrgLock syntheticOrgLock;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private FanOutWalkRepository fanOutWalkRepository;

	// ===================================================================
	// Per-org orchestration
	// ===================================================================

	/**
	 * Full cycle for one org: (re)submit changed buckets, ingest ready buckets,
	 * re-pull findings for buckets DTrack has re-analysed since {@code since}, then
	 * fan out. {@code since} bounds the "what changed on DTrack" probe -- pass the
	 * last successful sync time for the daily run, or an epoch instant to force a
	 * full re-check (manual trigger). Runs under the org's cycle lock
	 * ({@link SyntheticOrgLock}), so it never overlaps the tick for the same org.
	 *
	 * @return false when the cycle was skipped because the lock stayed held, so
	 *   the daily run knows not to advance its sync time past this org's window.
	 */
	public boolean resyncOrg(UUID orgUuid, ZonedDateTime since) {
		return withOrgLock(orgUuid, "Synthetic DTrack resync", () -> {
			try {
				submitOrg(orgUuid);
			} catch (Exception e) {
				log.error("Synthetic DTrack submit failed for org {}", orgUuid, e);
			}
			try {
				ingestOrgBuckets(orgUuid);
			} catch (Exception e) {
				log.error("Synthetic DTrack ingest failed for org {}", orgUuid, e);
			}
			try {
				resyncFindingsForOrg(orgUuid, since);
			} catch (Exception e) {
				log.error("Synthetic DTrack findings resync failed for org {}", orgUuid, e);
			}
			try {
				fanOutOrg(orgUuid);
			} catch (Exception e) {
				log.error("Synthetic DTrack fan-out failed for org {}", orgUuid, e);
			}
		});
	}

	/**
	 * Manual, on-demand resync for one org -- backs the operator "refresh from
	 * DTrack" action. Uses an epoch cutoff so every bucket project is re-checked,
	 * making an operator-triggered refresh reliably pick up findings just changed in
	 * DTrack. Async so the GraphQL mutation returns immediately.
	 */
	@org.springframework.scheduling.annotation.Async
	public void resyncOrgManualAsync(UUID orgUuid) {
		runOncePerOrg(manualResyncInFlight, orgUuid, "Manual synthetic DTrack resync",
				() -> resyncOrg(orgUuid, ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
	}

	/**
	 * Admin "force re-upload to DTrack" action: re-submit every synthetic bucket for
	 * the org regardless of the content-hash idempotency check -- into its existing
	 * DTrack project, or a fresh one if the project is gone. DTrack then re-analyses
	 * and the next ingest re-pulls findings in the current (alias-collapsed) shape,
	 * migrating buckets stored before that fix. Heavier than {@link #resyncOrgManualAsync}
	 * (full re-analysis + a brief SUBMITTED window where coverage drops) -- for operator
	 * recovery, not routine. Async so the GraphQL mutation returns immediately.
	 */
	@org.springframework.scheduling.annotation.Async
	public void forceReuploadOrgAsync(UUID orgUuid) {
		String action = "Force re-upload to DTrack";
		runOncePerOrg(forceReuploadInFlight, orgUuid, action,
				() -> withOrgLock(orgUuid, action, () -> {
					try {
						submitOrg(orgUuid, true);
					} catch (Exception e) {
						log.error("Force re-upload (submit) failed for org {}", orgUuid, e);
					}
					try {
						ingestOrgBuckets(orgUuid);
					} catch (Exception e) {
						log.error("Force re-upload (ingest) failed for org {}", orgUuid, e);
					}
					try {
						fanOutOrg(orgUuid);
					} catch (Exception e) {
						log.error("Force re-upload (fan-out) failed for org {}", orgUuid, e);
					}
				}));
	}

	/**
	 * Runs one org's cycle under its lock, waiting up to {@link #ORG_LOCK_WAIT}.
	 *
	 * @return whether the cycle ran. A timeout or interrupt skips it with an
	 *   ERROR: the requested resync or re-upload did not happen.
	 */
	private boolean withOrgLock(UUID orgUuid, String action, Runnable cycle) {
		try (SyntheticOrgLock.Lease lease = syntheticOrgLock.acquire(orgUuid, ORG_LOCK_WAIT)) {
			if (lease == null) {
				log.error("{} skipped for org {}: another resync or re-upload of this org held its "
						+ "synthetic cycle lock for {}", action, orgUuid, ORG_LOCK_WAIT);
				return false;
			}
			cycle.run();
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("{} skipped for org {}: interrupted while waiting for its synthetic cycle lock",
					action, orgUuid);
			return false;
		}
	}

	/** Runs {@code action} unless the same action is already running for the org on this node. */
	private void runOncePerOrg(Set<UUID> inFlight, UUID orgUuid, String action, Runnable run) {
		if (!inFlight.add(orgUuid)) {
			log.info("{} for org {} is already running on this node; this request is dropped", action, orgUuid);
			return;
		}
		try {
			run.run();
		} finally {
			inFlight.remove(orgUuid);
		}
	}

	/**
	 * Whether two stored {@code canonical -> {kind: [finding]}} maps hold the
	 * same findings. Each fetch stamps every finding's {@code attributedAt} with
	 * the fetch time, so plain equality never holds between two pulls of an
	 * unchanged project. The resync would then rewrite every re-pulled bucket
	 * and bump its date, which sends the whole org back through fan-out. This
	 * comparison ignores {@code attributedAt} and list order, and compares
	 * numbers by value: a freshly converted map and one read back from JSONB can
	 * hold the same date as a {@code BigDecimal} and a {@code Double}. A
	 * canonical with no findings counts the same as an absent one.
	 */
	static boolean sameFindings(Map<String, Object> a, Map<String, Object> b) {
		return findingsFingerprint(a).equals(findingsFingerprint(b));
	}

	/**
	 * Canonical purls whose findings differ between two stored bucket payloads (TPF-137): a purl
	 * present in one and not the other, or with a different findingsFingerprint entry. Order and
	 * attributedAt are ignored as sameFindings ignores them; a purl with no findings counts as
	 * absent.
	 */
	static Set<String> changedCanonicalPurls(Map<String, Object> before, Map<String, Object> after) {
		Map<String, Map<Object, Integer>> a = findingsFingerprint(before);
		Map<String, Map<Object, Integer>> b = findingsFingerprint(after);
		Set<String> changed = new TreeSet<>();
		for (Map.Entry<String, Map<Object, Integer>> e : a.entrySet()) {
			if (!e.getValue().equals(b.get(e.getKey()))) changed.add(e.getKey());
		}
		for (String k : b.keySet()) if (!a.containsKey(k)) changed.add(k);
		return changed;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Map<Object, Integer>> findingsFingerprint(Map<String, Object> byCanonical) {
		Map<String, Map<Object, Integer>> out = new HashMap<>();
		if (byCanonical == null) return out;
		for (Map.Entry<String, Object> e : byCanonical.entrySet()) {
			if (!(e.getValue() instanceof Map)) continue;
			Map<Object, Integer> counts = new HashMap<>();
			for (Map.Entry<String, Object> kind : ((Map<String, Object>) e.getValue()).entrySet()) {
				if (!(kind.getValue() instanceof List)) continue;
				for (Object finding : (List<Object>) kind.getValue()) {
					if (finding == null) continue;
					Object key = finding;
					if (finding instanceof Map) {
						Map<String, Object> copy = new HashMap<>((Map<String, Object>) finding);
						copy.remove(CommonVariables.ATTRIBUTED_AT_FIELD);
						key = comparable(copy);
					}
					counts.merge(List.of(kind.getKey(), key), 1, Integer::sum);
				}
			}
			if (!counts.isEmpty()) out.put(e.getKey(), counts);
		}
		return out;
	}

	/**
	 * A stored JSON value in a form whose equality ignores list order (the
	 * lists come from sets) and number representation.
	 */
	@SuppressWarnings("unchecked")
	private static Object comparable(Object value) {
		if (value instanceof Map) {
			Map<Object, Object> out = new HashMap<>();
			((Map<Object, Object>) value).forEach((k, v) -> out.put(k, comparable(v)));
			return out;
		}
		if (value instanceof List) {
			Map<Object, Integer> counts = new HashMap<>();
			for (Object item : (List<Object>) value) {
				counts.merge(item == null ? "" : comparable(item), 1, Integer::sum);
			}
			return counts;
		}
		if (value instanceof Number n) return n.doubleValue();
		return value;
	}

	/**
	 * Re-pull findings for INGESTED buckets whose DTrack project has been
	 * re-analysed since {@code since} (new advisories published against
	 * already-scanned components -- the time-varying findings that the legacy daily
	 * sync caught and the every-minute submit/ingest does not, since it only acts on
	 * membership changes). Uses the same change-detection as the legacy sync
	 * ({@code retrieveUnsyncedDtrackProjects}); only buckets whose findings actually
	 * differ are rewritten, and fan-out (via the idempotent artifact write) then
	 * updates only the affected components.
	 */
	void resyncFindingsForOrg(UUID orgUuid, ZonedDateTime since) {
		java.util.Set<UUID> changedProjects = integrationService
				.retrieveUnsyncedDtrackProjects(orgUuid, since);
		if (changedProjects.isEmpty()) return;
		int refreshed = 0;
		for (SyntheticDtrackBucket bucket : bucketRepository.findByOrg(orgUuid)) {
			if (IngestState.INGESTED != bucket.getIngestState()) continue;
			if (bucket.getDtrackProjectUuid() == null
					|| !changedProjects.contains(bucket.getDtrackProjectUuid())) continue;
			try {
				SyntheticFindings findings = dTrackService.syntheticFetchFindings(
						orgUuid, bucket.getDtrackProjectUuid());
				Map<String, Object> newByCanonical = toFindingsByCanonical(orgUuid, findings, bucket.getRefMap());
				Map<String, Object> before = bucket.getFindings();
				if (sameFindings(newByCanonical, before)) continue; // no change
				bucket.setFindings(newByCanonical);
				bucket.setLastIngested(ZonedDateTime.now());
				recordFanOutChanges(bucket, membershipPurlsOf(bucket),
						changedCanonicalPurls(before, newByCanonical), nowMicros());
				bucketRepository.save(bucket);
				refreshed++;
			} catch (Exception e) {
				log.error("Failed to resync findings for synthetic bucket {} (org {})",
						bucket.getBucketIndex(), orgUuid, e);
			}
		}
		if (refreshed > 0) {
			log.info("Synthetic findings resync: refreshed {} bucket(s) for org {}", refreshed, orgUuid);
		}
	}

	// ===================================================================
	// Submit
	// ===================================================================

	/**
	 * Cheap idle-skip gate for the scheduler: true when the org has synthetic-DTrack
	 * work this tick -- a matchable component not yet assigned to a bucket (new or
	 * just-enriched), or a bucket that failed to submit and needs retry. When false,
	 * the scheduler skips {@code pullEnrichmentForOrg} + {@code submitOrg}, avoiding
	 * the rebom config probe, the full matchable scan, and the per-bucket re-hash for
	 * an idle org.
	 *
	 * <p>Two indexed {@code EXISTS} probes; bear-agnostic so the gate itself needs no
	 * rebom call. It does NOT detect a deletion that shrinks a bucket without leaving
	 * an unassigned row (rare -- canonicals are sticky; self-heals on the next change).
	 * {@code ingestOrgBuckets} and {@code fanOutOrg} still run every tick regardless,
	 * so in-flight (SUBMITTED) buckets always advance to INGESTED.
	 */
	boolean hasPendingSyntheticWork(UUID orgUuid) {
		return sbomComponentRepository.existsUnbucketedMatchableByOrg(orgUuid.toString())
				// PENDING has exactly one writer: an in-progress retirement whose
				// DTrack project deletion hasn't succeeded yet. The retry lives in
				// submitOrg's retire pass, so the gate must wake for it -- on an
				// otherwise-idle org the retirement would never complete.
				|| bucketRepository.existsByOrgAndIngestState(orgUuid, IngestState.PENDING)
				|| bucketRepository.existsByOrgAndIngestState(orgUuid, IngestState.FAILED);
	}

	/**
	 * Group the org's matchable components by their STICKY bucket assignment and
	 * (re)submit any bucket whose membership changed since last submission
	 * (content-hash mismatch).
	 *
	 * <p>Buckets are assigned per-component and never reshuffled (see
	 * {@link #assignStickyBuckets}). A new or newly-enriched component only changes
	 * the one bucket it lands in, so unrelated buckets keep their content hash and
	 * are not re-sent to DTrack -- unlike the prior positional slicing, where a
	 * single insertion shifted every later component's position and re-submitted
	 * every downstream bucket.
	 */
	void submitOrg(UUID orgUuid) {
		submitOrg(orgUuid, false);
	}

	/**
	 * @param force when true, re-upload every bucket to DTrack regardless of the
	 *   content-hash idempotency check -- used by the admin "force re-upload" action
	 *   (re-submit each bucket into its existing project, or a new one if absent).
	 */
	void submitOrg(UUID orgUuid, boolean force) {
		// Gate by BEAR config: a BEAR-configured org ships a component only once the
		// enrichment puller has pulled its enriched licenses (enriched_at set), so
		// DTrack receives enriched licenses. A non-BEAR org has no enrichment to wait
		// on, so every matchable component ships immediately.
		boolean bearConfigured;
		try {
			bearConfigured = rebomService.isEnrichmentConfigured(orgUuid);
		} catch (Exception e) {
			// Unknown config (rebom unreachable): skip the whole pass, submission
			// and bucket retirement alike, until rebom answers. Either assumption
			// does damage: "configured" on a non-BEAR org finds no enriched
			// component, retires every bucket and deletes its Dependency-Track
			// project; "not configured" on a BEAR org ships un-enriched licenses.
			// Nothing is lost by waiting for the next tick.
			// A forced re-upload is someone's explicit request: say so every time.
			if (bearConfigUnknown.add(orgUuid) || force) {
				log.warn("submitOrg: unable to determine BEAR config for org {}; skipping submission and "
						+ "bucket retirement until rebom answers: {}", orgUuid, e.getMessage());
			} else {
				log.debug("submitOrg: BEAR config for org {} still unknown, pass skipped: {}",
						orgUuid, e.getMessage());
			}
			return;
		}
		if (bearConfigUnknown.remove(orgUuid)) {
			log.info("submitOrg: BEAR config for org {} readable again; submission resumes", orgUuid);
		}
		List<SbomComponent> matchable = bearConfigured
				? sbomComponentRepository.findEnrichedMatchableByOrgOrdered(orgUuid.toString())
				: sbomComponentRepository.findMatchableByOrgOrdered(orgUuid.toString());
		if (matchable.isEmpty()) {
			// Still retire: a population that went ENTIRELY unmatchable (e.g.
			// every row turned enrichment-terminal) leaves zero live buckets,
			// and returning before retirement would strand every bucket -- and
			// its DTrack project -- forever.
			retireEmptyBuckets(orgUuid, Set.of());
			return;
		}

		// Assign any not-yet-bucketed components first-fit, then group by the
		// persisted index. TreeMap so bucket order is deterministic.
		assignStickyBuckets(orgUuid, matchable);
		Map<Integer, List<SbomComponent>> byBucket = new TreeMap<>();
		for (SbomComponent sc : matchable) {
			Integer bi = sc.getSyntheticBucketIndex();
			if (bi == null) continue; // assignment failed this tick; retry next
			byBucket.computeIfAbsent(bi, k -> new ArrayList<>()).add(sc);
		}

		for (Map.Entry<Integer, List<SbomComponent>> e : byBucket.entrySet()) {
			submitBucket(orgUuid, e.getKey(), e.getValue(), force);
		}

		// A bucket whose every member was deleted (rare -- canonicals are sticky)
		// would otherwise keep contributing stale coverage in fan-out. Retire it.
		retireEmptyBuckets(orgUuid, byBucket.keySet());
	}

	/**
	 * Assign a sticky bucket index to every matchable component that lacks one,
	 * first-fit into the lowest-indexed bucket with spare capacity (else a new
	 * bucket). Existing assignments are never changed, so adding a component never
	 * moves another. No-op (no DB writes) once everything is assigned -- the common
	 * steady-state path. Deterministic fill order (by canonical purl) so the very
	 * first pass over a pre-existing org reproduces the old positional grouping,
	 * avoiding a migration re-submit storm.
	 */
	private void assignStickyBuckets(UUID orgUuid, List<SbomComponent> matchable) {
		List<SbomComponent> unassigned = new ArrayList<>();
		Map<Integer, Integer> counts = new HashMap<>();
		int maxIndex = -1;
		for (SbomComponent sc : matchable) {
			Integer bi = sc.getSyntheticBucketIndex();
			if (bi == null) {
				unassigned.add(sc);
			} else {
				counts.merge(bi, 1, Integer::sum);
				if (bi > maxIndex) maxIndex = bi;
			}
		}
		if (unassigned.isEmpty()) return;
		unassigned.sort(Comparator.comparing(SbomComponent::getCanonicalPurl));

		int nextNewIndex = maxIndex + 1;
		for (SbomComponent sc : unassigned) {
			int target = -1;
			for (int bi = 0; bi <= maxIndex; bi++) {
				if (counts.getOrDefault(bi, 0) < BUCKET_SIZE) { target = bi; break; }
			}
			if (target < 0) { target = nextNewIndex++; maxIndex = target; }
			sc.setSyntheticBucketIndex(target);
			counts.merge(target, 1, Integer::sum);
			sbomComponentRepository.save(sc);
		}
	}

	/** (Re)submit a single bucket if its membership changed since last submission. */
	private void submitBucket(UUID orgUuid, int bucketIndex, List<SbomComponent> members, boolean force) {
		// Deterministic order so the content hash and emitted BOM are stable.
		List<SbomComponent> slice = new ArrayList<>(members);
		slice.sort(Comparator.comparing(SbomComponent::getCanonicalPurl));
		String contentHash = contentHash(slice);

		SyntheticDtrackBucket bucket = bucketRepository
				.findByOrgAndBucketIndex(orgUuid, bucketIndex)
				.orElseGet(() -> {
					SyntheticDtrackBucket b = new SyntheticDtrackBucket();
					b.setOrg(orgUuid);
					b.setBucketIndex(bucketIndex);
					return b;
				});
		bucket.setBucketIndex(bucketIndex);

		// Idempotency: skip re-upload only when membership is unchanged AND the
		// bucket already submitted/ingested successfully. PENDING/FAILED retry.
		// Never hash the generated BOM JSON (metadata.timestamp churns).
		// force=true (admin re-upload) bypasses this so every bucket is re-sent.
		if (!force
				&& contentHash.equals(bucket.getContentHash())
				&& bucket.getDtrackProjectUuid() != null
				&& (IngestState.SUBMITTED == bucket.getIngestState()
					|| IngestState.INGESTED == bucket.getIngestState())) {
			return;
		}

		// The membership as loaded, before a successful upload replaces the refMap (TPF-137 D25).
		Set<String> membershipAsLoaded = membershipPurlsOf(bucket);
		try {
			Map<String, Object> refMap = new LinkedHashMap<>();
			JsonNode bomJson = buildBom(orgUuid, slice, bucketIndex, refMap);
			String projectName = "rearm-synthetic-" + orgUuid;
			String projectVersion = "bucket-" + bucketIndex;
			UUID projectId = bucket.getDtrackProjectUuid() != null
					? bucket.getDtrackProjectUuid()
					: dTrackService.syntheticGetOrCreateProject(orgUuid, projectName, projectVersion);
			String token = dTrackService.syntheticUploadBom(
					orgUuid, projectId, bomJson, projectName, projectVersion);

			bucket.setDtrackProjectUuid(projectId);
			bucket.setContentHash(contentHash);
			bucket.setRefMap(refMap);
			bucket.setIngestState(IngestState.SUBMITTED);
			bucket.setLastSubmitted(ZonedDateTime.now());
			// Findings do not change at submit: on a recorded bucket this only moves recordedAt.
			recordFanOutChanges(bucket, membershipAsLoaded, Set.of(), nowMicros());
			bucket.getRefMap().put("__token", token);
			bucketRepository.save(bucket);
		} catch (Exception e) {
			log.error("Failed to submit synthetic bucket {} for org {}", bucketIndex, orgUuid, e);
			bucket.setIngestState(IngestState.FAILED);
			recordFanOutChanges(bucket, membershipAsLoaded, Set.of(), nowMicros());
			bucketRepository.save(bucket);
		}
	}

	/**
	 * Retire buckets that no longer have any matchable members: clear local
	 * coverage (so fan-out stops counting their stale purls as scanned),
	 * delete the bucket's Dependency-Track project, and converge to
	 * INGESTED. Self-healing: a failed DTrack deletion stays PENDING with its
	 * project ref intact and is retried on every subsequent tick. Cheap in
	 * steady state: fully-converged buckets are skipped without writes.
	 */
	private void retireEmptyBuckets(UUID orgUuid, Set<Integer> liveIndices) {
		for (SyntheticDtrackBucket b : bucketRepository.findByOrg(orgUuid)) {
			if (liveIndices.contains(b.getBucketIndex())) continue;
			boolean locallyClear = (b.getRefMap() == null || b.getRefMap().isEmpty())
					&& (b.getFindings() == null || b.getFindings().isEmpty())
					&& b.getContentHash() == null;
			// Fully retired: local coverage cleared, DTrack project gone,
			// terminal state. Only this converged shape is skipped -- a bucket
			// that is empty but still holds a project ref (or a non-terminal
			// state) is a retirement in progress and must be retried.
			if (locallyClear && b.getDtrackProjectUuid() == null
					&& IngestState.INGESTED == b.getIngestState()) {
				continue;
			}
			// Before the clear (TPF-137): every purl that had findings (their canonicals must drop
			// them) and the membership as loaded (the fold of an unrecorded bucket needs it).
			Set<String> membershipAsLoaded = membershipPurlsOf(b);
			Set<String> retiredPurls = changedCanonicalPurls(b.getFindings(), Map.of());
			if (!locallyClear) {
				b.setRefMap(new LinkedHashMap<>());
				b.setFindings(new LinkedHashMap<>());
				b.setContentHash(null);
			}
			// Complete the DTrack side: without this, a bucket emptied without a
			// resubmit (all members left the matchable population at once -- the
			// V79 generic-purl backfill emptied ~19 buckets in one prod org)
			// leaves an orphaned DTrack project still carrying the very
			// components the population change was meant to evict.
			if (b.getDtrackProjectUuid() != null) {
				IntegrationService.DtrackProjectDeletionOutcome outcome =
						integrationService.deleteDtrackProjectForOrg(
								orgUuid, b.getDtrackProjectUuid().toString());
				if (outcome == IntegrationService.DtrackProjectDeletionOutcome.FAILED) {
					// Retryable: keep the ref, stay PENDING; every subsequent
					// tick re-attempts. This makes the stall reporter's
					// ">2h not INGESTED" line a REAL alarm for retired buckets
					// (DTrack cleanup cannot complete) instead of permanent
					// noise.
					b.setIngestState(IngestState.PENDING);
					recordFanOutChanges(b, membershipAsLoaded, retiredPurls, nowMicros());
					bucketRepository.save(b);
					log.warn("Retiring bucket {} (org {}): DTrack project {} deletion failed; will retry",
							b.getBucketIndex(), orgUuid, b.getDtrackProjectUuid());
					continue;
				}
				if (outcome == IntegrationService.DtrackProjectDeletionOutcome.NO_INTEGRATION) {
					// Org dropped its DTrack integration: the project is
					// unreachable by us and harmless. Converge rather than
					// retry forever -- mirrors the legacy phase-out's stance.
					log.warn("Retiring bucket {} (org {}): no DTrack integration to delete project {} through; converging",
							b.getBucketIndex(), orgUuid, b.getDtrackProjectUuid());
				}
				b.setDtrackProjectUuid(null);
			}
			// Converged: nothing to ingest, nothing left in DTrack. INGESTED is
			// the terminal state the stall reporter ignores; a bucket revived by
			// a returning member goes back to SUBMITTED on its next submit.
			b.setIngestState(IngestState.INGESTED);
			recordFanOutChanges(b, membershipAsLoaded, retiredPurls, nowMicros());
			bucketRepository.save(b);
		}
	}

	/** sha256 over the sorted set of canonical_purls -- the bucket membership fingerprint. */
	private String contentHash(List<SbomComponent> slice) {
		List<String> purls = new ArrayList<>(slice.size());
		for (SbomComponent sc : slice) purls.add(sc.getCanonicalPurl());
		purls.sort(String::compareTo);
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for (String p : purls) {
				md.update(p.getBytes(StandardCharsets.UTF_8));
				md.update((byte) 0x01); // \u0001 separator -- never NUL in keys/hashes
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Exception e) {
			throw new RuntimeException("SHA-256 unavailable", e);
		}
	}

	/**
	 * Build a CycloneDX 1.6 BOM for one bucket using the cyclonedx-core-java model
	 * + generator (same path as OBOM/VDR export), returned as a JsonNode ready for
	 * upload. For each canonical: a primary component carrying its purl (when
	 * purl-canonical) + cpe[0], plus a slim companion component per extra CPE
	 * (capped at {@link #MAX_CPES}) so DTrack's NVD matcher hits every CPE.
	 * DTrack runs both matchers independently; companions carry only the extra
	 * cpe (no purl) to avoid redundant purl re-matching.
	 *
	 * identityToCanonical is populated with every emitted coordinate (purl and
	 * each cpe) -> canonical_purl, so ingest can map findings -- which come back
	 * keyed by purl OR (for cpe-only companions) cpe -- back to the canonical.
	 */
	private JsonNode buildBom(UUID orgUuid, List<SbomComponent> slice,
			int bucketIndex, Map<String, Object> identityToCanonical) {
		List<Component> components = new ArrayList<>(slice.size());
		int i = 0;
		for (SbomComponent sc : slice) {
			String canonical = sc.getCanonicalPurl();
			List<String> cpes = cpesOf(sc, canonical);

			Component comp = new Component();
			comp.setType(Component.Type.LIBRARY);
			comp.setBomRef("c" + i++);
			comp.setName(nameOf(sc, canonical));
			SbomComponentData scd = SbomComponentData.dataFromRecord(sc);
			if (scd.group() != null) comp.setGroup(scd.group());
			if (scd.version() != null) comp.setVersion(scd.version());
			if (canonical.startsWith("pkg:")) {
				comp.setPurl(canonical);
				identityToCanonical.put(canonical, canonical);
			}
			if (!cpes.isEmpty()) {
				comp.setCpe(cpes.get(0));
				identityToCanonical.put(cpes.get(0), canonical);
			}
			LicenseChoice lc = CdxLicenseUtil.toLicenseChoice(sc.getLicenses());
			if (lc != null) comp.setLicenses(lc);
			components.add(comp);

			// Companion components for the extra CPEs (cpe[1..]).
			for (int k = 1; k < cpes.size(); k++) {
				Component companion = new Component();
				companion.setType(Component.Type.LIBRARY);
				companion.setBomRef("c" + i++);
				companion.setName(nameOf(sc, canonical));
				companion.setCpe(cpes.get(k));
				identityToCanonical.put(cpes.get(k), canonical);
				components.add(companion);
			}
		}

		Bom bom = new Bom();
		Metadata metadata = new Metadata();
		// The generator renders this as an RFC 3339 instant -- DTrack rejects the
		// ZonedDateTime.toString() form (it appends an offset/zone like [GMT]).
		metadata.setTimestamp(new java.util.Date());
		bom.setMetadata(metadata);
		bom.setComponents(components);
		try {
			BomJsonGenerator gen = BomGeneratorFactory.createJson(Version.VERSION_16, bom);
			return Utils.OM.readTree(gen.toJsonString());
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate synthetic CycloneDX BOM for org " + orgUuid, e);
		}
	}

	private String nameOf(SbomComponent sc, String canonical) {
		String name = SbomComponentData.dataFromRecord(sc).name();
		return name != null ? name : canonical;
	}

	/**
	 * Distinct CPEs for a component, primary-first, capped at {@link #MAX_CPES}.
	 * Sourced from the identities array; the canonical itself is included when it
	 * is a CPE-canonical component (no purl).
	 */
	private List<String> cpesOf(SbomComponent sc, String canonical) {
		List<String> out = new ArrayList<>();
		if (canonical.startsWith("cpe:")) out.add(canonical);
		if (sc.getIdentities() != null) {
			for (ComponentIdentity id : sc.getIdentities()) {
				if ("cpe".equals(id.scheme()) && id.value() != null) {
					String v = id.value();
					if (!out.contains(v)) out.add(v);
				}
			}
		}
		return out.size() > MAX_CPES ? new ArrayList<>(out.subList(0, MAX_CPES)) : out;
	}

	// ===================================================================
	// Ingest
	// ===================================================================

	/** Poll SUBMITTED buckets; once DTrack finishes processing, fetch + store findings. */
	void ingestOrgBuckets(UUID orgUuid) {
		for (SyntheticDtrackBucket bucket : bucketRepository.findByOrg(orgUuid)) {
			if (IngestState.SUBMITTED != bucket.getIngestState()) continue;
			if (bucket.getDtrackProjectUuid() == null) continue;
			try {
				String token = bucket.getRefMap() != null
						? (String) bucket.getRefMap().get("__token") : null;
				if (token != null && dTrackService.syntheticIsTokenProcessing(orgUuid, token)) {
					continue; // still analysing; try next tick
				}
				SyntheticFindings findings = dTrackService.syntheticFetchFindings(
						orgUuid, bucket.getDtrackProjectUuid());
				Map<String, Object> before = bucket.getFindings();
				Map<String, Object> after = toFindingsByCanonical(orgUuid, findings, bucket.getRefMap());
				bucket.setFindings(after);
				bucket.setIngestState(IngestState.INGESTED);
				bucket.setLastIngested(ZonedDateTime.now());
				// TPF-137 D4: the ingest that ends a SUBMITTED window records the bucket's whole
				// membership (its canonicals were gate-skipped while it was uncovered) united with
				// the findings diff (a member that left still needs its findings dropped).
				Set<String> membership = membershipPurlsOf(bucket);
				Set<String> changed = new TreeSet<>(membership);
				changed.addAll(changedCanonicalPurls(before, after));
				recordFanOutChanges(bucket, membership, changed, nowMicros());
				bucketRepository.save(bucket);
			} catch (Exception e) {
				log.error("Failed to ingest synthetic bucket {} for org {}",
						bucket.getBucketIndex(), orgUuid, e);
			}
		}
	}

	/**
	 * Group findings by canonical_purl, stored as plain JSON maps (never DTO
	 * records -- JsonBinaryType cannot serialize those; convertValue also
	 * normalizes ZonedDateTime). Shape:
	 *   { canonicalPurl: { "vulns": [..], "violations": [..] } }
	 *
	 * Each finding is resolved to its canonical via the identity map (purl OR
	 * cpe -> canonical) so cpe-only (purl-less) findings from CPE-canonical
	 * components and CPE companions are attributed, not dropped.
	 */
	private Map<String, Object> toFindingsByCanonical(
			UUID orgUuid, SyntheticFindings findings, Map<String, Object> identityMap) {
		Map<String, Object> byCanonical = new LinkedHashMap<>();
		CanonicalResolver resolver = new CanonicalResolver(identityMap);
		if (findings.vulns() != null) {
			for (IntegrationService.VulnWithCpe vc : findings.vulns()) {
				String canonical = resolver.resolve(vc.vuln().purl(), vc.cpe());
				if (canonical == null) continue;
				bucketFor(byCanonical, canonical, "vulns")
						.add(Utils.OM.convertValue(vc.vuln(), LinkedHashMap.class));
			}
		}
		if (findings.violations() != null) {
			for (IntegrationService.ViolationWithCpe vc : findings.violations()) {
				String canonical = resolver.resolve(vc.violation().purl(), vc.cpe());
				if (canonical == null) continue;
				bucketFor(byCanonical, canonical, "violations")
						.add(Utils.OM.convertValue(vc.violation(), LinkedHashMap.class));
			}
		}
		// Collapse alias-duplicate findings per canonical through the SAME mechanism
		// the artifact metrics use -- VulnAnalysisService.processReleaseMetricsDto at
		// ORG scope (exactly what ArtifactService.computeArtifactMetrics runs). This
		// applies BOTH the DTrack-provided aliases AND org-wide vulnerability-analysis
		// records (which can add aliases / override severity), then re-organizes -- so
		// the stored bucket findings end up in the same collapsed+enriched shape the
		// artifact write produces. Without matching it exactly, the idempotency guard
		// (findingsSignature) would keep missing and rewrite the artifact every tick,
		// churning release rollups. Reuse (not duplicate) the single enrichment path.
		for (Object entry : byCanonical.values()) {
			try {
				organizeCanonicalFindings(orgUuid, (Map<String, Object>) entry);
			} catch (Exception e) {
				// Never let a malformed finding break the whole bucket's ingest --
				// keep that canonical's raw findings rather than dropping coverage.
				log.warn("Alias-merge of bucket findings failed for a canonical, keeping raw: {}", e.getMessage());
			}
		}
		return byCanonical;
	}

	/**
	 * Alias-merge one canonical's findings in place via the shared
	 * {@link ReleaseMetricsDto#computeMetricsFromFacts()} path, round-tripping the
	 * stored JSON maps through the typed DTOs. Per-canonical so alias grouping never
	 * crosses components (aliases of an advisory always share the component).
	 */
	@SuppressWarnings("unchecked")
	private void organizeCanonicalFindings(UUID orgUuid, Map<String, Object> entry) {
		ReleaseMetricsDto tmp = new ReleaseMetricsDto();
		List<Object> vmaps = (List<Object>) entry.get("vulns");
		if (vmaps != null && !vmaps.isEmpty()) {
			List<VulnerabilityDto> vulns = new ArrayList<>(vmaps.size());
			for (Object m : vmaps) vulns.add(Utils.OM.convertValue(m, VulnerabilityDto.class));
			tmp.setVulnerabilityDetails(vulns);
		}
		List<Object> viomaps = (List<Object>) entry.get("violations");
		if (viomaps != null && !viomaps.isEmpty()) {
			List<ViolationDto> viols = new ArrayList<>(viomaps.size());
			for (Object m : viomaps) viols.add(Utils.OM.convertValue(m, ViolationDto.class));
			tmp.setViolationDetails(viols);
		}
		// ORG-scope enrichment + alias-organize, identical to computeArtifactMetrics.
		vulnAnalysisService.processReleaseMetricsDto(orgUuid, orgUuid, AnalysisScope.ORG, tmp);
		if (tmp.getVulnerabilityDetails() != null) {
			List<Object> out = new ArrayList<>(tmp.getVulnerabilityDetails().size());
			for (VulnerabilityDto v : tmp.getVulnerabilityDetails()) out.add(Utils.OM.convertValue(v, LinkedHashMap.class));
			entry.put("vulns", out);
		}
		if (tmp.getViolationDetails() != null) {
			List<Object> out = new ArrayList<>(tmp.getViolationDetails().size());
			for (ViolationDto v : tmp.getViolationDetails()) out.add(Utils.OM.convertValue(v, LinkedHashMap.class));
			entry.put("violations", out);
		}
	}

	/**
	 * Maps a finding's purl-or-cpe coordinate back to the canonical the bucket
	 * submitted, via the identity map: exact purl, then the purl with
	 * percent-encoding differences ignored ({@link Utils#purlSemanticKey}), then
	 * exact cpe. Purl identity goes first because a cpe can be shared by several
	 * components in a bucket and the identity map keeps only the last one.
	 *
	 * <p>The encoding fallback is load-bearing. DTrack re-encodes the purls it is
	 * sent, so a canonical rebom stored as {@code zlib@1:1.2.13.dfsg-1} or
	 * {@code glibc@2.36-9+deb12u14} comes back as {@code 1%3A1.2.13.dfsg-1} /
	 * {@code 2.36-9%2Bdeb12u14}, and IntegrationService turns a scoped npm
	 * {@code %40scope} into {@code @scope}. Filed under those keys, the findings
	 * match no component and never reach an artifact, which still shows as
	 * scanned.
	 */
	private static final class CanonicalResolver {
		private final Map<String, Object> identityMap;
		/** Semantic key -> canonical, built on the first exact miss. */
		private Map<String, String> bySemanticKey;

		CanonicalResolver(Map<String, Object> identityMap) {
			this.identityMap = identityMap != null ? identityMap : Map.of();
		}

		String resolve(String purl, String cpe) {
			if (purl != null && identityMap.get(purl) != null) return String.valueOf(identityMap.get(purl));
			if (purl != null) {
				String canonical = semanticIndex().get(Utils.purlSemanticKey(purl));
				if (canonical != null) return canonical;
			}
			if (cpe != null && identityMap.get(cpe) != null) return String.valueOf(identityMap.get(cpe));
			// Fallback: a purl-keyed finding is canonical by itself (purl == canonical).
			return purl;
		}

		private Map<String, String> semanticIndex() {
			if (bySemanticKey == null) {
				bySemanticKey = new HashMap<>();
				for (Map.Entry<String, Object> e : identityMap.entrySet()) {
					String key = Utils.purlSemanticKey(e.getKey());
					if (key != null && e.getValue() != null) {
						bySemanticKey.putIfAbsent(key, String.valueOf(e.getValue()));
					}
				}
			}
			return bySemanticKey;
		}
	}

	@SuppressWarnings("unchecked")
	private List<Object> bucketFor(Map<String, Object> byCanonical, String canonical, String kind) {
		Map<String, Object> entry = (Map<String, Object>) byCanonical
				.computeIfAbsent(canonical, k -> new LinkedHashMap<String, Object>());
		return (List<Object>) entry.computeIfAbsent(kind, k -> new ArrayList<>());
	}

	// ===================================================================
	// Fan-out
	// ===================================================================

	/**
	 * Mark scanned and distribute findings to the artifacts whose components are
	 * covered by an INGESTED bucket. Coverage-driven (not findings-driven): a
	 * clean artifact with zero findings is still marked scanned so it flips from
	 * "Scan pending" to "Scan done" -- just like the legacy path stamped
	 * lastScanned after fetching the project even with no findings.
	 *
	 * An artifact is only marked once ALL of its matchable (purl/cpe) components
	 * are covered, so an artifact with a still-PENDING/gated component isn't
	 * prematurely reported as 0-vuln scanned.
	 */
	/**
	 * Report a fan-out stall if one looks permanent.
	 *
	 * <p>Fan-out only stamps an artifact once EVERY one of its matchable
	 * components is covered by an INGESTED bucket. Most gaps are transient (a
	 * new dependency waiting a tick or two for submit + DTrack), and those
	 * resolve on their own. Two conditions do not resolve on their own and
	 * leave affected artifacts unscanned indefinitely, with nothing in the logs
	 * today to say so:
	 *
	 * <ul>
	 *   <li>components that never get bucketed at all -- almost always the BEAR
	 *       enrichment gate in {@code submitOrg}, which only ships components
	 *       whose {@code enriched_at} is set, so an org whose enrichment path
	 *       is broken silently stops scanning;</li>
	 *   <li>buckets that never reach INGESTED -- submission or DTrack ingest
	 *       failing repeatedly.</li>
	 * </ul>
	 *
	 * <p>Logged at ERROR because the user-visible symptom (releases stuck on
	 * "Scan pending", vulnerabilities never appearing) is indistinguishable
	 * from "nothing to report", and there is no other signal. Rate-limited per
	 * org so a long stall is a heartbeat, not a flood.
	 */
	/**
	 * Diagnostics for the FANOUT-STALL report: for up to {@code sampleSize} of
	 * the org's oldest never-scanned mapped artifacts, classify the blocker --
	 * un-enriched components (enrichment gate), other uncovered components, or
	 * coverage-clear (candidate starvation suspects), or not scannable (no
	 * matchable component, TPF-125). Rate-limited cadence only. Package-private
	 * so tests assert the returned text.
	 */
	String summarizeNeverScannedBlockers(UUID orgUuid, int sampleSize) {
		try {
			Set<String> covered = coveredPurlsOf(bucketRepository.findByOrg(orgUuid));
			List<UUID> sample = artifactCanonicalMapRepository
					.findNeverScannedMappedArtifactCanonicalsOlderThan(
							orgUuid, ZonedDateTime.now().minus(STALL_COMPONENT_AGE), sampleSize);
			int blockedOnCoverage = 0;
			int blockedOnEnrichment = 0;
			int coverageClear = 0;
			int notScannable = 0;
			for (UUID canonicalArtifact : sample) {
				List<ArtifactSbomComponent> ascs = artifactSbomComponentRepository
						.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact);
				Set<UUID> compUuids = new HashSet<>();
				for (ArtifactSbomComponent asc : ascs) compUuids.add(asc.getSbomComponentUuid());
				List<SbomComponent> rows = new ArrayList<>();
				for (SbomComponent sc : sbomComponentRepository.findAllById(compUuids)) rows.add(sc);
				GateView gate = classifyForGate(rows);
				if (!gate.scannable()) {
					notScannable++;
					continue;
				}
				boolean missing = false;
				boolean missingUnenriched = false;
				for (SbomComponent sc : rows) {
					if (!isGateMatchable(sc)) continue;
					if (!covered.contains(sc.getCanonicalPurl())) {
						missing = true;
						if (sc.getEnrichedAt() == null) missingUnenriched = true;
					}
				}
				if (!missing) coverageClear++;
				else if (missingUnenriched) blockedOnEnrichment++;
				else blockedOnCoverage++;
			}
			return String.format("of %d sampled: %d blocked on un-enriched components, "
					+ "%d blocked on other uncovered components, %d coverage-clear (starvation suspects), "
					+ "%d not scannable",
					sample.size(), blockedOnEnrichment, blockedOnCoverage, coverageClear, notScannable);
		} catch (Exception e) {
			log.error("Fan-out stall diagnostics failed for org {}", orgUuid, e);
			return "diagnostics unavailable: " + e.getMessage();
		}
	}

	void reportFanOutStallIfAny(UUID orgUuid) {
		try {
			Instant last = lastStallReport.get(orgUuid);
			if (last != null && last.isAfter(Instant.now().minus(STALL_LOG_INTERVAL))) return;

			ZonedDateTime compCutoff = ZonedDateTime.now().minus(STALL_COMPONENT_AGE);
			Object[] row = sbomComponentRepository.summarizeStaleUnbucketedMatchable(
					orgUuid.toString(), compCutoff);
			// Aggregate query: always one row, but the driver may nest it.
			Object[] r = (row != null && row.length == 1 && row[0] instanceof Object[] inner) ? inner : row;
			long staleCount = (r != null && r.length > 0 && r[0] != null) ? ((Number) r[0]).longValue() : 0L;

			ZonedDateTime bucketCutoff = ZonedDateTime.now().minus(STALL_BUCKET_AGE);
			long stuckBuckets = bucketRepository.findByOrg(orgUuid).stream()
					.filter(b -> IngestState.INGESTED != b.getIngestState())
					.filter(b -> b.getLastUpdatedDate() != null && b.getLastUpdatedDate().isBefore(bucketCutoff))
					.count();

			// Third stall class (prod 2026-07-25): BOM artifacts never scanned long
			// after upload while the org pipeline looks healthy -- unbucketed=0,
			// all buckets INGESTED. The pending count (TPF-114) separates "no
			// fan-out pass reached it" from "reached and held by the coverage gate".
			// Log-only, like the other classes.
			ZonedDateTime artifactCutoff = ZonedDateTime.now().minus(STALL_COMPONENT_AGE);
			long neverScanned = artifactCanonicalMapRepository
					.countNeverScannedMappedArtifactsOlderThan(orgUuid, artifactCutoff);

			if (staleCount == 0 && stuckBuckets == 0 && neverScanned == 0) return;

			if (neverScanned > 0) {
				long unreached = artifactCanonicalMapRepository
						.countFanOutPendingOlderThan(orgUuid, artifactCutoff);
				log.error("[FANOUT-STALL] org {}: {} mapped artifact(s) still never scanned {}h after upload. "
						+ "Sampled blocking reasons: {}. "
						+ "{} of them unreached by any fan-out pass (map rows older than {}h without fanOutAt): "
						+ "nonzero means the catch-up or new-upload query is not draining; zero means every "
						+ "one was reached and is held by the coverage gate.",
						orgUuid, neverScanned, STALL_COMPONENT_AGE.toHours(),
						summarizeNeverScannedBlockers(orgUuid, 5),
						unreached, STALL_COMPONENT_AGE.toHours());
			}

			if (staleCount > 0) {
				long unenriched = (r != null && r.length > 2 && r[2] != null) ? ((Number) r[2]).longValue() : 0L;
				Object oldest = (r != null && r.length > 1) ? r[1] : null;
				String samplePurl = sbomComponentRepository
						.findOldestStaleUnbucketedPurl(orgUuid.toString(), compCutoff);
				// Diagnostics are defensively isolated: they must never break the
				// report or its rate-limit stamp (which sits after the log calls).
				String enrichDiag = "";
				if (unenriched > 0) {
					try {
						enrichDiag = " Enrichment diagnostics: "
								+ sbomComponentService.summarizeEnrichmentStall(orgUuid, 10) + ".";
					} catch (Exception diagEx) {
						enrichDiag = " (enrichment diagnostics unavailable: " + diagEx.getMessage() + ")";
					}
				}
				log.error("[SYNTHETIC-STALL] org {}: {} matchable component(s) still unbucketed after {}h "
						+ "({} un-enriched; oldest created {}; e.g. {}). Artifacts containing them can never "
						+ "pass fan-out coverage, so their releases stay unscanned. {}{}",
						orgUuid, staleCount, STALL_COMPONENT_AGE.toHours(), unenriched, oldest, samplePurl,
						unenriched > 0
							? "Most likely the BEAR enrichment gate: submitOrg only ships components with enriched_at set."
							: "Components are enriched but not being assigned a bucket -- check submitOrg.",
						enrichDiag);
			}
			if (stuckBuckets > 0) {
				log.error("[SYNTHETIC-STALL] org {}: {} synthetic bucket(s) have not reached INGESTED in {}h; "
						+ "components in them stay uncovered and their artifacts stay unscanned.",
						orgUuid, stuckBuckets, STALL_BUCKET_AGE.toHours());
			}
			lastStallReport.put(orgUuid, Instant.now());
		} catch (Exception e) {
			// Diagnostics must never break the tick they are diagnosing.
			log.warn("Fan-out stall check failed for org {}: {}", orgUuid, e.getMessage());
		}
	}

	// ===================================================================
	// The coverage gate's shared helpers (TPF-121): fanOutOrg, the stall
	// sampler and the scan-status explainer call the same code, so the three
	// can never disagree.
	// ===================================================================

	/**
	 * The coverage gate's matchable predicate (fanOutOrg): not the BOM's own root/self component, not
	 * enrichment-terminal, and carrying a purl or cpe. Shared by the gate, the stall sampler and the scan-status
	 * explainer (TPF-121) so the three can never disagree.
	 *
	 * <p>Root/self components are excluded: they're the app itself (not a dependency), are never submitted to
	 * synthetic DTrack, and BEAR never enriches them -- so requiring their coverage would block the artifact on
	 * "scan pending" forever (mirrors the root exclusion in the matchable repo queries). Terminal components
	 * never ship to DTrack, so requiring their coverage would scan-block the artifact forever -- excluded from
	 * the gate exactly like roots (see V75).
	 */
	static boolean isGateMatchable(SbomComponent sc) {
		if (sc.isRoot() || sc.isEnrichmentTerminal()) return false;
		String p = sc.getCanonicalPurl();
		return p != null && (p.startsWith("pkg:") || p.startsWith("cpe:"));
	}

	/** The gate's coverage set: ref_map keys of the INGESTED buckets among the given ones, "__token" excluded. */
	static Set<String> coveredPurlsOf(Iterable<SyntheticDtrackBucket> buckets) {
		Set<String> covered = new HashSet<>();
		for (SyntheticDtrackBucket b : buckets) {
			if (IngestState.INGESTED != b.getIngestState() || b.getRefMap() == null) continue;
			for (String k : b.getRefMap().keySet()) if (!"__token".equals(k)) covered.add(k);
		}
		return covered;
	}

	/**
	 * The pass cutoff: the newest lastUpdatedDate among the INGESTED buckets given, null when there is none. The value
	 * a pass stamps as fanOutAt (TPF-114) and the instant the explainer reports as newestIngestedBucketUpdate.
	 */
	static ZonedDateTime candidateCutoffOf(Iterable<SyntheticDtrackBucket> buckets) {
		ZonedDateTime cutoff = null;
		for (SyntheticDtrackBucket b : buckets) {
			if (IngestState.INGESTED != b.getIngestState()) continue;
			ZonedDateTime u = b.getLastUpdatedDate();
			if (u != null && (cutoff == null || u.isAfter(cutoff))) cutoff = u;
		}
		return cutoff;
	}

	/**
	 * A fan-out stamp as the candidate query reads it (TPF-121 round 2, F-1): the query casts the ISO text to
	 * timestamptz, which keeps microseconds, and PostgreSQL rounds the fraction with rint(strtod(fraction) * 1000000)
	 * (datetime.c ParseFractionalSecond). Same operations here: the correctly rounded double of the nine-digit
	 * fraction (an IEEE-754 division of exact integers equals strtod of the same decimal), the same double product,
	 * Math.rint = C rint in the default rounding mode. Instant.ofEpochSecond normalizes 1_000_000 micros into the
	 * next second, as the cast's fsec == USECS_PER_SEC does. Compare against this value; report the original.
	 */
	static Instant atDatabasePrecision(Instant stamp) {
		double frac = stamp.getNano() / 1_000_000_000d;        // strtod(".ddddddddd")
		long micros = (long) Math.rint(frac * 1_000_000d);      // rint(frac * 1000000)
		return Instant.ofEpochSecond(stamp.getEpochSecond(), micros * 1_000L);
	}

	/** One canonical's component rows classified exactly as the coverage gate reads them (TPF-125). */
	record GateView(Set<String> matchablePurls, int components, int root, int terminal, int unmatchable) {
		boolean scannable() {
			return !matchablePurls.isEmpty();
		}

		/** TPF-121 4.6 precedence; read only when !scannable(). */
		NoMatchableComponentsReason reason() {
			if (components == 0) return NoMatchableComponentsReason.NO_COMPONENTS;
			if (components == root) return NoMatchableComponentsReason.ONLY_ROOT;
			if (unmatchable == 0) return NoMatchableComponentsReason.ALL_TERMINAL;
			return NoMatchableComponentsReason.NO_PURL_OR_CPE;
		}

		ArtifactNotScannable marker(ZonedDateTime at) {
			return new ArtifactNotScannable(reason(), at, components, root, terminal, unmatchable);
		}
	}

	/**
	 * The gate's classification of a canonical's rows (TPF-125): the order of the three tests is the
	 * gate's own (root, then terminal, then isGateMatchable), so a root row counts as root whatever
	 * else it carries and a terminal non-root row as terminal; unmatchable is a non-root, non-terminal
	 * row without a pkg:/cpe: purl, TPF-121's unmatchableComponentCount. matchablePurls is the set the
	 * gate's containsAll consumes. Package-private static so the tests call it on fixtures.
	 */
	static GateView classifyForGate(Iterable<SbomComponent> rows) {
		Set<String> matchable = new HashSet<>();
		int n = 0;
		int root = 0;
		int terminal = 0;
		int unmatchable = 0;
		for (SbomComponent sc : rows) {
			n++;
			if (sc.isRoot()) {
				root++;
				continue;
			}
			if (sc.isEnrichmentTerminal()) {
				terminal++;
				continue;
			}
			if (isGateMatchable(sc)) matchable.add(sc.getCanonicalPurl());
			else unmatchable++;
		}
		return new GateView(matchable, n, root, terminal, unmatchable);
	}

	void fanOutOrg(UUID orgUuid) {
		// From INGESTED buckets: the findings (by canonical purl) and the set of
		// canonical purls DTrack has actually scanned (the bucket ref_map keys).
		// Every bucket of the org is kept too (TPF-137): the catch-up and the changed
		// set read every bucket whatever its state.
		Map<String, FindingSet> global = new HashMap<>();
		List<SyntheticDtrackBucket> all = bucketRepository.findByOrg(orgUuid);
		List<SyntheticDtrackBucket> ingested = new ArrayList<>();
		for (SyntheticDtrackBucket bucket : all) {
			if (IngestState.INGESTED != bucket.getIngestState()) continue;
			Map<String, Object> findingsBeforeRekey = bucket.getFindings();
			if (rekeyEncodingVariantFindings(bucket)) {
				// Bumping lastUpdatedDate raises the cutoff below, and the re-keyed purls
				// are recorded as changed (TPF-137) for the walk of this same pass.
				ZonedDateTime previous = bucket.getLastUpdatedDate();
				BucketFlowControl previousFlowControl = bucket.getFlowControl();
				try {
					recordFanOutChanges(bucket, membershipPurlsOf(bucket),
							changedCanonicalPurls(findingsBeforeRekey, bucket.getFindings()), nowMicros());
					bucketRepository.save(bucket);
					log.info("Re-keyed encoding-variant findings in synthetic bucket {} for org {}",
							bucket.getBucketIndex(), orgUuid);
				} catch (Exception e) {
					// Not persisted (e.g. a concurrent manual resync): keep the healed
					// findings for this pass but not the cutoff bump nor the unsaved
					// bookkeeping (an unrecorded bucket stays unrecorded and the catch-up
					// below records it in place); retried next tick.
					bucket.setLastUpdatedDate(previous);
					bucket.setFlowControl(previousFlowControl);
					log.error("Failed to save re-keyed synthetic bucket {} for org {}",
							bucket.getBucketIndex(), orgUuid, e);
				}
			}
			ingested.add(bucket);
			if (bucket.getFindings() != null) mergeBucketFindings(global, bucket.getFindings());
		}
		// The gate's coverage set, and the newest INGESTED-bucket update = the pass
		// cutoff, the value every canonical this pass processes is stamped with
		// (TPF-114): bucket state as of that instant. Shared helpers (TPF-121): the
		// scan-status explainer computes both the same way.
		Set<String> coveredPurls = coveredPurlsOf(ingested);
		ZonedDateTime cutoff = candidateCutoffOf(ingested);
		// No INGESTED coverage: nothing to apply, nothing stamped, no watermark.
		// (last_updated_date is NOT NULL, so a null cutoff is a hand-built row only.)
		if (coveredPurls.isEmpty() || cutoff == null) return;
		String fanOutAt = DateTimeFormatter.ISO_INSTANT.format(cutoff.toInstant());

		// (A) never-stamped canonicals, newest map row first; (A) keeps the first
		// call but leaves FANOUT_CHANGE_RESERVE of the batch to (B).
		Set<UUID> pending = new LinkedHashSet<>(artifactCanonicalMapRepository
				.findNeverFannedOutCanonicals(orgUuid, FANOUT_BATCH_LIMIT * 4));
		Set<UUID> candidates = new LinkedHashSet<>();
		List<UUID> surplus = new ArrayList<>();
		for (UUID c : pending) {
			if (candidates.size() < FANOUT_BATCH_LIMIT - FANOUT_CHANGE_RESERVE) candidates.add(c);
			else surplus.add(c);
		}

		// Watermark: the cached value, refreshed from the persisted one only when
		// the cutoff is after the cache (steady state touches no system_info).
		ZonedDateTime since = fanOutDrainedAt.get(orgUuid);
		if (since == null || cutoff.isAfter(since)) {
			ZonedDateTime persisted = systemInfoService.getFanOutDrainedAt(orgUuid);
			if (persisted != null && (since == null || persisted.isAfter(since))) since = persisted;
			if (since != null) fanOutDrainedAt.put(orgUuid, since);
		}

		// (B) the change-driven drain (TPF-137): the canonicals holding a component with a
		// pending change recorded at or before the drain's cutoff, walked in fixed slices of
		// the uuid space from the persisted cursor, under the selection budget, the batch room
		// and the metrics back-pressure.
		boolean changeDriven = false;
		boolean coldStartSkipped = false;
		ZonedDateTime drainedThrough = null;
		WalkPass walk = null;
		if (since == null && artifactCanonicalMapRepository.countFanOutStamped(orgUuid) == 0) {
			// Cold start with no stamped map row (TPF-136): every row of the org is in (A)'s
			// set, so the walk could only return what (A) already serves. Skip it and
			// record the cutoff as drained; (B) starts at the next bucket change.
			changeDriven = true;
			coldStartSkipped = true;
			drainedThrough = cutoff;
			log.info("Synthetic fan-out cold start for org {}: no stamped map row, the bucket walk is skipped "
					+ "and the cutoff {} is recorded as drained when the pass completes", orgUuid, cutoff);
		} else if (since == null || cutoff.isAfter(since)) {
			changeDriven = true;
			walk = new WalkPass();
			// D22: every bucket a writer moved without recording is caught up first.
			ZonedDateTime oldestCaughtUp = catchUpUnrecordedBuckets(orgUuid, all, since);
			FanOutWalkState state = systemInfoService.getFanOutWalk(orgUuid);
			if (state != null && oldestCaughtUp != null
					&& !oldestCaughtUp.isAfter(parseWalkCutoff(state))) {
				// D23: entries recorded at or before the running drain's cutoff mean its set was
				// incomplete when it started: restart it at this pass's cutoff with the full set.
				systemInfoService.clearFanOutWalk(orgUuid);
				log.info("Synthetic fan-out drain of org {} restarted at cutoff {}: bucket(s) caught up at {}, "
						+ "at or before its cutoff {}", orgUuid, cutoff, oldestCaughtUp, state.cutoff());
				state = null;
			}
			walk.statePresent = state != null;
			walk.drainCutoff = state == null ? cutoff : parseWalkCutoff(state);
			walk.cursorAtStart = state == null ? FanOutWalkSlice.START : UUID.fromString(state.cursor());
			walk.cursor = walk.cursorAtStart;
			List<UUID> changed = pendingChangedComponents(all, walk.drainCutoff);
			if (changed.isEmpty()) {
				// 4.5 step 2: a moved bucket with nothing pending completes the drain at its cutoff.
				walk.nothingPending = true;
				walk.reachedEnd = true;
			} else {
				boolean fullMode = changed.size() > FANOUT_CHANGED_SET_CAP;
				int walkRoom = pacedWalkRoom(orgUuid, FANOUT_BATCH_LIMIT - candidates.size());
				if (walkRoom > 0) {
					if (!walk.statePresent) {
						log.info("Synthetic fan-out drain of org {} started at cutoff {} with {} changed components{}",
								orgUuid, walk.drainCutoff, changed.size(), fullMode ? ", full mode" : "");
					}
					walkSlices(orgUuid, walk, fullMode ? null : changed.toArray(new UUID[0]), walkRoom, candidates);
				}
			}
			if (walk.reachedEnd) drainedThrough = walk.drainCutoff;
		}

		// (A)'s surplus fills whatever (B) left of the batch.
		for (UUID c : surplus) {
			if (candidates.size() >= FANOUT_BATCH_LIMIT) break;
			candidates.add(c);
		}

		if (candidates.isEmpty()) {
			// An empty change-driven pass still records its drain (TPF-114 F-2).
			recordFanOutWalk(orgUuid, walk, coldStartSkipped, cutoff, 0);
			recordFanOutDrain(orgUuid, changeDriven, since, drainedThrough, 0);
			return;
		}

		// Database clock, once per pass: the precondition instant of every stamp,
		// so a requeue (fanOutRequeuedAt, also database clock) that lands during
		// the pass is never stamped over.
		ZonedDateTime passStartedAt = artifactCanonicalMapRepository.dbClockTimestamp();
		int stampFailures = 0;
		for (UUID canonicalArtifact : candidates) {
			List<ArtifactSbomComponent> ascs = artifactSbomComponentRepository
					.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact);
			Set<UUID> compUuids = new HashSet<>();
			for (ArtifactSbomComponent asc : ascs) compUuids.add(asc.getSbomComponentUuid());

			// This canonical's component rows as the gate reads them (isGateMatchable:
			// roots and enrichment-terminal rows excluded, a pkg:/cpe: purl required).
			GateView gate = classifyForGate(sbomComponentRepository.findAllById(compUuids));
			Set<String> matchablePurls = gate.matchablePurls();
			// No matchable component at all (TPF-125): nothing for Dependency-Track to
			// scan, so the artifacts are marked not scannable with the reason, never
			// stamped scanned; the map rows are stamped so the canonical rotates out.
			if (!gate.scannable()) {
				ArtifactNotScannable marker = gate.marker(passStartedAt);
				for (ArtifactCanonicalMap m : artifactCanonicalMapRepository
						.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact)) {
					markArtifactNotScannable(m.getArtifactUuid(), marker);
				}
				if (!stampFanOutPass(orgUuid, canonicalArtifact, fanOutAt, passStartedAt)) stampFailures++;
				continue;
			}
			// Only mark scanned once every matchable component is covered (scanned).
			// Otherwise a still-gated component would be reported as 0-vuln prematurely.
			// A gate skip is stamped too (TPF-114): the canonical rotates out until a
			// covering bucket moves or a gate writer requeues it.
			if (!coveredPurls.containsAll(matchablePurls)) {
				// The canonical has a matchable component: a not-scannable marker from an
				// earlier pass no longer holds, so it reads "Scan pending" from this pass
				// on (a no-op on an unmarked row; the clear touches the carrying releases).
				for (ArtifactCanonicalMap m : artifactCanonicalMapRepository
						.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact)) {
					clearArtifactNotScannable(m.getArtifactUuid());
				}
				if (!stampFanOutPass(orgUuid, canonicalArtifact, fanOutAt, passStartedAt)) stampFailures++;
				continue;
			}

			// Union the (possibly empty) findings for this artifact's components.
			FindingSet artifactFindings = new FindingSet();
			for (String purl : matchablePurls) {
				FindingSet fs = global.get(purl);
				if (fs != null) artifactFindings.merge(fs);
			}

			// Apply to every artifact mapping to this canonical. With no findings
			// this still stamps lastScanned (-> firstScanned), which is right here:
			// every matchable component is covered, so an empty result is a clean
			// scan. A canonical with no matchable component never reaches this branch
			// (gate.scannable() above): it is marked not scannable with its reason,
			// and only its map rows are stamped (TPF-125).
			List<ArtifactCanonicalMap> maps = artifactCanonicalMapRepository
					.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact);
			int failedApplies = 0;
			for (ArtifactCanonicalMap m : maps) {
				if (applyFindingsToArtifact(m.getArtifactUuid(), artifactFindings) == FindingsApply.FAILED) failedApplies++;
			}
			if (failedApplies > 0) {
				// TPF-137 D26: at least one artifact's findings were not written. Not stamped: a
				// stamp means applied. Requeued into (A) instead (fanOutAt removed,
				// fanOutRequeuedAt = the database clock, the TPF-114 requeue shape), so the next
				// pass retries it from (A) whatever the drain does; the cursor and the watermark
				// move on, so a canonical that fails every tick never holds the org's later
				// change-driven re-scans (TPF-136 F-7).
				if (!requeueAfterFailedApply(orgUuid, canonicalArtifact, failedApplies, maps.size())) stampFailures++;
				continue;
			}
			// Writes first, stamp last: a crash in between leaves the canonical
			// unstamped and the next tick repeats it (idempotent).
			if (!stampFanOutPass(orgUuid, canonicalArtifact, fanOutAt, passStartedAt)) stampFailures++;
		}
		// A skip pass has no (B) candidate: a stamp that threw leaves its (A) row never-stamped,
		// so the drain is recorded whatever the stamps did (TPF-136 F-1).
		int failures = coldStartSkipped ? 0 : stampFailures;
		recordFanOutWalk(orgUuid, walk, coldStartSkipped, cutoff, failures);
		recordFanOutDrain(orgUuid, changeDriven, since, drainedThrough, failures);
	}

	/** The outcome of applying a canonical's findings to one artifact (TPF-137 D26). */
	private enum FindingsApply { APPLIED, NO_ARTIFACT, FAILED }

	/**
	 * One pass's view of the org's change-driven drain (TPF-137): its cutoff, the cursor the pass
	 * started from and the one it reached, whether the walk passed the end of the uuid space,
	 * whether a persisted state was resumed and whether the drain had nothing pending.
	 */
	private static final class WalkPass {
		ZonedDateTime drainCutoff;
		UUID cursorAtStart;
		UUID cursor;
		boolean reachedEnd;
		boolean statePresent;
		boolean nothingPending;
		int statements;
	}

	/**
	 * The walk of one pass (TPF-137 4.5 step 3): one slice statement at a time from the cursor,
	 * while the batch room, the back-pressure room and the selection budget allow. A slice the
	 * room stopped keeps the cursor inside it; a drained slice (an empty one included) moves the
	 * cursor to the next slice's start; the last slice drained ends the walk. Candidates are
	 * added in walk order, filtered by the stamp check. {@code changed} null = full mode.
	 */
	private void walkSlices(UUID orgUuid, WalkPass walk, UUID[] changed, int walkRoom, Set<UUID> candidates) {
		long deadline = nanoTime.getAsLong() + FANOUT_SELECT_BUDGET.toNanos();
		int taken = 0;
		while (true) {
			int room = Math.min(FANOUT_BATCH_LIMIT - candidates.size(), walkRoom - taken);
			if (room <= 0) break;
			long startedAt = nanoTime.getAsLong();
			if (startedAt > deadline) break;
			int slice = FanOutWalkSlice.sliceOf(walk.cursor);
			UUID sliceEnd = FanOutWalkSlice.endOf(slice);
			List<UUID> found;
			try {
				walk.statements++;
				found = null == changed
						? fanOutWalkRepository.findCanonicalsInSlice(orgUuid, walk.cursor, sliceEnd, room)
						: fanOutWalkRepository.findChangedCanonicalsInSlice(orgUuid, walk.cursor, sliceEnd, changed, room);
			} catch (Exception e) {
				// One slice statement failed (the client timeout, a cancelled or broken
				// connection): the pass goes on without it (TPF-136 catch, per slice in
				// TPF-137). The cursor stays before this slice and it is walked again next
				// tick; the slices after it depend on it, so the walk ends here.
				long elapsedMs = (nanoTime.getAsLong() - startedAt) / 1_000_000L;
				log.error("Synthetic fan-out slice statement failed for org {} slice {} cursor {} after {} ms; "
						+ "the cursor stays before this slice and it is walked again next tick",
						orgUuid, slice, walk.cursor, elapsedMs, e);
				break;
			}
			if (!found.isEmpty()) {
				Set<UUID> needing = new HashSet<>(artifactCanonicalMapRepository
						.findCanonicalsStampedBefore(orgUuid, found, walk.drainCutoff));
				for (UUID c : found) {
					if (needing.contains(c) && candidates.add(c)) taken++; // walk order kept
				}
			}
			if (found.size() >= room) {
				walk.cursor = found.get(found.size() - 1); // a dense slice: the room stopped it, the cursor stays inside
			} else if (slice == FanOutWalkSlice.SLICES - 1) {
				walk.cursor = FanOutWalkSlice.END;
				walk.reachedEnd = true;
				break;
			} else {
				walk.cursor = FanOutWalkSlice.startOf(slice + 1); // the slice is drained (an empty slice included)
			}
		}
	}

	/**
	 * The walk state after the stamping loop (TPF-137 4.5 step 4 and 7), called before
	 * recordFanOutDrain. A cold-start skip clears the pending entries up to the cutoff and writes
	 * no state (D17). A pass with a stamp failure writes nothing: the cursor is restored to where
	 * the pass started, which is exactly what the persisted state already holds (or its absence),
	 * so the next tick re-walks this pass's slices and the stamp check re-selects the canonical
	 * whose stamp threw (D14). A walk that passed the end of the space completes the drain: the
	 * pending entries it covered are cleared, then the state is removed (recordFanOutDrain then
	 * moves the watermark to the drain's cutoff). Otherwise the cursor is persisted, only if it
	 * moved.
	 */
	private void recordFanOutWalk(UUID orgUuid, WalkPass walk, boolean coldStartSkipped, ZonedDateTime cutoff,
			int stampFailures) {
		if (coldStartSkipped) {
			bucketRepository.clearFanOutChangedThrough(orgUuid, cutoff);
			return;
		}
		if (null == walk || stampFailures != 0) return;
		if (walk.reachedEnd) {
			if (!walk.nothingPending) bucketRepository.clearFanOutChangedThrough(orgUuid, walk.drainCutoff);
			if (walk.statePresent || !walk.nothingPending) {
				systemInfoService.clearFanOutWalk(orgUuid);
				log.info("Synthetic fan-out drain of org {} completed at cutoff {} ({} slice statements in this pass)",
						orgUuid, walk.drainCutoff, walk.statements);
			}
			return;
		}
		if (!walk.cursor.equals(walk.cursorAtStart)) {
			systemInfoService.stampFanOutWalk(orgUuid, walk.drainCutoff, walk.cursor);
		}
	}

	/** A persisted drain cutoff as a UTC instant (getFanOutWalk has already checked that it parses). */
	private static ZonedDateTime parseWalkCutoff(FanOutWalkState state) {
		return Instant.parse(state.cutoff()).atZone(ZoneOffset.UTC);
	}

	/**
	 * The changed set at {@code drainCutoff} (TPF-137 4.5, D24): the union, over every bucket of
	 * the org whatever its state, of the pending entries dated at or before the cutoff, as uuids,
	 * in uuid text order. The same rows the completion clear rewrites.
	 */
	static List<UUID> pendingChangedComponents(Iterable<SyntheticDtrackBucket> buckets, ZonedDateTime drainCutoff) {
		Instant through = drainCutoff.toInstant();
		Set<String> changed = new TreeSet<>();
		for (SyntheticDtrackBucket b : buckets) {
			BucketFlowControl fc = b.getFlowControl();
			if (null == fc || null == fc.fanOutChanged()) continue;
			for (Map.Entry<String, String> e : fc.fanOutChanged().entrySet()) {
				Instant at = parseInstant(e.getValue());
				if (null != at && !at.isAfter(through)) changed.add(e.getKey());
			}
		}
		List<UUID> out = new ArrayList<>(changed.size());
		for (String k : changed) {
			try {
				out.add(UUID.fromString(k));
			} catch (IllegalArgumentException e) {
				log.error("Synthetic fan-out: pending change key {} is not a component uuid; ignored", k, e);
			}
		}
		return out;
	}

	/**
	 * The back-pressure room of the walk (TPF-137 4.7, D19, D20): at most
	 * {@code METRICS_BACKLOG_PRESSURE_THRESHOLD - backlog} candidates this tick, none while the
	 * metrics compute backlog is at or above the threshold; {@code room} is the batch room. One
	 * INFO line when the org's walk pauses and one when it resumes.
	 */
	private int pacedWalkRoom(UUID orgUuid, int room) {
		long backlog = metricsBacklog();
		int walkRoom = (int) Math.max(0L,
				Math.min((long) room, ReleaseService.METRICS_BACKLOG_PRESSURE_THRESHOLD - backlog));
		if (walkRoom == 0 && room > 0) {
			if (walkPausedForBacklog.add(orgUuid)) {
				log.info("Synthetic fan-out walk of org {} paused: the metrics compute backlog is {} (threshold {}); "
						+ "new uploads are still fanned out", orgUuid, backlog,
						ReleaseService.METRICS_BACKLOG_PRESSURE_THRESHOLD);
			}
		} else if (walkRoom > 0 && walkPausedForBacklog.remove(orgUuid)) {
			log.info("Synthetic fan-out walk of org {} resumed: the metrics compute backlog is {}", orgUuid, backlog);
		}
		return walkRoom;
	}

	/** The metrics compute backlog (releases eligible for compute); 0 when the count fails, logged. */
	private long metricsBacklog() {
		try {
			return releaseRepository.countReleasesEligibleForMetricsCompute();
		} catch (Exception e) {
			log.error("Synthetic fan-out could not read the metrics compute backlog; the walk runs unpaced this tick", e);
			return 0L;
		}
	}

	// ===================================================================
	// Fan-out bookkeeping on the bucket rows (TPF-137)
	// ===================================================================

	/** A bucket date for a save: now, truncated to microseconds, the precision the column keeps. */
	private static ZonedDateTime nowMicros() {
		return ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

	/** The ISO-8601 UTC form of a bookkeeping instant, truncated to microseconds. */
	private static String isoMicros(ZonedDateTime t) {
		return DateTimeFormatter.ISO_INSTANT.format(t.toInstant().truncatedTo(ChronoUnit.MICROS));
	}

	/** The bucket's membership: its ref_map keys, "__token" excluded (the coordinates coveredPurlsOf reads). */
	static Set<String> membershipPurlsOf(SyntheticDtrackBucket bucket) {
		Set<String> out = new TreeSet<>();
		if (null == bucket.getRefMap()) return out;
		for (String k : bucket.getRefMap().keySet()) if (!"__token".equals(k)) out.add(k);
		return out;
	}

	/**
	 * Whether a bucket was moved by a writer that recorded nothing (TPF-137 4.2): no bookkeeping, a
	 * recordedAt that is absent or does not parse, or a loaded lastUpdatedDate (truncated to
	 * microseconds) that differs from recordedAt in either direction. Every save of this code sets
	 * the two to one instant and the catch-up writes recordedAt equal to the loaded date, so on a
	 * recorded bucket they are equal. Shared by the fold, the catch-up and the explainer.
	 */
	static boolean isUnrecorded(SyntheticDtrackBucket bucket) {
		BucketFlowControl fc = bucket.getFlowControl();
		if (null == fc || null == fc.recordedAt() || null == bucket.getLastUpdatedDate()) return true;
		Instant recordedAt = parseInstant(fc.recordedAt());
		if (null == recordedAt) return true;
		return !bucket.getLastUpdatedDate().toInstant().truncatedTo(ChronoUnit.MICROS).equals(recordedAt);
	}

	/**
	 * Records a bucket save's fan-out bookkeeping (TPF-137 4.2), called by each of the seven bucket
	 * saves in place of their date line; it does not save. In order: (1) the fold (D25): on a bucket
	 * unrecorded as loaded and dated after the org's persisted watermark, the changed purls are
	 * united with the membership as loaded, the catch-up's record for that bucket; (2) the purls are
	 * resolved to component uuids and merged into the pending map at {@code at}; (3) recordedAt =
	 * {@code at}; (4) the bucket date = {@code at}, last, so the date, the entries and recordedAt
	 * are one instant.
	 */
	private void recordFanOutChanges(SyntheticDtrackBucket bucket, Set<String> membershipAsLoaded,
			Set<String> changedPurls, ZonedDateTime at) {
		ZonedDateTime micros = at.truncatedTo(ChronoUnit.MICROS);
		Set<String> purls = changedPurls;
		if (!membershipAsLoaded.isEmpty() && isUnrecorded(bucket) && null != bucket.getLastUpdatedDate()) {
			ZonedDateTime watermark = systemInfoService.getFanOutDrainedAt(bucket.getOrg());
			if (null == watermark || bucket.getLastUpdatedDate().isAfter(watermark)) {
				purls = new TreeSet<>(changedPurls);
				purls.addAll(membershipAsLoaded);
			}
		}
		mergeFanOutChanges(bucket, purls, micros);
		bucket.setLastUpdatedDate(micros);
	}

	/**
	 * The lower layer of recordFanOutChanges, which the catch-up calls directly: resolves the purls
	 * to the org's non-terminal component uuids and sets a NEW flow_control (the loaded map is never
	 * mutated, so a caller can restore the object it held) whose pending map is the old one with each
	 * resolved uuid at {@code at} (the newest change wins) and whose recordedAt is {@code at}.
	 *
	 * @return the number of entries written
	 */
	private int mergeFanOutChanges(SyntheticDtrackBucket bucket, Set<String> purls, ZonedDateTime at) {
		String stamp = isoMicros(at);
		BucketFlowControl old = bucket.getFlowControl();
		Map<String, String> pending = new LinkedHashMap<>();
		if (null != old && null != old.fanOutChanged()) pending.putAll(old.fanOutChanged());
		int written = 0;
		if (!purls.isEmpty()) {
			for (ComponentUuidByPurl row : sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(bucket.getOrg(), purls)) {
				pending.put(row.getUuid().toString(), stamp);
				written++;
			}
		}
		bucket.setFlowControl(new BucketFlowControl(pending, stamp));
		return written;
	}

	/**
	 * The catch-up (TPF-137 D22): every bucket a writer moved without recording, dated after the
	 * watermark (or every one when there is none), whatever its state, gets its whole membership
	 * recorded at its own date, written in place (no revision, the date untouched). In steady state
	 * nothing is unrecorded and this costs one comparison per bucket.
	 *
	 * @return the oldest date at which an entry was recorded, null when none (D23 reads it)
	 */
	private ZonedDateTime catchUpUnrecordedBuckets(UUID orgUuid, List<SyntheticDtrackBucket> buckets,
			ZonedDateTime since) {
		ZonedDateTime oldest = null;
		for (SyntheticDtrackBucket b : buckets) {
			if (null == b.getLastUpdatedDate() || !isUnrecorded(b)) continue;
			if (null != since && !b.getLastUpdatedDate().isAfter(since)) continue;
			int written = mergeFanOutChanges(b, membershipPurlsOf(b), b.getLastUpdatedDate());
			bucketRepository.stampFlowControl(b.getUuid(), Utils.OM.writeValueAsString(b.getFlowControl()));
			if (written > 0 && (null == oldest || b.getLastUpdatedDate().isBefore(oldest))) oldest = b.getLastUpdatedDate();
		}
		if (null != oldest) {
			log.info("Synthetic fan-out of org {} caught up unrecorded bucket(s), the oldest dated {}", orgUuid, oldest);
		}
		return oldest;
	}

	/**
	 * A canonical whose findings could not be applied to every artifact (TPF-137 D26): not
	 * stamped, requeued into (A) through the TPF-114 requeue statement, so the next pass retries
	 * it. The failed applies have logged their own throwable.
	 *
	 * @return false when the requeue threw, which the pass counts as a stamp failure
	 */
	private boolean requeueAfterFailedApply(UUID orgUuid, UUID canonicalArtifact, int failed, int total) {
		log.error("Synthetic findings of canonical artifact {} (org {}) not applied to {} of {} artifact(s); "
				+ "not stamped, requeued for the next pass", canonicalArtifact, orgUuid, failed, total);
		try {
			artifactCanonicalMapRepository.requeueFanOutForCanonical(orgUuid, canonicalArtifact);
			return true;
		} catch (Exception e) {
			log.error("Failed to requeue canonical artifact {} (org {}) after a failed findings apply",
					canonicalArtifact, orgUuid, e);
			return false;
		}
	}

	/**
	 * Stamp one processed canonical (TPF-114, ArtifactCanonicalMapRepository.stampFanOutPass).
	 *
	 * @return false when the stamp threw, so the pass does not advance the watermark
	 */
	private boolean stampFanOutPass(UUID orgUuid, UUID canonicalArtifact, String fanOutAt,
			ZonedDateTime passStartedAt) {
		try {
			int n = artifactCanonicalMapRepository.stampFanOutPass(
					orgUuid, canonicalArtifact, fanOutAt, passStartedAt);
			if (n == 0) {
				log.info("Fan-out stamp of canonical artifact {} (org {}) wrote no row: no map rows left, "
						+ "or requeued during the pass; left pending", canonicalArtifact, orgUuid);
			}
			return true;
		} catch (Exception e) {
			log.error("Failed to stamp the fan-out pass of canonical artifact {} (org {})",
					canonicalArtifact, orgUuid, e);
			return false;
		}
	}

	/**
	 * Advance the org's drained-at watermark after a change-driven pass whose
	 * bucket walk reached the end of a date group with no stamp failure
	 * (TPF-114): to min(drainedThrough, now()), cached per replica and stamped
	 * persistently. A pass that filled the batch from (B) before the end of a date
	 * group, ran out of budget, or could not stamp a canonical leaves the
	 * watermark where it was, so the next tick walks the remaining buckets again.
	 * A cold-start pass that skipped the walk (TPF-136) passes 0 failures: it has
	 * no change-driven candidate, and a never-stamped row whose stamp threw is
	 * still never-stamped. A drain that reached the end of the uuid space (TPF-137)
	 * passes its own cutoff as drainedThrough; a pass that stopped inside the space
	 * passes null.
	 */
	private void recordFanOutDrain(UUID orgUuid, boolean changeDriven, ZonedDateTime since,
			ZonedDateTime drainedThrough, int stampFailures) {
		if (!changeDriven || drainedThrough == null || stampFailures != 0) return;
		ZonedDateTime now = ZonedDateTime.now();
		// Clamp: a bucket dated ahead of real time by a fast clock must not park
		// the shared watermark there.
		ZonedDateTime advanceTo = drainedThrough.isAfter(now) ? now : drainedThrough;
		if (since != null && !advanceTo.isAfter(since)) return;
		fanOutDrainedAt.merge(orgUuid, advanceTo, (old, nu) -> nu.isAfter(old) ? nu : old);
		try {
			systemInfoService.stampFanOutDrainedAt(orgUuid, advanceTo);
		} catch (Exception e) {
			log.error("Failed to persist the fan-out drained-at watermark of org {}; the replica cache keeps it",
					orgUuid, e);
		}
	}

	// ===================================================================
	// Scan status (TPF-121): why one artifact is or is not scanned
	// ===================================================================

	/** Whether the canonical qualifies for a fan-out pass, and the sentence of the rule evaluated (TPF-121). */
	private record CandidateAnswer(boolean inCandidateSet, String candidateRule) {}

	/**
	 * Read-only explanation of one artifact's synthetic scan state (TPF-121): the canonical map row and its stamps,
	 * the coverage gate evaluated with the gate's own predicate against the org's INGESTED buckets, the blocking
	 * components, and whether the artifact is in the fan-out candidate set under the TPF-114 rule. Writes nothing:
	 * no save, no stamp, no requeue, no watermark. The caller has already authorized the artifact for reading.
	 */
	public ArtifactScanStatusDto explainArtifactScanStatus(ArtifactData ad) {
		UUID artifactUuid = ad.getUuid();
		String type = null == ad.getType() ? "" : ad.getType().name();
		if (ArtifactType.BOM != ad.getType()) {
			return new ArtifactScanStatusDto(artifactUuid, type, ArtifactScanState.NOT_A_BOM, false, null, null,
					null, null, null, null, null, 0, null, false,
					"not a BOM: no synthetic scan runs on this artifact type", 0, 0, 0, 0, null, null, 0, false, List.of());
		}
		DependencyTrackIntegration metrics = ad.getMetrics();
		ZonedDateTime firstScanned = null == metrics ? null : metrics.getFirstScanned();
		ZonedDateTime lastScanned = null == metrics ? null : metrics.getLastScanned();

		Optional<ArtifactCanonicalMap> oacm = artifactCanonicalMapRepository.findByArtifactUuid(artifactUuid);
		if (oacm.isEmpty()) {
			return new ArtifactScanStatusDto(artifactUuid, type, ArtifactScanState.UNMAPPED, false, null, null,
					firstScanned, lastScanned, null, null, null, 0, null, false,
					"not mapped: no canonical map row yet (the post-upload reconcile has not mapped this artifact)",
					0, 0, 0, 0, null, null, 0, false, List.of());
		}
		ArtifactCanonicalMap own = oacm.get();
		UUID org = ad.getOrg();
		UUID canonical = own.getCanonicalArtifactUuid();
		CanonicalMapFlowControl fc = own.getFlowControl();
		ZonedDateTime fanOutAt = parseStampInstant(null == fc ? null : fc.fanOutAt(), artifactUuid, "fanOutAt");
		ZonedDateTime fanOutRequeuedAt = parseStampInstant(null == fc ? null : fc.fanOutRequeuedAt(), artifactUuid,
				"fanOutRequeuedAt");

		// The org's buckets, read once as every fan-out tick reads them.
		List<SyntheticDtrackBucket> buckets = bucketRepository.findByOrg(org);
		Set<String> covered = coveredPurlsOf(buckets);
		ZonedDateTime newestIngestedBucketUpdate = candidateCutoffOf(buckets);
		int ingestedBucketCount = 0;
		Map<Integer, SyntheticDtrackBucket> bucketsByIndex = new HashMap<>();
		for (SyntheticDtrackBucket b : buckets) {
			if (IngestState.INGESTED == b.getIngestState()) ingestedBucketCount++;
			bucketsByIndex.put(b.getBucketIndex(), b);
		}

		// The canonical's components, joined exactly as the gate joins them.
		Set<UUID> compUuids = new HashSet<>();
		for (ArtifactSbomComponent asc : artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(org, canonical)) {
			compUuids.add(asc.getSbomComponentUuid());
		}
		List<SbomComponent> components = new ArrayList<>();
		for (SbomComponent sc : sbomComponentRepository.findAllById(compUuids)) components.add(sc);

		// Steps 4 and 5 through the gate's own classification (TPF-126 case 2): the counts and the
		// reason are the ones the fan-out derives, so the marker and the explainer say the same word.
		GateView gate = classifyForGate(components);
		int rootCount = gate.root();
		int terminalCount = gate.terminal();
		int unmatchableCount = gate.unmatchable();
		int matchableCount = gate.components() - rootCount - terminalCount - unmatchableCount;
		List<SbomComponent> uncovered = new ArrayList<>();
		for (SbomComponent sc : components) {
			if (isGateMatchable(sc) && !covered.contains(sc.getCanonicalPurl())) uncovered.add(sc);
		}

		// Asked once per call, and only when a blocker row exists.
		Boolean enrichmentGated = null;
		List<ScanBlockerDto> blockers = new ArrayList<>();
		if (!uncovered.isEmpty()) {
			enrichmentGated = enrichmentGatedOf(org);
			for (SbomComponent sc : uncovered) blockers.add(blockerRow(sc, bucketsByIndex, enrichmentGated));
			final Boolean gated = enrichmentGated;
			blockers.sort(Comparator.comparing((ScanBlockerDto r) -> waitsOnEnrichment(r, gated) ? 0 : 1)
					.thenComparing(ScanBlockerDto::purl));
		}
		int blockerTotal = blockers.size();
		if (blockers.size() > SCAN_BLOCKER_CAP) blockers = new ArrayList<>(blockers.subList(0, SCAN_BLOCKER_CAP));
		boolean blockersTruncated = blockerTotal > blockers.size();

		NoMatchableComponentsReason noMatchableReason = gate.scannable() ? null : gate.reason();

		// Step 3a (TPF-126 / TPF-125): the stored not-scannable marker is what the last fan-out pass
		// concluded and what the UI shows, so it wins over firstScanned and over the live evaluation;
		// the live counts stay, so a disagreement (a matchable row gained since) is visible beside it.
		ArtifactNotScannable marker = null == metrics ? null : metrics.getNotScannable();
		ZonedDateTime notScannableAt = null == marker ? null : marker.at();
		ArtifactScanState state;
		if (null != marker) {
			state = ArtifactScanState.NO_MATCHABLE_COMPONENTS;
			noMatchableReason = marker.reason();
		} else if (null != firstScanned) state = ArtifactScanState.SCANNED;
		else if (matchableCount == 0) state = ArtifactScanState.NO_MATCHABLE_COMPONENTS;
		else if (blockerTotal > 0) state = ArtifactScanState.BLOCKED_ON_COVERAGE;
		else state = ArtifactScanState.WAITING_FOR_FAN_OUT;

		// Sibling map rows matter only for a stamped own row (rule A over the canonical's other rows).
		List<ArtifactCanonicalMap> siblings = (null == fc || null == fc.fanOutAt()) ? List.of()
				: artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(org, canonical);
		CandidateAnswer candidate = isInFanOutCandidateSet(own, siblings, components, bucketsByIndex);

		return new ArtifactScanStatusDto(artifactUuid, type, state, true, canonical, own.getCreatedDate(),
				firstScanned, lastScanned, fanOutAt, fanOutRequeuedAt, newestIngestedBucketUpdate, ingestedBucketCount,
				enrichmentGated, candidate.inCandidateSet(), candidate.candidateRule(), matchableCount, rootCount,
				terminalCount, unmatchableCount, noMatchableReason, notScannableAt, blockerTotal, blockersTruncated,
				blockers);
	}

	/**
	 * Is this artifact's canonical in the fan-out candidate set under the TPF-114 rule as TPF-137 narrows it: (A)
	 * never stamped (a map row of the canonical without fanOutAt, taken newest row first; a canonical requeued after a
	 * failed apply reads so too), or (B) a pending change: (B1) a bucket of any state holding one of its non-terminal
	 * components carries a fanOutChanged entry for that component after the artifact's fanOutAt, or (B2) that bucket
	 * is unrecorded (its date differs from recordedAt) and dated after fanOutAt, which the next pass's catch-up records
	 * as its whole membership. Evaluated in Java over the rows the explainer already holds, mirroring
	 * findNeverFannedOutCanonicals, FanOutWalkRepository.findChangedCanonicalsInSlice and
	 * findCanonicalsStampedBefore; the per-org watermark and the walk cursor are not consulted because they say where
	 * the walk is, never whether a row qualifies (every bucket dated at or before the watermark was drained by an
	 * earlier pass, or was skipped on a cold start with no stamped row because (A) serves every row (TPF-136)).
	 *
	 * <p>"Qualifies under the rule", not "the next tick takes it": a pass reads a bounded window of (A), applies at
	 * most FANOUT_BATCH_LIMIT canonicals and walks a drain over many ticks. Rule (B) compares at the query's precision
	 * (atDatabasePrecision); a change to the walk statements, the column types or the stamp format must update this
	 * method.
	 */
	CandidateAnswer isInFanOutCandidateSet(ArtifactCanonicalMap own, List<ArtifactCanonicalMap> siblings,
			List<SbomComponent> components, Map<Integer, SyntheticDtrackBucket> bucketsByIndex) {
		CanonicalMapFlowControl fc = own.getFlowControl();
		String fanOutAt = null == fc ? null : fc.fanOutAt();
		if (null == fanOutAt) {
			String requeuedAt = null == fc ? null : fc.fanOutRequeuedAt();
			String prefix = null == requeuedAt ? ""
					: "requeued at " + requeuedAt + ": a gate writer changed its inputs; ";
			return new CandidateAnswer(true, prefix
					+ "never fanned out: this artifact's map row has no fanOutAt (rule A, newest map row first)");
		}
		for (ArtifactCanonicalMap sibling : siblings) {
			if (null == sibling.getFlowControl() || null == sibling.getFlowControl().fanOutAt()) {
				return new CandidateAnswer(true, "never fanned out: another artifact of the same canonical has a map "
						+ "row without fanOutAt (rule A); the pass re-applies the canonical's findings to every "
						+ "artifact, this one included");
			}
		}
		Instant stamp = parseInstant(fanOutAt);
		if (null != stamp) {
			Instant fanOutAtDb = atDatabasePrecision(stamp);
			Instant newest = null;
			String message = null;
			for (SbomComponent sc : components) {
				if (sc.isEnrichmentTerminal() || null == sc.getSyntheticBucketIndex()) continue;
				SyntheticDtrackBucket b = bucketsByIndex.get(sc.getSyntheticBucketIndex());
				if (null == b) continue;
				// B1: a pending change of this component recorded after the stamp (every bucket state, D24).
				BucketFlowControl bfc = b.getFlowControl();
				String raw = null == bfc || null == bfc.fanOutChanged() || null == sc.getUuid() ? null
						: bfc.fanOutChanged().get(sc.getUuid().toString());
				Instant entry = null == raw ? null : parseInstant(raw);
				if (null != entry && entry.isAfter(fanOutAtDb) && (null == newest || entry.isAfter(newest))) {
					newest = entry;
					message = "component " + sc.getCanonicalPurl() + " changed in bucket " + b.getBucketIndex()
							+ " at " + raw + " after fanOutAt " + fanOutAt + " (rule B, change-driven)";
				}
				// B2: the bucket moved without recording (the next pass's catch-up, mirrored).
				if (null != b.getLastUpdatedDate() && isUnrecorded(b)) {
					Instant moved = b.getLastUpdatedDate().toInstant();
					if (moved.isAfter(fanOutAtDb) && (null == newest || moved.isAfter(newest))) {
						newest = moved;
						message = "bucket " + b.getBucketIndex() + " holding component " + sc.getCanonicalPurl()
								+ " moved at " + isoInstant(b.getLastUpdatedDate()) + " after fanOutAt " + fanOutAt
								+ " and is not yet recorded (rule B, change-driven; the next pass records it)";
					}
				}
			}
			if (null != message) return new CandidateAnswer(true, message);
		}
		return new CandidateAnswer(false, "not a candidate: fanned out at " + fanOutAt
				+ " and no component of it has a pending change since");
	}

	/**
	 * Whether submission in this org waits for BEAR enrichment, asked as submitOrg asks it: TRUE ships only
	 * enriched components, FALSE ships every matchable component as it is, null when rebom did not answer. Never
	 * throws and never retries: the status still answers without it.
	 */
	private Boolean enrichmentGatedOf(UUID org) {
		try {
			return rebomService.isEnrichmentConfigured(org);
		} catch (Exception e) {
			log.warn("Scan status: could not read whether BEAR enrichment is configured for org {}: {}",
					org, e.getMessage());
			return null;
		}
	}

	/** One blocker row: the bucket decides the reason, enrichment only for a row with no bucket. */
	private static ScanBlockerDto blockerRow(SbomComponent sc, Map<Integer, SyntheticDtrackBucket> bucketsByIndex,
			Boolean enrichmentGated) {
		String purl = sc.getCanonicalPurl();
		ScanBlockerEnrichmentState enrichment = null == sc.getEnrichedAt() ? ScanBlockerEnrichmentState.UNENRICHED
				: ScanBlockerEnrichmentState.ENRICHED;
		Integer index = sc.getSyntheticBucketIndex();
		SyntheticDtrackBucket bucket = null == index ? null : bucketsByIndex.get(index);
		String ingestState = null == bucket || null == bucket.getIngestState() ? null : bucket.getIngestState().name();
		ZonedDateTime bucketUpdated = null == bucket ? null : bucket.getLastUpdatedDate();
		String reason;
		if (null != index && null == bucket) {
			reason = "bucket " + index + " not found (retired)";
		} else if (null != bucket && IngestState.INGESTED != bucket.getIngestState()) {
			reason = "bucket " + index + " is " + ingestState + " since "
					+ (null == bucketUpdated ? "an unknown date" : isoInstant(bucketUpdated))
					+ "; its components are uncovered until it is INGESTED";
		} else if (null != bucket) {
			reason = "bucket " + index + " is INGESTED but its ref_map does not list this purl; "
					+ "re-submission pending";
		} else if (null != sc.getEnrichedAt()) {
			reason = "enriched but not assigned a synthetic bucket yet";
		} else if (Boolean.TRUE.equals(enrichmentGated)) {
			reason = "not enriched yet; submitOrg ships only enriched components";
		} else if (Boolean.FALSE.equals(enrichmentGated)) {
			reason = "not assigned a synthetic bucket yet; this organization has no BEAR enrichment, so submitOrg "
					+ "ships it un-enriched on a coming pass";
		} else {
			reason = "not assigned a synthetic bucket yet; enrichment state unknown (rebom did not answer whether "
					+ "BEAR enrichment is configured)";
		}
		return new ScanBlockerDto(purl, enrichment, null, index,
				ingestState, null == bucket ? null : bucketUpdated, reason);
	}

	/** A row waiting on enrichment in a BEAR-gated org: no bucket and not enriched (listed first). */
	private static boolean waitsOnEnrichment(ScanBlockerDto row, Boolean enrichmentGated) {
		return Boolean.TRUE.equals(enrichmentGated) && null == row.bucketIndex()
				&& ScanBlockerEnrichmentState.UNENRICHED == row.enrichment();
	}

	/**
	 * A stored fan-out stamp as a UTC ZonedDateTime with every digit kept; null when absent or unparseable (an
	 * unparseable value is logged at WARN, never thrown).
	 */
	private static ZonedDateTime parseStampInstant(String raw, UUID artifactUuid, String key) {
		if (null == raw) return null;
		Instant i = parseInstant(raw);
		if (null == i) {
			log.warn("Scan status: artifact {} has an unparseable {} stamp '{}'; reported as null", artifactUuid,
					key, raw);
			return null;
		}
		return i.atZone(ZoneOffset.UTC);
	}

	/** The ISO_INSTANT form, falling back to a legacy zoned value; null when neither parses. */
	private static Instant parseInstant(String raw) {
		try {
			return Instant.parse(raw);
		} catch (DateTimeParseException e) {
			try {
				return ZonedDateTime.parse(raw).toInstant();
			} catch (DateTimeParseException e2) {
				return null;
			}
		}
	}

	private static String isoInstant(ZonedDateTime t) {
		return DateTimeFormatter.ISO_INSTANT.format(t.toInstant());
	}

	/**
	 * Re-files stored findings that an ingest without the
	 * {@link CanonicalResolver} encoding fallback keyed under DTrack's re-encoded
	 * purl instead of the submitted canonical. A later DTrack re-analysis rewrites
	 * such a bucket, but only when DTrack reports its project as changed, which a
	 * stable org may never see; this repairs the stored map in place instead.
	 * No-op for a bucket whose keys are all submitted canonicals, and for keys
	 * that resolve to no canonical (left as they are).
	 *
	 * @return whether the bucket's findings were re-keyed.
	 */
	@SuppressWarnings("unchecked")
	boolean rekeyEncodingVariantFindings(SyntheticDtrackBucket bucket) {
		Map<String, Object> findings = bucket.getFindings();
		Map<String, Object> refMap = bucket.getRefMap();
		if (findings == null || findings.isEmpty() || refMap == null) return false;
		Set<Object> canonicals = new HashSet<>(refMap.values());
		if (canonicals.containsAll(findings.keySet())) return false;
		CanonicalResolver resolver = new CanonicalResolver(refMap);
		Map<String, Object> rekeyed = new LinkedHashMap<>();
		boolean changed = false;
		for (Map.Entry<String, Object> e : findings.entrySet()) {
			String key = e.getKey();
			String target = canonicals.contains(key) ? key : resolver.resolve(key, null);
			if (!key.equals(target)) changed = true;
			Object prior = rekeyed.putIfAbsent(target, e.getValue());
			if (prior instanceof Map && e.getValue() instanceof Map) {
				// Both spellings stored: keep every finding under the canonical and
				// alias-collapse the union, as ingest does for each canonical.
				Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) prior);
				for (Map.Entry<String, Object> kind : ((Map<String, Object>) e.getValue()).entrySet()) {
					List<Object> list = new ArrayList<>();
					if (merged.get(kind.getKey()) instanceof List) list.addAll((List<Object>) merged.get(kind.getKey()));
					if (kind.getValue() instanceof List) list.addAll((List<Object>) kind.getValue());
					merged.put(kind.getKey(), list);
				}
				try {
					organizeCanonicalFindings(bucket.getOrg(), merged);
				} catch (Exception ex) {
					log.warn("Alias-merge of re-keyed bucket findings failed for a canonical, keeping raw: {}", ex.getMessage());
				}
				rekeyed.put(target, merged);
			}
		}
		if (changed) bucket.setFindings(rekeyed);
		return changed;
	}

	/** Mark one artifact not scannable (TPF-125); a failure is logged and the pass continues. */
	private void markArtifactNotScannable(UUID artifactUuid, ArtifactNotScannable marker) {
		Optional<Artifact> oa = sharedArtifactService.getArtifact(artifactUuid);
		if (oa.isEmpty()) return;
		try {
			sharedArtifactService.markArtifactNotScannable(oa.get(), marker);
		} catch (Exception e) {
			log.error("Failed to mark artifact {} not scannable", artifactUuid, e);
		}
	}

	/** Clear one artifact's not-scannable marker (TPF-125); a failure is logged and the pass continues. */
	private void clearArtifactNotScannable(UUID artifactUuid) {
		try {
			sharedArtifactService.clearArtifactNotScannable(artifactUuid);
		} catch (Exception e) {
			log.error("Failed to clear the not-scannable marker of artifact {}", artifactUuid, e);
		}
	}

	/**
	 * Applies a canonical's findings to one artifact. NO_ARTIFACT when the artifact is gone (nothing
	 * to apply, not a failure), APPLIED once updateArtifactDti returned (its idempotent branch
	 * included), FAILED when it threw, logged here with the throwable (TPF-137 D26).
	 */
	private FindingsApply applyFindingsToArtifact(UUID artifactUuid, FindingSet fs) {
		Optional<Artifact> oa = sharedArtifactService.getArtifact(artifactUuid);
		if (oa.isEmpty()) return FindingsApply.NO_ARTIFACT;
		try {
			List<VulnerabilityDto> vulns = new ArrayList<>(fs.vulnMaps.size());
			for (Map<String, Object> m : fs.vulnMaps) {
				vulns.add(Utils.OM.convertValue(withArtifactSource(m, artifactUuid), VulnerabilityDto.class));
			}
			List<ViolationDto> violations = new ArrayList<>(fs.violationMaps.size());
			for (Map<String, Object> m : fs.violationMaps) {
				violations.add(Utils.OM.convertValue(withArtifactSource(m, artifactUuid), ViolationDto.class));
			}
			DependencyTrackIntegration dti = new DependencyTrackIntegration();
			dti.setVulnerabilityDetails(vulns);
			dti.setViolationDetails(violations);
			dti.setLastScanned(ZonedDateTime.now());
			sharedArtifactService.updateArtifactDti(oa.get(), dti, WhoUpdated.getAutoWhoUpdated());
			return FindingsApply.APPLIED;
		} catch (Exception e) {
			log.error("Failed to apply synthetic findings to artifact {}", artifactUuid, e);
			return FindingsApply.FAILED;
		}
	}

	/**
	 * Re-stamp a finding's source with the target artifact. The synthetic fetch
	 * has no artifact context (one bucket serves many), so findings come back with
	 * a null-artifact source; fan-out stamps the actual artifact here so the
	 * Sources column (and downstream attribution) is populated.
	 */
	private Map<String, Object> withArtifactSource(Map<String, Object> m, UUID artifactUuid) {
		Map<String, Object> clone = new LinkedHashMap<>(m);
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("artifact", artifactUuid.toString());
		clone.put("sources", List.of(source));
		return clone;
	}

	@SuppressWarnings("unchecked")
	private void mergeBucketFindings(Map<String, FindingSet> global, Map<String, Object> bucketFindings) {
		for (Map.Entry<String, Object> e : bucketFindings.entrySet()) {
			if (!(e.getValue() instanceof Map)) continue;
			String canonical = e.getKey();
			Map<String, Object> entry = (Map<String, Object>) e.getValue();
			FindingSet fs = global.computeIfAbsent(canonical, k -> new FindingSet());
			addStampedMaps(entry.get("vulns"), canonical, fs.vulnMaps);
			addStampedMaps(entry.get("violations"), canonical, fs.violationMaps);
		}
	}

	/**
	 * Copy each finding map and stamp its {@code purl} with the canonical it maps
	 * to, so the "PURL or Location" column shows a coordinate even for
	 * CPE-canonical components (the canonical IS the cpe; DTrack returns no purl
	 * for those, which would otherwise leave the column blank).
	 */
	@SuppressWarnings("unchecked")
	private void addStampedMaps(Object list, String canonical, List<Map<String, Object>> out) {
		if (!(list instanceof List)) return;
		for (Object o : (List<Object>) list) {
			if (!(o instanceof Map)) continue;
			Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
			m.put("purl", canonical);
			out.add(m);
		}
	}

	/** Accumulator of finding maps (kept as maps so purl/sources can be stamped). */
	private static final class FindingSet {
		final List<Map<String, Object>> vulnMaps = new ArrayList<>();
		final List<Map<String, Object>> violationMaps = new ArrayList<>();
		boolean isEmpty() { return vulnMaps.isEmpty() && violationMaps.isEmpty(); }
		void merge(FindingSet other) {
			vulnMaps.addAll(other.vulnMaps);
			violationMaps.addAll(other.violationMaps);
		}
	}
}
