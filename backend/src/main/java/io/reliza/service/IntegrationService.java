/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

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
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.TextPayload;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.CweEntry;
import io.reliza.model.VulnerabilityRecordData.Fetcher;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.model.VulnerabilityRecordData.VulnSourceSnapshot;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.IntegrationWebDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.SeveritySourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.TriggerIntegrationInputDto;
import io.reliza.model.OrganizationData;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.IntegrationRepository;
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
	
	// slack var
	private static final String SLACK_URI_PREFIX = "https://hooks.slack.com/services/";	
	
	private final WebClient slackWebClient = WebClient
													.builder()
													.baseUrl(SLACK_URI_PREFIX)
													.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
													.build();
	private final WebClient teamsWebClient = WebClient
			.builder()
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	
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
	
	@Transactional
	public Integration createIntegration (String identifier, UUID organization, IntegrationType type,
			URI uri, String secret, String schedule, URI frontendUri, WhoUpdated wu) {
		Integration i = new Integration();
		String encryptedSecret = encryptionService.encrypt(secret);
		IntegrationData id = IntegrationData.integrationDataFactory(identifier, organization, type, uri, encryptedSecret, frontendUri);
		if (StringUtils.isNotEmpty(schedule)) id.setSchedule(schedule.toString());
		return saveIntegration(i, Utils.dataToRecord(id), wu);
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
			throws DatabindException, JacksonException {
		Integration i = new Integration();
		String secret = tii.getSecret();
		if (tii.getType() == IntegrationType.GITHUB) {
			secret = normalizeGithubAppPrivateKey(secret);
		}
		String encryptedSecret = encryptionService.encrypt(secret);
		IntegrationData id = new IntegrationData();
		id.setOrg(tii.getOrg());
		id.setIdentifier("TRIGGER_" + UUID.randomUUID().toString());
		id.setType(tii.getType());
		id.setSecret(encryptedSecret);
		id.setNote(tii.getNote());
		id.setCapabilities(tii.getCapabilities());
		if (StringUtils.isNotEmpty(tii.getSchedule())) id.setSchedule(tii.getSchedule());
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
			List<IntegrationData.IntegrationCapability> capabilities, WhoUpdated wu) {
		Integration i = repository.findById(uuid)
				.orElseThrow(() -> new IllegalArgumentException("Integration not found: " + uuid));
		IntegrationData id = IntegrationData.dataFromRecord(i);
		id.setCapabilities(capabilities == null ? List.of() : capabilities);
		return saveIntegration(i, Utils.dataToRecord(id), wu);
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
	
	public void sendNotification (UUID orgUuid, IntegrationType notificationType, String channelIdentifier, List<TextPayload> payload) {
		if (!payload.isEmpty()) {
			switch (notificationType) {
			case SLACK:
				// retrieve notification secret
				Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(orgUuid, notificationType, channelIdentifier);
				if (oid.isPresent()) {
					String secret = encryptionService.decrypt(oid.get().getSecret());
					// construct payload string for Slack
					StringBuilder payloadStrSlackSb = parseTextPayloadForSlack(payload);
					Map<String, String> payloadMap = Map.of("text", payloadStrSlackSb.toString());
					slackWebClient.post().uri(secret).bodyValue(payloadMap)
						.retrieve()
						.toEntity(String.class)
						.subscribe(
							successValue -> {},
							error -> {
								log.error(error.getMessage());
							}
						);
				}
				break;
			case MSTEAMS:
				// retrieve notification secret
				Optional<IntegrationData> oidteams = getIntegrationDataByOrgTypeIdentifier(orgUuid, notificationType, channelIdentifier);
				if (oidteams.isPresent()) {
					String secret = encryptionService.decrypt(oidteams.get().getSecret());
					StringBuilder payloadStrTeamsSb = parseTextPayloadForMsteams(payload, true);
					try {
						Map<String, Object> payloadMap = wrapTeamsPayloadInActionCard(payloadStrTeamsSb.toString());
						URI teamsUri = URI.create(secret);
						var twc = teamsWebClient.post().uri(teamsUri);
						    twc.bodyValue(payloadMap).retrieve()
							.toEntity(String.class)
							.subscribe(
								successValue -> {},
								error -> {
									if (error instanceof WebClientResponseException wcre) {
										log.error("Teams webhook error: status={}, body={}", wcre.getStatusCode(), wcre.getResponseBodyAsString());
									} else {
										log.error("Teams webhook error: {}", error.getMessage(), error);
									}
								}
							);
					} catch (Exception e) {
						log.error("Error on submitting Teams notification", e);
					}
				}
				break;
			default:
				break;
			}
		}
	}
	
	private Map<String, Object> wrapTeamsPayloadInActionCard (String payload) throws DatabindException, JacksonException {
		String cardSample = """
	    {
	       "type":"message",
	       "attachments":[
	          {
	             "contentType":"application/vnd.microsoft.card.adaptive",
	             "contentUrl":null,
	             "content":{
					  "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
					  "type": "AdaptiveCard",
					  "version": "1.5",
					  "body": [
					    {
					      "type": "TextBlock",
					      "text": ""
					    }
					  ],
					  "msteams": {
					  	"width": "Full"
					  }
				 }
	         }
	       ]
	    }
				""";
		@SuppressWarnings("unchecked")
		Map<String, Object> cardSampleParsed = Utils.OM.readValue(cardSample, Map.class);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> attArr = (List<Map<String, Object>>) cardSampleParsed.get("attachments");
		@SuppressWarnings("unchecked")
		Map<String, Object> contentMap = (Map<String, Object>) attArr.get(0).get("content");
		@SuppressWarnings("unchecked")
		List<Map<String, String>> bodyArr = (List<Map<String, String>>) contentMap.get("body");
		Map<String, String> bodyTextMap = bodyArr.get(0);
		bodyTextMap.put("text", payload);
		return cardSampleParsed;
	}
	
	private StringBuilder parseTextPayloadForMsteams(List<TextPayload> payload, boolean withUris) {
		StringBuilder payloadStrTeamsSb = new StringBuilder();
		// emojis in teams - see 
		// here - https://apps.timwhitlock.info/emoji/tables/unicode
		// and here - https://stackoverflow.com/questions/53384141/teams-webhook-send-emojis-in-notifications
		for (var p: payload) {
			if (null == p.uri() || !withUris) {
				String addText = p.text();
				boolean hasEmoji = false;
				switch (addText) {
				case "\n":
					addText = "<br />";
					break;
				case ":white_check_mark:":
					addText = "&#x2705;";
					hasEmoji = true;
					break;
				case ":x:":
					addText = "&#x274c;";
					hasEmoji = true;
					break;
				case ":floppy_disk:":
					addText = "&#x1F4BE;";
					hasEmoji = true;
					break;
				default:
					break;
				}
				// no URI mode also does not support emojis
				if (!(!withUris && hasEmoji)) payloadStrTeamsSb.append(addText);
			} else {
				String addTextUri = p.text();
				if (":package:".equals(addTextUri)) addTextUri = "&#x1F4E6;";
				payloadStrTeamsSb.append("[");
				payloadStrTeamsSb.append(addTextUri);
				payloadStrTeamsSb.append("](");
				payloadStrTeamsSb.append(p.uri());
				payloadStrTeamsSb.append(")");
			}
		}
		return payloadStrTeamsSb;
	}
	
	private StringBuilder parseTextPayloadForSlack(List<TextPayload> payload) {
		StringBuilder payloadStrSlackSb = new StringBuilder();
		for (var p: payload) {
			if (null == p.uri()) {
				payloadStrSlackSb.append(p.text());
			} else {
				payloadStrSlackSb.append("<");
				payloadStrSlackSb.append(p.uri());
				payloadStrSlackSb.append("|");
				payloadStrSlackSb.append(p.text());
				payloadStrSlackSb.append(">");
			}
		}
		return payloadStrSlackSb;
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
	
	private static int parseDtrackTotalCountHeader(org.springframework.http.ResponseEntity<?> resp) {
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
			Double cvssV3BaseScore, String cvssV3Vector,
			Double cvssV3ImpactSubScore, Double cvssV3ExploitabilitySubScore,
			Double cvssV4Score, String cvssV4Vector,
			Double epssScore, Double epssPercentile,
			String uuid, Date published, Date updated) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackViolationRaw (ViolationType type, DtrackComponentRaw component) {}
	
	protected List<VulnerabilityDto> fetchDependencyTrackVulnerabilityDetails(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned) throws DatabindException, JacksonException, RelizaException {
		return fetchDependencyTrackVulnerabilityDetailsWithCpe(dtrackBaseUri, apiToken, dtrackProject,
				artifactUuid, orgUuid, lastScanned).stream().map(VulnWithCpe::vuln).collect(java.util.stream.Collectors.toList());
	}

	/**
	 * Same as {@link #fetchDependencyTrackVulnerabilityDetails} but pairs each
	 * finding with its component's CPE so the synthetic-SBOM ingest can map
	 * cpe-only (purl-less) findings back to their canonical component.
	 */
	public List<VulnWithCpe> fetchDependencyTrackVulnerabilityDetailsWithCpe(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid, UUID orgUuid, ZonedDateTime lastScanned) throws DatabindException, JacksonException, RelizaException {
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
			refreshVulnerabilityRecords(orgUuid, rowsByCanonical, aliasesByCanonical, fetcherEndpoint);
		}

		return all;
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
	 */
	private void refreshVulnerabilityRecords(UUID orgUuid,
			Map<String, List<DtrackVulnRaw>> rowsByCanonical,
			Map<String, Set<String>> aliasesByCanonical,
			String fetcherEndpoint) {
		if (rowsByCanonical.isEmpty()) return;
		WhoUpdated wu = WhoUpdated.getAutoWhoUpdated();
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
				vulnerabilityRecordService.upsertFromSnapshots(orgUuid, aliases, snapshots, wu);
			} catch (Exception e) {
				log.error("Failed to upsert vulnerability_record for org={} canonical={}: {}",
						orgUuid, canonical, e.getMessage());
			}
		}
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
		s.setCvssV3BaseScore(dvr.cvssV3BaseScore());
		s.setCvssV3Vector(dvr.cvssV3Vector());
		s.setCvssV3ImpactSubScore(dvr.cvssV3ImpactSubScore());
		s.setCvssV3ExploitabilitySubScore(dvr.cvssV3ExploitabilitySubScore());
		s.setCvssV4Score(dvr.cvssV4Score());
		s.setCvssV4Vector(dvr.cvssV4Vector());
		s.setEpssScore(dvr.epssScore());
		s.setEpssPercentile(dvr.epssPercentile());
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
	static String normalizeGithubAppPrivateKey (String input) {
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
