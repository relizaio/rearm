/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.CommonVariables.SidPurlMode;
import io.reliza.common.CommonVariables.SidPurlOverride;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.VisibilitySetting;
import io.reliza.common.LogSafe;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.ComponentRevisionConflictException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ResourceGroupData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentLock;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.DeclarativeProvenance;
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
import io.reliza.service.oss.OssPerspectiveService;
import lombok.NonNull;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import tools.jackson.core.JacksonException;


@Service
public class ComponentService {

	@PersistenceContext
	private EntityManager entityManager;
	
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
	private OssPerspectiveService ossPerspectiveService;

	@Autowired
	private io.reliza.repositories.ReleaseRepository releaseRepository;

	// @Lazy breaks a construction cycle: ComponentService -> ComponentOwnershipService
	// -> UserService -> ComponentService. Only used at runtime in updateComponent.
	@Autowired
	@Lazy
	private ComponentOwnershipService componentOwnershipService;

	// @Lazy breaks a construction cycle: ReleaseService reaches components through
	// GetComponentService. Only used at runtime in updateComponent (SCORE-71 ADR-9).
	@Autowired
	@Lazy
	private ReleaseService releaseService;

	// The TEA membership gate of the perspective writers (task TEA-14); @Lazy, as TeaProfileService reads components.
	@Autowired
	@Lazy
	private TeaProfileService teaProfileService;

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

	/** TPF-100: the list rows without the documents; the same branch on type as listComponentsByOrganization. */
	public List<ComponentData.ComponentRef> listComponentRefsByOrganization(UUID orgUuid, ComponentType pt) {
		List<Object[]> rows = (null == pt || pt == ComponentType.ANY)
				? repository.findComponentRefsByOrganization(orgUuid.toString())
				: repository.findComponentRefsByOrganization(orgUuid.toString(), pt.toString());
		return rows.stream().map(ComponentData.ComponentRef::fromRow).toList();
	}
	
	/**
	 * Resolve a component or product of the org by name for programmatic callers
	 * that identify it by name rather than uuid (CLI --component / --product).
	 * Matching is case-insensitive on the trimmed name over the org's active
	 * components and products; exactly one hit is required.
	 *
	 * @throws RelizaException when the name matches nothing or more than one
	 */
	public UUID resolveUniqueComponentUuidByName(UUID orgUuid, String name) throws RelizaException {
		List<ComponentData> candidates = listComponentDataByOrganization(orgUuid, ComponentType.COMPONENT, ComponentType.PRODUCT);
		return pickUniqueByName(candidates, name).getUuid();
	}

	/**
	 * Pure selection behind {@link #resolveUniqueComponentUuidByName}: exactly one
	 * non-archived candidate whose name equals {@code name} ignoring case and
	 * surrounding whitespace.
	 */
	static ComponentData pickUniqueByName(Collection<ComponentData> candidates, String name) throws RelizaException {
		if (StringUtils.isBlank(name)) throw new RelizaException("Component name must not be empty");
		String wanted = name.trim();
		List<ComponentData> hits = candidates.stream()
				.filter(c -> c.getStatus() != StatusEnum.ARCHIVED)
				.filter(c -> null != c.getName() && c.getName().trim().equalsIgnoreCase(wanted))
				.collect(Collectors.toList());
		if (hits.isEmpty()) {
			throw new RelizaException("No active component or product named '" + wanted + "' in this organization");
		}
		if (hits.size() > 1) {
			throw new RelizaException("Component name '" + wanted + "' is ambiguous: " + hits.size()
					+ " active components or products carry it (" + hits.stream().map(c -> c.getUuid().toString()).collect(Collectors.joining(", "))
					+ "); use the uuid");
		}
		return hits.get(0);
	}

	/**
	 * Programmatic component reference: a uuid, or -- for organization-scoped
	 * keys (ORGANIZATION, ORGANIZATION_RW, FREEFORM) -- the unique name of an
	 * active component or product in the org (see {@link #pickUniqueByName}).
	 * Component-scoped keys already identify their component; a name is not
	 * accepted for them. Delegates the uuid case to
	 * {@link Utils#resolveProgrammaticComponentId} so the key / component
	 * mismatch rules are unchanged.
	 */
	public UUID resolveComponentReference(String reference, CommonVariables.AuthHeaderParse ahp, UUID orgUuid) throws RelizaException {
		if (StringUtils.isNotEmpty(reference) && !isUuidReference(reference)) {
			if (null == ahp || ApiTypeEnum.COMPONENT == ahp.getType()) {
				throw new RelizaException("A component name cannot be used with a component-scoped API key; pass the uuid or omit it");
			}
			if (null == orgUuid) {
				throw new RelizaException("Component name lookup requires an organization-scoped API key");
			}
			return resolveUniqueComponentUuidByName(orgUuid, reference);
		}
		return Utils.resolveProgrammaticComponentId(reference, ahp);
	}

	static boolean isUuidReference(String reference) {
		try {
			UUID.fromString(reference.trim());
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
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
		// A member of another organization is dropped, never shown and never thrown on (task TEA-8,
		// D11): one stale foreign row must not deny the organization its own listing.
		List<ComponentData> components = getComponentService.listComponentsByPerspective(perspectiveUuid).stream()
				.filter(cd -> {
					boolean own = orgUuid.equals(cd.getOrg());
					if (!own) log.error("Component {} (UUID: {}) belongs to organization {} but was listed for organization {}: dropped",
							cd.getName(), cd.getUuid(), cd.getOrg(), orgUuid);
					return own;
				}).collect(Collectors.toList());
		
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
	
	/**
	 * Create a component of any kind but BOARD_DOCUMENT. A BOARD_DOCUMENT component is a board's document series
	 * (board-documents.md D1): only the board makes one, through {@link #createBoardDocumentComponent},
	 * so a catalog file, the provider or a form cannot create one that belongs to no board.
	 *
	 * <p>No TEA wiring check (task TEA-7, round 3, T-6): the dto carries no action, rule, ref or
	 * effective policy (approvalPolicy is ignored here). A component created under a name an
	 * organization rule maps to a policy carrying TEA_PUBLISH or TEA_HIDE is enrolled by that rule,
	 * by design: the rule is the organization admin's standing choice. A field added here that
	 * can carry wiring needs the check of OssComponentDataFetcher.authorizeAndStampTeaWiring.
	 */
	public Component createComponent (CreateComponentDto cpd, WhoUpdated wu) throws RelizaException {
		refuseDocumentKind(cpd.getKind());
		return createComponentOfKind(cpd, wu);
	}

	/** The refusal for BOARD_DOCUMENT on create, before anything is written (task fceb1e57 T-5). */
	public static void refuseDocumentKind(ComponentKind kind) throws RelizaException {
		if (ComponentKind.BOARD_DOCUMENT == kind) {
			throw new RelizaException("Boards set the BOARD_DOCUMENT kind: a component cannot be created with it; "
					+ "a board creates its own document components when it publishes");
		}
	}

	/** A board's document component: the one path that creates kind BOARD_DOCUMENT. */
	Component createBoardDocumentComponent (CreateComponentDto cpd, WhoUpdated wu) throws RelizaException {
		if (ComponentKind.BOARD_DOCUMENT != cpd.getKind()) {
			throw new IllegalArgumentException("createBoardDocumentComponent makes BOARD_DOCUMENT components only");
		}
		return createComponentOfKind(cpd, wu);
	}

	private Component createComponentOfKind (CreateComponentDto cpd, WhoUpdated wu) throws RelizaException {
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
			LogSafe.text(cpd.getName()), LogSafe.text(cpd.getVersionSchema()),
			LogSafe.text(cpd.getFeatureBranchVersioning()), cpd.getVersionType());
		
		// The factory cannot refuse, so the declared licence and supplier are validated here,
		// before anything is written, the VCS repository below included (SCORE-39 ADR-1).
		DeclaredComponentFields declared = DeclaredComponentFields.patch(null, cpd.getLicenseExpression(),
				cpd.getLicenseUrl(), cpd.getSupplierName());

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
		declared.applyTo(cd);

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
	
	/**
	 * Record the last declarative apply on a component, in place (TPF-71 / TPF-108): no other field changes,
	 * no revision, no audit row, no who-updated keys. {@code wu} is kept for the callers; the stamp does not
	 * write it (the who-updated keys stay the last content change's).
	 */
	@Transactional
	public void stampDeclarativeProvenance(UUID componentUuid, DeclarativeProvenance provenance, WhoUpdated wu) throws RelizaException {
		Objects.requireNonNull(provenance, "provenance");
		lockedComponentRow(componentUuid).orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		String json;
		try {
			json = Utils.OM.writeValueAsString(provenance);
		} catch (JacksonException e) {
			throw new IllegalStateException("Could not serialize the declarative provenance of component " + componentUuid, e);
		}
		if (repository.stampDeclarativeProvenance(componentUuid, json) == 0) {
			throw new RelizaException("Component not found: " + componentUuid);
		}
		Component m = repository.findById(componentUuid).orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		if (null != entityManager) entityManager.refresh(m); // a fresh read by uuid, then the refresh (TPF-108 4.6)
	}

	/**
	 * A component's declared licence and supplier name after a patch (SCORE-39 ADR-1, ADR-3).
	 */
	record DeclaredComponentFields(String licenseExpression, String licenseUrl, String supplierName) {

		/**
		 * What {@code stored} (null on create) holds after the patch, PATCH: a null value leaves the
		 * stored one alone, a blank one clears it (clearing the expression clears the URL), anything
		 * else is validated, the expression stored in canonical form and the supplier name trimmed. A
		 * URL needs an expression on the component; the supplier name is checked by
		 * {@link SbomSupplierNames#validName}.
		 *
		 * @throws RelizaException naming the field, before anything is changed
		 */
		static DeclaredComponentFields patch(ComponentData stored, String expression, String url,
				String supplierName) throws RelizaException {
			DeclaredLicenses.LicensePatch applied = DeclaredLicenses.patch(
					null == stored ? null : stored.getLicenseExpression(), null == stored ? null : stored.getLicenseUrl(),
					expression, url, "licenseExpression", "licenseUrl");
			String supplier = null == stored ? null : stored.getSupplierName();
			if (null != supplierName) supplier = SbomSupplierNames.validName(supplierName);
			return new DeclaredComponentFields(applied.expression(), applied.url(), supplier);
		}

		void applyTo(ComponentData cd) {
			cd.setLicenseExpression(licenseExpression);
			cd.setLicenseUrl(licenseUrl);
			cd.setSupplierName(supplierName);
		}
	}

	/**
	 * The component row locked FOR UPDATE until the caller's transaction ends, then read fresh
	 * (task TEA-7, round 5, T-13): the read a wiring save checks against. The managed instance is
	 * refreshed because a read earlier in the transaction may have loaded it before the lock was
	 * granted. Empty when the row is gone.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<ComponentData> lockForWiringSave(UUID uuid) {
		return lockAndRefresh(uuid).map(ComponentData::dataFromRecord);
	}

	/**
	 * The row locked through {@link #lockedComponentRow} (TPF-38's bounded wait) and refreshed after:
	 * the one locked read of the funnel and the action-users scrub (task TEA-7, round 8, F-3).
	 */
	private Optional<Component> lockAndRefresh(UUID uuid) {
		return lockedComponentRow(uuid);
	}

	/**
	 * The one writer of a component's name, approval policy and wiring lists (the funnel). Its
	 * first read is the row locked for the save (task TEA-7, round 5, T-13), so every caller holds
	 * the row from the read to the commit, and it saves in {@link WiringWrite#WRITE} mode.
	 */
	@Transactional
	public Component updateComponent (ComponentDto cdto, WhoUpdated wu) throws RelizaException {
		Component comp = null;
		Optional<Component> op = lockAndRefresh(cdto.getUuid());
		if (op.isPresent()) {
			comp = op.get();
			// TPF-61: a save prepared from an older read is refused and nothing is written. Compared with the row
			// lockAndRefresh just locked and refreshed: only there is the stored revision the committed one.
			assertExpectedRevision(comp, cdto.getExpectedRevision());
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
				// BOARD_DOCUMENT marks a board's document series (board-documents.md D1): only the board that
				// creates the component sets it, and nothing turns one kind into the other afterwards.
				if (cdto.getKind() != cd.getKind()
						&& (ComponentKind.BOARD_DOCUMENT == cdto.getKind() || ComponentKind.BOARD_DOCUMENT == cd.getKind())) {
					throw new RelizaException("Boards set the BOARD_DOCUMENT kind: a component's kind cannot be changed to or from it");
				}
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
			// Every uuid a rule names resolves to exactly one action, and every rule uuid to one
			// rule: a list carrying a uuid twice is refused for every caller (task TEA-7, round 3,
			// T-5). This is the one writer of both lists.
			if (null != cdto.getReleaseInputTriggers()) {
				cdto.getReleaseInputTriggers().forEach(t -> {
					if (null == t.getUuid()) t.setUuid(UUID.randomUUID());
				});
				Utils.requireDistinctUuids(cdto.getReleaseInputTriggers().stream().map(ComponentData.ReleaseInputEvent::getUuid)
						.toList(), "Rule", "component");
				cd.setReleaseInputTriggers(cdto.getReleaseInputTriggers());
			}
			if (null != cdto.getOutputTriggers()) {
				cdto.getOutputTriggers().forEach(t -> {
					if (null == t.getUuid()) t.setUuid(UUID.randomUUID());
				});
				Utils.requireDistinctUuids(cdto.getOutputTriggers().stream().map(ComponentData.ReleaseOutputEvent::getUuid)
						.toList(), "Output event", "component");
				cd.setOutputTriggers(cdto.getOutputTriggers());
			}
			if (null != cdto.getGlobalInputEventRefs()) {
				// one ref per global rule (task TEA-7, round 5, rule A extended): the engine and the
				// stamps read refs by the rule's uuid
				Utils.requireDistinctUuids(cdto.getGlobalInputEventRefs().stream().filter(java.util.Objects::nonNull)
						.map(ComponentData.GlobalInputEventRef::getUuid).toList(), "Global rule ref", "component");
				cd.setGlobalInputEventRefs(cdto.getGlobalInputEventRefs());
			}
			if (null != cdto.getStatus()) {
				cd.setStatus(cdto.getStatus());
			}
			if (null != cdto.getIdentifiers()) {
				RearmIdentifier.validateVocabulary(cdto.getIdentifiers());
				cd.setIdentifiers(cdto.getIdentifiers());
			}
			if (null != cdto.getNature()) cd.setNature(cdto.getNature());
			if (null != cdto.getActionGuards()) cd.setActionGuards(cdto.getActionGuards());
			if (null != cdto.getDeviceClass()) {
				// Setting deviceClass to NONE retracts the device window with it. Left behind,
				// the window stays RESOLVABLE -- the resolver reads the profile, not the class
				// -- so a component that is no longer a device would keep stamping a device
				// commitment onto its releases' exports. Recorded as a REMOVED event rather
				// than deleted quietly: withdrawing a regulatory claim is itself a fact.
				retractWindowOnDeviceClassNone(cd, cdto.getDeviceClass(), wu);
				cd.setDeviceClass(cdto.getDeviceClass());
			}
			if (null != cdto.getMedicalProfile()) {
				// CARRY THE WINDOW ACROSS THE PROFILE REPLACEMENT. MedicalProfileInput has no
				// deviceSupportWindow field, so this assignment replaces the whole profile with
				// one that cannot carry it: without this, toggling udiBearing would silently
				// retract a declared regulatory commitment. Exactly the failure
				// ShippedProductService.update guards against on the batch side.
				ComponentData.DeviceSupportWindow carried = (null == cd.getMedicalProfile())
						? null : cd.getMedicalProfile().getDeviceSupportWindow();
				cd.setMedicalProfile(cdto.getMedicalProfile());
				if (null != carried && null == cd.getMedicalProfile().getDeviceSupportWindow()) {
					cd.getMedicalProfile().setDeviceSupportWindow(carried);
				}
			}
			applyDeviceSupportWindowPatch(cd, cdto, wu);
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
			// Read before the patch: a PRODUCT component's supplier change invalidates (SCORE-71 ADR-9).
			String supplierBefore = cd.getSupplierName();
			DeclaredComponentFields.patch(cd, cdto.getLicenseExpression(), cdto.getLicenseUrl(), cdto.getSupplierName())
					.applyTo(cd);
			if (null != cdto.getLeads()) cd.setLeads(cdto.getLeads());
			// Durable owner (RFC Phase 4b): validate the ref synchronously (no DB
			// FK backs it) against the component's org before storing. Null = no
			// change; an invalid ref throws before any save.
			// clearOwner wins over owner: an explicit "remove it" should not be
			// silently ignored because a stale owner object rode along in the
			// same payload.
			if (Boolean.TRUE.equals(cdto.getClearOwner())) {
				cd.setOwner(null);
			} else if (null != cdto.getOwner()) {
				componentOwnershipService.validateOwner(cdto.getOwner(), cd.getOrg());
				cd.setOwner(cdto.getOwner());
			}
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
			comp = saveComponent(comp, componentData, wu, WiringWrite.WRITE);
			// The supplier is in every release's node facts; a nested product row rebuilt alone
			// after it would be written apart in the products above (SCORE-71 ADR-9).
			if (ComponentType.PRODUCT == cd.getType() && !Objects.equals(supplierBefore, cd.getSupplierName())) {
				releaseService.invalidateMergedSbomRowsOfComponent(cd);
			}
		}
		return comp;
	}
	
	/**
	 * {@link #handleRemoveUserFromTriggers} in a transaction of its own, for a caller whose own
	 * write must not depend on it (deactivating a user). Joining the caller's transaction, a
	 * RuntimeException here -- one component of the org that cannot be saved -- would mark that
	 * transaction rollback-only even if the caller catches it, and its commit would fail. Here it
	 * rolls back this org's scrub alone. Call it on the Spring proxy, from another bean: a call
	 * from inside this class would run in the caller's transaction.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void handleRemoveUserFromTriggersInNewTransaction (UUID org, UUID user, final WhoUpdated wu) {
		handleRemoveUserFromTriggers(org, user, wu);
	}

	@Transactional
	public void handleRemoveUserFromTriggers (UUID org, UUID user, final WhoUpdated wu) {
		// The org listing is an unlocked scan for candidates only; the removal is decided again on
		// the row read under its lock, and that fresh copy is what gets saved.
		var comps = listComponentDataByOrganization(org, ComponentType.ANY);
		comps.forEach(cd -> {
			boolean names = null != cd.getOutputTriggers() && cd.getOutputTriggers().stream()
					.anyMatch(t -> null != t && null != t.getUsers() && t.getUsers().contains(user));
			if (names) {
				try {
					removeUserFromActions(cd.getUuid(), user, wu);
				} catch (RelizaException e) {
					log.error("Error updating triggers on user delete for comp = " + cd.getUuid(), e);
				}
			}
		});
	}

	/**
	 * Apply the device support window patch (D7), with the clear flag idiom eos/eol already
	 * use on releases: OMITTED leaves the stored value alone, {@code clearDeviceSupportWindow}
	 * removes it, and an EMPTY OBJECT IS NOT A CLEAR.
	 *
	 * <p>That last rule is the one worth stating. An empty window is a serialization accident
	 * waiting to happen -- a UI that binds two date pickers and posts whatever they hold sends
	 * {@code {}} the moment both are blank, and if that meant "clear" then opening the form and
	 * saving anything else would silently retract a regulatory commitment. Clearing has to be
	 * asked for.
	 *
	 * @throws RelizaException when the window is not valid for this component
	 */
	static void applyDeviceSupportWindowPatch (ComponentData cd, ComponentDto cdto, WhoUpdated wu)
			throws RelizaException {
		if (Boolean.TRUE.equals(cdto.getClearDeviceSupportWindow())) {
			if (null != cd.getMedicalProfile()
					&& null != cd.getMedicalProfile().getDeviceSupportWindow()) {
				recordWindowEvent(cd, cd.getMedicalProfile().getDeviceSupportWindow(), null, wu);
				cd.getMedicalProfile().setDeviceSupportWindow(null);
			}
			return;
		}
		ComponentData.DeviceSupportWindow patch = cdto.getDeviceSupportWindow();
		if (null == patch) return;

		// Only meaningful on a device. Rejected rather than stored-and-ignored: a support
		// window recorded against a component that is not a device would be read back by the
		// resolver, reach a generated document, and describe a device that does not exist.
		if (null == cd.getDeviceClass() || ComponentData.DeviceClass.NONE == cd.getDeviceClass()) {
			throw new RelizaException("A device support window can only be declared on a"
					+ " component with a device class. Set deviceClass first, or clear the"
					+ " window.");
		}
		// A PATCH, MERGED OVER WHAT IS STORED -- not a replacement. eos and eol are
		// independently optional on the input, so posting {eos} alone used to store {eos, null}
		// and silently retract a declared end of sale: a regulatory commitment withdrawn by
		// omission, which is precisely what clearDeviceSupportWindow exists to make explicit.
		// Same rule the batch override follows in ShippedProductService.update.
		ComponentData.DeviceSupportWindow stored = null == cd.getMedicalProfile()
				? null : cd.getMedicalProfile().getDeviceSupportWindow();
		if (null != stored) {
			if (null == patch.getEos()) patch.setEos(stored.getEos());
			if (null == patch.getEol()) patch.setEol(stored.getEol());
		}
		// Checked AFTER the merge, so a patch that moves one date past the stored other is
		// rejected instead of being stored as an inconsistent pair.
		if (null != patch.getEos() && null != patch.getEol()
				&& patch.getEos().isAfter(patch.getEol())) {
			throw new RelizaException("End of support (" + patch.getEos() + ") cannot be after"
					+ " end of life / end of sale (" + patch.getEol() + ").");
		}
		// PROVENANCE IS SERVER-SET, always. The input type deliberately omits assertedBy and
		// source, because a caller that could attribute a regulatory claim to someone else is a
		// caller that can forge one. assessedAt stays caller-supplied so earlier work can be
		// recorded honestly -- the same split the component attestations use -- and defaults to
		// the time of the write, which is what the schema promises.
		patch.setAssertedBy(null == wu ? null : wu.getLastUpdatedBy());
		patch.setSource(null == wu || null == wu.getCreatedType()
				? ProgrammaticType.MANUAL : wu.getCreatedType());
		if (null == patch.getAssessedAt() || patch.getAssessedAt().isBlank()) {
			patch.setAssessedAt(DateTimeFormatter.ISO_INSTANT.format(
					ZonedDateTime.now(ZoneOffset.UTC).toInstant()));
		}
		if (null == cd.getMedicalProfile()) cd.setMedicalProfile(new ComponentData.MedicalProfile());
		ComponentData.DeviceSupportWindow previous = stored;
		recordWindowEvent(cd, previous, patch, wu);
		cd.getMedicalProfile().setDeviceSupportWindow(patch);
	}

	/**
	 * Retract the device window when a component stops being a device.
	 *
	 * <p>Left behind, the window stays RESOLVABLE: {@code DeviceLifecycleResolver} reads the
	 * medical profile, not the device class, so a component that is no longer a device would
	 * keep stamping a device commitment onto its releases' exports. Recorded as a REMOVED event
	 * rather than deleted quietly -- withdrawing a regulatory claim is itself a fact.
	 *
	 * <p>Package-private for the same reason {@code applyDeviceSupportWindowPatch} is: the rule
	 * is worth a test of its own, and burying it inline in updateComponent would leave it
	 * covered only by whatever happened to exercise that method.
	 */
	static void retractWindowOnDeviceClassNone (ComponentData cd, ComponentData.DeviceClass incoming,
			WhoUpdated wu) {
		if (ComponentData.DeviceClass.NONE != incoming) return;
		if (null == cd.getMedicalProfile() || null == cd.getMedicalProfile().getDeviceSupportWindow()) return;
		recordWindowEvent(cd, cd.getMedicalProfile().getDeviceSupportWindow(), null, wu);
		cd.getMedicalProfile().setDeviceSupportWindow(null);
	}

	/** One update event per window change, so the commitment's history is on the record. */
	private static void recordWindowEvent (ComponentData cd, ComponentData.DeviceSupportWindow before,
			ComponentData.DeviceSupportWindow after, WhoUpdated wu) {
		String oldValue = describeWindow(before);
		String newValue = describeWindow(after);
		if (java.util.Objects.equals(oldValue, newValue)) return;
		ComponentData.ComponentUpdateAction action = (null == before)
				? ComponentData.ComponentUpdateAction.ADDED
				: (null == after ? ComponentData.ComponentUpdateAction.REMOVED
						: ComponentData.ComponentUpdateAction.CHANGED);
		cd.addUpdateEvent(new ComponentData.ComponentUpdateEvent(
				ComponentData.ComponentUpdateScope.DEVICE_SUPPORT_WINDOW,
				action, oldValue, newValue, ZonedDateTime.now(ZoneOffset.UTC), wu));
	}

	/** The window as one comparable line. "not declared" is a value, not an absence. */
	private static String describeWindow (ComponentData.DeviceSupportWindow w) {
		if (null == w) return null;
		return "eos=" + (null == w.getEos() ? "null" : w.getEos())
				+ ", eol=" + (null == w.getEol() ? "null" : w.getEol());
	}

	@Transactional
	public Component updateComponentResourceGroup (@NonNull UUID ComponentId, @NonNull UUID appId, WhoUpdated wu) throws RelizaException {
		Component proj = null;
		Optional<Component> op = lockedComponentRow(ComponentId);
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
		Component p = lockedComponentRow(ComponentUuid).get();
		ComponentData pd = ComponentData.dataFromRecord(p);
		pd.setVersionSchema(versionSchema);
		return saveComponent(p, Utils.dataToRecord(pd), wu);
	}
	
	/**
	 * The component's data read under a row write lock, for a caller about to modify a JSONB list
	 * on it.
	 *
	 * <p>The lock belongs before the read the decision is made from, not before the write. Two
	 * concurrent lock releases that each read the list unlocked would both see the lock active,
	 * both write a LOCK_RELEASE attestation, and only then serialize on the overwrite -- a race
	 * with an audit trail claiming it happened twice. Callers hold the lock across the whole
	 * read-check-write because it is their transaction.
	 */
	@Transactional
	public ComponentData getComponentDataWriteLocked(UUID componentUuid) throws RelizaException {
		// The lock statement locks the row; it does not refresh an instance the session already
		// holds. A caller that scanned this row before taking the lock -- which is exactly what
		// looking a lock up by uuid does -- would otherwise get the pre-lock copy handed back and
		// decide from it, which is the race the lock was taken to close. Same idiom as
		// OssReleaseService.addApprovals; lockedComponentRow does both steps.
		Component c = lockedComponentRow(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		return ComponentData.dataFromRecord(c);
	}

	/**
	 * The component row for a read-modify-write: locked, then re-read. The lock statement locks the row
	 * but hands back an instance this persistence context already holds (open-in-view: the request's
	 * authorization read), so the refresh is what makes the writer decide from the row as committed.
	 * Call it inside the writer's transaction, before anything is decided from the row: outside one the
	 * lock ends with its query and the refresh throws. A caller that wrote rows that come before the
	 * component in the lock order (advisory locks; user, board, task, release rows; then the component;
	 * then its branches) flushes them first.
	 *
	 * <p>The lock query is JPQL on Component, so Hibernate's AUTO flush writes this transaction's own
	 * pending component changes before it runs, and the refresh re-reads a row that already holds them
	 * (two writers of one component in one transaction, e.g. a declarative entry's update then stamp).
	 * Plain reads are not blocked: a row lock never blocks a plain SELECT in Postgres.
	 */
	private Optional<Component> lockedComponentRow(UUID componentUuid) {
		Optional<Component> c = repository.findByIdWriteLocked(componentUuid);
		c.ifPresent(entityManager::refresh);
		return c;
	}

	/**
	 * TPF-61: refuse a save prepared from another revision than the stored one. {@code lockedRow} must be
	 * the row {@link #lockAndRefresh} or {@link #lockedComponentRow} returned (both lock the same way):
	 * every committed save adds exactly one to the revision
	 * under that lock, so equality there means nothing changed since the caller read. Null skips the check
	 * (declarative applies, the CLI, the Terraform provider and older pages send none).
	 */
	private static void assertExpectedRevision(Component lockedRow, Integer expectedRevision)
			throws ComponentRevisionConflictException {
		if (null == expectedRevision || expectedRevision.intValue() == lockedRow.getRevision()) return;
		ComponentData stored = ComponentData.dataFromRecord(lockedRow);
		Object actor = lockedRow.getRecordData().get(CommonVariables.LAST_UPDATED_ACTOR_FIELD);
		throw new ComponentRevisionConflictException(expectedRevision, lockedRow.getRevision(),
				stored.getLastUpdatedBy(), stored.getCreatedType(),
				null == actor ? null : UUID.fromString(actor.toString()), lockedRow.getLastUpdatedDate());
	}
	
	/**
	 * Replace the component's lock list. Persistence only: which locks may be raised or released,
	 * and by whom, is {@code ComponentLockService}'s business -- this keeps the row round-trip in
	 * the one place that owns component rows.
	 */
	@Transactional
	public ComponentData setLocks(UUID componentUuid, List<ComponentLock> locks, WhoUpdated wu)
			throws RelizaException {
		Component c = lockedComponentRow(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		ComponentData cd = ComponentData.dataFromRecord(c);
		cd.setLocks(locks == null ? new LinkedList<>() : locks);
		return ComponentData.dataFromRecord(saveComponent(c, Utils.dataToRecord(cd), wu));
	}

	/**
	 * The component's TEA-facing id, minted on the first call and returned unchanged after that
	 * (task TEA-2). Its production caller is the first TEA publication of the component (task
	 * TEA-5). Under the {@code TEA_ID_MINT} transaction lock, then the row refreshed, as
	 * {@code OrganizationService.ensureTeaUuid}. The mint is one conditional single-key statement
	 * (TEA-5 round 4, decision 24): no revision bump, no audit row, never the whole-record funnel,
	 * so it neither erases nor is erased by a concurrent {@link #saveComponent}, which carries the
	 * stored id forward under the row lock.
	 */
	@Transactional
	public UUID ensureTeaUuid(UUID componentUuid, WhoUpdated wu) throws RelizaException {
		repository.lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), componentUuid.toString());
		Component c = repository.findById(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		if (null != entityManager) entityManager.refresh(c);
		UUID existing = ComponentData.dataFromRecord(c).getTeaUuid();
		if (null != existing) return existing;
		UUID teaUuid = UUID.randomUUID();
		int updated;
		try {
			updated = repository.mintTeaUuid(componentUuid, teaUuid.toString());
		} catch (DataIntegrityViolationException e) {
			log.error("The TEA id of component " + componentUuid + " could not be assigned", e);
			throw new RelizaException("the TEA id of component " + componentUuid + " could not be assigned: "
					+ e.getMostSpecificCause().getMessage());
		}
		// the request's or the transaction's managed instance must read the id from here on
		if (null != entityManager) entityManager.refresh(c);
		if (1 == updated) {
			log.info("TEA id minted for component " + componentUuid + ": " + teaUuid);
			return teaUuid;
		}
		UUID stored = ComponentData.dataFromRecord(c).getTeaUuid();
		if (null != stored) return stored;
		throw new RelizaException("Component not found: " + componentUuid);
	}

	/**
	 * Removes the user from the recipients of the component's actions, on the row locked for the
	 * save. The one writer of an action list besides {@link #updateComponent}: it writes the
	 * stored list it read under the lock with only the recipients changed, so it saves in
	 * {@link WiringWrite#WRITE} mode (the stamps of TEA-7 round 5 do not cover recipients).
	 */
	private void removeUserFromActions(UUID componentUuid, UUID user, WhoUpdated wu) throws RelizaException {
		Optional<Component> oc = lockAndRefresh(componentUuid);
		if (oc.isEmpty()) return;
		ComponentData cd = ComponentData.dataFromRecord(oc.get());
		if (null == cd.getOutputTriggers()) return;
		boolean changed = false;
		for (ComponentData.ReleaseOutputEvent t : cd.getOutputTriggers()) {
			if (null != t && null != t.getUsers() && t.getUsers().contains(user)) {
				LinkedHashSet<UUID> cleanedUsers = new LinkedHashSet<>(t.getUsers());
				cleanedUsers.remove(user);
				t.setUsers(cleanedUsers);
				changed = true;
			}
		}
		if (changed) saveComponent(oc.get(), Utils.dataToRecord(cd), wu, WiringWrite.WRITE);
	}

	/**
	 * How a whole-record save treats the five keys {@link #updateComponent} alone writes (task
	 * TEA-7, round 5, T-13): {@code CARRY} writes the stored name, approval policy, actions, rules
	 * and global-rule refs back over the caller's record (a stale copy restores nothing), {@code
	 * WRITE} writes the caller's. Both carry the stored TEA id.
	 */
	enum WiringWrite { CARRY, WRITE }

	/** The keys {@link WiringWrite#CARRY} writes back from the locked row. */
	static final List<String> WIRING_KEYS = List.of(CommonVariables.NAME_FIELD, "approvalPolicy", "outputTriggers",
			"releaseInputTriggers", "globalInputEventRefs");

	@Transactional
	private Component saveComponent (Component p, Map<String,Object> recordData, WhoUpdated wu) throws RelizaException {
		return saveComponent(p, recordData, wu, WiringWrite.CARRY);
	}

	@Transactional
	private Component saveComponent (Component p, Map<String,Object> recordData, WhoUpdated wu, WiringWrite mode)
			throws RelizaException {
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
			recordData = withStoredKeys(recordData, repository.lockRowForUpdate(p.getUuid()), mode);
			auditService.createAndSaveAuditRecord(TableName.COMPONENTS, p);
			p.setRevision(p.getRevision() + 1);
			p.setLastUpdatedDate(ZonedDateTime.now());
		}
		p.setRecordData(recordData);
		p = (Component) WhoUpdated.injectWhoUpdatedData(p, wu);
		return repository.save(p);
	}

	/**
	 * The caller's record with the locked row's TEA id, and in {@link WiringWrite#CARRY} mode its
	 * five wiring keys, in place of the caller's (absent stays absent). A row that is gone leaves
	 * the record as the caller sent it, without a TEA id.
	 */
	@SuppressWarnings("unchecked")
	static Map<String, Object> withStoredKeys(Map<String, Object> recordData, Optional<String> storedJson,
			WiringWrite mode) {
		Map<String, Object> stored = storedJson.map(json -> (Map<String, Object>) Utils.OM.readValue(json, Map.class))
				.orElse(Map.of());
		Object teaUuid = stored.get(CommonVariables.TEA_UUID_FIELD);
		Map<String, Object> out = Utils.withStoredTeaUuid(recordData,
				Optional.ofNullable(null == teaUuid ? null : teaUuid.toString()));
		if (WiringWrite.CARRY == mode && storedJson.isPresent()) {
			for (String key : WIRING_KEYS) {
				if (stored.containsKey(key)) out.put(key, stored.get(key));
				else out.remove(key);
			}
		}
		return out;
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

	/**
	 * Set the component's VCS repository. Only {@code vcs} is taken from the caller: the rest of the
	 * record is the row as committed, read under the row lock, and the fresh data is returned.
	 */
	@Transactional
	public ComponentData updateComponentVcsRepo(ComponentData componentData, UUID vcsUuid, WhoUpdated wu) throws RelizaException {
		// verify that vcs repo exists
		Optional<VcsRepository> vcsRepo = vcsRepositoryService.getVcsRepository(vcsUuid);
		if (vcsRepo.isPresent()) {
			Component c = lockedComponentRow(componentData.getUuid()).get();
			ComponentData fresh = ComponentData.dataFromRecord(c);
			fresh.setVcs(vcsUuid);
			saveComponent(c, Utils.dataToRecord(fresh), wu);
			return fresh;
		}
		return componentData;
	}
	
	@Transactional
	public Boolean archiveComponent(UUID ComponentUuid, WhoUpdated wu) throws RelizaException {
		Boolean archived = false;
		Optional<Component> op = lockedComponentRow(ComponentUuid);
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
		Component p = lockedComponentRow(ComponentUuid).get();
		ComponentData pd = ComponentData.dataFromRecord(p);
		pd.setVisibilitySetting(visibility);
		return saveComponent(p, Utils.dataToRecord(pd), wu);
	}
	
	/**
	 * The creation form: the perspectives of a component a create-in-perspective path has just made
	 * (ComponentDataFetcher, ReleaseDatafetcher, SaasComponentDataFetcher). Not gated: creating into a
	 * PUBLIC perspective is accepted (TEA-9 round 2; a new component has no profile row and no
	 * publication). No membership path of an existing component may call it: those take a
	 * {@link TeaProfileService.TeaCaller} through the gated overloads below (task TEA-14).
	 */
	@Transactional
	public Component setPerspectives (UUID componentUuid, Collection<UUID> perspectiveUuids, WhoUpdated wu) throws RelizaException {
		Component c = lockedComponentRow(componentUuid).get();
		return writePerspectives(c, perspectiveUuids, wu);
	}

	/**
	 * Set the component's perspectives to exactly these, under the row lock: the TEA-8 sweep, then
	 * the TEA gate on the swept set with the caller's standing (task TEA-9, moved under the lock by
	 * TEA-14: an archived component never gains ENABLED or PUBLIC, and a change that makes the
	 * component resolve PUBLIC needs an organization admin), then the write. The gate throws before
	 * anything is written. Plain {@code @Transactional}: the transaction owner above is
	 * {@code rollbackFor = RelizaException.class}.
	 */
	@Transactional
	public Component setPerspectives (UUID componentUuid, Collection<UUID> perspectiveUuids,
			TeaProfileService.TeaCaller caller, WhoUpdated wu) throws RelizaException {
		Component c = lockedComponentRow(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		ComponentData locked = ComponentData.dataFromRecord(c);
		List<UUID> swept = ossPerspectiveService.assignablePerspectiveUuids(locked.getOrg(),
				new LinkedHashSet<>(perspectiveUuids));
		teaProfileService.requireMembershipAllowed(locked, swept, caller);
		return writePerspectives(c, swept, wu);
	}

	/**
	 * Add and remove perspectives on a component, the delta applied to the stored set under the row
	 * lock: a removal or an addition another writer committed first is kept. The TEA gate judges the
	 * set as it will be written ({@code stored + add - remove}, read under the lock) with the
	 * caller's standing, so a concurrent add and remove, or a profile change between a check and the
	 * write, cannot write a set the gate never saw (task TEA-14, decision 3). A missing component, or
	 * a delta that changes nothing, writes nothing and asks no gate. A SYSTEM join that widens the
	 * component to PUBLIC is written with a WARN line, its only trace. Plain {@code @Transactional}:
	 * the transaction owner above is {@code rollbackFor = RelizaException.class}.
	 */
	@Transactional
	public void changePerspectives (UUID componentUuid, Collection<UUID> add, Collection<UUID> remove,
			TeaProfileService.TeaCaller caller, WhoUpdated wu) throws RelizaException {
		Optional<Component> oc = lockedComponentRow(componentUuid);
		if (oc.isEmpty()) return;
		Component c = oc.get();
		ComponentData locked = ComponentData.dataFromRecord(c);
		Set<UUID> stored = locked.getPerspectives();
		Set<UUID> current = new LinkedHashSet<>(null == stored ? Set.of() : stored);
		Set<UUID> next = new LinkedHashSet<>(current);
		if (null != add) next.addAll(add);
		if (null != remove) next.removeAll(remove);
		if (next.equals(current)) return;
		TeaProfileService.TeaExposureChange x = teaProfileService.requireMembershipAllowed(locked, next, caller);
		if (TeaProfileService.TeaCaller.SYSTEM == caller && null != x
				&& TeaProfileService.TeaPublicPath.NONE != x.path()) {
			log.warn("Component {} joins the perspectives of its board and resolves to the PUBLIC TEA profile of {}",
					locked.getUuid(), teaProfileService.sourceLabelOf(x));
		}
		writePerspectives(c, next, wu);
	}

	private Component writePerspectives (Component c, Collection<UUID> perspectiveUuids, WhoUpdated wu) throws RelizaException {
		ComponentData cd = ComponentData.dataFromRecord(c);
		// Sweep (task TEA-8): only assignable perspectives are stored. A deleted (ARCHIVED),
		// foreign or unknown perspective is a stale membership and is dropped on every save,
		// whoever saves; the board writer carries the current set forward, so this is tolerant
		// rather than a refusal (the mutation refuses before it gets here).
		Set<UUID> wanted = new LinkedHashSet<>(perspectiveUuids);
		List<UUID> assignable = ossPerspectiveService.assignablePerspectiveUuids(cd.getOrg(), wanted);
		Set<UUID> stale = new LinkedHashSet<>(wanted);
		stale.removeAll(assignable);
		if (!stale.isEmpty()) {
			log.warn("Component {}: dropping stale perspective memberships {} (deleted, foreign or unknown perspectives)",
					cd.getUuid(), stale);
		}
		cd.setPerspectives(new LinkedHashSet<>(assignable));
		// sidPurl collision check: perspective changes can flip resolver enablement and/or
		// authority segments under ENABLED_FLEXIBLE, so the new perspective set has to
		// pass the same (segments, name) uniqueness gate the create/update paths use.
		checkSidCollision(cd, cd.getUuid());
		return saveComponent(c, Utils.dataToRecord(cd), wu);
	}
	
	/**
	 * Outcome of VCS-based component resolution, modeled as data rather than
	 * exceptions so callers that may CREATE on absence can tell the three cases
	 * apart without catch-based control flow. The prod failure mode this
	 * prevents: a caller holding createComponentIfMissing caught EVERY
	 * resolution exception as "not found" -- including AMBIGUOUS -- so once two
	 * duplicate registrations existed, every CI run minted another (30+
	 * observed, one per run). AMBIGUOUS is an operator problem to surface,
	 * never a license to create component N+1.
	 */
	public static enum ComponentResolutionStatus { FOUND, NOT_FOUND, AMBIGUOUS }

	public static record ComponentResolution(ComponentResolutionStatus status, UUID componentId, String detail) {
		public static ComponentResolution found(UUID componentId) {
			return new ComponentResolution(ComponentResolutionStatus.FOUND, componentId, null);
		}
		public static ComponentResolution notFound(String detail) {
			return new ComponentResolution(ComponentResolutionStatus.NOT_FOUND, null, detail);
		}
		public static ComponentResolution ambiguous(String detail) {
			return new ComponentResolution(ComponentResolutionStatus.AMBIGUOUS, null, detail);
		}
	}

	/**
	 * Tri-state component lookup by VCS repository UUID and repo path within an
	 * organization. Never throws for a resolution outcome.
	 */
	private ComponentResolution componentResolutionByVcsAndPath(UUID vcsUuid, UUID orgUuid, String repoPath) {
		List<Component> components = repository.findAllComponentsByVcsAndPath(vcsUuid.toString(), orgUuid.toString(), repoPath);

		log.debug("Found {} components for vcsUuid={}, orgUuid={}, repoPath={}", components.size(), vcsUuid, orgUuid, repoPath);

		if (components.isEmpty()) {
			return ComponentResolution.notFound(String.format(
				"Component not found for VCS UUID '%s' and repo path '%s'", vcsUuid, repoPath));
		}

		if (components.size() > 1) {
			String componentIds = components.stream()
				.map(c -> c.getUuid().toString())
				.collect(java.util.stream.Collectors.joining(", "));
			log.error("Multiple components found (ambiguous): vcsUuid={}, orgUuid={}, repoPath={}, count={}, componentIds={}", 
				vcsUuid, orgUuid, repoPath, components.size(), componentIds);
			return ComponentResolution.ambiguous(String.format(
				"Multiple components found for VCS UUID '%s' and repo path '%s'. Component IDs: %s. Please use component UUID instead.", 
				vcsUuid, repoPath, componentIds));
		}

		return ComponentResolution.found(components.get(0).getUuid());
	}

	/**
	 * Every live (non-archived) component of the org that is bound to a VCS
	 * repository -- the candidate universe for duplicate-registration repair.
	 */
	public List<Component> listLiveVcsComponentsOfOrg(UUID orgUuid) {
		return repository.findLiveVcsComponentsOfOrg(orgUuid.toString());
	}

	/**
	 * Throwing convenience over {@link #componentResolutionByVcsAndPath} for
	 * callers with no create-on-missing semantics (e.g. versionFeatureSet
	 * override resolution): NOT_FOUND and AMBIGUOUS both surface as errors.
	 */
	public ComponentData findComponentDataByVcsAndPath(UUID vcsUuid, UUID orgUuid, String repoPath) throws RelizaException {
		ComponentResolution resolution = componentResolutionByVcsAndPath(vcsUuid, orgUuid, repoPath);
		if (resolution.status() != ComponentResolutionStatus.FOUND) {
			throw new RelizaException(resolution.detail());
		}
		return getComponentService.getComponentData(resolution.componentId())
				.orElseThrow(() -> new RelizaException("Component " + resolution.componentId() + " not found"));
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
		ComponentResolution resolution = resolveComponentResolutionFromInput(inputMap, authCtx);
		if (resolution.status() == ComponentResolutionStatus.FOUND) return resolution.componentId();
		throw new RelizaException(resolution.detail());
	}

	/**
	 * Tri-state variant of {@link #resolveComponentIdFromInput} for callers that
	 * may CREATE the component when it does not exist (addRelease / getNewVersion
	 * with {@code createComponentIfMissing}). Resolution outcomes come back as
	 * data -- creation is only legitimate on {@code NOT_FOUND}, and {@code
	 * AMBIGUOUS} (duplicate registrations for the same VCS + repoPath) must fail
	 * the call rather than mint another duplicate. Genuine errors that are not
	 * resolution outcomes (wrong key type, VCS/component mismatch) still throw.
	 */
	public ComponentResolution resolveComponentResolutionFromInput(Map<String, Object> inputMap, ProgrammaticAuthContext authCtx) throws RelizaException {
		CommonVariables.AuthHeaderParse ahp = authCtx == null ? null : authCtx.ahp();
		UUID orgUuid = authCtx == null ? null : authCtx.orgUuid();
		String vcsUri = Utils.normalizeVcsUri((String) inputMap.get("vcsUri"));
		String repoPath = (String) inputMap.get("repoPath");

		UUID componentId = resolveComponentReference((String) inputMap.get(CommonVariables.COMPONENT_FIELD), ahp, orgUuid);

		if (null == componentId &&
			!(ApiTypeEnum.ORGANIZATION_RW == ahp.getType()
				|| ApiTypeEnum.ORGANIZATION == ahp.getType()
				|| ahp.isRbacKey())) {
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
			return ComponentResolution.found(componentId);
		}

		// Only attempt VCS-based resolution if componentId is not provided
		if (vcsUri != null) {
			if (orgUuid == null) return ComponentResolution.notFound("Component not found");
			return resolveComponentResolutionByVcsUriAndPath(orgUuid, vcsUri, repoPath);
		}

		return ComponentResolution.notFound("Component not found");
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
		// Full canonical form (scheme, user@, git@, trailing .git, scp colon): the
		// VCS row is stored in this form, and deriving the component NAME from a
		// lesser form leaked '.git' into names (e.g. 'repo.git-alpha').
		String cleanVcsUri = Utils.cleanVcsUri(Utils.normalizeVcsUri(vcsUri));

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
	public ComponentResolution resolveComponentResolutionByVcsUriAndPath(UUID orgUuid, String vcsUri, String repoPath) {
		// Full canonicalization happens inside getVcsRepositoryDataByUri, matching
		// the form rows are stored in (see that method for the prod incident this
		// alignment fixes -- the resolve side used to canonicalize LESS than the
		// create side, so '.git'-suffixed CI URIs never resolved and re-created).
		String cleanVcsUri = Utils.cleanVcsUri(Utils.normalizeVcsUri(vcsUri));

		log.debug("VCS-based component resolution : vcsUri={}, repoPath={}, orgUuid={}", cleanVcsUri, repoPath, orgUuid);

		// Find VCS repository within organization
		Optional<VcsRepositoryData> vcsRepoData = vcsRepositoryService.getVcsRepositoryDataByUri(orgUuid, cleanVcsUri);
		if (!vcsRepoData.isPresent()) {
			log.warn("VCS repository not found : vcsUri={}, orgUuid={}", vcsUri, orgUuid);
			return ComponentResolution.notFound("VCS repository " + cleanVcsUri + " not found in this organization");
		}

		log.debug("VCS repository found : uuid={}, name={}", 
		 vcsRepoData.get().getUuid(), vcsRepoData.get().getName());

		ComponentResolution resolution = componentResolutionByVcsAndPath(vcsRepoData.get().getUuid(), orgUuid, repoPath);
		if (resolution.status() != ComponentResolutionStatus.FOUND) {
			log.error("Component resolution failed : vcsUri={}, repoPath={}, vcsUuid={}, orgUuid={}, error={}",
			 vcsUri, repoPath, vcsRepoData.get().getUuid(), orgUuid, resolution.detail());
		}
		return resolution;
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
