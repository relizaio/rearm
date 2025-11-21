/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.VisibilitySetting;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ResourceGroupData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.DefaultBranchName;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.repositories.ComponentRepository;
import lombok.NonNull;


@Service
public class ComponentService {
	
	@Autowired
    private AuditService auditService;
	
	@Autowired
    private ResourceGroupService resourceGroupService;
	
	@Autowired
    private BranchService branchService;
	
	@Autowired
    private GetComponentService getComponentService;
	
	@Autowired
    private VcsRepositoryService vcsRepositoryService;
	
	@Autowired
    private ApiKeyService apiKeyService;

	private static final Logger log = LoggerFactory.getLogger(ComponentService.class);
			
	private final ComponentRepository repository;

	ComponentService(
		ComponentRepository repository,
		@Value("${relizaprops.baseuri}") String baseUri
	) {
	    this.repository = repository;
	}
	
	public Optional<Component> getComponentByParent (UUID parentUuid, UUID myorg) {
		return repository.findComponentByParent(parentUuid.toString(), myorg.toString());
	}
	
	public Optional<ComponentData> getComponentDataByParent (UUID parentUuid, UUID myorg) {
		Optional<ComponentData> pData = Optional.empty();
		Optional<Component> p = getComponentByParent(parentUuid, myorg);
		if (p.isPresent()) {
			pData = Optional
							.of(
								ComponentData
									.dataFromRecord(p
										.get()
								));
		}
		return pData;
	}

	public Optional<Component> findComponentByOrgNameType (UUID orgUuid, String ComponentName, ComponentType type) {
		return repository.findComponentByOrgNameType(orgUuid.toString(), ComponentName, type.toString());
	}
	
	public Optional<ComponentData> findComponentDataByOrgNameType (UUID orgUuid, String ComponentName, ComponentType type) {
		Optional<ComponentData> pData = Optional.empty();
		var p = findComponentByOrgNameType(orgUuid, ComponentName, type);
		if (p.isPresent()) {
			pData = Optional
							.of(
								ComponentData
									.dataFromRecord(p
										.get()
								));
		}
		return pData;
	}
	
	private List<Component> listAllComponents() {
		return repository.findAll();
	}
	
	public Collection<ComponentData> listAllComponentData() {
		List<Component> ComponentList = listAllComponents();
		return transformComponentToComponentData(ComponentList);
	}
	
	public List<Component> listComponentsByOrganization(UUID orgUuid, ComponentType pt) {
		List<Component> components = null;
		if (null == pt || pt == ComponentType.ANY) {
			components = repository.findComponentsByOrganization(orgUuid.toString());
		} else {
			components = repository.findComponentsByOrganization(orgUuid
												.toString(), pt.toString());
		}
		return components;
	}
	
	public List<ComponentData> listComponentDataByOrganization(UUID orgUuid, ComponentType... pts) {
		List<ComponentData> globalListOfComponentData = new LinkedList<>();
		for (ComponentType pt: pts) {
			List<Component> componentList = listComponentsByOrganization(orgUuid, pt);
			var pdList = transformComponentToComponentData(componentList);
			globalListOfComponentData.addAll(pdList);
		}
		return globalListOfComponentData;
	}
	
	public List<Component> listComponentsByApprovalPolicy(UUID approvalPolicyUuid) {
		return repository.findComponentsByApprovalPolicy(approvalPolicyUuid.toString());
	}
	
	private List<ComponentData> transformComponentToComponentData (Collection<Component> Components) {
		return Components.stream()
				.map(ComponentData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public Component createComponent (String name, UUID orgUuid, ComponentType pt, String versionSchema, String featureBranchVersioning, UUID vcsRepoUuid, WhoUpdated wu) throws RelizaException {
		var cpd = CreateComponentDto.builder()
					.name(name)
					.organization(orgUuid)
					.type(pt)
					.versionSchema(versionSchema)
					.featureBranchVersioning(featureBranchVersioning)
					.vcs(vcsRepoUuid)
					.kind(ComponentKind.GENERIC)
					.build();
		return createComponent(cpd, wu);
	}
	
	public Component createComponent (CreateComponentDto cpd, WhoUpdated wu) throws RelizaException {
		Component p = new Component();
		
		// Validate version schema configuration
		if (StringUtils.isEmpty(cpd.getVersionSchema())) {
			log.error("Component creation failed: Version schema is required for component '{}'", cpd.getName());
			throw new RelizaException("Version schema is required. Please specify a version schema (e.g., 'semver')");
		}
		
		if (StringUtils.isEmpty(cpd.getFeatureBranchVersioning())) {
			log.error("Component creation failed: Feature branch versioning is required for component '{}'", cpd.getName());
			throw new RelizaException("Feature branch versioning is required. Please specify feature branch versioning (e.g., 'Branch.Micro')");
		}
		
		// Set default version type if not provided
		if (null == cpd.getVersionType()) {
			cpd.setVersionType(VersionTypeEnum.DEV);
			log.debug("Version type not specified, defaulting to DEV for component: {}", cpd.getName());
		}
		
		log.info("Creating component '{}' with versionSchema='{}', featureBranchVersioning='{}', versionType='{}'", 
			cpd.getName(), cpd.getVersionSchema(), cpd.getFeatureBranchVersioning(), cpd.getVersionType());
		
		// validate vcs repository
		if (null != cpd.getVcs()) {
			var vcsRepoOpt = vcsRepositoryService.getVcsRepository(cpd.getVcs());
			UUID vcsOrg = null;
			if (vcsRepoOpt.isPresent()) {
				var vcd = VcsRepositoryData.dataFromRecord(vcsRepoOpt.get());
				vcsOrg = vcd.getOrg();
			}
			if (null == vcsOrg || !vcsOrg.equals(cpd.getOrganization())) {
				log.error("SECURITY: submitted wrong vcs id = " + cpd.getVcs() + " for org = " + cpd.getOrganization() + ", Component name = " + cpd.getName());
				throw new RelizaException("VCS not found");
			}
		}
		if (null == cpd.getVcs() && null != cpd.getVcsRepository() && StringUtils.isNotEmpty(cpd.getVcsRepository().getUri())) {
			VcsRepository vcsrepo = vcsRepositoryService.createVcsRepository(cpd.getVcsRepository().getName(),
					cpd.getOrganization(), cpd.getVcsRepository().getUri(), 
					cpd.getVcsRepository().getType(), wu);
			cpd.setVcs(vcsrepo.getUuid());
		}
		
		if (null == cpd.getKind()) cpd.setKind(ComponentKind.GENERIC);
		
		ComponentData cd = ComponentData.componentDataFactory(cpd);
		cd.setUuid(p.getUuid());
		
		// when creating a Component, always create a base branch
		DefaultBranchName dbn = DefaultBranchName.MAIN;
		if (cpd.getDefaultBranch() != null) {
			dbn = cpd.getDefaultBranch();
		}
		
		// create base branch
		if (cpd.getType() == ComponentType.COMPONENT) {
			branchService.createBranch(dbn.toString().toLowerCase(), cd, BranchType.BASE, null, null, null, null, wu);
		} else {
			branchService.createBranch(CommonVariables.BASE_FEATURE_SET_NAME, cd, BranchType.BASE, null, null, null, null, wu);
		}
		
		Map<String,Object> ComponentData = Utils.dataToRecord(cd);
		return saveComponent(p, ComponentData, wu);
	}
	
	@Transactional
	public Component updateComponent (ComponentDto cdto, WhoUpdated wu) throws RelizaException {
		Component comp = null;
		Optional<Component> op = getComponentService.getComponent(cdto.getUuid());
		if (op.isPresent()) {
			comp = op.get();
			ComponentData cd = ComponentData.dataFromRecord(comp);
			if (StringUtils.isNotEmpty(cdto.getName())) {
				cd.setName(cdto.getName());
			}
			if (null != cdto.getVersionSchema()) {
				cd.setVersionSchema(cdto.getVersionSchema());
			}
			if (null != cdto.getFeatureBranchVersioning()) {
				cd.setFeatureBranchVersioning(cdto.getFeatureBranchVersioning());
			}
			if (null != cdto.getVcs()) {
				cd.setVcs(cdto.getVcs());
			}
			if (null != cdto.getRepoPath()) {
				cd.setRepoPath(cdto.getRepoPath());
			}
			if (null != cdto.getKind()) {
				cd.setKind(cdto.getKind());
			}
			if (null != cdto.getDefaultConfig()) {
				cd.setDefaultConfig(cdto.getDefaultConfig());
			}
			if(null != cdto.getVersionType()){
				cd.setVersionType(cdto.getVersionType());
			}
			if(null != cdto.getMarketingVersionSchema()){
				cd.setMarketingVersionSchema(cdto.getMarketingVersionSchema());
			}
			if (null != cdto.getApprovalPolicy()) {
				cd.setApprovalPolicy(cdto.getApprovalPolicy());
			}
			if (null != cdto.getReleaseInputTriggers()) {
				cdto.getReleaseInputTriggers().forEach(t -> {
					if (null == t.getUuid()) t.setUuid(UUID.randomUUID());
				});
				cd.setReleaseInputTriggers(cdto.getReleaseInputTriggers());
			}
			if (null != cdto.getOutputTriggers()) {
				cdto.getOutputTriggers().forEach(t -> {
					if (null == t.getUuid()) t.setUuid(UUID.randomUUID());
				});
				cd.setOutputTriggers(cdto.getOutputTriggers());
			}
			if (null != cdto.getStatus()) {
				cd.setStatus(cdto.getStatus());
			}
			if (null != cdto.getIdentifiers()) cd.setIdentifiers(cdto.getIdentifiers());
			Map<String,Object> componentData = Utils.dataToRecord(cd);
			comp = saveComponent(comp, componentData, wu);
		}
		return comp;
	}
	
	@Transactional
	public void handleRemoveUserFromTriggers (UUID org, UUID user, final WhoUpdated wu) {
		var comps = listComponentDataByOrganization(org, ComponentType.ANY);
		comps.forEach(cd -> {
			if (null != cd.getOutputTriggers() && !cd.getOutputTriggers().isEmpty()) {
				cd.getOutputTriggers().forEach(t -> {
					if (null != t.getUsers() && !t.getUsers().isEmpty()) {
						LinkedHashSet<UUID> cleanedUsers = new LinkedHashSet<>(t.getUsers());
						if (cleanedUsers.contains(user)) {
							cleanedUsers.remove(user);
							t.setUsers(cleanedUsers);
							Component c = getComponentService.getComponent(cd.getUuid()).get();
							Map<String,Object> recordData = Utils.dataToRecord(cd);
							try {
								saveComponent (c, recordData, wu);
							} catch (RelizaException e) {
								log.error("Error updating triggers on user delete for comp = " + c.getUuid(), e);
							} 
						}
					}
				});
			}
		});
	}
	
	@Transactional
	public Component updateComponentResourceGroup (@NonNull UUID ComponentId, @NonNull UUID appId, WhoUpdated wu) throws RelizaException {
		Component proj = null;
		Optional<Component> op = getComponentService.getComponent(ComponentId);
		if (op.isPresent()) {
			proj = op.get();
			ComponentData pd = ComponentData.dataFromRecord(proj);
			
			// verify that app exists and belongs to same org
			Optional<ResourceGroupData> optApp = Optional.empty();
			if (!CommonVariables.DEFAULT_RESOURCE_GROUP.equals(appId)) {
				optApp = resourceGroupService.getResourceGroupData(appId, pd.getOrg());
			}
			if (!CommonVariables.DEFAULT_RESOURCE_GROUP.equals(appId) && optApp.isEmpty()) {
				throw new RelizaException("Wrong resourceGroup = " + appId);
			} else {
				pd.setResourceGroup(appId);
				Map<String,Object> ComponentData = Utils.dataToRecord(pd);
				proj = saveComponent(proj, ComponentData, wu);
			}
		}
		return proj;
	}
	
	@Transactional
	public Component setComponentVersion (UUID ComponentUuid, String versionSchema, WhoUpdated wu) throws RelizaException {
		Component p = getComponentService.getComponent(ComponentUuid).get();
		ComponentData pd = ComponentData.dataFromRecord(p);
		pd.setVersionSchema(versionSchema);
		return saveComponent(p, Utils.dataToRecord(pd), wu);
	}
	
	@Transactional
	private Component saveComponent (Component p, Map<String,Object> recordData, WhoUpdated wu) throws RelizaException {
		// let's add some validation here
		// per schema version 0 we require that schema version 0 has name and organization
		if (null == recordData || recordData.isEmpty() || 
				null == recordData.get(CommonVariables.NAME_FIELD) ||
				null == recordData.get(CommonVariables.ORGANIZATION_FIELD) ||
				null == recordData.get(CommonVariables.TYPE_FIELD)) {
			throw new IllegalStateException("Component must have name, type and organization in record data");
		}
		
		Optional<Component> op = getComponentService.getComponent(p.getUuid());
		if (op.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.COMPONENTS, p);
			p.setRevision(p.getRevision() + 1);
			p.setLastUpdatedDate(ZonedDateTime.now());
		}
		p.setRecordData(recordData);
		p = (Component) WhoUpdated.injectWhoUpdatedData(p, wu);
		return repository.save(p);
	}

	public void saveAll(List<Component> Components){
		repository.saveAll(Components);
	}
	
	/**
	 * This method aggregates dependencies from all branches of specific Component
	 * and presents them as a single list of Component dependencies of this Component
	 * @param ComponentUuid UUID of the Component for which we are computing dependencies
	 * @return List of ComponentData - those are dependencies of this Component
	 */
	/*
	public List<ComponentData> listComponentDependencies (UUID ComponentUuid) {
		List<ComponentData> dependencyList = new LinkedList<>();
		// First, get collect all branches of the Component
		Collection<BranchData> branchDataList = branchService.listBranchDataOfComponent(ComponentUuid, StatusEnum.ACTIVE);
		Set<UUID> dependencyBranchUuidSet = new LinkedHashSet<>();
		branchDataList.forEach(bd -> {
			dependencyBranchUuidSet.addAll(bd.getComponents());
		});
		
		Iterable<Component> dependencyComponents = repository.findAllById(dependencyUuidSet);
		dependencyComponents.forEach(dp -> {
			ComponentData projData = ComponentData.dataFromRecord(dp);
			dependencyList.add(projData);
		});
		return dependencyList;
	}
	*/
	
	/**
	 * Get Component by sce UUID
	 * @param branchUuid - UUID of sce for which we're locating parent product
	 * @return Optional of Component
	 */
	private Optional<Component> getComponentBySourceCodeEntry (UUID sceUuid) {
		Optional<Component> proj = Optional.empty();
		List<Component> Components = repository.findComponentsBySce(sceUuid.toString());
		if (!Components.isEmpty()) {
			proj = Optional.of(Components.get(0));
		}
		return proj;
	}
	
	public Optional<ComponentData> getComponentDataBySourceCodeEntry (UUID sceUuid) {
		Optional<ComponentData> opd = Optional.empty();
		Optional<Component> proj = getComponentBySourceCodeEntry(sceUuid);
		if (proj.isPresent()) {
			opd = Optional.of(ComponentData.dataFromRecord(proj.get()));
		}
		return opd;
	}

	public ComponentData updateComponentVcsRepo(ComponentData ComponentData, UUID vcsUuid, WhoUpdated wu) throws RelizaException {
		// verify that vcs repo exists
		Optional<VcsRepository> vcsRepo = vcsRepositoryService.getVcsRepository(vcsUuid);
		if (vcsRepo.isPresent()) {
			ComponentData.setVcs(vcsUuid);
			saveComponent(getComponentService.getComponent(ComponentData.getUuid()).get(), Utils.dataToRecord(ComponentData), wu);
		}
		return ComponentData;
	}
	
	public Boolean archiveComponent(UUID ComponentUuid, WhoUpdated wu) throws RelizaException {
		Boolean archived = false;
		Optional<Component> op = getComponentService.getComponent(ComponentUuid);
		if (op.isPresent()) {
			ComponentData pd = ComponentData.dataFromRecord(op.get());
			pd.setStatus(StatusEnum.ARCHIVED);
			Map<String,Object> recordData = Utils.dataToRecord(pd);
			saveComponent(op.get(), recordData, wu);
			
			for (ApiKeyData apiKey : apiKeyService.listApiKeyDataByObjUuidAndType(ComponentUuid, ApiTypeEnum.COMPONENT, pd.getOrg())) {
				apiKeyService.deleteApiKey(apiKey.getUuid(), wu);
			}
		}
		return archived;
	}
	
	public UUID resolveProductIdFromProductString(String inputProduct, UUID org) {
		UUID productId = null;
		if (StringUtils.isNotEmpty(inputProduct)) {
			if (Utils.isStringUuid(inputProduct)) {
				productId = UUID.fromString(inputProduct);
			} else {
				var product = listComponentDataByOrganization(org, ComponentType.PRODUCT).stream()
						.filter(prod -> {
							return prod.getName().equalsIgnoreCase(inputProduct);
						}).findAny().orElse(null);
				if (null == product) {
					product = listComponentDataByOrganization(CommonVariables.EXTERNAL_PROJ_ORG_UUID, ComponentType.PRODUCT).stream()
							.filter(prod -> {
								return prod.getName().equalsIgnoreCase(inputProduct);
							}).findAny().orElse(null);
				}
				if (null != product) productId = product.getUuid();
			}
		}
		return productId;
	}
	
	@Transactional
	public Component setComponentVisibility (UUID ComponentUuid, VisibilitySetting visibility, WhoUpdated wu) throws RelizaException {
		Component p = getComponentService.getComponent(ComponentUuid).get();
		ComponentData pd = ComponentData.dataFromRecord(p);
		pd.setVisibilitySetting(visibility);
		return saveComponent(p, Utils.dataToRecord(pd), wu);
	}
	
	/**
	 * Find component data by VCS repository UUID and repository path within an organization.
	 * Throws RelizaException if component not found or if multiple components match (ambiguous).
	 * 
	 * @param vcsUuid VCS repository UUID
	 * @param orgUuid Organization UUID
	 * @param repoPath Repository path (can be null for root)
	 * @return ComponentData matching the VCS and repoPath
	 * @throws RelizaException if component not found or multiple components found
	 */
	public ComponentData findComponentDataByVcsAndPath(UUID vcsUuid, UUID orgUuid, String repoPath) throws RelizaException {
		List<Component> components = repository.findAllComponentsByVcsAndPath(vcsUuid.toString(), orgUuid.toString(), repoPath);
		
		log.debug("Found {} components for vcsUuid={}, orgUuid={}, repoPath={}", components.size(), vcsUuid, orgUuid, repoPath);
		
		if (components.isEmpty()) {
			throw new RelizaException(String.format(
				"Component not found for VCS UUID '%s' and repo path '%s'", vcsUuid, repoPath));
		}
		
		if (components.size() > 1) {
			String componentIds = components.stream()
				.map(c -> c.getUuid().toString())
				.collect(java.util.stream.Collectors.joining(", "));
			log.error("Multiple components found (ambiguous): vcsUuid={}, orgUuid={}, repoPath={}, count={}, componentIds={}", 
				vcsUuid, orgUuid, repoPath, components.size(), componentIds);
			throw new RelizaException(String.format(
				"Multiple components found for VCS UUID '%s' and repo path '%s'. Component IDs: %s. Please use component UUID instead.", 
				vcsUuid, repoPath, componentIds));
		}
		
		return ComponentData.dataFromRecord(components.get(0));
	}
	
	/**
	 * Resolve component ID from input map, supporting both VCS-based and traditional resolution.
	 * This is a convenience method for GraphQL datafetchers that handles both resolution strategies.
	 * 
	 * @param inputMap Map containing component resolution parameters (vcsUri, repoPath, component)
	 * @param ahp Authentication header parse containing org UUID and API key context
	 * @return UUID of the resolved component
	 * @throws RelizaException if component resolution fails
	 */
	public UUID resolveComponentIdFromInput(Map<String, Object> inputMap, CommonVariables.AuthHeaderParse ahp) throws RelizaException {
		String vcsUri = (String) inputMap.get("vcsUri");
		String repoPath = (String) inputMap.get("repoPath");
		
		UUID componentId = Utils.resolveProgrammaticComponentId((String) inputMap.get(CommonVariables.COMPONENT_FIELD), ahp);
		UUID componentIdFromVcs = null;

		if (null == componentId && ApiTypeEnum.ORGANIZATION_RW != ahp.getType()) {
			throw new RelizaException("Wrong Key Type");
		}
		if (vcsUri != null) {
			ComponentData componentData = resolveComponentByVcsUriAndPath(ahp.getOrgUuid(), vcsUri, repoPath);
			componentIdFromVcs = componentData.getUuid();
		}
		if (null != componentIdFromVcs && null != componentId && !componentIdFromVcs.equals(componentId)) {
			throw new RelizaException("VCS data does not match component");
		} else if (null != componentIdFromVcs) {
			return componentIdFromVcs;
		} else if (null != componentId) {
			return componentId;
		} else {
			throw new RelizaException("Component not found");
		}
	}
	
	/**
	 * Resolve component by VCS URI and repository path within an organization.
	 * This is a comprehensive method that handles VCS lookup and component resolution.
	 * 
	 * @param orgUuid Organization UUID (from API key context)
	 * @param vcsUri VCS repository URI
	 * @param repoPath Repository path (can be null for root)
	 * @return ComponentData matching the VCS URI and repoPath
	 * @throws RelizaException if VCS not found, component not found, or multiple components found
	 */
	public ComponentData resolveComponentByVcsUriAndPath(UUID orgUuid, String vcsUri, String repoPath) throws RelizaException {
		
		log.debug("VCS-based component resolution : vcsUri={}, repoPath={}, orgUuid={}", vcsUri, repoPath, orgUuid);
		
		// Find VCS repository within organization
		Optional<VcsRepositoryData> vcsRepoData = vcsRepositoryService.getVcsRepositoryDataByUri(orgUuid, vcsUri);
		if (!vcsRepoData.isPresent()) {
			log.error("VCS repository not found : vcsUri={}, orgUuid={}", vcsUri, orgUuid);
			throw new RelizaException("VCS repository not found: " + vcsUri);
		}
		
		log.debug("VCS repository found : uuid={}, name={}", 
		 vcsRepoData.get().getUuid(), vcsRepoData.get().getName());
		
		// Find component by VCS UUID + orgUuid + repoPath (org-scoped)
		try {
			ComponentData componentData = findComponentDataByVcsAndPath(vcsRepoData.get().getUuid(), orgUuid, repoPath);
			log.debug("Component resolved : componentId={}, componentName={}, repoPath={}", 
			 componentData.getUuid(), componentData.getName(), repoPath);
			return componentData;
		} catch (RelizaException e) {
			log.error("Component resolution failed : vcsUri={}, repoPath={}, vcsUuid={}, orgUuid={}, error={}", 
			 vcsUri, repoPath, vcsRepoData.get().getUuid(), orgUuid, e.getMessage());
			throw e;
		}
	}
}
