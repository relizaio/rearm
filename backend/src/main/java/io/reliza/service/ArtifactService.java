/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.SerializationFormat;
import io.reliza.model.ArtifactData.SpecVersion;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.SyncDtrackStatusResponseDto;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
import io.reliza.model.BranchData;
import io.reliza.model.DeliverableData;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VariantData;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.service.IntegrationService.DependencyTrackUploadResult;
import io.reliza.service.IntegrationService.UploadableBom;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class ArtifactService {
	
	@Autowired
    private SharedArtifactService sharedArtifactService;
	
	@Autowired
    private RebomService rebomService;
	
	@Autowired
    private IntegrationService integrationService;
	
	@Autowired
	private BomLifecycleService bomLifecycleService;
	
	@Lazy
	@Autowired
	private DTrackService dtrackService;
	
	@Autowired
    private SystemInfoService systemInfoService;
	
	@Lazy
	@Autowired
	private VulnAnalysisService vulnAnalysisService;
	
	@Autowired
	private BranchService branchService;
	
	@Lazy
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;
	
	@Autowired
	private VariantService variantService;
	
	@Lazy
	@Autowired
	private GetDeliverableService getDeliverableService;
	
	private final ArtifactRepository repository;

    private final String registryHost;
    private final String url;
    private final WebClient webClient;
    private final String ociRepository;


    public ArtifactService(
		ArtifactRepository repository,
		@Value("${relizaprops.ociArtifacts.registry}") String registryHost,
		@Value("${relizaprops.ociArtifacts.namespace}") String registryNamespace,
        @Value("${relizaprops.ociArtifacts.serviceUrl}") String url
	) {
		this.repository = repository;
		this.registryHost = registryHost;
        this.url= url;
        this.ociRepository = registryNamespace + "/downloadable-artifacts";
		// Configure WebClient with increased buffer size for large OCI artifacts
		ExchangeStrategies strategies = ExchangeStrategies.builder()
			.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB buffer
			.build();
		
		this.webClient = WebClient.builder()
			.baseUrl(this.url)
			.exchangeStrategies(strategies)
			.build();
	}
	
	public Optional<ArtifactData> getArtifactData (UUID uuid) {
		Optional<ArtifactData> aData = Optional.empty();
		Optional<Artifact> a = sharedArtifactService.getArtifact(uuid);
		if (a.isPresent()) {
			aData = Optional
							.of(
								ArtifactData
									.dataFromRecord(a
										.get()
								));
		}
		return aData;
	}

    /**
     * Returns the artifact data for a specific version. If version is null or matches the current version, returns the latest.
     * Otherwise, searches previousVersions for a matching version.
     */
    public Optional<ArtifactData> getArtifactDataByVersion(UUID uuid, Integer version) {
        Optional<ArtifactData> latestOpt = getArtifactData(uuid);
        if (version == null || latestOpt.isEmpty()) return latestOpt;
        ArtifactData latest = latestOpt.get();
        if (latest.getVersion() != null && version.toString().equals(latest.getVersion())) {
            return latestOpt;
        }
        if (latest.getPreviousVersions() != null) {
            for (ArtifactData.ArtifactVersionSnapshot snap : latest.getPreviousVersions()) {
                if (snap.version() != null && version.toString().equals(snap.version())) {
                    return Optional.of(ArtifactData.ArtifactVersionSnapshot.fromSnapshot(snap));
                }
            }
        }
        return Optional.empty();
    }

	public List<Artifact> getArtifacts (Iterable<UUID> uuids) {
		return (List<Artifact>) repository.findAllById(uuids);
	}
	
	public List<ArtifactData> getArtifactDataList (Iterable<UUID> uuids) {
		List<Artifact> artifacts = getArtifacts(uuids);
		return artifacts.stream().map(ArtifactData::dataFromRecord).collect(Collectors.toList());
	}

	public List<Artifact> listArtifactsByOrg (UUID org) {
		return repository.listArtifactsByOrg(org.toString());
	}
	
	public Optional<Artifact> findArtifactByStoredDigest (UUID orgUuid, String digest) {
		Optional<Artifact> a = Optional.empty();
		List<Artifact> artifacts = repository.findArtifactsByStoredDigest(orgUuid.toString(), digest);
		if( null != artifacts && artifacts.size() > 0){
			a = Optional.of(artifacts.get(0));
		}
		return a;
	}
	
	public List<Artifact> listArtifactsByDtrackProjects (Collection<UUID> dtrackProjects) {
		Set<UUID> uniqueDtrackProjects = new HashSet<>(dtrackProjects);
		List<String> dtrackProjectsForQuery = uniqueDtrackProjects.stream().map(x -> x.toString()).toList();
		return repository.findArtifactsByDtrackProjects(dtrackProjectsForQuery);
	}
	
	public List<String> findOrphanedDtrackProjects (UUID orgUuid) {
		String orgUuidStr = orgUuid.toString();
		
		// Get all distinct dtrack projects for the org
		List<String> allProjects = repository.listDistinctDtrackProjectsByOrg(orgUuidStr)
			.stream()
			.filter(StringUtils::isNotEmpty)
			.distinct()
			.toList();
		
		log.info("[DTRACK-CLEANUP] Found {} total dtrack projects for org {}", allProjects.size(), orgUuid);
		
		// Build 4 HashSets of active artifact UUIDs from each path
		Set<String> activeReleaseArtifacts = new HashSet<>(
			repository.listActiveReleaseArtifactUuids(orgUuidStr).stream()
				.filter(StringUtils::isNotEmpty).toList());
		log.info("[DTRACK-CLEANUP] Found {} active release artifacts", activeReleaseArtifacts.size());
		
		Set<String> activeSceArtifactsViaSourceCodeEntry = new HashSet<>(
			repository.listActiveSceArtifactUuidsViaSourceCodeEntry(orgUuidStr).stream()
				.filter(StringUtils::isNotEmpty).toList());
		log.info("[DTRACK-CLEANUP] Found {} active SCE artifacts via sourceCodeEntry", activeSceArtifactsViaSourceCodeEntry.size());
		
		Set<String> activeSceArtifactsViaCommits = new HashSet<>(
			repository.listActiveSceArtifactUuidsViaCommits(orgUuidStr).stream()
				.filter(StringUtils::isNotEmpty).toList());
		log.info("[DTRACK-CLEANUP] Found {} active SCE artifacts via commits", activeSceArtifactsViaCommits.size());
		
		Set<String> activeDeliverableArtifacts = new HashSet<>(
			repository.listActiveDeliverableArtifactUuids(orgUuidStr).stream()
				.filter(StringUtils::isNotEmpty).toList());
		log.info("[DTRACK-CLEANUP] Found {} active deliverable artifacts", activeDeliverableArtifacts.size());
		
		// Filter out projects that have any artifact in any of the active sets
		List<String> orphanedProjects = new ArrayList<>();
		for (String project : allProjects) {
			// Get all artifacts for this dtrack project
			List<Artifact> projectArtifacts = repository.findArtifactsByDtrackProjectAndOrg(orgUuidStr, project);
			
			boolean isActive = false;
			for (Artifact artifact : projectArtifacts) {
				String artifactUuid = artifact.getUuid().toString();
				if (activeReleaseArtifacts.contains(artifactUuid) ||
					activeSceArtifactsViaSourceCodeEntry.contains(artifactUuid) ||
					activeSceArtifactsViaCommits.contains(artifactUuid) ||
					activeDeliverableArtifacts.contains(artifactUuid)) {
					isActive = true;
					break;
				}
			}
			
			if (!isActive) {
				orphanedProjects.add(project);
			}
		}
		
		log.info("[DTRACK-CLEANUP] Found {} orphaned dtrack projects for org {}", orphanedProjects.size(), orgUuid);
		return orphanedProjects;
	}
	
	public List<String> findDeletedDtrackProjects(UUID orgUuid) {
		return repository.listDistinctDeletedDtrackProjectsByOrg(orgUuid.toString())
			.stream()
			.filter(StringUtils::isNotEmpty)
			.distinct()
			.toList();
	}
	
	//Creates or Updates existing artifact
	@Transactional
	public Artifact createArtifact(ArtifactDto artifactDto, WhoUpdated wu) throws RelizaException{
		log.debug("RGDEBUG: create Artifact called: {}", artifactDto);
		Artifact a;
		Optional<Artifact> oa = Optional.empty();
		if(null != artifactDto.getUuid()){
			oa = sharedArtifactService.getArtifact(artifactDto.getUuid());
		}
		if(oa.isEmpty()){
			a = new Artifact();
			if (null != artifactDto.getUuid()) a.setUuid(artifactDto.getUuid());
		}else {
			a = oa.get();
		}
		if(null == artifactDto.getType())
			throw new RelizaException("Artifact must have type!");
		ArtifactData ad = ArtifactData.artifactDataFactory(artifactDto, a.getUuid());
		Map<String,Object> recordData = Utils.dataToRecord(ad);
		a = sharedArtifactService.saveArtifact(a, recordData, wu);
		return a;
	}

	/**
	 * This method checks if a new set of artifacts have added on complete tag
	 * @param currentArtifacts
	 * @param newArtifacts
	 * @return If a single artifact is not valid return false
	 */
	public Boolean checkArtifactsToUpdate(List<UUID> currentArtUuids, List<UUID> newArtUuids) {
		Boolean isValid = true;
		Set<UUID> currentArtSet = new HashSet<>(currentArtUuids);
		List<ArtifactData> artifacts = newArtUuids.stream()
													.filter(artUuid -> !currentArtSet.contains(artUuid))
													.map(artUuid -> getArtifactData(artUuid).get())
													.collect(Collectors.toList());
		
		Iterator<ArtifactData> artifactIter = artifacts.iterator();
		while (isValid && artifactIter.hasNext()) {
			ArtifactData artifact = artifactIter.next();
			isValid = artifact.getTags().stream().anyMatch(tr -> tr.key().equals(CommonVariables.ADDED_ON_COMPLETE) && tr.value().equalsIgnoreCase("true"));
		}
		return isValid;
	}

	public Boolean archiveArtifact(UUID artifactId, WhoUpdated wu) {
		Boolean archived = false;
		Optional<Artifact> artifact = sharedArtifactService.getArtifact(artifactId);
		if (artifact.isPresent()) {
			ArtifactData artifactData = ArtifactData.dataFromRecord(artifact.get());
			artifactData.setStatus(StatusEnum.ARCHIVED);
			Map<String,Object> recordData = Utils.dataToRecord(artifactData);
			sharedArtifactService.saveArtifact(artifact.get(), recordData, wu);
			archived = true;
		}
		return archived;
	}

	public void saveAll(List<Artifact> artifacts){
		repository.saveAll(artifacts);
	}

	private boolean isRebomStoreable (ArtifactDto artifactDto) {
		return null!= artifactDto.getBomFormat() 
				&& null != artifactDto.getType() 
				&& (
					artifactDto.getType().equals(ArtifactType.BOM)
				);
	}
	
	public boolean isRebomStoreable (ArtifactData artifactData) {
		return  null != artifactData.getBomFormat()
				&& null != artifactData.getType() && (
					artifactData.getType().equals(ArtifactType.BOM)
			);
	}
	
	// TODO try to enforce strict type here
	// TODO double check how we set and meaning of hash in rebom options
	@Transactional
	public List<UUID> uploadListOfArtifacts(OrganizationData od, List<Map<String, Object>> arts, RebomOptions rebomOptions, WhoUpdated wu) throws RelizaException {
		List<UUID> artIds = new LinkedList<>();
		if (null != arts && !arts.isEmpty()) {
			for (Map<String, Object> artMap : arts) {
				var artId = uploadSingleArtifactWithFileFromList(artMap, od, rebomOptions, wu);
				artIds.add(artId);
			}
		}
		return artIds;
	}

	// TODO try to enforce strict type here
	private UUID uploadSingleArtifactWithFileFromList(Map<String, Object> artMap, OrganizationData od, RebomOptions rebomOptions, WhoUpdated wu) throws RelizaException {
		List<UUID> artifactsOfThisArtifact = new LinkedList<>();
		if (artMap.containsKey("artifacts")) {
			artifactsOfThisArtifact = uploadListOfArtifacts(od, (List<Map<String, Object>>) artMap.get("artifacts"), rebomOptions, wu);
		}
		artMap.remove("artifacts");
		MultipartFile file = (MultipartFile) artMap.get("file");
		artMap.remove("file");
		if(!artMap.containsKey("storedIn") || StringUtils.isEmpty((String)artMap.get("storedIn"))){
			artMap.put("storedIn", "REARM");
		}
		ArtifactDto artDto = Utils.OM.convertValue(artMap, ArtifactDto.class);
		artDto.setArtifacts(artifactsOfThisArtifact);
		artDto.setOrg(od.getOrg());
		RebomOptions updRebomOptions = new RebomOptions(rebomOptions.name(), rebomOptions.group(), rebomOptions.version(), rebomOptions.belongsTo(),
				rebomOptions.hash(), artDto.getStripBom(), rebomOptions.purl());
		return uploadArtifact(artDto, file.getResource(), updRebomOptions, wu);
	}

	@Transactional
	public UUID uploadArtifact(ArtifactDto artifactDto, Resource file, RebomOptions rebomOptions, WhoUpdated wu) throws RelizaException {
		UUID orgUuid = artifactDto.getOrg();
		if (null == orgUuid) throw new RelizaException("Missing artifact org.");
		OASResponseDto artifactUploadResponse = null;
		ArtifactData existingAd = null;
		if(null != artifactDto.getUuid()){
			existingAd = getArtifactData(artifactDto.getUuid()).get();
		}
		if (null == artifactDto.getUuid()) artifactDto.setUuid(UUID.randomUUID());

		ArtifactFormatResolution formatResolution = resolveArtifactFormat(file, artifactDto.getType());
		if (formatResolution != null) {
			artifactDto.setSerializationFormat(formatResolution.serializationFormat());
			artifactDto.setSpecVersion(formatResolution.specVersion());
		}
		
		validateBomSpecVersion(artifactDto);

		if(artifactDto.getStoredIn().equals(StoredIn.REARM)){
			if(isRebomStoreable(artifactDto)){
				artifactUploadResponse = storeArtifactOnRebom(artifactDto, file, existingAd, rebomOptions);
			} else {
				artifactUploadResponse = storeArtifactOnRearmDirect(artifactDto, file, existingAd);
			}
		}
		
		// artifactDto.setDisplayIdentifier(this.registryHost + "/" + this.ociRepository);
		if(null!=artifactUploadResponse){
			Set<DigestRecord> digestRecords = null != artifactDto.getDigestRecords() ? artifactDto.getDigestRecords() : new HashSet<>();
			String ociDigest = artifactUploadResponse.getOciResponse().getDigest();
			if(StringUtils.isNotEmpty(ociDigest)){
				var dr = Utils.convertDigestStringToRecord(ociDigest, DigestScope.OCI_STORAGE);
				digestRecords.add(dr.get());
			}
			if(StringUtils.isNotEmpty(artifactUploadResponse.getFileSHA256Digest())){
				digestRecords.add(new DigestRecord(TeaChecksumType.SHA_256, artifactUploadResponse.getFileSHA256Digest(), DigestScope.ORIGINAL_FILE));
			}
			artifactDto.setDigestRecords(digestRecords);

			// Preserve existing tags and add system tags
			List<TagRecord> allTags = new ArrayList<>();
			if (artifactDto.getTags() != null) {
				allTags.addAll(artifactDto.getTags());
			}
			allTags.add(new TagRecord(CommonVariables.SIZE_FEILD, 
					artifactUploadResponse.getOriginalSize() != null 
						? artifactUploadResponse.getOriginalSize().toString() 
						: artifactUploadResponse.getOciResponse().getSize(), 
					Removable.NO));
			allTags.add(new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, 
					artifactUploadResponse.getOriginalMediaType() != null 
						? artifactUploadResponse.getOriginalMediaType() 
						: artifactUploadResponse.getOciResponse().getMediaType(), 
					Removable.NO));
			allTags.add(new TagRecord(CommonVariables.DOWNLOADABLE_ARTIFACT, "true", Removable.NO));
			allTags.add(new TagRecord(CommonVariables.TAG_FIELD, artifactDto.getUuid().toString(), Removable.NO));
			allTags.add(new TagRecord(CommonVariables.FILE_NAME_FIELD, file.getFilename(), Removable.NO));
			artifactDto.setTags(allTags);
		}
		
		Artifact art = createArtifact(artifactDto, wu);
		
        return art.getUuid();
    }
	
	@Transactional
	private OASResponseDto storeArtifactOnRearmDirect (ArtifactDto artifactDto, Resource file, ArtifactData existingAd) throws RelizaException {
		String inputVersion = artifactDto.getVersion();
		String version = StringUtils.isNotEmpty(inputVersion) ? inputVersion : "1";
		if(null != existingAd && StringUtils.isNotEmpty(existingAd.getVersion())){
			if(StringUtils.isNotEmpty(inputVersion)){
				if(Integer.valueOf(inputVersion) <= Integer.valueOf(existingAd.getVersion())){
					throw new RelizaException("Uploaded artifact should have an incremented version");
				}
			}else{
				version = String.valueOf(Integer.valueOf(existingAd.getVersion()) + 1);
			}
		}
		artifactDto.setVersion(version);
		DigestRecord sha256Dr = null;
		if (null != artifactDto.getDigestRecords() && !artifactDto.getDigestRecords().isEmpty()) {
			sha256Dr = artifactDto.getDigestRecords().stream().filter((DigestRecord dr) -> dr.algo().equals(TeaChecksumType.SHA_256)).findFirst().orElse(null);
		}
		String sha256Digest = (null != sha256Dr) ? sha256Dr.digest() : null;
		OASResponseDto artifactUploadResponse = uploadFileToConfiguredOci(file, artifactDto.getUuid().toString(), sha256Digest);
		if (SpecVersion.SARIF_2_1.equals(artifactDto.getSpecVersion()) || SpecVersion.SARIF_2_0.equals(artifactDto.getSpecVersion())) {
			try {
				var sarifJson = Utils.readJsonFromResource(file);
				String sarifString = Utils.OM.writeValueAsString(sarifJson);
				ReleaseMetricsDto rmd = rebomService.parseSarifOnRebom(sarifString, artifactDto);
				artifactDto.setRmd(rmd);
			} catch (Exception e) {
				log.error("Error parsing SARIF file", e);
				throw new RelizaException("Error parsing SARIF file");
			}
		} else if ((artifactDto.getType().equals(ArtifactType.VDR) || artifactDto.getType().equals(ArtifactType.BOV)) &&
			artifactDto.getBomFormat().equals(BomFormat.CYCLONEDX) && SerializationFormat.JSON.equals(artifactDto.getSerializationFormat())) {
			try {
				var vdrJson = Utils.readJsonFromResource(file);
				String vdrString = Utils.OM.writeValueAsString(vdrJson);
				ReleaseMetricsDto rmd = rebomService.parseCycloneDxContent(vdrString, artifactDto);
				artifactDto.setRmd(rmd);
			} catch (Exception e) {
				log.error("Error parsing CycloneDX VDR/BOV file", e);
				throw new RelizaException("Error parsing CycloneDX VDR/BOV file");
			}
		}
		return artifactUploadResponse;
	}
	
	/**
	 * 
	 * @param artifactDto - Mutates artifactDto
	 * @param file
	 * @param existingAd
	 * @param rebomOptions
	 * @return
	 * @throws RelizaException
	 */
	@Transactional
	private OASResponseDto storeArtifactOnRebom (ArtifactDto artifactDto, Resource file, ArtifactData existingAd, RebomOptions rebomOptions) throws RelizaException {
		UUID orgUuid = artifactDto.getOrg();
		
		// 1. Parse BOM file
		JsonNode bomJson;
		try {
			bomJson = Utils.readJsonFromResource(file);
		} catch (Exception e) {
			log.error("Error reading Json", e);
			throw new RelizaException("Cannot parse artifact JSON. Make sure artifact type is set correctly.");
		}
		
		log.info("Starting BOM processing for artifact {}, format: {}", 
			artifactDto.getUuid(), artifactDto.getBomFormat());
		
		// 2. Prepare for processing (format-specific validation/setup)
		UUID existingSerialNumberForSpdx = null;
		if(artifactDto.getBomFormat().equals(BomFormat.CYCLONEDX)){
			validateCycloneDxUpdate(artifactDto, bomJson, existingAd);
		} else if(artifactDto.getBomFormat().equals(BomFormat.SPDX)){
			existingSerialNumberForSpdx = prepareSpdxUpdate(existingAd);
		}

		// 3. Process BOM through lifecycle service
		BomLifecycleService.BomLifecycleResult lifecycleResult;
		try {
			lifecycleResult = bomLifecycleService.processBomArtifact(
				bomJson,
				rebomOptions,
				artifactDto.getBomFormat(),
				orgUuid,
				artifactDto.getUuid(),
				existingAd,
				existingSerialNumberForSpdx
			);
		} catch (Exception e) {
			log.error("BOM lifecycle processing failed for artifact {}: {}", 
				artifactDto.getUuid(), e.getMessage(), e);
			throw new RelizaException("BOM processing failed: " + e.getMessage());
		}
		
		RebomResponse rebomResponse = lifecycleResult.rebomResponse();
		
		// 4. Apply response to artifact (format-specific)
		OASResponseDto response;
		if(artifactDto.getBomFormat().equals(BomFormat.CYCLONEDX)){
			applyCycloneDxResponse(artifactDto, rebomResponse, lifecycleResult, rebomOptions);
			response = rebomResponse.bom();
		} else if(artifactDto.getBomFormat().equals(BomFormat.SPDX)){
			response = applySpdxResponse(artifactDto, rebomResponse, lifecycleResult, rebomOptions);
		} else {
			throw new RelizaException("Unsupported BOM format: " + artifactDto.getBomFormat());
		}
		
		log.info("BOM processing complete for artifact {}, internalBomId: {}", 
			artifactDto.getUuid(), artifactDto.getInternalBom().id());
		
		return response;
	}

	/**
	 * Validates CycloneDX BOM update logic:
	 * - Extracts version from BOM
	 * - Checks if update vs new artifact (by serialNumber)
	 * - Validates version progression
	 * - Updates artifact UUID if different serialNumber
	 */
	private void validateCycloneDxUpdate(
		ArtifactDto artifactDto, 
		JsonNode bomJson, 
		ArtifactData existingAd
	) throws RelizaException {
		String newbomVerString = bomJson.get("version").asText();
		Integer newBomVersion = Integer.valueOf(newbomVerString);
		artifactDto.setVersion(newbomVerString);
		
		if(null != existingAd){
			Integer oldBomVersion = Integer.valueOf(
				getArtifactBomLatestVersion(existingAd.getInternalBom().id(), existingAd.getOrg())
			);
			String oldBomSerial = existingAd.getInternalBom().id().toString();
			
			String newBomSerial = bomJson.get("serialNumber").textValue();
			if(newBomSerial.startsWith("urn")){
				newBomSerial = newBomSerial.replace("urn:uuid:","");
			}
			
			if(oldBomSerial.equals(newBomSerial)){
				if(newBomVersion <= oldBomVersion)
					throw new RelizaException("Uploaded bom should have an incremented version");
			} else {
				artifactDto.setUuid(UUID.randomUUID());
			}
		}
	}

	/**
	 * Prepares SPDX BOM for processing:
	 * - Detects if update (existingAd present)
	 * - Returns existingSerialNumber for continuity
	 * 
	 * Note: Unlike CycloneDX, SPDX versioning is managed by rebom-backend.
	 * We just pass existingSerialNumber to maintain artifact continuity.
	 */
	private UUID prepareSpdxUpdate(ArtifactData existingAd) {
		if(null != existingAd && null != existingAd.getInternalBom()){
			return existingAd.getInternalBom().id();
		}
		return null;
	}

	/**
	 * Extracts internalBomId from rebom response serialNumber.
	 * Handles both "urn:uuid:..." and plain UUID formats.
	 */
	private UUID extractInternalBomId(RebomResponse rebomResponse) throws RelizaException {
		try {
			String bomSerialNumber = rebomResponse.meta().serialNumber();
			if(bomSerialNumber.startsWith("urn")){
				bomSerialNumber = bomSerialNumber.replace("urn:uuid:","");
			}
			return UUID.fromString(bomSerialNumber);
		} catch (Exception e) {
			throw new RelizaException("Error parsing BOM serialNumber: " + e.getMessage());
		}
	}

	/**
	 * Applies CycloneDX-specific data from rebom response to artifact:
	 * - Extracts bomDigest (REARM scope)
	 * - Sets internalBom
	 * - Handles DTrack result
	 */
	private void applyCycloneDxResponse(
		ArtifactDto artifactDto,
		RebomResponse rebomResponse,
		BomLifecycleService.BomLifecycleResult lifecycleResult,
		RebomOptions rebomOptions
	) throws RelizaException {
		// Extract digests
		Set<DigestRecord> digestRecords = artifactDto.getDigestRecords() != null 
			? artifactDto.getDigestRecords() 
			: new HashSet<>();
		
		if(StringUtils.isNotEmpty(rebomResponse.meta().bomDigest())){
			digestRecords.add(new DigestRecord(
				TeaChecksumType.SHA_256, 
				rebomResponse.meta().bomDigest(), 
				DigestScope.REARM
			));
		}
		
		artifactDto.setDigestRecords(digestRecords);
		
		// Set internalBom
		UUID internalBomId = extractInternalBomId(rebomResponse);
		artifactDto.setInternalBom(new InternalBom(internalBomId, rebomOptions.belongsTo()));
		
		// Ensure UUID is set
		if(null == artifactDto.getUuid()){
			artifactDto.setUuid(UUID.randomUUID());
		}
		
		// Note: DTrack integration is now handled asynchronously via the scheduler
	}

	/**
	 * Applies SPDX-specific data from rebom response to artifact:
	 * - Extracts rebom-managed version
	 * - Extracts originalFileDigest (hash of original SPDX file)
	 * - Extracts original file metadata (size, mediaType)
	 * - Sets internalBom
	 * - Handles DTrack result
	 * - Updates OASResponse with original file info
	 */
	private OASResponseDto applySpdxResponse(
		ArtifactDto artifactDto,
		RebomResponse rebomResponse,
		BomLifecycleService.BomLifecycleResult lifecycleResult,
		RebomOptions rebomOptions
	) throws RelizaException {
		// Extract version (rebom-managed)
		if(rebomResponse.meta().bomVersion() != null){
			artifactDto.setVersion(rebomResponse.meta().bomVersion());
		}
		
		// Extract digests
		Set<DigestRecord> digestRecords = artifactDto.getDigestRecords() != null 
			? artifactDto.getDigestRecords() 
			: new HashSet<>();
		
		if(StringUtils.isNotEmpty(rebomResponse.meta().bomDigest())){
			digestRecords.add(new DigestRecord(
				TeaChecksumType.SHA_256, 
				rebomResponse.meta().bomDigest(), 
				DigestScope.REARM
			));
		}
		
		// For SPDX, originalFileDigest is hash of original SPDX file (not converted CycloneDX)
		if (StringUtils.isNotEmpty(rebomResponse.meta().originalFileDigest())) {
			digestRecords.add(new DigestRecord(
				TeaChecksumType.SHA_256, 
				rebomResponse.meta().originalFileDigest(), 
				DigestScope.ORIGINAL_FILE
			));
		}
		
		artifactDto.setDigestRecords(digestRecords);
		
		// Set internalBom
		UUID internalBomId = extractInternalBomId(rebomResponse);
		artifactDto.setInternalBom(new InternalBom(internalBomId, rebomOptions.belongsTo()));
		
		// Ensure UUID is set
		if(null == artifactDto.getUuid()){
			artifactDto.setUuid(UUID.randomUUID());
		}
		
		// Note: DTrack integration is now handled asynchronously via the scheduler
		
		// Update OASResponse with original SPDX file metadata
		OASResponseDto response = rebomResponse.bom();
		if (rebomResponse.meta().originalFileSize() != null) {
			response.setOriginalSize(rebomResponse.meta().originalFileSize());
		}
		if (StringUtils.isNotEmpty(rebomResponse.meta().originalMediaType())) {
			response.setOriginalMediaType(rebomResponse.meta().originalMediaType());
		}
		if (StringUtils.isNotEmpty(rebomResponse.meta().originalFileDigest())) {
			response.setFileSHA256Digest(rebomResponse.meta().originalFileDigest());
		}
		
		return response;
	}

	private OASResponseDto uploadFileToConfiguredOci(Resource file, String tag, String sha256Digest){
		MultiValueMap<String, Object> formData = new LinkedMultiValueMap<>();
		if(!tag.startsWith("rearm")){
			tag = "rearm-" + tag;
		}
		formData.add("registry", this.registryHost);
        formData.add("repo", this.ociRepository);
        formData.add("file", file);
        formData.add("tag", tag);
		
		if(StringUtils.isNotEmpty(sha256Digest))
    		formData.add("inputDigest", sha256Digest);

		Mono<OASResponseDto> resp = this.webClient.post().uri("/push")
		.contentType(MediaType.MULTIPART_FORM_DATA)
		.body(BodyInserters.fromMultipartData(formData))
		.exchangeToMono(response -> {
			if (response.statusCode().equals(HttpStatus.OK)) {
				return response.bodyToMono(OASResponseDto.class);
			} else {
				return response.createError();
			}
		});
		OASResponseDto artifactUploadResponse = resp.block();
		return artifactUploadResponse;
	}
	
	protected void initialProcessArtifactsOnDependencyTrack () {
		List<Artifact> initialArts = repository.listInitialArtifactsPendingOnDependencyTrack();
		log.debug("PSDEBUG: located " + initialArts.size() + " to process initially on dep track");
		initialArts.forEach(a -> {
			try {
				fetchDependencyTrackDataForArtifact(a);
			} catch (RelizaException e) {
				log.error("Exception on initial processing artifact on dtrack for id = " + a.getUuid());
			}
		});
	}
	
	/**
	 * Submit pending BOM artifacts to Dependency Track.
	 * This method finds orgs with DTrack integration, then for each org finds artifacts 
	 * that have been uploaded to rebom but not yet submitted to DTrack,
	 * checks their enrichment status, and submits them using the enriched BOM from rebom.
	 */
	protected void submitPendingArtifactsToDependencyTrack() {
		// Get all orgs that have DTrack integration configured
		List<UUID> orgsWithDtrack = integrationService.listOrgsWithDtrackIntegration();
		if (orgsWithDtrack.isEmpty()) {
			log.debug("No orgs with DTrack integration found");
			return;
		}
		
		log.debug("Found {} orgs with DTrack integration", orgsWithDtrack.size());
		
		for (UUID orgUuid : orgsWithDtrack) {
			List<Artifact> pendingArts = repository.listArtifactsPendingDtrackSubmission(orgUuid.toString());
			log.debug("Found {} artifacts pending DTrack submission for org {}", pendingArts.size(), orgUuid);
			
			for (Artifact a : pendingArts) {
				try {
					submitArtifactToDependencyTrack(a);
				} catch (Exception e) {
					log.error("Exception submitting artifact {} to DTrack: {}", a.getUuid(), e.getMessage());
				}
			}
		}
	}
	
	/**
	 * Submit a single artifact to Dependency Track using the enriched BOM from rebom.
	 * Includes deduplication logic - if another artifact with the same BOM digest already
	 * has a DTrack project, we reuse that project instead of creating a new one.
	 */
	private void submitArtifactToDependencyTrack(Artifact a) {
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		
		if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) {
			log.warn("Artifact {} has no internalBom id, skipping DTrack submission", ad.getUuid());
			return;
		}
		
		UUID internalBomId = ad.getInternalBom().id();
		UUID orgUuid = ad.getOrg();
		
		// Check enrichment status from rebom
		RebomService.BomMeta bomMeta = rebomService.getBomMetadataById(internalBomId, orgUuid);
		if (bomMeta == null) {
			log.warn("Could not retrieve BOM metadata for artifact {}, internalBomId {}", ad.getUuid(), internalBomId);
			return;
		}
		
		RebomService.EnrichmentStatus enrichmentStatus = bomMeta.enrichmentStatus();
		
		// Only proceed if enrichment is COMPLETED, FAILED, or SKIPPED (not PENDING)
		if (enrichmentStatus == RebomService.EnrichmentStatus.PENDING) {
			log.debug("Artifact {} enrichment still pending, will retry later", ad.getUuid());
			return;
		}
		
		if (enrichmentStatus == RebomService.EnrichmentStatus.FAILED) {
			log.error("Artifact {} enrichment failed: {}", ad.getUuid(), bomMeta.enrichmentError());
			// Still proceed with submission using non-enriched BOM
		}
		
		// Check for deduplication - if another artifact with same digest already has DTrack project
		String bomDigest = bomMeta.bomDigest();
		log.debug("Checking deduplication for artifact {} with bomDigest {}", ad.getUuid(), bomDigest);
		if (StringUtils.isNotEmpty(bomDigest)) {
			Optional<DependencyTrackUploadResult> deduplicatedResult = tryDtrackDeduplication(orgUuid, bomDigest, ad.getUuid());
			log.debug("Deduplication result for artifact {}: present={}", ad.getUuid(), deduplicatedResult.isPresent());
			if (deduplicatedResult.isPresent()) {
				// Reuse existing DTrack project
				DependencyTrackUploadResult dtur = deduplicatedResult.get();
				log.info("Artifact {} deduplicated, reusing existing DTrack project {}", ad.getUuid(), dtur.projectId());
				sharedArtifactService.updateArtifactFromDtur(ad, dtur);
				return;
			}
		}
		
		// Retrieve the enriched BOM from rebom (always CycloneDX)
		JsonNode bomJson;
		try {
			bomJson = rebomService.findBomByIdJson(internalBomId, orgUuid);
		} catch (Exception e) {
			log.error("Failed to retrieve BOM for artifact {}: {}", ad.getUuid(), e.getMessage());
			return;
		}
		
		if (bomJson == null) {
			log.error("Retrieved null BOM for artifact {}", ad.getUuid());
			return;
		}
		
		// Determine project name and version
		String projectName = bomMeta.name();
		String projectVersion = dtrackService.resolveBomVersionForArtifactUpload(bomMeta, ad.getUuid());
		if (StringUtils.isEmpty(projectName)) {
			projectName = ad.getUuid().toString();
		}
		
		// Submit to DTrack
		try {
			byte[] bomContent = Utils.OM.writeValueAsBytes(bomJson);
			UploadableBom ub = new UploadableBom(null, bomContent, true);
			
			String oldProjectId = (ad.getMetrics() != null) 
				? ad.getMetrics().getDependencyTrackProject() : null;
			
			DependencyTrackUploadResult dtur = integrationService.uploadBomAndUpdateRelated(
				orgUuid, ub, projectName, projectVersion, oldProjectId, ad);
			
			if (dtur != null) {
				log.info("Successfully submitted artifact {} to DTrack, project: {}", 
					ad.getUuid(), dtur.projectId());
			}
		} catch (Exception e) {
			log.error("Failed to submit artifact {} to DTrack: ", ad.getUuid());
			log.error("Artifact submission error details", e);
		}
	}
	
	/**
	 * Try to find an existing artifact with the same BOM digest that already has a DTrack project.
	 * If found and the project is not deleted, return the DTrack result to reuse.
	 */
	private Optional<DependencyTrackUploadResult> tryDtrackDeduplication(UUID orgUuid, String bomDigest, UUID currentArtifactUuid) {
		Optional<Artifact> existingArtifact = sharedArtifactService.findArtifactByStoredDigest(orgUuid, bomDigest);
		
		log.debug("tryDtrackDeduplication for artifact {} - found existing: {}", 
			currentArtifactUuid, existingArtifact.isPresent());
		
		if (existingArtifact.isEmpty()) {
			return Optional.empty();
		}
		
		ArtifactData existingAd = ArtifactData.dataFromRecord(existingArtifact.get());
		log.debug("Found existing artifact {} for deduplication check", existingAd.getUuid());
		
		// Don't deduplicate with self
		if (existingAd.getUuid().equals(currentArtifactUuid)) {
			log.debug("Existing artifact is self, skipping");
			return Optional.empty();
		}
		
		// Check if existing artifact has DTrack project
		if (existingAd.getMetrics() == null || StringUtils.isEmpty(existingAd.getMetrics().getDependencyTrackProject())) {
			log.debug("Existing artifact {} has no DTrack project (metrics={}, dtrackProject={})", 
				existingAd.getUuid(), 
				existingAd.getMetrics() != null,
				existingAd.getMetrics() != null ? existingAd.getMetrics().getDependencyTrackProject() : "null");
			return Optional.empty();
		}
		
		// Check if DTrack project was deleted
		Boolean deleted = existingAd.getMetrics().getDtrackProjectDeleted();
		if (deleted != null && deleted) {
			log.info("Existing artifact {} has deleted DTrack project, skipping deduplication", existingAd.getUuid());
			return Optional.empty();
		}
		
		// Request metrics refresh on the existing project
		dtrackService.requestMetricsRefresh(existingAd);
		
		log.info("DTrack deduplication found: artifact {} will reuse DTrack project from artifact {}", 
			currentArtifactUuid, existingAd.getUuid());
		
		return Optional.of(new DependencyTrackUploadResult(
			existingAd.getMetrics().getDependencyTrackProject(),
			existingAd.getMetrics().getUploadToken(),
			existingAd.getMetrics().getProjectName(),
			existingAd.getMetrics().getProjectVersion(),
			existingAd.getMetrics().getDependencyTrackFullUri()));
	}
	
	public boolean fetchDependencyTrackDataForArtifact (Artifact a) throws RelizaException {
		ZonedDateTime lastScanned = ZonedDateTime.now();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		var dti = integrationService.resolveDependencyTrackProcessingStatus(ad, lastScanned);
		boolean doUpdate = false;
		if (null != dti) {
			log.debug("PSDEBUG: setting dti to last scanned = " + dti.getLastScanned() + " on artifact = " + a.getUuid());
			if (null == ad.getMetrics() || null == ad.getMetrics().getLastScanned() || 
					ad.getMetrics().getLastScanned().plusSeconds(1).isBefore(dti.getLastScanned())){
				doUpdate = true;
				log.debug("PSDEBUG: saving artifact dti on artifact = " + a.getUuid());
			}
		}
			
		if (doUpdate) sharedArtifactService.updateArtifactDti(a, dti, WhoUpdated.getAutoWhoUpdated());
		return true;
	}

	/**
	 * Sync dependency track data for all unsynced projects
	 * Retrieves unsynced projects from Dependency Track, finds associated artifacts,
	 * and updates their dependency track data
	 * @param orgUuid Organization UUID
	 * @return Number of artifacts processed
	 */
	public int syncUnsyncedDependencyTrackData(UUID orgUuid) {
		log.info("Starting sync of unsynced dependency track data for org {}", orgUuid);
		
		// Get last sync time from system info
		var lastSyncTime = systemInfoService.getLastDtrackSync();
		if (lastSyncTime == null) {
			log.info("No last sync time found, using 1970-01-01 as default for org {}", orgUuid);
			lastSyncTime = java.time.ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);
		}
		
		// Get all unsynced projects from Dependency Track
		Set<UUID> unsyncedProjects = integrationService.retrieveUnsyncedDtrackProjects(orgUuid, lastSyncTime);
		log.info("Found {} unsynced projects for org {}", unsyncedProjects.size(), orgUuid);
		
		if (unsyncedProjects.isEmpty()) {
			log.info("No unsynced projects found for org {}", orgUuid);
			return 0;
		}
		
		// Get artifacts for these projects
		List<Artifact> artifacts = listArtifactsByDtrackProjects(unsyncedProjects);
		log.info("Found {} artifacts to sync for {} unsynced projects in org {}", 
				artifacts.size(), unsyncedProjects.size(), orgUuid);
		
		// Process each artifact
		ZonedDateTime lastScanned = ZonedDateTime.now();
		int processedCount = 0;
		for (Artifact artifact : artifacts) {
			try {
				fetchDependencyTrackDataForArtifact(artifact);
				processedCount++;
				log.debug("Successfully processed artifact {} for dependency track sync", artifact.getUuid());
			} catch (Exception e) {
				log.error("Error processing artifact {} for dependency track sync: {}", 
						artifact.getUuid(), e.getMessage(), e);
			}
		}
		
		log.info("Completed sync of unsynced dependency track data for org {}. Processed {}/{} artifacts", 
				orgUuid, processedCount, artifacts.size());
		
		// Count errors and only update last sync time if there are no errors
		int errorCount = artifacts.size() - processedCount;
		if (errorCount == 0) {
			// Update last sync time to current time only if all artifacts processed successfully
			systemInfoService.setLastDtrackSync(lastScanned);
			log.debug("Updated last dependency track sync time to current time - no errors encountered");
		} else {
			log.warn("Skipping last sync time update due to {} errors during processing", errorCount);
		}
		
		return processedCount;
	}

	public String getArtifactBomLatestVersion(UUID id, UUID org) throws RelizaException{
		JsonNode bom;
		try {
			bom = rebomService.findBomByIdJson(id, org);
		} catch (Exception e) {
			throw new RelizaException("Error retrieving BOM for artifact: " + e.getMessage());
		}
		
		if (bom == null || bom.isEmpty()) {
			throw new RelizaException("BOM not found for artifact");
		}

		JsonNode version = bom.get("version");
		if (version != null) {
			return version.asText();
		}
		
		throw new RelizaException("Version not found in BOM");
	}
	
	public Optional<ArtifactData> getArtifactSignature(ArtifactData ad) {
		Optional<ArtifactData> retAd = Optional.empty();
		var signatureAD = getArtifactDataList(ad.getArtifacts()).stream().filter(a -> a.getType() == ArtifactType.SIGNATURE).findFirst();
		if (signatureAD.isPresent()) {
			if (!signatureAD.get().getOrg().equals(ad.getOrg())) {
				log.error(String.format("Signature artifact does not belong to the same organization as the artifact: %s, %s", ad.getUuid(), signatureAD.get().getUuid()));
				throw new RuntimeException("Org mismatch");
			}
			retAd = Optional.of(signatureAD.get());
		}
		return retAd;
	}
	
	/**
	 * Compute artifact metrics by processing vulnerability analysis
	 * Only saves the artifact if metrics have changed after processing
	 */
	public void computeArtifactMetrics(UUID artifactId) {
		Optional<Artifact> oa = sharedArtifactService.getArtifact(artifactId);
		if (oa.isPresent()) {
			var ad = ArtifactData.dataFromRecord(oa.get());
			if (ad.getMetrics() != null) {
				ReleaseMetricsDto originalMetrics = ad.getMetrics();
				ReleaseMetricsDto clonedMetrics = originalMetrics.clone();
				
				// Process vulnerability analysis on cloned metrics
				vulnAnalysisService.processReleaseMetricsDto(ad.getOrg(), ad.getOrg(), AnalysisScope.ORG, clonedMetrics);
				
				// Only save if metrics changed
				if (!clonedMetrics.equals(originalMetrics)) {
					// Convert ReleaseMetricsDto to DependencyTrackIntegration
					ArtifactData.DependencyTrackIntegration dti = ArtifactData.DependencyTrackIntegration.fromReleaseMetricsDto(clonedMetrics);
					// Preserve existing DependencyTrack-specific fields
					if (ad.getMetrics() != null) {
						dti.setDependencyTrackProject(ad.getMetrics().getDependencyTrackProject());
						dti.setUploadToken(ad.getMetrics().getUploadToken());
						dti.setDependencyTrackFullUri(ad.getMetrics().getDependencyTrackFullUri());
						dti.setProjectName(ad.getMetrics().getProjectName());
						dti.setProjectVersion(ad.getMetrics().getProjectVersion());
						dti.setUploadDate(ad.getMetrics().getUploadDate());
					}
					ad.setMetrics(dti);
					Map<String, Object> recordData = Utils.dataToRecord(ad);
					sharedArtifactService.saveArtifact(oa.get(), recordData, WhoUpdated.getAutoWhoUpdated());
					log.debug("Artifact metrics updated for artifact: {}", artifactId);
				} else {
					log.debug("No metrics changes for artifact: {}", artifactId);
				}
			}
		} else {
			log.warn("Attempted to compute metrics for non-existent artifact = " + artifactId);
		}
	}
	
	/**
	 * Find artifacts with matching vulnerability
	 */
	public List<Artifact> findArtifactsWithVulnerability(UUID orgUuid, String location, String findingId) {
		return repository.findArtifactsWithVulnerability(orgUuid.toString(), location, findingId);
	}
	
	/**
	 * Find artifacts with matching violation
	 */
	public List<Artifact> findArtifactsWithViolation(UUID orgUuid, String location, String findingId) {
		return repository.findArtifactsWithViolation(orgUuid.toString(), location, findingId);
	}
	
	/**
	 * Find artifacts with matching weakness
	 */
	public List<Artifact> findArtifactsWithWeakness(UUID orgUuid, String location, String findingId) {
		return repository.findArtifactsWithWeakness(orgUuid.toString(), location, findingId);
	}
	
	/**
	 * Result of artifact format resolution containing serialization format and optional spec version
	 */
	public record ArtifactFormatResolution(SerializationFormat serializationFormat, SpecVersion specVersion) {}
	
	private static final Set<ArtifactType> RESOLVABLE_ARTIFACT_TYPES = Set.of(
		ArtifactType.BOM,
		ArtifactType.ATTESTATION,
		ArtifactType.VDR,
		ArtifactType.BOV,
		ArtifactType.VEX,
		ArtifactType.CODE_SCANNING_RESULT,
		ArtifactType.SARIF
	);
	
	private static final Set<SpecVersion> CYCLONEDX_SPEC_VERSIONS = Set.of(
		SpecVersion.CYCLONEDX_1_0,
		SpecVersion.CYCLONEDX_1_1,
		SpecVersion.CYCLONEDX_1_2,
		SpecVersion.CYCLONEDX_1_3,
		SpecVersion.CYCLONEDX_1_4,
		SpecVersion.CYCLONEDX_1_5,
		SpecVersion.CYCLONEDX_1_6,
		SpecVersion.CYCLONEDX_1_7
	);
	
	private static final Set<SpecVersion> SPDX_SPEC_VERSIONS = Set.of(
		SpecVersion.SPDX_2_0,
		SpecVersion.SPDX_2_1,
		SpecVersion.SPDX_2_2,
		SpecVersion.SPDX_2_3,
		SpecVersion.SPDX_3_0
	);
	
	/**
	 * Validates that BOM artifacts with known formats have resolvable spec versions
	 * @throws RelizaException if validation fails
	 */
	private void validateBomSpecVersion(ArtifactDto artifactDto) throws RelizaException {
		if (artifactDto.getBomFormat() == null || artifactDto.getSerializationFormat() != SerializationFormat.JSON) {
			return;
		}
		
		SpecVersion specVersion = artifactDto.getSpecVersion();
		
		if (artifactDto.getBomFormat() == BomFormat.CYCLONEDX) {
			if (specVersion == null || !CYCLONEDX_SPEC_VERSIONS.contains(specVersion)) {
				throw new RelizaException("CycloneDX JSON BOM has unrecognized or missing spec version. Supported versions: 1.0-1.7");
			}
		}
		
		if (artifactDto.getBomFormat() == BomFormat.SPDX) {
			if (specVersion == null || !SPDX_SPEC_VERSIONS.contains(specVersion)) {
				throw new RelizaException("SPDX JSON BOM has unrecognized or missing spec version. Supported versions: 2.0-2.3, 3.0");
			}
		}
	}
	
	/**
	 * Validates and resolves artifact format details (serialization format and spec version)
	 * Only processes specific artifact types: BOM, ATTESTATION, VDR, BOV, VEX, CODE_SCANNING_RESULT, SARIF
	 * @param file The artifact file resource
	 * @param artifactType The type of artifact
	 * @return ArtifactFormatResolution with resolved format and optional spec version, or null if type not resolvable
	 */
	private ArtifactFormatResolution resolveArtifactFormat(Resource file, ArtifactType artifactType) {
		if (artifactType == null || !RESOLVABLE_ARTIFACT_TYPES.contains(artifactType)) {
			return null;
		}
		
		SerializationFormat serializationFormat = SerializationFormat.OTHER;
		SpecVersion specVersion = null;
		
		try {
			String content = new String(file.getInputStream().readAllBytes());
			String trimmed = content.trim();
			
			if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
				serializationFormat = SerializationFormat.JSON;
				specVersion = resolveSpecVersionFromJson(trimmed);
			} else if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) {
				serializationFormat = SerializationFormat.XML;
			} else if (isYamlContent(trimmed)) {
				serializationFormat = SerializationFormat.YAML;
			}
		} catch (Exception e) {
			log.warn("Could not read artifact file for format resolution", e);
		}
		
		return new ArtifactFormatResolution(serializationFormat, specVersion);
	}
	
	/**
	 * Attempts to resolve spec version from JSON content
	 */
	private SpecVersion resolveSpecVersionFromJson(String jsonContent) {
		try {
			JsonNode root = Utils.OM.readTree(jsonContent);
			
			// Check for CycloneDX
			if (root.has("bomFormat") && "CycloneDX".equalsIgnoreCase(root.get("bomFormat").asText())) {
				String version = root.has("specVersion") ? root.get("specVersion").asText() : null;
				if (version != null) {
					return switch (version) {
						case "1.0" -> SpecVersion.CYCLONEDX_1_0;
						case "1.1" -> SpecVersion.CYCLONEDX_1_1;
						case "1.2" -> SpecVersion.CYCLONEDX_1_2;
						case "1.3" -> SpecVersion.CYCLONEDX_1_3;
						case "1.4" -> SpecVersion.CYCLONEDX_1_4;
						case "1.5" -> SpecVersion.CYCLONEDX_1_5;
						case "1.6" -> SpecVersion.CYCLONEDX_1_6;
						case "1.7" -> SpecVersion.CYCLONEDX_1_7;
						default -> null;
					};
				}
			}
			
			// Check for SPDX
			if (root.has("spdxVersion")) {
				String version = root.get("spdxVersion").asText();
				if (version != null) {
					if (version.contains("2.0")) return SpecVersion.SPDX_2_0;
					if (version.contains("2.1")) return SpecVersion.SPDX_2_1;
					if (version.contains("2.2")) return SpecVersion.SPDX_2_2;
					if (version.contains("2.3")) return SpecVersion.SPDX_2_3;
					if (version.contains("3.0")) return SpecVersion.SPDX_3_0;
				}
			}
			
			// Check for SARIF
			if (root.has("$schema") && root.get("$schema").asText().contains("sarif")) {
				String version = root.has("version") ? root.get("version").asText() : null;
				if (version != null) {
					if (version.startsWith("2.0")) return SpecVersion.SARIF_2_0;
					if (version.startsWith("2.1")) return SpecVersion.SARIF_2_1;
				}
			}
			
			// Check for OpenVEX
			if (root.has("@context") && root.get("@context").asText().contains("openvex")) {
				return SpecVersion.OPENVEX_0_2;
			}
			
			// Check for CSAF
			if (root.has("document") && root.get("document").has("csaf_version")) {
				String version = root.get("document").get("csaf_version").asText();
				if (version != null) {
					if (version.startsWith("2.0")) return SpecVersion.CSAF_2_0;
					if (version.startsWith("2.1")) return SpecVersion.CSAF_2_1;
				}
			}
			
		} catch (JsonProcessingException e) {
			log.warn("Could not parse JSON for spec version resolution", e);
		}
		
		return null;
	}
	
	/**
	 * Simple heuristic to detect YAML content
	 */
	private boolean isYamlContent(String content) {
		// YAML typically starts with key: value or --- or has indentation-based structure
		return content.startsWith("---") || 
			   content.contains(":\n") || 
			   content.contains(": \n") ||
			   (content.contains(":") && !content.contains("{") && !content.contains("<"));
	}
	
	/**
	 * Sync DTrack status for all BOM artifacts from active branches of an organization.
	 * Gathers artifacts from releases, source code entries, and deliverables, then triggers
	 * metrics refresh on Dependency Track for each BOM artifact.
	 * 
	 * @param orgUuid Organization UUID
	 * @return Response with success count and failed artifact UUIDs
	 */
	@Transactional
	public SyncDtrackStatusResponseDto syncDtrackStatus(UUID orgUuid) {
		log.info("Starting DTrack status sync for organization: {}", orgUuid);
		
		Set<UUID> artifactUuids = collectArtifactUuidsFromActiveBranches(orgUuid);
		Set<UUID> failedArtifactUuids = new HashSet<>();
		int successCount = 0;
		
		log.info("Collected {} unique artifact UUIDs for DTrack sync", artifactUuids.size());
		
		// Filter to only BOM type artifacts and trigger metrics refresh
		for (UUID artifactUuid : artifactUuids) {
			try {
				Optional<ArtifactData> artifactOpt = getArtifactData(artifactUuid);
				if (artifactOpt.isPresent()) {
					ArtifactData artifact = artifactOpt.get();
					
					// Only process BOM type artifacts
					if (artifact.getType() == ArtifactType.BOM && artifact.getStoredIn() == StoredIn.REARM) {
						boolean success = integrationService.requestMetricsRefreshOnDependencyTrack(artifact);
						if (success) {
							successCount++;
							log.debug("Successfully requested DTrack refresh for artifact: {}", artifactUuid);
						} else {
							failedArtifactUuids.add(artifactUuid);
							log.error("Failed to request DTrack refresh for artifact: {}", artifactUuid);
						}
					}
				}
			} catch (Exception e) {
				failedArtifactUuids.add(artifactUuid);
				log.error("Exception while processing artifact {} for DTrack sync: {}", artifactUuid, e.getMessage(), e);
			}
		}
		
		log.info("DTrack status sync completed for organization {}. Success: {}, Failed: {}", 
			orgUuid, successCount, failedArtifactUuids.size());
		
		return new SyncDtrackStatusResponseDto(successCount, failedArtifactUuids);
	}
	
	/**
	 * Refresh DTrack projects by checking if BOMs were updated since last upload.
	 * For each distinct DTrack project in the org, finds the latest uploaded artifact
	 * and checks if it needs resubmission based on BOM update.
	 * Runs asynchronously.
	 * 
	 * @param orgUuid Organization UUID
	 */
	@org.springframework.scheduling.annotation.Async
	public void refreshDtrackProjects(UUID orgUuid) {
		log.info("Starting DTrack project refresh for organization: {}", orgUuid);
		
		int successCount = 0;
		int failedCount = 0;
		
		// Get artifact UUIDs from active branches to scope the refresh
		Set<UUID> activeBranchArtifactUuids = collectArtifactUuidsFromActiveBranches(orgUuid);
		log.info("Found {} artifact UUIDs from active branches", activeBranchArtifactUuids.size());
		
		// Get distinct DTrack projects for this org
		List<String> allDtrackProjects = repository.listDistinctDtrackProjectsByOrg(orgUuid.toString());
		
		// Filter to only DTrack projects that have at least one artifact from active branches
		List<String> dtrackProjects = allDtrackProjects.stream()
			.filter(dtrackProject -> {
				List<Artifact> artifacts = repository.findArtifactsByDtrackProjectAndOrg(orgUuid.toString(), dtrackProject);
				return artifacts.stream().anyMatch(a -> activeBranchArtifactUuids.contains(a.getUuid()));
			})
			.toList();
		
		log.info("Found {} distinct DTrack projects for organization {} ({} have active branch artifacts)", 
			allDtrackProjects.size(), orgUuid, dtrackProjects.size());
		
		for (String dtrackProject : dtrackProjects) {
			try {
				// Get all artifacts sharing this DTrack project, ordered by uploadDate desc
				List<Artifact> artifacts = repository.findArtifactsByDtrackProjectAndOrg(orgUuid.toString(), dtrackProject);
				
				if (artifacts.isEmpty()) {
					log.debug("No artifacts found for DTrack project: {}", dtrackProject);
					continue;
				}
				
				// Pick the first one (latest uploaded)
				ArtifactData latestArtifact = ArtifactData.dataFromRecord(artifacts.get(0));
				
				log.debug("Processing DTrack project {} with latest artifact {}", dtrackProject, latestArtifact.getUuid());
				
				// Check if BOM was updated and resubmit if needed
				boolean success = dtrackService.checkAndResubmitIfBomUpdated(latestArtifact);
				if (success) {
					successCount++;
					log.debug("Successfully processed DTrack project {} (artifact: {})", dtrackProject, latestArtifact.getUuid());
					
					// Update uploadDate for all other artifacts sharing this DTrack project
					// (latest artifact already updated by resubmitArtifact)
					ZonedDateTime now = ZonedDateTime.now();
					for (int i = 1; i < artifacts.size(); i++) {
						Artifact artifact = artifacts.get(i);
						ArtifactData ad = ArtifactData.dataFromRecord(artifact);
						if (ad.getMetrics() != null) {
							ad.getMetrics().setUploadDate(now);
							sharedArtifactService.saveArtifact(artifact, Utils.dataToRecord(ad), WhoUpdated.getAutoWhoUpdated());
						}
					}
					log.debug("Updated uploadDate for {} other artifacts sharing DTrack project {}", artifacts.size() - 1, dtrackProject);
				}
				
				// Sleep for 10 seconds between artifacts
				if (dtrackProjects.indexOf(dtrackProject) < dtrackProjects.size() - 1) {
					Thread.sleep(10000);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("DTrack project refresh interrupted for organization {}", orgUuid);
				break;
			} catch (Exception e) {
				failedCount++;
				log.error("Exception while processing DTrack project {}: {}", dtrackProject, e.getMessage(), e);
			}
		}
		
		log.info("DTrack project refresh completed for organization {}. Success: {}, Failed: {}", 
			orgUuid, successCount, failedCount);
	}
	
	/**
	 * Collects all artifact UUIDs from active branches of an organization.
	 * Gathers artifacts from releases, source code entries, and deliverables.
	 * 
	 * @param orgUuid Organization UUID
	 * @return Set of artifact UUIDs from active branches
	 */
	private Set<UUID> collectArtifactUuidsFromActiveBranches(UUID orgUuid) {
		Set<UUID> artifactUuids = new HashSet<>();
		
		// Get all active branches for the organization
		List<BranchData> activeBranches = branchService.listBranchDataOfOrg(orgUuid);
		
		log.info("Found {} active branches for organization {}", activeBranches.size(), orgUuid);
		
		// For each active branch, gather all releases and their artifacts
		for (BranchData branch : activeBranches) {
			List<ReleaseData> releases = sharedReleaseService.listReleaseDataOfBranch(
				branch.getUuid(), 10000, false
			);
			
			for (ReleaseData release : releases) {
				// Path 1: Direct release artifacts
				if (release.getArtifacts() != null) {
					artifactUuids.addAll(release.getArtifacts());
				}
				
				// Path 2a: Source code entry artifacts (sourceCodeEntry field)
				if (release.getSourceCodeEntry() != null) {
					Optional<SourceCodeEntryData> sceOpt = getSourceCodeEntryService.getSourceCodeEntryData(release.getSourceCodeEntry());
					if (sceOpt.isPresent() && sceOpt.get().getArtifacts() != null) {
						artifactUuids.addAll(sceOpt.get().getArtifacts().stream().map(x -> x.artifactUuid()).toList());
					}
				}
				
				// Path 2b: Source code entry artifacts (commits array)
				if (release.getCommits() != null) {
					for (UUID commitUuid : release.getCommits()) {
						Optional<SourceCodeEntryData> sceOpt = getSourceCodeEntryService.getSourceCodeEntryData(commitUuid);
						if (sceOpt.isPresent() && sceOpt.get().getArtifacts() != null) {
							artifactUuids.addAll(sceOpt.get().getArtifacts().stream().map(x -> x.artifactUuid()).toList());
						}
					}
				}
				
				// Path 3: Variant deliverable artifacts
				List<VariantData> variants = variantService.getVariantsOfRelease(release.getUuid());
					
				for (VariantData variant : variants) {
					if (variant.getOutboundDeliverables() != null) {
						for (UUID deliverableUuid : variant.getOutboundDeliverables()) {
							Optional<DeliverableData> deliverableOpt = getDeliverableService.getDeliverableData(deliverableUuid);
							if (deliverableOpt.isPresent() && deliverableOpt.get().getArtifacts() != null) {
								artifactUuids.addAll(deliverableOpt.get().getArtifacts());
							}
						}
					}
				}
			}
		}
		
		return artifactUuids;
	}

}
