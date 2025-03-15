/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.common.LambdaExceptionWrappers.*;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.Deliverable;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.DeliverableVersionComparator;
import io.reliza.model.DeliverableData.PackageType;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.repositories.DeliverableRepository;



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
	
	private static final Logger log = LoggerFactory.getLogger(DeliverableService.class);
			
	private final DeliverableRepository repository;
	
	DeliverableService(DeliverableRepository repository) {
	    this.repository = repository;
	}
	
	private Optional<Deliverable> getDeliverable (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<DeliverableData> getDeliverableData (UUID uuid) {
		Optional<DeliverableData> dData = Optional.empty();
		Optional<Deliverable> a = getDeliverable(uuid);
		if (a.isPresent()) {
			dData = Optional
							.of(
									DeliverableData
									.dataFromRecord(a
										.get()
								));
		}
		return dData;
	}
	

	private List<Deliverable> getDeliverablesByDigest (String digest, UUID orgUuid) {
		return repository.findDeliverableByDigest(digest, orgUuid.toString());
	}
	
	private List<Deliverable> getDeliverablesByBuildId (String query, UUID orgUuid) {
		return repository.findDeliverableByBuildId(query, orgUuid.toString());
	}
	
	/**
	 * This method attempts to find deliverable by digest within current organization and external project organization
	 * If more than one artifact is returned, we should pick the one with the latest version
	 * I.e. this can happen when same image is pushed into several docker tags
	 * @param digest
	 * @param orgUuid
	 * @return Optional Deliverable which contains this digest
	 */
	public Optional<DeliverableData> getDeliverableDataByDigest (String digest, UUID orgUuid) {
		Optional<DeliverableData> dData = Optional.empty();
		List<Deliverable> dbds = new LinkedList<>();
		if (StringUtils.isNotEmpty(digest)) {
			dbds = getDeliverablesByDigest(digest, orgUuid);
			
			// if list is empty, try public orgs
			if (dbds.isEmpty()) dbds = getDeliverablesByDigest(digest, CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		}
		if (!dbds.isEmpty()) {
			List<DeliverableData> ddList = dbds.stream().map(DeliverableData::dataFromRecord).collect(Collectors.toList());
			if (ddList.size() == 1) {
				dData = Optional.of(ddList.get(0));
			} else {
				log.warn("Multiple deliverables match single digest = " + digest + " !");
				// TODO: we're not currently handling case where component might be different
				DeliverableData sampleData = ddList.get(0);
				String versionSchema = null;
				String versionPin = null;
				if (null != sampleData.getBranch()) {
					BranchData bd = branchService.getBranchData(sampleData.getBranch()).get();
					ComponentData cd = getComponentService.getComponentData(bd.getComponent()).get();
					versionSchema = cd.getVersionSchema();
					versionPin = bd.getVersionSchema();
				}
				
				// TODO: populate version from release if it's not set on artifact
				
				// sort by version and select top one
				ddList.sort(new DeliverableVersionComparator(versionSchema, versionPin));
				dData = Optional.of(ddList.get(0));
			}
		}
		return dData;
	}
	
	public List<DeliverableData> getDeliverableDataByBuildId (String query, UUID orgUuid) {
		return getDeliverablesByBuildId(query, orgUuid).stream().map(DeliverableData::dataFromRecord).collect(Collectors.toList());
	}
	
	public Optional<Deliverable> getDeliverableByDigestAndComponent (String digest, UUID compUuid) {
		return repository.findDeliverableByDigestAndComponent(digest, compUuid.toString());
	}
	
	public Optional<DeliverableData> getDeliverableDataByDigestAndProject (String digest, UUID projectUuid) {
		Optional<DeliverableData> dData = Optional.empty();
		Optional<Deliverable> d = getDeliverableByDigestAndComponent(digest, projectUuid);
		if (d.isPresent()) {
			dData = Optional
							.of(
									DeliverableData
									.dataFromRecord(d
										.get()
								));
		}
		return dData;
	}
	
	public List<Deliverable> getDeliverables (Iterable<UUID> uuids) {
		return (List<Deliverable>) repository.findAllById(uuids);
	}
	
	public List<DeliverableData> getDeliverableDataList (Iterable<UUID> uuids) {
		List<Deliverable> deliverables = getDeliverables(uuids);
		return deliverables.stream().map(DeliverableData::dataFromRecord).collect(Collectors.toList());
	}
	
	public List<Deliverable> listDeliverablesByComponent (UUID component) {
		return repository.listDeliverablesByComponent(component.toString());
	}
	
	public List<Deliverable> listDeliverablesByOrg (UUID org) {
		return repository.listDeliverablesByOrg(org.toString());
	}
	
	public List<DeliverableData> listDeliverableDataByComponent (UUID component) {
		List<Deliverable> deliverables = listDeliverablesByComponent(component);
		return deliverables.stream().map(DeliverableData::dataFromRecord).collect(Collectors.toList());
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
		Optional<Deliverable> od = getDeliverable(d.getUuid());
		if (od.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.DELIVERABLES, d);
			d.setRevision(d.getRevision() + 1);
			d.setLastUpdatedDate(ZonedDateTime.now());
		}
		d.setRecordData(recordData);
		d = (Deliverable) WhoUpdated.injectWhoUpdatedData(d, wu);
		return repository.save(d);
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
		// List<DeliverableDto> deliverableDtos = deliverablesList.stream().map(artMap -> Utils.OM.convertValue(artMap, DeliverableDto.class)).toList();
		// log.info("del dto: {}", deliverableDtos);
		// Boolean typeNotSet = deliverableDtos.stream().anyMatch(deliverableDto -> deliverableDto.getType() == null);
		// if(typeNotSet){
		// 	throw new RelizaException("Deliverables must have type!");
		// }
		deliverablesList.stream().forEach( 
			handlingConsumerWrapper((Map<String, Object> deliverableItem) -> {
			
			//extract arts
			var arts = (List<Map<String, Object>>) deliverableItem.get("artifacts");
			deliverableItem.remove("artifacts");
			DeliverableDto deliverableDto = Utils.OM.convertValue(deliverableItem,DeliverableDto.class);
			deliverableDto.cleanDigests();
			
			List<UUID> artIds = arts.stream().map((Map<String, Object> artMap) -> {
				MultipartFile file = (MultipartFile) artMap.get("file");
				artMap.remove("file");
				// validations
				if(!artMap.containsKey("storedIn") || StringUtils.isEmpty((String)artMap.get("storedIn"))){
					artMap.put("storedIn", "REARM");
				}
				ArtifactDto artDto = Utils.OM.convertValue(artMap, ArtifactDto.class);

				// artDto.setFile(file);
				UUID artId = null;
				try {
					artId = artifactService.uploadArtifact(artDto, od.getUuid(), file.getResource(), new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.DELIVERABLE, deliverableDto.getShaDigest()),wu);
				} catch (Exception e) {
					throw new RuntimeException(e); // Re-throw the exception
				}
				return artId;
			}).filter(Objects::nonNull).toList();
			deliverableDto.setArtifacts(artIds);
			// TODO: for now always create artifacts from programmatic - later add logic to parse digests and uris
			
			// if deliverable with this digest already exists for this org, do not create a new one (only software deliverables)
			List<Deliverable> deliverablesByDigest = new LinkedList<>();
			if (null != branchUUID && null != deliverableDto.getSoftwareMetadata() && 
					null != deliverableDto.getSoftwareMetadata().getDigests() &&
					!deliverableDto.getSoftwareMetadata().getDigests().isEmpty()) {
				deliverableDto.getSoftwareMetadata().getDigests().forEach(dd -> {
					deliverablesByDigest.addAll(getDeliverablesByDigest(dd, bd.getOrg()));
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
	
				// digests may be not present for failed artifacts / artifact builds - TODO: think more
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
			}
		},RelizaException.class));
		return deliverables;
	}

	public Boolean archiveDeliverable(UUID deliverableId, WhoUpdated wu) {
		Boolean archived = false;
		Optional<Deliverable> deliverable = getDeliverable(deliverableId);
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
		Deliverable deliverable = getDeliverable(deliverableId).get();
		DeliverableData dd = DeliverableData.dataFromRecord(deliverable);
		List<UUID> artifacts = dd.getArtifacts();
		artifacts.add(artifactUuid);
		dd.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(dd);
		saveDeliverable(deliverable, recordData, wu);
		return true;
	}
	
	public void saveAll(List<Deliverable> artifacts){
		repository.saveAll(artifacts);
	}

}
