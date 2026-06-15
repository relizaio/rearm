/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import io.reliza.common.CommonVariables.SidPurlMode;
import io.reliza.common.CommonVariables.SidPurlOverride;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.VisibilitySetting;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ResourceGroupData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.DefaultBranchName;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.ProgrammaticAuthContext;
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

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private SidPurlResolver sidPurlResolver;

	@Autowired
	private io.reliza.repositories.ReleaseRepository releaseRepository;

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

	/**
	 * Compute the synthetic {@code effectiveLifecycle} for many components in a single
	 * round-trip. Replaces the per-component release fetch that surfaced as an N+1 in the
	 * {@code Component.effectiveLifecycle} GraphQL resolver (84 components ≈ 3.5s before).
	 *
	 * <p>Bucket logic — unchanged from the legacy per-component path
	 * (see {@code ComponentDataFetcher.effectiveLifecycle}):
	 * <ul>
	 *   <li>If any release has lifecycle &gt; GA, return the min of those (most-recent-only
	 *       post-GA state).</li>
	 *   <li>Else return the max of releases at lifecycle &le; GA (most-advanced pre-or-at-GA
	 *       state).</li>
	 *   <li>Component with no releases (or only releases lacking a lifecycle): key absent
	 *       from the returned map.</li>
	 * </ul>
	 *
	 * <p>Empty input is short-circuited; otherwise one native query of shape
	 * {@code (component_uuid, lifecycle)} runs and the bucketing happens in-process.
	 */
	public Map<UUID, ReleaseLifecycle> effectiveLifecyclesForComponents(Collection<UUID> componentUuids) {
		if (componentUuids == null || componentUuids.isEmpty()) return Map.of();
		List<String> componentUuidsAsStrings = componentUuids.stream()
				.filter(java.util.Objects::nonNull)
				.map(UUID::toString)
				.toList();
		if (componentUuidsAsStrings.isEmpty()) return Map.of();
		List<Object[]> rows = releaseRepository.findReleaseLifecyclesByComponents(componentUuidsAsStrings);
		final int gaOrd = ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal();
		Map<UUID, ReleaseLifecycle> minPostGa = new java.util.HashMap<>();
		Map<UUID, ReleaseLifecycle> maxPreOrAtGa = new java.util.HashMap<>();
		for (Object[] row : rows) {
			UUID componentUuid;
			ReleaseLifecycle lc;
			try {
				componentUuid = UUID.fromString((String) row[0]);
				lc = ReleaseLifecycle.valueOf((String) row[1]);
			} catch (IllegalArgumentException | NullPointerException e) {
				// Skip rows with malformed/unknown lifecycle values rather than throw —
				// the GraphQL response should keep coming for the other components.
				continue;
			}
			if (lc.ordinal() > gaOrd) {
				ReleaseLifecycle existing = minPostGa.get(componentUuid);
				if (existing == null || lc.ordinal() < existing.ordinal()) minPostGa.put(componentUuid, lc);
			} else {
				ReleaseLifecycle existing = maxPreOrAtGa.get(componentUuid);
				if (existing == null || lc.ordinal() > existing.ordinal()) maxPreOrAtGa.put(componentUuid, lc);
			}
		}
		// post-GA bucket wins when present (matches the per-component path: "sub-GA releases
		// ignored once any release has reached GA").
		Map<UUID, ReleaseLifecycle> out = new java.util.HashMap<>();
		for (UUID cu : componentUuids) {
			if (cu == null) continue;
			ReleaseLifecycle post = minPostGa.get(cu);
			if (post != null) {
				out.put(cu, post);
			} else {
				ReleaseLifecycle pre = maxPreOrAtGa.get(cu);
				if (pre != null) out.put(cu, pre);
			}
		}
		return out;
	}

	/**
	 * Convenience: pre-populate the {@code cachedEffectiveLifecycle} field on each ComponentData
	 * so the GraphQL sub-field resolver can read straight from the source object instead of
	 * running the per-component release lookup. Sets {@code effectiveLifecycleCached=true} on
	 * every component so the resolver can distinguish "not cached" from "cached, but null".
	 */
	public void populateEffectiveLifecycle(Collection<ComponentData> components) {
		if (components == null || components.isEmpty()) return;
		Set<UUID> uuids = components.stream()
				.map(ComponentData::getUuid)
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toSet());
		Map<UUID, ReleaseLifecycle> lifecycles = effectiveLifecyclesForComponents(uuids);
		for (ComponentData cd : components) {
			if (cd == null || cd.getUuid() == null) continue;
			cd.setCachedEffectiveLifecycle(lifecycles.get(cd.getUuid()));
			cd.setEffectiveLifecycleCached(true);
		}
	}
	
	public List<ComponentData> listComponentDataByOrganizationAndPerspective(UUID orgUuid, UUID perspectiveUuid, ComponentType... pts) {
		List<ComponentData> components = getComponentService.listComponentsByPerspective(perspectiveUuid);
		
		// Check for organization mismatch
		for (ComponentData cd : components) {
			if (!cd.getOrg().equals(orgUuid)) {
				log.error("Access denied: Component {} (UUID: {}) belongs to organization {} but requested for organization {}", 
						cd.getName(), cd.getUuid(), cd.getOrg(), orgUuid);
				throw new org.springframework.security.access.AccessDeniedException("Access denied");
			}
		}
		
		// Filter by type if needed
		if (pts != null && pts.length > 0) {
			java.util.Set<ComponentType> typeSet = new java.util.HashSet<>(java.util.Arrays.asList(pts));
			if (!typeSet.contains(ComponentType.ANY)) {
				components = components.stream()
						.filter(cd -> typeSet.contains(cd.getType()))
						.collect(Collectors.toList());
			}
		} else {
			// Maintain original behavior: if no types specified, return empty list
			components = new LinkedList<>();
		}
		
		return components;
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
		
		// Validate component name
		if (StringUtils.isBlank(cpd.getName())) {
			throw new RelizaException("Component name cannot be empty");
		}
		
		// Validate version schema configuration
		if (StringUtils.isEmpty(cpd.getVersionSchema())) {
			log.error("Component creation failed: Version schema is required for component '{}'", cpd.getName());
			throw new RelizaException("Version schema is required. Please specify a version schema (e.g., 'semver')");
		}
		
		if (cpd.getType() == ComponentType.COMPONENT && StringUtils.isEmpty(cpd.getFeatureBranchVersioning())) {
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

		validateSidOverrideAgainstOrg(cpd.getOrganization(), cpd.getSidPurlOverride());

		ComponentData cd = ComponentData.componentDataFactory(cpd);
		cd.setUuid(p.getUuid());

		// sidPurl collision check before any side-effecting work (branch creation, save) so
		// a rejected create leaves no orphan rows behind.
		checkSidCollision(cd, null);

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
				String previousName = cd.getName();
				if (!cdto.getName().equals(previousName)) {
					// Auto-record the rename so the component's CLE history
					// surfaces a componentRenamed event at the TEA layer. Done
					// here (not in saveComponent) so non-rename updates don't
					// pollute the audit list. Idempotent — equal-string updates
					// are skipped above.
					cd.addUpdateEvent(new ComponentData.ComponentUpdateEvent(
							ComponentData.ComponentUpdateScope.NAME,
							ComponentData.ComponentUpdateAction.CHANGED,
							previousName,
							cdto.getName(),
							java.time.ZonedDateTime.now(),
							wu));
				}
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
			if (null != cdto.getAuthentication()) {
				cd.setAuthentication(cdto.getAuthentication());
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
				// Setting a per-component policy that differs from what's
				// stored: stale per-rule refs (disables / output-overrides)
				// target the previous policy's rule UUIDs and have no meaning
				// under the new one. Drop them rather than orphan them. This
				// covers manual→manual switches and the org-rule→manual
				// transition (cd.approvalPolicy null prior). The refs block
				// further down will reapply explicitly if cdto carries them.
				if (!cdto.getApprovalPolicy().equals(cd.getApprovalPolicy())) {
					cd.setGlobalInputEventRefs(null);
				}
				cd.setApprovalPolicy(cdto.getApprovalPolicy());
			} else if (Boolean.TRUE.equals(cdto.getClearApprovalPolicy()) && null != cd.getApprovalPolicy()) {
				// Explicit clear path — UI sends this flag when the operator
				// hits the clearable X on the policy dropdown. Drop the refs
				// alongside, since they're tied to the cleared policy's rules.
				cd.setGlobalInputEventRefs(null);
				cd.setApprovalPolicy(null);
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
			if (null != cdto.getGlobalInputEventRefs()) {
				cd.setGlobalInputEventRefs(cdto.getGlobalInputEventRefs());
			}
			if (null != cdto.getStatus()) {
				cd.setStatus(cdto.getStatus());
			}
			if (null != cdto.getIdentifiers()) cd.setIdentifiers(cdto.getIdentifiers());
			if (null != cdto.getNature()) cd.setNature(cdto.getNature());
			if (null != cdto.getDeviceClass()) cd.setDeviceClass(cdto.getDeviceClass());
			if (null != cdto.getMedicalProfile()) cd.setMedicalProfile(cdto.getMedicalProfile());
			if (null != cdto.getBranchSuffixMode()) {
				// INHERIT clears the override (defers to org setting) — store as null for a cleaner record
				cd.setBranchSuffixMode(cdto.getBranchSuffixMode() == io.reliza.common.CommonVariables.BranchSuffixMode.INHERIT
						? null : cdto.getBranchSuffixMode());
			}
			if (null != cdto.getSidPurlOverride()) {
				SidPurlOverride incoming = cdto.getSidPurlOverride();
				// Normalize INHERIT → null so we can compare against the stored value, which
				// the factory and update path both keep null-canonicalized.
				SidPurlOverride normalized = incoming == SidPurlOverride.INHERIT ? null : incoming;
				// Only enforce org-mode policy when the override is actually changing.
				// Otherwise a saved-when-FLEXIBLE component can never be edited again
				// after the org flips to STRICT/DISABLED — the dto sends the stored override
				// on every save, and validateSidOverrideAgainstOrg would reject it.
				if (!java.util.Objects.equals(normalized, cd.getSidPurlOverride())) {
					validateSidOverrideAgainstOrg(cd.getOrg(), incoming);
				}
				cd.setSidPurlOverride(normalized);
			}
			if (null != cdto.getSidAuthoritySegments()) {
				List<String> segments = cdto.getSidAuthoritySegments();
				// Validate non-empty segments (symmetry with applySidPurlPatch and
				// PerspectiveService.updateSidPurl); empty list clears.
				if (!segments.isEmpty()
						&& !segments.equals(cd.getSidAuthoritySegments())) {
					var vr = io.reliza.common.SidPurlUtils.validateAuthoritySegments(segments);
					if (!vr.valid()) {
						throw new RelizaException("sidAuthoritySegments invalid: " + vr.error());
					}
				}
				cd.setSidAuthoritySegments(segments.isEmpty() ? null : segments);
			}
			if (null != cdto.getIsInternal()) cd.setIsInternal(cdto.getIsInternal());
			if (null != cdto.getLeads()) cd.setLeads(cdto.getLeads());
			// Contacts are operator free text rendered back into the team UI, so
			// HTML-sanitize at this chokepoint (covers every updateComponent caller,
			// not just the GraphQL boundary) before they hit the JSONB record.
			if (null != cdto.getContacts()) cd.setContacts(ComponentData.sanitizeContacts(cdto.getContacts()));
			// sidPurl collision check after all field mutations have been applied so the
			// candidate {cd} reflects exactly what would be saved (name, sidPurlOverride,
			// sidAuthoritySegments, isInternal). Pass cd.getUuid() as excludeUuid so the
			// candidate's own pre-update DB row isn't compared against itself.
			checkSidCollision(cd, cd.getUuid());
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
			
			// Archive all branches of this component
			branchService.archiveAllBranchesOfComponent(ComponentUuid, wu);
			
			for (ApiKeyData apiKey : apiKeyService.listApiKeyDataByObjUuidAndType(ComponentUuid, ApiTypeEnum.COMPONENT, pd.getOrg())) {
				apiKeyService.deleteApiKey(apiKey.getUuid(), wu);
			}
			archived = true;
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
	
	@Transactional
	public Component setPerspectives (UUID componentUuid, Collection<UUID> perspectiveUuids, WhoUpdated wu) throws RelizaException {
		Component c = getComponentService.getComponent(componentUuid).get();
		ComponentData cd = ComponentData.dataFromRecord(c);
		cd.setPerspectives(new LinkedHashSet<>(perspectiveUuids));
		// sidPurl collision check: perspective changes can flip resolver enablement and/or
		// authority segments under ENABLED_FLEXIBLE, so the new perspective set has to
		// pass the same (segments, name) uniqueness gate the create/update paths use.
		checkSidCollision(cd, cd.getUuid());
		return saveComponent(c, Utils.dataToRecord(cd), wu);
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
	 * Takes a {@link ProgrammaticAuthContext} so the resolved org is available for VCS-based
	 * lookups even with key types whose auth header does not embed it (FREEFORM).
	 *
	 * @param inputMap Map containing component resolution parameters (vcsUri, repoPath, component)
	 * @param authCtx Programmatic auth context (parsed AHP + resolved org)
	 * @return UUID of the resolved component
	 * @throws RelizaException if component resolution fails
	 */
	public UUID resolveComponentIdFromInput(Map<String, Object> inputMap, ProgrammaticAuthContext authCtx) throws RelizaException {
		CommonVariables.AuthHeaderParse ahp = authCtx == null ? null : authCtx.ahp();
		UUID orgUuid = authCtx == null ? null : authCtx.orgUuid();
		String vcsUri = Utils.normalizeVcsUri((String) inputMap.get("vcsUri"));
		String repoPath = (String) inputMap.get("repoPath");

		UUID componentId = Utils.resolveProgrammaticComponentId((String) inputMap.get(CommonVariables.COMPONENT_FIELD), ahp);

		if (null == componentId &&
			!(ApiTypeEnum.ORGANIZATION_RW == ahp.getType()
				|| ApiTypeEnum.ORGANIZATION == ahp.getType()
				|| ApiTypeEnum.FREEFORM == ahp.getType())) {
			throw new RelizaException("Wrong Key Type");
		}

		// If componentId is already resolved, use it directly
		if (null != componentId) {
			// If vcsUri is also provided, validate it matches the component's VCS
			if (vcsUri != null) {
				Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
				if (ocd.isPresent() && ocd.get().getVcs() != null) {
					// Use the component's organization to look up VCS repository
					UUID componentOrgUuid = ocd.get().getOrg();
					Optional<VcsRepositoryData> vcsRepoData = vcsRepositoryService.getVcsRepositoryDataByUri(componentOrgUuid, vcsUri);
					if (vcsRepoData.isEmpty() || !vcsRepoData.get().getUuid().equals(ocd.get().getVcs())) {
						throw new RelizaException("VCS URI does not match component's VCS repository");
					}
				}
			}
			return componentId;
		}

		// Only attempt VCS-based resolution if componentId is not provided
		if (vcsUri != null) {
			if (orgUuid == null) throw new RelizaException("Component not found");
			ComponentData componentData = resolveComponentByVcsUriAndPath(orgUuid, vcsUri, repoPath);
			return componentData.getUuid();
		}

		throw new RelizaException("Component not found");
	}
	
	/**
	 * Create a component from VCS URI and optional repository path.
	 * This method will auto-create the VCS repository if it doesn't exist.
	 * 
	 * @param orgUuid Organization UUID
	 * @param vcsUri VCS repository URI
	 * @param repoPath Repository path (can be null for root)
	 * @param vcsDisplayName Display name for VCS repository (optional, used when creating new VCS)
	 * @param vcsType VCS type (optional, defaults to GIT)
	 * @param versionSchema Version schema for the component
	 * @param featureBranchVersionSchema Feature branch version schema
	 * @param wu WhoUpdated tracking
	 * @return ComponentData for the newly created component
	 * @throws RelizaException if creation fails
	 */
	@Transactional
	public ComponentData createComponentFromVcsUri(UUID orgUuid, String vcsUri, String repoPath, String vcsDisplayName, VcsType vcsType,
			String versionSchema, String featureBranchVersionSchema, WhoUpdated wu) throws RelizaException {
		return createComponentFromVcsUri(orgUuid, vcsUri, repoPath, vcsDisplayName, vcsType, versionSchema, featureBranchVersionSchema, null, wu);
	}

	public ComponentData createComponentFromVcsUri(UUID orgUuid, String vcsUri, String repoPath, String vcsDisplayName, VcsType vcsType,
			String versionSchema, String featureBranchVersionSchema, String componentNameOverride, WhoUpdated wu) throws RelizaException {
		// Strip username from URI if present (e.g., https://relizaio@dev.azure.com/ -> https://dev.azure.com/)
		String cleanVcsUri = Utils.normalizeVcsUri(vcsUri);

		// Find or create VCS repository
		VcsType effectiveVcsType = (vcsType != null) ? vcsType : VcsType.GIT;
		Optional<VcsRepository> vcsRepo = vcsRepositoryService.getVcsRepositoryByUri(orgUuid, cleanVcsUri, vcsDisplayName, effectiveVcsType, true, wu);
		UUID vcsUuid = vcsRepo.get().getUuid();

		// Create component (use override name if provided, otherwise resolve from VCS URI)
		String componentName = StringUtils.isNotEmpty(componentNameOverride)
			? componentNameOverride
			: resolveComponentNameFromVcsUri(cleanVcsUri, repoPath);
		
		CreateComponentDto cpd = CreateComponentDto.builder()
				.organization(orgUuid)
				.name(componentName)
				.type(ComponentType.COMPONENT)
				.vcs(vcsUuid)
				.repoPath(repoPath)
				.versionSchema(versionSchema)
				.featureBranchVersioning(featureBranchVersionSchema)
				.build();
		
		Component component = createComponent(cpd, wu);
		return ComponentData.dataFromRecord(component);
	}
	
		
	/**
	 * Resolve component name from VCS URI and repoPath.
	 */
	private String resolveComponentNameFromVcsUri(String vcsUri, String repoPath) {
		String[] segments = vcsUri.split("/");
		String componentName = segments[segments.length - 1];
		
		if (StringUtils.isNotEmpty(repoPath) && !".".equals(repoPath)) {
			String[] pathSegments = repoPath.split("/");
			componentName += "-" + pathSegments[pathSegments.length - 1];
		}		
		return componentName;
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

		String cleanVcsUri = Utils.normalizeVcsUri(vcsUri);
		
		log.debug("VCS-based component resolution : vcsUri={}, repoPath={}, orgUuid={}", cleanVcsUri, repoPath, orgUuid);
		
		// Find VCS repository within organization
		Optional<VcsRepositoryData> vcsRepoData = vcsRepositoryService.getVcsRepositoryDataByUri(orgUuid, cleanVcsUri);
		if (!vcsRepoData.isPresent()) {
			log.warn("VCS repository not found : vcsUri={}, orgUuid={}", vcsUri, orgUuid);
			throw new RelizaException("VCS repository " + cleanVcsUri + " not found in this organization");
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

	/**
	 * sidPurl read-side collision check: reject the write if {@code candidate} would, once
	 * saved, resolve to the same {@code (segments, name)} sid identity as some other
	 * active component in the same org. Excludes EXTERNAL components and any component
	 * whose resolver policy is disabled (no collision possible unless both sides would
	 * actually emit a platform sid PURL).
	 *
	 * <p>{@code excludeUuid} is the candidate's own UUID on update/perspective-set so
	 * the candidate doesn't collide with its own pre-save row; pass {@code null} on create.
	 *
	 * <p>Race window is accepted (design §6, item 1) — two concurrent creates against
	 * the same {@code (segments, name)} can both pass this check. Closed later with a
	 * Postgres advisory lock if it leaks in production.
	 */
	private void checkSidCollision(ComponentData candidate, UUID excludeUuid) throws RelizaException {
		if (candidate.getIsInternalOrDefault() != BelongsToOrganization.INTERNAL) {
			return;
		}
		Optional<OrganizationData> orgOpt = getOrganizationService.getOrganizationData(candidate.getOrg());
		if (orgOpt.isEmpty()) {
			return;
		}
		OrganizationData org = orgOpt.get();
		SidPurlResolver.ResolvedSidPolicy candidatePolicy = sidPurlResolver.resolveForComponent(candidate, org);
		if (!candidatePolicy.enabled()) {
			return;
		}
		List<String> candidateSegments = candidatePolicy.authoritySegments();
		String candidateName = candidate.getName();
		if (candidateSegments == null || candidateSegments.isEmpty() || candidateName == null) {
			// Resolver said enabled but produced no segments — orchestrator's
			// validateAuthoritySegments will reject at release-create time. No
			// collision check possible; let the downstream gate surface the error.
			return;
		}

		// Pre-filter siblings by name (cheap string equality) before paying the
		// per-sibling resolve cost. Same-org names that don't match the candidate
		// can never collide on (segments, name).
		List<ComponentData> siblings = listComponentDataByOrganization(candidate.getOrg(), ComponentType.ANY);
		for (ComponentData sibling : siblings) {
			if (excludeUuid != null && excludeUuid.equals(sibling.getUuid())) {
				continue;
			}
			if (sibling.getStatus() != StatusEnum.ACTIVE) {
				continue;
			}
			if (sibling.getIsInternalOrDefault() != BelongsToOrganization.INTERNAL) {
				continue;
			}
			if (!candidateName.equals(sibling.getName())) {
				continue;
			}
			SidPurlResolver.ResolvedSidPolicy siblingPolicy = sidPurlResolver.resolveForComponent(sibling, org);
			if (!siblingPolicy.enabled()) {
				continue;
			}
			if (!candidateSegments.equals(siblingPolicy.authoritySegments())) {
				continue;
			}
			throw new RelizaException("sid PURL collision: component " + sibling.getUuid()
					+ " (\"" + sibling.getName() + "\") already resolves to pkg:sid/"
					+ String.join("/", candidateSegments) + "/" + candidateName
					+ " in this organization. Rename one of the components or change the "
					+ "authority segments to disambiguate.");
		}
	}

	/**
	 * Reject ENABLE/DISABLE component-level overrides unless the org is in
	 * {@link SidPurlMode#ENABLED_FLEXIBLE}. INHERIT/null are always allowed —
	 * they express no override.
	 */
	private void validateSidOverrideAgainstOrg(UUID orgUuid, SidPurlOverride override) throws RelizaException {
		if (override == null || override == SidPurlOverride.INHERIT) {
			return;
		}
		var od = getOrganizationService.getOrganizationData(orgUuid);
		SidPurlMode mode = od.isPresent() && od.get().getSettings() != null
				? od.get().getSettings().getSidPurlModeOrDefault()
				: SidPurlMode.DISABLED;
		if (mode != SidPurlMode.ENABLED_FLEXIBLE) {
			throw new RelizaException("sidPurlOverride=" + override
					+ " requires org sidPurlMode=ENABLED_FLEXIBLE (current: " + mode + ")");
		}
	}
}
