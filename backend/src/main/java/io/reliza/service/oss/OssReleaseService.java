/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.CommonVariables.ReleaseEventType;
import io.reliza.common.CommonVariables.SidPurlMode;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.Utils.UuidDiff;
import io.reliza.common.ValidationResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ConditionGroup;
import io.reliza.model.GenericReleaseData;
import io.reliza.model.OrganizationData;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.ReleaseData.UpdateReleaseStrength;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.AssignmentTypeEnum;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.AcollectionService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuditService;
import io.reliza.service.BranchService;
import io.reliza.service.DependencyPatternService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.NotificationService;
import io.reliza.dto.ChangelogRecords.CommitRecord;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.VariantService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.versioning.Version.VersionHelper;
import io.reliza.versioning.VersionApi;
import io.reliza.versioning.VersionApi.ActionEnum;
import lombok.extern.slf4j.Slf4j;
import io.reliza.versioning.VersionUtils;

@Slf4j
@Service
public class OssReleaseService {
	
	@Autowired
	private NotificationService notificationService;
	
	@Autowired
	private AuditService auditService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private DependencyPatternService dependencyPatternService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private VersionAssignmentService versionAssignmentService;
	
	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;
	
	@Autowired
	private VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	private VariantService variantService;
		
	@Autowired
	private AcollectionService acollectionService;

	@Autowired
	private ArtifactService artifactService;
			
	@Autowired
	@Lazy
	private SbomComponentService sbomComponentService;

	@Autowired
	@Lazy
	private OssPullRequestAggregatorService pullRequestAggregatorService;

	private final ReleaseRepository repository;
	
	OssReleaseService(ReleaseRepository repository) {
		this.repository = repository;
	}

	public enum AcollectionMode { RESOLVE, SKIP }

	@Transactional
	public Release saveRelease (Release r, ReleaseData rd, WhoUpdated wu) {
		return saveRelease(r, rd, wu, true, AcollectionMode.RESOLVE);
	}

	@Transactional
	public Release saveRelease (Release r, ReleaseData rd, WhoUpdated wu, boolean considerTriggers) {
		return saveRelease(r, rd, wu, considerTriggers, AcollectionMode.RESOLVE);
	}

	@Transactional
	public Release saveRelease (Release r, ReleaseData rd, WhoUpdated wu, AcollectionMode acollectionMode) {
		return saveRelease(r, rd, wu, true, acollectionMode);
	}

	@Transactional
	public Release saveRelease (Release r, ReleaseData rd, WhoUpdated wu, boolean considerTriggers, AcollectionMode acollectionMode) {
		return saveRelease(r, Utils.dataToRecord(rd), rd, wu, considerTriggers, acollectionMode);
	}

	@Transactional
	public Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu) {
		return saveRelease (r, recordData, null, wu, true, AcollectionMode.RESOLVE);
	}
			
	@Transactional
	public Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu, boolean considerTriggers) {
		return saveRelease (r, recordData, null, wu, considerTriggers, AcollectionMode.RESOLVE);
	}
	
	@Transactional
	public Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu, AcollectionMode acollectionMode) {
		return saveRelease (r, recordData, null, wu, true, acollectionMode);
	}
	
	@Transactional
	public Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu, boolean considerTriggers, AcollectionMode acollectionMode) {
		return saveRelease(r, recordData, null, wu, considerTriggers, acollectionMode);
	}

	@Transactional
	public Release saveRelease (Release r, Map<String,Object> recordData, ReleaseData rd, WhoUpdated wu, boolean considerTriggers, AcollectionMode acollectionMode) {
		// let's add some validation here
		// per schema version 0 we require that schema version 0 has name and project
		if (null == recordData || recordData.isEmpty() ||  null == recordData.get(CommonVariables.VERSION_FIELD)) {
			throw new IllegalStateException("Release must have record data");
		}
		
		Release rValidate = new Release();
		rValidate.setRecordData(recordData);
		ReleaseData rdValidated = ReleaseData.dataFromRecord(rValidate);
		
		ValidationResult vr = ReleaseData.validateReleaseData(rdValidated);
		if (!vr.isValid()) {
			throw new IllegalStateException(vr.getSingleStringError());
		}
		Optional<Release> or = sharedReleaseService.getRelease(r.getUuid());
		if (or.isPresent()) {
			r.setRevision(r.getRevision() + 1);
			r.setLastUpdatedDate(ZonedDateTime.now());
			auditService.createAndSaveAuditRecord(TableName.RELEASES, r);
		}
		r.setRecordData(recordData);
		if (rd != null) {
			r.setMetrics(rd.getMetrics() != null ? Utils.OM.convertValue(rd.getMetrics(), LinkedHashMap.class) : null);
			r.setApprovalEvents(rd.getApprovalEvents() != null
				? rd.getApprovalEvents().stream().map(e -> (Map<String,Object>) Utils.OM.convertValue(e, LinkedHashMap.class)).toList()
				: null);
			r.setUpdateEvents(rd.getUpdateEvents() != null
				? rd.getUpdateEvents().stream().map(e -> (Map<String,Object>) Utils.OM.convertValue(e, LinkedHashMap.class)).toList()
				: null);
		}
		log.debug("setting release recordData:{}", recordData);
		r = (Release) WhoUpdated.injectWhoUpdatedData(r, wu);
		r = repository.save(r);
		if (acollectionMode == AcollectionMode.RESOLVE) acollectionService.resolveReleaseCollection(r.getUuid(), wu);
		try {
			ReleaseData savedRd = ReleaseData.dataFromRecord(r);
			pullRequestAggregatorService.recomputeForReleaseSce(savedRd, wu);
		} catch (Exception e) {
			log.warn("PR aggregator recompute failed after release save {}: {}", r.getUuid(), e.getMessage());
		}
		return r;
	}
	
	public Optional<ReleaseData> getReleasePerProductComponent (UUID orgUuid, UUID projectUuid, UUID productUuid, 
				String branch) throws RelizaException {
					return getReleasePerProductComponent(orgUuid, projectUuid, productUuid, branch, ReleaseLifecycle.ASSEMBLED);
		}
	
	public Optional<ReleaseData> getReleasePerProductComponent (UUID orgUuid, UUID componentUuid, UUID productUuid, 
			String branch, ReleaseLifecycle lifecycle) throws RelizaException {
		return getReleasePerProductComponent(orgUuid, componentUuid, productUuid, branch, lifecycle, null);
	}
	/**
	 * 
	 * @param orgUuid needed for the case if we're dealing with external org project - we need to supply our org here
	 * @param componentUuid
	 * @param productUuid
	 * @param branch
	 * @param et
	 * @param status
	 * @return
	 * @throws RelizaException
	 */
	public Optional<ReleaseData> getReleasePerProductComponent (UUID orgUuid, UUID componentUuid, UUID productUuid, 
				String branch, ReleaseLifecycle lifecycle, ConditionGroup cg) throws RelizaException {
		Optional<ReleaseData> retRd = Optional.empty();
		Optional<ReleaseData> optRd = Optional.empty();

		UUID projectOrProductToResolve = (null == productUuid) ? componentUuid : productUuid;

		Optional<Branch> b = branchService.findBranchByName(projectOrProductToResolve, branch);
		
		if (b.isPresent()) {
			optRd = sharedReleaseService.getReleaseDataOfBranch(orgUuid, b.get().getUuid(), lifecycle);
		}
		
		if (optRd.isPresent() && null == productUuid) {
			retRd = optRd;
		} else if (optRd.isPresent()) {
			List<ReleaseData> components = sharedReleaseService.unwindReleaseDependencies(optRd.get())
											.stream()
											.collect(Collectors.toList());
			Iterator<ReleaseData> componentIter = components.iterator();
			while (retRd.isEmpty() && componentIter.hasNext()) {
				ReleaseData rd = componentIter.next();
				if (componentUuid.equals(rd.getComponent())) {
					retRd = Optional.of(rd);
				}
			}
		}
		return retRd;
	}
	
	/**
	 * Get the latest release before the specified version.
	 * If no release with upToVersion exists, throws RelizaException.
	 */
	public Optional<ReleaseData> getReleaseBeforeVersion(UUID orgUuid, UUID componentUuid, UUID productUuid,
			String branch, ReleaseLifecycle lifecycle, ConditionGroup cg, String upToVersion) throws RelizaException {
		
		UUID componentOrProductToResolve = (null == productUuid) ? componentUuid : productUuid;
		Optional<Branch> b = branchService.findBranchByName(componentOrProductToResolve, branch);
		
		if (b.isEmpty()) {
			return Optional.empty();
		}
		
		BranchData bd = BranchData.branchDataFromDbRecord(b.get());
		ComponentData cd = getComponentService.getComponentData(bd.getComponent()).get();
		
		// Get all releases on the branch sorted by version (descending - latest first)
		List<GenericReleaseData> releases = sharedReleaseService
				.listReleaseDataOfBranch(bd.getUuid(), orgUuid, lifecycle, SharedReleaseService.DEFAULT_NUM_RELEASES);
		releases.sort(new ReleaseData.ReleaseVersionComparator(cd.getVersionSchema(), bd.getVersionSchema()));
		
		// Find the release with upToVersion
		int upToVersionIndex = -1;
		for (int i = 0; i < releases.size(); i++) {
			if (upToVersion.equals(releases.get(i).getVersion())) {
				upToVersionIndex = i;
				break;
			}
		}
		
		if (upToVersionIndex == -1) {
			// upToVersion not found - fall back to latest release behavior
			return getReleasePerProductComponent(orgUuid, componentUuid, productUuid, branch, lifecycle, cg);
		}
		
		// Get the release immediately after in the sorted list (which is the previous version)
		if (upToVersionIndex + 1 >= releases.size()) {
			return Optional.empty(); // No release before upToVersion
		}
		
		ReleaseData previousRelease = (ReleaseData) releases.get(upToVersionIndex + 1);
		
		// Handle product case - find component release within product release
		if (null != productUuid) {
			List<ReleaseData> components = sharedReleaseService.unwindReleaseDependencies(previousRelease)
					.stream()
					.collect(Collectors.toList());
			for (ReleaseData rd : components) {
				if (componentUuid.equals(rd.getComponent())) {
					return Optional.of(rd);
				}
			}
			return Optional.empty();
		}
		
		return Optional.of(previousRelease);
	}
	public void processRelease (UUID releaseId) {
	}
	@Transactional
	public Release updateReleaseLifecycle (UUID releaseId, ReleaseLifecycle newLifecycle, WhoUpdated wu) {
		return updateReleaseLifecycle(releaseId, newLifecycle, wu, true);
	}
	
	@Transactional
	public Release updateReleaseLifecycle (UUID releaseId, ReleaseLifecycle newLifecycle, WhoUpdated wu, boolean considerTriggers) {
		Release r = sharedReleaseService.getRelease(releaseId).get();
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		ReleaseLifecycle oldLifecycle = rd.getLifecycle();
		rd.setLifecycle(newLifecycle);
		ReleaseUpdateEvent rue = new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED, oldLifecycle.name(),
				newLifecycle.name(), null, ZonedDateTime.now(), wu);
		rd.addUpdateEvent(rue);
		r = saveRelease(r, rd, wu, considerTriggers);
		ReleaseData savedRd = ReleaseData.dataFromRecord(r);
		processReleaseLifecycleEvents(savedRd, newLifecycle, oldLifecycle);
		if (newLifecycle.ordinal() > oldLifecycle.ordinal()
				&& newLifecycle.ordinal() <= ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal()) {
			cascadeLifecycleToComponents(savedRd, newLifecycle, wu, new HashSet<>());
		}
		return r;
	}

	/**
	 * Bulk-advance every release of {@code componentUuid} whose lifecycle
	 * is {@code ≥ GENERAL_AVAILABILITY && < targetLifecycle} to
	 * {@code targetLifecycle}. Pre-GA releases (PENDING / DRAFT / ASSEMBLED
	 * / READY_TO_SHIP) and terminal-state releases (CANCELLED / REJECTED)
	 * stay where they are, as do releases already at or beyond the target.
	 *
	 * <p>Each bumped release goes through the standard
	 * {@link #updateReleaseLifecycle(UUID, ReleaseLifecycle, WhoUpdated)}
	 * path so the LIFECYCLE-CHANGED update event is recorded normally and
	 * any downstream lifecycle-event triggers fire. Caller transaction
	 * boundary is honoured by the @Transactional on this method — partial
	 * failure rolls all bumps back together.
	 *
	 * <p>Caller validates that {@code targetLifecycle.ordinal() >
	 * GENERAL_AVAILABILITY.ordinal()} (ordinal-bounded so future enum
	 * additions don't need a code change). GA itself isn't a meaningful
	 * bulk target.
	 *
	 * @return the UUIDs of the releases that were actually bumped
	 */
	@Transactional
	public List<UUID> bulkUpdateComponentReleaseLifecycle(UUID componentUuid, ReleaseLifecycle targetLifecycle, WhoUpdated wu) {
		final int gaOrdinal = ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal();
		final int targetOrdinal = targetLifecycle.ordinal();
		List<UUID> bumped = new LinkedList<>();
		var releases = sharedReleaseService.listReleaseDatasOfComponent(componentUuid, 1000, 0);
		for (var rd : releases) {
			ReleaseLifecycle lc = rd.getLifecycle();
			if (lc == null) continue;
			int lcOrdinal = lc.ordinal();
			// Eligible: at-or-past GA, but strictly below the target.
			// Pre-GA, CANCELLED, REJECTED all sit below GA in the enum
			// ordering, so the lower bound naturally excludes them.
			if (lcOrdinal < gaOrdinal || lcOrdinal >= targetOrdinal) continue;
			updateReleaseLifecycle(rd.getUuid(), targetLifecycle, wu);
			bumped.add(rd.getUuid());
		}
		return bumped;
	}

	private void cascadeLifecycleToComponents(ReleaseData rd, ReleaseLifecycle newLifecycle, WhoUpdated wu, Set<UUID> visited) {
		if (!visited.add(rd.getUuid())) return;
		if (rd.getParentReleases() == null || rd.getParentReleases().isEmpty()) return;
		for (ParentRelease pr : rd.getParentReleases()) {
			Optional<ReleaseData> ocrd = sharedReleaseService.getReleaseData(pr.getRelease());
			if (ocrd.isEmpty()) continue;
			ReleaseData crd = ocrd.get();
			if (crd.getLifecycle().ordinal() < newLifecycle.ordinal()) {
				Release updated = updateReleaseLifecycle(crd.getUuid(), newLifecycle, wu, false);
				crd = ReleaseData.dataFromRecord(updated);
			}
			cascadeLifecycleToComponents(crd, newLifecycle, wu, visited);
		}
	}
	
	/**
	 * Fills identifiers on releases that have none. Releases that already carry any
	 * identifier are left alone; v1 does not productize a "rebuild sid on identified
	 * releases" flow.
	 */
	@Transactional
	public ReleaseData updateReleaseIdentifiersFromComponent (ReleaseData rd, WhoUpdated wu) throws RelizaException {
		if (null == rd.getIdentifiers() || rd.getIdentifiers().isEmpty()) {
			Release r = sharedReleaseService.getRelease(rd.getUuid()).get();
			ComponentData cd = getComponentService.getComponentData(rd.getComponent()).get();
			final UUID orgUuid = rd.getOrg(); // effectively final for the lambda below
			OrganizationData org = getOrganizationService.getOrganizationData(orgUuid)
					.orElseThrow(() -> new RelizaException("Organization not found: " + orgUuid));
			var buildResult = sharedReleaseService.buildReleaseIdentifiers(cd, org, rd.getVersion(),
					rd.getSidComponentName(), null);
			List<TeaIdentifier> identifiers = buildResult.identifiers();
			if (null != identifiers && !identifiers.isEmpty()) {
				rd.setIdentifiers(identifiers);
				if (buildResult.sidComponentNameSnapshot() != null) {
					rd.setSidComponentName(buildResult.sidComponentNameSnapshot());
				}
				r = saveRelease(r, rd, wu, false);
				rd = ReleaseData.dataFromRecord(r);
			}
		}
		return rd;
	}

	public void updateComponentReleasesWithIdentifiers (UUID componentUuid, WhoUpdated wu) throws RelizaException {
		// Top-of-batch org policy validation (design §4.3). Fail before loading any
		// release row when the org is ENABLED_STRICT with empty/invalid authority
		// segments — otherwise the first per-release resolve would throw a release-
		// scoped error for what is actually an org-scoped misconfiguration, after we'd
		// already paid the cost of loading up to 100k releases.
		ComponentData cd = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		OrganizationData org = getOrganizationService.getOrganizationData(cd.getOrg())
				.orElseThrow(() -> new RelizaException("Organization not found: " + cd.getOrg()));
		OrganizationData.Settings settings = org.getSettings();
		if (settings != null && settings.getSidPurlModeOrDefault() == SidPurlMode.ENABLED_STRICT) {
			SidPurlUtils.ValidationResult vr = SidPurlUtils.validateAuthoritySegments(
					settings.getSidAuthoritySegments());
			if (!vr.valid()) {
				throw new RelizaException("Cannot refresh component identifiers: org sidPurlMode="
						+ "ENABLED_STRICT requires valid sidAuthoritySegments (" + vr.error() + ")");
			}
		}

		var compRDs = sharedReleaseService.listReleaseDatasOfComponent(componentUuid, 100000, 0);
		// for-loop (not forEach) so the checked RelizaException propagates and aborts the batch.
		for (ReleaseData rd : compRDs) {
			updateReleaseIdentifiersFromComponent(rd, wu);
		}
	}
	
	@Transactional
	public Release updateRelease (ReleaseDto releaseDto, WhoUpdated wu) throws RelizaException {
		return updateRelease(releaseDto, UpdateReleaseStrength.DRAFT_ONLY, wu);
	}

	@Transactional
	public Release updateReleaseTagsMeta (ReleaseDto releaseDto, WhoUpdated wu) throws RelizaException {
		ReleaseDto tagsMetaDto = ReleaseDto.builder()
			.uuid(releaseDto.getUuid())
			.tags(releaseDto.getTags())
			.notes(releaseDto.getNotes())
			.build();
		return updateRelease(tagsMetaDto, UpdateReleaseStrength.FULL, wu);
	}
	
	/**
	 * Updates release based on dto
	 * @param releaseDto
	 * @param strength - controls lifecycle enforcement for assembly-affecting updates
	 * @param wu
	 * @return
	 * @throws RelizaException 
	 */
	@Transactional
	public Release updateRelease (ReleaseDto releaseDto, UpdateReleaseStrength strength, WhoUpdated wu) throws RelizaException {
		Release r = null;
		// locate and lock release in db
		Optional<Release> rOpt = sharedReleaseService.getRelease(releaseDto.getUuid());
		if (rOpt.isPresent()) {
			r = rOpt.get();
			ReleaseData rData = ReleaseData.dataFromRecord(r);
			ReleaseLifecycle oldLifecycle = rData.getLifecycle();
			boolean isAssemblyAllowed = ReleaseLifecycle.isAssemblyAllowed(rData.getLifecycle());
			boolean isAssemblyRequested = ReleaseDto.isAssemblyRequested(releaseDto);
			boolean permitted = true;
			if (isAssemblyRequested) {
				switch (strength) {
				case UpdateReleaseStrength.FULL:
					permitted = true;
					break;
				case UpdateReleaseStrength.DRAFT_PENDING:
					permitted = isAssemblyAllowed; // DRAFT or PENDING
					break;
				case UpdateReleaseStrength.DRAFT_ONLY:
					permitted = (rData.getLifecycle() == ReleaseLifecycle.DRAFT);
					break;
				default:
					permitted = false;
					break;
				}
			}
			if (!permitted) {
				throw new RelizaException("Cannot update release in the current lifecycle with requested properties");
			}
			doUpdateRelease (r, rData, releaseDto, wu);
		}
		
		return r;
	}
	

	private Release doUpdateRelease (final Release r, ReleaseData rData, ReleaseDto releaseDto, WhoUpdated wu) throws RelizaException {
		log.debug("updating exisiting rd, with dto: {}", releaseDto);
		// sidComponentName is system-controlled. Reject mutation; allow idempotent pass.
		if (releaseDto.getSidComponentName() != null
				&& !releaseDto.getSidComponentName().equals(rData.getSidComponentName())) {
			throw new RelizaException("sidComponentName is immutable once set and may not be changed via release update "
					+ "(release " + r.getUuid() + ", existing=" + rData.getSidComponentName()
					+ ", attempted=" + releaseDto.getSidComponentName() + ")");
		}
		List<UuidDiff> artDiff = Utils.diffUuidLists(rData.getArtifacts(), releaseDto.getArtifacts());
		if (!artDiff.isEmpty()) {
			rData.setArtifacts(releaseDto.getArtifacts());
			artDiff.forEach(ad -> {
				rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.ARTIFACT, ad.diffAction(), null, null,
					ad.object(), ZonedDateTime.now(), wu));
				if (ad.diffAction() == ReleaseUpdateAction.REMOVED) {
					Optional<ArtifactData> oad = artifactService.getArtifactData(ad.object());
					if (oad.isPresent()) {
						List<Release> or = sharedReleaseService.findReleasesByReleaseArtifact(ad.object(), oad.get().getOrg());
						if (or.isEmpty() || (or.size() == 1 && or.get(0).getUuid().equals(r.getUuid()))) {
							artifactService.archiveArtifact(ad.object(), wu);
						}
					}
				}
			});
		}
		if (StringUtils.isNotEmpty(releaseDto.getVersion()) && !releaseDto.getVersion().equals(rData.getVersion())) {
			rData.setVersion(releaseDto.getVersion());
			rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.VERSION, ReleaseUpdateAction.CHANGED, rData.getVersion(),
					releaseDto.getVersion(), null, ZonedDateTime.now(), wu));
		}
		if(null != releaseDto.getParentReleases()){
			sharedReleaseService.checkCircularDependency(r.getUuid(), releaseDto.getParentReleases());
			List<UuidDiff> parentReleaseDiff = Utils.diffUuidLists(rData.getParentReleases().stream().map(x -> x.getRelease()).toList(), releaseDto.getParentReleases().stream().map(x -> x.getRelease()).toList());
			if (!parentReleaseDiff.isEmpty()) {
				rData.setParentReleases(releaseDto.getParentReleases());
				parentReleaseDiff.forEach(pd -> rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.PARENT_RELEASE, pd.diffAction(),
						null, null, pd.object(), ZonedDateTime.now(), wu)));
			}
		}
		
		List<UuidDiff> commitDiff = Utils.diffUuidLists(rData.getCommits(), releaseDto.getCommits());
		if (!commitDiff.isEmpty()) {
			rData.setCommits(releaseDto.getCommits());
			commitDiff.forEach(cd -> rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SOURCE_CODE_ENTRY, cd.diffAction(),
					null, null, cd.object(), ZonedDateTime.now(), wu)));
		}
		if (null != releaseDto.getTickets()) {
			rData.setTickets(releaseDto.getTickets());
		}
		if (null != releaseDto.getSourceCodeEntry() && !releaseDto.getSourceCodeEntry().equals(rData.getSourceCodeEntry())) {
			if (null != rData.getSourceCodeEntry()) rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SOURCE_CODE_ENTRY,
					ReleaseUpdateAction.REMOVED, null, null, rData.getSourceCodeEntry(), ZonedDateTime.now(), wu));
			rData.setSourceCodeEntry(releaseDto.getSourceCodeEntry());
			rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SOURCE_CODE_ENTRY,
					ReleaseUpdateAction.ADDED, null, null, releaseDto.getSourceCodeEntry(), ZonedDateTime.now(), wu));
			
		}
		if (null != releaseDto.getNotes() && !releaseDto.getNotes().equals(rData.getNotes())) {
			rData.setNotes(releaseDto.getNotes());
			rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.NOTES,
					ReleaseUpdateAction.CHANGED, rData.getNotes(), releaseDto.getNotes(), null, ZonedDateTime.now(), wu));
		}
		if (null != releaseDto.getTags() && (null == rData.getTags() || 
				!releaseDto.getTags().toString().equals(rData.getTags().toString()))) {
			
			// Validate that non-removable tags are not being removed
			if (null != rData.getTags()) {
				Set<String> newTagKeys = releaseDto.getTags().stream()
						.map(CommonVariables.TagRecord::key)
						.collect(Collectors.toSet());
				
				List<String> nonRemovableTagsBeingRemoved = rData.getTags().stream()
						.filter(tag -> tag.removable() == CommonVariables.Removable.NO)
						.filter(tag -> !newTagKeys.contains(tag.key()))
						.map(CommonVariables.TagRecord::key)
						.collect(Collectors.toList());
				
				if (!nonRemovableTagsBeingRemoved.isEmpty()) {
					throw new RelizaException("Cannot remove non-removable tags: " + 
							String.join(", ", nonRemovableTagsBeingRemoved));
				}
			}
			
			rData.setTags(releaseDto.getTags());
			String oldTags = null != rData.getTags() ? rData.getTags().toString() : "";
			rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.TAGS,
					ReleaseUpdateAction.CHANGED, oldTags, releaseDto.getTags().toString(), 
					null, ZonedDateTime.now(), wu));
		}
		List<UuidDiff> inboundDelDiff = Utils.diffUuidLists(rData.getInboundDeliverables(), releaseDto.getInboundDeliverables());
		if (!inboundDelDiff.isEmpty()) {
			rData.setInboundDeliverables(releaseDto.getInboundDeliverables());
			inboundDelDiff.forEach(idd -> rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.INBOUND_DELIVERY, idd.diffAction(),
					null, null, idd.object(), ZonedDateTime.now(), wu)));
		}
	
		if (null != releaseDto.getIdentifiers()) {
			// sid PURLs are platform-controlled. Reject sid set changes; allow idempotent pass.
			Set<String> dtoSidPurls = collectSidPurlValues(releaseDto.getIdentifiers());
			Set<String> existingSidPurls = collectSidPurlValues(rData.getIdentifiers());
			if (!dtoSidPurls.equals(existingSidPurls)) {
				throw new RelizaException("sid PURLs are platform-controlled and may not be added, removed, "
						+ "or changed via release update (release " + r.getUuid()
						+ ", existing=" + existingSidPurls + ", attempted=" + dtoSidPurls + ")");
			}
			rData.setIdentifiers(releaseDto.getIdentifiers());
		}
		log.debug("saving release with rData: {}", rData);
		return saveRelease(r, rData, wu);
	}

	/** Collect {@code idValue}s for sid-typed PURL identifiers. Null-safe. */
	private static Set<String> collectSidPurlValues(List<TeaIdentifier> identifiers) {
		if (identifiers == null || identifiers.isEmpty()) {
			return Set.of();
		}
		return identifiers.stream()
				.filter(ti -> ti != null && ti.getIdType() == TeaIdentifierType.PURL)
				.map(TeaIdentifier::getIdValue)
				.filter(SidPurlUtils::isSidPurl)
				.collect(Collectors.toUnmodifiableSet());
	}
	
	@Transactional
	private void processReleaseLifecycleEvents (ReleaseData rData, ReleaseLifecycle curLifecycle, ReleaseLifecycle oldLifecycle) {
		if (curLifecycle == ReleaseLifecycle.DRAFT && oldLifecycle != ReleaseLifecycle.DRAFT) {
			notificationService.processReleaseEvent(rData, ReleaseEventType.RELEASE_DRAFTED);
		} else if (curLifecycle == ReleaseLifecycle.CANCELLED) {
			notificationService.processReleaseEvent(rData, ReleaseEventType.RELEASE_CANCELLED);
		} else if (curLifecycle == ReleaseLifecycle.REJECTED) {
			notificationService.processReleaseEvent(rData, ReleaseEventType.RELEASE_REJECTED);
		} else if (curLifecycle == ReleaseLifecycle.ASSEMBLED) {
			notificationService.processReleaseEvent(rData, ReleaseEventType.RELEASE_ASSEMBLED);
			autoIntegrateProducts(rData);
		}
	}
	
	@Async
	public void autoIntegrateProducts(ReleaseData rd) {
		autoIntegrateProductsForRelease(rd, new HashSet<>());
	}

	/**
	 * Batch auto-integrate. Processes each release in order against a shared set of
	 * already-processed feature sets, so a given feature set is auto-integrated at
	 * most once for the whole batch. Only ASSEMBLED releases trigger integration —
	 * mixed-lifecycle batches behave like single creates (lifecycle gating unchanged).
	 */
	@Async
	public void autoIntegrateProductsForBatch(List<ReleaseData> releases) {
		if (null == releases || releases.isEmpty()) {
			return;
		}
		Set<UUID> processedFeatureSets = new HashSet<>();
		for (ReleaseData rd : releases) {
			if (rd.getLifecycle() == ReleaseLifecycle.ASSEMBLED) {
				try {
					autoIntegrateProductsForRelease(rd, processedFeatureSets);
				} catch (Exception e) {
					log.error("autoIntegrateProducts failed for release {} in batch", rd.getUuid(), e);
				}
			}
		}
	}

	private void autoIntegrateProductsForRelease(ReleaseData rd, Set<UUID> processedFeatureSets) {
		if (null == rd.getBranch()) {
			return;
		}

		// PATH 1: Find feature sets with EXPLICIT dependency on this component+branch
		List<BranchData> explicitFs = branchService.findFeatureSetDataByChildComponentBranch(
			rd.getOrg(), rd.getComponent(), rd.getBranch());

		for (BranchData fs : explicitFs) {
			// Skip if already auto-integrated earlier in the batch
			if (processedFeatureSets.contains(fs.getUuid())) {
				continue;
			}
			if (fs.getAutoIntegrate() == AutoIntegrateState.ENABLED) {
				autoIntegrateFeatureSetProduct(fs, rd);
				processedFeatureSets.add(fs.getUuid());
			}
		}

		// PATH 2: Find feature sets with PATTERN-BASED dependencies
		Optional<ComponentData> componentOpt = getComponentService.getComponentData(rd.getComponent());
		if (componentOpt.isPresent()) {
			String componentName = componentOpt.get().getName();

			// Get all feature sets with patterns in this org
			List<BranchData> patternFs = branchService.findFeatureSetDataWithDependencyPatterns(rd.getOrg());

			for (BranchData fs : patternFs) {
				// Skip if already processed via explicit dependency or earlier in the batch
				if (processedFeatureSets.contains(fs.getUuid())) {
					continue;
				}

				// Check if component name matches any pattern in this feature set
				if (dependencyPatternService.componentMatchesAnyPattern(
						componentName, fs.getDependencyPatterns())) {
					// Pattern matched - trigger auto-integrate
					// The effective dependencies will be resolved inside autoIntegrateFeatureSetProduct
					// which will handle branch matching and other validation
					autoIntegrateFeatureSetProduct(fs, rd);
					processedFeatureSets.add(fs.getUuid());
				}
			}
		}
	}
	
	
	private void autoIntegrateFeatureSetProduct(BranchData featureSet, ReleaseData triggeringRelease) {
		// Resolve effective dependencies (patterns + overrides + manual)
		List<ChildComponent> effectiveDependencies = 
			dependencyPatternService.resolveEffectiveDependencies(featureSet);
		
		// 1. Find matching dependency for this release
		Optional<ChildComponent> matchingDependency = findMatchingDependency(effectiveDependencies, triggeringRelease);
		if (matchingDependency.isEmpty()) {
			log.debug("PSDEBUG: No matching child component found for rd = " + triggeringRelease.getUuid() + " in fs = " + featureSet.getUuid());
			return;
		}
		
		// 2. Validate if we should proceed with auto-integrate
		if (!shouldProceedWithAutoIntegrate(featureSet, triggeringRelease, matchingDependency.get())) {
			return;
		}
		
		// 3. Gather releases from all dependencies
		Optional<Set<ParentRelease>> dependencyReleasesOpt = gatherReleasesFromDependencies(featureSet, triggeringRelease, effectiveDependencies);
		if (dependencyReleasesOpt.isEmpty()) {
			return;
		}
		
		// 4. Determine which release to use (BASE branch logic)
		ReleaseData releaseToUse = determineReleaseToUse(featureSet, triggeringRelease);
		
		// 5. Check if release is already in a product
		if (isReleaseAlreadyInProduct(featureSet, releaseToUse)) {
			log.debug("PSDEBUG: releaseToUse " + releaseToUse.getUuid() + " is already in a product release for feature set " + featureSet.getUuid() + ", skipping");
			return;
		}
		
		// 6. Update parent releases with new release
		Set<ParentRelease> updatedReleases = replaceComponentRelease(dependencyReleasesOpt.get(), releaseToUse);
		
		// 7. Create the product release
		createProductRelease(featureSet, triggeringRelease.getOrg(), updatedReleases);
	}
	
	/**
	 * Validates if auto-integrate should proceed based on dependency status and duplicate checks.
	 * 
	 * @return true if should proceed, false otherwise
	 */
	private boolean shouldProceedWithAutoIntegrate(BranchData featureSet, ReleaseData triggeringRelease, ChildComponent matchingDependency) {
		// Check if dependency is IGNORED or pinned to a specific release
		if (StatusEnum.IGNORED == matchingDependency.getStatus() || matchingDependency.getRelease() != null) {
			return false;
		}
		
		log.debug("PSDEBUG: accessed auto-integrate for fs = " + featureSet.getUuid() + ", rd = " + triggeringRelease.getUuid());
		
		// Check if this release is already in a product for this feature set
		var existingProducts = sharedReleaseService.greedylocateProductsOfRelease(triggeringRelease);
		if (!existingProducts.isEmpty()) {
			boolean alreadyInProduct = existingProducts.stream()
				.anyMatch(x -> x.getBranch().equals(featureSet.getUuid()));
			if (alreadyInProduct) {
				log.debug("PSDEBUG: requirements not met - release already in product");
				return false;
			}
		}

		// Guard against out-of-order assembly: if a parent release in the latest product shares
		// the triggering branch and is strictly newer, skip auto-integrate
		Optional<ReleaseData> latestProductOpt = sharedReleaseService.getReleaseDataOfBranch(featureSet.getUuid());
		if (latestProductOpt.isPresent()) {
			UUID trigBranchUuid = triggeringRelease.getBranch();

			// Single pass: find the parent release (if any) whose branch matches the triggering branch
			Optional<ReleaseData> existingRdOpt = Optional.empty();
			for (ParentRelease pr : latestProductOpt.get().getParentReleases()) {
				Optional<ReleaseData> prRdOpt = sharedReleaseService.getReleaseData(pr.getRelease());
				if (prRdOpt.isPresent() && trigBranchUuid.equals(prRdOpt.get().getBranch())) {
					existingRdOpt = prRdOpt;
					break;
				}
			}

			// Outside the loop: if a matching parent was found, compare using ReleaseVersionComparator (includes date fallback)
			if (existingRdOpt.isPresent()) {
				ReleaseData existingRd = existingRdOpt.get();
				Optional<BranchData> trigBranchOpt = branchService.getBranchData(trigBranchUuid);
				if (trigBranchOpt.isPresent()) {
					BranchData trigBranch = trigBranchOpt.get();
					Optional<ComponentData> trigCompOpt = getComponentService.getComponentData(trigBranch.getComponent());
					if (trigCompOpt.isPresent()) {
						String compSchema = trigCompOpt.get().getVersionSchema();
						String branchSchema = trigBranch.getVersionSchema();
						if (new ReleaseData.ReleaseVersionComparator(compSchema, branchSchema)
								.compare(existingRd, triggeringRelease) < 0) {
							log.debug("skipping out-of-order auto-integrate existing {} ({}) in product is newer than triggering {} ({})",
								existingRd.getUuid(), existingRd.getVersion(), triggeringRelease.getUuid(), triggeringRelease.getVersion());
							return false;
						}
					}
				}
			}
		}

		log.debug("PSDEBUG: requirements met - proceeding with auto-integrate");
		return true;
	}
	
	/**
	 * Gathers releases from all dependencies to use as parent releases in the product.
	 * 
	 * @param featureSet The feature set branch data
	 * @param triggeringRelease The release that triggered auto-integrate
	 * @param effectiveDependencies The resolved effective dependencies (patterns + overrides + manual)
	 * @return Optional containing dependency releases, or empty if requirements not met
	 */
	private Optional<Set<ParentRelease>> gatherReleasesFromDependencies(
			BranchData featureSet, 
			ReleaseData triggeringRelease,
			List<ChildComponent> effectiveDependencies) {
		
		// Use the method that accepts dependencies directly
		Set<ParentRelease> dependencyReleases = sharedReleaseService.getCurrentProductParentRelease(
			featureSet.getUuid(), 
			triggeringRelease,
			effectiveDependencies,
			ReleaseLifecycle.ASSEMBLED
		);
		
		if (dependencyReleases == null || dependencyReleases.isEmpty()) {
			log.debug("PSDEBUG: Failed to gather dependency releases - missing required dependencies");
			return Optional.empty();
		}
		
		return Optional.of(dependencyReleases);
	}
	
	/**
	 * Determines which release to use in the product.
	 * If triggering release is from a non-BASE branch, but there's a BASE branch dependency
	 * for the same component, uses the BASE branch's latest release instead.
	 * 
	 * @return The release to use (either triggering release or BASE branch release)
	 */
	private ReleaseData determineReleaseToUse(BranchData featureSet, ReleaseData triggeringRelease) {
		Optional<BranchData> triggeringBranchOpt = branchService.getBranchData(triggeringRelease.getBranch());
		boolean isFromBaseBranch = triggeringBranchOpt.isPresent() && 
								   triggeringBranchOpt.get().getType() == BranchType.BASE;
		
		if (isFromBaseBranch) {
			// Triggering release is from BASE branch - use it
			return triggeringRelease;
		}
		
		// Check if there's a BASE branch dependency for the same component
		List<ChildComponent> sameComponentDeps = featureSet.getDependencies().stream()
			.filter(cc -> cc.getUuid().equals(triggeringRelease.getComponent()))
			.collect(Collectors.toList());
		
		for (ChildComponent cc : sameComponentDeps) {
			if (cc.getBranch() != null && !cc.getBranch().equals(triggeringRelease.getBranch())) {
				Optional<BranchData> depBranchOpt = branchService.getBranchData(cc.getBranch());
				if (depBranchOpt.isPresent() && depBranchOpt.get().getType() == BranchType.BASE) {
					// Found BASE branch dependency - use its latest release
					Optional<ReleaseData> baseReleaseOpt = sharedReleaseService.getReleaseDataOfBranch(
						triggeringRelease.getOrg(), 
						cc.getBranch(), 
						ReleaseLifecycle.ASSEMBLED
					);
					if (baseReleaseOpt.isPresent()) {
						log.debug("PSDEBUG: Using BASE branch release " + baseReleaseOpt.get().getUuid() + 
							" instead of triggering release " + triggeringRelease.getUuid());
						return baseReleaseOpt.get();
					}
				}
			}
		}
		
		// No BASE branch found - use triggering release
		return triggeringRelease;
	}
	
	/**
	 * Checks if the release is already included in a product for this feature set.
	 * 
	 * @return true if release is already in product, false otherwise
	 */
	private boolean isReleaseAlreadyInProduct(BranchData featureSet, ReleaseData release) {
		var existingProducts = sharedReleaseService.greedylocateProductsOfRelease(release);
		return existingProducts.stream()
			.anyMatch(x -> x.getBranch().equals(featureSet.getUuid()));
	}
	
	/**
	 * Replaces the old component release with the new one in the parent releases set.
	 * 
	 * @return Updated set of parent releases
	 */
	private Set<ParentRelease> replaceComponentRelease(Set<ParentRelease> parentReleases, ReleaseData newRelease) {
		UUID componentUuid = newRelease.getComponent();
		
		// Remove any existing release for this component
		parentReleases.removeIf(pr -> {
			Optional<ReleaseData> existingRd = sharedReleaseService.getReleaseData(pr.getRelease());
			return existingRd.isPresent() && existingRd.get().getComponent().equals(componentUuid);
		});
		
		// Add the new release
		ParentRelease newParentRelease = ParentRelease.minimalParentReleaseFactory(newRelease.getUuid());
		parentReleases.add(newParentRelease);
		
		return parentReleases;
	}
	
	/**
	 * Creates the product release with version calculation and all parent releases.
	 * This method is shared between manual auto-integrate (GraphQL) and automatic triggers.
	 * 
	 * @param featureSet The feature set branch data
	 * @param orgUuid The organization UUID
	 * @param parentReleases The parent releases to include in the product
	 */
	public Optional<UUID> createProductRelease(BranchData featureSet, UUID orgUuid, Collection<ParentRelease> parentReleases) {
		// Determine version bump action
		ActionEnum action = ActionEnum.BUMP;
		try {
			action = getLargestActionFromComponents(
				featureSet.getComponent(),
				featureSet.getUuid(),
				new LinkedList<ParentRelease>(parentReleases)
			);
		} catch (RelizaException e1) {
			log.error("Error calculating version action for feature set " + featureSet.getUuid() + ": " + e1.getMessage());
		}

		// Get new version. Wrapper retries on (branch, version) unique-constraint
		// collisions — two concurrent auto-integrations on the same product feature
		// set will otherwise both compute the same next version and one will fail.
		Optional<VersionAssignment> ova = versionAssignmentService.getSetNewVersionWrapper(
			featureSet.getUuid(),
			action,
			null,
			null,
			VersionTypeEnum.DEV
		);

		// Build and create release
		ReleaseDto releaseDto = ReleaseDto.builder()
			.component(featureSet.getComponent())
			.branch(featureSet.getUuid())
			.org(orgUuid)
			.status(ReleaseStatus.ACTIVE)
			.lifecycle(ReleaseLifecycle.ASSEMBLED)
			.version(ova.get().getVersion())
			.parentReleases(new LinkedList<ParentRelease>(parentReleases))
			.build();

		try {
			Release created = createRelease(releaseDto, WhoUpdated.getAutoWhoUpdated());
			return created != null ? Optional.of(created.getUuid()) : Optional.empty();
		} catch (Exception e) {
			// The previous version logged just the message without the
			// throwable, so the actual cause was invisible in production
			// — pass `e` so the stack trace lands on the next failure.
			log.error("Exception on creating programmatic release, feature set = "
					+ featureSet.getUuid(), e);
			return Optional.empty();
		}
	}
	
	/**
	 * Finds the matching dependency in the dependencies list for the triggering release.
	 * 
	 * @param dependencies The list of dependencies to search in
	 * @param triggeringRelease The release that triggered auto-integrate
	 * @return Optional containing the matching dependency, or empty if no match found
	 */
	private Optional<ChildComponent> findMatchingDependency(List<ChildComponent> dependencies, ReleaseData triggeringRelease) {
		
		// Try exact match: component UUID + branch UUID
		Optional<ChildComponent> matchingChild = dependencies.stream()
			.filter(cc -> cc.getUuid().equals(triggeringRelease.getComponent()) && 
						  cc.getBranch() != null && 
						  cc.getBranch().equals(triggeringRelease.getBranch()))
			.findFirst();
		
		// If no exact match, apply fallback logic
		if (matchingChild.isEmpty()) {
			List<ChildComponent> sameComponentDeps = dependencies.stream()
				.filter(cc -> cc.getUuid().equals(triggeringRelease.getComponent()))
				.collect(Collectors.toList());
			
			if (sameComponentDeps.size() == 1) {
				// Only one entry for this component
				ChildComponent singleDep = sameComponentDeps.get(0);
				if (triggeringRelease.getBranch().equals(singleDep.getBranch())) {
					matchingChild = Optional.of(singleDep);
				}
				// If branch doesn't match, matchingChild remains empty (no auto-integrate)
			} else if (sameComponentDeps.size() > 1) {
				// Multiple branches for same component - apply priority logic:
				// 1. If one of the branches is BASE, that takes priority
				// 2. Otherwise, use the branch that matches the release's branch
				for (ChildComponent cc : sameComponentDeps) {
					if (cc.getBranch() != null) {
						Optional<BranchData> childBranchOpt = branchService.getBranchData(cc.getBranch());
						if (childBranchOpt.isPresent() && childBranchOpt.get().getType() == BranchType.BASE) {
							// Check if the release is from the BASE branch
							if (cc.getBranch().equals(triggeringRelease.getBranch())) {
								matchingChild = Optional.of(cc);
								break;
							}
						}
					}
				}
				// If still no match and release branch matches one of the dependencies, use that
				if (matchingChild.isEmpty()) {
					matchingChild = sameComponentDeps.stream()
						.filter(cc -> triggeringRelease.getBranch().equals(cc.getBranch()))
						.findFirst();
				}
			}
		}
		
		return matchingChild;
	}

	/**
	 * Similar to getLargestActionFromComponents(UUID, UUID), however this method only requires feature set UUID
	 * instead of feature set UUID and product uuid.
	 * @param featureSetUuid
	 * @return ActionEnum representing the largest difference between latest and target versions of components of latest release of specified feature set. Default to ActionEnum.BUMP.
	 * @throws RelizaException
	 */
	public ActionEnum getLargestActionFromComponents(UUID featureSetUuid) throws RelizaException {
		Optional<ComponentData> pd = getComponentService.getComponentDataByBranch(featureSetUuid);
		ActionEnum action = ActionEnum.BUMP;
		if (pd.isPresent()) {
			UUID productUuid = pd.get().getUuid();
			action = getLargestActionFromComponents(productUuid, featureSetUuid);
		}
		return action;
	}

	/**
	 * Similar to getLargestActionFromComponents(UUID, UUID, UUID), however this method only requires product uuid and feature set UUID
	 * instead of product uuid, feature set UUID and currentRelease uuid.
	 * @param featureSetUuid
	 * @return ActionEnum representing the largest difference between latest and target versions of components of latest release of specified feature set. Default to ActionEnum.BUMP.
	 * @throws RelizaException
	 */
	public ActionEnum getLargestActionFromComponents(UUID productUuid, UUID featureSetUuid) throws RelizaException {
		
		ActionEnum action = ActionEnum.BUMP;
	
		List<ParentRelease> currentReleases = new ArrayList<ParentRelease>(
				sharedReleaseService.getCurrentProductParentRelease(featureSetUuid, ReleaseLifecycle.DRAFT));
		action = getLargestActionFromComponents(productUuid, featureSetUuid, currentReleases);
		
		return action;
	}
	
	/**
	 * This method checks all components of a latest product release and determines the largest version bump that occured between
	 * the component release included in the product release, and the latest release of each component.
	 * i.e. if one of the components of given release has had minor upgraded, this will return BUMP_MINOR action
	 * <br><br>
	 * Note: Only checks top level components for version changes
	 * 
	 * @param productUuid
	 * @param featureSetUuid
	 * @return ActionEnum representing the largest difference between latest and target versions of components of latest release of specified feature set. Default to ActionEnum.BUMP.
	 * @throws RelizaException 
	 */
	public ActionEnum getLargestActionFromComponents(UUID productUuid, UUID featureSetUuid, List<ParentRelease> currentReleases) throws RelizaException {
		Optional<BranchData> ofs = branchService.getBranchData(featureSetUuid);
		// get name of feature set from uuid
		if (ofs.isEmpty()) {
			throw new RelizaException("Feature set UUID = " + featureSetUuid + " does not exist.");
		}
		// Return Action
		ActionEnum action = ActionEnum.BUMP; 
		// for each component release get largest action and break
		BranchData fs = ofs.get();
		Optional<ReleaseData> latestProductRelease = getReleasePerProductComponent(ofs.get().getOrg(), productUuid, null, fs.getName(), null);

		if (latestProductRelease.isEmpty())
			return action;
		
		// get only top-level components of latest product release (option 1)
		Set<UUID> latestComponentReleaseUuids = latestProductRelease.get().getParentReleases()
				.stream().map(x -> x.getRelease()).collect(Collectors.toSet());
		
		Set<UUID> currentComponentReleaseUuids = currentReleases
				.stream().map(x -> x.getRelease()).collect(Collectors.toSet());

		
		// Filter the lists down to required and transient components
		// how to deal with optionals?
		// From currentComponentRds get projectIds, compare it with projectIds on latestComponentRds if they dont match means - a project got added or removed
		// For each currentcomponentRd se if its on the same branch as latest
		// Pair-wise compare list of changes in commits between current and latest release and return largest bump action
		// remove feature set pinned releases from list of component releases
		Set<UUID> pinnedReleases = fs.getDependencies().stream().map(cp -> cp.getRelease()).filter(Objects::nonNull).collect(Collectors.toSet());
		latestComponentReleaseUuids.removeAll(pinnedReleases);
		currentComponentReleaseUuids.removeAll(pinnedReleases);

		List<ReleaseData> latestComponentRds = sharedReleaseService.getReleaseDataList(latestComponentReleaseUuids, fs.getOrg());
		List<ReleaseData> currentComponentRds = sharedReleaseService.getReleaseDataList(currentComponentReleaseUuids, fs.getOrg());

		Map<UUID, ReleaseData> latestComponentRdMap = latestComponentRds.stream().collect(Collectors.toMap(ReleaseData::getComponent, Function.identity(), this::selectBestReleaseForComponent));
		Map<UUID, ReleaseData> currentComponentRdMap = currentComponentRds.stream().collect(Collectors.toMap(ReleaseData::getComponent, Function.identity(), this::selectBestReleaseForComponent));
		
		Set<UUID> latestComponentComponentIds = latestComponentRdMap.keySet();
		Set<UUID> currentComponentComponentIds = currentComponentRdMap.keySet();

		action = currentComponentComponentIds.size() == latestComponentComponentIds.size() ? action : ActionEnum.BUMP_MINOR;
		log.info("action after project comparison: {}", action);

		for (UUID p: currentComponentComponentIds) {
			ReleaseData currentComponentRelease = currentComponentRdMap.get(p);
			ReleaseData latestComponentRelease = latestComponentRdMap.get(p);
			if(latestComponentRelease != null){
				if(currentComponentRelease.getUuid().equals(latestComponentRelease.getUuid())) continue;
				action = currentComponentRelease.getBranch().equals(latestComponentRelease.getBranch()) ? action: ActionEnum.BUMP_MINOR;
				log.info("action after branch comparison: {}", action);
				BranchData componentBranch = branchService.getBranchData(currentComponentRelease.getBranch()).get();
				if (componentBranch.getVersionSchema().equalsIgnoreCase("Semver")
						|| componentBranch.getVersionSchema().equalsIgnoreCase("Major.Minor.Patch")
						|| componentBranch.getVersionSchema().equalsIgnoreCase("Major.Minor.Micro")) {
					// Parse versions into VersionHelper objects in order to iterate through components
					Optional<VersionHelper> ooldVh = VersionUtils.parseVersion(latestComponentRelease.getVersion(), componentBranch.getVersionSchema(), true);
					Optional<VersionHelper> onewVh = VersionUtils.parseVersion(currentComponentRelease.getVersion(), componentBranch.getVersionSchema(), true);
					
					if (ooldVh.isPresent() && onewVh.isPresent()) {
						var oldVh = ooldVh.get();
						var newVh = onewVh.get();
					// end loop early if we find MAJOR component change, because this is the max
						for (int i = 0; i < oldVh.getVersionComponents().size() && action != ActionEnum.BUMP_MAJOR; i++) {
							if (!oldVh.getVersionComponents().get(i).equals(newVh.getVersionComponents().get(i))) {
								// found differing version components, check which ones
								switch(i) {
								case 0:
									// Major, since major is max, always set to major if we find difference in major component
									action = ActionEnum.BUMP_MAJOR;
									break;
								case 1:
									// Minor, only set if *action* is not MAJOR
									action = ActionEnum.BUMP_MINOR;
									break;
								case 2:
									// Micro/Patch, set if action is not already larger (ie: major or minor)
									if(action != ActionEnum.BUMP_MINOR) {
										action = ActionEnum.BUMP_PATCH;
									}
									break;
								}
							}
						}
					}
					log.info("action after versions comparison: {}", action);
				}else{
					List<String> commits = getCommitListBetweenComponentReleases(currentComponentRelease.getUuid(), latestComponentRelease.getUuid(), fs.getOrg());
					for(String commit: commits){
						if(commit != null && !StringUtils.isEmpty(commit)){
							try {
								log.info("checking commit: {}", commit);
								ActionEnum commitAction = VersionApi.getActionFromRawCommit(commit);
								log.info("commitAction: {}", commitAction);
								if(commitAction.equals(ActionEnum.BUMP_MAJOR)){
									action = commitAction;
									break;
								}
								if (commitAction != null && (action == null || commitAction.compareTo(action) > 0)) {
									action = commitAction;
								}
							} catch (Exception e) {
								log.warn("Exception on getting action from commit", e);
							}
						}
					}
					log.info("action after checking commits: {}", action);
				}
				
			}
			
			if(action.equals(ActionEnum.BUMP_MAJOR)){
				break;
			}

		}

		log.info("return action: {}", action);
		return action;
	}
	
/**
	 * Selects the best release when multiple releases exist for the same component.
	 * Priority:
	 * 1. Release from BASE branch always wins
	 * 2. If neither is from BASE branch, use the one with later created date
	 * 
	 * @param existing The existing release in the map
	 * @param replacement The new release being added
	 * @return The release that should be kept
	 */
	private ReleaseData selectBestReleaseForComponent(ReleaseData existing, ReleaseData replacement) {
		// Check if existing is from BASE branch
		Optional<BranchData> existingBranchOpt = branchService.getBranchData(existing.getBranch());
		boolean existingIsBase = existingBranchOpt.isPresent() && existingBranchOpt.get().getType() == BranchType.BASE;
		
		// Check if replacement is from BASE branch
		Optional<BranchData> replacementBranchOpt = branchService.getBranchData(replacement.getBranch());
		boolean replacementIsBase = replacementBranchOpt.isPresent() && replacementBranchOpt.get().getType() == BranchType.BASE;
		
		// If one is BASE and the other isn't, prefer BASE
		if (existingIsBase && !replacementIsBase) {
			return existing;
		}
		if (replacementIsBase && !existingIsBase) {
			return replacement;
		}
		
		// Neither or both are BASE - use the one with later created date
		ZonedDateTime existingDate = existing.getCreatedDate();
		ZonedDateTime replacementDate = replacement.getCreatedDate();
		
		if (existingDate == null && replacementDate == null) {
			return replacement; // fallback
		}
		if (existingDate == null) {
			return replacement;
		}
		if (replacementDate == null) {
			return existing;
		}
		
		return replacementDate.isAfter(existingDate) ? replacement : existing;
	}
	
	public List<String> getCommitListBetweenComponentReleases(UUID uuid1, UUID uuid2, UUID org) throws RelizaException{
		List<String> commits = new ArrayList<>();
		List<ReleaseData> rds = sharedReleaseService.listAllReleasesBetweenReleases(uuid1,  uuid2);
		rds.remove(rds.size()-1); //remove the extra element at the end of the list
		if(rds.size() > 0){
				List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(rds, org);
				List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
				Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
				commits = commitIdToRecordMap.entrySet().stream().map(
							 e -> e.getValue().commitMessage()).filter(Objects::nonNull).collect(Collectors.toList());
				
		}
		return commits;
	}
	

	/**
	 * Seed {@code releaseDto} with the stored sid identity so rebuild / PENDING-update
	 * passes the snapshot + sid-PURL immutability checks in {@link #doUpdateRelease}.
	 * sid identity is computed exactly once, at creation, and never re-derived; rebuild
	 * after a component rename or org-authority change must keep the original identity
	 * or it would crash the update flow.
	 *
	 * <p>Caller-supplied sid PURLs are dropped silently; existing ones from {@code existingRd}
	 * are re-attached. Caller-supplied non-sid identifiers (CPE, TEI, etc.) flow through.
	 * If the caller passed null identifiers, leave that null so the update path skips
	 * identifier handling entirely and the stored list survives intact.
	 *
	 * <p><b>Caller contract for non-null identifiers:</b> when {@code releaseDto.identifiers}
	 * is non-null, the caller is taking ownership of the entire non-sid identifier set —
	 * stored non-sid identifiers (carryover PURLs, CPEs, TEIs) <em>not</em> reasserted by
	 * the caller will be wiped by {@link #doUpdateRelease}. Concretely, passing
	 * {@code [vendor_sid_only]} on a release that has stored carryover PURLs will result
	 * in: caller's sid stripped here → empty list seeded → existing sid re-attached →
	 * {@code doUpdateRelease} sees identical sid sets on both sides and overwrites the
	 * stored list with the empty merge, losing the carryover. Callers that want to
	 * preserve stored non-sid identifiers must pass {@code null} (the no-op signal).
	 */
	private static void seedDtoWithExistingSidIdentity(ReleaseDto releaseDto, ReleaseData existingRd) {
		releaseDto.setSidComponentName(existingRd.getSidComponentName());

		if (releaseDto.getIdentifiers() == null) {
			return;
		}
		List<TeaIdentifier> merged = new LinkedList<>(releaseDto.getIdentifiers());
		merged.removeIf(ti -> ti != null && ti.getIdType() == TeaIdentifierType.PURL
				&& SidPurlUtils.isSidPurl(ti.getIdValue()));
		List<TeaIdentifier> existing = existingRd.getIdentifiers();
		if (existing != null) {
			existing.stream()
					.filter(ti -> ti != null && ti.getIdType() == TeaIdentifierType.PURL
							&& SidPurlUtils.isSidPurl(ti.getIdValue()))
					.forEach(merged::add);
		}
		releaseDto.setIdentifiers(merged);
	}

	@Transactional
	public Release createRelease (ReleaseDto releaseDto, WhoUpdated wu) throws RelizaException {
		return createRelease(releaseDto, wu, false, false);
	}

	@Transactional
	public Release createRelease (ReleaseDto releaseDto, WhoUpdated wu, boolean rebuildRelease) throws RelizaException {
		return createRelease(releaseDto, wu, rebuildRelease, false);
	}

	/**
	 * @param deferAutoIntegrate when true, the per-release product auto-integration is
	 *        skipped so a batch caller can fire it once, deduped across the whole batch,
	 *        after all releases are created (see autoIntegrateProductsForBatch). The other
	 *        post-save side-effects (follow-release / follow-instance refresh, SBOM
	 *        reconcile) still run per release.
	 */
	@Transactional
	public Release createRelease (ReleaseDto releaseDto, WhoUpdated wu, boolean rebuildRelease, boolean deferAutoIntegrate) throws RelizaException {
		Release r = new Release();
		// resolve branch or feature set uuid to its corresponding project or product parent
		Optional<BranchData> bdOpt = Optional.empty();
		if (null != releaseDto.getBranch()) {
			bdOpt = branchService.getBranchData(releaseDto.getBranch());
			if (bdOpt.isPresent() && null == releaseDto.getComponent()) {
				releaseDto.setComponent(bdOpt.get().getComponent());
			} else if (bdOpt.isEmpty()) {
				throw new RelizaException("Could not locate branch when creating release for branch = " + releaseDto.getBranch());
			} else if (!bdOpt.get().getComponent().equals(releaseDto.getComponent())) {
				throw new RelizaException("Component and branch mismatch for component = " + releaseDto.getComponent() 
					+ " and branch = " + releaseDto.getBranch());
			}
		}
		if (null == releaseDto.getComponent()) throw new IllegalStateException("Component or Product is required on release creation");
		if (null == releaseDto.getStatus()) releaseDto.setStatus(ReleaseStatus.ACTIVE);
		if (null == releaseDto.getLifecycle()) releaseDto.setLifecycle(ReleaseLifecycle.DRAFT);

		List<UUID> allCommits = new LinkedList<>(); 
		
		if(null != releaseDto.getSourceCodeEntry() )
			allCommits.add(releaseDto.getSourceCodeEntry());
		if(null!= releaseDto.getCommits() && !releaseDto.getCommits().isEmpty())
			allCommits.addAll(releaseDto.getCommits());
		//handle tickets
		if(!allCommits.isEmpty()){
			Set<UUID> tickets = getSourceCodeEntryService.getTicketsList(allCommits, List.of(releaseDto.getOrg(), CommonVariables.EXTERNAL_PROJ_ORG_UUID));
			if(!tickets.isEmpty()){
				releaseDto.setTickets(tickets);
			}
		}
		
		// --- sid identity ---
		// Look up version assignment first so we know whether this is a fresh create or
		// an update; the orchestrator runs only on fresh creates so existing sid identity
		// is never re-derived (write-once invariant).
		ComponentData cd = getComponentService.getComponentData(releaseDto.getComponent()).get();
		OrganizationData org = getOrganizationService.getOrganizationData(releaseDto.getOrg())
				.orElseThrow(() -> new RelizaException("Organization not found: " + releaseDto.getOrg()));

		Optional<VersionAssignment> ova = versionAssignmentService.getVersionAssignment(
				releaseDto.getComponent(), releaseDto.getVersion());
		Optional<ReleaseData> existingReleaseData = (ova.isPresent() && null != ova.get().getRelease())
				? sharedReleaseService.getReleaseData(ova.get().getRelease())
				: Optional.empty();

		if (existingReleaseData.isPresent()) {
			// Rebuild / PENDING-update — preserve stored sid identity, do not re-derive.
			seedDtoWithExistingSidIdentity(releaseDto, existingReleaseData.get());
		} else {
			// Fresh release — orchestrator runs even when caller supplied identifiers,
			// so non-PURL identifiers (e.g. CPE) still gain a sid PURL.
			var buildResult = sharedReleaseService.buildReleaseIdentifiers(cd, org, releaseDto.getVersion(),
					releaseDto.getSidComponentName(), releaseDto.getIdentifiers());
			releaseDto.setIdentifiers(buildResult.identifiers());
			if (buildResult.sidComponentNameSnapshot() != null) {
				releaseDto.setSidComponentName(buildResult.sidComponentNameSnapshot());
			}
		}

		ReleaseData rData = ReleaseData.releaseDataFactory(releaseDto);
		ReleaseUpdateEvent rue = new ReleaseUpdateEvent(ReleaseUpdateScope.RELEASE_CREATED, ReleaseUpdateAction.ADDED, null, null, null,
				ZonedDateTime.now(), wu);
		rData.addUpdateEvent(rue);

		if (ova.isPresent() && null != ova.get().getRelease()) {
			//Release already exists, proceeding with an update to the existing release
			final String dtoVersion = rData.getVersion(); // capture for lambda — rData is reassigned below
			ReleaseData existingRd = existingReleaseData.orElseThrow(() ->
					new RelizaException("Cannot find the existing release data associated with the version = " + dtoVersion));
			
			// If rebuildRelease is true, strip and rebuild the release regardless of lifecycle
			if (rebuildRelease) {
				log.info("Rebuilding release: uuid={}, version={}", existingRd.getUuid(), rData.getVersion());
				// Strip the existing release - clear artifacts, source code entries, commits, deliverables
				releaseDto.setUuid(existingRd.getUuid());
				releaseDto.setArtifacts(releaseDto.getArtifacts()); // use new artifacts from input
				releaseDto.setSourceCodeEntry(releaseDto.getSourceCodeEntry()); // use new SCE from input
				releaseDto.setCommits(releaseDto.getCommits()); // use new commits from input
				// Clear old deliverables - use new ones from input or empty list if not provided
				if (null == releaseDto.getInboundDeliverables()) {
					releaseDto.setInboundDeliverables(new LinkedList<>());
				}
				if (null == releaseDto.getOutboundDeliverables()) {
					releaseDto.setOutboundDeliverables(new LinkedList<>());
				}
				r = updateRelease(releaseDto, UpdateReleaseStrength.FULL, wu);
				r = updateReleaseLifecycle(existingRd.getUuid(), releaseDto.getLifecycle(), wu);
				rData = ReleaseData.dataFromRecord(r);
			} else if (existingRd.getLifecycle() != ReleaseLifecycle.PENDING) {
				// Only allow updates from PENDING lifecycle if rebuildRelease is false
				throw new RelizaException("Cannot create release because this version already belongs to another non-pending release, version = " + rData.getVersion());
			} else {
				releaseDto.setUuid(existingRd.getUuid());
				r = updateRelease(releaseDto, UpdateReleaseStrength.DRAFT_PENDING, wu);
				r = updateReleaseLifecycle(existingRd.getUuid(), releaseDto.getLifecycle(), wu);
				rData = ReleaseData.dataFromRecord(r);
			}
		} else if (ova.isPresent()) {
			r = saveRelease(r, rData, wu);
			rData = ReleaseData.dataFromRecord(r);
			variantService.ensureBaseVariantForRelease(rData, wu);
			VersionAssignment va = ova.get();
			va.setRelease(r.getUuid());
			va.setAssignmentType(AssignmentTypeEnum.ASSIGNED);
			versionAssignmentService.saveVersionAssignment(va);
			handleNewReleaseNotifications(rData, bdOpt.get());
		} else { // ova empty
			r = saveRelease(r, rData, wu);
			rData = ReleaseData.dataFromRecord(r);
			variantService.ensureBaseVariantForRelease(rData, wu);
			versionAssignmentService.createNewVersionAssignment(rData.getBranch(), rData.getVersion(), r.getUuid());
			handleNewReleaseNotifications(rData, bdOpt.get());
		}
		// Post-save side-effect: feature-set auto-integrate for any
		// downstream product whose dependency list includes this
		// component+branch. Best-effort and isolated from the
		// release-create transaction — pre-fix, an exception here
		// (e.g. concurrent state, malformed dep config) propagated up
		// through createRelease and the surrounding caller saw a null
		// return even though the row had already been written.
		if (rData.getLifecycle() == ReleaseLifecycle.ASSEMBLED ) {
			final ReleaseData postSaveRd = rData;
			if (!deferAutoIntegrate) {
				try { autoIntegrateProducts(postSaveRd); }
				catch (Exception e) { log.error("autoIntegrateProducts failed for release {}", postSaveRd.getUuid(), e); }
			}
		}
		// Queue SBOM-component reconciliation for any release that came in with
		// artifacts attached (e.g. addReleaseProgrammatic / addReleaseManual
		// passing them on the create payload). The post-creation addArtifact
		// path already calls requestReconcile per artifact, so this only fills
		// the gap for create-with-artifacts. Idempotent — markSbomReconcileRequested
		// no-ops if a request is already pending.
		//
		// Source-code-entry-only releases (the `--scearts` fs-bom CI flow)
		// used to slip through this gate because their BOM lives on the
		// SCE rather than on the release — resulting in 0-component
		// inventories until something else (a later addArtifact, a
		// deliverable add) triggered reconcile.
		// SbomComponentService.collectBomArtifactUuids already walks SCE
		// artifacts at reconcile time, so the only fix needed is to
		// extend the trigger gate. Outbound deliverables are not checked
		// here because they're materialised post-`ensureBaseVariantForRelease`
		// via a separate code path that has its own reconcile trigger;
		// the deliverable-add mutation surface invokes requestReconcile
		// directly.
		if (hasArtifactsRequiringReconcile(rData)) {
			sbomComponentService.requestReconcile(r.getUuid());
		}
		return r;
	}

	private boolean hasArtifactsRequiringReconcile (ReleaseData rd) {
		if (rd.getArtifacts() != null && !rd.getArtifacts().isEmpty()) return true;
		if (rd.getSourceCodeEntry() != null) {
			return getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry())
					.map(sce -> sce.getArtifacts() != null && !sce.getArtifacts().isEmpty())
					.orElse(false);
		}
		return false;
	}

	private void handleNewReleaseNotifications(ReleaseData rData, BranchData bd) {
		if (rData.getLifecycle() == ReleaseLifecycle.PENDING) {
			notificationService.processReleaseEvent(rData, bd, ReleaseEventType.RELEASE_SCHEDULED);
		} else {
			notificationService.processReleaseEvent(rData, bd, ReleaseEventType.NEW_RELEASE);
		}
	}

	
}
