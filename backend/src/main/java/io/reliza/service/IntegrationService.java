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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.TextPayload;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.IntegrationWebDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
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
	
				
				DependencyTrackProjectInput dtpi = new DependencyTrackProjectInput(projectName, projectVersion, null, "APPLICATION", List.of(), List.of(), true, false);
				String projectCreateUriStr = oid.get().getUri().toString() + "/api/v1/project";
				URI projectCreateUri = URI.create(projectCreateUriStr);
				
				var createDtrackProjectResp = dtrackWebClient
				.put()
				.uri(projectCreateUri)
				.header("X-API-Key", apiToken)
				.bodyValue(dtpi).retrieve()
				.toEntity(String.class)
				.block();
				
				@SuppressWarnings("unchecked")
				Map<String, Object> createProjResp = Utils.OM.readValue(createDtrackProjectResp.getBody(), Map.class);
				String projectIdStr = (String) createProjResp.get("uuid");
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
			.bodyValue(payload).retrieve()
			.toEntity(String.class)
			.block();
		@SuppressWarnings("unchecked")
		Map<String, Object> addBomResp = Utils.OM.readValue(tokenResp.getBody(), Map.class);
		String token = (String) addBomResp.get("token");
		URI fullDtrackUri = URI.create(dtrackIntegration.getFrontendUri()
				.toString() + "/projects/" + projectId.toString());
		return new DependencyTrackUploadResult(projectId.toString(), token, projectName, projectVersion, fullDtrackUri);
	}
	
	protected DependencyTrackIntegration resolveDependencyTrackProcessingStatus (ArtifactData ad) {
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
					dti.setLastScanned(ZonedDateTime.now());
				}
			} catch (Exception e) {
				log.error("Exception processing status of artifact on dependency track with id = " + ad.getUuid(), e);
			}
		}
		return dti;
	}
	
	private DependencyTrackIntegration obtainDepdencyTrackProjectMetrics (URI dtrackBaseUri,
			String apiToken, ArtifactData ad) throws JsonMappingException, JsonProcessingException {
		String dtrackProject = ad.getMetrics().getDependencyTrackProject();
		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		List<VulnerabilityDto> vulnerabilityDetails = fetchDependencyTrackVulnerabilityDetails(
				dtrackBaseUri, apiToken, dtrackProject);
		List<ViolationDto> violationDetails = fetchDependencyTrackViolationDetails(
				dtrackBaseUri, apiToken, dtrackProject);
		dti.setVulnerabilityDetails(vulnerabilityDetails);
		dti.setViolationDetails(violationDetails);
		dti.computeMetricsFromFacts();
		if (null == dti.getDependencyTrackProject()) dti.setDependencyTrackProject(ad.getMetrics().getDependencyTrackProject());
		if (null == dti.getProjectName()) dti.setProjectName(ad.getMetrics().getProjectName());
		if (null == dti.getProjectVersion()) dti.setProjectVersion(ad.getMetrics().getProjectVersion());
		return dti;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackResolvedLicenseRaw(String licenseId) {}
	
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackComponentRaw(String purl, DtrackResolvedLicenseRaw resolvedLicense) {}
	
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackVulnRaw (String vulnId, VulnerabilitySeverity severity, List<DtrackComponentRaw> components) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DtrackViolationRaw (ViolationType type, DtrackComponentRaw component) {}
	
	private List<VulnerabilityDto> fetchDependencyTrackVulnerabilityDetails(URI dtrackBaseUri,
			String apiToken, String dtrackProject) throws JsonMappingException, JsonProcessingException {
		URI dtrackUri = URI.create(dtrackBaseUri.toString() + "/api/v1/vulnerability/project/" + dtrackProject + "?pageNumber=1&pageSize=10000");
		var resp = dtrackWebClient
				.get()
				.uri(dtrackUri)
				.header("X-API-Key", apiToken)
				.retrieve()
				.toEntity(String.class)
				.block();
		@SuppressWarnings("unchecked")
		List<Object> vulnDetailsRaw = Utils.OM.readValue(resp.getBody(), List.class);
		List<VulnerabilityDto> vulnerabilityDetails = new LinkedList<>();
		vulnDetailsRaw.forEach(vd -> {
			DtrackVulnRaw dvr = Utils.OM.convertValue(vd, DtrackVulnRaw.class);
			dvr.components().forEach(c -> {
				VulnerabilityDto vdto = new VulnerabilityDto(c.purl(), dvr.vulnId(), dvr.severity());
				vulnerabilityDetails.add(vdto);
			});
		});
		return vulnerabilityDetails;
	}
	
	private List<ViolationDto> fetchDependencyTrackViolationDetails(URI dtrackBaseUri,
			String apiToken, String dtrackProject) throws JsonMappingException, JsonProcessingException {
		URI dtrackUri = URI.create(dtrackBaseUri.toString() + "/api/v1/violation/project/" + dtrackProject + "?pageNumber=1&pageSize=10000");
		var resp = dtrackWebClient
				.get()
				.uri(dtrackUri)
				.header("X-API-Key", apiToken)
				.retrieve()
				.toEntity(String.class)
				.block();
		@SuppressWarnings("unchecked")
		List<Object> violationDetailsRaw = Utils.OM.readValue(resp.getBody(), List.class);
		List<ViolationDto> violationDetails = new LinkedList<>();
		violationDetailsRaw.forEach(vd -> {
			DtrackViolationRaw dvr = Utils.OM.convertValue(vd, DtrackViolationRaw.class);
			String licenseId = (null != dvr.component().resolvedLicense()) ? dvr.component().resolvedLicense().licenseId() : "undetected";
			ViolationDto vdto = new ViolationDto(dvr.component().purl(), dvr.type(),
					licenseId, null);
			violationDetails.add(vdto);
		});
		return violationDetails;
	}
	
	public record ComponentPurlToDtrackProject (String purl, List<UUID> projects) {}
	
	public List<ComponentPurlToDtrackProject> searchDependencyTrackComponent (String query, UUID org) {
		List<ComponentPurlToDtrackProject> sbomComponents = new LinkedList<>();
		Optional<IntegrationData> oid = getIntegrationDataByOrgTypeIdentifier(org, IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		if (oid.isPresent()) {
			IntegrationData dtrackIntegration = oid.get();
			try {
				String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
				URI componentSearchUri;
				if (query.startsWith("pkg:")) {
					componentSearchUri = URI.create(dtrackIntegration.getUri().toString() + "/api/v1/component/identity?purl=" + query + "&pageSize=10000&pageNumber=1");
				} else {
					componentSearchUri = URI.create(dtrackIntegration.getUri().toString() + "/api/v1/component/identity?name=" + query + "&pageSize=10000&pageNumber=1");	
				}
				
				var resp = dtrackWebClient
						.get()
						.uri(componentSearchUri)
						.header("X-API-Key", apiToken)
						.retrieve()
						.toEntity(String.class)
						.block();
				if (null != resp.getBody()) {
					List<Map<String, Object>> respList = Utils.OM.readValue(resp.getBody(), List.class);
					sbomComponents = respList
						.stream()
						.collect(Collectors.groupingBy(x -> URLDecoder.decode((String) x.get("purl"), StandardCharsets.UTF_8), 
								Collectors.mapping(x -> UUID.fromString(((Map<String, String>) x.get("project")).get("uuid")), 
										Collectors.toList())))
						.entrySet().stream()
						.map(e -> new ComponentPurlToDtrackProject(e.getKey(), e.getValue())).toList();				
				} else {
					log.warn("Null body searching components on dtrack for query = " + query + " and org = " + org);
				}
			} catch (Exception e) {
				log.error("Exception searching components on dtrack for query = " + query + " and org = " + org, e);
			}
		}
		return sbomComponents;
	}
	
	
	@Transactional
	public boolean requestMetricsRefreshOnDependencyTrack (ArtifactData ad) {
		boolean isRequested = false;
		Optional<IntegrationData> oid = Optional.empty();
		String depTrackProject = (null != ad.getMetrics()) ? ad.getMetrics().getDependencyTrackProject() : null;
		if (StringUtils.isNotEmpty(depTrackProject)) {
			oid = getIntegrationDataByOrgTypeIdentifier(ad.getOrg(), IntegrationType.DEPENDENCYTRACK,
				CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		}
		if (oid.isPresent()) {
			IntegrationData dtrackIntegration = oid.get();
			try {
				String apiToken = encryptionService.decrypt(dtrackIntegration.getSecret());
				URI refreshUri = URI.create(dtrackIntegration.getUri().toString() + "/api/v1/metrics/project/" + depTrackProject + "/refresh");
				var processingResp = dtrackWebClient
						.get()
						.uri(refreshUri)
						.header("X-API-Key", apiToken)
						.retrieve()
						.toEntity(String.class)
						.block();
				if (processingResp.getStatusCode() == HttpStatus.OK) isRequested = true;
				else log.warn(processingResp.toString());
			} catch (WebClientResponseException wcre) {
				if (wcre.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404)) && null != ad.getMetrics()) {
					// resubmit
					try {
						var downloadable = sharedArtifactService.downloadArtifact(ad);
						String projectVersion = StringUtils.isNotEmpty(ad.getMetrics().getProjectVersion()) ? ad.getMetrics().getProjectVersion() : UUID.randomUUID().toString();
						String projectName = StringUtils.isNotEmpty(ad.getMetrics().getProjectName()) ? ad.getMetrics().getProjectName() : UUID.randomUUID().toString();  
						UploadableBom ub = new UploadableBom(null, downloadable.block().getBody(), true);
						var dtur = sendBomToDependencyTrack(ad.getOrg(), ub, projectName, projectVersion);
						sharedArtifactService.updateArtifactFromDtur(ad, dtur);
						isRequested = true;
					} catch (Exception e) {
						log.error("Error on downloading artifact for refetching", e);
					}
				} else {
					log.error("Web exception processing status of artifact on dependency track with id = " + ad.getUuid(), wcre);
				}
			} catch (Exception e) {
				log.error("Exception processing status of artifact on dependency track with id = " + ad.getUuid(), e);
			}
		}
		return isRequested;
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
				
				// Paginated retrieval with 200 records per page
				List<Object> vulnerabilityFindings = new ArrayList<>();
				int pageNumber = 1;
				int pageSize = 200;
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
				
				// Paginated retrieval with 200 records per page
				List<Object> violationFindings = new ArrayList<>();
				int pageNumber = 1;
				int pageSize = 200;
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
}
