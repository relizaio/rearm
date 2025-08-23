/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ReleaseEventType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.Utils.UuidDiff;
import io.reliza.common.ValidationResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ConditionGroup;
import io.reliza.model.tea.TeaIdentifier;
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
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.AcollectionService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuditService;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.NotificationService;
import io.reliza.service.ReleaseService.CommitRecord;
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
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private GetComponentService getComponentService;
	
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
			
	private final ReleaseRepository repository;
	
	OssReleaseService(ReleaseRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu) {
		return saveRelease (r, recordData, wu, true);
	}
			
	@Transactional
	public Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu, boolean considerTriggers) {
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
			// here we may have a conflict if we have too many releases created at once - then revisions may be non-unique, so we'll introduce some retry mechanics
			int tries = 5;
			boolean savedRevision = false;
			while (tries > 0 && !savedRevision) {
				try {
					auditService.createAndSaveAuditRecord(TableName.RELEASES, r);
					savedRevision = true;
				} catch (Exception e) {
					log.error("Error on saving audit record", e);
					r.setRevision(r.getRevision() + 1);
				}
				--tries;
			}
			if (tries < 1 && !savedRevision) {
				throw new IllegalStateException("Could not save release r = " + r.getUuid() + ", revision = " + r.getRevision() + " after 5 tries, aborting.");
			}
		}
		r.setRecordData(recordData);
		log.debug("setting release recordData:{}", recordData);
		r = (Release) WhoUpdated.injectWhoUpdatedData(r, wu);
		r = repository.save(r);
		acollectionService.resolveReleaseCollection(r.getUuid(), wu);
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
		Map<String,Object> recordData = Utils.dataToRecord(rd);
		r = saveRelease(r, recordData, wu, considerTriggers);
		processReleaseLifecycleEvents (ReleaseData.dataFromRecord(r), newLifecycle, oldLifecycle);
		return r;
	}
	
	@Transactional
	public ReleaseData updateReleaseIdentifiersFromComponent (ReleaseData rd, WhoUpdated wu) {
		if (null == rd.getIdentifiers() || rd.getIdentifiers().isEmpty()) {
			Release r = sharedReleaseService.getRelease(rd.getUuid()).get();
			var identifiers = sharedReleaseService.resolveReleaseIdentifiersFromComponent(rd);
			if (null != identifiers && !identifiers.isEmpty()) {
				rd.setIdentifiers(identifiers);
				Map<String,Object> recordData = Utils.dataToRecord(rd);
				r = saveRelease(r, recordData, wu, false);
				rd = ReleaseData.dataFromRecord(r);
			}
		}
		return rd;
	}
	
	public void updateComponentReleasesWithIdentifiers (UUID componentUuid, WhoUpdated wu) {
		var compRDs = sharedReleaseService.listReleaseDatasOfComponent(componentUuid, 100000, 0);
		compRDs.forEach(rd -> updateReleaseIdentifiersFromComponent(rd, wu));
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
			processReleaseLifecycleEvents (rData, releaseDto.getLifecycle(), oldLifecycle);
		}
		
		return r;
	}
	

	private Release doUpdateRelease (final Release r, ReleaseData rData, ReleaseDto releaseDto, WhoUpdated wu) throws RelizaException {
		log.debug("updating exisiting rd, with dto: {}", releaseDto);
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
			rData.setIdentifiers(releaseDto.getIdentifiers());
		}
		Map<String,Object> recordData = Utils.dataToRecord(rData);
		log.debug("saving release with recordData: {}", recordData);
		return saveRelease(r, recordData, wu);
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
		// locate all product feature sets for this release
		if (null != rd.getBranch()) { // placeholder releases do not have branches and we do not want to auto-integrate on those
			List<BranchData> branchDatas = branchService.findBranchDataByChildComponentBranch(rd.getOrg(), rd.getComponent(), rd.getBranch());
			if (!branchDatas.isEmpty()) {
				for (BranchData bd : branchDatas) {
					if (bd.getAutoIntegrate() == AutoIntegrateState.ENABLED) autoIntegrateFeatureSetProduct(bd, rd);
				}
			}
		}
	}
	
	
	private void autoIntegrateFeatureSetProduct(BranchData bd, ReleaseData rd) {
		List<ChildComponent> dependencies = bd.getDependencies();
		final Map<UUID, ChildComponent> componentsWBranches = dependencies.stream().collect(Collectors.toMap(x -> x.getUuid(), Function.identity()));
		// check that status of this child project is not ignored, check that this release is not ignored and not pinned
		if (StatusEnum.IGNORED != componentsWBranches.get(rd.getComponent()).getStatus() &&
			null ==	componentsWBranches.get(rd.getComponent()).getRelease()) {
			log.debug("PSDEBUG: accessed auto-integrate for bd = " + bd.getUuid() + ", rd = " + rd.getUuid());
			// See if any product of this branch already has this release 
			// which can happen on status change - in which case do not proceed
			var existingProducts = sharedReleaseService.greedylocateProductsOfRelease(rd);
			boolean requirementsMet = true; 
			if (!existingProducts.isEmpty()) {
				// check if existing products contain this branch
				// if yes, do not proceed
				requirementsMet = !existingProducts
									.stream()
									.anyMatch(x -> x.getBranch().equals(bd.getUuid()));
			}
			log.debug("PSDEBUG: requirements met status after product check = " + requirementsMet);
			
			// Take every required child project of this branch, and take latest assembled release for each of them
			Set<ParentRelease> parentReleases = new HashSet<>();
			if (requirementsMet) {
				parentReleases = sharedReleaseService.getCurrentProductParentRelease(bd.getUuid(), rd, ReleaseLifecycle.ASSEMBLED);
				requirementsMet = parentReleases != null && parentReleases.size() > 0;
			}
			
			// If one of required projects does not have latest release, then we fail the process and don't yield anything there
			if (requirementsMet) {
				// add current release to parent
				ParentRelease dr = ParentRelease.minimalParentReleaseFactory(rd.getUuid(), null);
				parentReleases.add(dr); // current release
				// create new product release
				ActionEnum action = ActionEnum.BUMP;
				try {
					action = getLargestActionFromComponents(bd.getComponent(), bd.getUuid(), new LinkedList<ParentRelease>(parentReleases));
				} catch (RelizaException e1) {
					log.error(e1.getMessage());
				}
				Optional<VersionAssignment> ova = versionAssignmentService.getSetNewVersionWrapper(bd.getUuid(), action, null, null);
				ReleaseDto releaseDto = ReleaseDto.builder()
												.component(bd.getComponent())
												.branch(bd.getUuid())
												.org(rd.getOrg())
												.status(ReleaseStatus.ACTIVE)
												.lifecycle(ReleaseLifecycle.ASSEMBLED)
												.version(ova.get().getVersion())
												.parentReleases(new LinkedList<ParentRelease>(parentReleases))
												.build();
				try {
					createRelease(releaseDto, WhoUpdated.getAutoWhoUpdated());
				} catch (Exception e) {
					log.error("Exception on creating programmatic release, feature set = " + bd.getUuid() + ", trigger release = " + rd.getUuid());
				}
			}
		}
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

		Map<UUID, ReleaseData> latestComponentRdMap = latestComponentRds.stream().collect(Collectors.toMap(ReleaseData::getComponent, Function.identity()));
		Map<UUID, ReleaseData> currentComponentRdMap = currentComponentRds.stream().collect(Collectors.toMap(ReleaseData::getComponent, Function.identity()));
		
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
					VersionHelper oldVh = VersionUtils.parseVersion(latestComponentRelease.getVersion());
					VersionHelper newVh = VersionUtils.parseVersion(currentComponentRelease.getVersion());
					
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
	

	@Transactional
	public Release createRelease (ReleaseDto releaseDto, WhoUpdated wu) throws RelizaException {
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
		
		if (null == releaseDto.getIdentifiers() || releaseDto.getIdentifiers().isEmpty()) {
			ComponentData cd = getComponentService.getComponentData(releaseDto.getComponent()).get();
			List<TeaIdentifier> releaseIdentifiers = sharedReleaseService.resolveReleaseIdentifiersFromComponent(releaseDto.getVersion(), cd);
			releaseDto.setIdentifiers(releaseIdentifiers);
		}

		ReleaseData rData = ReleaseData.releaseDataFactory(releaseDto);
		ReleaseUpdateEvent rue = new ReleaseUpdateEvent(ReleaseUpdateScope.RELEASE_CREATED, ReleaseUpdateAction.ADDED, null, null, null,
				ZonedDateTime.now(), wu);
		rData.addUpdateEvent(rue);
		Map<String,Object> recordData = Utils.dataToRecord(rData);

		// consume or create version assignment
		Optional<VersionAssignment> ova = versionAssignmentService.getVersionAssignment(rData.getComponent(), rData.getVersion());
		if (ova.isPresent() && null != ova.get().getRelease()) {
			//Release already exists, proceding with an update to the existing release
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(ova.get().getRelease());
			if(ord.isEmpty())
				throw new RelizaException("Cannot find the existing release data associated with the version = " + rData.getVersion());
			
			//allow updates only from a PENDING lifecycle
			ReleaseData existingRd = ord.get();
			if (existingRd.getLifecycle() != ReleaseLifecycle.PENDING)
				throw new RelizaException("Cannot create release because this version already belongs to another non-pending release, version = " + rData.getVersion());
			
			r = updateReleaseLifecycle(existingRd.getUuid(), releaseDto.getLifecycle(), wu);
			
			releaseDto.setUuid(existingRd.getUuid());
			r = updateRelease(releaseDto, UpdateReleaseStrength.DRAFT_PENDING, wu);
			rData = ReleaseData.dataFromRecord(r);
		} else if (ova.isPresent()) {
			r = saveRelease(r, recordData, wu);
			rData = ReleaseData.dataFromRecord(r);
			variantService.ensureBaseVariantForRelease(rData, wu);
			VersionAssignment va = ova.get();
			va.setRelease(r.getUuid());
			va.setAssignmentType(AssignmentTypeEnum.ASSIGNED);
			versionAssignmentService.saveVersionAssignment(va);
		} else { // ova empty
			r = saveRelease(r, recordData, wu);
			rData = ReleaseData.dataFromRecord(r);

			variantService.ensureBaseVariantForRelease(rData, wu);
			versionAssignmentService.createNewVersionAssignment(rData.getBranch(), rData.getVersion(), r.getUuid());
		}
		if (rData.getLifecycle() == ReleaseLifecycle.PENDING) {
			notificationService.processReleaseEvent(rData, bdOpt.get(), ReleaseEventType.RELEASE_SCHEDULED);
		} else {
			notificationService.processReleaseEvent(rData, bdOpt.get(), ReleaseEventType.NEW_RELEASE);
			if (rData.getLifecycle() == ReleaseLifecycle.ASSEMBLED ) {
				autoIntegrateProducts(rData);
			}
		}
		return r;
	}

	
}
