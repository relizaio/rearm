/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
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
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.service.IntegrationService.DependencyTrackUploadResult;
import io.reliza.service.RebomService.BomMediaType;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class SharedArtifactService {
	

	@Autowired
    private AuditService auditService;

    private final String registryHost;
    private final String url;
    private final WebClient webClient;
    private final String ociRepository;
    
	private final ArtifactRepository repository;


    public SharedArtifactService(
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
	
	@Autowired
    private RebomService rebomService;

	public Optional<Artifact> getArtifact (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Mono<ResponseEntity<byte[]>> downloadArtifact(ArtifactData ad) throws Exception{
		Mono<ResponseEntity<byte[]>> monoResponseEntity = null;
        log.info("download artifacts for ad: {}", ad);

		if(null != ad.getInternalBom()){
			String rebom;
			// For versioned BOMs, use the version-specific query -- TODO SPDX
			if(ad.getBomFormat().equals(BomFormat.SPDX)){
				rebom = (rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg(), BomFormat.CYCLONEDX)).toString();
			} else if (ad.getVersion() != null && !ad.getVersion().isEmpty()) {
				try {
					Integer version = Integer.parseInt(ad.getVersion());
					rebom = rebomService.findBomByVersion(ad.getInternalBom().id(), ad.getOrg(), version).toString();
				} catch (NumberFormatException e) {
					// Version is not numeric, fall back to latest
					rebom = rebomService.findBomByIdJson(ad.getInternalBom().id(), ad.getOrg()).toString();
				}
			} else {
				rebom = rebomService.findBomByIdJson(ad.getInternalBom().id(), ad.getOrg()).toString();
			}
			byte[] byteArray = rebom.getBytes();
			ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(byteArray);
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
		String tagValue = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.TAG_FIELD)).findFirst().get().value();
		String mediaType = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.MEDIA_TYPE_FIELD)).findFirst().get().value();
		String fileName = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.FILE_NAME_FIELD)).findFirst().get().value();
		String resolvedFileName = StringUtils.isNotEmpty(fileName) ? fileName : tagValue;
		String ociDigest = ad.getDigestRecords().stream().filter((DigestRecord dr) -> dr.algo().equals(TeaChecksumType.SHA_256) && dr.scope().equals(DigestScope.OCI_STORAGE)).findFirst().orElseThrow().digest();
		return this.webClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/pull")
							.queryParam("registry", this.registryHost)
							.queryParam("repo",this.ociRepository)
							.queryParam("tag", "sha256:" + ociDigest)
							.build()
					)
					.accept(MediaType.APPLICATION_OCTET_STREAM)
					.retrieve()
					.bodyToMono(byte[].class)
					.map(data -> ResponseEntity.ok()
									.contentType(MediaType.parseMediaType(mediaType))
									.header("Content-Disposition", "attachment; filename=\"" + resolvedFileName + "\"")
									.body(data));	
	}
	public Mono<ResponseEntity<byte[]>> downloadRawArtifact(ArtifactData ad) throws Exception{
		Mono<ResponseEntity<byte[]>> monoResponseEntity = null;

		if(null != ad.getInternalBom()){
			String rebom;
			// For versioned BOMs (like SPDX), use the version-specific query
			if (ad.getVersion() != null && !ad.getVersion().isEmpty()) {
				try {
					Integer version = Integer.parseInt(ad.getVersion());
					rebom = rebomService.findRawBomByVersion(ad.getInternalBom().id(), ad.getOrg(), version).toString();
				} catch (NumberFormatException e) {
					// Version is not numeric, fall back to latest
					rebom = rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg()).toString();
				}
			} else {
				rebom = rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg()).toString();
			}
			byte[] byteArray = rebom.getBytes();
			ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(byteArray);
			monoResponseEntity = Mono.just(responseEntity);
		}else {
			monoResponseEntity = downloadRearmNonBomArtifact(ad);
		}
		return monoResponseEntity;
    }

	@Transactional
	protected void updateArtifactFromDtur(ArtifactData ad, DependencyTrackUploadResult dtur) {
		DependencyTrackIntegration dti = ad.getMetrics();
		dti.setDependencyTrackProject(dtur.projectId());
		dti.setUploadToken(dtur.token());
		dti.setProjectName(dtur.projectName());
		dti.setProjectVersion(dtur.projectVersion());
		dti.setDependencyTrackFullUri(dtur.fullProjectUri());
		Artifact a = getArtifact(ad.getUuid()).get();
		updateArtifactDti(a, dti, WhoUpdated.getAutoWhoUpdated());
	}
	
	@Transactional
	protected Artifact updateArtifactDti(Artifact a, DependencyTrackIntegration dti, WhoUpdated wu) {
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		DependencyTrackIntegration existingDti = ad.getMetrics();
		
		if (existingDti != null) {
			// Merge findings: keep only those in new dti, but preserve earlier attributedAt dates
			existingDti.setAttributedAtFallback(a.getCreatedDate());
			existingDti.updateFromAuthoritativeSource(dti);
			// Copy DependencyTrack-specific fields from new dti
			existingDti.setDependencyTrackProject(dti.getDependencyTrackProject());
			existingDti.setUploadToken(dti.getUploadToken());
			existingDti.setProjectName(dti.getProjectName());
			existingDti.setProjectVersion(dti.getProjectVersion());
			existingDti.setDependencyTrackFullUri(dti.getDependencyTrackFullUri());
			existingDti.setLastScanned(dti.getLastScanned());
		} else {
			// No existing metrics - use new dti as-is
			ad.setMetrics(dti);
		}
		
		Map<String,Object> recordData = Utils.dataToRecord(ad);
		return saveArtifact(a, recordData, wu);
	}
	
	
	/**
	 * Saves an artifact without adding a version snapshot.
	 * Used for version history transfers where we don't want to create a new snapshot.
	 */
	@Transactional
	protected Artifact saveArtifactWithoutSnapshot(Artifact a, Map<String, Object> recordData, WhoUpdated wu) {
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
		a = (Artifact) WhoUpdated.injectWhoUpdatedData(a, wu);
		return repository.save(a);
	}

	@Transactional
	protected Artifact saveArtifact (Artifact a, Map<String, Object> recordData, WhoUpdated wu) {
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
		a = (Artifact) WhoUpdated.injectWhoUpdatedData(a, wu);
		return repository.save(a);
	}

	/**
	 * Transfers version history from one artifact to another during replacement scenarios
	 * @param oldArtifactUuid UUID of the artifact being replaced
	 * @param newArtifactUuid UUID of the new artifact that should receive the version history
	 * @param wu WhoUpdated information for audit trail
	 * @return true if transfer was successful, false otherwise
	 */
	@Transactional
	public boolean transferArtifactVersionHistory(UUID oldArtifactUuid, UUID newArtifactUuid, WhoUpdated wu) {
		try {
			Optional<Artifact> oldArtifactOpt = getArtifact(oldArtifactUuid);
			Optional<Artifact> newArtifactOpt = getArtifact(newArtifactUuid);
			
			if (oldArtifactOpt.isPresent() && newArtifactOpt.isPresent()) {
				ArtifactData newArtifact = ArtifactData.dataFromRecord(newArtifactOpt.get());
				ArtifactData oldArtifact = ArtifactData.dataFromRecord(oldArtifactOpt.get());
				
				// Transfer version history from old artifact to new artifact
				newArtifact.transferVersionHistory(oldArtifact);
				
				// Save the updated artifact WITHOUT adding a self-snapshot
				Map<String, Object> artifactRecordData = Utils.dataToRecord(newArtifact);
				saveArtifactWithoutSnapshot(newArtifactOpt.get(), artifactRecordData, wu);
				return true;
			}
			
			return false;
		} catch (Exception e) {
			log.error("Error transferring version history from {} to {}: {}", oldArtifactUuid, newArtifactUuid, e.getMessage());
			return false;
		}
	}
	
}
