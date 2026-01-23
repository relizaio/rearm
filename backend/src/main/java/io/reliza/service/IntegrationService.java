/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Collection;
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
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.TextPayload;
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
	@Lazy
	private DTrackService dtrackService;
	
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
	
	final int dtrackBufferSize = 64 * 1024 * 1024;
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
			throws JsonMappingException, JsonProcessingException {
		Integration i = new Integration();
		String encryptedSecret = encryptionService.encrypt(tii.getSecret());
		IntegrationData id = new IntegrationData();
		id.setOrg(tii.getOrg());
		id.setIdentifier("TRIGGER_" + UUID.randomUUID().toString());
		id.setType(tii.getType());
		id.setSecret(encryptedSecret);
		id.setNote(tii.getNote());
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
									log.error(error.getMessage());
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
	
	private Map<String, Object> wrapTeamsPayloadInActionCard (String payload) throws JsonMappingException, JsonProcessingException {
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
			UUID projectId, UploadableBom bom, String projectName, String projectVersion) throws JsonMappingException, JsonProcessingException {
		String apiUriStr = dtrackIntegration.getUri().toString() + "/api/v1/bom";
		URI apiUri = URI.create(apiUriStr);
		String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
		

		String bomBase64;
		
		if (!bom.isRaw()) {
			String bomString = "";
			try {
				bomString = Utils.OM.writeValueAsString(bom.bomJson());
			} catch (JsonProcessingException e) {
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
	
	/**
	 * Upload a new BOM to an existing Dependency Track project.
	 * Used when updating an artifact that already has a DTrack project associated.
	 */
	public DependencyTrackUploadResult uploadBomToExistingDtrackProject(UUID orgUuid, UploadableBom bom, 
			UUID existingProjectId, String projectName, String projectVersion) {
		try {
			return dtrackService.uploadBomToExistingProject(orgUuid, bom, existingProjectId, 
				projectName, projectVersion);
		} catch (RelizaException e) {
			log.error("Error uploading BOM to existing Dependency Track project: " + existingProjectId, e);
			throw new RuntimeException("Error uploading BOM to existing Dependency Track project: " + e.getMessage());
		}
	}
	
	protected DependencyTrackIntegration resolveDependencyTrackProcessingStatus (ArtifactData ad, ZonedDateTime lastScanned) throws RelizaException {
		if (null == lastScanned) lastScanned = ZonedDateTime.now();
		DependencyTrackIntegration dti = null;
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(ad.getOrg(), IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isPresent()) {
			IntegrationData dtrackIntegration = oid.get();
			try {
				String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
				URI eventTokenUri = URI.create(dtrackIntegration.getUri().toString() + "/api/v1/event/token/" + ad.getMetrics().getUploadToken());
				var processingResp = dtrackWebClient
						.get()
						.uri(eventTokenUri)
						.header("X-API-Key", apiToken)
						.retrieve()
						.toEntity(String.class)
						.block();
				@SuppressWarnings("unchecked")
				Map<String, Object> processingRespMap = Utils.OM.readValue(processingResp.getBody(), Map.class);
				Boolean isProcessing = (Boolean) processingRespMap.get("processing");
				if (!isProcessing) {
					dti = obtainDepdencyTrackProjectMetrics(dtrackIntegration.getUri(), apiToken, ad);
					URI fullDtrackUri = URI.create(dtrackIntegration.getFrontendUri()
							.toString() + "/projects/" + ad.getMetrics().getDependencyTrackProject());
					dti.setDependencyTrackFullUri(fullDtrackUri);
					dti.setDependencyTrackProject(ad.getMetrics().getDependencyTrackProject());
					dti.setUploadToken(ad.getMetrics().getUploadToken());
					dti.setLastScanned(lastScanned);
				}
			} catch (Exception e) {
				log.error("Exception processing status of artifact on dependency track with id = " + ad.getUuid(), e);
				throw new RelizaException("Could not refetch Dependency Metrics for artifact id = " + ad.getUuid());
			}
		}
		return dti;
	}
	
	private DependencyTrackIntegration obtainDepdencyTrackProjectMetrics (URI dtrackBaseUri,
			String apiToken, ArtifactData ad) throws JsonMappingException, JsonProcessingException {
		String dtrackProject = ad.getMetrics().getDependencyTrackProject();
		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		List<VulnerabilityDto> vulnerabilityDetails = fetchDependencyTrackVulnerabilityDetails(
				dtrackBaseUri, apiToken, dtrackProject, ad.getUuid());
		List<ViolationDto> violationDetails = fetchDependencyTrackViolationDetails(
				dtrackBaseUri, apiToken, dtrackProject, ad.getUuid());
		dti.setVulnerabilityDetails(vulnerabilityDetails);
		dti.setViolationDetails(violationDetails);
		vulnAnalysisService.processReleaseMetricsDto(ad.getOrg(), ad.getOrg(), AnalysisScope.ORG, dti);
		if (null == dti.getDependencyTrackProject()) dti.setDependencyTrackProject(ad.getMetrics().getDependencyTrackProject());
		if (null == dti.getProjectName()) dti.setProjectName(ad.getMetrics().getProjectName());
		if (null == dti.getProjectVersion()) dti.setProjectVersion(ad.getMetrics().getProjectVersion());
		return dti;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackResolvedLicenseRaw(String licenseId) {}
	
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackComponentRaw(String purl, DtrackResolvedLicenseRaw resolvedLicense, String licenseExpression) {}
	
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackAliasRaw(String cveId, String ghsaId, String uuid) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackVulnRaw (String vulnId, VulnerabilitySeverity severity, List<DtrackComponentRaw> components, List<DtrackAliasRaw> aliases) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackViolationRaw (ViolationType type, DtrackComponentRaw component) {}
	
	private List<VulnerabilityDto> fetchDependencyTrackVulnerabilityDetails(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid) throws JsonMappingException, JsonProcessingException {
		String baseUri = dtrackBaseUri.toString() + "/api/v1/vulnerability/project/" + dtrackProject;
		List<Object> vulnDetailsRaw = executeDtrackPaginatedCall(baseUri, apiToken, "");
		List<VulnerabilityDto> vulnerabilityDetails = new LinkedList<>();
		final FindingSourceDto source = new FindingSourceDto(artifactUuid, null, null);
		vulnDetailsRaw.forEach(vd -> {
			DtrackVulnRaw dvr = Utils.OM.convertValue(vd, DtrackVulnRaw.class);
			Set<VulnerabilityAliasDto> aliases = new LinkedHashSet<>();
			if (null != dvr.aliases() && !dvr.aliases().isEmpty()) {
				dvr.aliases().forEach(a -> {
					if (a.cveId() != null && !a.cveId().trim().isEmpty()) {
						aliases.add(new VulnerabilityAliasDto(VulnerabilityAliasType.CVE, a.cveId()));
					}
					if (a.ghsaId() != null && !a.ghsaId().trim().isEmpty()) {
						aliases.add(new VulnerabilityAliasDto(VulnerabilityAliasType.GHSA, a.ghsaId()));
					}
				});
			}
			
			// Create severity source based on the main vulnerability ID
			SeveritySourceDto severitySource = Utils.createSeveritySourceDto(dvr.vulnId(), dvr.severity());
			
			final ZonedDateTime attributedAt = ZonedDateTime.now();
			dvr.components().forEach(c -> {
				VulnerabilityDto vdto = new VulnerabilityDto(c.purl(), dvr.vulnId(), dvr.severity(), aliases, Set.of(source), Set.of(severitySource), null, null, attributedAt);
				vulnerabilityDetails.add(vdto);
			});
		});
		return vulnerabilityDetails;
	}
	
	private List<ViolationDto> fetchDependencyTrackViolationDetails(URI dtrackBaseUri,
			String apiToken, String dtrackProject, UUID artifactUuid) throws JsonMappingException, JsonProcessingException {
		String baseUri = dtrackBaseUri.toString() + "/api/v1/violation/project/" + dtrackProject;
		List<Object> violationDetailsRaw = executeDtrackPaginatedCall(baseUri, apiToken, "");
		List<ViolationDto> violationDetails = new LinkedList<>();
		final FindingSourceDto source = new FindingSourceDto(artifactUuid, null, null);
		final Set<FindingSourceDto> sources = Set.of(source);
		violationDetailsRaw.forEach(vd -> {
			DtrackViolationRaw dvr = Utils.OM.convertValue(vd, DtrackViolationRaw.class);
			String licenseId;
			if (null != dvr.component().resolvedLicense()) {
				licenseId = dvr.component().resolvedLicense().licenseId();
			} else if (StringUtils.isNotEmpty(dvr.component().licenseExpression)) {
				licenseId = dvr.component().licenseExpression;
			} else {
				licenseId = "undetected";
			}
			ViolationDto vdto = new ViolationDto(dvr.component().purl(), dvr.type(),
					licenseId, null, sources, null, null, ZonedDateTime.now());
			violationDetails.add(vdto);
		});
		return violationDetails;
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
					log.debug("searchDependencyTrackComponent - purl search took {} ms, found {} results", System.currentTimeMillis() - beforeSearch, respList.size());
				} else {
					respList = executeDtrackNameAndGroupSearchParallel(baseUri, apiToken, query, versionParam);
					log.debug("searchDependencyTrackComponent - parallel name+group search took {} ms, found {} results", 
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
				log.debug("searchDependencyTrackComponent - processing took {} ms, produced {} components, total {} ms", 
						System.currentTimeMillis() - beforeProcessing, sbomComponents.size(), System.currentTimeMillis() - startTime);
			} catch (Exception e) {
				log.error("Exception searching components on dtrack for query = " + query + " and org = " + org, e);
				throw new RelizaException("Error searching SBOM components");
			}
		}
		return sbomComponents;
	}
	
	public List<ComponentPurlToDtrackProject> searchDependencyTrackComponentBatch (List<SbomComponentSearchQuery> queries, UUID org) throws RelizaException {
		// Use a concurrent map to combine results by purl, merging project lists
		Map<String, List<UUID>> combinedResults = new ConcurrentHashMap<>();
		
		// Execute searches in parallel with up to 4 threads
		ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, queries.size()));
		List<Future<List<ComponentPurlToDtrackProject>>> futures = new ArrayList<>();
		
		for (SbomComponentSearchQuery query : queries) {
			futures.add(executor.submit(() -> searchDependencyTrackComponent(query.name(), org, query.version())));
		}
		
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
		
		// Convert back to list, deduplicating projects per purl
		return combinedResults.entrySet().stream()
			.map(e -> new ComponentPurlToDtrackProject(e.getKey(), e.getValue().stream().distinct().toList()))
			.toList();
	}

	/**
	 * Execute a paginated DTrack API call, fetching all pages until no more results.
	 * @param baseUri Base URI without pagination parameters
	 * @param apiToken DTrack API token
	 * @param existingParams Any existing query parameters (should end with & if not empty, or be empty)
	 * @return List of all results from all pages
	 */
	private List<Object> executeDtrackPaginatedCall(String baseUri, String apiToken, String existingParams) 
			throws JsonMappingException, JsonProcessingException {
		return executeDtrackPaginatedCall(baseUri, apiToken, existingParams, CommonVariables.DTRACK_DEFAULT_PAGE_SIZE);
	}
	
	private List<Object> executeDtrackPaginatedCall(String baseUri, String apiToken, String existingParams, int pageSize) 
			throws JsonMappingException, JsonProcessingException {
		List<Object> allResults = new ArrayList<>();
		int pageNumber = 1;
		boolean hasMorePages = true;
		String separator = existingParams.isEmpty() ? "?" : (existingParams.endsWith("&") ? "" : "&");
		
		while (hasMorePages) {
			final int page1 = pageNumber;
			final int page2 = pageNumber + 1;
			
			var future1 = java.util.concurrent.CompletableFuture.supplyAsync(() -> 
				fetchDtrackPage(baseUri, apiToken, existingParams, separator, page1, pageSize));
			var future2 = java.util.concurrent.CompletableFuture.supplyAsync(() -> 
				fetchDtrackPage(baseUri, apiToken, existingParams, separator, page2, pageSize));
			
			List<Object> results1 = future1.join();
			List<Object> results2 = future2.join();
			
			if (results1 != null && !results1.isEmpty()) {
				allResults.addAll(results1);
			}
			if (results2 != null && !results2.isEmpty()) {
				allResults.addAll(results2);
			}
			
			// Determine if there are more pages based on the second page result
			if (results2 == null || results2.isEmpty() || results2.size() < pageSize) {
				hasMorePages = false;
			}
			
			pageNumber += 2;
		}
		return allResults;
	}
	
	private List<Object> fetchDtrackPage(String baseUri, String apiToken, String existingParams, 
			String separator, int pageNumber, int pageSize) {
		try {
			URI dtrackUri = URI.create(baseUri + existingParams + separator + "pageNumber=" + pageNumber + "&pageSize=" + pageSize);
			var resp = dtrackWebClient
				.get()
				.uri(dtrackUri)
				.header("X-API-Key", apiToken)
				.retrieve()
				.toEntity(String.class)
				.block();
			
			if (null == resp.getBody()) {
				log.warn("Null body from DTrack API for uri = {}", dtrackUri);
				return null;
			}
			@SuppressWarnings("unchecked")
			List<Object> pageResults = Utils.OM.readValue(resp.getBody(), List.class);
			return pageResults;
		} catch (Exception e) {
			log.error("Error fetching DTrack page {} for uri = {}", pageNumber, baseUri, e);
			return null;
		}
	}
	
	private List<Map<String, Object>> executeDtrackComponentSearch (String baseUri, String apiToken, String queryParams) 
			throws JsonMappingException, JsonProcessingException {
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
				log.error("Error in name search", e);
				return new LinkedList<Map<String, Object>>();
			}
		});
		var groupFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return executeDtrackComponentSearch(baseUri, apiToken, queryParams2);
			} catch (Exception e) {
				log.error("Error in group search", e);
				return new LinkedList<Map<String, Object>>();
			}
		});
		
		var respList1 = nameFuture.join();
		var respList2 = groupFuture.join();
		List<Map<String, Object>> combined = new LinkedList<>();
		combined.addAll(respList1);
		combined.addAll(respList2);
		return combined;
	}
	
	
	@Transactional
	public boolean requestMetricsRefreshOnDependencyTrack (ArtifactData ad) {
		return dtrackService.requestMetricsRefresh(ad);
	}
	
	/**
	 * Upload BOM to DTrack and update related artifacts if project ID changes.
	 * Handles both initial upload and resubmission scenarios.
	 * 
	 * @param orgUuid Organization UUID
	 * @param bom BOM to upload (JsonNode or raw bytes)
	 * @param projectName DTrack project name
	 * @param projectVersion DTrack project version
	 * @param oldProjectId Previous project ID (null for new artifacts)
	 * @param updateArtifact Artifact to update with result (null to skip)
	 * @return DependencyTrackUploadResult with project ID and token
	 */
	public DependencyTrackUploadResult uploadBomAndUpdateRelated(
			UUID orgUuid,
			UploadableBom bom,
			String projectName,
			String projectVersion,
			String oldProjectId,
			ArtifactData updateArtifact) {
		try {
			return dtrackService.uploadBomAndUpdateRelated(orgUuid, bom, projectName, 
				projectVersion, oldProjectId, updateArtifact);
		} catch (RelizaException e) {
			log.error("Error uploading BOM to DTrack", e);
			throw new RuntimeException("Error uploading BOM to DTrack: " + e.getMessage());
		}
	}
	
	private boolean resubmitArtifactToDependencyTrack(ArtifactData ad) {
		return dtrackService.resubmitArtifact(ad);
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
				
				// Paginated retrieval
				List<Object> vulnerabilityFindings = new ArrayList<>();
				int pageNumber = 1;
				int pageSize = CommonVariables.DTRACK_DEFAULT_PAGE_SIZE;
				boolean hasMorePages = true;
				
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
					
					@SuppressWarnings("unchecked")
					List<Object> pageFindings = Utils.OM.readValue(resp.getBody(), List.class);
					
					if (pageFindings.isEmpty()) {
						// No more records, stop pagination
						hasMorePages = false;
						log.debug("No more vulnerability findings found at page {} for org {}", pageNumber, orgUuid);
					} else {
						// Add findings from this page to the total list
						vulnerabilityFindings.addAll(pageFindings);
						log.debug("Retrieved {} vulnerability findings from page {} for org {}", pageFindings.size(), pageNumber, orgUuid);
						
						// If we got fewer records than page size, this is the last page
						if (pageFindings.size() < pageSize) {
							hasMorePages = false;
							log.debug("Last page reached (partial page) for org {}", orgUuid);
						}
					}
					
					pageNumber++;
				}
				
				// Extract project UUIDs from component.project field
				vulnerabilityFindings.forEach(finding -> {
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
				});
				
				log.info("Retrieved {} unique project UUIDs from {} unsynced vulnerabilities from Dependency Track for org {}", 
						projectUuids.size(), vulnerabilityFindings.size(), orgUuid);
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
				
				// Paginated retrieval
				List<Object> violationFindings = new ArrayList<>();
				int pageNumber = 1;
				int pageSize = CommonVariables.DTRACK_DEFAULT_PAGE_SIZE;
				boolean hasMorePages = true;
				
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
					
					@SuppressWarnings("unchecked")
					List<Object> pageFindings = Utils.OM.readValue(resp.getBody(), List.class);
					
					if (pageFindings.isEmpty()) {
						// No more records, stop pagination
						hasMorePages = false;
						log.debug("No more violation findings found at page {} for org {}", pageNumber, orgUuid);
					} else {
						// Add findings from this page to the total list
						violationFindings.addAll(pageFindings);
						log.debug("Retrieved {} violation findings from page {} for org {}", pageFindings.size(), pageNumber, orgUuid);
						
						// If we got fewer records than page size, this is the last page
						if (pageFindings.size() < pageSize) {
							hasMorePages = false;
							log.debug("Last page reached (partial page) for org {}", orgUuid);
						}
					}
					
					pageNumber++;
				}
				
				// Extract project UUIDs from project.uuid field
				violationFindings.forEach(violation -> {
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
				});
				
				log.info("Retrieved {} unique project UUIDs from {} unsynced violations from Dependency Track for org {}", 
						projectUuids.size(), violationFindings.size(), orgUuid);
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
	 * Result of DTrack project cleanup operation
	 */
	public record DtrackProjectCleanupResult(
		int projectsEvaluated,
		int projectsDeleted,
		int projectsFailed,
		List<String> deletedProjectIds,
		List<String> errors
	) {}
	
	/**
	 * Clean up DTrack projects that are only used by archived branches
	 * 
	 * @param orgUuid Organization UUID
	 * @return Cleanup result summary
	 */
	@Transactional
	public DtrackProjectCleanupResult cleanupArchivedDtrackProjects(UUID orgUuid) {
		log.info("[DTRACK-CLEANUP] Starting cleanup for org {}", orgUuid);
		
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(
			orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		
		if (oid.isEmpty()) {
			log.debug("[DTRACK-CLEANUP] No DTrack integration configured for org {}", orgUuid);
			return new DtrackProjectCleanupResult(0, 0, 0, List.of(), List.of());
		}
		
		IntegrationData dtrackIntegration = oid.get();
		String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
		
		List<String> orphanedProjectIds = artifactService.findOrphanedDtrackProjects(orgUuid);
		log.info("[DTRACK-CLEANUP] Found {} orphaned projects for org {}", orphanedProjectIds.size(), orgUuid);
		
		if (orphanedProjectIds.isEmpty()) {
			return new DtrackProjectCleanupResult(0, 0, 0, List.of(), List.of());
		}
		
		// Mark all artifacts using these projects as deleted BEFORE deleting from DTrack
		log.info("[DTRACK-CLEANUP] Marking artifacts for {} orphaned projects", orphanedProjectIds.size());
		markArtifactsWithDeletedProjects(orgUuid, orphanedProjectIds);
		
		List<String> deletedProjects = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		
		BatchDeleteResult batchResult = deleteDtrackProjectsBatch(dtrackIntegration, apiToken, orphanedProjectIds);
		
		if (batchResult.allSucceeded()) {
			deletedProjects.addAll(orphanedProjectIds);
			log.info("[DTRACK-CLEANUP] Batch delete succeeded for all {} projects", orphanedProjectIds.size());
		} else if (batchResult.partialSuccess()) {
			deletedProjects.addAll(batchResult.succeededProjects());
			log.info("[DTRACK-CLEANUP] Batch delete partial success: {} succeeded, {} failed", 
				batchResult.succeededProjects().size(), batchResult.failedProjects().size());
			
			// Only retry the failed projects
			if (!batchResult.failedProjects().isEmpty()) {
				log.info("[DTRACK-CLEANUP] Retrying {} failed projects individually", batchResult.failedProjects().size());
				for (String projectId : batchResult.failedProjects()) {
					try {
						boolean deleted = deleteDtrackProject(dtrackIntegration, apiToken, projectId);
						if (deleted) {
							deletedProjects.add(projectId);
						} else {
							log.warn("[DTRACK-CLEANUP] Failed to delete project {} on retry", projectId);
							errors.add("Failed to delete project " + projectId);
						}
					} catch (Exception ex) {
						log.error("[DTRACK-CLEANUP] Error deleting project {}: {}", projectId, ex.getMessage());
						errors.add("Error deleting project " + projectId + ": " + ex.getMessage());
					}
				}
			}
		} else {
			// Complete failure - fall back to individual deletion for all
			log.warn("[DTRACK-CLEANUP] Batch delete failed, falling back to individual deletions for {} projects", orphanedProjectIds.size());
			for (String projectId : orphanedProjectIds) {
				try {
					boolean deleted = deleteDtrackProject(dtrackIntegration, apiToken, projectId);
					if (deleted) {
						deletedProjects.add(projectId);
					} else {
						log.warn("[DTRACK-CLEANUP] Failed to delete project {}", projectId);
						errors.add("Failed to delete project " + projectId);
					}
				} catch (Exception ex) {
					log.error("[DTRACK-CLEANUP] Error deleting project {}: {}", projectId, ex.getMessage());
					errors.add("Error deleting project " + projectId + ": " + ex.getMessage());
				}
			}
		}
		
		DtrackProjectCleanupResult result = new DtrackProjectCleanupResult(
			orphanedProjectIds.size(),
			deletedProjects.size(),
			errors.size(),
			deletedProjects,
			errors
		);
		
		log.info("[DTRACK-CLEANUP] Completed for org {}: evaluated={}, deleted={}, failed={}", 
			orgUuid, result.projectsEvaluated(), result.projectsDeleted(), result.projectsFailed());
		
		return result;
	}
	
	/**
	 * Result of a batch delete operation
	 */
	private record BatchDeleteResult(
		boolean allSucceeded,
		List<String> succeededProjects,
		List<String> failedProjects
	) {
		boolean partialSuccess() {
			return !succeededProjects.isEmpty() && !failedProjects.isEmpty();
		}
	}
	
	/**
	 * Batch delete multiple projects from Dependency Track (v4.12+)
	 * DTrack API limits batch size to 1000 projects per request
	 */
	private BatchDeleteResult deleteDtrackProjectsBatch(IntegrationData dtrackIntegration, String apiToken, List<String> projectIds) {
		// DTrack API enforces max 1000 projects per batch
		final int MAX_BATCH_SIZE = 1000;
		
		if (projectIds.size() > MAX_BATCH_SIZE) {
			log.info("[DTRACK-CLEANUP] Processing {} projects in chunks of {}", projectIds.size(), MAX_BATCH_SIZE);
			
			// Split into chunks and process each
			List<String> allSucceeded = new ArrayList<>();
			List<String> allFailed = new ArrayList<>();
			
			for (int i = 0; i < projectIds.size(); i += MAX_BATCH_SIZE) {
				int end = Math.min(i + MAX_BATCH_SIZE, projectIds.size());
				List<String> chunk = projectIds.subList(i, end);
				
				BatchDeleteResult chunkResult = deleteDtrackProjectsBatchInternal(dtrackIntegration, apiToken, chunk);
				allSucceeded.addAll(chunkResult.succeededProjects());
				allFailed.addAll(chunkResult.failedProjects());
			}
			
			return new BatchDeleteResult(allFailed.isEmpty(), allSucceeded, allFailed);
		}
		
		return deleteDtrackProjectsBatchInternal(dtrackIntegration, apiToken, projectIds);
	}
	
	/**
	 * Internal method to perform the actual batch delete API call
	 * Returns which projects succeeded and which failed
	 */
	private BatchDeleteResult deleteDtrackProjectsBatchInternal(IntegrationData dtrackIntegration, String apiToken, List<String> projectIds) {
		try {
			URI batchDeleteUri = URI.create(dtrackIntegration.getUri().toString() + "/api/v1/project/batchDelete");
			
			// DTrack batch delete expects POST method with Set<UUID>, not DELETE with List
			Set<String> projectIdsSet = new HashSet<>(projectIds);
			
			var response = dtrackWebClient
				.post()
				.uri(batchDeleteUri)
				.header("X-API-Key", apiToken)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(projectIdsSet)
				.retrieve()
				.toEntity(String.class)
				.block();
			
			if (response != null && response.getStatusCode().is2xxSuccessful()) {
				// All projects succeeded
				return new BatchDeleteResult(true, new ArrayList<>(projectIds), List.of());
			}
			
			return new BatchDeleteResult(false, List.of(), new ArrayList<>(projectIds));
			
		} catch (WebClientResponseException wcre) {
			String responseBody = wcre.getResponseBodyAsString();
			
			if (wcre.getStatusCode() == HttpStatus.NOT_FOUND) {
				log.warn("[DTRACK-CLEANUP] Batch delete endpoint not found (404) - DTrack version < v4.12");
				throw new UnsupportedOperationException("Batch delete endpoint not available (DTrack < v4.12)");
			}
			
			// Parse error response to identify which projects failed
			if (wcre.getStatusCode() == HttpStatus.BAD_REQUEST && responseBody != null && responseBody.contains("errors")) {
				try {
					JsonNode errorJson = Utils.OM.readTree(responseBody);
					JsonNode errorsNode = errorJson.get("errors");
					
					if (errorsNode != null && errorsNode.isObject()) {
						List<String> actualFailures = new ArrayList<>();
						
						// Categorize errors: "Project not found" = already deleted (success), others = actual failures
						errorsNode.properties().iterator().forEachRemaining(entry -> {
							String projectId = entry.getKey();
							String errorMessage = entry.getValue().asText();
							
							if (!"Project not found".equalsIgnoreCase(errorMessage)) {
								actualFailures.add(projectId);
								log.warn("[DTRACK-CLEANUP] Project {} failed with error: {}", projectId, errorMessage);
							}
						});
						
						// Calculate succeeded projects = all - actual failures (already-deleted count as success)
						List<String> succeededProjectIds = new ArrayList<>(projectIds);
						succeededProjectIds.removeAll(actualFailures);
						
						// Only return actual failures for retry, not already-deleted projects
						return new BatchDeleteResult(actualFailures.isEmpty(), succeededProjectIds, actualFailures);
					}
				} catch (Exception parseEx) {
					log.warn("[DTRACK-CLEANUP] Failed to parse batch delete error response: {}", parseEx.getMessage());
				}
			}
			
			log.error("[DTRACK-CLEANUP] Error batch deleting projects: {}", wcre.getMessage());
			return new BatchDeleteResult(false, List.of(), new ArrayList<>(projectIds));
		} catch (Exception e) {
			log.error("[DTRACK-CLEANUP] Unexpected error batch deleting projects: {}", e.getMessage());
			return new BatchDeleteResult(false, List.of(), new ArrayList<>(projectIds));
		}
	}
	
	/**
	 * Mark all artifacts using the specified DTrack projects as deleted
	 * This prevents deduplication from reusing deleted project IDs
	 */
	private void markArtifactsWithDeletedProjects(UUID orgUuid, List<String> projectIds) {
		if (projectIds == null || projectIds.isEmpty()) {
			return;
		}
		
		int totalMarked = 0;
		for (String projectId : projectIds) {
			// Convert string project ID to UUID
			UUID projectUuid = UUID.fromString(projectId);
			List<Artifact> affectedArtifacts = artifactService.listArtifactsByDtrackProjects(List.of(projectUuid));
			log.debug("[DTRACK-CLEANUP] Found {} artifacts using project {}", affectedArtifacts.size(), projectId);
			
			for (Artifact artifact : affectedArtifacts) {
				ArtifactData ad = ArtifactData.dataFromRecord(artifact);
				if (ad.getMetrics() != null && ad.getOrg().equals(orgUuid)) {
					ad.getMetrics().setDtrackProjectDeleted(true);
					Map<String, Object> recordData = Utils.dataToRecord(ad);
					sharedArtifactService.saveArtifact(artifact, recordData, WhoUpdated.getAutoWhoUpdated());
					totalMarked++;
				}
			}
		}
		
		log.info("[DTRACK-CLEANUP] Marked {} artifacts as having deleted DTrack projects", totalMarked);
	}
	
	/**
	 * Delete a project from Dependency Track
	 */
	private boolean deleteDtrackProject(IntegrationData dtrackIntegration, String apiToken, String projectId) {
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
}
