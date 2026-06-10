/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import tools.jackson.core.JacksonException;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
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
import org.springframework.scheduling.annotation.Async;
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

import tools.jackson.databind.JsonNode;

import io.reliza.common.CommonVariables;
import io.reliza.common.HeapPressureGuard;
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
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
import io.reliza.model.BranchData;
import io.reliza.model.DeliverableData;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VariantData;
import io.reliza.repositories.ArtifactLiteRepository;
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
	private final ArtifactLiteRepository liteRepository;

    private final String url;
    private final WebClient webClient;
	private final String registryNamespace;


    public ArtifactService(
		ArtifactRepository repository,
		ArtifactLiteRepository liteRepository,
		@Value("${relizaprops.ociArtifacts.namespace}") String registryNamespace,
        @Value("${relizaprops.ociArtifacts.serviceUrl}") String url
	) {
		this.repository = repository;
		this.liteRepository = liteRepository;
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
	
	/**
	 * Generate monthly OCI repository name for artifact storage rotation.
	 * Format: namespace/downloadable-artifacts-YYYY-MM (e.g., reliza/downloadable-artifacts-2026-03)
	 * Uses UTC timezone to ensure consistency with rebom-backend TypeScript implementation.
	 * 
	 * @return Monthly repository name string with namespace prefix
	 */
	private String getMonthlyRepositoryName() {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		return String.format("%s/downloadable-artifacts-%04d-%02d", 
			this.registryNamespace,
			now.getYear(), 
			now.getMonthValue());
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

	/**
	 * Totals-only read: ArtifactData built from the light view (no per-finding
	 * metric detail arrays). Use on read paths that only need artifact fields +
	 * severity/policy totals to avoid loading the heavy metrics jsonb. For
	 * detail-level needs use {@link #getArtifactData(UUID)}.
	 */
	public Optional<ArtifactData> getArtifactDataLight (UUID uuid) {
		return liteRepository.findById(uuid).map(ArtifactData::fromLite);
	}

	/** Batch totals-only read counterpart to {@link #getArtifactDataList(Iterable)}. */
	public List<ArtifactData> getArtifactDataListLight (Collection<UUID> uuids) {
		if (uuids == null || uuids.isEmpty()) {
			return new LinkedList<>();
		}
		return liteRepository.findByUuidIn(uuids).stream()
				.map(ArtifactData::fromLite)
				.collect(Collectors.toList());
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
	
	/**
	 * Loads artifact identities for the given DTrack projects. Use when the
	 * caller only needs the artifact identities —
	 * materializing the full {@link Artifact} fires Hibernate's JSONB
	 * snapshot deep-copy on the {@code metrics} column for every result,
	 * which has been a heap-pressure source on large sweeps (see PR #92 /
	 * #94 for the equivalent pattern on other batch loops).
	 */
	public List<UUID> listArtifactUuidsByDtrackProjects (Collection<UUID> dtrackProjects) {
		Set<UUID> uniqueDtrackProjects = new HashSet<>(dtrackProjects);
		List<String> dtrackProjectsForQuery = uniqueDtrackProjects.stream().map(x -> x.toString()).toList();
		return repository.findArtifactUuidsByDtrackProjects(dtrackProjectsForQuery);
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
		validateCoverageTypeTags(artifactDto.getTags());
		// EXTERNALLY-stored artifacts must carry at least one
		// downloadLink — without bytes in our blob store AND no link to
		// jump to, the row is unreachable end-to-end. Catch the bad
		// state at write time so the UI never has to render an
		// "external (no link)" placeholder.
		if (artifactDto.getStoredIn() == ArtifactData.StoredIn.EXTERNALLY
				&& (artifactDto.getDownloadLinks() == null || artifactDto.getDownloadLinks().isEmpty())) {
			throw new RelizaException(
					"Artifact with storedIn=EXTERNALLY must carry at least one downloadLink");
		}
		ArtifactData ad = ArtifactData.artifactDataFactory(artifactDto, a.getUuid());
		a = sharedArtifactService.saveArtifact(a, ad, wu);
		return a;
	}

	/**
	 * Updates user-defined (removable) tags on an artifact.
	 * Non-removable (system) tags are preserved and cannot be modified.
	 * @param artifactUuid UUID of the artifact to update
	 * @param newTags list of new user-defined tags to set
	 * @param wu who updated
	 * @return updated Artifact
	 * @throws RelizaException if artifact not found or validation fails
	 */
	@Transactional
	public Artifact updateArtifactTags(UUID artifactUuid, List<TagRecord> newTags, WhoUpdated wu) throws RelizaException {
		Optional<Artifact> oa = sharedArtifactService.getArtifact(artifactUuid);
		if (oa.isEmpty()) {
			throw new RelizaException("Artifact not found: " + artifactUuid);
		}
		Artifact a = oa.get();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		
		// Preserve non-removable (system) tags and collect their keys
		List<TagRecord> preservedTags = new ArrayList<>();
		Set<String> reservedKeys = new HashSet<>();
		if (ad.getTags() != null) {
			for (TagRecord tag : ad.getTags()) {
				if (tag.removable() == Removable.NO) {
					preservedTags.add(tag);
					reservedKeys.add(tag.key());
				}
			}
		}
		
		// Add new user-defined tags with Removable.YES, rejecting keys used by system tags
		if (newTags != null) {
			for (TagRecord tag : newTags) {
				if (reservedKeys.contains(tag.key())) {
					throw new RelizaException("Tag key '" + tag.key() + "' is reserved for system use and cannot be set by users");
				}
				preservedTags.add(new TagRecord(tag.key(), tag.value(), Removable.YES));
			}
		}
		
		validateCoverageTypeTags(preservedTags);
		ad.setTags(preservedTags);
		return sharedArtifactService.saveArtifact(a, ad, wu);
	}

	/**
	 * Validates that any tags with key COVERAGE_TYPE have valid ArtifactCoverageType values.
	 * @param tags list of tags to validate
	 * @throws RelizaException if any COVERAGE_TYPE tag has an invalid value
	 */
	public void validateCoverageTypeTags(List<TagRecord> tags) throws RelizaException {
		if (tags == null) return;
		for (TagRecord tag : tags) {
			if (CommonVariables.ARTIFACT_COVERAGE_TYPE_TAG_KEY.equals(tag.key())) {
				if (CommonVariables.ArtifactCoverageType.get(tag.value()) == null) {
					throw new RelizaException("Invalid artifact coverage type: " + tag.value() 
						+ ". Valid values are: DEV, TEST, BUILD_TIME");
				}
			}
		}
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
			sharedArtifactService.saveArtifact(artifact.get(), artifactData, wu);
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
			if (artifactDto.getBomFormat() == null && formatResolution.bomFormat() != null) {
				artifactDto.setBomFormat(formatResolution.bomFormat());
				log.info("Auto-detected bomFormat={} from specVersion={}", formatResolution.bomFormat(), formatResolution.specVersion());
			} else if (artifactDto.getBomFormat() != null) {
				log.info("bomFormat parameter is deprecated and will be removed in a future version - format is now auto-detected from file content");
			}
		}
		
		validateBomSpecVersion(artifactDto);

		if(artifactDto.getStoredIn().equals(StoredIn.REARM)){
			if(isRebomStoreable(artifactDto)){
				artifactUploadResponse = storeArtifactOnRebom(artifactDto, file, existingAd, rebomOptions);
			} else {
				artifactUploadResponse = storeArtifactOnRearmDirect(artifactDto, file, existingAd);
			}
		}
		
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
			
			// Store OCI repository name for monthly rotation support (downloadable artifacts only)
			if(StringUtils.isNotEmpty(artifactUploadResponse.getOciRepositoryName())){
				artifactDto.setOciRepositoryName(artifactUploadResponse.getOciRepositoryName());
			}

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
			// Extract lifecycle phases from CycloneDX metadata and add as document-declared tags
			List<TagRecord> lifecycleTags = extractCycloneDxLifecycles(bomJson);
			if (!lifecycleTags.isEmpty()) {
				List<TagRecord> existingTags = artifactDto.getTags() != null ? new ArrayList<>(artifactDto.getTags()) : new ArrayList<>();
				existingTags.addAll(lifecycleTags);
				artifactDto.setTags(existingTags);
				log.info("Extracted {} lifecycle phase(s) from CycloneDX metadata for artifact {}", 
					lifecycleTags.size(), artifactDto.getUuid());
			}
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
			log.warn("BOM lifecycle processing failed for artifact {}: {}", 
				artifactDto.getUuid(), e.getMessage());
			// Unwrap RuntimeException to get the actual cause message (e.g. from rebom GraphQL errors)
			Throwable cause = e;
			while (cause.getCause() != null && cause.getCause() != cause) {
				cause = cause.getCause();
			}
			throw new RelizaException("BOM processing failed: " + cause.getMessage());
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
	 * Extracts lifecycle phases from CycloneDX metadata.lifecycles.
	 * Supports both standard phases (phase field) and custom phases (name field).
	 * Returns a list of TagRecords with LIFECYCLE_DECLARED key and Removable.NO.
	 * @param bomJson the parsed CycloneDX BOM JSON
	 * @return list of lifecycle declared tags (may be empty)
	 */
	private List<TagRecord> extractCycloneDxLifecycles(JsonNode bomJson) {
		List<TagRecord> lifecycleTags = new ArrayList<>();
		JsonNode metadata = bomJson.get("metadata");
		if (metadata == null) return lifecycleTags;
		JsonNode lifecycles = metadata.get("lifecycles");
		if (lifecycles == null || !lifecycles.isArray()) return lifecycleTags;
		for (JsonNode lc : lifecycles) {
			String phase = null;
			if (lc.has("phase") && !lc.get("phase").isNull()) {
				phase = lc.get("phase").asText();
			} else if (lc.has("name") && !lc.get("name").isNull()) {
				phase = lc.get("name").asText();
			}
			if (StringUtils.isNotEmpty(phase)) {
				lifecycleTags.add(new TagRecord(
					CommonVariables.ARTIFACT_LIFECYCLE_DECLARED_TAG_KEY, phase, Removable.NO));
			}
		}
		return lifecycleTags;
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
		
		// For SPDX artifacts, we want to display original SPDX file metadata to users,
		// not the converted CycloneDX metadata. The OCI response contains info about
		// the converted BOM, but we override it with original file info for user display.
		// This matches CycloneDX behavior where we show raw BOM metadata, not augmented.
		OASResponseDto response = rebomResponse.bom();
		if (response == null) {
			log.error("rebomResponse.bom() is null for SPDX artifact UUID: {}", artifactDto.getUuid());
			throw new RelizaException("BOM processing failed: rebom response missing OCI storage information. " +
				"This may indicate a rebom-backend storage error.");
		}
		
		// Override OCI response with original SPDX file metadata for user-facing display
		// (The converted CycloneDX is stored internally, but users should see their original file info)
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
		
		// Generate monthly repository name for storage rotation
		String monthlyRepoName = getMonthlyRepositoryName();
		
        formData.add("repo", monthlyRepoName);
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
		
		// Store just the repository name (without namespace) for downloadable artifact storage
		// Extract repository name from full path (e.g., "reliza/downloadable-artifacts-2026-03" -> "downloadable-artifacts-2026-03")
		// Since getMonthlyRepositoryName() always returns "namespace/repo-name" format, extract after first slash
		String repoNameOnly = monthlyRepoName.contains("/") 
			? monthlyRepoName.substring(monthlyRepoName.indexOf('/') + 1) 
			: monthlyRepoName;
		artifactUploadResponse.setOciRepositoryName(repoNameOnly);
		
		return artifactUploadResponse;
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
		var signatureAD = getArtifactDataListLight(ad.getArtifacts()).stream().filter(a -> a.getType() == ArtifactType.SIGNATURE).findFirst();
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
					sharedArtifactService.saveArtifactMetrics(oa.get(), dti);
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
	 * UUIDs of artifacts whose JSONB metrics contain the given vulnerability finding.
	 * Returns UUIDs only — the sole caller ({@code VulnAnalysisUpdateService})
	 * passes each UUID straight to {@link #computeArtifactMetrics(UUID)}, so
	 * materializing the full Artifact (with its JSONB metrics snapshot
	 * deep-copy) would be pure waste and a heap-pressure liability when a
	 * popular CVE affects many artifacts org-wide.
	 */
	public List<UUID> findArtifactUuidsWithVulnerability(UUID orgUuid, String location, String findingId) {
		return repository.findArtifactsWithVulnerability(orgUuid.toString(), location, findingId);
	}

	/** UUIDs of artifacts with a matching violation finding. See {@link #findArtifactUuidsWithVulnerability}. */
	public List<UUID> findArtifactUuidsWithViolation(UUID orgUuid, String location, String findingId) {
		return repository.findArtifactsWithViolation(orgUuid.toString(), location, findingId);
	}

	/** UUIDs of artifacts with a matching weakness finding. See {@link #findArtifactUuidsWithVulnerability}. */
	public List<UUID> findArtifactUuidsWithWeakness(UUID orgUuid, String location, String findingId) {
		return repository.findArtifactsWithWeakness(orgUuid.toString(), location, findingId);
	}
	
	/**
	 * Result of artifact format resolution containing serialization format and optional spec version
	 */
	public record ArtifactFormatResolution(SerializationFormat serializationFormat, SpecVersion specVersion, BomFormat bomFormat) {}
	
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
		
		BomFormat bomFormat = deriveBomFormatFromSpecVersion(specVersion);
		return new ArtifactFormatResolution(serializationFormat, specVersion, bomFormat);
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
			
		} catch (JacksonException e) {
			log.warn("Could not parse JSON for spec version resolution", e);
		}
		
		return null;
	}
	
	/**
	 * Detects BomFormat (CYCLONEDX or SPDX) from raw BOM string content.
	 * Returns null if format cannot be determined.
	 */
	public BomFormat detectBomFormat(String bomContent) {
		if (bomContent == null) return null;
		SpecVersion specVersion = resolveSpecVersionFromJson(bomContent.trim());
		return deriveBomFormatFromSpecVersion(specVersion);
	}

	/**
	 * Derives BomFormat from a detected SpecVersion.
	 * Returns null if the SpecVersion does not map to a known BOM format (e.g. SARIF, CSAF).
	 */
	private BomFormat deriveBomFormatFromSpecVersion(SpecVersion specVersion) {
		if (null != specVersion) {
			if (CYCLONEDX_SPEC_VERSIONS.contains(specVersion)) {
				return BomFormat.CYCLONEDX;
			} else if (SPDX_SPEC_VERSIONS.contains(specVersion)) {
				return BomFormat.SPDX;
			}
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
	

}
