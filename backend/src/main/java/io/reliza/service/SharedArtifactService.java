/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CarryForwardArm;
import io.reliza.model.dto.CarryForwardPairing;
import io.reliza.model.dto.CarryForwardTally;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.DtrackFetchStatus;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.util.BackoffPolicy;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@Slf4j
public class SharedArtifactService {
	

	@Autowired
    private AuditService auditService;

	@Autowired
	private MetricsAuditRepository metricsAuditRepository;

	@Autowired
	private io.reliza.repositories.ReleaseRepository releaseRepository;

    private final String url;
    private final WebClient webClient;
    private final String registryNamespace;
    
	private final ArtifactRepository repository;


    public SharedArtifactService(
    	ArtifactRepository repository,
		@Value("${relizaprops.ociArtifacts.namespace}") String registryNamespace,
        @Value("${relizaprops.ociArtifacts.serviceUrl}") String url
	) {
		this.repository = repository;
        this.url= url;
        this.registryNamespace = registryNamespace;
        
		// Configure WebClient with increased buffer size for large OCI artifacts
		ExchangeStrategies strategies = ExchangeStrategies.builder()
			.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB buffer
			.build();
		
		this.webClient = WebClient.builder()
			.baseUrl(this.url)
			.exchangeStrategies(strategies)
			.build();
	}
	
	@Autowired
    private RebomService rebomService;

	@Autowired
	private SupportInjectionService supportInjectionService;

	/**
	 * What a conditional carry-forward seed write did. Three outcomes, not a boolean, because a scan
	 * winning the race and the write THROWING are opposite facts about the row: after a race loss the
	 * replacement holds an authoritative scan result, after a throw it is still the empty pre-seed row
	 * -- and folding both into {@code false} let the caller report a failed write as the benign
	 * "already scanned", a positive claim about a row that was never read.
	 */
	public enum SeedWriteResult { WRITTEN, RACE_LOST, WRITE_FAILED }

	/**
	 * Seed an artifact's metrics ONLY while it is still unscanned, reporting what happened.
	 *
	 * <p>Same audit-row and release-touch tail as {@link #saveArtifactMetrics}, but the write is
	 * conditional in SQL. Used exclusively by the findings carry-forward, which must lose to a real
	 * scan rather than overwrite it -- see {@code ArtifactRepository.updateMetricsIfStillUnscanned}.
	 * The write is attempted FIRST here: if a scan beat us there is nothing to audit and no release
	 * to re-enqueue, so an unconditional audit row would record a revision that never happened.
	 *
	 * @return {@link SeedWriteResult#WRITTEN} if the seed landed, {@link SeedWriteResult#RACE_LOST}
	 *   if a real scan won the race (its result stands), {@link SeedWriteResult#WRITE_FAILED} if the
	 *   write threw -- the row is unchanged and the caller must NOT treat that as a benign decline
	 */
	@Transactional
	public SeedWriteResult saveArtifactMetricsIfStillUnscanned (Artifact a, DependencyTrackIntegration metrics) {
		try {
			String metricsJson = Utils.OM.writeValueAsString(metrics);
			if (0 == repository.updateMetricsIfStillUnscanned(a.getUuid(), metricsJson)) {
				log.info("[CARRY-FORWARD] artifact {} NOT seeded: a real scan landed first and its own "
						+ "result is authoritative", a.getUuid());
				return SeedWriteResult.RACE_LOST;
			}
			writeMetricsAuditRow(a);
			touchReleasesCarrying(a);
			return SeedWriteResult.WRITTEN;
		} catch (Exception e) {
			log.error("Error seeding carry-forward metrics for artifact {}: {}", a.getUuid(), e.getMessage());
			return SeedWriteResult.WRITE_FAILED;
		}
	}

	/**
	 * Persist an artifact's metrics through the single chokepoint, with its audit row and release
	 * touches.
	 *
	 * @return how many times the row's metrics_revision was advanced. Callers that FLUSH this managed
	 *   entity afterwards must apply it themselves -- see ArtifactService's in-place seam. The sync is
	 *   NOT done here on purpose: this is the chokepoint every artifact-metrics write goes through,
	 *   including the per-minute synthetic fan-out, and dirtying the entity would make Hibernate emit
	 *   a second FULL-column UPDATE at commit (Artifact has no @DynamicUpdate) and bump its @Version
	 *   -- doubling JSONB rewrites on the hottest write path and creating optimistic-lock collisions
	 *   with concurrent writers that the previous targeted native UPDATE could not produce.
	 */
	@Transactional
	public int saveArtifactMetrics (Artifact a, DependencyTrackIntegration metrics) {
		try {
			int extraBump = writeMetricsAuditRow(a);
			String metricsJson = Utils.OM.writeValueAsString(metrics);
			repository.updateMetrics(a.getUuid(), metricsJson);
			touchReleasesCarrying(a);
			return 1 + extraBump;
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("Failed to serialize artifact metrics for artifact " + a.getUuid(), e);
		}
	}

	/**
	 * Stamp the metrics_audit row holding the content this write REPLACES.
	 *
	 * <p>Extracted so the conditional carry-forward seed and the ordinary write cannot drift: they are
	 * the same audit contract, and duplicating it is how a fix lands on one path only -- the failure
	 * mode this change has already hit five times on other seams.
	 */
	private int writeMetricsAuditRow (Artifact a) {
		if (a.getMetrics() == null) return 0;
		int revision = a.getMetricsRevision();
		int extraBump = 0;
		int maxAuditRevision = metricsAuditRepository.findMaxRevision(
				MetricsEntityType.ARTIFACT.name(), a.getUuid());
		if (maxAuditRevision >= revision) {
			revision = maxAuditRevision + 1;
			log.error("Duplicate metrics audit revision detected for artifact {} - expected {} but max audit is {}, bumping to {}",
					a.getUuid(), a.getMetricsRevision(), maxAuditRevision, revision);
			repository.bumpMetricsRevision(a.getUuid());
			extraBump = 1;
		}
		MetricsAudit audit = new MetricsAudit();
		audit.setEntityType(MetricsEntityType.ARTIFACT);
		audit.setEntityUuid(a.getUuid());
		audit.setOrg(UUID.fromString((String) a.getRecordData().get("org")));
		audit.setMetricsRevision(revision);
		audit.setRevisionCreatedDate(ZonedDateTime.now());
		audit.setEntityCreatedDate(a.getCreatedDate());
		audit.setMetrics(a.getMetrics());
		metricsAuditRepository.save(audit);
		return extraBump;
	}


	/**
	 * Event-driven rollup push. This is the single chokepoint every artifact-metrics write goes
	 * through (synthetic fan-out, SARIF/VDR ingest, legacy DTrack fetch, carry-forward), so mark the
	 * releases carrying this artifact for metrics recompute right here. Replaces reliance on the
	 * BY_OUTBOUND_DELIVERABLES polling finder, whose full jsonb expansion grows with total instance
	 * data and times out on large instances -- the poll remains only as a bounded safety net.
	 *
	 * <p>The three arms cover every way an artifact reaches a release: as a variant's outbound
	 * deliverable, via a source-code entry, and attached directly. Two GIN probes each (V68/V61),
	 * ~2.5ms, no-op when the artifact is not carried that way. No attach-time counterpart is needed
	 * for the direct arm: attaching modifies the release row, so the ordinary save already makes it
	 * visible to BY_UPDATE.
	 */
	private void touchReleasesCarrying (Artifact a) {
		releaseRepository.touchReleasesByScannedDeliverableArtifact(a.getUuid().toString());
		releaseRepository.touchReleasesByScannedSceArtifact(a.getUuid().toString());
		releaseRepository.touchReleasesByScannedArtifactDirect(a.getUuid().toString());
	}

	public Optional<Artifact> getArtifact (UUID uuid) {
		return repository.findById(uuid);
	}
	
	/**
	 * Find artifact by stored digest (REARM scope)
	 * Used for BOM deduplication. Picks the OLDEST matching artifact so the
	 * choice is deterministic as same-digest artifacts accumulate — the
	 * previous first-row pick depended on unspecified row order, which made
	 * the canonical-artifact selection wander between reconciles and
	 * fragment dedup across several "canonical" roots.
	 */
	public Optional<Artifact> findArtifactByStoredDigest(UUID orgUuid, String digest) {
		List<Artifact> artifacts = repository.findArtifactsByStoredDigest(orgUuid.toString(), digest);
		if (null == artifacts || artifacts.isEmpty()) return Optional.empty();
		return artifacts.stream().min(java.util.Comparator.comparing(Artifact::getCreatedDate));
	}
	
	public Mono<ResponseEntity<byte[]>> downloadArtifact(ArtifactData ad) throws Exception{
		Mono<ResponseEntity<byte[]>> monoResponseEntity = null;
        log.info("download artifacts for ad: {}", ad);

		if(null != ad.getInternalBom()){
			byte[] byteArray;
			// For SPDX, augmented BOM is the converted CycloneDX
			if(ad.getBomFormat().equals(BomFormat.SPDX)){
				String rebom;
				// Support version parameter for SPDX augmented downloads
				if (ad.getVersion() != null && !ad.getVersion().isEmpty()) {
					try {
						Integer version = Integer.parseInt(ad.getVersion());
						rebom = rebomService.findBomByVersion(ad.getInternalBom().id(), ad.getOrg(), version).toString();
					} catch (NumberFormatException e) {
						// Version is not numeric, fall back to latest converted CycloneDX
						rebom = (rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg(), BomFormat.CYCLONEDX)).toString();
					}
				} else {
					// No version specified, return latest converted CycloneDX
					rebom = (rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg(), BomFormat.CYCLONEDX)).toString();
				}
				// Support injection into the SPDX-augmented (converted CycloneDX) download is a
				// later slice; served as-is for now.
				byteArray = rebom.getBytes();
			} else {
				// Native CycloneDX: fetch the BOM as a JsonNode, inject the CURRENT (derived,
				// non-attested, current-state) per-component support facts, then serialize. The
				// rebom fetch is already blocked, so this is a synchronous tree edit; the signed
				// raw original served by downloadRawArtifact is a separate path and untouched.
				JsonNode bomNode;
				if (ad.getVersion() != null && !ad.getVersion().isEmpty()) {
					try {
						Integer version = Integer.parseInt(ad.getVersion());
						bomNode = rebomService.findBomByVersion(ad.getInternalBom().id(), ad.getOrg(), version);
					} catch (NumberFormatException e) {
						// Version is not numeric, fall back to latest
						bomNode = rebomService.findBomByIdJson(ad.getInternalBom().id(), ad.getOrg());
					}
				} else {
					bomNode = rebomService.findBomByIdJson(ad.getInternalBom().id(), ad.getOrg());
				}
				try {
					supportInjectionService.injectCurrentSupport(bomNode, ad.getOrg());
				} catch (Exception supportEx) {
					// Support injection is an add-on -- never fail the core BOM download because
					// of a support-resolution error (transient DB, unexpected node shape). Serve
					// the un-injected BOM and alert (this path had no DB dependency before PR2a).
					log.error("Support injection failed for artifact {} (org {}); serving un-injected BOM: {}",
							ad.getUuid(), ad.getOrg(), supportEx.getMessage(), supportEx);
					// Still run the DB-free strip so an uploader-forged reliza:support:* cannot
					// survive a resolution outage, and the disclosure marker is still stamped.
					try {
						supportInjectionService.stripForgedProvenanceAndMark(bomNode);
					} catch (Exception stripEx) {
						log.error("Support strip fallback also failed for artifact {}: {}",
								ad.getUuid(), stripEx.getMessage(), stripEx);
					}
				}
				byteArray = bomNode.toString().getBytes(StandardCharsets.UTF_8);
			}
			String bomFileName = ad.getTags().stream()
				.filter(t -> t.key().equals(CommonVariables.FILE_NAME_FIELD))
				.map(t -> t.value())
				.findFirst()
				.orElse(ad.getUuid().toString() + ".json");
			ResponseEntity<byte[]> responseEntity = ResponseEntity.ok()
				.header("Content-Disposition", "attachment; filename=\"" + bomFileName + "\"")
				.body(byteArray);
			monoResponseEntity = Mono.just(responseEntity);
		}else {
			monoResponseEntity = downloadRearmNonBomArtifact(ad);
		}

		return monoResponseEntity;
    }
	private Mono<ResponseEntity<byte[]>> downloadRearmNonBomArtifact(ArtifactData ad)throws RelizaException{
		var tags = ad.getTags();
		Boolean isDownloadable = tags.stream().anyMatch(t -> t.key().equals(CommonVariables.DOWNLOADABLE_ARTIFACT) && t.value().equalsIgnoreCase("true"));
		if(!isDownloadable)
			throw new RelizaException("No Downloadable object associated with artifact: " + ad.getUuid().toString());
		String tagValue = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.TAG_FIELD)).findFirst().orElseThrow(() -> new RelizaException("Missing TAG_FIELD for artifact: " + ad.getUuid())).value();
		String mediaType = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.MEDIA_TYPE_FIELD)).findFirst().orElseThrow(() -> new RelizaException("Missing MEDIA_TYPE_FIELD for artifact: " + ad.getUuid())).value();
		String fileName = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.FILE_NAME_FIELD)).findFirst().orElseThrow(() -> new RelizaException("Missing FILE_NAME_FIELD for artifact: " + ad.getUuid())).value();
		String resolvedFileName = StringUtils.isNotEmpty(fileName) ? fileName : tagValue;
		String ociDigest = ad.getDigestRecords().stream().filter((DigestRecord dr) -> dr.algo().equals(TeaChecksumType.SHA_256) && dr.scope().equals(DigestScope.OCI_STORAGE)).findFirst().orElseThrow().digest();
		
		// Reconstruct full repository path from stored name
		// Stored name is just "downloadable-artifacts-2026-03", need to add namespace prefix
		String repositoryName;
		if (StringUtils.isNotEmpty(ad.getOciRepositoryName())) {
			// Combine namespace with stored monthly repository name
			repositoryName = io.reliza.util.OciRepositoryUtil.constructRepositoryPath(this.registryNamespace, ad.getOciRepositoryName());
		} else {
			// Legacy artifact - use default repository
			repositoryName = io.reliza.util.OciRepositoryUtil.constructRepositoryPath(this.registryNamespace, "downloadable-artifacts");
		}
		
		// Get expected file digest for validation (ORIGINAL_FILE scope)
	Optional<String> expectedDigest = ad.getDigestRecords().stream()
		.filter(dr -> dr.algo().equals(TeaChecksumType.SHA_256) && dr.scope().equals(DigestScope.ORIGINAL_FILE))
		.map(DigestRecord::digest)
		.findFirst();
	
	return this.webClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/pull")
									.queryParam("repo", repositoryName)
							.queryParam("tag", "sha256:" + ociDigest)
							.build()
					)
					.accept(MediaType.APPLICATION_OCTET_STREAM)
					.retrieve()
					.bodyToMono(byte[].class)
					.map(data -> {
						// Validate downloaded artifact digest
						if (expectedDigest.isPresent()) {
							try {
								MessageDigest digest = MessageDigest.getInstance("SHA-256");
								byte[] hash = digest.digest(data);
								String actualDigest = bytesToHex(hash);
								
								if (!actualDigest.equalsIgnoreCase(expectedDigest.get())) {
									log.error("Digest validation failed for artifact {}. Expected: {}, Actual: {}", 
										ad.getUuid(), expectedDigest.get(), actualDigest);
									throw new RuntimeException(new RelizaException("Downloaded artifact digest does not match stored digest. Expected: " 
										+ expectedDigest.get() + ", Actual: " + actualDigest));
								}
								
								log.debug("Artifact {} digest validated successfully: {}", ad.getUuid(), actualDigest);
							} catch (Exception e) {
								if (e instanceof RuntimeException && e.getCause() instanceof RelizaException) {
									throw (RuntimeException) e;
								}
								log.error("Error validating artifact digest for {}: {}", ad.getUuid(), e.getMessage());
								throw new RuntimeException(new RelizaException("Error validating artifact digest: " + e.getMessage()));
							}
						} else {
							log.warn("No original file digest available for artifact {} - skipping digest validation", ad.getUuid());
						}
						
						return ResponseEntity.ok()
								.contentType(MediaType.parseMediaType(mediaType))
								.header("Content-Disposition", "attachment; filename=\"" + resolvedFileName + "\"")
								.body(data);
					});	
	}
	public Mono<ResponseEntity<byte[]>> downloadRawArtifact(ArtifactData ad) throws Exception{
		Mono<ResponseEntity<byte[]>> monoResponseEntity = null;

		if(null != ad.getInternalBom()){
			String rebom;
			// For SPDX BOMs, pass the format to get original SPDX instead of converted CycloneDX
			BomFormat format = ad.getBomFormat().equals(BomFormat.SPDX) ? BomFormat.SPDX : null;
			log.info("downloadRawArtifact: bomFormat={}, format parameter={}, internalBomId={}, version={}", 
				ad.getBomFormat(), format, ad.getInternalBom().id(), ad.getVersion());
			
			// Check if version is specified for version-specific downloads
			if (ad.getVersion() != null && !ad.getVersion().isEmpty()) {
				try {
					Integer version = Integer.parseInt(ad.getVersion());
					log.info("Downloading version-specific raw BOM: version={}, format={}", version, ad.getBomFormat());
					// Use findRawBomByVersion for both SPDX and CycloneDX when version is specified
					rebom = rebomService.findRawBomByVersion(ad.getInternalBom().id(), ad.getOrg(), version).toString();
				} catch (NumberFormatException e) {
					// Version is not numeric, fall back to latest with format
					log.warn("Version is not numeric: {}, falling back to latest", ad.getVersion());
					if (ad.getBomFormat().equals(BomFormat.SPDX)) {
						rebom = rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg(), format).toString();
					} else {
						rebom = rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg()).toString();
					}
				}
			} else {
				// No version specified - download latest
				if (ad.getBomFormat().equals(BomFormat.SPDX)) {
					log.info("Downloading latest raw SPDX BOM with format: {}", format);
					rebom = rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg(), format).toString();
				} else {
					log.info("Downloading latest raw CycloneDX BOM");
					rebom = rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg()).toString();
				}
			}
			
			byte[] byteArray = rebom.getBytes();
			String bomFileName = ad.getTags().stream()
				.filter(t -> t.key().equals(CommonVariables.FILE_NAME_FIELD))
				.map(t -> t.value())
				.findFirst()
				.orElse(ad.getUuid().toString() + ".json");
			ResponseEntity<byte[]> responseEntity = ResponseEntity.ok()
				.header("Content-Disposition", "attachment; filename=\"" + bomFileName + "\"")
				.body(byteArray);
			monoResponseEntity = Mono.just(responseEntity);
		}else {
			monoResponseEntity = downloadRearmNonBomArtifact(ad);
		}
		return monoResponseEntity;
    }

	@Transactional
	protected Artifact updateArtifactDti(Artifact a, DependencyTrackIntegration dti, WhoUpdated wu) {
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		DependencyTrackIntegration existingDti = ad.getMetrics();

		// Idempotency: if the artifact is already scanned and the incoming findings
		// match what's stored, skip the write entirely. The synthetic fan-out runs
		// every minute over all covered artifacts; without this guard each tick
		// re-saved every artifact's metrics (bumping lastScanned) and triggered a
		// release metrics recompute — effectively rewriting everything every minute.
		// Now we write only when findings actually change (or on first scan).
		if (existingDti != null && existingDti.getFirstScanned() != null
				&& findingsSignature(existingDti).equals(findingsSignature(dti))) {
			// Advance the scan stamp even when skipping the write. The fan-out
			// candidate query pools every artifact whose stamp predates the newest
			// bucket ingest, under the contract that a processed candidate drops
			// out; returning without stamping made unchanged artifacts PERMANENT
			// candidates after any mass re-ingest, and once more than
			// FANOUT_BATCH_LIMIT of them looped, the unordered batch starved
			// genuinely-unscanned artifacts forever (prod 2026-07-25). The
			// targeted single-field update deliberately bypasses
			// saveArtifactMetrics so no release touches fire and no metrics-audit
			// row is written -- findings did not change, which is exactly the
			// churn this guard exists to prevent.
			repository.advanceLastScannedOnly(a.getUuid());
			return a;
		}

		// A finding cannot be attributed before this artifact existed. The synthetic
		// fan-out hands over bucket findings stamped with the BUCKET's DTrack scan
		// time, so a new artifact whose components were already covered would
		// otherwise inherit an attribution predating its own creation. Clamp the
		// incoming side BEFORE the preserve-earlier merge below -- otherwise the
		// merge would keep re-selecting the stale pre-creation timestamp.
		dti.clampAttributedAtFloor(a.getCreatedDate());

		if (existingDti != null) {
			// Merge findings: keep only those in new dti, but preserve earlier attributedAt dates
			existingDti.setAttributedAtFallback(a.getCreatedDate());
			existingDti.clampAttributedAtFloor(a.getCreatedDate());
			// DIAGNOSTIC (prod incident 2026-08-13): updateFromAuthoritativeSource keeps ONLY what the
			// incoming source lists, and it distinguishes null (ignore) from EMPTY (wipe). So a scanner
			// response carrying an empty -- rather than absent -- finding list truncates the artifact, the
			// release recompute then merges to nothing, and the release visibly drops to zero findings until
			// the next good scan restores them. That flap is what makes the v3 live emit see an empty
			// pre-image, misread a re-appearance as a first scan, and disagree with the repair sweep.
			// Logged at ERROR because the instance this was found on has no SQL/kubectl access and ERROR is
			// the only channel; it fires only on an actual non-empty -> empty truncation, which should be
			// rare. Records EMPTY vs NULL for the incoming lists, but note BOTH destroy the stored findings:
			// updateFromAuthoritativeSource assigns the filtered list when the incoming one is non-null and
			// assigns NULL otherwise. An earlier version of this comment claimed null was ignored -- it is
			// not, and reading only the if-branch is how that got written. The distinction is still worth
			// printing because it says which shape the scanner returned, but neither is safe.
			int hadVulns = existingDti.getVulnerabilityDetails() == null ? 0 : existingDti.getVulnerabilityDetails().size();
			int hadViols = existingDti.getViolationDetails() == null ? 0 : existingDti.getViolationDetails().size();
			int hadWeaks = existingDti.getWeaknessDetails() == null ? 0 : existingDti.getWeaknessDetails().size();
			int incomingVulns = dti.getVulnerabilityDetails() == null ? -1 : dti.getVulnerabilityDetails().size();
			int incomingViols = dti.getViolationDetails() == null ? -1 : dti.getViolationDetails().size();
			int incomingWeaks = dti.getWeaknessDetails() == null ? -1 : dti.getWeaknessDetails().size();
			String incomingProject = dti.getDependencyTrackProject();
			existingDti.updateFromAuthoritativeSource(dti);
			int nowVulns = existingDti.getVulnerabilityDetails() == null ? 0 : existingDti.getVulnerabilityDetails().size();
			int nowViols = existingDti.getViolationDetails() == null ? 0 : existingDti.getViolationDetails().size();
			int nowWeaks = existingDti.getWeaknessDetails() == null ? 0 : existingDti.getWeaknessDetails().size();
			// Weaknesses included: the release-level loss probe counts them, so omitting them here produced
			// a loss line pointing at a truncation line that could never exist.
			// CAVEAT on the weakness arm: it can only fire ALONGSIDE a vuln/violation change, because the
			// idempotency guard above short-circuits on findingsSignature, which hashes vulnIds/severity/purl
			// and violation purl/type and NOT weaknesses. So a scan that wipes only weaknesses returns before
			// reaching this point, and the release-level probe can still report a loss with no truncation
			// partner. Including weaknesses here narrows that gap; it does not close it.
			// TOTAL wipe only, matching the release-level probe's reasoning rather than contradicting it.
			// A partial drop is ordinary churn: measured on the sandbox, "now < had" fires on 1849 of 43194
			// artifact transitions (4.3%) while the total-wipe predicate fires on 0 -- and this sits on the
			// per-minute synthetic fan-out path, so partial sensitivity would put ~1850 lines of remediation
			// and re-scan noise onto the monitored ERROR channel. Weaknesses are included because the
			// A CARRIED artifact is exempt. Findings-present with a null firstScanned is the
			// carry-forward marker (no real scan ever leaves that combination), and replacing inherited
			// findings with this artifact's own first scan result is the seam working exactly as
			// designed -- including when that result is legitimately empty because the rebuild fixed
			// everything, and including when it is empty because every component dereferenced. Without
			// this exemption carry-forward manufactures a brand-new false-positive population for this
			// probe: every clean rebuild reads as a total wipe. Note the "fires on 0 of 43194"
			// measurement quoted above PREDATES carry-forward and no longer bounds it.
			//
			// DECISION 2026-08-19 (design doc section 13.1): the exemption keys ONLY on the existing
			// carry-forward marker, and deliberately does NOT try to tell a legitimate clean scan apart
			// from an all-dereferenced empty. An earlier revision threaded a hadRealCoverage flag from
			// the synthetic fan-out to keep the probe armed on the all-dereferenced case, but that case
			// is ALL_ARTIFACTS_GONE, which this seam cannot remediate -- so the ERROR became a standing
			// per-build alert nobody could clear, on the exact channel carry-forward exists to quiet,
			// AND it was redundant: [METRICS-LOSS-PROVENANCE] already reports that population per
			// release, rate-limited and with the release uuid attached. So the artifact-level ERROR is
			// suppressed for every carried artifact's own first scan and the loss is read off the
			// release-level probe instead. The one thing given up is an artifact-level ERROR if a
			// spurious empty first scan (the 2026-08-13 shape) lands on a carried artifact rather than a
			// scanned one; that too still surfaces at the release level.
			boolean carriedReplacedByItsOwnScan = isCarriedReplacedByItsOwnScan(
					existingDti.getFirstScanned(), hadVulns + hadViols + hadWeaks);
			if (!carriedReplacedByItsOwnScan
					&& ((hadVulns > 0 && nowVulns == 0) || (hadViols > 0 && nowViols == 0)
							|| (hadWeaks > 0 && nowWeaks == 0))) {
				log.error("[METRICS-TRUNCATION] artifact {} ({}) org={} dtrackProject={} (incoming {}) "
						+ "| vulns {} -> {} | violations {} -> {} | weaknesses {} -> {} "
						+ "| incoming vulns={} violations={} weaknesses={} (-1=null, 0=empty; BOTH overwrite)",
						a.getUuid(), ad.getDisplayIdentifier(), ad.getOrg(),
						existingDti.getDependencyTrackProject(), incomingProject,
						hadVulns, nowVulns, hadViols, nowViols, hadWeaks, nowWeaks,
						incomingVulns, incomingViols, incomingWeaks);
			}
			// Copy DependencyTrack-specific fields from new dti
			existingDti.setDependencyTrackProject(dti.getDependencyTrackProject());
			existingDti.setUploadToken(dti.getUploadToken());
			existingDti.setProjectName(dti.getProjectName());
			existingDti.setProjectVersion(dti.getProjectVersion());
			existingDti.setDependencyTrackFullUri(dti.getDependencyTrackFullUri());
			existingDti.setLastScanned(dti.getLastScanned());
			if (existingDti.getFirstScanned() == null && dti.getLastScanned() != null) {
				existingDti.setFirstScanned(dti.getLastScanned());
			}
			if (dti.getUploadDate() != null) {
				existingDti.setUploadDate(dti.getUploadDate());
			}
		} else {
			// No existing metrics - use new dti as-is
			if (dti.getFirstScanned() == null && dti.getLastScanned() != null) {
				dti.setFirstScanned(dti.getLastScanned());
			}
			existingDti = dti;
		}
		saveArtifactMetrics(a, existingDti);
		return a;
	}

	/**
	 * Stable identity signature of a DTI's findings — the set of vulnerability
	 * (vulnId/severity/purl) and violation (purl/type) keys, order-independent and
	 * excluding volatile fields (attributedAt, analysisDate). Two DTIs with the
	 * same signature carry the same findings; used by {@link #updateArtifactDti}
	 * to skip redundant rewrites. A new/removed CVE changes the signature, so real
	 * changes (e.g. a newly-published advisory pulled in by the daily resync) still
	 * propagate.
	 */
	static String findingsSignature(DependencyTrackIntegration dti) {
		if (dti == null) return "";
		java.util.TreeSet<String> vulns = new java.util.TreeSet<>();
		if (dti.getVulnerabilityDetails() != null) {
			for (var v : dti.getVulnerabilityDetails()) {
				vulns.add(v.vulnId() + "\u0001" + v.severity() + "\u0001" + v.purl());
			}
		}
		java.util.TreeSet<String> viols = new java.util.TreeSet<>();
		if (dti.getViolationDetails() != null) {
			for (var v : dti.getViolationDetails()) {
				viols.add(v.purl() + "\u0001" + v.type());
			}
		}
		return "v=" + vulns + ";p=" + viols;
	}

	@Transactional
	protected void markArtifactDtrackFailed(UUID artifactUuid, String failureReason) {
		Optional<Artifact> oa = getArtifact(artifactUuid);
		if (oa.isEmpty()) {
			log.warn("markArtifactDtrackFailed: artifact not found for uuid {}", artifactUuid);
			return;
		}
		Artifact a = oa.get();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		DependencyTrackIntegration dti = ad.getMetrics();
		if (dti == null) {
			dti = new DependencyTrackIntegration();
			ad.setMetrics(dti);
		}
		dti.setDtrackSubmissionFailed(true);
		dti.setDtrackSubmissionFailureReason(failureReason);
		saveArtifactMetrics(a, dti);
	}

	@Transactional
	protected void resetArtifactDtrackFailedState(UUID artifactUuid) {
		Optional<Artifact> oa = getArtifact(artifactUuid);
		if (oa.isEmpty()) {
			log.warn("resetArtifactDtrackFailedState: artifact not found for uuid {}", artifactUuid);
			return;
		}
		Artifact a = oa.get();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		DependencyTrackIntegration dti = ad.getMetrics();
		if (dti == null) {
			return;
		}
		Boolean failed = dti.getDtrackSubmissionFailed();
		Integer attempts = dti.getDtrackSubmissionAttempts();
		if ((failed != null && failed) || (attempts != null && attempts > 0)) {
			dti.setDtrackSubmissionFailed(false);
			dti.setDtrackSubmissionAttempts(0);
			dti.setDtrackSubmissionFailureReason(null);
			saveArtifactMetrics(a, dti);
		}
	}

	/**
	 * Record a Dependency-Track FETCH failure on the artifact's metrics. Bumps
	 * the failure counter, pushes {@code dtrackFetchSkipUntil} forward by an
	 * exponential backoff window, and sets status to {@link DtrackFetchStatus#FAILED}.
	 * The previously-good vulnerabilityDetails list on the artifact is left
	 * untouched — the contract is "fetch failed, don't overwrite, retry later."
	 *
	 * <p>Distinct from {@link #markArtifactDtrackFailed} which is the SUBMISSION-side
	 * marker (uploads to DT). Both can coexist on the same artifact, e.g. an
	 * artifact whose initial submission failed AND whose subsequent reattempt at
	 * fetching metrics also failed.
	 *
	 * <p>If a concurrent scheduler tick races and the JPA optimistic-lock
	 * {@code @Version} on Artifact fires, the exception propagates — the next
	 * tick will pick the artifact up again, so no retry loop here.
	 */
	@Transactional
	protected void markArtifactDtrackFetchFailed(UUID artifactUuid, String failureReason) {
		Optional<Artifact> oa = getArtifact(artifactUuid);
		if (oa.isEmpty()) {
			log.warn("markArtifactDtrackFetchFailed: artifact not found for uuid {}", artifactUuid);
			return;
		}
		Artifact a = oa.get();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		DependencyTrackIntegration dti = ad.getMetrics();
		if (dti == null) {
			dti = new DependencyTrackIntegration();
			ad.setMetrics(dti);
		}
		int currentCount = dti.getDtrackFetchFailureCount() == null ? 0 : dti.getDtrackFetchFailureCount();
		int nextCount = currentCount + 1;
		dti.setDtrackFetchStatus(DtrackFetchStatus.FAILED);
		dti.setDtrackFetchFailureCount(nextCount);
		dti.setDtrackFetchFailureReason(truncate(failureReason, DependencyTrackIntegration.FETCH_FAILURE_REASON_MAX_LEN));
		// Store as an Instant ISO-8601 string (".....Z"), not ZonedDateTime.toString():
		// the latter appends the zone-region suffix (e.g. "...Z[GMT]") on a GMT-TZ pod,
		// which Postgres can't cast to timestamptz in the scheduler pickup query.
		dti.setDtrackFetchSkipUntil(Instant.now()
				.plusSeconds(BackoffPolicy.dtrackFetchSkipSeconds(nextCount))
				.toString());
		saveArtifactMetrics(a, dti);
	}

	/**
	 * Clear any previously-set fetch-failure state after a successful drain.
	 * Guarded no-op when state is already clean so the metrics row isn't
	 * pointlessly bumped on every successful tick.
	 */
	@Transactional
	protected void resetArtifactDtrackFetchFailedState(UUID artifactUuid) {
		Optional<Artifact> oa = getArtifact(artifactUuid);
		if (oa.isEmpty()) {
			log.warn("resetArtifactDtrackFetchFailedState: artifact not found for uuid {}", artifactUuid);
			return;
		}
		Artifact a = oa.get();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		DependencyTrackIntegration dti = ad.getMetrics();
		if (dti == null) {
			return;
		}
		DtrackFetchStatus status = dti.getDtrackFetchStatus();
		Integer count = dti.getDtrackFetchFailureCount();
		String skipUntil = dti.getDtrackFetchSkipUntil();
		boolean dirty = (status != null && status != DtrackFetchStatus.OK)
				|| (count != null && count > 0)
				|| skipUntil != null
				|| dti.getDtrackFetchFailureReason() != null;
		if (dirty) {
			dti.setDtrackFetchStatus(DtrackFetchStatus.OK);
			dti.setDtrackFetchFailureCount(0);
			dti.setDtrackFetchFailureReason(null);
			dti.setDtrackFetchSkipUntil(null);
			saveArtifactMetrics(a, dti);
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) return null;
		return s.length() <= max ? s : s.substring(0, max);
	}

	/**
	 * Saves an artifact without adding a version snapshot.
	 * Used for version history transfers where we don't want to create a new snapshot.
	 */
	@Transactional
	protected Artifact saveArtifactWithoutSnapshot(Artifact a, ArtifactData ad, WhoUpdated wu) {
		return saveArtifactWithoutSnapshot(a, Utils.dataToRecord(ad), ad, wu);
	}

	@Transactional
	protected Artifact saveArtifactWithoutSnapshot(Artifact a, Map<String, Object> recordData, WhoUpdated wu) {
		return saveArtifactWithoutSnapshot(a, recordData, null, wu);
	}

	@Transactional
	protected Artifact saveArtifactWithoutSnapshot(Artifact a, Map<String, Object> recordData, ArtifactData ad, WhoUpdated wu) {
		if(recordData.containsKey("uuid") && null != recordData.get("uuid") && recordData.get("uuid").toString().equals(a.getUuid().toString())){
			log.debug("record and object ids equal");
		}else{
			log.warn("unequal record and object id");
			log.warn("record data: {}", recordData);
			log.warn("art object: {}", a);
		}
		
		Optional<Artifact> oa = getArtifact(a.getUuid());
		if (oa.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.ARTIFACTS, a);
			a.setLastUpdatedDate(ZonedDateTime.now());
		}
		a.setRecordData(recordData);
		if (ad != null && ad.getMetrics() != null) {
			a.setMetrics(Utils.OM.convertValue(ad.getMetrics(), LinkedHashMap.class));
		}
		a = (Artifact) WhoUpdated.injectWhoUpdatedData(a, wu);
		return repository.save(a);
	}

	@Transactional
	protected Artifact saveArtifact (Artifact a, ArtifactData ad, WhoUpdated wu) {
		return saveArtifact(a, Utils.dataToRecord(ad), ad, wu);
	}

	@Transactional
	protected Artifact saveArtifact (Artifact a, Map<String, Object> recordData, WhoUpdated wu) {
		return saveArtifact(a, recordData, null, wu);
	}

	@Transactional
	protected Artifact saveArtifact (Artifact a, Map<String, Object> recordData, ArtifactData ad, WhoUpdated wu) {
		if(recordData.containsKey("uuid") && null != recordData.get("uuid") && recordData.get("uuid").toString().equals(a.getUuid().toString())){
			log.debug("record and object ids equal");
		}else{
			log.warn("unequal record and object id");
			log.warn("record data: {}", recordData);
			log.warn("art object: {}", a);
		}
		// let's add some validation here
		// TODO: add validation
		Optional<Artifact> oa = getArtifact(a.getUuid());
		if (oa.isPresent()) {
			log.debug("existing artifact object: {}", oa.get());
			
			// Create version snapshot of current state before updating
			ArtifactData currentArtifactData = ArtifactData.dataFromRecord(oa.get());
			ArtifactData newArtifactData = Utils.OM.convertValue(recordData, ArtifactData.class);
			
			// Only create version snapshot if version has changed
			String currentVersion = currentArtifactData.getVersion();
			String newVersion = newArtifactData.getVersion();
			boolean versionChanged = (currentVersion == null && newVersion != null) ||
									 (currentVersion != null && !currentVersion.equals(newVersion));
			
			if (versionChanged) {
				ArtifactData.ArtifactVersionSnapshot currentSnapshot = ArtifactData.ArtifactVersionSnapshot.fromArtifactData(currentArtifactData);
				
				// Preserve existing version history from the current artifact
				if (currentArtifactData.getPreviousVersions() != null && !currentArtifactData.getPreviousVersions().isEmpty()) {
					// Add existing versions that aren't already present in the new data (avoid duplicates)
					for (ArtifactData.ArtifactVersionSnapshot existingSnapshot : currentArtifactData.getPreviousVersions()) {
						if (newArtifactData.getPreviousVersions() == null || !newArtifactData.getPreviousVersions().contains(existingSnapshot)) {
							newArtifactData.addVersionSnapshot(existingSnapshot);
						}
					}
				}
				newArtifactData.addVersionSnapshot(currentSnapshot);
				recordData = Utils.dataToRecord(newArtifactData);
			}
			
			auditService.createAndSaveAuditRecord(TableName.ARTIFACTS, a);
			a.setLastUpdatedDate(ZonedDateTime.now());
		}
		a.setRecordData(recordData);
		if (ad != null && ad.getMetrics() != null) {
			a.setMetrics(Utils.OM.convertValue(ad.getMetrics(), LinkedHashMap.class));
		}
		a = (Artifact) WhoUpdated.injectWhoUpdatedData(a, wu);
		return repository.save(a);
	}

	/**
	 * Transfers version history from one artifact to another during replacement scenarios,
	 * and carries the predecessor's FINDINGS forward until the replacement's own scan lands.
	 *
	 * <p>The carry-forward is the fix for the incomplete-scan finding loss: a release re-derives its
	 * findings by merging its artifacts' metrics, so a replacement that has not been scanned yet
	 * contributes nothing and the release collapses to zero until the scan arrives. Seeding the
	 * replacement means the merge always has something to merge. See
	 * {@code ai-agents/findings-carry-forward-design.md}.
	 *
	 * @param oldArtifactUuid UUID of the artifact being replaced
	 * @param newArtifactUuid UUID of the new artifact that should receive the version history
	 * @param wu WhoUpdated information for audit trail
	 * @return true if transfer was successful, false otherwise
	 */
	@Transactional
	public boolean transferArtifactVersionHistory(UUID oldArtifactUuid, UUID newArtifactUuid, WhoUpdated wu) {
		CarryForwardOutcome outcome = transferArtifactVersionHistoryInternal(oldArtifactUuid,
				newArtifactUuid, wu, true);
		if (CarryForwardOutcome.SEEDED == outcome) {
			// ERROR only on the MANUAL seam. The instance this matters on retains ERROR only, and this
			// line has to be correlated against [METRICS-LOSS] and [METRICS-LOSS-PROVENANCE] -- a
			// pairing that is impossible if only one half leaves the pod. Manual BOM replacement is a
			// rare user action, so the volume is nil. The REBUILD seam reaches the same carry-forward
			// through executeCarryForward, which deliberately does NOT log
			// here: it fires on every CI build and belongs in that seam's bounded aggregate instead.
			log.error("[CARRY-FORWARD] artifact {} seeded from predecessor {}, left unscanned",
					newArtifactUuid, oldArtifactUuid);
		} else if (CarryForwardOutcome.ALREADY_SCANNED == outcome) {
			// Only the SURPRISING decline goes to the monitored channel. A replacement that was
			// already scanned means the fan-out beat the upload, which is rare and worth seeing.
			log.error("[CARRY-FORWARD] artifact {} NOT seeded from predecessor {}: replacement was "
					+ "already scanned or carried its own findings; that result is authoritative",
					newArtifactUuid, oldArtifactUuid);
		} else if (CarryForwardOutcome.SEED_WRITE_FAILED == outcome) {
			// Distinct from the line above: here the seed write THREW rather than losing a race, so the
			// replacement was NOT reliably seeded. Do not claim its result is authoritative -- there is
			// none. The wording stays "may not have carried" rather than "is empty" on purpose: the
			// write is @Transactional and swallows its own exception, so a throw in the audit/touch tail
			// AFTER the conditional UPDATE committed would leave the row seeded but un-requeued -- still
			// a failure to surface, but not necessarily an empty row. Actionable either way, so it goes
			// to the monitored channel.
			log.error("[CARRY-FORWARD] artifact {} NOT seeded from predecessor {}: the seed write FAILED; "
					+ "the replacement may not have carried its predecessor's findings and the release "
					+ "may read zero until a real scan lands", newArtifactUuid, oldArtifactUuid);
		}
		return CarryForwardOutcome.ARTIFACT_MISSING != outcome
				&& CarryForwardOutcome.SEED_WRITE_FAILED != outcome;
	}

	/**
	 * Execute an arm's carry-forward: seed every pair, classify every outcome, report once.
	 *
	 * <p><b>This is the shared tail all three rebuild arms run.</b> The arms differ only in how they
	 * decide that two BOMs are the same thing across a rebuild -- names plus purl corroboration for
	 * deliverables, sole-BOM for flat artifact sets -- and that decision is made BEFORE this call and
	 * handed in as a {@link CarryForwardPairing}. Everything after it is identical, so it lives here
	 * exactly once.
	 *
	 * <p>That is not tidiness. This change's dominant defect, five times over, was a fix applied to
	 * one arm of a symmetric pair and called done: the seam itself covered deliverables but not
	 * release-direct/SCE; the seeded-vs-paired count was corrected on one arm; the duplicate-name
	 * guard de-duplicated one side; the ERROR-to-INFO move was applied to one arm; and the exclusion
	 * of no-op outcomes from the alert gate covered one of the two no-op shapes. Each was found by a
	 * later review round, and the last four rounds found 4, 10, 5 and 10 defects without converging.
	 * With one tail, a fix here lands on every arm by construction and that class of bug cannot recur.
	 *
	 * <p>The switch is exhaustive over {@link CarryForwardOutcome} on purpose: adding a fifth outcome
	 * must be a compile error here rather than a value silently falling into whichever bucket the
	 * previous author happened to make the default. ARTIFACT_MISSING in particular was previously
	 * collapsed into the benign "nothing to carry" bucket by a boolean return, which excluded a
	 * genuine failure from the alert gate.
	 *
	 * @param pairing what to seed and why the rest were declined
	 * @param arm which ownership arm, for the log line
	 * @param releaseUuid the release being rebuilt -- an ERROR line without it is unactionable
	 */
	@Transactional
	public CarryForwardTally executeCarryForward (CarryForwardPairing pairing, CarryForwardArm arm,
			UUID releaseUuid, WhoUpdated wu) {
		int seeded = 0;
		int nothingToCarry = 0;
		int alreadyScanned = 0;
		int artifactMissing = 0;
		int seedWriteFailed = 0;
		for (CarryForwardPairing.BomPair pair : pairing.paired()) {
			// transferLineage=false: this is the REBUILD path. See the parameter's javadoc -- the
			// chain grows without bound on every CI build and restates history the release model
			// already holds. Findings carry forward; version lineage deliberately does not.
			CarryForwardOutcome outcome = transferArtifactVersionHistoryInternal(
					pair.predecessorBom(), pair.successorBom(), wu, false);
			// A switch EXPRESSION, not a statement. Only the expression form is checked for
			// exhaustiveness over an enum, so this is what actually makes adding a fifth outcome a
			// compile error here -- the arrow-form STATEMENT this replaced compiled fine with a
			// constant unhandled, which would have let it fall into no bucket at all while still
			// counting toward candidates. The javadoc claimed the guarantee; now the code provides it.
			// (Deliberately no default: a default would restore the silent-fallthrough it prevents.)
			CarryForwardOutcome counted = switch (outcome) {
				case SEEDED -> CarryForwardOutcome.SEEDED;
				case NOTHING_TO_CARRY -> CarryForwardOutcome.NOTHING_TO_CARRY;
				// A scan beating the seed is the correct outcome and not a decline to worry about --
				// the replacement's own result is authoritative and this seam deliberately loses that
				// race. But it is NOT the same as a clean predecessor, and folding the two together
				// is what the enum exists to prevent: a fan-out that consistently beats the seed would
				// otherwise report as the healthy "predecessor carried nothing" shape.
				case ALREADY_SCANNED -> CarryForwardOutcome.ALREADY_SCANNED;
				case ARTIFACT_MISSING -> CarryForwardOutcome.ARTIFACT_MISSING;
				// The conditional seed write threw. Distinct from ALREADY_SCANNED: the row is still
				// collapsed to zero, so this is a real failure, not a race the seam meant to lose.
				case SEED_WRITE_FAILED -> CarryForwardOutcome.SEED_WRITE_FAILED;
			};
			// Switched on the ENUM, not on an int the switch decoded into. Decoding to 0/1/2/3 and
			// unpacking with an if/else chain reintroduced exactly the silent default the exhaustive
			// switch exists to prevent: a fifth constant would compile and land in the final else.
			switch (counted) {
				case SEEDED: seeded++; break;
				case NOTHING_TO_CARRY: nothingToCarry++; break;
				case ALREADY_SCANNED: alreadyScanned++; break;
				case ARTIFACT_MISSING: artifactMissing++; break;
				case SEED_WRITE_FAILED: seedWriteFailed++; break;
			}
		}
		CarryForwardTally tally = new CarryForwardTally(seeded, pairing.candidates(),
				pairing.unpaired(), pairing.purlConflict(), pairing.bomCountAmbiguous(),
				pairing.noBom(), nothingToCarry, alreadyScanned, artifactMissing, seedWriteFailed);
		if (0 == tally.candidates()) {
			// Nothing was even considered -- there was no prior set, so this is a first create rather
			// than a rebuild. The deliverable seam is not gated on the rebuild flag (it runs whenever
			// outbound deliverables are attached), so without this every ordinary release create
			// would emit a "0 of 0 seeded" line. Say nothing when nothing happened.
			return tally;
		}
		// ONE line per arm per rebuild, always INFO. There is deliberately no ERROR gate here.
		// An earlier revision had one, and it was the single largest source of defects on this
		// change: it fired on healthy shapes (a deliverable with no SBOM, a predecessor that was
		// legitimately clean), its counters diverged between the two arms, and on a genuinely
		// unpairable component it produced a standing per-build alert nobody could clear -- on the
		// exact channel this work exists to quiet.
		//
		// It was also redundant. [METRICS-LOSS-PROVENANCE] already answers "did carry-forward fail?"
		// per release and with the release uuid attached: a seeded replacement prints
		// firstScanned=null:findings=N, a failed pairing prints firstScanned=null:findings=0. That
		// probe is deployed, rate-limited, and correlated with the alert the customer actually
		// watches. This line is a diagnostic, not an alert, and belongs at INFO.
		log.info("[CARRY-FORWARD] {} arm, release {}: {} of {} seeded from a predecessor "
				+ "(nothingToCarry={} alreadyScanned={} noBom={} unpaired={} purlConflict={} "
				+ "bomCountAmbiguous={} artifactMissing={} seedWriteFailed={})",
				arm.label(), releaseUuid, tally.seeded(), tally.candidates(), tally.nothingToCarry(),
				tally.alreadyScanned(), tally.noBom(), tally.unpaired(), tally.purlConflict(),
				tally.bomCountAmbiguous(), tally.artifactMissing(), tally.seedWriteFailed());
		return tally;
	}


	/**
	 * What a carry-forward attempt actually did. An enum rather than a boolean because the outcomes
	 * need different responses: "it ran and had nothing to carry", "a scan beat it", "the rows were
	 * not there" and "the seed write threw" are not the same failure, and a release that still
	 * collapses cannot be triaged without knowing which. SEED_WRITE_FAILED is kept apart from
	 * ALREADY_SCANNED on purpose: both stop short of SEEDED, but only the former leaves the row
	 * collapsed to zero -- reporting it as a benign race loss asserts an authoritative scan result the
	 * replacement does not have.
	 */
	public enum CarryForwardOutcome { SEEDED, NOTHING_TO_CARRY, ALREADY_SCANNED, ARTIFACT_MISSING, SEED_WRITE_FAILED }

	// No @Transactional: this is only ever self-invoked from the two public wrappers above, so the
	// proxy is bypassed and the annotation would be inert -- the transaction genuinely comes from
	// them. Private for the same reason: an external caller would silently get no isolation.
	/**
	 * @param transferLineage whether to also copy the predecessor's previousVersions chain and append
	 *   a snapshot of it. TRUE only for the MANUAL replace, which is what that behaviour was built
	 *   for and where it is a rare user action. FALSE for the rebuild arms: they run on every CI
	 *   build, the chain is inherited whole and appended to each time, and it is stored inside the
	 *   artifact's record_data JSONB -- so an N-build component reaches N-1 snapshots in one row,
	 *   O(N^2) across rows, re-parsed by every dataFromRecord on the metrics hot path, plus a generic
	 *   audit row per pair per build on a table already carrying a rotate-and-drop workaround.
	 *   It also bought nothing: a rebuild's chain merely restates build history the release and
	 *   branch model already hold, and because the transfer ran BEFORE the findings guard it was
	 *   charged even to pairs that carried nothing -- so a component with a clean BOM paid on every
	 *   build forever while carry-forward did nothing for it.
	 */
	private CarryForwardOutcome transferArtifactVersionHistoryInternal(UUID oldArtifactUuid,
			UUID newArtifactUuid, WhoUpdated wu, boolean transferLineage) {
		try {
			Optional<Artifact> oldArtifactOpt = getArtifact(oldArtifactUuid);
			Optional<Artifact> newArtifactOpt = getArtifact(newArtifactUuid);

			if (oldArtifactOpt.isPresent() && newArtifactOpt.isPresent()) {
				ArtifactData newArtifact = ArtifactData.dataFromRecord(newArtifactOpt.get());
				ArtifactData oldArtifact = ArtifactData.dataFromRecord(oldArtifactOpt.get());

				if (transferLineage) {
					// Transfer version history from old artifact to new artifact
					newArtifact.transferVersionHistory(oldArtifact);
					// Save the updated artifact WITHOUT adding a self-snapshot
					saveArtifactWithoutSnapshot(newArtifactOpt.get(), newArtifact, wu);
				}

				// Findings carry-forward, AFTER the snapshot save. Deliberately a separate write
				// through saveArtifactMetrics rather than folded into the call above: that is the
				// single chokepoint every artifact-metrics write passes through, so it is what
				// writes the metrics_audit row and pushes the carrying releases back into the
				// recompute pool. Going through saveArtifactWithoutSnapshot instead would update
				// the column silently and leave the release showing the predecessor's findings
				// credited to an artifact no longer attached to it.
				DependencyTrackIntegration inherited = inheritFindingsFromPredecessor(
						newArtifact.getMetrics(), oldArtifact.getMetrics());
				// Everything in here logs at INFO and returns an OUTCOME instead. Deciding what reaches
				// the monitored channel is the CALLER's job, because the two callers need opposite
				// answers: the manual seam is a rare user action whose events must be correlatable
				// against [METRICS-LOSS], while the rebuild seam fires on every CI build and would
				// otherwise put steady volume on the alerting channel this work exists to quiet.
				// The outcome is an enum rather than a boolean because "it ran and had nothing to
				// carry", "a scan beat it" and "the rows were not there" need different responses.
				if (null != inherited) {
					// CONDITIONAL write. The in-Java guard above read this row earlier in the
					// transaction, and the synthetic fan-out writes artifact metrics on its own PT1M
					// tick with no shared lock -- so a scan landing in between would otherwise be
					// clobbered by the predecessor's findings, showing a fixed CVE as still open or
					// hiding a newly introduced one until something re-scanned. Re-testing the
					// condition in the UPDATE's own WHERE makes the database the arbiter.
					SeedWriteResult writeResult = saveArtifactMetricsIfStillUnscanned(
							newArtifactOpt.get(), inherited);
					switch (writeResult) {
						case RACE_LOST:
							// A real scan beat the seed. Its result is authoritative and this seam
							// deliberately loses that race -- benign.
							return CarryForwardOutcome.ALREADY_SCANNED;
						case WRITE_FAILED:
							// The write threw and the row is still the empty pre-seed row. This is NOT a
							// race loss: reporting it as ALREADY_SCANNED would assert the replacement holds
							// an authoritative scan result it does not have, on a row still collapsed to
							// zero. Its own distinct outcome so the alert gate and the tally can tell a
							// failed seed from a benign one.
							return CarryForwardOutcome.SEED_WRITE_FAILED;
						case WRITTEN:
							break;
					}
					log.info("[CARRY-FORWARD] artifact {} seeded from predecessor {} with {} finding(s), "
							+ "left unscanned", newArtifactUuid, oldArtifactUuid,
							countInheritedFindings(inherited));
					return CarryForwardOutcome.SEEDED;
				}
				if (null != newArtifact.getMetrics()
						&& (null != newArtifact.getMetrics().getFirstScanned()
								|| countInheritedFindings(newArtifact.getMetrics()) > 0)) {
					return CarryForwardOutcome.ALREADY_SCANNED;
				}
				// The ordinary decline: nothing to carry. Fires on every VEX / attestation /
				// signature replacement and on any BOM whose predecessor was legitimately clean, so
				// it is NOT rare and must not sit on the alerting channel.
				log.info("[CARRY-FORWARD] artifact {} not seeded from predecessor {}: predecessor "
						+ "carried no findings (genuine first scan)", newArtifactUuid, oldArtifactUuid);
				return CarryForwardOutcome.NOTHING_TO_CARRY;
			}

			return CarryForwardOutcome.ARTIFACT_MISSING;
		} catch (Exception e) {
			log.error("Error transferring version history from {} to {}: {}", oldArtifactUuid, newArtifactUuid, e.getMessage());
			return CarryForwardOutcome.ARTIFACT_MISSING;
		}
	}

	/**
	 * Build the metrics a REPLACEMENT artifact should carry until its own scan lands: the
	 * predecessor's findings, with both scan stamps left NULL.
	 *
	 * <p>Static, and it does NOT mutate either argument -- the successor is cloned first. That keeps
	 * the decision unit-testable rather than only reachable through a persistence path, and it means
	 * the caller's {@code ArtifactData} is not silently altered behind the snapshot save that runs
	 * just before this. An earlier revision mutated the argument in place; it was harmless only
	 * because of statement order, which is not a property worth depending on.
	 *
	 * <p><b>Both stamps must stay null.</b> That is what keeps the release's {@code anyBomUnscanned}
	 * true, so the release still reports "scan pending" and its {@code firstScanned} stays wiped.
	 * (The recompute when the real scan lands comes from {@code saveArtifactMetrics} firing
	 * {@code touchReleasesByScannedDeliverableArtifact}, not from this flag.)
	 *
	 * <p>Null stamps are also the marker for "inherited, not yet confirmed": every write path that
	 * sets artifact findings either sets or preserves {@code firstScanned}, so findings-present with
	 * a null stamp cannot arise from a real scan. Verified 2026-08-17 across {@code
	 * saveArtifactMetrics}' call sites -- note two near-misses that hold for indirect reasons rather
	 * than by construction: {@code ArtifactService.computeArtifactMetrics} never sets the stamp and
	 * survives only because {@code DependencyTrackIntegration.fromReleaseMetricsDto} copies it
	 * through, and {@code DTrackService.fetchVulnsAndBuildDone} builds findings with no stamp at all
	 * but terminates at a read-only fetcher and never persists. Re-check both if either changes.
	 *
	 * <p>{@code computeMetricsFromFacts} defaults a null {@code lastScanned} to now(), so the stamps
	 * are nulled AFTER that call, not before.
	 *
	 * <p><b>Finding attribution is deliberately NOT re-pointed at the successor.</b> Each finding's
	 * {@code sources[].artifact} keeps naming the predecessor, because that is where the finding was
	 * actually observed. Re-pointing would make the loss probe's {@code lostFrom} and
	 * {@code gatheredNow} identical and collapse its verdict from ARTIFACTS_SWAPPED to
	 * SAME_ARTIFACT_LOST_IN_PLACE, sending the next investigation to artifact metrics when the fault
	 * is wiring. It would also silently restate the VEX rulings that ride on those same source
	 * records ({@code analysisState} / {@code analysisDate}).
	 *
	 * <p>Inherited findings keep the PREDECESSOR's {@code attributedAt}, which predates the successor
	 * and so violates the "a finding cannot be attributed before this artifact existed" floor that
	 * {@code clampAttributedAtFloor} asserts elsewhere. That is deliberate and temporary: the dates
	 * are the truth about when the finding was observed, and the first real scan clamps them. Do not
	 * "fix" it by clamping here -- that would restamp every carried finding to the replacement's
	 * creation instant and destroy the age information the changelog reads.
	 *
	 * <p>Returns null when there is nothing to do, so the caller performs no write.
	 *
	 * @param successorMetrics the replacement's current metrics; its DTrack plumbing is preserved
	 * @param predecessorMetrics the replaced artifact's metrics, the source of the findings
	 * @return metrics to persist on the successor, or null to leave it alone
	 */
	static DependencyTrackIntegration inheritFindingsFromPredecessor(DependencyTrackIntegration successorMetrics,
			DependencyTrackIntegration predecessorMetrics) {
		if (null == predecessorMetrics || 0 == countInheritedFindings(predecessorMetrics)) {
			// Nothing to carry. Covers a first upload, and a replacement whose predecessor was
			// itself never scanned -- both are genuine first scans and must stay that way.
			return null;
		}
		// Never overwrite a real scan. The replacement is normally seconds old and unscanned, but
		// the upload and the fan-out are independent, so a scan CAN land first; that result is
		// authoritative and inheriting over it would resurrect findings the scan just cleared.
		if (null != successorMetrics && null != successorMetrics.getFirstScanned()) {
			return null;
		}
		// Never overwrite findings the successor already carries, stamped or not.
		//
		// Defence in depth, and the reason matters because an earlier draft of this comment got it
		// WRONG: it claimed inline-parsed SARIF / VDR / BOV arrive without a firstScanned stamp. They
		// do not -- RebomService.parseSarifOnRebom and parseCycloneDxContent both set firstScanned and
		// lastScanned explicitly so the UI artifact-row gate works, and artifactDataFactory copies the
		// stamp through fromReleaseMetricsDto. So the guard ABOVE already declines every SARIF/VDR/BOV
		// re-upload and this arm never fires on them today.
		//
		// It is kept because it is the only thing standing between a findings-bearing upload and a
		// silent overwrite if those setFirstScanned calls are ever removed or a new inline-parsed
		// artifact type lands without them -- and the damage would be unrecoverable, since no later
		// Dependency-Track scan corrects a non-DTrack artifact type. Do not delete it on the grounds
		// that it is currently unreachable.
		if (null != successorMetrics && countInheritedFindings(successorMetrics) > 0) {
			return null;
		}
		// CLONE the successor rather than mutating it: its DTrack plumbing (submission attempts, fetch
		// state) survives, only the finding-bearing fields come from the predecessor, and the caller's
		// object is left alone. clone() gives fresh detail lists, so no list is shared with either
		// argument.
		DependencyTrackIntegration inherited = (null != successorMetrics)
				? (DependencyTrackIntegration) successorMetrics.clone() : new DependencyTrackIntegration();
		inherited.setVulnerabilityDetails(null != predecessorMetrics.getVulnerabilityDetails()
				? new LinkedList<>(predecessorMetrics.getVulnerabilityDetails()) : new LinkedList<>());
		inherited.setViolationDetails(null != predecessorMetrics.getViolationDetails()
				? new LinkedList<>(predecessorMetrics.getViolationDetails()) : new LinkedList<>());
		inherited.setWeaknessDetails(null != predecessorMetrics.getWeaknessDetails()
				? new LinkedList<>(predecessorMetrics.getWeaknessDetails()) : new LinkedList<>());
		// The SUMMARY scalars computeMetricsFromFacts does not re-derive. Without this they would keep
		// the successor clone's zeros, so a carried artifact reported a full detail list alongside
		// components=0, findingsTotal=0 and suppressed=0 -- neither the predecessor's numbers nor a
		// derivation of the carried facts, just wrong. They describe the findings we just carried, so
		// they come from where those findings came from. (computeMetricsFromFacts below then overwrites
		// every field it DOES derive, so the two can never disagree; these are only the remainder.)
		inherited.setComponents(predecessorMetrics.getComponents());
		inherited.setVulnerableComponents(predecessorMetrics.getVulnerableComponents());
		inherited.setSuppressed(predecessorMetrics.getSuppressed());
		inherited.setFindingsTotal(predecessorMetrics.getFindingsTotal());
		inherited.setFindingsAudited(predecessorMetrics.getFindingsAudited());
		inherited.setFindingsUnaudited(predecessorMetrics.getFindingsUnaudited());
		inherited.setInheritedRiskScore(predecessorMetrics.getInheritedRiskScore());
		inherited.setPolicyViolationsFail(predecessorMetrics.getPolicyViolationsFail());
		inherited.setPolicyViolationsWarn(predecessorMetrics.getPolicyViolationsWarn());
		inherited.setPolicyViolationsInfo(predecessorMetrics.getPolicyViolationsInfo());
		inherited.setPolicyViolationsAudited(predecessorMetrics.getPolicyViolationsAudited());
		inherited.setPolicyViolationsUnaudited(predecessorMetrics.getPolicyViolationsUnaudited());
		inherited.setPolicyViolationsLicenseAudited(predecessorMetrics.getPolicyViolationsLicenseAudited());
		inherited.setPolicyViolationsLicenseUnaudited(predecessorMetrics.getPolicyViolationsLicenseUnaudited());
		inherited.setPolicyViolationsOperationalAudited(predecessorMetrics.getPolicyViolationsOperationalAudited());
		inherited.setPolicyViolationsOperationalUnaudited(predecessorMetrics.getPolicyViolationsOperationalUnaudited());
		inherited.setPolicyViolationsSecurityAudited(predecessorMetrics.getPolicyViolationsSecurityAudited());
		inherited.setPolicyViolationsSecurityUnaudited(predecessorMetrics.getPolicyViolationsSecurityUnaudited());
		// Derive the scalar counts from the detail lists rather than copying them, so the two can
		// never disagree.
		inherited.computeMetricsFromFacts();
		inherited.setFirstScanned(null);
		inherited.setLastScanned(null);
		return inherited;
	}

	/**
	 * Is this a CARRIED artifact having its inherited findings replaced by its own first scan?
	 *
	 * <p>Extracted so it can be asserted directly. It gates the [METRICS-TRUNCATION] probe, and a
	 * predicate that silently over-matches there does not fail a test -- it just stops an ERROR being
	 * emitted, which is invisible until the incident it was meant to catch goes unreported.
	 *
	 * <p>The predicate is the carry-forward marker and nothing else: a null firstScanned with findings
	 * present cannot arise from a real scan (every write path that sets findings sets or preserves the
	 * stamp), so it is exactly the state carry-forward leaves. When such an artifact's own first scan
	 * lands and empties the carried set, that is the seam working -- whether the emptiness is a genuine
	 * remediation or an all-dereferenced ALL_ARTIFACTS_GONE. DECISION 2026-08-19 (design doc 13.1):
	 * both are exempt here, because the ALL_ARTIFACTS_GONE loss this seam cannot fix is already
	 * reported per release by the rate-limited [METRICS-LOSS-PROVENANCE] probe, and keeping this
	 * artifact-level ERROR armed on that population only produced a standing per-build alert nobody
	 * could clear. An earlier revision threaded a hadRealCoverage flag from the synthetic fan-out to
	 * discriminate the two; it was removed with that flag.
	 *
	 * @param existingFirstScanned the stamp on the row BEFORE this write; null means never scanned,
	 *   which combined with findings present is the carry-forward marker
	 * @param existingFindingCount how many findings the row already held
	 */
	static boolean isCarriedReplacedByItsOwnScan (ZonedDateTime existingFirstScanned,
			int existingFindingCount) {
		return null == existingFirstScanned && existingFindingCount > 0;
	}

	/**
	 * Raw size of the three detail lists -- what "has findings" means for the carry-forward.
	 *
	 * <p>Package-visible and single: this is the operative definition of "has findings" for the whole
	 * seam (it gates inheritFindingsFromPredecessor's early return), so a second copy elsewhere could
	 * drift -- e.g. a fourth detail list added here and not there.
	 */
	static int countInheritedFindings(DependencyTrackIntegration m) {
		if (null == m) return 0;
		return (null != m.getVulnerabilityDetails() ? m.getVulnerabilityDetails().size() : 0)
				+ (null != m.getViolationDetails() ? m.getViolationDetails().size() : 0)
				+ (null != m.getWeaknessDetails() ? m.getWeaknessDetails().size() : 0);
	}
	
	/**
	 * Convert byte array to hex string
	 */
	private static String bytesToHex(byte[] bytes) {
		StringBuilder result = new StringBuilder();
		for (byte b : bytes) {
			result.append(String.format("%02x", b));
		}
		return result.toString();
	}
	
}
