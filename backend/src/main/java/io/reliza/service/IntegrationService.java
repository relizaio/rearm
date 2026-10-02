/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.DependencyTrackVersion;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.AffectedIdentityType;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.AffectedRangeType;
import io.reliza.model.VulnerabilityRecordData.CweEntry;
import io.reliza.model.VulnerabilityRecordData.RangeSourceKey;
import io.reliza.model.VulnerabilityRecordData.Fetcher;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.model.VulnerabilityRecordData.VulnScore;
import io.reliza.model.VulnerabilityRecordData.VulnScoreType;
import io.reliza.model.VulnerabilityRecordData.VulnSourceSnapshot;
import io.reliza.model.VulnerabilityRecordData.VulnSubScoreType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.IntegrationWebDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.SeveritySourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.TriggerIntegrationInputDto;
import io.reliza.model.OrganizationData;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.service.VulnerabilityRecordService.FetchedAffectedRanges;
import io.reliza.service.VulnerabilityRecordService.UpsertOrigin;
import io.reliza.service.VulnerabilityRecordService.UpsertOutcome;
import io.reliza.service.VulnerabilityRecordService.UpsertResult;
import lombok.Data;

@Service
public class IntegrationService {
	
	@Autowired
	private AuditService auditService;
	
	@Autowired
	private EncryptionService encryptionService;
	
	@Autowired
	private SharedArtifactService sharedArtifactService;
	
	@Autowired
	@Lazy
	private VulnAnalysisService vulnAnalysisService;
	
	@Autowired
	@Lazy
	private ArtifactService artifactService;

	@Autowired
	private ArtifactRepository artifactRepository;

	@Autowired
	@Lazy
	private DTrackService dtrackService;

	@Autowired
	@Lazy
	private VulnerabilityRecordService vulnerabilityRecordService;

	@Autowired
	private GetOrganizationService getOrganizationService;
	
	private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

	private final IntegrationRepository repository;

	// 150 MB ceiling on a single Dependency-Track response payload. Was 200 MB,
	// then 50 MB (too tight — tripped DataBufferLimitException on real
	// projects), then 100 MB. Raised again to 150 MB to give projects with
	// very large vuln listings more headroom while still keeping the per-call
	// allocation well under the original 200 MB. We paginate everything (see
	// executeDtrackPaginatedCallWithTransform), so this cap is only a
	// runaway-allocation safety net.
	//
	// Sizing relationship with CommonVariables.DTRACK_DEFAULT_PAGE_SIZE: the
	// cap must exceed pageSize × worst-case-per-row × safety-factor. Measured
	// worst-case row size 2026-05-22 was 33 KB (Log4Shell-class GHSA). With
	// the current pageSize=2000 and a 2× safety factor that's a theoretical
	// max page of ~132 MB — 150 MB gives ~12% additional headroom. Past
	// pageSize bumps should re-check this relationship before landing.
	final int dtrackBufferSize = 150 * 1024 * 1024;
    final ExchangeStrategies dtrackExchangeStrategies = ExchangeStrategies.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(dtrackBufferSize))
            .build();
    
	/** How long a version probe waits for Dependency-Track's answer. */
	private static final Duration DTRACK_VERSION_PROBE_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * How often an integration detected as version 4 is probed again, to catch
	 * an in-place upgrade; an undetected one is probed every sweep run.
	 */
	private static final Duration DTRACK_V4_REPROBE_INTERVAL = Duration.ofDays(1);

	private final WebClient dtrackWebClient = WebClient
			.builder()
			.exchangeStrategies(dtrackExchangeStrategies)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	
	IntegrationService(IntegrationRepository repository) {
	    this.repository = repository;
	}

	@Data
	public static class ExternalApiResponse {
		private int code = -1;
		private String message;
		private boolean errorred = false;
		private String body;
		
		public Boolean isSuccessful () {
			return !errorred && (code >= 200 && code < 400);
		}
	}
	
	public Optional<Integration> getIntegration (UUID uuid) {
		return repository.findById(uuid);
	}

	public List<Integration> listIntegrationsByOrg(UUID orgUuid){
		return repository.listIntegrationsByOrg(orgUuid.toString());
	}

	public List<Integration> getIntegrations (Iterable<UUID> uuids) {
		return (List<Integration>) repository.findAllById(uuids);
	}
	
	public List<IntegrationData> getIntegrationDataList (Iterable<UUID> uuids) {
		List<Integration> branches = getIntegrations(uuids);
		return branches.stream().map(IntegrationData::dataFromRecord).collect(Collectors.toList());
	}

	
	public Optional<IntegrationData> getIntegrationData (UUID uuid) {
		Optional<IntegrationData> iData = Optional.empty();
		Optional<Integration> i = getIntegration(uuid);
		if (i.isPresent()) {
			iData = Optional
							.of(
								IntegrationData
									.dataFromRecord(i
										.get()
								));
		}
		return iData;
	}
	
	public Set<IntegrationType> listConfiguredBaseIntegrationTypesPerOrg (UUID org) {
		var integrations = listIntegrationsByOrg(org);
		return integrations.stream()
				.map(IntegrationData::dataFromRecord)
				.filter(id -> CommonVariables.BASE_INTEGRATION_IDENTIFIER.equals(id.getIdentifier()))
				.map(id -> id.getType())
				.collect(Collectors.toSet());
	}
	
	public List<IntegrationWebDto> listCiIntegrationsPerOrg (UUID org) {
		var integrations = listIntegrationsByOrg(org);
		return integrations.stream()
				.map(IntegrationData::dataFromRecord)
				.filter(id -> StringUtils.isNotEmpty(id.getIdentifier()) && id.getIdentifier().startsWith("TRIGGER_"))
				.map(IntegrationData::toWebDto)
				.toList();
	}
	
	public void deleteIntegration (UUID uuid) {
		repository.deleteById(uuid);
	}
	
	protected void deleteIntegrationByOrgIdentifier (UUID orgUuid, String identifier) {
		Optional<Integration> oi = getIntegrationByOrgIdentifier(orgUuid, identifier);
		if (oi.isPresent()) {
			deleteIntegration(oi.get().getUuid());
		}
	}
	
	protected void deleteIntegrationByOrgTypeIdentifier (UUID orgUuid, IntegrationType type, String identifier) {
		Optional<Integration> oi = getIntegrationByOrgTypeIdentifier(orgUuid, type, identifier);
		if (oi.isPresent()) {
			deleteIntegration(oi.get().getUuid());
		}
	}

	private Optional<Integration> getIntegrationByOrgIdentifier (UUID orgUuid, String identifier) {
		return repository.findIntegrationByOrgIdentifier(orgUuid.toString(), identifier);
	}
	
	private Optional<Integration> getIntegrationByOrgTypeIdentifier (UUID orgUuid, IntegrationType type, String identifier) {
		return repository.findIntegrationByOrgTypeIdentifier(orgUuid.toString(), type.toString(), identifier);
	}
	
	public Optional<IntegrationData> getIntegrationDataByOrgTypeIdentifier (UUID orgUuid, IntegrationType type, String identifier) {
		Optional<IntegrationData> oid = Optional.empty();
		Optional<Integration> oi = getIntegrationByOrgTypeIdentifier(orgUuid, type, identifier);
		if (oi.isPresent()) {
			oid = Optional.of(IntegrationData.dataFromRecord(oi.get()));
		}
		return oid;
	}
	
	/**
	 * Check if an organization has Dependency Track integration configured.
	 */
	public boolean hasDependencyTrackIntegration(UUID orgUuid) {
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(
			orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		return oid.isPresent();
	}
	
	/**
	 * Get all organization UUIDs that have Dependency Track integration configured.
	 */
	public List<UUID> listOrgsWithDtrackIntegration() {
		List<String> orgStrings = repository.listOrgsWithDtrackIntegration();
		return orgStrings.stream()
			.map(UUID::fromString)
			.collect(Collectors.toList());
	}

	/**
	 * The orgs whose Dependency-Track integration is enabled and detected as
	 * version 5. Their drains carry no upstream advisory dates, which the
	 * affected-ranges sweep needs to know. An integration whose version is
	 * not detected is left out, whatever version it is taken as meanwhile:
	 * the sweep already reads its drained snapshots, which carry no date, as
	 * undated. One query; an integration that cannot be read is logged and
	 * left out rather than failing the caller.
	 */
	public Set<UUID> listOrgsWithDtrackV5() {
		Set<UUID> out = new HashSet<>();
		for (Integration i : repository.listBaseIntegrationsByType(IntegrationType.DEPENDENCYTRACK.name())) {
			try {
				IntegrationData id = IntegrationData.dataFromRecord(i);
				if (id.getOrg() != null && id.getDtrackVersion() == DependencyTrackVersion.V5
						&& !Boolean.FALSE.equals(id.getIsEnabled())) {
					out.add(id.getOrg());
				}
			} catch (RuntimeException e) {
				log.warn("Unreadable Dependency-Track integration {}, left out of the version 5 orgs", i.getUuid(), e);
			}
		}
		return out;
	}
	
	/**
	 * KEV per-org integration upsert (V54). Creates the {@code (org, type,
	 * "base")} integration row if missing, otherwise patches the existing
	 * row. {@code isEnabled} replaces the flag; {@code rawToken} (PLAINTEXT
	 * — encrypted here) replaces the secret when non-null, leaves it
	 * untouched when null. Pass {@code rawToken = ""} to explicitly clear
	 * the secret. {@code type} must be {@code CISA_KEV} or
	 * {@code VULNCHECK_KEV}.
	 */
	@Transactional
	public IntegrationData upsertKevIntegration(UUID orgUuid, IntegrationType type,
			boolean isEnabled, String rawToken, WhoUpdated wu) {
		if (type != IntegrationType.CISA_KEV && type != IntegrationType.VULNCHECK_KEV) {
			throw new IllegalArgumentException("upsertKevIntegration: type must be CISA_KEV or VULNCHECK_KEV, was " + type);
		}
		String identifier = CommonVariables.BASE_INTEGRATION_IDENTIFIER;
		Optional<Integration> existing = repository.findIntegrationByOrgTypeIdentifier(
				orgUuid.toString(), type.name(), identifier);
		Integration i = existing.orElseGet(Integration::new);
		IntegrationData id = existing.isPresent()
				? IntegrationData.dataFromRecord(existing.get())
				: new IntegrationData();
		if (!existing.isPresent()) {
			id.setOrg(orgUuid);
			id.setType(type);
			id.setIdentifier(identifier);
		}
		id.setIsEnabled(isEnabled);
		if (rawToken != null) {
			id.setSecret(rawToken.isEmpty() ? null : encryptionService.encrypt(rawToken));
		}
		saveIntegration(i, Utils.dataToRecord(id), wu);
		// Re-read so the returned object has the uuid stamped by saveIntegration
		// (existing case kept it; new case generated one).
		IntegrationData out = IntegrationData.dataFromRecord(i);
		return out;
	}

	@Transactional
	public Integration createIntegration (String identifier, UUID organization, IntegrationType type,
			URI uri, String secret, String schedule, URI frontendUri, WhoUpdated wu) {
		Integration i = new Integration();
		String encryptedSecret = encryptionService.encrypt(secret);
		IntegrationData id = IntegrationData.integrationDataFactory(identifier, organization, type, uri, encryptedSecret, frontendUri);
		if (StringUtils.isNotEmpty(schedule)) id.setSchedule(schedule.toString());
		// Auto-detect the Dependency-Track generation from its /api/version so the
		// vuln drain picks the right endpoint without the operator having to know
		// or declare it. A probe that fails leaves it undetected, for
		// redetectDtrackVersions to settle, rather than guessing. Changing the
		// URI or the key means deleting and re-creating the integration, which
		// detects again.
		if (type == IntegrationType.DEPENDENCYTRACK && uri != null) {
			try {
				id.setDtrackVersion(probeDtrackVersion(uri, secret));
			} catch (RelizaException e) {
				log.warn("Could not detect the Dependency-Track version at {}: {}; taken as {} until a probe succeeds",
						uri, e.getMessage(), IntegrationData.DEFAULT_DTRACK_VERSION);
			}
		}
		return saveIntegration(i, Utils.dataToRecord(id), wu);
	}

	/**
	 * Probe a Dependency-Track instance's {@code /api/version} and map its
	 * reported version to a {@link DependencyTrackVersion}: V5 when the major
	 * version is 5 or greater, otherwise the legacy V4.
	 *
	 * @throws RelizaException when the instance cannot be reached or does not
	 *         say which version it is; the caller decides how loudly to log it
	 */
	protected DependencyTrackVersion probeDtrackVersion(URI baseUri, String apiToken) throws RelizaException {
		URI versionUri = URI.create(baseUri.toString() + "/api/version");
		String body;
		try {
			var resp = dtrackWebClient.get().uri(versionUri).header("X-API-Key", apiToken)
					.retrieve().toEntity(String.class).block(DTRACK_VERSION_PROBE_TIMEOUT);
			body = resp == null ? null : resp.getBody();
		} catch (Exception e) {
			throw new RelizaException("no answer from " + versionUri + ": " + e.getMessage());
		}
		if (body == null) throw new RelizaException("empty answer from " + versionUri);
		Object version;
		try {
			version = Utils.OM.readValue(body, Map.class).get("version");
		} catch (Exception e) {
			throw new RelizaException("unreadable answer from " + versionUri);
		}
		String vs = version == null ? "" : version.toString().trim();
		int firstDot = vs.indexOf('.');
		String major = firstDot > 0 ? vs.substring(0, firstDot) : vs;
		try {
			return Integer.parseInt(major) >= 5 ? DependencyTrackVersion.V5 : DependencyTrackVersion.V4;
		} catch (NumberFormatException nfe) {
			throw new RelizaException("unparseable version '" + vs + "' from " + versionUri);
		}
	}

	/**
	 * Integrations already warned about (undetected) or logged as failing,
	 * so a lasting problem is reported once per process rather than every run.
	 */
	private final Set<UUID> dtrackRedetectReported = ConcurrentHashMap.newKeySet();

	/** When each version 4 integration was last probed, per process; see {@link #DTRACK_V4_REPROBE_INTERVAL}. */
	private final Map<UUID, Instant> dtrackV4ProbedAt = new ConcurrentHashMap<>();

	/**
	 * Probe the Dependency-Track integrations whose version may be wrong and
	 * store what the probe tells: every run those not detected yet (created
	 * before detection existed, or whose instance could not be probed; taken
	 * as {@link IntegrationData#DEFAULT_DTRACK_VERSION} meanwhile), and once
	 * per {@link #DTRACK_V4_REPROBE_INTERVAL} those stored as the legacy
	 * version 4 (upgraded in place since, or stored when a failed probe still
	 * meant version 4). A version 4 that does not answer keeps its version
	 * quietly. The row is read again after the probe and that fresh copy is
	 * written, so a change made while the probe waited is kept and a row
	 * deleted meanwhile is not brought back. One integration failing does not
	 * stop the others. The caller holds the {@code REDETECT_DTRACK_VERSIONS}
	 * lock.
	 *
	 * @return integrations whose version was stored
	 */
	public int redetectDtrackVersions() {
		int stored = 0;
		Instant now = Instant.now();
		Set<UUID> stillReported = new HashSet<>();
		Set<UUID> v4 = new HashSet<>();
		for (Integration row : repository.listBaseIntegrationsByType(IntegrationType.DEPENDENCYTRACK.name())) {
			try {
				IntegrationData id = IntegrationData.dataFromRecord(row);
				DependencyTrackVersion known = id.getDtrackVersion();
				if (known == DependencyTrackVersion.V5 || id.getUri() == null) continue;
				if (known == DependencyTrackVersion.V4) {
					v4.add(row.getUuid());
					Instant last = dtrackV4ProbedAt.get(row.getUuid());
					if (last != null && last.plus(DTRACK_V4_REPROBE_INTERVAL).isAfter(now)) continue;
					dtrackV4ProbedAt.put(row.getUuid(), now);
				}
				DependencyTrackVersion detected;
				try {
					detected = probeDtrackVersion(id.getUri(), encryptionService.decrypt(id.getSecret()));
				} catch (RelizaException e) {
					if (known == null) {
						stillReported.add(row.getUuid());
						if (dtrackRedetectReported.add(row.getUuid())) {
							log.warn("Could not detect the Dependency-Track version of org {}: {}; taken as {} until a probe succeeds",
									id.getOrg(), e.getMessage(), IntegrationData.DEFAULT_DTRACK_VERSION);
						}
					}
					continue;
				}
				if (detected == DependencyTrackVersion.V4) {
					v4.add(row.getUuid());
					dtrackV4ProbedAt.put(row.getUuid(), now);
				}
				if (detected == known) continue;
				Optional<Integration> fresh = repository.findById(row.getUuid());
				if (fresh.isEmpty()) continue;
				IntegrationData freshData = IntegrationData.dataFromRecord(fresh.get());
				if (freshData.getDtrackVersion() != known || !Objects.equals(freshData.getUri(), id.getUri())) continue;
				freshData.setDtrackVersion(detected);
				saveIntegration(fresh.get(), Utils.dataToRecord(freshData), WhoUpdated.getAutoWhoUpdated());
				stored++;
				log.info("Detected Dependency-Track {} for org {} (was {})", detected, id.getOrg(),
						known == null ? "not detected" : known);
			} catch (Exception e) {
				// An unreadable row or a secret that no longer decrypts: needs someone, once.
				stillReported.add(row.getUuid());
				if (dtrackRedetectReported.add(row.getUuid())) {
					log.error("Dependency-Track version re-detection failed for integration {}", row.getUuid(), e);
				}
			}
		}
		dtrackRedetectReported.retainAll(stillReported);
		dtrackV4ProbedAt.keySet().retainAll(v4);
		return stored;
	}
	
	private URI adoUrlEncode (@NonNull String baseUri) {
		String encUri = baseUri;
		if (!baseUri.contains("%2")) {
			encUri = URLEncoder.encode(baseUri, StandardCharsets.UTF_8).replace("+", "%20");
		}
		return URI.create(encUri);
	}

	@Transactional
	public Integration createIntegration (TriggerIntegrationInputDto tii, WhoUpdated wu)
			throws DatabindException, JacksonException, RelizaException {
		Integration i = new Integration();
		String secret = tii.getSecret();
		if (tii.getType() == IntegrationType.GITHUB) {
			secret = normalizeGithubAppPrivateKey(secret);
		}
		IntegrationData id = new IntegrationData();
		id.setOrg(tii.getOrg());
		id.setIdentifier("TRIGGER_" + UUID.randomUUID().toString());
		id.setType(tii.getType());
		id.setNote(tii.getNote());
		id.setCapabilities(tii.getCapabilities());
		// Set the App ID (schedule) before the assert so it can validate both the
		// key and the App ID together (see assertGithubPrValidateHasKey).
		if (StringUtils.isNotEmpty(tii.getSchedule())) id.setSchedule(tii.getSchedule());
		// Reject a PR_VALIDATE GitHub integration with no App key/App ID before
		// encryption: `secret` here is the normalized plaintext, and an
		// empty/null key would otherwise reach encrypt() (a null throws a
		// less actionable IllegalArgumentException) or persist silently broken.
		assertGithubPrValidateHasKey(id, secret);
		id.setSecret(encryptionService.encrypt(secret));
		if (StringUtils.isNotEmpty(tii.getUri())) {
			URI uri = null;
			if (tii.getType() == IntegrationType.ADO) {
				uri = adoUrlEncode(tii.getUri());
			} else {
				uri = URI.create(tii.getUri());
			}
			id.setUri(uri);
		}
		if (StringUtils.isNotEmpty(tii.getFrontendUri())) id.setFrontendUri(adoUrlEncode(tii.getFrontendUri()));
		if (tii.getType() == IntegrationType.ADO) {
			Map<String, Object> adoParams = new HashMap<>();
			adoParams.put("client", tii.getClient());
			adoParams.put("tenant", tii.getTenant());
			id.setParameters(adoParams);
		}
		return saveIntegration(i, Utils.dataToRecord(id), wu);
	}

	/**
	 * Replace the asserted capabilities on an Integration. Idempotent,
	 * preserves all other record_data fields. Caller is responsible for
	 * authorization (org-admin).
	 */
	@Transactional
	public Integration updateCapabilities(UUID uuid,
			List<IntegrationData.IntegrationCapability> capabilities, WhoUpdated wu) throws RelizaException {
		Integration i = repository.findById(uuid)
				.orElseThrow(() -> new IllegalArgumentException("Integration not found: " + uuid));
		IntegrationData id = IntegrationData.dataFromRecord(i);
		id.setCapabilities(capabilities == null ? List.of() : capabilities);
		// Adding PR_VALIDATE to a GitHub integration that has no App key would
		// produce a silently-broken validation channel; the stored secret is
		// encrypted, so decrypt to detect the empty/absent case.
		assertGithubPrValidateHasKey(id, encryptionService.decrypt(id.getSecret()));
		return saveIntegration(i, Utils.dataToRecord(id), wu);
	}

	/**
	 * Replace the GitHub App private key (and optionally the App ID) on an existing
	 * GITHUB integration, leaving capabilities and all other record_data untouched.
	 * The key is normalized (PKCS#1 PEM / PKCS#8 PEM / DER base64 all accepted) and
	 * re-encrypted, and the PR_VALIDATE-needs-key rule is re-asserted so a
	 * credential replacement can't leave a PR_VALIDATE integration broken. A blank
	 * {@code appId} leaves the stored App ID unchanged (rotate the key alone).
	 * Caller is responsible for authorization (org-admin).
	 */
	@Transactional
	public Integration updateGithubAppCredentials(UUID uuid, String privateKey, String appId, WhoUpdated wu)
			throws RelizaException {
		Integration i = repository.findById(uuid)
				.orElseThrow(() -> new IllegalArgumentException("Integration not found: " + uuid));
		IntegrationData id = IntegrationData.dataFromRecord(i);
		if (id.getType() != IntegrationType.GITHUB) {
			throw new RelizaException("updateGithubAppCredentials is only valid for GITHUB integrations");
		}
		String secret = normalizeGithubAppPrivateKey(privateKey);
		// Update the App ID (schedule) before the assert so it validates the key and
		// App ID together; a blank appId leaves the existing App ID in place.
		if (StringUtils.isNotBlank(appId)) {
			id.setSchedule(appId);
		}
		assertGithubPrValidateHasKey(id, secret);
		id.setSecret(encryptionService.encrypt(secret));
		return saveIntegration(i, Utils.dataToRecord(id), wu);
	}

	/**
	 * A GITHUB integration with the PR_VALIDATE capability needs the GitHub App
	 * private key: {@code SaasIntegrationService.getGithubKey} signs the
	 * installation-token JWT with it. Saving PR_VALIDATE with an empty key
	 * yields an integration that silently fails on every PR-validation and
	 * DTrack-sync comment attempt (getGithubKey logs and returns null) -- the
	 * misconfiguration behind this incident. Reject it at the create/update
	 * boundary with a clear message instead. {@code plainSecret} is the
	 * decrypted/plaintext key (callers pass it pre-encryption or post-decryption)
	 * so blank detection works regardless of the entry path.
	 *
	 * <p>The App ID ({@code IntegrationData.schedule}) is validated alongside the
	 * key: {@code getGithubJWT} uses it as the JWT issuer, so a blank App ID is the
	 * same silent-failure class as a blank key (the token mint fails and no comment
	 * / check-run is posted). The caller must set the schedule on {@code id} before
	 * invoking this method.
	 */
	private void assertGithubPrValidateHasKey(IntegrationData id, String plainSecret) throws RelizaException {
		if (id.getType() == IntegrationType.GITHUB
				&& id.hasCapability(IntegrationData.IntegrationCapability.PR_VALIDATE)) {
			if (StringUtils.isBlank(plainSecret)) {
				throw new RelizaException("GitHub integration with PR_VALIDATE capability requires a "
						+ "non-empty GitHub App private key (secret); none was provided");
			}
			if (StringUtils.isBlank(id.getSchedule())) {
				throw new RelizaException("GitHub integration with PR_VALIDATE capability requires a "
						+ "non-empty GitHub App ID; none was provided");
			}
		}
	}

	@Transactional
	private Integration saveIntegration (Integration i, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		if (null == recordData || recordData.isEmpty()) {
			throw new IllegalStateException("Integration must have record data");
		}
		// TODO: add better validation
		Optional<Integration> ir = getIntegration(i.getUuid());
		if (ir.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.INTEGRATIONS, i);
			i.setRevision(i.getRevision() + 1);
			i.setLastUpdatedDate(ZonedDateTime.now());
		}
		i.setRecordData(recordData);
		i = (Integration) WhoUpdated.injectWhoUpdatedData(i, wu);
		return repository.save(i);
	}
	
	public record DependencyTrackBomPayload (UUID project, String bom) {}
	
	public record DependencyTrackProjectInput(String name, String version, String parent, String classifier, List<Object> accessTeams,
			List<Object> tags, Boolean active, Boolean isLatest) {}
	
	
	public record DependencyTrackUploadResult(String projectId, String token, String projectName, String projectVersion, 
			URI fullProjectUri) {}
	
	public record UploadableBom (JsonNode bomJson, byte[] rawBom, boolean isRaw) {}
	
	protected DependencyTrackUploadResult sendBomToDependencyTrack (UUID orgUuid, UploadableBom bom, String projectName, String projectVersion) {
		DependencyTrackUploadResult dtur = null;
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(orgUuid, IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isPresent()) {
			try {
				String apiToken = encryptionService.decrypt(oid.get().getSecret());
	
				String projectIdStr = null;
				
				DependencyTrackProjectInput dtpi = new DependencyTrackProjectInput(projectName, projectVersion, null, "APPLICATION", List.of(), List.of(), true, false);
				String projectCreateUriStr = oid.get().getUri().toString() + "/api/v1/project";
				URI projectCreateUri = URI.create(projectCreateUriStr);
				
				try {
					var createDtrackProjectResp = dtrackWebClient
					.put()
					.uri(projectCreateUri)
					.header("X-API-Key", apiToken)
					.bodyValue(dtpi).retrieve()
					.toEntity(String.class)
					.block();
					
					@SuppressWarnings("unchecked")
					Map<String, Object> createProjResp = Utils.OM.readValue(createDtrackProjectResp.getBody(), Map.class);
					projectIdStr = (String) createProjResp.get("uuid");
				} catch (WebClientResponseException wcre) {
					if (wcre.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(409))) {
						// Project already exists, look it up
						log.info("Project {}:{} already exists in DTrack, looking up existing project", projectName, projectVersion);
						String lookupUriStr = oid.get().getUri().toString() + "/api/v1/project/lookup?name=" + 
								java.net.URLEncoder.encode(projectName, java.nio.charset.StandardCharsets.UTF_8) + 
								"&version=" + java.net.URLEncoder.encode(projectVersion, java.nio.charset.StandardCharsets.UTF_8);
						URI lookupUri = URI.create(lookupUriStr);
						
						var lookupResp = dtrackWebClient
							.get()
							.uri(lookupUri)
							.header("X-API-Key", apiToken)
							.retrieve()
							.toEntity(String.class)
							.block();
						
						@SuppressWarnings("unchecked")
						Map<String, Object> lookupProjResp = Utils.OM.readValue(lookupResp.getBody(), Map.class);
						projectIdStr = (String) lookupProjResp.get("uuid");
						
						if (StringUtils.isEmpty(projectIdStr)) {
							log.error("Project lookup failed for {}:{} - no UUID in response", projectName, projectVersion);
							throw new RuntimeException("Failed to lookup existing DTrack project");
						}
					} else {
						throw wcre;
					}
				}
				
				dtur = sendBomToDependencyTrackOnCreatedProject(oid.get(), UUID.fromString(projectIdStr), bom, projectName, projectVersion);
			} catch (Exception e) {
				log.error("Error on uploading bom to dependency track", e);
				throw new RuntimeException("Error on uploading bom to dependency track");
			}
		}
		return dtur;
	}
	
	private DependencyTrackUploadResult sendBomToDependencyTrackOnCreatedProject (IntegrationData dtrackIntegration,
			UUID projectId, UploadableBom bom, String projectName, String projectVersion) throws DatabindException, JacksonException {
		String apiUriStr = dtrackIntegration.getUri().toString() + "/api/v1/bom";
		URI apiUri = URI.create(apiUriStr);
		String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
		

		String bomBase64;
		
		if (!bom.isRaw()) {
			String bomString = "";
			try {
				bomString = Utils.OM.writeValueAsString(bom.bomJson());
			} catch (JacksonException e) {
				log.error("Invalid json sent to dtrack", e);
				throw new RuntimeException("Invalid json input");
			}
			bomBase64 = Base64.getEncoder().encodeToString(bomString.getBytes());
		} else {
			bomBase64 = Base64.getEncoder().encodeToString(bom.rawBom());
		}
		
		DependencyTrackBomPayload payload = new DependencyTrackBomPayload(projectId, bomBase64);
		
		
		var tokenResp = dtrackWebClient
			.put()
			.uri(apiUri)
			.header("X-API-Key", apiToken)
			.bodyValue(payload)
			.exchangeToMono(response -> {
				if (response.statusCode().isError()) {
					return response.bodyToMono(String.class)
						.flatMap(body -> {
							log.error("DTrack BOM upload failed: status={}, body={}", response.statusCode(), body);
							return Mono.error(new RuntimeException("DTrack BOM upload error: " + response.statusCode() + " - " + body));
						});
				}
				return response.toEntity(String.class);
			})
			.block();
		@SuppressWarnings("unchecked")
		Map<String, Object> addBomResp = Utils.OM.readValue(tokenResp.getBody(), Map.class);
		String token = (String) addBomResp.get("token");
		URI fullDtrackUri = URI.create(dtrackIntegration.getFrontendUri()
				.toString() + "/projects/" + projectId.toString());
		return new DependencyTrackUploadResult(projectId.toString(), token, projectName, projectVersion, fullDtrackUri);
	}
	

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackResolvedLicenseRaw(String licenseId) {}
	
	// Package-private (not private) so a test subclass can construct
	// deterministic DtrackPageResult instances.
	record DtrackPageResult(List<Object> results, int totalCount) {}
	
	static int parseDtrackTotalCountHeader(org.springframework.http.ResponseEntity<?> resp) {
		String header = resp.getHeaders().getFirst("X-Total-Count");
		if (header != null) {
			try {
				return Integer.parseInt(header);
			} catch (NumberFormatException nfe) {
				log.warn("Invalid X-Total-Count header value: {}", header);
			}
		}
		return -1;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackComponentRaw(String purl, String cpe, DtrackResolvedLicenseRaw resolvedLicense, String licenseExpression) {}

	/** A fetched vulnerability paired with its component's CPE (null when absent). */
	public record VulnWithCpe(VulnerabilityDto vuln, String cpe) {}

	/** A fetched policy violation paired with its component's CPE (null when absent). */
	public record ViolationWithCpe(ViolationDto violation, String cpe) {}
	
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackAliasRaw(String cveId, String ghsaId, String uuid) {}

	/**
	 * Subset of Dependency-Track's {@code Cwe} object. Jackson default-binds the numeric id under
	 * the key {@code cweId}; the human-readable name isn't consumed today but is carried for
	 * possible future use.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackCweRaw(Integer cweId, String name) {}

	/**
	 * Subset of DTrack's {@code /api/v1/vulnerability/project/{uuid}} vulnerability payload.
	 *
	 * <p>DTrack returns one row per upstream feed for each vulnerability — e.g. a CVE
	 * is repeated as one NVD row and one GITHUB row, linked through {@link #aliases}.
	 * The {@link #source} field is what tells us which upstream feed contributed the
	 * description / CWEs / CVSS scoring on this particular row. {@link #title} only
	 * appears on some sources (GHSA usually carries one; NVD typically doesn't).
	 *
	 * <p>{@link #references} is a markdown-formatted blob (one bullet per advisory URL)
	 * — kept as a string here and union-deduped in the merger.
	 *
	 * <p>Dates are declared as {@link Date}; Jackson handles both ISO 8601 strings and epoch
	 * millis transparently so we don't need to guess DT's on-the-wire representation.
	 *
	 * <p>{@link #uuid} is DTrack's internal vulnerability UUID — opaque, but useful as the
	 * {@code fetcherRef} when re-fetching a single record without re-listing a project.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackVulnRaw (String vulnId, String source, String title,
			VulnerabilitySeverity severity,
			List<DtrackComponentRaw> components, List<DtrackAliasRaw> aliases,
			String description, List<DtrackCweRaw> cwes, String references,
			Double cvssV2BaseScore, String cvssV2Vector,
			Double cvssV2ImpactSubScore, Double cvssV2ExploitabilitySubScore,
			Double cvssV3BaseScore, String cvssV3Vector,
			Double cvssV3ImpactSubScore, Double cvssV3ExploitabilitySubScore,
			Double cvssV4Score, String cvssV4Vector,
			Double owaspRRLikelihoodScore, Double owaspRRTechnicalImpactScore,
			Double owaspRRBusinessImpactScore, String owaspRRVector,
			Double epssScore, Double epssPercentile,
			String uuid, Date published, Date updated,
			List<DtrackAffectedComponentRaw> affectedComponents) {}

	/** Subset of Dependency-Track's {@code AffectedVersionAttribution}: a source that vouches for a range. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record DtrackAffectedVersionAttributionRaw(String source) {}

	/**
	 * Subset of Dependency-Track's {@code AffectedComponent}. Only the
	 * single-vulnerability endpoint fills {@code affectedComponents}; the
	 * project listing leaves it out. Same fields on DT 4 and 5. The identity
	 * type binds to our enum, whose names are DT's; the version type stays a
	 * string so that an absent one can be told from one we do not know (see
	 * {@link #toAffectedRanges}).
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record DtrackAffectedComponentRaw(
			@JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL) AffectedIdentityType identityType,
			String identity,
			String versionType,
			String version,
			String versionStartIncluding, String versionStartExcluding,
			String versionEndIncluding, String versionEndExcluding,
			List<DtrackAffectedVersionAttributionRaw> affectedVersionAttributions) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackViolationRaw (ViolationType type, DtrackComponentRaw component) {}
	
	protected List<VulnerabilityDto> fetchDependencyTrackVulnerabilityDetails(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned,
			DependencyTrackVersion dtrackVersion) throws DatabindException, JacksonException, RelizaException {
		return fetchDependencyTrackVulnerabilityDetailsWithCpe(dtrackBaseUri, apiToken, dtrackProject,
				artifactUuid, orgUuid, lastScanned, dtrackVersion).stream().map(VulnWithCpe::vuln).collect(java.util.stream.Collectors.toList());
	}

	/**
	 * Same as {@link #fetchDependencyTrackVulnerabilityDetails} but pairs each
	 * finding with its component's CPE so the synthetic-SBOM ingest can map
	 * cpe-only (purl-less) findings back to their canonical component.
	 *
	 * <p>Dispatches on {@code dtrackVersion} (null: not detected, taken as
	 * {@link IntegrationData#DEFAULT_DTRACK_VERSION}): Dependency-Track 5
	 * reads the finding endpoint ({@link #fetchDependencyTrackFindingDetailsWithCpe}),
	 * the legacy Dependency-Track 4 the vulnerability endpoint
	 * ({@link #fetchLegacyDtrack4VulnerabilityDetailsWithCpe}).
	 */
	public List<VulnWithCpe> fetchDependencyTrackVulnerabilityDetailsWithCpe(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned,
			DependencyTrackVersion dtrackVersion) throws DatabindException, JacksonException, RelizaException {
		return switch (IntegrationData.effectiveDtrackVersion(dtrackVersion)) {
			case V5 -> fetchDependencyTrackFindingDetailsWithCpe(dtrackBaseUri, apiToken, dtrackProject,
					artifactUuid, orgUuid, lastScanned);
			case V4 -> fetchLegacyDtrack4VulnerabilityDetailsWithCpe(dtrackBaseUri, apiToken, dtrackProject,
					artifactUuid, orgUuid, lastScanned);
		};
	}

	/**
	 * Legacy Dependency-Track 4 drain over {@code /api/v1/vulnerability/project/{uuid}},
	 * whose rows carry the aliases inline. Same output and records refresh as
	 * the version 5 drain ({@link #fetchDependencyTrackFindingDetailsWithCpe}).
	 */
	private List<VulnWithCpe> fetchLegacyDtrack4VulnerabilityDetailsWithCpe(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned)
			throws DatabindException, JacksonException, RelizaException {
		String baseUri = dtrackBaseUri.toString() + "/api/v1/vulnerability/project/" + dtrackProject;
		final FindingSourceDto source = new FindingSourceDto(artifactUuid, null, null);
		// Use lastScanned (DTrack scan time) as attributedAt so findings are included in First Scanned VDR
		// snapshots. Using ZonedDateTime.now() would set attributedAt after lastScanned/firstScanned,
		// causing all findings to be filtered out when the cutoff equals firstScanned.
		final ZonedDateTime attributedAt = lastScanned != null ? lastScanned : ZonedDateTime.now();
		final String fetcherEndpoint = dtrackBaseUri.toString();

		// Per-fetch context for the vulnerability_records upsert pass that runs
		// after pagination completes. We bucket raw rows by canonical primary id
		// (CVE-X preferred over GHSA-Y, etc.) so the multiple per-source rows
		// DTrack hands us for the same vuln land on one canonical record.
		Map<String, List<DtrackVulnRaw>> rowsByCanonical = new java.util.LinkedHashMap<>();
		Map<String, Set<String>> aliasesByCanonical = new java.util.LinkedHashMap<>();

		List<VulnWithCpe> all = executeDtrackPaginatedCallWithTransform(baseUri, apiToken, "", rawPage -> {
			List<VulnWithCpe> pageResults = new ArrayList<>();
			for (Object vd : rawPage) {
				DtrackVulnRaw dvr = Utils.OM.convertValue(vd, DtrackVulnRaw.class);
				Set<VulnerabilityAliasDto> aliases = new LinkedHashSet<>();
				Set<String> aliasStrings = new LinkedHashSet<>();
				if (dvr.vulnId() != null) aliasStrings.add(dvr.vulnId());
				if (null != dvr.aliases() && !dvr.aliases().isEmpty()) {
					dvr.aliases().forEach(a -> {
						if (a.cveId() != null && !a.cveId().trim().isEmpty()) {
							aliases.add(new VulnerabilityAliasDto(VulnerabilityAliasType.CVE, a.cveId()));
							aliasStrings.add(a.cveId());
						}
						if (a.ghsaId() != null && !a.ghsaId().trim().isEmpty()) {
							aliases.add(new VulnerabilityAliasDto(VulnerabilityAliasType.GHSA, a.ghsaId()));
							aliasStrings.add(a.ghsaId());
						}
					});
				}

				// Create severity source based on the main vulnerability ID
				SeveritySourceDto severitySource = Utils.createSeveritySourceDto(dvr.vulnId(), dvr.severity());

				// Bucket the raw row for post-pagination upsert. Canonical id is
				// CVE-preferred to mirror the merger's pickPrimaryVulnId rule, so
				// every (NVD CVE-X, GITHUB GHSA-Y) pair lands on the same key.
				String canonical = VulnerabilityRecordData.pickPrimaryVulnId(aliasStrings);
				if (canonical != null) {
					rowsByCanonical.computeIfAbsent(canonical, k -> new ArrayList<>()).add(dvr);
					aliasesByCanonical.computeIfAbsent(canonical, k -> new LinkedHashSet<>()).addAll(aliasStrings);
				}

				dvr.components().forEach(c -> {
					// Decode URL-encoded @ symbol in purl from DTrack
					String purl = c.purl() != null ? c.purl().replace("%40", "@") : null;
					// description / cwes / references / published / updated were
					// previously baked into each finding row, ballooning the
					// release.metrics column with paragraphs of advisory text per
					// CVE per release. Those fields now live in
					// rearm.vulnerability_records (one row per (org, primary
					// vuln id)) and are nulled here. VDR / openvex exporters
					// resolve them from the new table at export time.
					VulnerabilityDto vdto = new VulnerabilityDto(purl, dvr.vulnId(), dvr.severity(), aliases, Set.of(source), Set.of(severitySource), null, null, attributedAt,
							null, null, null, null, null, null);
					pageResults.add(new VulnWithCpe(vdto, c.cpe()));
				});
			}
			return pageResults;
		});

		// After pagination: refresh vulnerability_records for today-attributed
		// findings only. Older attributedAt dates represent point-in-time
		// captures we deliberately don't recheck — the next scan that brings
		// a vuln back into scope picks up upstream changes naturally.
		if (orgUuid != null && attributedAt.toLocalDate().equals(java.time.LocalDate.now(ZoneOffset.UTC))) {
			refreshVulnerabilityRecords(orgUuid, rowsByCanonical, aliasesByCanonical, fetcherEndpoint, apiToken);
		}

		return all;
	}

	// --- Dependency-Track 5 finding-endpoint shapes ---------------------------
	// V5 dropped aliases from /api/v1/vulnerability/project and left it
	// unpaginated; /api/v1/finding/project carries aliases and paginates, and
	// pre-flattens to one row per (component, canonical vulnerability) -- the
	// same dedup the V4 path reconstructs from per-source rows via aliases.

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackFindingComponentRaw(String purl, String cpe) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackFindingVulnRaw(String vulnId, String source, String title,
			VulnerabilitySeverity severity, List<DtrackAliasRaw> aliases,
			String description, List<DtrackCweRaw> cwes, String references,
			Double cvssV2BaseScore, String cvssV2Vector,
			Double cvssV3BaseScore, String cvssV3Vector, Double cvssV4Score, String cvssV4Vector,
			Double owaspLikelihoodScore, Double owaspTechnicalImpactScore,
			Double owaspBusinessImpactScore, String owaspRRVector,
			Double epssScore, Double epssPercentile, String uuid, Date published) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackFindingRaw(DtrackFindingComponentRaw component, DtrackFindingVulnRaw vulnerability) {}

	/**
	 * V5 vulnerability drain over {@code /api/v1/finding/project/{uuid}}.
	 * Produces the same {@link VulnWithCpe} list and refreshes
	 * {@code vulnerability_records} the same way the V4 path does, so every
	 * downstream consumer (metrics, VDR, records) is version-agnostic.
	 */
	private List<VulnWithCpe> fetchDependencyTrackFindingDetailsWithCpe(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned)
			throws DatabindException, JacksonException, RelizaException {
		String baseUri = dtrackBaseUri.toString() + "/api/v1/finding/project/" + dtrackProject;
		final FindingSourceDto source = new FindingSourceDto(artifactUuid, null, null);
		final ZonedDateTime attributedAt = lastScanned != null ? lastScanned : ZonedDateTime.now();
		final String fetcherEndpoint = dtrackBaseUri.toString();

		Map<String, List<DtrackFindingVulnRaw>> rowsByCanonical = new java.util.LinkedHashMap<>();
		Map<String, Set<String>> aliasesByCanonical = new java.util.LinkedHashMap<>();

		List<VulnWithCpe> all = executeDtrackPaginatedCallWithTransform(baseUri, apiToken, "", rawPage -> {
			List<VulnWithCpe> pageResults = new ArrayList<>();
			for (Object fd : rawPage) {
				DtrackFindingRaw dfr = Utils.OM.convertValue(fd, DtrackFindingRaw.class);
				DtrackFindingVulnRaw v = dfr.vulnerability();
				if (v == null || v.vulnId() == null) continue;

				Set<VulnerabilityAliasDto> aliases = new LinkedHashSet<>();
				Set<String> aliasStrings = new LinkedHashSet<>();
				aliasStrings.add(v.vulnId());
				if (null != v.aliases()) {
					v.aliases().forEach(a -> {
						if (a.cveId() != null && !a.cveId().trim().isEmpty()) {
							aliases.add(new VulnerabilityAliasDto(VulnerabilityAliasType.CVE, a.cveId()));
							aliasStrings.add(a.cveId());
						}
						if (a.ghsaId() != null && !a.ghsaId().trim().isEmpty()) {
							aliases.add(new VulnerabilityAliasDto(VulnerabilityAliasType.GHSA, a.ghsaId()));
							aliasStrings.add(a.ghsaId());
						}
					});
				}

				SeveritySourceDto severitySource = Utils.createSeveritySourceDto(v.vulnId(), v.severity());

				String canonical = VulnerabilityRecordData.pickPrimaryVulnId(aliasStrings);
				if (canonical != null) {
					rowsByCanonical.computeIfAbsent(canonical, k -> new ArrayList<>()).add(v);
					aliasesByCanonical.computeIfAbsent(canonical, k -> new LinkedHashSet<>()).addAll(aliasStrings);
				}

				String purl = (dfr.component() != null && dfr.component().purl() != null)
						? dfr.component().purl().replace("%40", "@") : null;
				String cpe = dfr.component() != null ? dfr.component().cpe() : null;
				// Same as the V4 path: description / cwes / references / published
				// live in vulnerability_records, not on the per-release finding row.
				VulnerabilityDto vdto = new VulnerabilityDto(purl, v.vulnId(), v.severity(), aliases,
						Set.of(source), Set.of(severitySource), null, null, attributedAt,
						null, null, null, null, null, null);
				pageResults.add(new VulnWithCpe(vdto, cpe));
			}
			return pageResults;
		});

		if (orgUuid != null && attributedAt.toLocalDate().equals(java.time.LocalDate.now(ZoneOffset.UTC))) {
			refreshVulnerabilityRecordsFromFindings(orgUuid, rowsByCanonical, aliasesByCanonical, fetcherEndpoint, apiToken);
		}

		return all;
	}

	/** V5 counterpart of {@link #refreshVulnerabilityRecords}, over finding rows. */
	private void refreshVulnerabilityRecordsFromFindings(UUID orgUuid,
			Map<String, List<DtrackFindingVulnRaw>> rowsByCanonical,
			Map<String, Set<String>> aliasesByCanonical,
			String fetcherEndpoint, String apiToken) {
		if (rowsByCanonical.isEmpty()) return;
		WhoUpdated wu = WhoUpdated.getAutoWhoUpdated();
		List<UpsertResult> upserted = new ArrayList<>();
		for (var entry : rowsByCanonical.entrySet()) {
			String canonical = entry.getKey();
			Set<String> aliases = aliasesByCanonical.getOrDefault(canonical, Set.of());
			try {
				List<VulnSourceSnapshot> snapshots = new ArrayList<>();
				for (DtrackFindingVulnRaw v : entry.getValue()) {
					VulnSourceSnapshot snap = toSnapshotFromFinding(v, fetcherEndpoint);
					if (snap != null) snapshots.add(snap);
				}
				if (snapshots.isEmpty()) continue;
				upserted.add(vulnerabilityRecordService.upsertFromSnapshots(orgUuid, aliases, snapshots, wu));
			} catch (Exception e) {
				log.error("Failed to upsert vulnerability_record (V5) for org={} canonical={}: {}",
						orgUuid, canonical, e.getMessage());
			}
		}
		fetchAffectedRangesInline(orgUuid, upserted, fetcherEndpoint, apiToken);
	}

	/** Build a {@link VulnSourceSnapshot} from a V5 finding's vulnerability object. */
	private static VulnSourceSnapshot toSnapshotFromFinding(DtrackFindingVulnRaw v, String fetcherEndpoint) {
		if (v.vulnId() == null) return null;
		VulnSourceSnapshot s = new VulnSourceSnapshot();
		s.setUpstreamSource(mapUpstreamSource(v.source()));
		s.setUpstreamVulnId(v.vulnId());
		s.setFetcher(Fetcher.DEPENDENCY_TRACK);
		s.setFetcherRef(v.uuid());
		s.setFetcherEndpoint(fetcherEndpoint);
		s.setTitle(v.title());
		s.setDescription(v.description());
		s.setSeverity(v.severity());
		// The V5 finding row names the OWASP fields without the RR infix
		// (the vulnerability endpoint keeps it) and carries no CVSS v2 / v3
		// sub-scores; those entries simply have none.
		s.setScores(List.of(
				VulnScore.of(VulnScoreType.CVSS_V2, v.cvssV2BaseScore(), v.cvssV2Vector()),
				VulnScore.of(VulnScoreType.CVSS_V3, v.cvssV3BaseScore(), v.cvssV3Vector()),
				VulnScore.of(VulnScoreType.CVSS_V4, v.cvssV4Score(), v.cvssV4Vector()),
				VulnScore.of(VulnScoreType.EPSS, v.epssScore(), null)
						.withSubScore(VulnSubScoreType.PERCENTILE, v.epssPercentile()),
				VulnScore.of(VulnScoreType.OWASP_RR, null, v.owaspRRVector())
						.withSubScore(VulnSubScoreType.LIKELIHOOD, v.owaspLikelihoodScore())
						.withSubScore(VulnSubScoreType.TECHNICAL_IMPACT, v.owaspTechnicalImpactScore())
						.withSubScore(VulnSubScoreType.BUSINESS_IMPACT, v.owaspBusinessImpactScore())));
		s.setReferences(v.references());
		if (v.published() != null) s.setPublished(v.published().toInstant().atZone(ZoneOffset.UTC));
		List<CweEntry> cwes = new ArrayList<>();
		if (v.cwes() != null) {
			for (DtrackCweRaw raw : v.cwes()) {
				if (raw == null || raw.cweId() == null) continue;
				CweEntry e = new CweEntry();
				e.setCweId(raw.cweId());
				e.setName(raw.name());
				cwes.add(e);
			}
		}
		s.setCwes(cwes);
		return s;
	}

	/**
	 * Merge fresh DTrack rows into {@code rearm.vulnerability_records}.
	 * One canonical row per primary vuln id; rows from different DTrack
	 * upstream feeds (NVD / GITHUB / OSV) for the same vuln are folded
	 * into the same canonical record as separate entries in
	 * {@link VulnerabilityRecordData#getSources()}.
	 *
	 * <p>Best-effort: a single bad row or an isolated upsert failure must
	 * not poison the rest of the DTrack ingest path — we log and continue.
	 * Records that have never had their affected ranges fetched get them
	 * right after, within limits; see {@link #fetchAffectedRangesInline}.
	 */
	private void refreshVulnerabilityRecords(UUID orgUuid,
			Map<String, List<DtrackVulnRaw>> rowsByCanonical,
			Map<String, Set<String>> aliasesByCanonical,
			String fetcherEndpoint, String apiToken) {
		if (rowsByCanonical.isEmpty()) return;
		WhoUpdated wu = WhoUpdated.getAutoWhoUpdated();
		List<UpsertResult> upserted = new ArrayList<>();
		for (var entry : rowsByCanonical.entrySet()) {
			String canonical = entry.getKey();
			List<DtrackVulnRaw> rows = entry.getValue();
			Set<String> aliases = aliasesByCanonical.getOrDefault(canonical, Set.of());
			try {
				List<VulnSourceSnapshot> snapshots = new ArrayList<>(rows.size());
				for (DtrackVulnRaw dvr : rows) {
					VulnSourceSnapshot snap = toSnapshot(dvr, fetcherEndpoint);
					if (snap != null) snapshots.add(snap);
				}
				if (snapshots.isEmpty()) continue;
				upserted.add(vulnerabilityRecordService.upsertFromSnapshots(orgUuid, aliases, snapshots, wu));
			} catch (Exception e) {
				log.error("Failed to upsert vulnerability_record for org={} canonical={}: {}",
						orgUuid, canonical, e.getMessage());
			}
		}
		fetchAffectedRangesInline(orgUuid, upserted, fetcherEndpoint, apiToken);
	}

	/** Ids we accept for a single-vulnerability fetch; also keeps the id safe as a URL path segment. */
	private static final Pattern SINGLE_VULN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

	/** Whether {@code vulnId} (already stripped) is an id a single-vulnerability fetch accepts. */
	public static boolean isValidSingleVulnId(String vulnId) {
		return vulnId != null && SINGLE_VULN_ID.matcher(vulnId).matches();
	}

	private static final Duration SINGLE_VULN_FETCH_TIMEOUT = Duration.ofSeconds(10);

	/** Per-request timeout of single-vulnerability fetches; a field so tests can shorten it. */
	Duration singleVulnFetchTimeout = SINGLE_VULN_FETCH_TIMEOUT;

	/** Upper bound on DT round-trips for one refresh (each 404 is a round-trip). */
	private static final int MAX_SINGLE_VULN_FETCHES = 6;

	/**
	 * Upper bound on DT round-trips for one record's affected ranges: one per
	 * GitHub / OSV id, which for a CVE in several distributions can pass the
	 * refresh's six. Past it the ranges of the first ids, in key order, are
	 * stored and the rest are not fetched; see {@link #fetchAffectedRanges}.
	 */
	private static final int MAX_RANGE_FETCHES = 20;

	/** DT keys a vulnerability by (source, id): GitHub rows by GHSA, OSV rows by ecosystem id. */
	private record DtrackVulnKey(UpstreamSource source, String vulnId) {}

	/** How a round of single-vulnerability fetches ended, apart from the rows it found. */
	enum DtrackFetchOutcome {
		/** Every key answered 200 or 404. */
		CLEAN,
		/** At least one key failed with something other than a 404; the rest were still tried. */
		FAILED,
		/** DT refused the API key (401 / 403); the round stopped, since every key would be refused. */
		AUTH_REJECTED,
		/**
		 * DT could not be reached (connection refused, unknown host, timeout,
		 * or a gateway in front of it answering 502 / 503 / 504); the round
		 * stopped, since every key would fail the same way.
		 */
		UNREACHABLE
	}

	/**
	 * Fetch one vulnerability from the org's Dependency-Track and upsert it into
	 * {@code vulnerability_records}. Serves the "Refresh from Dependency-Track"
	 * action for findings whose record is missing (the drain only records vulns
	 * attributed on the day of the scan) or stale. Callers must make sure the id
	 * is a finding of the org: a record's existence is read elsewhere as "this
	 * vulnerability affects the org" (KEV fan-out, first-insert notifications).
	 *
	 * <p>What is fetched:
	 * <ol>
	 *   <li>when the org already has a record for the id, its first snapshot
	 *       of each source by that snapshot's own (source, upstream id) -- the
	 *       exact DT keys;</li>
	 *   <li>the requested id under the sources its prefix suggests
	 *       ({@link #dtrackSourcesForVulnId});</li>
	 *   <li>one more round for GHSA / CVE aliases the fetched rows name, so a
	 *       CVE refresh also picks up the GitHub advisory the merger ranks
	 *       first;</li>
	 *   <li>the record's further advisories of a source (the first rounds
	 *       take one row per source), with the budget the rounds before left,
	 *       unless Dependency-Track stopped answering.</li>
	 * </ol>
	 * Every 200 becomes a snapshot; 404s are skipped; any other failure is logged
	 * and the next key is tried. The upsert uses
	 * {@link UpsertOrigin#MANUAL_REFRESH} and is seeded with the existing
	 * record's aliases so it lands on the same row. The endpoint and payload are
	 * the same on DT 4 and 5.
	 *
	 * @return the merged record after the upsert
	 * @throws RelizaException with a user-facing message when the id is
	 *         invalid, the org has no DT integration, DT refuses the API key,
	 *         or no key returned the vulnerability
	 */
	public VulnerabilityRecordData fetchSingleVulnerabilityFromDtrack(UUID orgUuid, String vulnId, WhoUpdated wu)
			throws RelizaException {
		String id = vulnId == null ? "" : vulnId.strip();
		if (!isValidSingleVulnId(id)) {
			throw new RelizaException("Invalid vulnerability id");
		}
		IntegrationData dtrackIntegration = getIntegrationDataByOrgTypeIdentifier(orgUuid,
				IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER)
				.orElseThrow(() -> new RelizaException(
						"No Dependency-Track integration is configured for this organization"));
		String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
		String fetcherEndpoint = dtrackIntegration.getUri().toString();

		Set<String> aliases = new LinkedHashSet<>();
		Set<DtrackVulnKey> storedKeys = new LinkedHashSet<>();
		Optional<VulnerabilityRecordData> existing = vulnerabilityRecordService.getByAlias(orgUuid, id);
		if (existing.isPresent()) {
			VulnerabilityRecordData record = existing.get();
			aliases.add(record.getPrimaryVulnId());
			if (record.getAliases() != null) aliases.addAll(record.getAliases());
			if (record.getSources() != null) {
				for (VulnSourceSnapshot snap : record.getSources()) {
					if (snap != null) addFetchableKey(storedKeys, snap.getUpstreamSource(), snap.getUpstreamVulnId());
				}
			}
		}
		Set<DtrackVulnKey> keys = new LinkedHashSet<>(storedKeys);
		for (UpstreamSource source : dtrackSourcesForVulnId(id)) addFetchableKey(keys, source, id);

		Set<DtrackVulnKey> attempted = new LinkedHashSet<>();
		List<DtrackVulnRaw> found = new ArrayList<>();
		DtrackFetchOutcome outcome = fetchDtrackVulnKeys(keys, attempted, found, fetcherEndpoint, apiToken, orgUuid,
				true, MAX_SINGLE_VULN_FETCHES);
		if (outcome == DtrackFetchOutcome.AUTH_REJECTED) {
			throw new RelizaException("Dependency-Track rejected this organization's API key;"
					+ " check the Dependency-Track integration");
		}

		Set<DtrackVulnKey> aliasKeys = new LinkedHashSet<>();
		for (DtrackVulnRaw dvr : found) {
			if (dvr.aliases() == null) continue;
			for (DtrackAliasRaw a : dvr.aliases()) {
				if (a == null) continue;
				addFetchableKey(aliasKeys, UpstreamSource.GITHUB, a.ghsaId());
				addFetchableKey(aliasKeys, UpstreamSource.NVD, a.cveId());
			}
		}
		// No further round once one found Dependency-Track unreachable: every
		// key would wait out the same timeout. What was found is still stored.
		DtrackFetchOutcome aliasOutcome = outcome != DtrackFetchOutcome.UNREACHABLE
				? fetchDtrackVulnKeys(aliasKeys, attempted, found, fetcherEndpoint, apiToken, orgUuid, true,
						MAX_SINGLE_VULN_FETCHES)
				: DtrackFetchOutcome.UNREACHABLE;
		// The record's other advisories of a source it already had a row for
		// (each has a snapshot of its own) get whatever budget is left: the
		// requested id and the aliases come first.
		DtrackFetchOutcome storedOutcome = aliasOutcome != DtrackFetchOutcome.UNREACHABLE
				? fetchDtrackVulnKeys(storedKeys, attempted, found, fetcherEndpoint, apiToken, orgUuid, false,
						MAX_SINGLE_VULN_FETCHES)
				: DtrackFetchOutcome.UNREACHABLE;
		boolean failed = outcome != DtrackFetchOutcome.CLEAN || aliasOutcome != DtrackFetchOutcome.CLEAN
				|| storedOutcome != DtrackFetchOutcome.CLEAN;

		List<VulnSourceSnapshot> snapshots = new ArrayList<>();
		for (DtrackVulnRaw dvr : found) {
			VulnSourceSnapshot snap = toSnapshot(dvr, fetcherEndpoint);
			if (snap == null) continue;
			snapshots.add(snap);
			aliases.addAll(dtrackAliasStrings(dvr));
		}
		if (snapshots.isEmpty()) {
			String searched = attempted.stream().map(k -> k.source().name()).distinct()
					.collect(Collectors.joining(", "));
			throw new RelizaException(failed
					? "Failed to fetch vulnerability " + id + " from Dependency-Track"
					: "Vulnerability " + id + " was not found in Dependency-Track (searched " + searched + ")");
		}
		// Concurrent refreshes / drains of the same record are serialised by
		// the per-record lock inside the upsert.
		VulnerabilityRecordData persisted = vulnerabilityRecordService.upsertFromSnapshots(orgUuid, aliases,
				snapshots, wu, UpsertOrigin.MANUAL_REFRESH).data();
		return refreshAffectedRanges(orgUuid, persisted, fetcherEndpoint, apiToken, found);
	}

	/**
	 * The refresh's ranges, fetched the way the sweep fetches them: the
	 * refresh round is capped and may not reach every GitHub / OSV id the
	 * record knows, so its responses alone are not a complete list.
	 * The rows it did fetch are reused rather than requested again. A failure
	 * here leaves the stored ranges as they were and the refresh still
	 * succeeds.
	 *
	 * @return the record as stored after the ranges
	 */
	private VulnerabilityRecordData refreshAffectedRanges(UUID orgUuid, VulnerabilityRecordData persisted,
			String fetcherEndpoint, String apiToken, List<DtrackVulnRaw> alreadyFetched) {
		try {
			AffectedRangesFetchResult result = fetchAffectedRanges(orgUuid, persisted, fetcherEndpoint, apiToken,
					alreadyFetched);
			if (result.status() != AffectedRangesFetchStatus.FETCHED) return persisted;
			vulnerabilityRecordService.storeAffectedRanges(persisted.getUuid(), result.fetched());
			return vulnerabilityRecordService.getData(orgUuid, persisted.getPrimaryVulnId()).orElse(persisted);
		} catch (Exception e) {
			log.error("Failed to refresh the affected ranges of vulnerability {} for org {}",
					persisted.getPrimaryVulnId(), orgUuid, e);
			return persisted;
		}
	}

	private static void addFetchableKey(Set<DtrackVulnKey> keys, UpstreamSource source, String vulnId) {
		if (source == null || toDtrackSource(source) == null || vulnId == null) return;
		String id = vulnId.strip();
		if (SINGLE_VULN_ID.matcher(id).matches()) keys.add(new DtrackVulnKey(source, id));
	}

	/**
	 * GET each not-yet-attempted key (with {@code onePerSource}, only while
	 * its source has no row yet), up to {@code maxAttempts} attempts in
	 * total, adding every 200 to {@code found}. A 404 is the
	 * normal "not under this source" answer. A 401 / 403 stops the round:
	 * the key is refused for every source, so trying the rest would only
	 * repeat the error. Other 4xx are logged without a stack trace (DT
	 * answered, the request was wrong); 5xx and parse errors keep theirs. No
	 * connection, no answer in time, or a gateway's 502 / 503 / 504 stops the
	 * round too: every key would fail the same way.
	 */
	private DtrackFetchOutcome fetchDtrackVulnKeys(Set<DtrackVulnKey> keys, Set<DtrackVulnKey> attempted,
			List<DtrackVulnRaw> found, String fetcherEndpoint, String apiToken, UUID orgUuid,
			boolean onePerSource, int maxAttempts) {
		DtrackFetchOutcome outcome = DtrackFetchOutcome.CLEAN;
		for (DtrackVulnKey key : keys) {
			if (attempted.size() >= maxAttempts) break;
			// One row per source while the budget is shared with guesses: keys
			// are ordered stored-advisory first, so each source's first stored
			// advisory is refreshed, and a key guessed from an id's prefix or an
			// alias is not spent on a source that already answered. The record's
			// further advisories of a source come in a later round.
			if (onePerSource && found.stream().anyMatch(f -> mapUpstreamSource(f.source()) == key.source())) continue;
			if (!attempted.add(key)) continue;
			URI uri = URI.create(fetcherEndpoint + "/api/v1/vulnerability/source/" + toDtrackSource(key.source())
					+ "/vuln/" + URLEncoder.encode(key.vulnId(), StandardCharsets.UTF_8));
			try {
				var resp = dtrackWebClient
						.get()
						.uri(uri)
						.header("X-API-Key", apiToken)
						.retrieve()
						.toEntity(String.class)
						.timeout(singleVulnFetchTimeout)
						.block();
				if (resp != null && resp.getBody() != null) {
					found.add(Utils.OM.readValue(resp.getBody(), DtrackVulnRaw.class));
				}
			} catch (WebClientResponseException wcre) {
				HttpStatusCode status = wcre.getStatusCode();
				if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) continue;
				if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED) || status.isSameCodeAs(HttpStatus.FORBIDDEN)) {
					log.error("Dependency-Track refused the API key ({}) fetching vulnerability {} for org {}",
							status.value(), key.vulnId(), orgUuid);
					return DtrackFetchOutcome.AUTH_REJECTED;
				}
				if (isGatewayUnavailable(status)) {
					log.error("Dependency-Track at {} is unavailable ({}) fetching vulnerability {} for org {}",
							fetcherEndpoint, status.value(), key.vulnId(), orgUuid);
					return DtrackFetchOutcome.UNREACHABLE;
				}
				outcome = DtrackFetchOutcome.FAILED;
				if (status.is4xxClientError()) {
					log.error("Dependency-Track returned {} fetching vulnerability {} (source {}) for org {}: {}",
							status.value(), key.vulnId(), key.source(), orgUuid, wcre.getMessage());
				} else {
					log.error("Dependency-Track returned {} fetching vulnerability {} (source {}) for org {}",
							status.value(), key.vulnId(), key.source(), orgUuid, wcre);
				}
			} catch (Exception e) {
				if (isUnreachable(e)) {
					log.error("Dependency-Track at {} could not be reached fetching vulnerability {} for org {}: {}",
							fetcherEndpoint, key.vulnId(), orgUuid, e.getMessage());
					return DtrackFetchOutcome.UNREACHABLE;
				}
				outcome = DtrackFetchOutcome.FAILED;
				log.error("Error fetching vulnerability {} (source {}) from Dependency-Track for org {}",
						key.vulnId(), key.source(), orgUuid, e);
			}
		}
		return outcome;
	}

	/**
	 * Whether a failed request means Dependency-Track itself could not be
	 * reached: no connection (WebClient wraps connect and I/O errors in
	 * {@link WebClientRequestException}) or no answer within the timeout
	 * ({@code Mono.timeout}'s TimeoutException, which {@code block()} wraps).
	 */
	private static boolean isUnreachable(Exception e) {
		return e instanceof WebClientRequestException || Exceptions.unwrap(e) instanceof TimeoutException;
	}

	/**
	 * Whether a status is a proxy or load balancer answering for a
	 * Dependency-Track that is down or overloaded, rather than DT failing one
	 * request: every other request of the round would get the same.
	 */
	private static boolean isGatewayUnavailable(HttpStatusCode status) {
		return status.isSameCodeAs(HttpStatus.BAD_GATEWAY) || status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)
				|| status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT);
	}

	/**
	 * Dependency-Track sources to try, in order, for a vulnerability id when
	 * nothing better is known. Ecosystem ids live under OSV; GHSA under GITHUB
	 * (OSV as a fallback for instances mirroring GHSA through OSV); a CVE under
	 * NVD first, as its authoritative home (GitHub and OSV rows are usually
	 * keyed by their own ids, which the alias round then reaches); anything
	 * else tries OSV first.
	 */
	static List<UpstreamSource> dtrackSourcesForVulnId(String vulnId) {
		String upper = vulnId.toUpperCase(Locale.ROOT);
		// OSV's Debian tracker ids (DEBIAN-CVE-*) have no VulnerabilityAliasType;
		// like the ecosystem ids they are published under OSV only.
		if (upper.startsWith("DEBIAN-")) return List.of(UpstreamSource.OSV);
		return switch (ReleaseMetricsDto.detectAliasType(upper)) {
			case GHSA -> List.of(UpstreamSource.GITHUB, UpstreamSource.OSV);
			case PYSEC, RUST, GO, ALPINE -> List.of(UpstreamSource.OSV);
			case CVE -> List.of(UpstreamSource.NVD, UpstreamSource.GITHUB, UpstreamSource.OSV);
			default -> List.of(UpstreamSource.OSV, UpstreamSource.GITHUB, UpstreamSource.NVD);
		};
	}

	/** The row's own id plus its CVE / GHSA aliases, as the drain collects them. */
	private static Set<String> dtrackAliasStrings(DtrackVulnRaw dvr) {
		Set<String> out = new LinkedHashSet<>();
		if (dvr.vulnId() != null) out.add(dvr.vulnId());
		if (dvr.aliases() != null) {
			for (DtrackAliasRaw a : dvr.aliases()) {
				if (a == null) continue;
				if (a.cveId() != null && !a.cveId().isBlank()) out.add(a.cveId());
				if (a.ghsaId() != null && !a.ghsaId().isBlank()) out.add(a.ghsaId());
			}
		}
		return out;
	}

	/** Cap on range fetches right after one drain; the rest wait for the nightly sweep. */
	private static final int INLINE_AFFECTED_RANGES_LIMIT = 100;

	/**
	 * Wall-clock bound on the range fetches right after one drain. They run
	 * on the ingest path (the per-minute synthetic tick, a GraphQL probe), so
	 * a slow Dependency-Track must not hold it up; whatever is left waits for
	 * the nightly sweep.
	 */
	private static final Duration INLINE_AFFECTED_RANGES_BUDGET = Duration.ofSeconds(15);

	/** How fetching one record's affected ranges ended. */
	public enum AffectedRangesFetchStatus {
		/** Every source answered (a 404 counts); the ranges are complete, possibly empty. */
		FETCHED,
		/** The org has no Dependency-Track integration, so there is nowhere to fetch from. */
		NO_INTEGRATION,
		/** Dependency-Track refused the org's API key; every other record of the org would fail the same way. */
		AUTH_REJECTED,
		/** Dependency-Track could not be reached; every other record of the org would fail the same way. */
		UNREACHABLE,
		/** A source failed for this vulnerability (5xx, other 4xx, unreadable payload); nothing may be stored. */
		FAILED
	}

	/**
	 * @param fetched  the complete fetch, ready to store, when {@link AffectedRangesFetchStatus#FETCHED}; else null
	 * @param requests Dependency-Track requests made; zero for a record with no GitHub or OSV id
	 */
	public record AffectedRangesFetchResult(AffectedRangesFetchStatus status, FetchedAffectedRanges fetched,
			int requests) {}

	/**
	 * Fetch a record's affected ranges from its org's Dependency-Track: one
	 * single-vulnerability GET per key of
	 * {@link VulnerabilityRecordData#rangeFetchKeys}, the ranges of all
	 * responses together. Does not store anything. A record with more than
	 * {@link #MAX_RANGE_FETCHES} keys gets the ranges of the first ones in key
	 * order, and the rest are never fetched (logged): treating the fetch as
	 * incomplete would request the record in full every night instead.
	 */
	public AffectedRangesFetchResult fetchAffectedRanges(UUID orgUuid, VulnerabilityRecordData record) {
		Optional<IntegrationData> dtrack = getIntegrationDataByOrgTypeIdentifier(orgUuid,
				IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (dtrack.isEmpty()) {
			return new AffectedRangesFetchResult(AffectedRangesFetchStatus.NO_INTEGRATION, null, 0);
		}
		return fetchAffectedRanges(orgUuid, record, dtrack.get().getUri().toString(),
				encryptionService.decrypt(dtrack.get().getSecret()), List.of());
	}

	/**
	 * @param alreadyFetched rows the caller already has for some keys (the
	 *                       refresh round's); those keys are not requested again
	 */
	private AffectedRangesFetchResult fetchAffectedRanges(UUID orgUuid, VulnerabilityRecordData record,
			String fetcherEndpoint, String apiToken, List<DtrackVulnRaw> alreadyFetched) {
		// Taken before the requests: a slower fetch must not look newer than one that started later.
		ZonedDateTime startedAt = ZonedDateTime.now();
		Set<RangeSourceKey> fromKeys = record.rangeFetchKeys();
		Set<DtrackVulnKey> keys = new LinkedHashSet<>();
		for (RangeSourceKey k : fromKeys) addFetchableKey(keys, k.source(), k.vulnId());
		Set<DtrackVulnKey> attempted = new LinkedHashSet<>();
		List<DtrackVulnRaw> found = new ArrayList<>();
		for (DtrackVulnRaw dvr : alreadyFetched) {
			DtrackVulnKey key = new DtrackVulnKey(mapUpstreamSource(dvr.source()), dvr.vulnId());
			if (keys.contains(key) && attempted.add(key)) found.add(dvr);
		}
		int reused = attempted.size();
		DtrackFetchOutcome outcome = keys.isEmpty() ? DtrackFetchOutcome.CLEAN
				: fetchDtrackVulnKeys(keys, attempted, found, fetcherEndpoint, apiToken, orgUuid, false,
						MAX_RANGE_FETCHES);
		if (outcome == DtrackFetchOutcome.CLEAN && !attempted.containsAll(keys)) {
			log.info("Vulnerability {} of org {} has {} GitHub / OSV ids; ranges fetched from the first {}, the rest not fetched",
					record.getPrimaryVulnId(), orgUuid, keys.size(), MAX_RANGE_FETCHES);
		}
		AffectedRangesFetchStatus status = switch (outcome) {
			case CLEAN -> AffectedRangesFetchStatus.FETCHED;
			case FAILED -> AffectedRangesFetchStatus.FAILED;
			case AUTH_REJECTED -> AffectedRangesFetchStatus.AUTH_REJECTED;
			case UNREACHABLE -> AffectedRangesFetchStatus.UNREACHABLE;
		};
		return new AffectedRangesFetchResult(status, status == AffectedRangesFetchStatus.FETCHED
				? new FetchedAffectedRanges(affectedRangesOf(found), startedAt, fromKeys) : null,
				attempted.size() - reused);
	}

	/**
	 * Right after a drain, fetch and store the affected ranges of the records
	 * it created, so a new vulnerability has its ranges within minutes rather
	 * than after the nightly sweep. Only new records: everything else due for
	 * a fetch (the backlog after an upgrade, changed sources) is the sweep's,
	 * off the ingest path. Bounded by {@link #INLINE_AFFECTED_RANGES_LIMIT}
	 * fetches and {@link #INLINE_AFFECTED_RANGES_BUDGET}, and stops at the
	 * first failed fetch: the ingest path must not wait on a struggling
	 * Dependency-Track. What is left, the sweep picks up.
	 */
	private void fetchAffectedRangesInline(UUID orgUuid, List<UpsertResult> upserts,
			String fetcherEndpoint, String apiToken) {
		fetchAffectedRangesInline(orgUuid, upserts, fetcherEndpoint, apiToken,
				INLINE_AFFECTED_RANGES_LIMIT, INLINE_AFFECTED_RANGES_BUDGET);
	}

	/** {@link #fetchAffectedRangesInline(UUID, List, String, String)} with explicit bounds, for tests. */
	void fetchAffectedRangesInline(UUID orgUuid, List<UpsertResult> upserts,
			String fetcherEndpoint, String apiToken, int limit, Duration budget) {
		long deadline = System.nanoTime() + budget.toNanos();
		int fetched = 0;
		int left = 0;
		for (UpsertResult upsert : upserts) {
			if (upsert == null || upsert.outcome() != UpsertOutcome.INSERTED) continue;
			VulnerabilityRecordData record = upsert.data();
			if (record.getAffectedRangesFetchedAt() != null) continue;
			if (left > 0 || fetched >= limit || System.nanoTime() > deadline) {
				left++;
				continue;
			}
			try {
				AffectedRangesFetchResult result = fetchAffectedRanges(orgUuid, record, fetcherEndpoint, apiToken,
						List.of());
				if (result.status() != AffectedRangesFetchStatus.FETCHED) {
					// fetchDtrackVulnKeys has logged the cause.
					left++;
					continue;
				}
				vulnerabilityRecordService.storeAffectedRanges(record.getUuid(), result.fetched());
				if (result.requests() > 0) fetched++;
			} catch (Exception e) {
				log.error("Failed to store affected ranges of vulnerability record {} for org {}",
						record.getPrimaryVulnId(), orgUuid, e);
				left++;
			}
		}
		if (left > 0) {
			log.info("Affected ranges fetched for {} new vulnerability records of org {}; {} left for the nightly sweep",
					fetched, orgUuid, left);
		}
	}

	/** The package-URL ranges of Dependency-Track single-vulnerability responses, all together. */
	private static List<AffectedRange> affectedRangesOf(List<DtrackVulnRaw> rows) {
		List<AffectedRange> out = new ArrayList<>();
		for (DtrackVulnRaw dvr : rows) out.addAll(toAffectedRanges(dvr.affectedComponents(), dvr.source()));
		return out;
	}

	/**
	 * One response's {@code affectedComponents} as ranges. Keeps package-URL
	 * identities only (see {@link VulnerabilityRecordData#PURL_RANGE_SOURCES}).
	 * A range's sources are
	 * its attributions; a range without any is attributed to the row's own
	 * source. Not normalized: the record's setter does that.
	 *
	 * <p>An every-version row (OSV {@code introduced: "0"} with nothing fixed,
	 * as in Debian's tracker for an unfixed package) comes from
	 * Dependency-Track 4 without a version type and from Dependency-Track 5
	 * as a range from {@code "0"}. Both become a range with no bounds (the
	 * record's setter drops a start of {@code "0"}, see
	 * {@link AffectedRange#normalize}), so the same advisory reads the same
	 * from either version. A row without a type but with a version is that
	 * exact version. A version type we do not know is kept without a type,
	 * with whatever version or bounds came with it.
	 */
	static List<AffectedRange> toAffectedRanges(List<DtrackAffectedComponentRaw> components, String rowSource) {
		List<AffectedRange> out = new ArrayList<>();
		if (components == null) return out;
		for (DtrackAffectedComponentRaw c : components) {
			if (c == null || c.identityType() != AffectedIdentityType.PURL || StringUtils.isBlank(c.identity())) continue;
			AffectedRange r = new AffectedRange();
			r.setIdentityType(AffectedIdentityType.PURL);
			r.setIdentity(c.identity());
			AffectedRangeType type = rangeTypeOf(c);
			r.setRangeType(type);
			if (type != AffectedRangeType.RANGE) r.setExactVersion(c.version());
			r.setVersionStartIncluding(c.versionStartIncluding());
			r.setVersionStartExcluding(c.versionStartExcluding());
			r.setVersionEndIncluding(c.versionEndIncluding());
			r.setVersionEndExcluding(c.versionEndExcluding());
			List<UpstreamSource> sources = new ArrayList<>();
			if (c.affectedVersionAttributions() != null) {
				for (DtrackAffectedVersionAttributionRaw a : c.affectedVersionAttributions()) {
					if (a != null && a.source() != null) sources.add(mapUpstreamSource(a.source()));
				}
			}
			if (sources.isEmpty()) sources.add(mapUpstreamSource(rowSource));
			r.setSources(sources);
			out.add(r);
		}
		return out;
	}

	/**
	 * A row's version type as ours: absent means a range, or that exact
	 * version when the row carries one; a type we do not know is null.
	 */
	private static AffectedRangeType rangeTypeOf(DtrackAffectedComponentRaw c) {
		if (c.versionType() != null) return EnumUtils.getEnum(AffectedRangeType.class, c.versionType());
		return StringUtils.isNotBlank(c.version()) ? AffectedRangeType.EXACT : AffectedRangeType.RANGE;
	}

	/**
	 * Convert one DTrack row into a per-source snapshot. Returns null if
	 * the row is missing the bits we need to identify the source (e.g.
	 * no {@code source} field at all — bookkeeping rows DTrack occasionally
	 * emits).
	 */
	private static VulnSourceSnapshot toSnapshot(DtrackVulnRaw dvr, String fetcherEndpoint) {
		if (dvr.vulnId() == null) return null;
		VulnSourceSnapshot s = new VulnSourceSnapshot();
		s.setUpstreamSource(mapUpstreamSource(dvr.source()));
		s.setUpstreamVulnId(dvr.vulnId());
		s.setFetcher(Fetcher.DEPENDENCY_TRACK);
		s.setFetcherRef(dvr.uuid());
		s.setFetcherEndpoint(fetcherEndpoint);
		s.setTitle(dvr.title());
		s.setDescription(dvr.description());
		s.setSeverity(dvr.severity());
		// Types the row has nothing for are dropped by setScores.
		s.setScores(List.of(
				VulnScore.of(VulnScoreType.CVSS_V2, dvr.cvssV2BaseScore(), dvr.cvssV2Vector())
						.withSubScore(VulnSubScoreType.IMPACT, dvr.cvssV2ImpactSubScore())
						.withSubScore(VulnSubScoreType.EXPLOITABILITY, dvr.cvssV2ExploitabilitySubScore()),
				VulnScore.of(VulnScoreType.CVSS_V3, dvr.cvssV3BaseScore(), dvr.cvssV3Vector())
						.withSubScore(VulnSubScoreType.IMPACT, dvr.cvssV3ImpactSubScore())
						.withSubScore(VulnSubScoreType.EXPLOITABILITY, dvr.cvssV3ExploitabilitySubScore()),
				VulnScore.of(VulnScoreType.CVSS_V4, dvr.cvssV4Score(), dvr.cvssV4Vector()),
				VulnScore.of(VulnScoreType.EPSS, dvr.epssScore(), null)
						.withSubScore(VulnSubScoreType.PERCENTILE, dvr.epssPercentile()),
				VulnScore.of(VulnScoreType.OWASP_RR, null, dvr.owaspRRVector())
						.withSubScore(VulnSubScoreType.LIKELIHOOD, dvr.owaspRRLikelihoodScore())
						.withSubScore(VulnSubScoreType.TECHNICAL_IMPACT, dvr.owaspRRTechnicalImpactScore())
						.withSubScore(VulnSubScoreType.BUSINESS_IMPACT, dvr.owaspRRBusinessImpactScore())));
		s.setReferences(dvr.references());
		if (dvr.published() != null) s.setPublished(dvr.published().toInstant().atZone(ZoneOffset.UTC));
		if (dvr.updated() != null) s.setUpdated(dvr.updated().toInstant().atZone(ZoneOffset.UTC));
		List<CweEntry> cwes = new ArrayList<>();
		if (dvr.cwes() != null) {
			for (DtrackCweRaw raw : dvr.cwes()) {
				if (raw == null || raw.cweId() == null) continue;
				CweEntry e = new CweEntry();
				e.setCweId(raw.cweId());
				e.setName(raw.name());
				cwes.add(e);
			}
		}
		s.setCwes(cwes);
		return s;
	}

	/**
	 * Translate DTrack's free-form {@code source} string into our enum.
	 * DTrack canonicalises to upper-case ({@code NVD} / {@code GITHUB} /
	 * {@code OSV} / {@code VULNDB}); anything else falls into
	 * {@link UpstreamSource#OTHER} so a future DTrack version that adds
	 * a new feed doesn't drop the snapshot on the floor.
	 */
	/**
	 * Inverse of {@link #mapUpstreamSource}: the DT source name for a
	 * {@code /vulnerability/source/{source}/...} path. Null for OTHER, which
	 * has no DT counterpart.
	 */
	private static String toDtrackSource(UpstreamSource source) {
		return switch (source) {
			case GITHUB -> "GITHUB";
			case OSV -> "OSV";
			case NVD -> "NVD";
			case VULNDB -> "VULNDB";
			case OTHER -> null;
		};
	}

	private static UpstreamSource mapUpstreamSource(String dtrackSource) {
		if (dtrackSource == null) return UpstreamSource.OTHER;
		switch (dtrackSource.toUpperCase()) {
			case "GITHUB":  return UpstreamSource.GITHUB;
			case "OSV":     return UpstreamSource.OSV;
			case "NVD":     return UpstreamSource.NVD;
			case "VULNDB":  return UpstreamSource.VULNDB;
			default:        return UpstreamSource.OTHER;
		}
	}
	
	protected List<ViolationDto> fetchDependencyTrackViolationDetails(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned) throws DatabindException, JacksonException, RelizaException {
		return fetchDependencyTrackViolationDetailsWithCpe(dtrackBaseUri, apiToken, dtrackProject,
				artifactUuid, orgUuid, lastScanned).stream().map(ViolationWithCpe::violation).collect(java.util.stream.Collectors.toList());
	}

	/**
	 * Same as {@link #fetchDependencyTrackViolationDetails} but pairs each
	 * violation with its component's CPE (synthetic-SBOM ingest mapping).
	 */
	public List<ViolationWithCpe> fetchDependencyTrackViolationDetailsWithCpe(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned) throws DatabindException, JacksonException, RelizaException {
		String baseUri = dtrackBaseUri.toString() + "/api/v1/violation/project/" + dtrackProject;
		final FindingSourceDto source = new FindingSourceDto(artifactUuid, null, null);
		final Set<FindingSourceDto> sources = Set.of(source);

		// Get ignore patterns from organization
		OrganizationData.IgnoreViolation ignoreViolation = null;
		Optional<OrganizationData> orgOpt = getOrganizationService.getOrganizationData(orgUuid);
		if (orgOpt.isPresent()) {
			ignoreViolation = orgOpt.get().getIgnoreViolation();
		}
		final OrganizationData.IgnoreViolation finalIgnoreViolation = ignoreViolation;

		return executeDtrackPaginatedCallWithTransform(baseUri, apiToken, "",
				CommonVariables.DTRACK_VIOLATIONS_PAGE_SIZE, rawPage -> {
			List<ViolationWithCpe> pageResults = new ArrayList<>();
			for (Object vd : rawPage) {
				DtrackViolationRaw dvr = Utils.OM.convertValue(vd, DtrackViolationRaw.class);
				// Decode URL-encoded @ symbol in purl from DTrack
				String purl = dvr.component().purl() != null ? dvr.component().purl().replace("%40", "@") : null;
				ViolationType violationType = dvr.type();

				// Check if this violation should be ignored based on purl regex patterns
				if (shouldIgnoreViolation(purl, violationType, finalIgnoreViolation)) {
					log.debug("Ignoring violation for purl {} of type {} based on org ignore patterns", purl, violationType);
					continue;
				}

				String licenseId;
				if (null != dvr.component().resolvedLicense()) {
					licenseId = dvr.component().resolvedLicense().licenseId();
				} else if (StringUtils.isNotEmpty(dvr.component().licenseExpression)) {
					licenseId = dvr.component().licenseExpression;
				} else {
					licenseId = "undetected";
				}
				ViolationDto vdto = new ViolationDto(purl, violationType,
						licenseId, null, sources, null, null, lastScanned != null ? lastScanned : ZonedDateTime.now());
				pageResults.add(new ViolationWithCpe(vdto, dvr.component().cpe()));
			}
			return pageResults;
		});
	}
	
	private boolean shouldIgnoreViolation(String purl, ViolationType violationType, OrganizationData.IgnoreViolation ignoreViolation) {
		if (ignoreViolation == null || purl == null) {
			return false;
		}
		
		List<String> patterns = null;
		if (violationType == ViolationType.LICENSE) {
			patterns = ignoreViolation.getLicenseViolationRegexIgnore();
		} else if (violationType == ViolationType.SECURITY) {
			patterns = ignoreViolation.getSecurityViolationRegexIgnore();
		} else if (violationType == ViolationType.OPERATIONAL) {
			patterns = ignoreViolation.getOperationalViolationRegexIgnore();
		}
		
		if (patterns == null || patterns.isEmpty()) {
			return false;
		}
		
		for (String pattern : patterns) {
			try {
				if (java.util.regex.Pattern.compile(pattern).matcher(purl).matches()) {
					return true;
				}
			} catch (java.util.regex.PatternSyntaxException e) {
				log.warn("Invalid regex pattern in ignoreViolation: {}", pattern);
			}
		}
		return false;
	}
	
	public record ComponentPurlToDtrackProject (String purl, List<UUID> projects) {}
	
	public record SbomComponentSearchQuery (String name, String version) {}
	
	private List<ComponentPurlToDtrackProject> searchDependencyTrackComponent (String query, UUID org, String version) throws RelizaException {
		long startTime = System.currentTimeMillis();
		List<ComponentPurlToDtrackProject> sbomComponents = new LinkedList<>();
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(org, IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isPresent()) {
			IntegrationData dtrackIntegration = oid.get();
			try {
				String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
				List<Map<String, Object>> respList = new LinkedList<>();
				String baseUri = dtrackIntegration.getUri().toString() + "/api/v1/component/identity";
				String versionParam = StringUtils.isNotEmpty(version) ? "&version=" + version : "";
				long beforeSearch = System.currentTimeMillis();
				if (query.startsWith("pkg:")) {
					String queryParams = "?purl=" + query + versionParam;
					respList = executeDtrackComponentSearch(baseUri, apiToken, queryParams);
					log.info("searchDependencyTrackComponent - purl search took {} ms, found {} results", System.currentTimeMillis() - beforeSearch, respList.size());
				} else {
					respList = executeDtrackNameAndGroupSearchParallel(baseUri, apiToken, query, versionParam);
					log.info("searchDependencyTrackComponent - parallel name+group search took {} ms, found {} results", 
							System.currentTimeMillis() - beforeSearch, respList.size());
				}
				long beforeProcessing = System.currentTimeMillis();
				if (null != respList && !respList.isEmpty()) {
					sbomComponents = respList
						.stream()
						.filter(x -> null != x.get("purl") && StringUtils.isNotEmpty((String) x.get("purl")))
						.collect(Collectors.groupingBy(x -> URLDecoder.decode((String) x.get("purl"), StandardCharsets.UTF_8), 
								Collectors.mapping(x -> UUID.fromString(((Map<String, String>) x.get("project")).get("uuid")), 
											Collectors.toList())))
						.entrySet().stream()
						.map(e -> new ComponentPurlToDtrackProject(e.getKey(), e.getValue())).toList();
				}
				log.info("searchDependencyTrackComponent - processing took {} ms, produced {} components, total {} ms", 
						System.currentTimeMillis() - beforeProcessing, sbomComponents.size(), System.currentTimeMillis() - startTime);
			} catch (Exception e) {
				log.error("Exception searching components on dtrack for query = " + query + " and org = " + org, e);
				throw new RelizaException("Error searching SBOM components");
			}
		}
		return sbomComponents;
	}
	
	public List<ComponentPurlToDtrackProject> searchDependencyTrackComponentBatch (List<SbomComponentSearchQuery> queries, UUID org) throws RelizaException {
		long batchStartTime = System.currentTimeMillis();
		log.info("searchDependencyTrackComponentBatch - starting batch search for {} queries", queries.size());
		
		// Use a concurrent map to combine results by purl, merging project lists
		Map<String, List<UUID>> combinedResults = new ConcurrentHashMap<>();
		
		// Execute searches in parallel with up to 4 threads
		ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, queries.size()));
		List<Future<List<ComponentPurlToDtrackProject>>> futures = new ArrayList<>();
		
		for (SbomComponentSearchQuery query : queries) {
			futures.add(executor.submit(() -> searchDependencyTrackComponent(query.name(), org, query.version())));
		}
		
		long parallelStartTime = System.currentTimeMillis();
		try {
			for (Future<List<ComponentPurlToDtrackProject>> future : futures) {
				List<ComponentPurlToDtrackProject> results = future.get();
				for (ComponentPurlToDtrackProject result : results) {
					combinedResults.computeIfAbsent(result.purl(), k -> Collections.synchronizedList(new LinkedList<>())).addAll(result.projects());
				}
			}
		} catch (InterruptedException | ExecutionException e) {
			log.error("Exception during parallel SBOM component search", e);
			throw new RelizaException("Error searching SBOM components");
		} finally {
			executor.shutdown();
		}
		log.info("searchDependencyTrackComponentBatch - parallel execution took {} ms", System.currentTimeMillis() - parallelStartTime);
		
		// Convert back to list, deduplicating projects per purl
		long dedupeStartTime = System.currentTimeMillis();
		List<ComponentPurlToDtrackProject> result = combinedResults.entrySet().stream()
			.map(e -> new ComponentPurlToDtrackProject(e.getKey(), e.getValue().stream().distinct().toList()))
			.toList();
		log.info("searchDependencyTrackComponentBatch - deduplication took {} ms, produced {} unique purls, total batch time {} ms", 
				System.currentTimeMillis() - dedupeStartTime, result.size(), System.currentTimeMillis() - batchStartTime);
		return result;
	}

	/**
	 * Execute a paginated DTrack API call, fetching all pages until no more results.
	 * @param baseUri Base URI without pagination parameters
	 * @param apiToken DTrack API token
	 * @param existingParams Any existing query parameters (should end with & if not empty, or be empty)
	 * @return List of all results from all pages
	 */
	private List<Object> executeDtrackPaginatedCall(String baseUri, String apiToken, String existingParams)
			throws DatabindException, JacksonException, RelizaException {
		return executeDtrackPaginatedCall(baseUri, apiToken, existingParams, CommonVariables.DTRACK_DEFAULT_PAGE_SIZE);
	}

	private List<Object> executeDtrackPaginatedCall(String baseUri, String apiToken, String existingParams, int pageSize)
			throws DatabindException, JacksonException, RelizaException {
		List<Object> allResults = new ArrayList<>();
		int pageNumber = 1;
		boolean hasMorePages = true;
		String separator = existingParams.isEmpty() ? "?" : (existingParams.endsWith("&") ? "" : "&");
		
		while (hasMorePages) {
			DtrackPageResult pageResult = fetchDtrackPage(baseUri, apiToken, existingParams, separator, pageNumber, pageSize);
			
			if (pageResult != null && pageResult.results() != null && !pageResult.results().isEmpty()) {
				allResults.addAll(pageResult.results());
			}
			
			// Stop if: null result, empty result, fetched all items per totalCount, or partial page
			if (pageResult == null || pageResult.results() == null || pageResult.results().isEmpty()
					|| pageResult.results().size() < pageSize
					|| (pageResult.totalCount() > 0 && allResults.size() >= pageResult.totalCount())) {
				hasMorePages = false;
			}
			
			pageNumber++;
		}
		return allResults;
	}
	
	/**
	 * Paginated DTrack call that transforms each page's raw objects immediately,
	 * discarding the raw data per page to reduce peak memory usage.
	 * @param baseUri Base URI for the DTrack API endpoint
	 * @param apiToken API token for authentication
	 * @param existingParams Existing query parameters
	 * @param pageTransformer Function that transforms a page of raw objects into the target type
	 * @return List of all transformed results from all pages
	 */
	// Package-private (not private) so the pagination loop's
	// fail-fast-on-exception contract is unit-testable via a subclass that
	// overrides fetchDtrackPage. See IntegrationServiceDtrackPaginationTest.
	<T> List<T> executeDtrackPaginatedCallWithTransform(String baseUri, String apiToken,
			String existingParams, Function<List<Object>, List<T>> pageTransformer) throws RelizaException {
		return executeDtrackPaginatedCallWithTransform(baseUri, apiToken, existingParams,
				CommonVariables.DTRACK_DEFAULT_PAGE_SIZE, pageTransformer);
	}

	<T> List<T> executeDtrackPaginatedCallWithTransform(String baseUri, String apiToken,
			String existingParams, int pageSize, Function<List<Object>, List<T>> pageTransformer) throws RelizaException {
		List<T> allResults = new ArrayList<>();
		int pageNumber = 1;
		boolean hasMorePages = true;
		int totalRawFetched = 0;
		String separator = existingParams.isEmpty() ? "?" : (existingParams.endsWith("&") ? "" : "&");

		while (hasMorePages) {
			DtrackPageResult pageResult = fetchDtrackPage(baseUri, apiToken, existingParams, separator, pageNumber, pageSize);

			if (pageResult != null && pageResult.results() != null && !pageResult.results().isEmpty()) {
				allResults.addAll(pageTransformer.apply(pageResult.results()));
				totalRawFetched += pageResult.results().size();
			}

			// Stop if: null result, empty result, fetched all items per totalCount, or partial page
			if (pageResult == null || pageResult.results() == null || pageResult.results().isEmpty()
					|| pageResult.results().size() < pageSize
					|| (pageResult.totalCount() > 0 && totalRawFetched >= pageResult.totalCount())) {
				hasMorePages = false;
			}

			pageNumber++;
		}
		return allResults;
	}
	
	// Package-private (not private) so a test subclass can override and
	// inject deterministic page results / exceptions.
	DtrackPageResult fetchDtrackPage(String baseUri, String apiToken, String existingParams,
			String separator, int pageNumber, int pageSize) throws RelizaException {
		URI dtrackUri = URI.create(baseUri + existingParams + separator + "pageNumber=" + pageNumber + "&pageSize=" + pageSize);
		try {
			log.debug("Calling DTrack API page fetch: uri = {}", dtrackUri);
			final long httpStartNs = System.nanoTime();
			var resp = dtrackWebClient
				.get()
				.uri(dtrackUri)
				.header("X-API-Key", apiToken)
				.retrieve()
				.toEntity(String.class)
				.block();

			// Null body is a legitimate empty-page signal (some DTrack endpoints
			// return an empty body with 200 when the project has no findings).
			// Distinct from a thrown exception — that path now propagates.
			if (null == resp.getBody()) {
				log.warn("Null body from DTrack API for uri = {}", dtrackUri);
				return null;
			}

			int totalCount = parseDtrackTotalCountHeader(resp);

			@SuppressWarnings("unchecked")
			List<Object> pageResults = Utils.OM.readValue(resp.getBody(), List.class);
			log.debug("DTrack API page fetch completed: uri = {}, pageNumber = {}, pageSize = {}, totalCount = {}, httpMs = {}",
				dtrackUri, pageNumber, pageSize, totalCount, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - httpStartNs));
			return new DtrackPageResult(pageResults, totalCount);
		} catch (Exception e) {
			// Previously returned null here — but null is the "no more pages"
			// signal upstream, so a transient HTTP/parse failure mid-drain
			// would silently truncate the result list and the caller would
			// persist an empty vulnerability set, overwriting the artifact's
			// previous good data. Propagate as a checked exception so the
			// scheduled-refresh persist site short-circuits instead.
			log.error("Error fetching DTrack page {} for uri = {}", pageNumber, dtrackUri, e);
			throw new RelizaException("Error fetching DTrack page " + pageNumber + " for uri " + dtrackUri + ": " + e.getMessage());
		}
	}
	
	private List<Map<String, Object>> executeDtrackComponentSearch (String baseUri, String apiToken, String queryParams)
			throws DatabindException, JacksonException, RelizaException {
		List<Object> results = executeDtrackPaginatedCall(baseUri, apiToken, queryParams, 400);
		List<Map<String, Object>> respList = new LinkedList<>();
		for (Object obj : results) {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) obj;
			respList.add(map);
		}
		return respList;
	}
	
	private List<Map<String, Object>> executeDtrackNameAndGroupSearchParallel(String baseUri, String apiToken, String query, String versionParam) {
		String queryParams1 = "?name=" + query + versionParam;
		String queryParams2 = "?group=" + query + versionParam;
		
		var nameFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return executeDtrackComponentSearch(baseUri, apiToken, queryParams1);
			} catch (Exception e) {
				throw new RuntimeException("Error in name search", e);
			}
		});
		var groupFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return executeDtrackComponentSearch(baseUri, apiToken, queryParams2);
			} catch (Exception e) {
				throw new RuntimeException("Error in group search", e);
			}
		});
		
		var respList1 = nameFuture.join();
		var respList2 = groupFuture.join();
		List<Map<String, Object>> combined = new LinkedList<>();
		combined.addAll(respList1);
		combined.addAll(respList2);
		return combined;
	}
	
	
	/**
	 * Retrieve unsynced vulnerabilities from Dependency Track based on last sync time
	 * @param orgUuid Organization UUID
	 * @param lastSyncTime Last sync time to filter findings
	 * @return Set of project UUIDs from vulnerability findings
	 */
	private Set<UUID> retrieveUnsyncedDtrackVulnerabilities(UUID orgUuid, ZonedDateTime lastSyncTime) {
		Set<UUID> projectUuids = new HashSet<>();
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(orgUuid, IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isPresent()) {
			IntegrationData dtrackIntegration = oid.get();
			try {
				String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
				String dateFilter = (lastSyncTime != null) ? "&attributedOnDateFrom=" + lastSyncTime.toLocalDate().toString() : "";
				
				// Paginated retrieval - extract project UUIDs per page to avoid OOM
				int pageNumber = 1;
				int pageSize = CommonVariables.DTRACK_DEFAULT_PAGE_SIZE;
				boolean hasMorePages = true;
				int totalVulnerabilitiesProcessed = 0;
				
				while (hasMorePages) {
					URI dtrackUri = URI.create(dtrackIntegration.getUri().toString() + 
							"/api/v1/finding?pageNumber=" + pageNumber + "&pageSize=" + pageSize + dateFilter);
					
					log.debug("Fetching vulnerability findings page {} with size {} for org {}", pageNumber, pageSize, orgUuid);
					
					var resp = dtrackWebClient
							.get()
							.uri(dtrackUri)
							.header("X-API-Key", apiToken)
							.retrieve()
							.toEntity(String.class)
							.block();
					
					int totalCount = parseDtrackTotalCountHeader(resp);
					
					@SuppressWarnings("unchecked")
					List<Object> pageFindings = Utils.OM.readValue(resp.getBody(), List.class);
					
					if (pageFindings.isEmpty()) {
						// No more records, stop pagination
						hasMorePages = false;
						log.debug("No more vulnerability findings found at page {} for org {}", pageNumber, orgUuid);
					} else {
						// Extract project UUIDs from this page immediately to avoid holding all findings in memory
						for (Object finding : pageFindings) {
							try {
								@SuppressWarnings("unchecked")
								Map<String, Object> findingMap = (Map<String, Object>) finding;
								@SuppressWarnings("unchecked")
								Map<String, Object> component = (Map<String, Object>) findingMap.get("component");
								if (component != null) {
									Object projectObj = component.get("project");
									if (projectObj != null) {
										UUID projectUuid = UUID.fromString(projectObj.toString());
										projectUuids.add(projectUuid);
									}
								}
							} catch (Exception e) {
								log.warn("Error parsing project UUID from vulnerability finding: {}", e.getMessage());
							}
						}
						totalVulnerabilitiesProcessed += pageFindings.size();
						log.debug("Retrieved {} vulnerability findings from page {} for org {}", pageFindings.size(), pageNumber, orgUuid);
						
						// Stop if: partial page or fetched all items per totalCount
						if (pageFindings.size() < pageSize
								|| (totalCount > 0 && totalVulnerabilitiesProcessed >= totalCount)) {
							hasMorePages = false;
							log.debug("Last page reached for org {} (pageSize={}, received={}, totalProcessed={}, totalCount={})", 
									orgUuid, pageSize, pageFindings.size(), totalVulnerabilitiesProcessed, totalCount);
						}
					}
					
					pageNumber++;
				}
				
				log.info("Retrieved {} unique project UUIDs from {} unsynced vulnerabilities from Dependency Track for org {}", 
						projectUuids.size(), totalVulnerabilitiesProcessed, orgUuid);
			} catch (WebClientResponseException wcre) {
				if (wcre.getStatusCode() == HttpStatus.NOT_FOUND) {
					log.warn("Dependency Track integration not found or no vulnerabilities available for org {}", orgUuid);
				} else {
					log.error("Web exception retrieving unsynced vulnerabilities from Dependency Track for org " + orgUuid, wcre);
				}
			} catch (Exception e) {
				log.error("Exception retrieving unsynced vulnerabilities from Dependency Track for org " + orgUuid, e);
			}
		}
		return projectUuids;
	}
	
	/**
	 * Retrieve unsynced violations from Dependency Track based on last sync time
	 * @param orgUuid Organization UUID
	 * @param lastSyncTime Last sync time to filter violations
	 * @return Set of project UUIDs from violation findings
	 */
	private Set<UUID> retrieveUnsyncedDtrackViolations(UUID orgUuid, ZonedDateTime lastSyncTime) {
		Set<UUID> projectUuids = new HashSet<>();
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(orgUuid, IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isPresent()) {
			IntegrationData dtrackIntegration = oid.get();
			try {
				String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
				String dateFilter = (lastSyncTime != null) ? "&occurredOnDateFrom=" + lastSyncTime.toLocalDate().toString() : "";
				
				// Paginated retrieval - extract project UUIDs per page to avoid OOM
				int pageNumber = 1;
				int pageSize = CommonVariables.DTRACK_VIOLATIONS_PAGE_SIZE;
				boolean hasMorePages = true;
				int totalViolationsProcessed = 0;
				
				while (hasMorePages) {
					URI dtrackUri = URI.create(dtrackIntegration.getUri().toString() + 
							"/api/v1/violation?pageNumber=" + pageNumber + "&pageSize=" + pageSize + dateFilter);
					
					log.debug("Fetching violation findings page {} with size {} for org {}", pageNumber, pageSize, orgUuid);
					
					var resp = dtrackWebClient
							.get()
							.uri(dtrackUri)
							.header("X-API-Key", apiToken)
							.retrieve()
							.toEntity(String.class)
							.block();
					
					int totalCount = parseDtrackTotalCountHeader(resp);
					
					@SuppressWarnings("unchecked")
					List<Object> pageFindings = Utils.OM.readValue(resp.getBody(), List.class);
					
					if (pageFindings.isEmpty()) {
						// No more records, stop pagination
						hasMorePages = false;
						log.debug("No more violation findings found at page {} for org {}", pageNumber, orgUuid);
					} else {
						// Extract project UUIDs from this page immediately to avoid holding all findings in memory
						for (Object violation : pageFindings) {
							try {
								@SuppressWarnings("unchecked")
								Map<String, Object> violationMap = (Map<String, Object>) violation;
								@SuppressWarnings("unchecked")
								Map<String, Object> project = (Map<String, Object>) violationMap.get("project");
								if (project != null) {
									Object projectUuidObj = project.get("uuid");
									if (projectUuidObj != null) {
										UUID projectUuid = UUID.fromString(projectUuidObj.toString());
										projectUuids.add(projectUuid);
									}
								}
							} catch (Exception e) {
								log.warn("Error parsing project UUID from violation finding: {}", e.getMessage());
							}
						}
						totalViolationsProcessed += pageFindings.size();
						log.debug("Retrieved {} violation findings from page {} for org {}", pageFindings.size(), pageNumber, orgUuid);
						
						// Stop if: partial page or fetched all items per totalCount
						if (pageFindings.size() < pageSize
								|| (totalCount > 0 && totalViolationsProcessed >= totalCount)) {
							hasMorePages = false;
							log.debug("Last page reached for org {} (pageSize={}, received={}, totalProcessed={}, totalCount={})", 
									orgUuid, pageSize, pageFindings.size(), totalViolationsProcessed, totalCount);
						}
					}
					pageNumber++;
				}
				
				log.info("Retrieved {} unique project UUIDs from {} unsynced violations from Dependency Track for org {}", 
						projectUuids.size(), totalViolationsProcessed, orgUuid);
			} catch (WebClientResponseException wcre) {
				if (wcre.getStatusCode() == HttpStatus.NOT_FOUND) {
					log.warn("Dependency Track integration not found or no violations available for org {}", orgUuid);
				} else {
					log.error("Web exception retrieving unsynced violations from Dependency Track for org " + orgUuid, wcre);
				}
			} catch (Exception e) {
				log.error("Exception retrieving unsynced violations from Dependency Track for org " + orgUuid, e);
			}
		}
		return projectUuids;
	}
	
	public UUID searchDtrackComponentByPurlAndProjects(UUID orgUuid, String purl, Collection<UUID> dtrackProjects) {
		Iterator<UUID> dtrackProjectsIter = dtrackProjects.iterator();
		UUID componentUuid = null;
		while (null == componentUuid && dtrackProjectsIter.hasNext()) {
			UUID projectUuid = dtrackProjectsIter.next();
			componentUuid = searchDtrackComponentByPurlAndProject(orgUuid, purl, projectUuid);
		}
		return componentUuid;
	}
	
	/**
	 * Search for a component in Dependency Track by PURL and project UUID
	 * @param orgUuid Organization UUID
	 * @param purl Package URL (PURL) to search for
	 * @param projectUuid Project UUID to search within
	 * @return Component data if found, null otherwise
	 */
	private UUID searchDtrackComponentByPurlAndProject(UUID orgUuid, String purl, UUID projectUuid) {
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(orgUuid, IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isPresent()) {
			IntegrationData dtrackIntegration = oid.get();
			try {
				String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
				
				// Build the URI with purl and project parameters
				URI dtrackUri = URI.create(dtrackIntegration.getUri().toString() + 
						"/api/v1/component/identity?purl=" + java.net.URLEncoder.encode(purl, "UTF-8") + 
						"&project=" + projectUuid.toString());
				
				log.debug("Searching for component with PURL {} in project {} for org {}", purl, projectUuid, orgUuid);
				
				var resp = dtrackWebClient
						.get()
						.uri(dtrackUri)
						.header("X-API-Key", apiToken)
						.retrieve()
						.toEntity(String.class)
						.block();
				
				if (resp != null && resp.getBody() != null) {
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> components = Utils.OM.readValue(resp.getBody(), List.class);
					log.debug("Found component for PURL {} in project {} for org {}", purl, projectUuid, orgUuid);
					if (null != components && !components.isEmpty()) {
						return UUID.fromString((String) components.get(0).get("uuid"));
					}
				}
				
			} catch (WebClientResponseException wcre) {
				if (wcre.getStatusCode() == HttpStatus.NOT_FOUND) {
					log.warn("Component not found for PURL {} in project {} for org {}", purl, projectUuid, orgUuid);
				} else {
					log.error("Web exception searching for component with PURL {} in project {} for org {}", purl, projectUuid, orgUuid, wcre);
				}
			} catch (Exception e) {
				log.error("Exception searching for component with PURL {} in project {} for org {}", purl, projectUuid, orgUuid, e);
			}
		}
		return null;
	}
	
	/**
	 * Retrieve all unsynced projects from Dependency Track based on last sync time
	 * Combines both vulnerability and violation findings into a single set of project UUIDs
	 * @param orgUuid Organization UUID
	 * @param lastSyncTime Last sync time to filter findings
	 * @return Set of project UUIDs that have unsynced vulnerabilities or violations
	 */
	public Set<UUID> retrieveUnsyncedDtrackProjects(UUID orgUuid, ZonedDateTime lastSyncTime) {
		Set<UUID> allProjectUuids = new HashSet<>();
		
		// Get projects with unsynced vulnerabilities
		Set<UUID> vulnerabilityProjects = retrieveUnsyncedDtrackVulnerabilities(orgUuid, lastSyncTime);
		allProjectUuids.addAll(vulnerabilityProjects);
		
		// Get projects with unsynced violations
		Set<UUID> violationProjects = retrieveUnsyncedDtrackViolations(orgUuid, lastSyncTime);
		allProjectUuids.addAll(violationProjects);
		
		log.info("Retrieved {} total unique project UUIDs ({} from vulnerabilities, {} from violations) for org {}", 
				allProjectUuids.size(), vulnerabilityProjects.size(), violationProjects.size(), orgUuid);
		
		return allProjectUuids;
	}
	
	/**
	 * Admin only call to re-encrypt all integration secrets with new encryption algorithm
	 */
	public void rotateEncryption (WhoUpdated wu) {
		var integrations = repository.findAll();
		integrations.forEach(i -> {
			var intD = IntegrationData.dataFromRecord(i);
			// re-encrypt secret if present
			var oldSecret = intD.getSecret();
			if (StringUtils.isNotEmpty(oldSecret)) {
				String pt = encryptionService.decrypt(oldSecret);
				String ct = encryptionService.encrypt(pt);
				intD.setSecret(ct);
				saveIntegration(i, Utils.dataToRecord(intD), wu);
			}
		});
	}
	
	/**
	 * Result of one phase-out tick: how many legacy DTrack projects were deleted,
	 * how many deletes failed (left for the next tick), and how many artifact rows
	 * had their legacy reference cleared.
	 */
	public record LegacyDtrackPhaseOutResult(int projectsDeleted, int projectsFailed, int artifactsCleared) {}

	/**
	 * One tick of the legacy per-artifact DTrack project phase-out. Deletes up to
	 * {@code batchLimit} unique legacy projects (globally, grouped per org so each
	 * org's DTrack token is used) and, on a successful delete, clears the project
	 * reference off the referring artifacts. A failed delete leaves the reference
	 * in place so the project is retried next tick. See
	 * {@code SchedulingService.scheduleLegacyDtrackProjectPhaseOut}.
	 */
	public LegacyDtrackPhaseOutResult phaseOutLegacyDtrackProjects(int batchLimit) {
		List<Object[]> rows = artifactRepository.listLegacyDtrackProjectsForPhaseOut(batchLimit);
		if (rows.isEmpty()) {
			return new LegacyDtrackPhaseOutResult(0, 0, 0);
		}
		// Group projects by org so we look up each org's DTrack token once.
		Map<UUID, List<String>> projectsByOrg = new HashMap<>();
		for (Object[] row : rows) {
			String projectId = row[0] == null ? null : String.valueOf(row[0]);
			String orgStr = row[1] == null ? null : String.valueOf(row[1]);
			if (projectId == null || projectId.isBlank() || orgStr == null || orgStr.isBlank()) continue;
			projectsByOrg.computeIfAbsent(UUID.fromString(orgStr), k -> new ArrayList<>()).add(projectId);
		}

		int deleted = 0, failed = 0, cleared = 0;
		for (Map.Entry<UUID, List<String>> e : projectsByOrg.entrySet()) {
			UUID orgUuid = e.getKey();
			Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(
					orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
			if (oid.isEmpty()) {
				// Org dropped its DTrack integration; we can't delete the project. Leave
				// the refs — harmless, and a re-added integration lets the next tick proceed.
				log.debug("[DTRACK-PHASEOUT] No DTrack integration for org {}; skipping {} project(s)",
						orgUuid, e.getValue().size());
				continue;
			}
			IntegrationData dtrackIntegration = oid.get();
			String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
			for (String projectId : e.getValue()) {
				try {
					if (deleteDtrackProject(dtrackIntegration, apiToken, projectId)) {
						deleted++;
						cleared += artifactRepository.clearDtrackProjectRef(orgUuid.toString(), projectId);
					} else {
						failed++;
					}
				} catch (Exception ex) {
					failed++;
					log.warn("[DTRACK-PHASEOUT] Error phasing out project {} (org {}): {}",
							projectId, orgUuid, ex.getMessage());
				}
			}
		}
		log.info("[DTRACK-PHASEOUT] tick complete: deleted={}, failed={}, artifacts_cleared={}",
				deleted, failed, cleared);
		return new LegacyDtrackPhaseOutResult(deleted, failed, cleared);
	}

	/** Outcome of {@link #deleteDtrackProjectForOrg}. */
	public enum DtrackProjectDeletionOutcome {
		/** Project is gone (includes the already-deleted 404 case). */
		DELETED,
		/** Org has no base DTrack integration; nothing to delete through. */
		NO_INTEGRATION,
		/** Transport / auth / server error — retryable. */
		FAILED
	}

	/**
	 * Delete one Dependency-Track project on behalf of an org, resolving the
	 * org's base DTrack integration and credentials internally. Callers decide
	 * what {@code NO_INTEGRATION} means for them: the legacy phase-out leaves
	 * refs in place awaiting a re-added integration, while synthetic-bucket
	 * retirement converges (the project is unreachable by us and harmless).
	 */
	public DtrackProjectDeletionOutcome deleteDtrackProjectForOrg(UUID orgUuid, String projectId) {
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(
				orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isEmpty()) return DtrackProjectDeletionOutcome.NO_INTEGRATION;
		try {
			String apiToken = encryptionService.decrypt(oid.get().getSecret());
			return deleteDtrackProject(oid.get(), apiToken, projectId)
					? DtrackProjectDeletionOutcome.DELETED
					: DtrackProjectDeletionOutcome.FAILED;
		} catch (Exception e) {
			// Undecryptable secret, missing encryption config, etc. — same
			// retryable class as a transport failure.
			log.warn("Could not delete DTrack project {} for org {}: {}",
					projectId, orgUuid, e.getMessage());
			return DtrackProjectDeletionOutcome.FAILED;
		}
	}

	/**
	 * Upper bound on one DTrack project deletion. Both callers run inside
	 * shared scheduler passes (bucket retirement on the PT1M synthetic tick,
	 * the legacy phase-out on its own), and the client has no other timeout —
	 * an unresponsive DTrack would otherwise hold the tick for the OS-level
	 * connect timeout (or indefinitely on a wedged response) PER project.
	 * A timeout surfaces as the retryable FAILED outcome: retirement stays
	 * PENDING and re-attempts next tick.
	 */
	private static final java.time.Duration DTRACK_PROJECT_DELETE_TIMEOUT =
			java.time.Duration.ofSeconds(10);

	/**
	 * Delete a project from Dependency Track
	 */
	boolean deleteDtrackProject(IntegrationData dtrackIntegration, String apiToken, String projectId) {
		try {
			URI deleteUri = URI.create(dtrackIntegration.getUri().toString() + "/api/v1/project/" + projectId);
			log.debug("[DTRACK-CLEANUP] Calling individual delete API: {}", deleteUri);

			var response = dtrackWebClient
				.delete()
				.uri(deleteUri)
				.header("X-API-Key", apiToken)
				.retrieve()
				.toEntity(String.class)
				.timeout(DTRACK_PROJECT_DELETE_TIMEOUT)
				.block();
			
			boolean success = response.getStatusCode().is2xxSuccessful();
			log.debug("[DTRACK-CLEANUP] Individual delete response for {}: status={}, success={}", 
				projectId, response.getStatusCode(), success);
			return success;
			
		} catch (WebClientResponseException wcre) {
			if (wcre.getStatusCode() == HttpStatus.NOT_FOUND) {
				log.info("[DTRACK-CLEANUP] DTrack project {} already deleted (404) - treating as success", projectId);
				return true;
			}
			log.error("[DTRACK-CLEANUP] WebClient error deleting DTrack project {}: status={}, message={}", 
				projectId, wcre.getStatusCode(), wcre.getMessage());
			return false;
		} catch (Exception e) {
			log.error("[DTRACK-CLEANUP] Unexpected error deleting DTrack project {}: {} - {}",
				projectId, e.getClass().getSimpleName(), e.getMessage());
			return false;
		}
	}

	/** PKCS#8 PrivateKeyInfo middle: AlgorithmIdentifier { rsaEncryption, NULL }. */
	private static final byte[] PKCS8_RSA_ALG_ID = new byte[] {
			0x30, 0x0d,
			0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
			0x05, 0x00
	};

	/**
	 * Wrap a PKCS#1 RSAPrivateKey DER blob in a PKCS#8 PrivateKeyInfo
	 * structure. Hardcodes the 2-byte length form, which is correct for
	 * any RSA key big enough to be usable with GitHub Apps (≥ 1024 bits).
	 */
	private static byte[] wrapPkcs1AsPkcs8 (byte[] pkcs1) {
		int pkcs1Len = pkcs1.length;
		int seqContentLen = 3
				+ PKCS8_RSA_ALG_ID.length
				+ 4
				+ pkcs1Len;
		byte[] out = new byte[4 + seqContentLen];
		int p = 0;
		out[p++] = 0x30; out[p++] = (byte) 0x82;
		out[p++] = (byte) ((seqContentLen >> 8) & 0xff);
		out[p++] = (byte) (seqContentLen & 0xff);
		out[p++] = 0x02; out[p++] = 0x01; out[p++] = 0x00;
		System.arraycopy(PKCS8_RSA_ALG_ID, 0, out, p, PKCS8_RSA_ALG_ID.length);
		p += PKCS8_RSA_ALG_ID.length;
		out[p++] = 0x04; out[p++] = (byte) 0x82;
		out[p++] = (byte) ((pkcs1Len >> 8) & 0xff);
		out[p++] = (byte) (pkcs1Len & 0xff);
		System.arraycopy(pkcs1, 0, out, p, pkcs1Len);
		return out;
	}

	/**
	 * Normalize a GitHub App private key to canonical PKCS#8 DER base64.
	 * Accepts: PEM PKCS#1 (-----BEGIN RSA PRIVATE KEY-----) — wrapped to
	 * PKCS#8; PEM PKCS#8 (-----BEGIN PRIVATE KEY-----) — body re-encoded
	 * as-is; DER PKCS#8 base64 (no PEM markers) — left as-is so existing
	 * stored secrets and the legacy "openssl pkcs8 ... | base64" recipe
	 * keep working. The read side (SaasIntegrationService.getGithubKey)
	 * always expects PKCS#8 DER base64, so all three inputs converge here.
	 */
	public static String normalizeGithubAppPrivateKey (String input) {
		if (StringUtils.isEmpty(input)) return input;
		String trimmed = input.trim();
		if (trimmed.startsWith("-----BEGIN ")) {
			boolean isPkcs1 = trimmed.contains("-----BEGIN RSA PRIVATE KEY-----");
			String body = trimmed
					.replaceAll("-----BEGIN [A-Z ]+-----", "")
					.replaceAll("-----END [A-Z ]+-----", "")
					.replaceAll("\\s+", "");
			byte[] der = Base64.getDecoder().decode(body);
			if (isPkcs1) der = wrapPkcs1AsPkcs8(der);
			return Base64.getEncoder().encodeToString(der);
		}
		return trimmed.replaceAll("\\s+", "");
	}
}
