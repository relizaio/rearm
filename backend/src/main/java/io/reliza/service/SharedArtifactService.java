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
import org.springframework.web.reactive.function.client.WebClient;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.tea.TeaArtifactChecksumType;
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
		this.webClient = WebClient.builder().baseUrl(this.url)
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
			String rebom = rebomService.findBomByIdJson(ad.getInternalBom().id(), ad.getOrg()).toString();
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
		String ociDigest = ad.getDigestRecords().stream().filter((DigestRecord dr) -> dr.algo().equals(TeaArtifactChecksumType.SHA_256) && dr.scope().equals(DigestScope.OCI_STORAGE)).findFirst().orElseThrow().digest();
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
			String rebom = rebomService.findRawBomById(ad.getInternalBom().id(), ad.getOrg()).toString();
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
		ad.setMetrics(dti);
		Map<String,Object> recordData = Utils.dataToRecord(ad);
		return saveArtifact(a, recordData, wu);
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
			ArtifactData.ArtifactVersionSnapshot currentSnapshot = ArtifactData.ArtifactVersionSnapshot.fromArtifactData(currentArtifactData);
			
			// Add version snapshot to the new artifact data
			ArtifactData newArtifactData = Utils.OM.convertValue(recordData, ArtifactData.class);
			newArtifactData.addVersionSnapshot(currentSnapshot);
			recordData = Utils.dataToRecord(newArtifactData);
			
			auditService.createAndSaveAuditRecord(TableName.ARTIFACTS, a);
			a.setLastUpdatedDate(ZonedDateTime.now());
		}
		a.setRecordData(recordData);
		a = (Artifact) WhoUpdated.injectWhoUpdatedData(a, wu);
		return repository.save(a);
	}
	
}
