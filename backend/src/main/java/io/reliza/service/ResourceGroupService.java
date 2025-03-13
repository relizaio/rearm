/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.model.ResourceGroup;
import io.reliza.model.ResourceGroupData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ResourceGroupRepository;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
public class ResourceGroupService {
	
	@Autowired
    private AuditService auditService;
			
	private final ResourceGroupRepository repository;
	
	ResourceGroupService(ResourceGroupRepository repository) {
	    this.repository = repository;
	}
	
	private Optional<ResourceGroup> getResourceGroup (UUID uuid, UUID org) {
		if (null == uuid) uuid = CommonVariables.DEFAULT_RESOURCE_GROUP;
		return repository.findResourceGroupByIdOrgOrganization(uuid.toString(), org.toString());
	}
	
	public Optional<ResourceGroupData> getResourceGroupData (UUID uuid, UUID org) {
		Optional<ResourceGroupData> oad = Optional.empty();
		var oa = getResourceGroup(uuid, org);
		if (oa.isPresent()) {
			oad = Optional.of(ResourceGroupData.dataFromRecord(oa.get()));
		}else if(uuid == null || uuid.equals(CommonVariables.DEFAULT_RESOURCE_GROUP)){
			oad = Optional.of(ResourceGroupData.defaultResourceGroupData(org));
		}
		return oad;
	}
	
	public List<ResourceGroup> listResourceGroupsOfOrg (UUID org) {
		return repository.findResourceGroupsByOrg(org.toString());
	}

	private List<ResourceGroup> getListOfResourceGroups(List<UUID> uuids) {
		return (List<ResourceGroup>) repository.findAllById(uuids);
	}

	public List<ResourceGroupData> getListOfResourceGroupData(List<UUID> uuids) {
		var resourceGroups = getListOfResourceGroups(uuids);
		return resourceGroups.stream()
				.map(ResourceGroupData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	public List<ResourceGroupData> listResourceGroupDataOfOrg (UUID org) {
		var resourceGroups = listResourceGroupsOfOrg(org);
		var orgResourceGroups = resourceGroups.stream().map(a -> ResourceGroupData.dataFromRecord(a)).collect(Collectors.toList());
		// inject default resource group
		List<ResourceGroupData> retList = new LinkedList<>();
		if(resourceGroups.isEmpty() || !orgResourceGroups.stream().anyMatch(rg -> rg.getUuid().equals(CommonVariables.DEFAULT_RESOURCE_GROUP)))
			retList.add(ResourceGroupData.defaultResourceGroupData(org));
		retList.addAll(orgResourceGroups);
		return retList;
	}
	
	public ResourceGroup createResourceGroup (String name, UUID org, WhoUpdated wu) {
		Map<String,Object> rgData = new HashMap<>();
		rgData.put(CommonVariables.NAME_FIELD, name);
		rgData.put(CommonVariables.ORGANIZATION_FIELD, org.toString());
		ResourceGroup rg = new ResourceGroup();
		rgData.put(CommonVariables.UUID_FIELD, rg.getUuid());
		rg = saveResourceGroup(rg, rgData, wu);
		return rg;
	}

	private ResourceGroup saveResourceGroup (ResourceGroup rg, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		// per schema version 0 we require that schema version 0 has name
		if (null == recordData || recordData.isEmpty() ||  StringUtils.isEmpty((String) recordData.get(CommonVariables.NAME_FIELD))) {
			throw new IllegalStateException("Resource Group must have name in record data");
		}
		
		UUID org = UUID.fromString((String) recordData.get(CommonVariables.ORGANIZATION_FIELD));
		if (null == org) {
			throw new IllegalStateException("Resource Group must have organization set in record data");
		}
		Optional<ResourceGroup> oa = getResourceGroup(rg.getUuid(), org);
		if (oa.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.RESOURCE_GROUPS, rg);
			rg.setRevision(rg.getRevision() + 1);
			rg.setLastUpdatedDate(ZonedDateTime.now());
		}
		rg.setRecordData(recordData);
		rg = (ResourceGroup) WhoUpdated.injectWhoUpdatedData(rg, wu);
		return repository.save(rg);
	}

	public void saveAll(List<ResourceGroup> resourceGroups){
		repository.saveAll(resourceGroups);
	}
	

}
