/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Commit;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Component.Type;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.Hash.Algorithm;
import org.cyclonedx.model.Pedigree;
import org.cyclonedx.model.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ReleaseEventType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.UuidDiff;
import io.reliza.common.ValidationResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ConditionGroup;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseApprovalEvent;
import io.reliza.model.ReleaseData.ReleaseApprovalInput;
import io.reliza.model.ReleaseData.ReleaseApprovalProgrammaticInput;
import io.reliza.model.ReleaseData.ReleaseBom;
import io.reliza.model.ReleaseData.ReleaseDataExtended;
import io.reliza.model.ReleaseData.ReleaseDateComparator;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.AssignmentTypeEnum;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.changelog.CommitType;
import io.reliza.model.changelog.ConventionalCommit;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.dto.AnalyticsDtos.VegaDateValue;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ComponentJsonDto;
import io.reliza.model.dto.ComponentJsonDto.ComponentJsonDtoBuilder;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.versioning.Version.VersionHelper;
import io.reliza.versioning.VersionApi;
import io.reliza.versioning.VersionApi.ActionEnum;
import io.reliza.versioning.VersionUtils;

@Service
public class ReleaseService {
	
	@Autowired
	private DeliverableService deliverableService;
	
	@Autowired
	private AuditService auditService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private NotificationService notificationService;
	
	@Autowired
	private SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	private VersionAssignmentService versionAssignmentService;
	
	@Autowired
	private ChangeLogService changeLogService;
	
	@Autowired
	private OrganizationService organizationService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private RebomService rebomService;
	
	@Autowired
	private VariantService variantService;
	
	@Autowired
	private ArtifactService artifactService;

	private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);
			
	private final ReleaseRepository repository;
	
	private static final String HELM_MIME_TYPE = "application/vnd.cncf.helm.config.v1+json"; // refer to https://github.com/opencontainers/artifacts/blob/main/artifact-authors.md
  
	public record CommitRecord(String commitUri, String commitId, String commitMessage, String commitAuthor, String commitEmail) {}
	
	public record TicketRecord(String ticketSubject, List<ChangeRecord> changes) {}
	
	public record ReleaseRecord(UUID uuid, String version, List<ChangeRecord> changes) {}
	
	public record ChangeRecord(String changeType, List<CommitMessageRecord> commitRecords) {}
	
	public record CommitMessageRecord(String linkifiedText, String rawText, String commitAuthor, String commitEmail) {}
	
	ReleaseService(ReleaseRepository repository) {
		this.repository = repository;
	}
	
	public Optional<Release> getRelease (UUID uuid) {
		return sharedReleaseService.getRelease(uuid);
	}
	
	public Optional<Release> getRelease (UUID releaseUuid, UUID orgUuid) {
		return sharedReleaseService.getRelease(releaseUuid, orgUuid);
	}
	
	public Optional<ReleaseData> getReleaseData (UUID uuid) {
		return sharedReleaseService.getReleaseData(uuid);
	}
	
	@Transactional
	public Optional<Release> getReleaseWriteLocked (UUID uuid) {
		return repository.findByIdWriteLocked(uuid);
	}
	 
	public Optional<ReleaseData> getReleaseData (UUID uuid, UUID myOrgUuid) throws RelizaException {
		return sharedReleaseService.getReleaseData(uuid, myOrgUuid);
	}
	
	private Optional<Release> getReleaseByComponentAndVersion (UUID component, String version) {
		return repository.findByComponentAndVersion(component.toString(), version);
	}
	
	public Optional<ReleaseData> getReleaseDataByComponentAndVersion (UUID componentUuid, String version) {
		Optional<ReleaseData> rData = Optional.empty();
		Optional<Release> r = getReleaseByComponentAndVersion(componentUuid, version);
		if (r.isPresent()) {
			rData = Optional
							.of(
								ReleaseData
									.dataFromRecord(r
										.get()
								));
		}
		return rData;
	}

	public List<ReleaseData> getReleaseDataList (Collection<UUID> uuidList, UUID org) {
		var rlzList = new LinkedList<ReleaseData>();
		uuidList.forEach(uuid -> {
			try {
				var rlzO = getReleaseData(uuid, org);
				if (rlzO.isPresent()) {
					rlzList.add(rlzO.get());
				} else {
					log.warn("Could not locate releaze with UUID = " + uuid + " from org = " + org);
				}
			} catch (RelizaException re) {
				log.error("exception on get release data", re);
			}
		});
		return rlzList;
	}

	public List<Release> getReleases (Iterable<UUID> uuids) {
		return (List<Release>) repository.findAllById(uuids);
	}
	
	public List<ReleaseData> getReleaseDataList (Iterable<UUID> uuids) {
		List<Release> branches = getReleases(uuids);
		return branches.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toList());
	}

	public List<Release> listReleasesOfOrg (UUID orgUuid) {
		return repository.findReleasesOfOrg(orgUuid
															.toString());
	}
	
	private List<Release> listReleasesOfOrgAfterDate (UUID orgUuid, ZonedDateTime cutOffDate) {
		return repository.findReleasesOfOrgAfterDate(orgUuid.toString(), cutOffDate);
	}

	private List<Release> listPendingReleasesAfterCutoff (Long cutOffHours) {
		LocalDateTime cutOffDate = LocalDateTime.now();
		cutOffDate = cutOffDate.minusHours(cutOffHours);
		return repository.findPendingReleasesAfterCutoff(ReleaseLifecycle.PENDING.toString(), cutOffDate.toString());
	}
	
	public List<ReleaseData> listReleaseDataOfOrg (UUID orgUuid) {
		return listReleaseDataOfOrg(orgUuid, true);
	}
	
	public List<ReleaseData> listReleaseDataOfOrg (UUID orgUuid, Boolean includePlaceHolder) {
		List<Release> releases = listReleasesOfOrg(orgUuid);
		return releases
						.stream()
						.map(ReleaseData::dataFromRecord)
						.filter(release -> !release.getVersion().contains("Placeholder") || includePlaceHolder)
						.collect(Collectors.toList());
	}
	
	private List<Release> getReleasesByCommitOrTag (String commit, UUID orgUuid) {
		List<Release> releases = new LinkedList<>();
		List<SourceCodeEntry> sceList = sourceCodeEntryService.getSourceCodeEntriesByCommitTag(orgUuid, commit);
		// TODO see if we can query db by array later
		sceList.forEach(sce -> {
			releases.addAll(repository.findReleaseBySce(sce.getUuid().toString(), orgUuid.toString()));
		});
		return releases;
	}
	
	public List<ReleaseData> getReleaseDataByCommitOrTag (String commit, UUID orgUuid) {
		List<Release> releases = getReleasesByCommitOrTag(commit, orgUuid);
		return releases
				.stream()
				.map(ReleaseData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public Optional<Release> getLatestReleaseBySce (UUID sce, UUID orgUuid) {
		return repository.findLatestReleaseBySce(sce.toString(), orgUuid.toString());
	}
	
	public Optional<ReleaseData> findLatestReleaseDataByTicketAndOrg (UUID ticket, UUID org) {
		
		Optional<ReleaseData> ord = Optional.empty();
		Optional<Release> or = Optional.empty();
		
		//Find the latest commit which references the ticket
		Optional<SourceCodeEntry> osce = sourceCodeEntryService.findLatestSceWithTicketAndOrg(ticket, org);
		
		//Find the commit's Release
		if(osce.isPresent())
			or = getLatestReleaseBySce(osce.get().getUuid(), org);
			
		if(or.isPresent())
			ord = Optional.of(ReleaseData.dataFromRecord(or.get()));
		
		return ord;
	}
	/**
	 * We expect no more than one release here, but will log warn if not
	 * @param deliverableUuid
	 * @param orgUuid - organization UUID - will only search for this org + external org
	 * @return
	 */
	public Optional<ReleaseData> getReleaseByOutboundDeliverable (UUID deliverableUuid, UUID orgUuid) {
		Optional<Release> or = Optional.empty();
		List<Release> releases = repository.findReleasesByDeliverable(deliverableUuid.toString(), orgUuid.toString());
		if (null != releases && !releases.isEmpty()) {
			or = Optional.of(releases.get(0));
			if (releases.size() > 1) {
				log.warn("More than one release returned per deliverable uuid = " + deliverableUuid);
			}
		}
		Optional<ReleaseData> ord = Optional.empty();
		if (or.isPresent()) ord = Optional.of(ReleaseData.dataFromRecord(or.get()));
		return ord;
	}
	
	public List<Release> findReleasesByArtifact (UUID artifactUuid, UUID orgUuid) {
		return repository.findReleasesByArtifact(artifactUuid.toString(), orgUuid.toString());
	}
	
	public Optional<ReleaseData> getReleaseDataByOutboundDeliverableDigest (String digest, UUID orgUuid) {
		Optional<ReleaseData> ord = Optional.empty();
		Optional<DeliverableData> oad = deliverableService.getDeliverableDataByDigest(digest, orgUuid);
		if (oad.isPresent()) {
			// locate lowest level release referencing this artifact
			// note that org uuid may be external that's why it may be different
			ord = getReleaseByOutboundDeliverable(oad.get().getUuid(), oad.get().getOrg());
		}
		return ord;
	}
	
	public List<ReleaseData> listReleaseDataByBuildId (String query, UUID orgUuid) {
		List<ReleaseData> releases = new LinkedList<>();
		List<DeliverableData> deliverables = deliverableService.getDeliverableDataByBuildId(query, orgUuid);
		deliverables.forEach(d -> {
			Optional<ReleaseData> ord = getReleaseByOutboundDeliverable(d.getUuid(), d.getOrg());
			if (ord.isPresent()) {
				releases.add(ord.get());
			}
		});
		return releases;
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
		if (null == releaseDto.getStatus()) releaseDto.setStatus(ReleaseStatus.ACTIVE);
		if (null == releaseDto.getLifecycle()) releaseDto.setLifecycle(ReleaseLifecycle.DRAFT);

		List<UUID> allCommits = new LinkedList<>(); 
		
		if(null != releaseDto.getSourceCodeEntry() )
			allCommits.add(releaseDto.getSourceCodeEntry());
		if(null!= releaseDto.getCommits() && !releaseDto.getCommits().isEmpty())
			allCommits.addAll(releaseDto.getCommits());
		//handle tickets
		if(!allCommits.isEmpty()){
			Set<UUID> tickets = sourceCodeEntryService.getTicketsList(allCommits, List.of(releaseDto.getOrg(), CommonVariables.EXTERNAL_PROJ_ORG_UUID));
			if(!tickets.isEmpty()){
				releaseDto.setTickets(tickets);
			}
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
			Optional<ReleaseData> ord = getReleaseData(ova.get().getRelease());
			if(ord.isEmpty())
				throw new RelizaException("Cannot find the existing release data associated with the version = " + rData.getVersion());
			
			//allow updates only from a PENDING lifecycle
			ReleaseData existingRd = ord.get();
			if (existingRd.getLifecycle() != ReleaseLifecycle.PENDING)
				throw new RelizaException("Cannot create release because this version already belongs to another non-pending release, version = " + rData.getVersion());
			
			r = updateReleaseLifecycle(existingRd.getUuid(), releaseDto.getLifecycle(), wu);
			
			releaseDto.setUuid(existingRd.getUuid());
			r = updateRelease(releaseDto, wu);
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
	
	@Transactional
	public ComponentJsonDto createReleaseAndGetChangeLog(SceDto sourceCodeEntry, List<SceDto> commitList,
			String nextVersion, ReleaseLifecycle lifecycleResolved, BranchData bd, WhoUpdated wu) throws Exception{
		ComponentJsonDto changelog = null;

		//check if source code details are present and create a release with these details and version
		Optional<SourceCodeEntryData> osced = Optional.empty();
		// also check if commits (base64 encoded) are present and if so add to release
		List<UUID> commits = new LinkedList<>();

		if (sourceCodeEntry != null || commitList != null) {
			// parse list of associated commits obtained via git log with previous CI build if any (note this may include osce)
			if (commitList != null) {
				ComponentData cd = getComponentService.getComponentData(bd.getComponent()).get();
				for (var com : commitList) {
					var parsedCommit = parseSceFromReleaseCreate(com, List.of(), bd, bd.getName(), nextVersion, wu);
					if (parsedCommit.isPresent()) {
						commits.add(parsedCommit.get().getUuid());
					}
				}
				
				// use the first commit of commitlist to fill in the missing fields of source code entry
				if (!commitList.isEmpty() && null == sourceCodeEntry) {
					sourceCodeEntry = commitList.get(0);
				} else if (!commitList.isEmpty() 
						&& null != sourceCodeEntry && sourceCodeEntry.getCommit().equalsIgnoreCase(commitList.get(0).getCommit())) {
					SceDto com = commitList.get(0);
					sourceCodeEntry.setCommitAuthor(com.getCommitAuthor());
					sourceCodeEntry.setCommitEmail(com.getCommitEmail());
					sourceCodeEntry.setDate(com.getDate());
					log.info("RGDEBUG: updated sceDTO = {}", sourceCodeEntry);
				}
			}
			if (null != sourceCodeEntry && StringUtils.isNotEmpty(sourceCodeEntry.getCommit())) {
				// Can't create osce without commit field
				sourceCodeEntry.setBranch(bd.getUuid());
				sourceCodeEntry.setOrganizationUuid(bd.getOrg());
				sourceCodeEntry.setVcsBranch(bd.getName());
				osced = sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(
					sourceCodeEntry,
					true,
					wu
				);
			}
		}
	
		var releaseDtoBuilder = ReleaseDto.builder()
							.branch(bd.getUuid())
							.org(bd.getOrg())
							.commits(commits);

		if (osced.isPresent()) {
			releaseDtoBuilder.sourceCodeEntry(osced.get().getUuid());
		}
		
		Release release = null;
		try {
			releaseDtoBuilder.version(nextVersion)
							.lifecycle(lifecycleResolved);
			release = createRelease(releaseDtoBuilder.build(), wu);
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
		
		Optional<ReleaseData> latestRelease = getReleasePerProductComponent(bd.getOrg(), bd.getComponent(), null, bd.getName(), null);
		if (latestRelease.isPresent()) {
			changelog = getChangelogBetweenReleases(
					ReleaseData.dataFromRecord(release).getUuid(), latestRelease.get().getUuid(), bd.getOrg(), AggregationType.AGGREGATED, null);
		}
		
		return changelog;
	}

	@Transactional
	public Release updateReleaseLifecycle (UUID releaseId, ReleaseLifecycle newLifecycle, WhoUpdated wu) {
		return updateReleaseLifecycle(releaseId, newLifecycle, wu, true);
	}
	
	@Transactional
	public Release updateReleaseLifecycle (UUID releaseId, ReleaseLifecycle newLifecycle, WhoUpdated wu, boolean considerTriggers) {
		Release r = getRelease(releaseId).get();
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
	public Release updateRelease (ReleaseDto releaseDto, WhoUpdated wu) throws RelizaException {
		return updateRelease(releaseDto, false, wu);
	}
	
	/**
	 * Updates release based on dto
	 * @param releaseDto
	 * @param force - if true, will update release regardless of status
	 * @param wu
	 * @return
	 * @throws RelizaException 
	 */
	@Transactional
	public Release updateRelease (ReleaseDto releaseDto, Boolean force, WhoUpdated wu) throws RelizaException {
		Release r = null;
		// locate and lock release in db
		Optional<Release> rOpt = getRelease(releaseDto.getUuid());
		if (rOpt.isPresent()) {
			r = rOpt.get();
			ReleaseData rData = ReleaseData.dataFromRecord(r);
			ReleaseLifecycle oldLifecycle = rData.getLifecycle();
			boolean isAssemblyAllowed = ReleaseLifecycle.isAssemblyAllowed(rData.getLifecycle());
			if (!isAssemblyAllowed && ReleaseDto.isAssemblyRequested(releaseDto) && !force) {
				throw new RelizaException("Cannot update assembled release with requested properties");
			}
			doUpdateRelease (r, rData, releaseDto, wu);
			processReleaseLifecycleEvents (rData, releaseDto.getLifecycle(), oldLifecycle);
		}
		
		return r;
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
	
	private Release doUpdateRelease (Release r, ReleaseData rData, ReleaseDto releaseDto, WhoUpdated wu) {
		log.debug("updating exisiting rd, with dto: {}", releaseDto);
		List<UuidDiff> artDiff = Utils.diffUuidLists(rData.getArtifacts(), releaseDto.getArtifacts());
		if (!artDiff.isEmpty()) {
			rData.setArtifacts(releaseDto.getArtifacts());
			artDiff.forEach(ad -> rData.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.ARTIFACT, ad.diffAction(), null, null,
					ad.object(), ZonedDateTime.now(), wu)));
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
		if (null != releaseDto.getReboms()) {
			rData.setReboms(releaseDto.getReboms());
		}
		Map<String,Object> recordData = Utils.dataToRecord(rData);
		log.debug("saving release with recordData: {}", recordData);
		r = saveRelease(r, recordData, wu);
		return r;
	}
	
	@Transactional
	public Optional<Release> addApproalsFromWs (UUID releaseId, List<ReleaseApprovalInput> approvals, final WhoUpdated wu) {
		List<ReleaseApprovalEvent> approvalEvents = approvals
				.stream()
				.map(x -> ReleaseData.approvalEventFromInput(x, wu)).toList();
		return addApprovals(releaseId, approvalEvents, wu);
	}
	
	@Transactional
	private Optional<Release> addApprovals (UUID releaseId, List<ReleaseApprovalEvent> approvalEvents, WhoUpdated wu) {
		Optional<Release> or = getRelease(releaseId);
		if (or.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(or.get());
			approvalEvents.forEach(ar -> rd.addApprovalEvent(ar));
			Map<String, Object> recordData = Utils.dataToRecord(rd);
			or = Optional.of(saveRelease(or.get(), recordData, wu));
		}
		return or;
		
	}

	@Transactional
	private Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu) {
		return saveRelease (r, recordData, wu, true);
	}
			
	@Transactional
	private Release saveRelease (Release r, Map<String,Object> recordData, WhoUpdated wu, boolean considerTriggers) {
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
		Optional<Release> or = getRelease(r.getUuid());
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
		return r;
	}
	
	public ReleaseData getComponentPlaceholderRelease (UUID projUuid) {
		List<Release> r = repository.findPlaceholderReleaseOfComponent(projUuid.toString());
		if (null == r || r.size() != 1) {
			throw new IllegalStateException("Illegal size of placeholder release list for project uuid = " + projUuid);
		}
		return ReleaseData.dataFromRecord(r.get(0));
	}
	
	public Optional<ReleaseData> matchReleaseGroupsToProductRelease(UUID featureSet, List<Set<UUID>> releaseGroups) {
		Optional<ReleaseData> ord = Optional.empty();
		var rgIterator = releaseGroups.iterator();
		while (ord.isEmpty() && rgIterator.hasNext()) {
			var rg = rgIterator.next();
			ord = matchToProductRelease(featureSet, rg);
		}
		return ord;
	}
	
	/**
	 * This method attempts to match supplied collection of releases to existing product release in a given feature set
	 * @param featureSet - UUID of feature set to search
	 * @param releases - Collection of UUIDs of releases to check for match
	 * @return Optional of ReleaseData, Optional is empty if no match, Optional contains matching ReleaseData if there is a match
	 */
	public Optional<ReleaseData> matchToProductRelease (UUID featureSet, Collection<UUID> releases) {
		Optional<ReleaseData> ord = Optional.empty();
		// locate candidates (all releases of feature set)
		// List<ReleaseData> releaseCandidates = listReleaseDataOfBranch(featureSet);
		// instead of listing release candidates, let's instead unwind release components and filter by branch - potentially, it's enough to unwind only one release and then further check
		
		// identify any ignored or transient projects
		var fsData = branchService.getBranchData(featureSet).get();
		List<ChildComponent> dependencies = fsData.getDependencies();
		final Map<UUID, ChildComponent> childComponents = dependencies.stream().collect(Collectors.toMap(x -> x.getUuid(), Function.identity()));
		
		var latestRd = sharedReleaseService.getReleaseDataOfBranch(fsData.getOrg(), featureSet, null);
		// gather non-ignored project ids
		Set<UUID> presentComponents = Set.of();
		if (latestRd.isPresent()) {
			var latestRlzComponents = latestRd.get().getParentReleases();
			presentComponents = latestRlzComponents.stream().map(x -> getReleaseData(x.getRelease()).get().getComponent()).collect(Collectors.toSet());
		}

		final Set<UUID> projectForIgnoreFiltering = new HashSet<>(presentComponents);

		// filter releases to match
		Set<UUID> releasesToMatch = new HashSet<>();
		Set<ReleaseData> releasesToFindProducts = new HashSet<>();
		Set<UUID> transientReleasesToMatch = new HashSet<>();
		releases.forEach(r -> {
			ReleaseData filterReleaseData = null;
			try {
				filterReleaseData = getReleaseData(r, fsData.getOrg()).get();
				ReleaseData rd = getReleaseData(r).get();
				var cp = childComponents.get(rd.getComponent());
				// since we may have legacy proxy release here, try that too
				if (null == cp) cp = childComponents.get(filterReleaseData.getComponent());
				if (null != cp) {
					if (cp.getStatus() == StatusEnum.TRANSIENT) {
						transientReleasesToMatch.add(filterReleaseData.getUuid());
						// if transient is present, also use that for locating products
						releasesToFindProducts.add(rd);
					} else if (cp.getStatus() != StatusEnum.IGNORED) {
						releasesToMatch.add(filterReleaseData.getUuid());
						releasesToFindProducts.add(rd);
					}
				} else if (projectForIgnoreFiltering.contains(rd.getComponent()) || projectForIgnoreFiltering.contains(filterReleaseData.getComponent())) {
					releasesToMatch.add(filterReleaseData.getUuid());
					releasesToFindProducts.add(rd);
				}
			} catch (RelizaException e) {
				log.error("Exception on fetching release on match", e);
			}
		});
		
		log.debug("PSDEBUG: releases to match before = " + releasesToMatch.toString());
		log.debug("PSDEBUG: transient releases to match before = " + transientReleasesToMatch.toString());
		if (!releasesToMatch.isEmpty()) {
			// locate candidates - this would be products of one of the releases, belonging to desired featureset
			Set<ReleaseData> releaseCandidates = new HashSet<>();
			long matchingStart = System.currentTimeMillis();
			Set<ReleaseData> rdPreCandidates = greedylocateProductsOfReleaseCollection(releasesToFindProducts, fsData.getOrg());
			long preCandidateTime = System.currentTimeMillis();
			log.info("Gather pre candidates time = " + (preCandidateTime - matchingStart));
			log.debug("PSDEBUG: product release candidates before filter = " + rdPreCandidates.toString());
			if (null != rdPreCandidates && !rdPreCandidates.isEmpty()) {
				releaseCandidates = rdPreCandidates.stream().filter(r -> featureSet.equals(r.getBranch())).collect(Collectors.toSet());
			}
			
			// sort by date
			var rcSortedList = new LinkedList<ReleaseData>(releaseCandidates);
			Collections.sort(rcSortedList, new ReleaseDateComparator());
			log.debug("PSDEBUG: product release candidates after filter = " + releaseCandidates.toString());
			log.info("Size of product candidates = " + releaseCandidates.size());
			
			// the logic on TRANSIENT- if both sides have transient release, we actually want them to match, but if one of the sides does not have it we still match
			// due to db query we already checked here that everything from instance is in the product
			// we now need to check that everything core from the product is in the instance as well
			// transients are essentially handled by now
			
			// iterate over releaseCandidates and see if any of them matches
			Iterator<ReleaseData> rcIterator = rcSortedList.iterator();
			while (rcIterator.hasNext() && ord.isEmpty()) {
				ReleaseData rc = rcIterator.next();
				Set<UUID> coreReleases = rc.getCoreParentReleases();
				Set<UUID> coreReleasesInFeatureSet = new HashSet<>();
				// get release data in bulk to optimize performance
				var coreReleasesRd = getReleaseDataList(coreReleases, fsData.getOrg())
						.stream()
						.collect(Collectors.toMap(x -> x.getUuid(), Function.identity()));
				// optimization - if we have a core release that is not in feature set, exit right away - it won't match
				boolean coreMayMatch = true;
				var coreOnInstIterator = coreReleases.iterator();
				while (coreMayMatch && coreOnInstIterator.hasNext()) {
					var r = coreOnInstIterator.next();
					ReleaseData filterReleaseData = null;
					try {
						filterReleaseData = coreReleasesRd.get(r);
						// handle proxy releases
						if (null == filterReleaseData) filterReleaseData = getReleaseData(r, fsData.getOrg()).get();
						ReleaseData nonProxyReleaseData = coreReleasesRd.get(r);
						if (null == nonProxyReleaseData) nonProxyReleaseData = getReleaseData(r).get();
						var cp = childComponents.get(nonProxyReleaseData.getComponent());
						// since we may have legacy proxy release here, try that too
						if (null == cp) cp = childComponents.get(filterReleaseData.getComponent());
						if (null == cp || (cp.getStatus() != StatusEnum.IGNORED && cp.getStatus() != StatusEnum.TRANSIENT)) {
							coreReleasesInFeatureSet.add(filterReleaseData.getUuid());
							if (!releasesToMatch.contains(filterReleaseData.getUuid())) { 
								coreMayMatch = false;
								log.info("didn't match because of  uuid = " + filterReleaseData.getUuid() + " , version = " + filterReleaseData.getVersion());
							}
						}
					} catch (RelizaException e) {
						log.error("Exception on fetching release on match", e);
						coreMayMatch = false;
					}
				}
				
				if (coreMayMatch) {
					log.debug("PSDEBUG: product release candidate = " + rc.getVersion());
					log.debug("PSDEBUG: core releases to match = " + coreReleasesInFeatureSet.toString());
					log.debug("PSDEBUG: core releases on instance = " + releasesToMatch);

					if (coreReleasesInFeatureSet.equals(releasesToMatch)) {
						log.debug("PSDEBUG: returning ord = " + rc.getVersion());
						ord = Optional.of(rc);
					}
				}
			}
			log.info("Matching time after greedy match = " + (System.currentTimeMillis() - preCandidateTime));
		}
		return ord;
	}
	
	/**
	 * Aggregates number of releases per time unit per defined time frame
	 * @param orgUuid
	 * @return
	 */
	public List<VegaDateValue> getReleaseCreateOverTimeAnalytics(UUID orgUuid, ZonedDateTime cutOffDate) {
		List<Release> releases = listReleasesOfOrgAfterDate(orgUuid, cutOffDate);
		return releases.stream()
				.map(r -> new VegaDateValue(r.getCreatedDate().toString(), Long.valueOf(1))).toList();
	}

	/**
	 * Looks for all releases with this specific version within specified organization uuid
	 * Note that this does not expand to external organization as is the case when we search by digests
	 * @param version
	 * @param orgUuid
	 * @return
	 */
	public List<Release> listReleaseByVersion(String version, UUID orgUuid) {
		List<Release> rlList = new LinkedList<>();
		if (StringUtils.isNotEmpty(version)) {
			rlList = repository.findReleasesOfOrgByVersion(orgUuid.toString(), version);
			if (rlList.isEmpty()) rlList = repository
					.findReleasesOfOrgByVersion(CommonVariables.EXTERNAL_PROJ_ORG_STRING, version);
		}
		return rlList;
	}
	
	public List<ReleaseData> listReleaseDataByVersion(String version, UUID orgUuid) {
		List<Release> releases = listReleaseByVersion(version, orgUuid);
		return releases
				.stream()
				.map(ReleaseData::dataFromRecord)
				.collect(Collectors.toList());
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
	
		List<ParentRelease> currentReleases = new ArrayList<ParentRelease>(getCurrentProductParentRelease(featureSetUuid, ReleaseLifecycle.DRAFT));
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

		List<ReleaseData> latestComponentRds = getReleaseDataList(latestComponentReleaseUuids, fs.getOrg());
		List<ReleaseData> currentComponentRds = getReleaseDataList(currentComponentReleaseUuids, fs.getOrg());

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
		List<ReleaseData> rds = listAllReleasesBetweenReleases(uuid1,  uuid2);
		rds.remove(rds.size()-1); //remove the extra element at the end of the list
		if(rds.size() > 0){
				List<SourceCodeEntryData> sceDataList = getSceDataListFromReleases(rds, org);
				List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
				Map<UUID, CommitRecord> commitIdToRecordMap = getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
				commits = commitIdToRecordMap.entrySet().stream().map(
							 e -> e.getValue().commitMessage).filter(Objects::nonNull).collect(Collectors.toList());
				
		}
		return commits;
	}
	
	/**
	 * This method recursively checks all release components, then components of those releases and finally flattens all that to a list
	 * @param releaseUuid
	 * @return List of releases that are dependencies to the release we are unwinding
	 */
	private Set<ReleaseData> unwindReleaseDependencies (ReleaseData rd) {
		Set<ReleaseData> retListOfReleases = new LinkedHashSet<>();
		Set<UUID> retListOfUuids = new HashSet<>(); // needed to check for circular links
		List<UUID> dependencies = rd.getParentReleases().stream().map(ParentRelease::getRelease).collect(Collectors.toList());
		// base case - no dependencies
		if (dependencies.isEmpty()) {
			return retListOfReleases;
		} else {
			List<ReleaseData> depsFromDb = getReleaseDataList(dependencies, rd.getOrg());
			depsFromDb.forEach(depData -> {
				// check we're not going in circles
				if (!retListOfUuids.contains(depData.getUuid())) {
					retListOfUuids.add(depData.getUuid());
					retListOfReleases.add(depData);
					// security check - org must either match base release of be known external org
					if (!depData.getOrg().equals(rd.getOrg()) && !depData.getOrg().equals(CommonVariables.EXTERNAL_PROJ_ORG_UUID)) {
						log.error("Security: Release from another organization with id " + depData.getUuid() + " is detected among dependencies of release " + rd.getUuid());
						throw new IllegalStateException("Security: Detected release from a different organization");
					} else {
						Set<ReleaseData> recursiveSet = unwindReleaseDependencies(depData);
						recursiveSet.forEach(recRl -> {
							if (!retListOfUuids.contains(recRl.getUuid())) {
								retListOfUuids.add(recRl.getUuid());
								retListOfReleases.add(recRl);
							}
						});
					}
				}
			});
		}
		
		return retListOfReleases;
	}
	
	/**
	 * Inverse of unwindReleaseDependencies - instead of unwinding dependencies
	 * this method recursively locates all products of which this release is part of
	 * and products that include those products
	 * @param rd
	 * @param setToBreakCircles - initialize to empty hash set - makes sure that products are accounted at max once
	 * @return
	 */
	public Set<ReleaseData> locateAllProductsOfRelease (ReleaseData rd, Set<UUID> setToBreakCircles) {
		Set<ReleaseData> products = new LinkedHashSet<>();
		List<Release> wipProducts = this.repository.findProductsByRelease(rd.getOrg().toString(),
				rd.getUuid().toString());
		if (!wipProducts.isEmpty()) {
			products = wipProducts.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toSet());
		}
		if (!products.isEmpty()) {
			for (ReleaseData brd : products) {
				if (!setToBreakCircles.contains(brd.getUuid())) {
					setToBreakCircles.add(brd.getUuid());
					products.addAll(locateAllProductsOfRelease(brd, setToBreakCircles));
				}
			}
		}
		return products;
	}
	
	
	/**
	 * This method will locate release products with only 1st level depth, without recursion
	 * Much more efficient than locateAllProductsOfRelease
	 * @param rd
	 * @return
	 */
	public Set<ReleaseData> greedylocateProductsOfRelease (ReleaseData rd) {
		return greedylocateProductsOfRelease(rd, null, true);
	}
	
	
	private Set<ReleaseData> greedylocateProductsOfReleaseCollection (Collection<ReleaseData> inputRds, UUID myOrg) {
		var inputSet = inputRds.stream().map(x -> x.getUuid()).collect(Collectors.toSet());
		// construct string for postgres query
		String inputArrStr = constructGreedyProductLocateQueryFromReleaseSet(inputSet);
		List<Release> releaseSet = repository.findProductsByReleases(myOrg.toString(), inputArrStr);
		log.debug("Size of product release set = " + releaseSet.size());
		return releaseSet.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toSet());
	}

	private String constructGreedyProductLocateQueryFromReleaseSet(Set<UUID> inputSet) {
		StringBuilder inputArrStringBuilder = new StringBuilder();
		inputArrStringBuilder.append("[");
		var inputIterator = inputSet.iterator();
		int i=0;
		while (inputIterator.hasNext()) {
			if (i==0) {
				inputArrStringBuilder.append("\"");
			} else {
				inputArrStringBuilder.append(",\"");
			}
			inputArrStringBuilder.append(inputIterator.next().toString());
			inputArrStringBuilder.append("\"");
			++i;
		}
		inputArrStringBuilder.append("]");
		String inputArrStr = inputArrStringBuilder.toString();
		log.debug("String to query for products = " + inputArrStr);
		return inputArrStr;
	}
	/**
	 * 
	 * @param rd
	 * @param myOrg - used in case we're dealing with external organization to pin to our org
	 * @return
	 */
	public Set<ReleaseData> greedylocateProductsOfRelease (ReleaseData rd, UUID myOrg, boolean sorted) {
		ReleaseData processingRd = null;
		if (null != myOrg) {
			try {
				var ord = getReleaseData(rd.getUuid(), myOrg);
				if (ord.isPresent()) processingRd = ord.get();
			} catch (RelizaException re) {
				log.error("Exception on getting releaxe on locate product", re);
			}
		} else {
			// legacy callers not supplying myorg or auth
			processingRd = rd;
			myOrg = rd.getOrg();
		}
		if (null != processingRd) {
			List<Release> wipProducts = this.repository.findProductsByRelease(myOrg.toString(),
					processingRd.getUuid().toString());
			var rdSet = wipProducts.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toSet());

			if (sorted) {
				List<ReleaseData> sortedRdList = new LinkedList<>(rdSet);
				Collections.sort(sortedRdList, new ReleaseDateComparator());
				rdSet = new LinkedHashSet<>(sortedRdList);
			}
			return rdSet;
		} else {
			return Set.of();
		}
	}
	
//	public boolean moveReleasesOfBranchToNewOrg(UUID branchUuid, UUID orgUuid, WhoUpdated wu) {
//		// locate
//		List<Release> releases = listReleasesOfBranch(branchUuid, 0, 0);
//		for (Release r : releases) {
//			ReleaseData rd = ReleaseData.dataFromRecord(r);
//			rd.setOrg(orgUuid);
//			saveRelease(r, Utils.dataToRecord(rd), wu);
//		}
//		return true;
//	}
	

	public JsonNode exportReleaseAsBom(UUID releaseUuid) {
		JsonNode output = null;
		Optional<ReleaseData> ord = getReleaseData(releaseUuid);
		if (ord.isPresent()) {
			List<Component> components = parseReleaseIntoCycloneDxComponents(releaseUuid);
			Set<ReleaseData> dependencies = unwindReleaseDependencies(ord.get());
			// TODO leverage artifacts in deployed releases correctly
			for (ReleaseData dependency : dependencies) {
				components.addAll(parseReleaseIntoCycloneDxComponents(dependency.getUuid()));
			}
			Bom bom = new Bom();
			for (Component c : components) {
				bom.addComponent(c);
			}
			// set component and metadata
			var orgData = organizationService.getOrganizationData(ord.get().getOrg()).get();
			var projData = getComponentService.getComponentData(ord.get().getComponent()).get();
			Component bomComponent = new Component();
			bomComponent.setName(projData.getName());
			bomComponent.setType(Type.APPLICATION);
			bomComponent.setVersion(ord.get().getVersion());
			Utils.setRelizaBomMetadata(bom, orgData.getName(), bomComponent);
			BomJsonGenerator generator = BomGeneratorFactory.createJson(org.cyclonedx.Version.VERSION_15, bom);
			try {
				output = generator.toJsonNode();
			} catch (Exception e) {
				log.error("error when generating cyclone dx bom", e);
			}
		}
		return output;
	}

	public List<DeliverableData> getAllDeliverableDataFromRelease(ReleaseData rd){
		// assume base variant for now - TODO
		ComponentData cd = getComponentService.getComponentData(rd.getComponent()).orElseThrow();
		List<UUID> deliverableUuids = new ArrayList<>();
		if (cd.getType() == ComponentType.PRODUCT) {
			Set<ReleaseData> dependencies = unwindReleaseDependencies(rd);
			deliverableUuids = dependencies
					.stream()
					.map(dep -> variantService.getBaseVariantForRelease(dep))
					.flatMap(
					dep -> {
						return dep.getOutboundDeliverables().stream();
					}
				).distinct().toList();
		} else {
			deliverableUuids = rd.getInboundDeliverables();
			deliverableUuids.addAll(variantService.getBaseVariantForRelease(rd).getOutboundDeliverables());
		}
		return deliverableService.getDeliverableDataList(deliverableUuids);
	}

	public String exportReleaseSbom(UUID releaseUuid, Boolean tldOnly, ArtifactBelongsTo belongsTo, BomStructureType structure, WhoUpdated wu) throws RelizaException, JsonProcessingException{
		ReleaseData rd = getReleaseData(releaseUuid).orElseThrow();
		RebomOptions mergeOptions = new RebomOptions(belongsTo, tldOnly, structure);
		UUID releaseBomId = matchOrGenerateSingleBomForRelease(rd, mergeOptions, wu);
		if(null == releaseBomId){
			throw new RelizaException("No SBOMs found!");
		}
		JsonNode mergedBomJsonNode = rebomService.findBomById(releaseBomId);
		String mergedBom = mergedBomJsonNode.toString();
	
		return mergedBom;
	}
	
	private UUID generateComponentReleaseBomForConfig(ReleaseData rd, RebomOptions rebomMergeOptions, WhoUpdated wu) throws RelizaException{
		UUID rebomId = null;
		List<UUID> bomIds = new ArrayList<>();
		final ArtifactBelongsTo typeFilter = rebomMergeOptions.belongsTo();
		// log.info("generateComponentReleaseBomForConfig: typeFilter: {}", typeFilter);

		if(null == typeFilter || typeFilter.equals(ArtifactBelongsTo.DELIVERABLE)){
			bomIds.addAll(
				getAllDeliverableDataFromRelease(rd).stream()
				.map(d -> d.getArtifacts())
				.flatMap(x -> x.stream())
				.map(a -> artifactService.getArtifactData(a))
				.filter(art -> art.isPresent() && null != art.get().getInternalBom())
				.map(a -> a.get().getInternalBom().id())
				.distinct()
				.toList()
			);
		}
		
		if(null == typeFilter || typeFilter.equals(ArtifactBelongsTo.SCE)){
			List<UUID> sceRebomIds  = null;
			if(null != rd.getSourceCodeEntry())
			sceRebomIds = sourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry()).get().getArtifacts().stream()
			.map(a -> artifactService.getArtifactData(a))
			.filter(art -> art.isPresent() && null != art.get().getInternalBom())
			.map(a -> a.get().getInternalBom().id())
			.distinct()
			.toList();
			if(null != sceRebomIds && sceRebomIds.size() > 0) bomIds.addAll(sceRebomIds);
		}
		
		if(null == typeFilter || typeFilter.equals(ArtifactBelongsTo.RELEASE)){
			List<UUID> releaseRebomIds  = null;
			releaseRebomIds = rd.getArtifacts().stream().map(a -> artifactService.getArtifactData(a))
			.filter(art -> art.isPresent() && null != art.get().getInternalBom())
			.map(a -> a.get().getInternalBom().id())
			.distinct()
			.toList();
			if(null != releaseRebomIds && releaseRebomIds.size() > 0) bomIds.addAll(releaseRebomIds);

		}
		log.info("RGDEBUG: generateComponentReleaseBomForConfig bomIds: {}", bomIds);
		// Call add bom on list

		if(bomIds.size() > 0){
			ComponentData pd = getComponentService.getComponentData(rd.getComponent()).get();
			OrganizationData od = organizationService.getOrganizationData(rd.getOrg()).get();
			var rebomOptions = new RebomOptions(pd.getName(), od.getName(), rd.getVersion(), rebomMergeOptions.belongsTo(), rebomMergeOptions.hash(), rebomMergeOptions.tldOnly(), rebomMergeOptions.structure(), rebomMergeOptions.notes() );
			rebomId = rebomService.mergeAndStoreBoms(bomIds, rebomOptions);
			
			addRebom(rd, new ReleaseBom(rebomId, rebomMergeOptions), wu);
		}else if (bomIds.size() > 0){
			rebomId = bomIds.get(0);
		}
		
		return rebomId;
	}
	
	
	// TODO shouldn't be called as get as it may mutate data
	// returns a single rebomId if present or recursively gather, merge and save as a single bom to return a single rebomId
	private UUID matchOrGenerateSingleBomForRelease(ReleaseData rd, RebomOptions rebomMergeOptions, WhoUpdated wu) throws RelizaException {
		return matchOrGenerateSingleBomForRelease(rd, rebomMergeOptions, false, null, wu);
	}
	private UUID matchOrGenerateSingleBomForRelease(ReleaseData rd, RebomOptions rebomMergeOptions, Boolean forced, UUID componentFilter, WhoUpdated wu) throws RelizaException {
		// for component structure and 
		UUID retRebomId = null;
		// match with request
		// log.info("rebomMergeOptions: {}",rebomMergeOptions);
		// log.info("rdReboms: {}",rd.getReboms() );
		ReleaseBom matchedBom = rd.getReboms().stream()
			.filter(rb -> 	
			Objects.equals(rb.rebomMergeOptions().belongsTo(), rebomMergeOptions.belongsTo()) 
				&& rb.rebomMergeOptions().tldOnly().equals(rebomMergeOptions.tldOnly()) 
				&& rb.rebomMergeOptions().structure().equals(rebomMergeOptions.structure())
			).findFirst().orElse(null);

		if(matchedBom == null || forced){
			ComponentData pd = getComponentService.getComponentData(rd.getComponent()).get();
			if(pd.getType().equals(ComponentType.COMPONENT)){
				retRebomId = generateComponentReleaseBomForConfig(rd, rebomMergeOptions, wu);
			} else {
				// TODO:
				// we don't need full unwind here, 
				// let's say at a level there's a product and a component release then
				// - we would want a merged product bom at the same level with a component release
				// - so this function should be responsible for recursive unwidning instead of relying upon unwindReleaseDeps ... 
				// alternate for full unwinding?
				var morerds = unwindReleaseDependencies(rd);
				LinkedList<UUID> bomIds = morerds.stream().map(r -> {
					try {
						var forceComponent = forced && componentFilter != null ? r.getUuid().equals(componentFilter) : false;
						return matchOrGenerateSingleBomForRelease(r, rebomMergeOptions, forceComponent, null, wu);
					} catch (RelizaException e) {
						log.error("error on getting release in matchOrGenerateSingleBomForRelease", e);
						return new UUID(0,0);
					}
				}).filter(Objects::nonNull).filter(r -> !(new UUID(0,0)).equals(r)).collect(Collectors.toCollection(LinkedList::new));
				if(bomIds != null && !bomIds.isEmpty()){
					if(bomIds.size() == 1){
						retRebomId = bomIds.getFirst();
					} else {
						var od = organizationService.getOrganizationData(rd.getOrg()).get();
						var rebomOptions = new RebomOptions(pd.getName(), od.getName(), rd.getVersion(),  rebomMergeOptions.belongsTo(), rebomMergeOptions.hash(), rebomMergeOptions.tldOnly(), rebomMergeOptions.structure(), rebomMergeOptions.notes());
						UUID rebomId = rebomService.mergeAndStoreBoms(bomIds, rebomOptions);
						addRebom(rd, new ReleaseBom(rebomId, rebomMergeOptions), wu);
						retRebomId = rebomId;
					}
				}
			}
		} else {
			retRebomId = matchedBom.rebomId();
		}

		return retRebomId;
		
	}
	
	protected Component parseProductReleaseIntoCycloneDxComponent (ReleaseDataExtended rd) {
		Component c = new Component();
		String namespaceForGroup = (StringUtils.isEmpty(rd.namespace())) ? CommonVariables.DEFAULT_NAMESPACE : rd.namespace();
		c.setGroup(namespaceForGroup + "---" + rd.productName());
		c.setType(Component.Type.APPLICATION);
		c.setVersion(rd.releaseData().getVersion());
		ComponentData pd = getComponentService.getComponentData(rd.releaseData().getComponent()).get();
		c.setName(pd.getName());
		
		List<Property> props = new LinkedList<>();
		
		String helmAppVersion = Utils.resolveTagByKey(CommonVariables.HELM_APP_VERSION, rd.releaseData().getTags());
		if (StringUtils.isNotEmpty(helmAppVersion)) {
			var p = new Property();
			p.setName(CommonVariables.HELM_APP_VERSION);
			p.setValue(helmAppVersion);
			props.add(p);
		}
		
		if (null != rd.properties() && !rd.properties().isEmpty()) {
			List<Property> addProps = rd.properties().entrySet()
					.stream().map(x -> {
						var p = new Property();
						p.setName(x.getKey());
						p.setValue(x.getValue());
						return p;
					}).collect(Collectors.toList());
			props.addAll(addProps);
		}
		if (!props.isEmpty()) c.setProperties(props);
		return c;
	}
	
	public List<Component> parseCustomReleaseDataIntoCycloneDxComponents(ReleaseDataExtended rd) {
		List<Component> retComponents = new LinkedList<>();
		Pedigree cyclonePedigree = new Pedigree();
		String notes = rd.releaseData().getNotes();
		cyclonePedigree.setNotes(notes);
		// locate own sce if present
		UUID sourceCodeEntryUuid = rd.releaseData().getSourceCodeEntry();
		if (null != sourceCodeEntryUuid) {
			Optional<SourceCodeEntryData> osced = sourceCodeEntryService.getSourceCodeEntryData(sourceCodeEntryUuid);
			if (osced.isPresent()) {
				VcsRepositoryData vcsrd = vcsRepositoryService.getVcsRepositoryData(osced.get().getVcs()).get();
				Commit commit = new Commit();
				commit.setUid(osced.get().getCommit());
				commit.setUrl(vcsrd.getUri());
				if (StringUtils.isNotEmpty(osced.get().getCommitMessage())) {
					commit.setMessage(osced.get().getCommitMessage());
				}
				cyclonePedigree.setCommits(List.of(commit));
			}
		}
		// locate deliverables if present
		// TODO assuming base variant for now
		var baseVar = variantService.getBaseVariantForRelease(rd.releaseData());
		Set<UUID> deliverableUuids = baseVar.getOutboundDeliverables();
		String version = rd.releaseData().getVersion();
		ComponentData pd = getComponentService.getComponentData(rd.releaseData().getComponent()).get();
		if (pd.getType() == ComponentType.PRODUCT) {
			retComponents.add(parseProductReleaseIntoCycloneDxComponent(rd));
		} else if (null != deliverableUuids) {
			List<DeliverableData> dds = deliverableService.getDeliverableDataList(deliverableUuids);
			for (DeliverableData dd : dds) {
				var tags = dd.getTags();
				Boolean addedOnComplete = tags.stream().anyMatch(tr -> tr.key().equals(CommonVariables.ADDED_ON_COMPLETE) && tr.value().equalsIgnoreCase("true"));
				if(addedOnComplete)
					continue;
				Component c = new Component();
				c.setName(dd.getDisplayIdentifier());
				c.setGroup(rd.namespace() + "---" + rd.productName());
				Component.Type cycloneComponentType = CdxType.toCycloneDxType(dd.getType());
				c.setType(cycloneComponentType);
				
				
				List<Property> props = new LinkedList<>();
				// for helm chart - set mime type
				// TODO switch to automated way on sending mime type and resolving when
				// shipping artifact data
				if (pd.getKind() == ComponentKind.HELM) {
					c.setMimeType(HELM_MIME_TYPE);
					String helmAppVersion = Utils.resolveTagByKey(CommonVariables.HELM_APP_VERSION, rd.releaseData().getTags());
					if (StringUtils.isNotEmpty(helmAppVersion)) {
						var p = new Property();
						p.setName(CommonVariables.HELM_APP_VERSION);
						p.setValue(helmAppVersion);
						props.add(p);
					}
				}
				
				c.setVersion(version);
				
				if (null != rd.properties() && !rd.properties().isEmpty()) {
					List<Property> addProps = rd.properties().entrySet()
							.stream().map(x -> {
								var p = new Property();
								p.setName(x.getKey());
								p.setValue(x.getValue());
								return p;
							}).collect(Collectors.toList());
					props.addAll(addProps);
				}
				
				if (!props.isEmpty()) c.setProperties(props);

				List<Hash> hashes = new LinkedList<>();
				if (null != dd.getSoftwareMetadata()) {
					for (String digest : dd.getSoftwareMetadata().getDigests()) {
						String[] hashArr = digest.split(":");
						if (hashArr.length == 2) {
							Algorithm ha = Utils.resolveHashAlgorithm(hashArr[0].toLowerCase());
							if (null != ha) {
								Hash h = new Hash(ha, hashArr[1]);
								hashes.add(h);
							}
						}
					}
				}
				if (!hashes.isEmpty()) {
					c.setHashes(hashes);
				}
				c.setPedigree(cyclonePedigree);
				retComponents.add(c);
			}
		}
		return retComponents;
	}
	
	public List<Component> parseParentReleaseIntoCycloneDxComponents (ParentRelease dr) {
		List<Component> retComponents = new LinkedList<>();
		Optional<ReleaseData> ord = getReleaseData(dr.getRelease());
		if (ord.isPresent()) {
			// TODO: map parent releases to products
			var ed = new ReleaseDataExtended(ord.get(),
					CommonVariables.DEFAULT_NAMESPACE, CommonVariables.DEFAULT_NAMESPACE, null);
			retComponents = parseCustomReleaseDataIntoCycloneDxComponents(ed);
		}
		return retComponents;
	}
	
	private List<Component> parseReleaseIntoCycloneDxComponents (UUID releaseUuid) {
		List<Component> retComponents = new LinkedList<>();
		Optional<ReleaseData> ord = getReleaseData(releaseUuid);
		if (ord.isPresent()) {
			var ed = new ReleaseDataExtended(ord.get(),
					CommonVariables.DEFAULT_NAMESPACE,
					CommonVariables.DEFAULT_NAMESPACE, null);
			retComponents = parseCustomReleaseDataIntoCycloneDxComponents(ed);
		}
		return retComponents;
	}
	
	public ZonedDateTime getReleaseCommitDate(ReleaseData rd){
		ZonedDateTime commitDate = null;
		Optional<SourceCodeEntryData> osced = Optional.empty();

		if(rd.getSourceCodeEntry() != null)
			osced = sourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry());
		
		if(osced.isPresent())
			commitDate = osced.get().getDateActual();

		return commitDate;
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
			List<ReleaseData> components = unwindReleaseDependencies(optRd.get())
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
	
	public List<ReleaseData> getPendingReleases(){
		List<Release> releases = listPendingReleasesAfterCutoff( Long.valueOf(2  /* hours */ ));
		return releases.stream()
		.map(ReleaseData::dataFromRecord)
		.collect(Collectors.toList());
	}

	public void rejectPendingReleases(){
		getPendingReleases().stream().forEach(rd -> {
			log.debug("cancelling pending release : uuid {}",rd.getUuid());
			updateReleaseLifecycle(rd.getUuid(), ReleaseLifecycle.CANCELLED, WhoUpdated.getAutoWhoUpdated());
		});
	}
	
	@Transactional
	public List<UUID> uploadSceArtifacts (List<Map<String, Object>> arts, OrganizationData od, SceDto sceDto,
			ComponentData cd, String version, WhoUpdated wu) {
		List<UUID> artIds = new ArrayList<>();
		if(null != arts && !arts.isEmpty()){
			for (Map<String, Object> artMap : arts) {
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
					artId = artifactService.uploadArtifact(artDto, od.getUuid(), file.getResource(), new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.SCE, sceDto.getCommit()), wu);
				} catch (Exception e) {
					log.error("Exception on uploading artifact", e);
					throw new RuntimeException(e); // Re-throw the exception
				}
				if (null != artId) artIds.add(artId);
			}
		}
		return artIds;
		
	}
	
	@Transactional
	public Optional<SourceCodeEntryData> parseSceFromReleaseCreate (SceDto sceDto, List<UUID> artIds, BranchData bd,
			String branchStr,
			String version,
			WhoUpdated wu) throws IOException {
		UUID orgUuid = bd.getOrg();
		sceDto.setArtifacts(artIds);
		sceDto.setBranch(bd.getUuid());
		sceDto.setOrganizationUuid(orgUuid);
		sceDto.setVcsBranch(branchStr);
		sceDto.cleanMessage();
		var osced = sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(
			sceDto,
			true,
			wu
		);
		return osced;
	}

	public List<Release> listReleasesByComponent (UUID projectUuid) {
		return repository.listReleasesByComponent(projectUuid.toString());
	}

	public List<ReleaseData> listReleaseDataByComponent (UUID projectUuid) {
		List<Release> releases = listReleasesByComponent(projectUuid);
		return releases.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toList());
	}
	
	public List<Release> listReleasesByComponents (Collection<UUID> projectUuids) {
		return repository.listReleasesByComponents(projectUuids);
	}

	public List<ReleaseData> listReleaseDataByComponents (Collection<UUID> projectUuids) {
		List<Release> releases = listReleasesByComponents(projectUuids);
		return releases.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toList());
	}
	
	public List<ReleaseData> findReleasesByTags (UUID orgUuid, UUID branchUuid, String tagKey, String tagValue) {
		
		List<Release> releases = new LinkedList<>();
		if (StringUtils.isEmpty(tagValue) && null == branchUuid) {
			releases = repository.findReleasesByTagKey(orgUuid.toString(), tagKey);
		} else if (StringUtils.isEmpty(tagValue) && null != branchUuid) {
			releases = repository.findBranchReleasesByTagKey(orgUuid.toString(), branchUuid.toString(), tagKey);
		} else if (StringUtils.isNotEmpty(tagValue) && null == branchUuid) {
			releases = repository.findReleasesByTagKeyAndValue(orgUuid.toString(), tagKey, tagValue);
		} else {
			releases = repository.findBranchReleasesByTagKeyAndValue(orgUuid.toString(), branchUuid.toString(), tagKey, tagValue);
		}
		
		List<ReleaseData> rds = releases.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toList());
		return rds;
	}

	//both dates are inclusive
	private List<Release> listReleasesOfBranchBetweenDates (UUID branchUuid,  ZonedDateTime fromDateTime, ZonedDateTime toDateTime) {
		return repository.findReleasesOfBranchBetweenDates(branchUuid.toString(), Utils.stringifyZonedDateTimeForSql(fromDateTime),
				Utils.stringifyZonedDateTimeForSql(toDateTime));
	}

	public List<ReleaseData> listAllReleasesBetweenReleases(UUID uuid1, UUID uuid2) throws RelizaException{
		List<ReleaseData> rds = new LinkedList<ReleaseData>();

		boolean proceed = false;
		Optional<ReleaseData> or1 = getReleaseData(uuid1);
		Optional<ReleaseData> or2 = getReleaseData(uuid2);
		ReleaseData r1 = null;
		ReleaseData r2 = null;

		proceed = or1.isPresent() && or2.isPresent();

		if(proceed){
			List<Release> releases = new LinkedList<>();
			
			r1 = or1.get();
			r2 = or2.get();
			boolean onSameBranch = r1.getBranch().equals(r2.getBranch());
			var rdc = new ReleaseDateComparator();
			int comparision = rdc.compare(r1, r2);
			
			if(onSameBranch){
				if(comparision >= 0){
					releases = listReleasesOfBranchBetweenDates(
						r1.getBranch(), 
						r1.getCreatedDate(),
						r2.getCreatedDate()
					);
				} else {
					releases = listReleasesOfBranchBetweenDates(
						r1.getBranch(), 
						r2.getCreatedDate(),
						r1.getCreatedDate()
					);
				}
				if(releases.size() > 0)
					rds = releases
						.stream()
						.map(ReleaseData::dataFromRecord)
						.collect(Collectors.toList());
			} else if (comparision >= 0){
				rds.add(r1);
				rds.add(r2);
			} else {
				rds.add(r2);
				rds.add(r1);
			}
		}

		return rds;
	}
	
	public ComponentJsonDto getChangeLogJsonForReleaseDataList(List<ReleaseData> releases, UUID org, Boolean removeLast, ComponentData pd, AggregationType aggregationType, String userTimeZone) {
		ComponentJsonDtoBuilder projectJson = ComponentJsonDto.builder();
		var lastRelease = releases.get(0);
		var firstRelease = releases.get(releases.size() - 1);
		if(removeLast)
			firstRelease = releases.remove(releases.size()-1); //remove the extra element at the end of the list firstRelease
		
		if (aggregationType.equals(AggregationType.AGGREGATED)) {
			projectJson.org(org)
				.uuid(pd.getUuid())
				.name(pd.getName())
				.firstRelease(new ReleaseRecord(firstRelease.getUuid(), firstRelease.getVersion(), null))
				.lastRelease(new ReleaseRecord(lastRelease.getUuid(), lastRelease.getVersion(), null));
		}

		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		
		
		
		List<ComponentJsonDto> branchesJsonList = new ArrayList<>();
		// List<UUID> branchIds = releases.stream().map(rd -> rd.getBranch()).toList();

		Map<UUID, BranchData> branchMap = branchService.listBranchDataOfComponent(pd.getUuid(), null).stream().collect(Collectors.toMap(BranchData::getUuid, Function.identity()));
		LinkedHashMap<UUID, List<ReleaseData>> releasesGroupedByBranch = releases.stream().collect(
				Collectors.groupingBy(ReleaseData::getBranch, LinkedHashMap::new, Collectors.toList()));

		releasesGroupedByBranch.forEach((branchId, brReleases) -> {
			ComponentJsonDtoBuilder branchJson = ComponentJsonDto.builder();
			BranchData currentBranch = branchMap.get(branchId);
			branchJson.org(org)
				.uuid(branchId)
				.name(currentBranch.getName())
				;

			List<SourceCodeEntryData> sceDataList = getSceDataListFromReleases(brReleases, org);
			Map<UUID, CommitRecord> commitIdToRecordMap = getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
			
			Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap = new HashMap<>();
			switch (aggregationType) {
				case NONE:
					List<ReleaseRecord> releaseRecordList = new ArrayList<>();
					for (var release : brReleases) {
						Set<UUID> ids = release.getAllCommits();
						commitIdToConventionalCommitMap = commitIdToRecordMap.entrySet().stream()
								.filter(entry -> ids.contains(entry.getKey()))
								.collect(Collectors.toMap(
								Entry::getKey, e -> changeLogService.resolveConventionalCommit(e.getValue().commitMessage)));
						if(commitIdToConventionalCommitMap.size() > 0){
							releaseRecordList.add(new ReleaseRecord(release.getUuid(),
									release.getDecoratedVersionString(userTimeZone),
									prepareChangeRecordList(commitIdToConventionalCommitMap, commitIdToRecordMap)));
						}
					}
					branchJson.releases(releaseRecordList);
					break;
				case AGGREGATED:
					commitIdToConventionalCommitMap = commitIdToRecordMap.entrySet().stream().collect(Collectors.toMap(
							Entry::getKey, e -> changeLogService.resolveConventionalCommit(e.getValue().commitMessage)));
					branchJson.changes(prepareChangeRecordList(commitIdToConventionalCommitMap, commitIdToRecordMap));
					break;
			}
			branchesJsonList.add(branchJson.build());
		});
		
		projectJson.branches(branchesJsonList);

		return projectJson.build();
	}
	
	private List<ChangeRecord> prepareChangeRecordList(Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap, Map<UUID, CommitRecord> commitIdToRecordMap) {
		List<ChangeRecord> changeRecordList = new ArrayList<>();
		Map<UUID, ConventionalCommit> filteredCommitMap;
		for (CommitType commitType : CommitType.values()) {
			filteredCommitMap = commitIdToConventionalCommitMap.entrySet().stream()
					.filter(entry -> entry.getValue().getType() == commitType && !entry.getValue().isBreakingChange())
					.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
			if (!filteredCommitMap.isEmpty())
				changeRecordList.add(new ChangeRecord(commitType.getFullName(), prepareCommitMessageRecordList(filteredCommitMap, commitIdToRecordMap)));
		}
		
		filteredCommitMap = commitIdToConventionalCommitMap.entrySet().stream()
				.filter(entry -> entry.getValue().isBreakingChange())
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		if (!filteredCommitMap.isEmpty())
			changeRecordList.add(new ChangeRecord("BREAKING CHANGES", prepareCommitMessageRecordList(filteredCommitMap, commitIdToRecordMap)));
		return changeRecordList;
	}
	
	private List<CommitMessageRecord> prepareCommitMessageRecordList(Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap, Map<UUID, CommitRecord> commitIdToRecordMap) {
		List<CommitMessageRecord> commitRecords = new ArrayList<>();
		for (UUID commitId : commitIdToConventionalCommitMap.keySet()) {
			String commitAuthor = null;
			String commitEmail = null;
			String linkifiedText = null;
			String rawText = commitIdToConventionalCommitMap.get(commitId).getMessage();
			
			CommitRecord commitRecord = commitIdToRecordMap.get(commitId);
			if (StringUtils.isNotEmpty(commitRecord.commitUri) && StringUtils.isNotEmpty(commitRecord.commitId)) {
				linkifiedText = Utils.linkifyCommit(commitRecord.commitUri, commitRecord.commitId);
			}
			
			if (StringUtils.isNotEmpty(commitRecord.commitAuthor) && StringUtils.isNotEmpty(commitRecord.commitEmail)) {
				commitAuthor = commitRecord.commitAuthor;
				commitEmail = commitRecord.commitEmail;
			}
			commitRecords.add(new CommitMessageRecord(linkifiedText, rawText, commitAuthor, commitEmail));
		}
		return commitRecords;
	}

	public List<SourceCodeEntryData> getSceDataListFromReleases(List<ReleaseData> releases, UUID org){
		List<UUID> commitIds = releases
			.stream()
			.map(release -> release.getAllCommits())
			.flatMap(x -> x.stream())
			.map(x -> (null == x) ? new UUID(0,0) : x)
			.collect(Collectors.toList());
		List<SourceCodeEntryData> sces = new ArrayList<>();
		if(null!= commitIds && commitIds.size() > 0)
		{
			sces = sourceCodeEntryService.getSceDataList(commitIds, List.of(org, CommonVariables.EXTERNAL_PROJ_ORG_UUID));
		}
		return sces;
	}

	/**
	 * This method attempts to prepare a map of commit id to message for all commits
	 * @param sces - List of sce data
	 * @param org - UUID of the org
	 * @param commitIdToMessageMap - Map of commit id to commit, message, and uri for all commits in the releases
	 * @return Map of commit id to message for all commits
	 */
	private Map<UUID, CommitRecord> getCommitMessageMapForSceDataList(List<SourceCodeEntryData> sces, List<VcsRepositoryData> vrds, UUID org){
		
		Map<UUID, CommitRecord> commitIdToMessageMap = new HashMap<UUID, CommitRecord>();
		// List<SourceCodeEntryData> sces = getSceDataListFromReleases(releases, org);
		if(null!= sces && sces.size() > 0)
		{
			sces
			.stream()
			.filter(Objects::nonNull)
			.forEach(sce -> {
				String msg = StringUtils.isNotEmpty(sce.getCommitMessage()) ? sce.getCommitMessage() : "(No commit message)";
				List<VcsRepositoryData> vcsRepo = vrds.stream().filter(vrd -> vrd.getUuid().equals(sce.getVcs())).collect(Collectors.toList());
				String commitUri = vcsRepo.size() > 0 ? vcsRepo.get(0).getUri() : "";
				commitIdToMessageMap.put(sce.getUuid(), new CommitRecord(commitUri, sce.getCommit(), msg, sce.getCommitAuthor(), sce.getCommitEmail()));
			});  // Collectors.toMap throws npe on null values - JDK-8148463(https://bugs.openjdk.java.net/browse/JDK-8148463) 
				// .collect(Collectors.toMap(
				// 	SourceCodeEntryData::getUuid, 
				// 	SourceCodeEntryData::getCommitMessage
				// ));
		}
		return commitIdToMessageMap;
	}

	public ComponentJsonDto getChangelogBetweenReleases(UUID uuid1, UUID uuid2, UUID org, AggregationType aggregated, String userTimeZone) throws RelizaException{
		ComponentJsonDto changelog = null;
		//gets one extra element at the end for product component comparision
		List<ReleaseData> rds = listAllReleasesBetweenReleases(uuid1,  uuid2);
		if(rds.size() > 0){
			ReleaseData rd = rds.get(0);
			if(rd != null){
				Optional <ComponentData> opd = getComponentService.getComponentData(rd.getComponent());
				ComponentData pd = opd.get();
				if(pd.getType().equals(ComponentType.COMPONENT)){
					changelog = getChangeLogJsonForReleaseDataList(rds, org, true, pd, aggregated, userTimeZone);
				}
				else changelog = getChangeLogJsonForProductReleaseDataList(rds, org, true, aggregated, userTimeZone);
			}
		}
		return changelog;
	}
	
	public ComponentJsonDto getChangeLogJsonForProductReleaseDataList(List<ReleaseData> productRds, UUID org,
			Boolean removeLast, AggregationType aggregationType, String userTimeZone){
		ComponentJsonDtoBuilder json = ComponentJsonDto.builder();
		Map<UUID, List<UUID>> productToComponentReleaseMap = new HashMap<UUID, List<UUID>>();

		ListIterator<ReleaseData> productIterator = productRds.listIterator(productRds.size());
		List<UUID> prevParents = null;
		while (productIterator.hasPrevious()) {
			ReleaseData curr = productIterator.previous();
			List<UUID> parents = curr.getParentReleases()
				.stream()
				.map(ParentRelease::getRelease)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

			List<UUID> originalParents = parents.stream().collect(Collectors.toList()); // create a copy that won't be modified
			if(prevParents != null && !prevParents.isEmpty())
				parents.removeAll(prevParents);

			productToComponentReleaseMap.put(curr.getUuid(), parents);
			prevParents = originalParents;
		}

		var productRdLast = productRds.get(0);
		var productRdFirst = productRds.get(productRds.size() - 1);

		if(removeLast){
			var removed = productRds.remove(productRds.size() - 1);
		}

		List<UUID> componentReleaseUuids = productToComponentReleaseMap.values()
				.stream()
				.flatMap(uuids -> uuids.stream())
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

		
		List<ReleaseData> componentComponentReleaseDataList = getReleaseDataList(componentReleaseUuids, org);
		List<SourceCodeEntryData> sceDataList = getSceDataListFromReleases(componentComponentReleaseDataList, org);
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		Map<UUID, CommitRecord> commitIdToRecordMap = getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
		Map<UUID, ReleaseData>  componentComponentUuidReleasesMap = componentComponentReleaseDataList
				.stream()
				.collect(Collectors.toMap(ReleaseData::getUuid, Function.identity()));

		Set<UUID> projects = componentComponentReleaseDataList.stream()
				.map(ReleaseData::getComponent)
				.collect(Collectors.toSet());
		
		List<ComponentData> projectsData = getComponentService.getListOfComponentData(projects);
		Map<UUID, ComponentData> projectDataMap = projectsData
				.stream()
				.collect(Collectors.toMap(ComponentData::getUuid, Function.identity()));
		
		if (aggregationType.equals(AggregationType.AGGREGATED)) {
			Optional <ComponentData> opd = getComponentService.getComponentData(productRdFirst.getComponent());
			ComponentData pd = opd.get();
			json.org(org)
				.uuid(pd.getUuid())
				.name(pd.getName())
				.firstRelease(new ReleaseRecord(productRdFirst.getUuid(), productRdFirst.getVersion(), null))
				.lastRelease(new ReleaseRecord(productRdLast.getUuid(), productRdLast.getVersion(), null));
			
			Map<UUID, List<ReleaseData>> groupedByComponentData = componentComponentReleaseDataList.stream().collect(
				Collectors.groupingBy(rd -> rd.getComponent()));
			List<ComponentJsonDto> projectList = new ArrayList<>();
			for (UUID project : groupedByComponentData.keySet()) {
				var releases = groupedByComponentData.get(project);
				BranchData bd = branchService.getBranchData(releases.get(0).getBranch()).get();
				releases.sort(new ReleaseData.ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
				projectList.add(getChangeLogJsonForReleaseDataList(releases, org, true, projectDataMap.get(project), aggregationType, userTimeZone));
			}
			json.components(projectList);
		} else {
			List<ComponentJsonDto> productReleaseRecordList = new ArrayList<>();
			for (var productRd : productRds) {
				Map<UUID, List<ReleaseData>> groupedByComponent =  productToComponentReleaseMap
						.get(productRd.getUuid()) // component release ids
						.stream()
						.filter(Objects::nonNull)
						.map(id -> componentComponentUuidReleasesMap.get(id)) // component release data list
						.filter(Objects::nonNull)
						.collect(Collectors.groupingBy(rd -> rd.getComponent()));
				
				List<ComponentJsonDto> projectsList = new ArrayList<>();
				for (var project : groupedByComponent.keySet()) {
					ComponentData pd =  projectDataMap.get(project);
					List<ReleaseRecord> releaseRecordList = new ArrayList<>();
					List<ReleaseData> projectReleases = groupedByComponent.get(project);
					List<ComponentJsonDto> branchesJsonList = new ArrayList<>();
					// List<UUID> branchIds = releases.stream().map(rd -> rd.getBranch()).toList();
			
					Map<UUID, BranchData> branchMap = branchService.listBranchDataOfComponent(project, null).stream().collect(Collectors.toMap(BranchData::getUuid, Function.identity()));
					Map<UUID, List<ReleaseData>> releasesGroupedByBranch = projectReleases.stream().collect(
							Collectors.groupingBy(rd -> rd.getBranch()));
					releasesGroupedByBranch.forEach((branchId, brReleases) -> {
						ComponentJsonDtoBuilder branchJson = ComponentJsonDto.builder();
						BranchData currentBranch = branchMap.get(branchId);
						branchJson.org(org)
						.uuid(branchId)
						.name(currentBranch.getName())
						;

						for (var release : brReleases) {
							
							Set<UUID> ids = release.getAllCommits();
							Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap = commitIdToRecordMap.entrySet().stream()
									.filter(entry -> ids.contains(entry.getKey()))
									.collect(Collectors.toMap(
									Entry::getKey, e -> changeLogService.resolveConventionalCommit(e.getValue().commitMessage)));
							
							if(commitIdToConventionalCommitMap.size() > 0){
								releaseRecordList.add(new ReleaseRecord(release.getUuid(),
										release.getDecoratedVersionString(userTimeZone),
										prepareChangeRecordList(commitIdToConventionalCommitMap, commitIdToRecordMap)));
							}
						}
						branchJson.releases(releaseRecordList);
						branchesJsonList.add(branchJson.build());
					});
					// BranchData branch = branchService.getBranchData()
					projectsList.add(ComponentJsonDto.builder()
							.org(pd.getOrg())
							.uuid(project)
							.name(pd.getName())
							.branches(branchesJsonList).build());
					
				}
				productReleaseRecordList.add(ComponentJsonDto.builder()
						.name(productRd.getDecoratedVersionString(userTimeZone))
						.uuid(productRd.getUuid())
						.components(projectsList).build());
			}
			json.components(productReleaseRecordList);
		}
		return json.build();
	}
	
	public ReleaseData addInboundDeliverables(ReleaseData releaseData, List<Map<String, Object>> deliverableDtos,
			WhoUpdated wu) throws RelizaException {
		List<UUID> currentDeliverables = releaseData.getInboundDeliverables();
		boolean isAllowed = ReleaseLifecycle.isAssemblyAllowed(releaseData.getLifecycle());
		if (!isAllowed) {
			throw new RelizaException("Cannot update deliverables on the current release lifecycle");
		}
		
		if (deliverableDtos != null && !deliverableDtos.isEmpty()) {
			List<UUID> newDeliverables = deliverableService.prepareListofDeliverables(deliverableDtos, releaseData.getBranch(),
					releaseData.getVersion(), wu);
			currentDeliverables.addAll(newDeliverables);
		}
		
		releaseData.setInboundDeliverables(currentDeliverables);
		ReleaseDto releaseDto = Utils.OM.convertValue(Utils.dataToRecord(releaseData), ReleaseDto.class);
		Release release = updateRelease(releaseDto, true, wu);
		return ReleaseData.dataFromRecord(release);
	}
	
	private ReleaseData addRebom(ReleaseData releaseData, ReleaseBom rebom, WhoUpdated wu) throws RelizaException {
		List<ReleaseBom> newBoms = new ArrayList<>(); 
		List<ReleaseBom> currentBoms = releaseData.getReboms();
		var currentBomSize = currentBoms.size();
		// find and replace existing bom matching the current merge crieteria
		// TODO: delete replaced boms
		List<ReleaseBom> filteredBoms = currentBoms.stream().filter(bom -> 
			!(bom.rebomMergeOptions().equals(rebom.rebomMergeOptions()))
		).toList();
		newBoms.addAll(filteredBoms);
		newBoms.add(rebom);
		log.info("RGDEBUG: add rebom on release: {}, new Bom: {}, replaced: {}", releaseData.getUuid(), rebom.rebomId(), currentBomSize  == newBoms.size());
		releaseData.setReboms(newBoms);
		ReleaseDto releaseDto = Utils.OM.convertValue(Utils.dataToRecord(releaseData), ReleaseDto.class);
		Release release = updateRelease(releaseDto, true, wu);
		return ReleaseData.dataFromRecord(release);
	}
	
	public Set<String> findDistinctReleaseTagKeysOfOrg(UUID org) {
		Set<String> distinctKeys = new HashSet<>();
		var releaseKeys = repository.findDistrinctReleaseKeysOfOrg(org.toString());
		distinctKeys.addAll(releaseKeys);
		return distinctKeys;
	}

	@Transactional
	public Boolean removeArtifact(UUID artifactUuid, UUID releaseUuid, WhoUpdated wu){
		Boolean removed = false;
		Optional<Release> rOpt = getRelease(releaseUuid);
		if (rOpt.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(rOpt.get());
			// remove artifact from release only if release is in draft state.
			if(ReleaseLifecycle.isAssemblyAllowed(rd.getLifecycle())){
				List<UUID> artifacts = rd.getArtifacts();
				artifacts.remove(artifactUuid);
				rd.setArtifacts(artifacts);
				Map<String,Object> recordData = Utils.dataToRecord(rd);
				saveRelease(rOpt.get(), recordData, wu);
				removed = true;
			}
			
		}
		
		if (removed) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isPresent()) {
				List<Release> or = findReleasesByArtifact(artifactUuid, oad.get().getOrg());
				if (or.isEmpty()) {
					// archive artifact
					artifactService.archiveArtifact(artifactUuid, wu);
				}
			}
		}
		return removed;
	}

	@Transactional
	public Boolean addArtifact(UUID artifactUuid, UUID releaseUuid, WhoUpdated wu) {
		Boolean added = false;
		Optional<Release> rOpt = getRelease(releaseUuid);
		if (null != artifactUuid && rOpt.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(rOpt.get());
				List<UUID> artifacts = rd.getArtifacts();
				artifacts.add(artifactUuid);
				rd.setArtifacts(artifacts);
				ReleaseUpdateEvent rue = new ReleaseUpdateEvent(ReleaseUpdateScope.ARTIFACT, ReleaseUpdateAction.ADDED,
						null, null, artifactUuid, ZonedDateTime.now(), wu);
				rd.addUpdateEvent(rue);
				Map<String,Object> recordData = Utils.dataToRecord(rd);
				saveRelease(rOpt.get(), recordData, wu);
				added = true;			
		}
		return added;
	}

	public BranchData createFeatureSetFromRelease(String featureSetName, ReleaseData rd, UUID org, WhoUpdated wu) throws RelizaException{
		ComponentData cd = getComponentService.getComponentData(rd.getComponent()).get();
		BranchData bd = BranchData.branchDataFromDbRecord(
			branchService.createBranch(
				featureSetName, cd, null, null, cd.getVersionSchema(), cd.getMarketingVersionSchema(), wu)
		);

		Set<ReleaseData> components = unwindReleaseDependencies(rd);
		
		List<ChildComponent> deps = components.stream().map(c -> {
			return ChildComponent.builder()
				.branch(c.getBranch())
				.release(c.getUuid())
				.status(StatusEnum.REQUIRED)
				.uuid(c.getComponent())
				.build();
		}).toList();
		BranchDto branchDto = BranchDto.builder()
		.uuid(bd.getUuid())
		.autoIntegrate(AutoIntegrateState.DISABLED)
		.dependencies(deps)
		.build();
		
		bd = branchService.updateBranch(branchDto, wu);

		return bd;
	}
	
	public UUID getResourceGroupForReleaseData (ReleaseData rd) {
		UUID rg = CommonVariables.DEFAULT_RESOURCE_GROUP;
		var pdo = getComponentService.getComponentData(rd.getComponent());
		if (null != pdo.get().getResourceGroup()) {
			rg = pdo.get().getResourceGroup();
		}
		return rg;
	}


	public void saveAll(List<Release> releases){
		repository.saveAll(releases);
	}
	
	public ComponentJsonDto getComponentChangeLog (UUID branch, UUID org, AggregationType aggregated, String userTimeZone) {
		ComponentJsonDto changelog = null;
		List<ReleaseData> releases = sharedReleaseService.listReleaseDataOfBranch(branch, true);
		ReleaseData rd = releases.get(0);
		if(rd != null){
			Optional <ComponentData> opd = getComponentService.getComponentData(rd.getComponent());
			ComponentData pd = opd.get();
			changelog = getChangeLogJsonForReleaseDataList(releases, org, false, pd, aggregated, userTimeZone);
		}
		
		return changelog;
	}
	

	public ComponentJsonDto getProductChangeLog (UUID branch, UUID org, AggregationType aggregated, String userTimeZone) {
		List<ReleaseData> productRds = sharedReleaseService.listReleaseDataOfBranch(branch, true)
				.stream()
				.filter(release -> !ReleaseLifecycle.isAssemblyAllowed(release.getLifecycle()))
				.collect(Collectors.toList());
		
		return getChangeLogJsonForProductReleaseDataList(productRds, org, false, aggregated, userTimeZone);
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
			var existingProducts = greedylocateProductsOfRelease(rd);
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
				parentReleases = getCurrentProductParentRelease(bd.getUuid(), rd, ReleaseLifecycle.ASSEMBLED);
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

	public Set<ParentRelease> getCurrentProductParentRelease(UUID branchUuid, ReleaseLifecycle lifecycle){
		return getCurrentProductParentRelease(branchUuid, null, lifecycle);
	}

	public Set<ParentRelease> getCurrentProductParentRelease(UUID branchUuid, ReleaseData triggeringRelease, ReleaseLifecycle lifecycle){
		BranchData bd = branchService.getBranchData(branchUuid).get();
		Set<ParentRelease> parentReleases = new HashSet<>();
		List<ChildComponent> dependencies = bd.getDependencies();
		boolean requirementsMet = true;
		var cpIter = dependencies.iterator();
		while (requirementsMet && cpIter.hasNext()) {
			var cp = cpIter.next();
			if (!cp.getUuid().equals(bd.getComponent()) && (cp.getStatus() == StatusEnum.REQUIRED || cp.getStatus() == StatusEnum.TRANSIENT)) {
				// if release present in cp, use that release
				Optional<ReleaseData> ord = Optional.empty();
				if (null != cp.getRelease()) {
					ord = getReleaseData(cp.getRelease());
				} else if (triggeringRelease != null && cp.getBranch().equals(triggeringRelease.getBranch())) {
					ord = Optional.of(triggeringRelease);
				} else {
					// obtain latest release - TODO - consider more complicated configurable logic later here
					ord = sharedReleaseService.getReleaseDataOfBranch(bd.getOrg(), cp.getBranch(), lifecycle);
				}
				if (ord.isPresent()) {
					// TODO handle proper deliverable selection
					ParentRelease dr = ParentRelease.minimalParentReleaseFactory(ord.get().getUuid(), null);
					parentReleases.add(dr);
				} else if (cp.getStatus() == StatusEnum.REQUIRED){
					requirementsMet = false;
				}
			}
		}
		if(!requirementsMet){
			return new HashSet<>();
		}
		return parentReleases;
	}
	
	public void autoIntegrateFeatureSetOnDemand (BranchData bd) {
		// check that status of this child project is not ignored
		log.info("PSDEBUG: autointegrate feature set on demand for bd = " + bd.getUuid());
		List<ChildComponent> dependencies = bd.getDependencies();
		
		// Take every required child project of this branch, and take latest completed release for each of them
		// including transients, but exclude ignored 
		// TODO get rid of OPTIONAL in auto-integrate - this one does not make much sense here
		
		Set<UUID> releasesToMatch = new LinkedHashSet<>(); // this will be used to match to existing product, in which case nothing would happen
		List<ParentRelease> parentReleases = new LinkedList<>();
		
		boolean requirementsMet = bd.getAutoIntegrate() == AutoIntegrateState.ENABLED;
		var cpIter = dependencies.iterator();
				
		while (requirementsMet && cpIter.hasNext()) {
			var cp = cpIter.next();
			if ((cp.getStatus() != StatusEnum.IGNORED)) {
				// if release present in cp, use that release
				Optional<ReleaseData> ord = Optional.empty();
				if (null != cp.getRelease()) {
					ord = getReleaseData(cp.getRelease());
				} else {
					// obtain latest release - TODO - consider more complicated configurable logic later here
					ord = sharedReleaseService.getReleaseDataOfBranch(bd.getOrg(), cp.getBranch(), null);
				}
				if (ord.isPresent()) {
					// TODO handle proper artifact selection via tags
					releasesToMatch.add(ord.get().getUuid());
					ParentRelease dr = ParentRelease.minimalParentReleaseFactory(ord.get().getUuid(), null);
					parentReleases.add(dr);
				} else if (cp.getStatus() == StatusEnum.REQUIRED){
					requirementsMet = false;
				}
			}
		}

	
		// check if a product release like that already exists
		if (requirementsMet) {
			var existingProduct = matchToProductRelease(bd.getUuid(), releasesToMatch);
			requirementsMet = existingProduct.isEmpty();
		}
			
		// If one of required projects does not have latest release, then we fail the process and don't yield anything there
		if (requirementsMet) {
			// create new product release
			// obtain next version
			// TODO take action based on component bump
			ActionEnum action = ActionEnum.BUMP;
			Optional<VersionAssignment> ova = versionAssignmentService.getSetNewVersionWrapper(bd.getUuid(), action, null, null);
			ReleaseDto releaseDto = ReleaseDto.builder()
											.component(bd.getComponent())
											.branch(bd.getUuid())
											.org(bd.getOrg())
											.status(ReleaseStatus.ACTIVE)
											.lifecycle(ReleaseLifecycle.ASSEMBLED)
											.version(ova.get().getVersion())
											.parentReleases(parentReleases)
											.build();
			try {
				createRelease(releaseDto, WhoUpdated.getAutoWhoUpdated());
			} catch (Exception e) {
				log.error("Exception on creating programmatic release, feature set = " + bd.getUuid() + ", manual trigger");
			}
		}
	}
	
	public Optional<ReleaseData> getReleaseDataFromProgrammaticInput (ReleaseApprovalProgrammaticInput rapi) throws RelizaException {
		Optional<ReleaseData> ord = Optional.empty();
		if (null != rapi.release()) {
			ord = getReleaseData(rapi.release());
		} else {
			ord = getReleaseDataByComponentAndVersion(rapi.component(), rapi.version());
		}
		return ord;
	}
	
	private Set<UUID> gatherReleaseArtifacts (ReleaseData rd) {
		Set<UUID> artifactIds = new HashSet<>(rd.getArtifacts());
		if (null != rd.getSourceCodeEntry()) {
			var sce = sourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry()).get();
			artifactIds.addAll(sce.getArtifacts());
		}
		// skip inbound deliverables
//		if (null != rd.getInboundDeliverables() && !rd.getInboundDeliverables().isEmpty()) {
//			rd.getInboundDeliverables().forEach(inbd -> {
//				var arts = deliverableService.getDeliverableData(inbd).get().getArtifacts();
//				if (null != arts && !arts.isEmpty()) artifactIds.addAll(arts);
//			});		
//		}
		variantService.getVariantsOfRelease(rd.getUuid()).forEach(rvd -> {
			if (null != rvd.getOutboundDeliverables() && !rvd.getOutboundDeliverables().isEmpty()) {
				rvd.getOutboundDeliverables().forEach(outbd -> {
					var arts = deliverableService.getDeliverableData(outbd).get().getArtifacts();
					if (null != arts && !arts.isEmpty()) artifactIds.addAll(arts);
				});
			}
		});
		return artifactIds;
	}
	
	public void computeReleaseMetrics (UUID releaseId) {
		Optional<Release> or = getRelease(releaseId);
		if (or.isPresent()) {
			computeReleaseMetrics(or.get(), ZonedDateTime.now());
		} else {
			log.warn("Attempted to compute metrics for non-existent release = " + releaseId);
		}
	}
	
	public void computeReleaseMetrics (Release r, ZonedDateTime lastScanned) {
		var rd = ReleaseData.dataFromRecord(r);
		var originalMetrics = null != rd.getMetrics() ? rd.getMetrics().clone() : null;
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		var allReleaseArts = gatherReleaseArtifacts(rd);
		allReleaseArts.forEach(aid -> {
			var ad = artifactService.getArtifactData(aid);
			rmd.mergeWithByContent(ad.get().getMetrics());
		});
		rmd.mergeWithByContent(rollUpProductReleaseMetrics(rd));
		if (!rmd.equals(originalMetrics)) {
			if (null == lastScanned) lastScanned = ZonedDateTime.now();
			rmd.setLastScanned(lastScanned);
			rd.setMetrics(rmd);
			Map<String,Object> recordData = Utils.dataToRecord(rd);
			saveRelease(r, recordData, WhoUpdated.getAutoWhoUpdated());
		}
	}
	
	private ReleaseMetricsDto rollUpProductReleaseMetrics (ReleaseData rd) {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rd.getParentReleases().forEach(r -> {
			try {
				ReleaseData parentRd = sharedReleaseService
						.getReleaseData(r.getRelease(), rd.getOrg()).get();
				rmd.mergeWithByContent(parentRd.getMetrics());
			} catch (RelizaException e) {
				log.error("Error on getting parent release", e);
			}
		});
		return rmd;
	}
	
	protected void computeMetricsForAllUnprocessedReleases () {
		ZonedDateTime lastScanned = ZonedDateTime.now();
		var releasesByArt = repository.findReleasesForMetricsComputeByArtifactDirect();
		var releasesBySce = repository.findReleasesForMetricsComputeBySce();
		var releasesByOutboundDel = repository.findReleasesForMetricsComputeByOutboundDeliverables();
		var productReleases = repository.findProductReleasesForMetricsCompute();
		Set<UUID> dedupProcessedReleases = new HashSet<>();
		computeMetricsForReleaseList(releasesByArt, dedupProcessedReleases, lastScanned);
		computeMetricsForReleaseList(releasesBySce, dedupProcessedReleases, lastScanned);
		computeMetricsForReleaseList(releasesByOutboundDel, dedupProcessedReleases, lastScanned);
		computeMetricsForReleaseList(productReleases, dedupProcessedReleases, lastScanned);
	}
	
	private void computeMetricsForReleaseList(List<Release> releaseList,
			Set<UUID> dedupProcessedReleases, ZonedDateTime lastScanned) {
		releaseList.forEach(r -> {
			if (!dedupProcessedReleases.contains(r.getUuid())) {
				computeReleaseMetrics(r, lastScanned);
				dedupProcessedReleases.add(r.getUuid());
			}
		});
	}
	
	@Async
	public void reconcileMergedSbomRoutine(ReleaseData rd, WhoUpdated wu) {
		// rd
		log.info("RGDEBUG: Reconcile Merged Sboms Routine started for release: {}", rd.getUuid());
		Set<ReleaseData> rds = greedylocateProductsOfRelease(rd);
		// log.info("greedy located rds: {}", rds);
		// Set<ReleaseData> allRds = locateAllProductsOfRelease(rd, new HashSet<>());
		// log.info("allRds rds: {}", allRds);
		for (ReleaseData r : rds) {
			if(null != r.getReboms() && r.getReboms().size() > 0){
				for (ReleaseBom releaseBom : r.getReboms()) {
					try {
						matchOrGenerateSingleBomForRelease(r, releaseBom.rebomMergeOptions(), true, rd.getUuid(), wu);
					} catch (RelizaException e) {
						log.error("Exception on reconcileMergedSbomRoutine: {}", e);
					}
				}
			}
		}
		log.info("Reconcile Routine end");

		// rds.stream().forEach((ReleaseData r) -> {
		// 	if(null != r.getReboms() && r.getReboms().size() > 0){
		// 		r.getReboms().stream().forEach(bom -> {
		// 			matchOrGenerateSingleBomForRelease(r, bom.rebomMergeOptions(), wu);
		// 		});
		// 	}
		// });
	}
	
}
