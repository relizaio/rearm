/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.ZonedDateTime;
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
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
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
    private SystemInfoService systemInfoService;
	
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

	public boolean isRebomStoreable (ArtifactDto artifactDto) {
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
		String tag = "";
		OASResponseDto artifactUploadResponse = null;
		DependencyTrackUploadResult dtur = null;
		ArtifactData existingAd = null;
		RebomResponse rebomResponse = null;
		if(null != artifactDto.getUuid()){
			existingAd = getArtifactData(artifactDto.getUuid()).get();
		}

		if(artifactDto.getStoredIn().equals(StoredIn.REARM)){
			if(isRebomStoreable(artifactDto)){
				JsonNode bomJson;
				try {
					bomJson = Utils.readJsonFromResource(file);
				} catch (IOException e) {
					log.error("Error reading Json", e);
					throw new RelizaException(e.getMessage());
				}
				if(artifactDto.getBomFormat().equals(BomFormat.CYCLONEDX)){
					String newbomVerString = bomJson.get("version").asText();
					Integer newBomVersion = Integer.valueOf(newbomVerString);
					artifactDto.setVersion(newbomVerString);
					//case of update
					if(null != existingAd){
						// find the existing artifact data
						Integer oldBomVersion =  Integer.valueOf(getArtifactBomLatestVersion(existingAd.getInternalBom().id(), existingAd.getOrg()));		
						String oldBomSerial = existingAd.getInternalBom().id().toString();
				

						String newBomSerial = bomJson.get("serialNumber").textValue();
						if(newBomSerial.startsWith("urn")){
							newBomSerial = newBomSerial.replace("urn:uuid:","");
						}
						
						// compare if lesser
						// reject
						if(oldBomSerial.equals(newBomSerial)){
							if(newBomVersion <= oldBomVersion)
								throw new RelizaException("Uploaded bom should have an incremented version");
						}else{
							artifactDto.setUuid(UUID.randomUUID());
						}
					}
				}


				rebomResponse = rebomService.uploadSbom(bomJson, rebomOptions, artifactDto.getBomFormat(), orgUuid);

				// UUID internalBomId = rebomOptions.serialNumber();
				UUID internalBomId;
				try{
					String bomSerialNumber = rebomResponse.meta().serialNumber();
					if(bomSerialNumber.startsWith("urn")){
						bomSerialNumber = bomSerialNumber.replace("urn:uuid:","");
					}
					internalBomId = UUID.fromString(bomSerialNumber);
				}catch (Exception e){
					throw new RelizaException("Error uploading BOM: " + e.getMessage());
				}
				artifactDto.setInternalBom(new InternalBom(internalBomId, rebomOptions.belongsTo()));
				artifactUploadResponse = rebomResponse.bom();
				
				if(null == artifactDto.getUuid()){
					artifactDto.setUuid(UUID.randomUUID());
				}
				if (artifactDto.getType().equals(ArtifactType.BOM)) {
					if(StringUtils.isNotEmpty(rebomResponse.meta().bomDigest()) ){
						var a = findArtifactByStoredDigest(orgUuid, rebomResponse.meta().bomDigest());
						if(a.isPresent()){
							var metrics = ArtifactData.dataFromRecord(a.get()).getMetrics();
							dtur = new DependencyTrackUploadResult(metrics.getDependencyTrackProject(), metrics.getUploadToken(), metrics.getProjectName(), metrics.getProjectVersion(),metrics.getDependencyTrackFullUri());
						}
					}
					if(null == dtur){
						// for an SPDX bom, we fetch the converted CycloneDX bomJson
						if(artifactDto.getBomFormat().equals(BomFormat.SPDX)){
							try {
								bomJson = rebomService.findRawBomById(internalBomId, orgUuid, BomFormat.CYCLONEDX);
							} catch (JsonProcessingException e) {
								throw new RelizaException("Failed to process BOM JSON: " + e.getMessage());
							}
						}
						String dtrackVersion = rebomOptions.version() + "-" + artifactDto.getUuid().toString();
						UploadableBom ub = new UploadableBom(bomJson, null, false);
						dtur = integrationService.sendBomToDependencyTrack(orgUuid, ub, rebomOptions.name(), dtrackVersion);
					}
					artifactDto.setDtur(dtur);
				}
				tag = artifactDto.getUuid().toString();
			} else {
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
				artifactUploadResponse = uploadFileToConfiguredOci(file, tag, sha256Digest);
				if (artifactDto.getType().equals(ArtifactType.SARIF)) {
					try {
						var sarifJson = Utils.readJsonFromResource(file);
						String sarifString = Utils.OM.writeValueAsString(sarifJson);
						ReleaseMetricsDto rmd = rebomService.parseSarifOnRebom(sarifString);
						artifactDto.setRmd(rmd);
					} catch (Exception e) {
						log.error("Error parsing SARIF file", e);
						throw new RelizaException("Error parsing SARIF file");
					}
				} else if ((artifactDto.getType().equals(ArtifactType.VDR) || artifactDto.getType().equals(ArtifactType.BOV)) &&
					artifactDto.getBomFormat().equals(BomFormat.CYCLONEDX) ) {
					try {
						var vdrJson = Utils.readJsonFromResource(file);
						String vdrString = Utils.OM.writeValueAsString(vdrJson);
						ReleaseMetricsDto rmd = rebomService.parseCycloneDxContent(vdrString);
						artifactDto.setRmd(rmd);
					} catch (Exception e) {
						log.error("Error parsing CycloneDX VDR/BOV file", e);
						throw new RelizaException("Error parsing CycloneDX VDR/BOV file");
					}
				}
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
			if(null!=rebomResponse && StringUtils.isNotEmpty(rebomResponse.meta().bomDigest())){
				digestRecords.add(new DigestRecord(TeaChecksumType.SHA_256, rebomResponse.meta().bomDigest(), DigestScope.REARM));
			}
			artifactDto.setDigestRecords(digestRecords);

			artifactDto.setTags(List.of(
	            new TagRecord(CommonVariables.SIZE_FEILD, artifactUploadResponse.getOciResponse().getSize(), Removable.NO),
	            new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, artifactUploadResponse.getOciResponse().getArtifactType(), Removable.NO),
	            new TagRecord(CommonVariables.DOWNLOADABLE_ARTIFACT, "true", Removable.NO),
	            new TagRecord(CommonVariables.TAG_FIELD, tag, Removable.NO),
	            new TagRecord(CommonVariables.FILE_NAME_FIELD, file.getFilename(), Removable.NO)
        ));
		}
		
		Artifact art = createArtifact(artifactDto, wu);
		
        return art.getUuid();
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
	
	protected void initialProcessArtifactsOnDependencyTrack (ZonedDateTime lastScanned) {
		List<Artifact> initialArts = repository.listInitialArtifactsPendingOnDependencyTrack();
		log.debug("PSDEBUG: located " + initialArts.size() + " to process initially on dep track");
		initialArts.forEach(a -> fetchDependencyTrackDataForArtifact(a, lastScanned));
	}
	
	public boolean fetchDependencyTrackDataForArtifact (Artifact a, ZonedDateTime lastScanned) {
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
		int processedCount = 0;
		ZonedDateTime lastScanned = ZonedDateTime.now();
		for (Artifact artifact : artifacts) {
			try {
				fetchDependencyTrackDataForArtifact(artifact, lastScanned);
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

}
