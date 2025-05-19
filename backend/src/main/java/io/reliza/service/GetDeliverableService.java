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
public class GetDeliverableService {
	
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
	
	private static final Logger log = LoggerFactory.getLogger(GetDeliverableService.class);
			
	private final DeliverableRepository repository;
	
	GetDeliverableService(DeliverableRepository repository) {
	    this.repository = repository;
	}
	
	protected Optional<Deliverable> getDeliverable (UUID uuid) {
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
	

	protected List<Deliverable> getDeliverablesByDigest (String digest, UUID orgUuid) {
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

}
