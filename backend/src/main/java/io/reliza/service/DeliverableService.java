/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.common.LambdaExceptionWrappers.*;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.tea.TeaArtifactChecksumType;
import io.reliza.common.Utils.StripBom;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.Deliverable;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.PackageType;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.repositories.DeliverableRepository;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
public class DeliverableService {
	
	@Autowired
    private AuditService auditService;
	
	@Autowired
    private BranchService branchService;
	
	@Autowired
    private OrganizationService organizationService;

	@Autowired
    private GetComponentService getComponentService;

	@Autowired 
	private ArtifactService artifactService;
	
	@Autowired 
	private GetDeliverableService getDeliverableService;
	
	@Autowired 
	private SharedReleaseService sharedReleaseService;
	
	@Autowired 
	private AcollectionService acollectionService;
			
	private final DeliverableRepository repository;
	
	DeliverableService(DeliverableRepository repository) {
	    this.repository = repository;
	}
	
	@Transactional
	public Deliverable createDeliverable(DeliverableDto deliverableDto, WhoUpdated wu) throws RelizaException{
		Deliverable d = null;
		if(null == deliverableDto.getType())
			throw new RelizaException("Deliverable must have type!");
		// resolve organization via branch
		Optional<BranchData> bdOpt = branchService.getBranchData(deliverableDto.getBranch());
		if (bdOpt.isPresent()) {
			d = new Deliverable();
			UUID component = bdOpt.get().getComponent();
			UUID orgUuid = getComponentService
										.getComponentData(component)
										.get()
										.getOrg();
			if (null == deliverableDto.getOrg())
				deliverableDto.setOrg(orgUuid);
			
			DeliverableData dd = DeliverableData.deliverableDataFactory(deliverableDto);
			Map<String,Object> recordData = Utils.dataToRecord(dd);
			d = saveDeliverable(d, recordData, wu);
		}
		return d;
	}
	
	@Transactional
	private Deliverable saveDeliverable (Deliverable d, Map<String, Object> recordData, WhoUpdated wu) {
		// TODO: add validation
		Optional<Deliverable> od = getDeliverableService.getDeliverable(d.getUuid());
		if (od.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.DELIVERABLES, d);
			d.setRevision(d.getRevision() + 1);
			d.setLastUpdatedDate(ZonedDateTime.now());
		}
		d.setRecordData(recordData);
		d = (Deliverable) WhoUpdated.injectWhoUpdatedData(d, wu);
		d = repository.save(d);
		DeliverableData dd = DeliverableData.dataFromRecord(d);
		Set<UUID> releaseIds = new HashSet<>();
		var ropt = sharedReleaseService.getReleaseByOutboundDeliverable(dd.getUuid(), dd.getOrg());
		if (ropt.isPresent()) releaseIds.add(ropt.get().getUuid());
		dd.getArtifacts().forEach(a -> {
			var releases = sharedReleaseService.findReleasesByReleaseArtifact(a, dd.getOrg());
			releases.forEach(r -> releaseIds.add(r.getUuid()));
		});
		releaseIds.forEach(r -> acollectionService.resolveReleaseCollection(r, wu));
		return d;
	}
	
	public List<UUID> prepareListofDeliverables(List<Map<String, Object>> deliverablesList,
			UUID branchUUID, String version, WhoUpdated wu) throws RelizaException{
		return prepareListofDeliverables(deliverablesList, branchUUID, version, false, wu);
	}
	
	public List<UUID> prepareListofDeliverables(List<Map<String, Object>> deliverablesList, UUID branchUUID,
			String version, Boolean addOnComplete, WhoUpdated wu) throws RelizaException {
		List<UUID> deliverables = new LinkedList<>();
		
		var bd = branchService.getBranchData(branchUUID).orElseThrow();
		ComponentData cd = getComponentService.getComponentData(bd.getComponent()).orElseThrow();
		OrganizationData od = organizationService.getOrganizationData(bd.getOrg()).orElseThrow();
		for (Map<String, Object> deliverableItem : deliverablesList) {
			//extract arts
			@SuppressWarnings("unchecked")
			var arts = (List<Map<String, Object>>) deliverableItem.get("artifacts");
			deliverableItem.remove("artifacts");
			DeliverableDto deliverableDto = Utils.OM.convertValue(deliverableItem,DeliverableDto.class);
			deliverableDto.cleanLegacyDigests();
			
			String purl = null;
			Optional<TeaIdentifier> purlId = Optional.empty();
			if (null != deliverableDto.getIdentifiers()) purlId = deliverableDto.getIdentifiers().stream().filter(id -> id.getIdType() == TeaIdentifierType.PURL).findFirst();
			if (purlId.isPresent()) purl = purlId.get().getIdValue();
			RebomOptions rebomOptions = new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.DELIVERABLE, deliverableDto.getShaDigest(), StripBom.FALSE, purl);
			var artIds = artifactService.uploadListOfArtifacts(od, arts, rebomOptions, wu);
			deliverableDto.setArtifacts(artIds);			
			// if deliverable with this digest already exists for this org, do not create a new one (only software deliverables)
			List<Deliverable> deliverablesByDigest = new LinkedList<>();
			if (null != branchUUID && null != deliverableDto.getSoftwareMetadata() && 
					null != deliverableDto.getSoftwareMetadata().getDigestRecords() &&
					!deliverableDto.getSoftwareMetadata().getDigestRecords().isEmpty()) {
				deliverableDto.getSoftwareMetadata().getDigestRecords().forEach(dd -> {
					deliverablesByDigest.addAll(getDeliverableService.getDeliverablesByDigestRecord(dd, bd.getOrg()));
				});
			}
			
			if (deliverablesByDigest.isEmpty()) {		
				if (null != deliverableDto.getSoftwareMetadata() && 
						null == deliverableDto.getSoftwareMetadata().getPackageType() && deliverableDto.getType() == CdxType.CONTAINER) {
					var sdm = deliverableDto.getSoftwareMetadata();
					sdm.setPackageType(PackageType.CONTAINER);
					deliverableDto.setSoftwareMetadata(sdm);
				}
				
				deliverableDto.setBranch(branchUUID);
	
				// digests may be not present for failed deliverables / deliverable builds - TODO: think more
				if (StringUtils.isEmpty(deliverableDto.getVersion())) {
					deliverableDto.setVersion(version);
				}
				
				// will provide ability for artifacts to be updated to a release during complete or rejected status
				if (addOnComplete) {
					List<TagRecord> artTags = deliverableDto.getTags();
					if (artTags != null) {
						artTags.add(new TagRecord(CommonVariables.ADDED_ON_COMPLETE, "true", Removable.NO));
					} else {
						artTags = List.of(new TagRecord(CommonVariables.ADDED_ON_COMPLETE, "true", Removable.NO));
					}
					deliverableDto.setTags(artTags);
				}
				
				// if( null != deliverableDto.getBomInputs() && deliverableDto.getBomInputs().size() > 0){
				// 	List<InternalBom> boms = new ArrayList<>(); 
				// 	for(RawBomInput bomInput: deliverableDto.getBomInputs()){
				// 		var entryUUID =  rebomService.uploadSbom(bomInput.rawBom(),  new RebomOptions( cd.getName(), od.getName(), version, bomInput.bomType(), deliverableDto.getShaDigest())).uuid();
				// 		boms.add(new InternalBom(entryUUID, bomInput.bomType()));
				// 	}
				
				// 	// TODO should create artifacts here and set artifacts
				
				// 	// artDto.setInternalBoms(boms);
				// }
				Deliverable d = createDeliverable(deliverableDto, wu);
				deliverables.add(d.getUuid());
			} else {
				throw new RelizaException("A deliverable with this exact digest already belongs to another release, first in list = " + deliverablesByDigest.get(0).getUuid().toString());
			}
		}
		return deliverables;
	}

	public Boolean archiveDeliverable(UUID deliverableId, WhoUpdated wu) {
		Boolean archived = false;
		Optional<Deliverable> deliverable = getDeliverableService.getDeliverable(deliverableId);
		if (deliverable.isPresent()) {
			DeliverableData deliverableData = DeliverableData.dataFromRecord(deliverable.get());
			deliverableData.setStatus(StatusEnum.ARCHIVED);
			Map<String,Object> recordData = Utils.dataToRecord(deliverableData);
			saveDeliverable(deliverable.get(), recordData, wu);
			archived = true;
		}
		return archived;
	}

	@Transactional
	public boolean addArtifact(UUID deliverableId, UUID artifactUuid, WhoUpdated wu) throws RelizaException{
		Deliverable deliverable = getDeliverableService.getDeliverable(deliverableId).get();
		DeliverableData dd = DeliverableData.dataFromRecord(deliverable);
		List<UUID> artifacts = dd.getArtifacts();
		artifacts.add(artifactUuid);
		dd.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(dd);
		saveDeliverable(deliverable, recordData, wu);
		return true;
	}
	@Transactional
	public boolean replaceArtifact(UUID deliverableId, UUID artifactIdToReplace, UUID artifactUuid, WhoUpdated wu) throws RelizaException{
		Deliverable deliverable = getDeliverableService.getDeliverable(deliverableId).get();
		DeliverableData dd = DeliverableData.dataFromRecord(deliverable);
		List<UUID> artifacts = dd.getArtifacts();
		artifacts.remove(artifactIdToReplace);
		artifacts.add(artifactUuid);
		dd.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(dd);
		saveDeliverable(deliverable, recordData, wu);
		return true;
	}
	
	public void saveAll(List<Deliverable> artifacts){
		repository.saveAll(artifacts);
	}
	
	/**
	 * Migrates all deliverables from deprecated digests field to digestRecords field
	 * This method should be run once to migrate existing data
	 */
	@Transactional
	private void migrateDigestsToDigestRecords() {
		Iterable<Deliverable> allDeliverables = repository.findAll();
		int migratedCount = 0;
		
		for (Deliverable deliverable : allDeliverables) {
			DeliverableData dd = DeliverableData.dataFromRecord(deliverable);
			
			if (dd.getSoftwareMetadata() != null) {
				var softwareMetadata = dd.getSoftwareMetadata();
				Set<String> legacyDigests = softwareMetadata.getDigests();
				
				// Only migrate if there are legacy digests and no digest records yet
				if (legacyDigests != null && !legacyDigests.isEmpty() && 
					(softwareMetadata.getDigestRecords() == null || softwareMetadata.getDigestRecords().isEmpty())) {
					
					Set<DigestRecord> digestRecords = new LinkedHashSet<>();
					
					for (String digestString : legacyDigests) {
						String cleanedDigest = io.reliza.common.Utils.cleanString(digestString);
						if (StringUtils.isNotEmpty(cleanedDigest)) {
							String[] digestParts = cleanedDigest.split(":", 2);
							if (digestParts.length == 2) {
								String digestTypeString = digestParts[0];
								String digestValue = digestParts[1];
								
								TeaArtifactChecksumType checksumType = TeaArtifactChecksumType.parseDigestType(digestTypeString);
								if (checksumType != null) {
									digestRecords.add(new DigestRecord(checksumType, digestValue, DigestScope.ORIGINAL_FILE));
								} else {
									// Log and skip unsupported digest types
									log.error("Skipping unsupported digest type: " + digestTypeString + " for deliverable: " + deliverable.getUuid());
								}
							}
						}
					}
					
					if (!digestRecords.isEmpty()) {
						softwareMetadata.setDigestRecords(digestRecords);
						dd.setSoftwareMetadata(softwareMetadata);
						
						Map<String, Object> recordData = io.reliza.common.Utils.dataToRecord(dd);
						saveDeliverable(deliverable, recordData, WhoUpdated.getAutoWhoUpdated());
						migratedCount++;
					}
				}
			}
		}
		
		log.info("Deliverable migration completed. Migrated " + migratedCount + " deliverables from digests to digestRecords.");
	}

}
