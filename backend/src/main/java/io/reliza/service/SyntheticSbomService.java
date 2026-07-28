/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
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
import java.util.UUID;

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
import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentData;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository;
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
 * See backend/ai-plans/synthetic_dtrack_identity/00_design.md for the full design.
 */
@Service
@Slf4j
public class SyntheticSbomService {

	/** Max components per synthetic SBOM / DTrack project. */
	static final int BUCKET_SIZE = 500;

	/**
	 * Fan-out candidates processed per org per tick. Each candidate costs two
	 * light per-artifact reads plus a metrics write, so this bounds the tick's
	 * work; anything left over is picked up on the next tick, because an
	 * unstamped artifact stays a candidate.
	 */
	static final int FANOUT_BATCH_LIMIT = 500;

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
	/** Cap on CPEs emitted per component (cpe[0] on the primary; companions TODO). */
	static final int MAX_CPES = 3;

	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SyntheticDtrackBucketRepository bucketRepository;
	@Autowired private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Autowired private io.reliza.repositories.ArtifactRepository artifactRepository;
	@Autowired @org.springframework.context.annotation.Lazy private SbomComponentService sbomComponentService;
	@Autowired private SharedArtifactService sharedArtifactService;
	@Autowired private DTrackService dTrackService;
	@Autowired private RebomService rebomService;
	@Autowired @Lazy private IntegrationService integrationService;
	@Autowired @Lazy private VulnAnalysisService vulnAnalysisService;

	// ===================================================================
	// Per-org orchestration
	// ===================================================================

	/**
	 * Full cycle for one org: (re)submit changed buckets, ingest ready buckets,
	 * re-pull findings for buckets DTrack has re-analysed since {@code since}, then
	 * fan out. {@code since} bounds the "what changed on DTrack" probe — pass the
	 * last successful sync time for the daily run, or an epoch instant to force a
	 * full re-check (manual trigger).
	 */
	public void resyncOrg(UUID orgUuid, ZonedDateTime since) {
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
	}

	/**
	 * Manual, on-demand resync for one org — backs the operator "refresh from
	 * DTrack" action. Uses an epoch cutoff so every bucket project is re-checked,
	 * making an operator-triggered refresh reliably pick up findings just changed in
	 * DTrack. Async so the GraphQL mutation returns immediately.
	 */
	@org.springframework.scheduling.annotation.Async
	public void resyncOrgManualAsync(UUID orgUuid) {
		resyncOrg(orgUuid, ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC));
	}

	/**
	 * Admin "force re-upload to DTrack" action: re-submit every synthetic bucket for
	 * the org regardless of the content-hash idempotency check — into its existing
	 * DTrack project, or a fresh one if the project is gone. DTrack then re-analyses
	 * and the next ingest re-pulls findings in the current (alias-collapsed) shape,
	 * migrating buckets stored before that fix. Heavier than {@link #resyncOrgManualAsync}
	 * (full re-analysis + a brief SUBMITTED window where coverage drops) — for operator
	 * recovery, not routine. Async so the GraphQL mutation returns immediately.
	 */
	@org.springframework.scheduling.annotation.Async
	public void forceReuploadOrgAsync(UUID orgUuid) {
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
	}

	/**
	 * Re-pull findings for INGESTED buckets whose DTrack project has been
	 * re-analysed since {@code since} (new advisories published against
	 * already-scanned components — the time-varying findings that the legacy daily
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
				if (newByCanonical.equals(bucket.getFindings())) continue; // no change
				bucket.setFindings(newByCanonical);
				bucket.setLastIngested(ZonedDateTime.now());
				bucket.setLastUpdatedDate(ZonedDateTime.now());
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
	 * work this tick — a matchable component not yet assigned to a bucket (new or
	 * just-enriched), or a bucket that failed to submit and needs retry. When false,
	 * the scheduler skips {@code pullEnrichmentForOrg} + {@code submitOrg}, avoiding
	 * the rebom config probe, the full matchable scan, and the per-bucket re-hash for
	 * an idle org.
	 *
	 * <p>Two indexed {@code EXISTS} probes; bear-agnostic so the gate itself needs no
	 * rebom call. It does NOT detect a deletion that shrinks a bucket without leaving
	 * an unassigned row (rare — canonicals are sticky; self-heals on the next change).
	 * {@code ingestOrgBuckets} and {@code fanOutOrg} still run every tick regardless,
	 * so in-flight (SUBMITTED) buckets always advance to INGESTED.
	 */
	boolean hasPendingSyntheticWork(UUID orgUuid) {
		return sbomComponentRepository.existsUnbucketedMatchableByOrg(orgUuid.toString())
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
	 * are not re-sent to DTrack — unlike the prior positional slicing, where a
	 * single insertion shifted every later component's position and re-submitted
	 * every downstream bucket.
	 */
	void submitOrg(UUID orgUuid) {
		submitOrg(orgUuid, false);
	}

	/**
	 * @param force when true, re-upload every bucket to DTrack regardless of the
	 *   content-hash idempotency check — used by the admin "force re-upload" action
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
			// Conservatively treat an unknown config as BEAR-on: better to hold a
			// component back one tick than ship it un-enriched.
			log.warn("submitOrg: unable to determine BEAR config for org {}, assuming configured: {}",
					orgUuid, e.getMessage());
			bearConfigured = true;
		}
		List<SbomComponent> matchable = bearConfigured
				? sbomComponentRepository.findEnrichedMatchableByOrgOrdered(orgUuid.toString())
				: sbomComponentRepository.findMatchableByOrgOrdered(orgUuid.toString());
		if (matchable.isEmpty()) return;

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

		// A bucket whose every member was deleted (rare — canonicals are sticky)
		// would otherwise keep contributing stale coverage in fan-out. Retire it.
		retireEmptyBuckets(orgUuid, byBucket.keySet());
	}

	/**
	 * Assign a sticky bucket index to every matchable component that lacks one,
	 * first-fit into the lowest-indexed bucket with spare capacity (else a new
	 * bucket). Existing assignments are never changed, so adding a component never
	 * moves another. No-op (no DB writes) once everything is assigned — the common
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
			bucket.setLastUpdatedDate(ZonedDateTime.now());
			bucket.getRefMap().put("__token", token);
			bucketRepository.save(bucket);
		} catch (Exception e) {
			log.error("Failed to submit synthetic bucket {} for org {}", bucketIndex, orgUuid, e);
			bucket.setIngestState(IngestState.FAILED);
			bucket.setLastUpdatedDate(ZonedDateTime.now());
			bucketRepository.save(bucket);
		}
	}

	/**
	 * Clear coverage for buckets that no longer have any matchable members so
	 * fan-out stops counting their stale purls as scanned. Cheap: only writes the
	 * buckets that still carry stale state.
	 */
	private void retireEmptyBuckets(UUID orgUuid, Set<Integer> liveIndices) {
		for (SyntheticDtrackBucket b : bucketRepository.findByOrg(orgUuid)) {
			if (liveIndices.contains(b.getBucketIndex())) continue;
			boolean alreadyEmpty = (b.getRefMap() == null || b.getRefMap().isEmpty())
					&& (b.getFindings() == null || b.getFindings().isEmpty())
					&& b.getContentHash() == null;
			if (alreadyEmpty) continue;
			b.setRefMap(new LinkedHashMap<>());
			b.setFindings(new LinkedHashMap<>());
			b.setContentHash(null);
			b.setIngestState(IngestState.PENDING);
			b.setLastUpdatedDate(ZonedDateTime.now());
			bucketRepository.save(b);
		}
	}

	/** sha256 over the sorted set of canonical_purls — the bucket membership fingerprint. */
	private String contentHash(List<SbomComponent> slice) {
		List<String> purls = new ArrayList<>(slice.size());
		for (SbomComponent sc : slice) purls.add(sc.getCanonicalPurl());
		purls.sort(String::compareTo);
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for (String p : purls) {
				md.update(p.getBytes(StandardCharsets.UTF_8));
				md.update((byte) 0x01); // \u0001 separator — never NUL in keys/hashes
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
	 * each cpe) -> canonical_purl, so ingest can map findings — which come back
	 * keyed by purl OR (for cpe-only companions) cpe — back to the canonical.
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
		// The generator renders this as an RFC 3339 instant — DTrack rejects the
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
				bucket.setFindings(toFindingsByCanonical(orgUuid, findings, bucket.getRefMap()));
				bucket.setIngestState(IngestState.INGESTED);
				bucket.setLastIngested(ZonedDateTime.now());
				bucket.setLastUpdatedDate(ZonedDateTime.now());
				bucketRepository.save(bucket);
			} catch (Exception e) {
				log.error("Failed to ingest synthetic bucket {} for org {}",
						bucket.getBucketIndex(), orgUuid, e);
			}
		}
	}

	/**
	 * Group findings by canonical_purl, stored as plain JSON maps (never DTO
	 * records — JsonBinaryType cannot serialize those; convertValue also
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
		if (findings.vulns() != null) {
			for (IntegrationService.VulnWithCpe vc : findings.vulns()) {
				String canonical = resolveCanonical(identityMap, vc.vuln().purl(), vc.cpe());
				if (canonical == null) continue;
				bucketFor(byCanonical, canonical, "vulns")
						.add(Utils.OM.convertValue(vc.vuln(), LinkedHashMap.class));
			}
		}
		if (findings.violations() != null) {
			for (IntegrationService.ViolationWithCpe vc : findings.violations()) {
				String canonical = resolveCanonical(identityMap, vc.violation().purl(), vc.cpe());
				if (canonical == null) continue;
				bucketFor(byCanonical, canonical, "violations")
						.add(Utils.OM.convertValue(vc.violation(), LinkedHashMap.class));
			}
		}
		// Collapse alias-duplicate findings per canonical through the SAME mechanism
		// the artifact metrics use — VulnAnalysisService.processReleaseMetricsDto at
		// ORG scope (exactly what ArtifactService.computeArtifactMetrics runs). This
		// applies BOTH the DTrack-provided aliases AND org-wide vulnerability-analysis
		// records (which can add aliases / override severity), then re-organizes — so
		// the stored bucket findings end up in the same collapsed+enriched shape the
		// artifact write produces. Without matching it exactly, the idempotency guard
		// (findingsSignature) would keep missing and rewrite the artifact every tick,
		// churning release rollups. Reuse (not duplicate) the single enrichment path.
		for (Object entry : byCanonical.values()) {
			try {
				organizeCanonicalFindings(orgUuid, (Map<String, Object>) entry);
			} catch (Exception e) {
				// Never let a malformed finding break the whole bucket's ingest —
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

	/** Map a finding's purl-or-cpe coordinate to its canonical via the identity map. */
	private String resolveCanonical(Map<String, Object> identityMap, String purl, String cpe) {
		if (identityMap != null) {
			if (purl != null && identityMap.get(purl) != null) return String.valueOf(identityMap.get(purl));
			if (cpe != null && identityMap.get(cpe) != null) return String.valueOf(identityMap.get(cpe));
		}
		// Fallback: a purl-keyed finding is canonical by itself (purl == canonical).
		return purl;
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
	 * "Scan pending" to "Scan done" — just like the legacy path stamped
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
	 * coverage-clear (candidate starvation suspects). Rate-limited cadence only.
	 */
	private String summarizeNeverScannedBlockers(UUID orgUuid, int sampleSize) {
		try {
			Set<String> covered = new HashSet<>();
			for (SyntheticDtrackBucket bucket : bucketRepository.findByOrg(orgUuid)) {
				if (IngestState.INGESTED != bucket.getIngestState() || bucket.getRefMap() == null) continue;
				for (String k : bucket.getRefMap().keySet()) if (!"__token".equals(k)) covered.add(k);
			}
			List<UUID> sample = artifactCanonicalMapRepository
					.findNeverScannedMappedArtifactCanonicalsOlderThan(
							orgUuid, ZonedDateTime.now().minus(STALL_COMPONENT_AGE), sampleSize);
			int blockedOnCoverage = 0;
			int blockedOnEnrichment = 0;
			int coverageClear = 0;
			for (UUID canonicalArtifact : sample) {
				List<ArtifactSbomComponent> ascs = artifactSbomComponentRepository
						.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact);
				Set<UUID> compUuids = new HashSet<>();
				for (ArtifactSbomComponent asc : ascs) compUuids.add(asc.getSbomComponentUuid());
				boolean missing = false;
				boolean missingUnenriched = false;
				for (SbomComponent sc : sbomComponentRepository.findAllById(compUuids)) {
					if (sc.isRoot()) continue;
					if (sc.isEnrichmentTerminal()) continue;
					String pu = sc.getCanonicalPurl();
					if (pu == null || !(pu.startsWith("pkg:") || pu.startsWith("cpe:"))) continue;
					if (!covered.contains(pu)) {
						missing = true;
						if (sc.getEnrichedAt() == null) missingUnenriched = true;
					}
				}
				if (!missing) coverageClear++;
				else if (missingUnenriched) blockedOnEnrichment++;
				else blockedOnCoverage++;
			}
			return String.format("of %d sampled: %d blocked on un-enriched components, "
					+ "%d blocked on other uncovered components, %d coverage-clear (starvation suspects)",
					sample.size(), blockedOnEnrichment, blockedOnCoverage, coverageClear);
		} catch (Exception e) {
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

			// Third stall class (prod 2026-07-25): artifacts never scanned long
			// after upload while the org pipeline looks healthy -- unbucketed=0,
			// all buckets INGESTED -- because the fan-out candidate batch was
			// occupied by non-draining candidates. Log-only, like the other classes.
			ZonedDateTime artifactCutoff = ZonedDateTime.now().minus(STALL_COMPONENT_AGE);
			long neverScanned = artifactCanonicalMapRepository
					.countNeverScannedMappedArtifactsOlderThan(orgUuid, artifactCutoff);

			if (staleCount == 0 && stuckBuckets == 0 && neverScanned == 0) return;

			if (neverScanned > 0) {
				log.error("[FANOUT-STALL] org {}: {} mapped artifact(s) still never scanned {}h after upload. "
						+ "Sampled blocking reasons: {}. "
						+ "If none are blocked on coverage, suspect fan-out candidate starvation "
						+ "(pool not draining past FANOUT_BATCH_LIMIT).",
						orgUuid, neverScanned, STALL_COMPONENT_AGE.toHours(),
						summarizeNeverScannedBlockers(orgUuid, 5));
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

	void fanOutOrg(UUID orgUuid) {
		// From INGESTED buckets: the findings (by canonical purl) and the set of
		// canonical purls DTrack has actually scanned (the bucket ref_map keys).
		Map<String, FindingSet> global = new HashMap<>();
		Set<String> coveredPurls = new HashSet<>();
		// Newest INGESTED-bucket update = the candidate cutoff, computed here from
		// the buckets this loop already iterates (previously an SQL subquery).
		double cutoffEpoch = 0d;
		for (SyntheticDtrackBucket bucket : bucketRepository.findByOrg(orgUuid)) {
			if (IngestState.INGESTED != bucket.getIngestState()) continue;
			if (bucket.getLastUpdatedDate() != null) {
				cutoffEpoch = Math.max(cutoffEpoch, bucket.getLastUpdatedDate().toInstant().getEpochSecond());
			}
			if (bucket.getFindings() != null) mergeBucketFindings(global, bucket.getFindings());
			if (bucket.getRefMap() != null) {
				for (String k : bucket.getRefMap().keySet()) {
					if (!"__token".equals(k)) coveredPurls.add(k);
				}
			}
		}
		if (coveredPurls.isEmpty()) return;

		// Two-query candidate resolution (see ArtifactRepository.findFanOutPoolSlice
		// for why the previous joined query was replaced): first the pool slice of
		// artifacts needing a scan pass -- never-scanned first, newest upload first
		// within them -- then their canonicals via the unique artifact->canonical
		// map lookup, deduplicated preserving the slice's priority order and capped
		// at the batch limit. The old SQL EXISTS (>=1 covered component) is
		// preserved semantically by the per-canonical gate below: a candidate with
		// no matchable components is skipped WITHOUT stamping, exactly as before.
		List<UUID> poolArtifacts = artifactRepository.findFanOutPoolSlice(
				orgUuid, cutoffEpoch, FANOUT_BATCH_LIMIT * 4);
		if (poolArtifacts.isEmpty()) return;
		Map<UUID, UUID> artifactToCanonical = new HashMap<>();
		for (ArtifactCanonicalMap m : artifactCanonicalMapRepository
				.findByOrgAndArtifactUuidIn(orgUuid, poolArtifacts)) {
			artifactToCanonical.put(m.getArtifactUuid(), m.getCanonicalArtifactUuid());
		}
		Set<UUID> canonicalArtifacts = new LinkedHashSet<>();
		for (UUID artifactUuid : poolArtifacts) {
			UUID canonical = artifactToCanonical.get(artifactUuid);
			if (canonical != null) canonicalArtifacts.add(canonical);
			if (canonicalArtifacts.size() >= FANOUT_BATCH_LIMIT) break;
		}
		if (canonicalArtifacts.isEmpty()) return;

		for (UUID canonicalArtifact : canonicalArtifacts) {
			List<ArtifactSbomComponent> ascs = artifactSbomComponentRepository
					.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact);
			Set<UUID> compUuids = new HashSet<>();
			for (ArtifactSbomComponent asc : ascs) compUuids.add(asc.getSbomComponentUuid());

			// This artifact's matchable component purls. Root/self components are
			// excluded: they're the app itself (not a dependency), are never
			// submitted to synthetic DTrack, and BEAR never enriches them — so
			// requiring their coverage would block the artifact on "scan pending"
			// forever (mirrors the root exclusion in the matchable repo queries).
			Set<String> matchablePurls = new HashSet<>();
			for (SbomComponent sc : sbomComponentRepository.findAllById(compUuids)) {
				if (sc.isRoot()) continue;
				// Terminal components never ship to DTrack, so requiring their
				// coverage would scan-block the artifact forever -- excluded from
				// the gate exactly like roots (see V75).
				if (sc.isEnrichmentTerminal()) continue;
				String p = sc.getCanonicalPurl();
				if (p != null && (p.startsWith("pkg:") || p.startsWith("cpe:"))) matchablePurls.add(p);
			}
			// Only mark scanned once every matchable component is covered (scanned).
			// Otherwise a still-gated component would be reported as 0-vuln prematurely.
			if (!coveredPurls.containsAll(matchablePurls)) continue;

			// Union the (possibly empty) findings for this artifact's components.
			FindingSet artifactFindings = new FindingSet();
			for (String purl : matchablePurls) {
				FindingSet fs = global.get(purl);
				if (fs != null) artifactFindings.merge(fs);
			}

			// Apply to every actual artifact mapping to this canonical — even with
			// no findings, this bumps lastScanned (-> firstScanned) so the artifact
			// shows "Scan done".
			List<ArtifactCanonicalMap> maps = artifactCanonicalMapRepository
					.findByOrgAndCanonicalArtifactUuid(orgUuid, canonicalArtifact);
			for (ArtifactCanonicalMap m : maps) {
				applyFindingsToArtifact(m.getArtifactUuid(), artifactFindings);
			}
		}
	}

	private void applyFindingsToArtifact(UUID artifactUuid, FindingSet fs) {
		Optional<Artifact> oa = sharedArtifactService.getArtifact(artifactUuid);
		if (oa.isEmpty()) return;
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
		} catch (Exception e) {
			log.error("Failed to apply synthetic findings to artifact {}", artifactUuid, e);
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
