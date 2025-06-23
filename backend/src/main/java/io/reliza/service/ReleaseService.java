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
import com.github.packageurl.PackageURL;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseApprovalProgrammaticInput;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.ReleaseData.ReleaseDataExtended;
import io.reliza.model.ReleaseData.ReleaseDateComparator;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.VersionAssignment;
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
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.versioning.VersionApi.ActionEnum;

@Service
public class ReleaseService {
	
	@Autowired
	private DeliverableService deliverableService;
	
	@Autowired
	private GetDeliverableService getDeliverableService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;
	
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

	@Autowired
	private OssReleaseService ossReleaseService;

	@Autowired
	private ReleaseRebomService releaseRebomService;
	
	@Autowired
	private ArtifactGatherService artifactGatherService;

	private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);
			
	private final ReleaseRepository repository;
	
	private static final String HELM_MIME_TYPE = "application/vnd.cncf.helm.config.v1+json"; // refer to https://github.com/opencontainers/artifacts/blob/main/artifact-authors.md
  
	public static record CommitRecord(String commitUri, String commitId, String commitMessage, String commitAuthor, String commitEmail) {

		public String commitMessage() {
			return this.commitMessage;
		}}
	
	public static record TicketRecord(String ticketSubject, List<ChangeRecord> changes) {}
	
	public static record ReleaseRecord(UUID uuid, String version, List<ChangeRecord> changes) {}
	
	public static record ChangeRecord(String changeType, List<CommitMessageRecord> commitRecords) {}
	
	public static record CommitMessageRecord(String linkifiedText, String rawText, String commitAuthor, String commitEmail) {}
	
	ReleaseService(ReleaseRepository repository) {
		this.repository = repository;
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
		List<SourceCodeEntry> sceList = getSourceCodeEntryService.getSourceCodeEntriesByCommitTag(orgUuid, commit);
		// TODO see if we can query db by array later
		sceList.forEach(sce -> {
			releases.addAll(sharedReleaseService.findReleasesBySce(sce.getUuid(), orgUuid));
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
		Optional<SourceCodeEntry> osce = getSourceCodeEntryService.findLatestSceWithTicketAndOrg(ticket, org);
		
		//Find the commit's Release
		if(osce.isPresent())
			or = getLatestReleaseBySce(osce.get().getUuid(), org);
			
		if(or.isPresent())
			ord = Optional.of(ReleaseData.dataFromRecord(or.get()));
		
		return ord;
	}
	
	public Optional<ReleaseData> getReleaseDataByOutboundDeliverableDigest (String digest, UUID orgUuid) {
		Optional<ReleaseData> ord = Optional.empty();
		Optional<DeliverableData> oad = getDeliverableService.getDeliverableDataByDigest(digest, orgUuid);
		if (oad.isPresent()) {
			// locate lowest level release referencing this artifact
			// note that org uuid may be external that's why it may be different
			ord = sharedReleaseService.getReleaseByOutboundDeliverable(oad.get().getUuid(), oad.get().getOrg());
		}
		return ord;
	}
	
	public List<ReleaseData> listReleaseDataByBuildId (String query, UUID orgUuid) {
		List<ReleaseData> releases = new LinkedList<>();
		List<DeliverableData> deliverables = getDeliverableService.getDeliverableDataByBuildId(query, orgUuid);
		deliverables.forEach(d -> {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseByOutboundDeliverable(d.getUuid(), d.getOrg());
			if (ord.isPresent()) {
				releases.add(ord.get());
			}
		});
		return releases;
	}
	
	@Transactional
	public ComponentJsonDto createReleaseAndGetChangeLog(SceDto sourceCodeEntry, List<SceDto> commitList,
			String nextVersion, ReleaseLifecycle lifecycleResolved, BranchData bd, WhoUpdated wu) throws Exception{
		ComponentJsonDto changelog = null;

		//check if source code details are present and create a release with these details and version
		Optional<SourceCodeEntryData> osced = Optional.empty();
		// also check if commits (base64 encoded) are present and if so add to release
		List<UUID> commits = new LinkedList<>();
		
		ComponentData cd = getComponentService.getComponentData(bd.getComponent()).get();
		
		if (sourceCodeEntry != null || commitList != null) {
			// parse list of associated commits obtained via git log with previous CI build if any (note this may include osce)
			if (commitList != null) {
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
					log.debug("RGDEBUG: updated sceDTO = {}", sourceCodeEntry);
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
			List<TeaIdentifier> releaseIdentifiers = sharedReleaseService.resolveReleaseIdentifiersFromComponent(nextVersion, cd);
			releaseDtoBuilder.version(nextVersion)
							.lifecycle(lifecycleResolved)
							.identifiers(releaseIdentifiers);
			release = ossReleaseService.createRelease(releaseDtoBuilder.build(), wu);
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
		
		Optional<ReleaseData> latestRelease = ossReleaseService.getReleasePerProductComponent(bd.getOrg(), bd.getComponent(), null, bd.getName(), null);
		if (latestRelease.isPresent()) {
			changelog = getChangelogBetweenReleases(
					ReleaseData.dataFromRecord(release).getUuid(), latestRelease.get().getUuid(), bd.getOrg(), AggregationType.AGGREGATED, null);
		}
		
		return changelog;
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
			presentComponents = latestRlzComponents.stream().map(x -> 
				sharedReleaseService.getReleaseData(x.getRelease()).get().getComponent()).collect(Collectors.toSet());
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
				ReleaseData rd = sharedReleaseService.getReleaseData(r).get();
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
			Set<ReleaseData> rdPreCandidates = sharedReleaseService.greedylocateProductsOfReleaseCollection(releasesToFindProducts, fsData.getOrg());
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
				var coreReleasesRd = sharedReleaseService.getReleaseDataList(coreReleases, fsData.getOrg())
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
						if (null == nonProxyReleaseData) nonProxyReleaseData = sharedReleaseService.getReleaseData(r).get();
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

	public JsonNode exportReleaseAsObom(UUID releaseUuid) {
		JsonNode output = null;
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isPresent()) {
			List<Component> components = parseReleaseIntoCycloneDxComponents(releaseUuid);
			Set<ReleaseData> dependencies = sharedReleaseService.unwindReleaseDependencies(ord.get());
			for (ReleaseData dependency : dependencies) {
				components.addAll(parseReleaseIntoCycloneDxComponents(dependency.getUuid()));
			}
			Bom bom = new Bom();
			for (Component c : components) {
				bom.addComponent(c);
			}
			var orgData = organizationService.getOrganizationData(ord.get().getOrg()).get();
			var cData = getComponentService.getComponentData(ord.get().getComponent()).get();
			Component bomComponent = new Component();
			bomComponent.setName(cData.getName());
			bomComponent.setType(Type.APPLICATION);
			bomComponent.setVersion(ord.get().getVersion());
			Utils.setRearmBomMetadata(bom, orgData.getName(), bomComponent);
			BomJsonGenerator generator = BomGeneratorFactory.createJson(org.cyclonedx.Version.VERSION_16, bom);
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
			Set<ReleaseData> dependencies = sharedReleaseService.unwindReleaseDependencies(rd);
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
		return getDeliverableService.getDeliverableDataList(deliverableUuids);
	}

	public String exportReleaseSbom(UUID releaseUuid, Boolean tldOnly, ArtifactBelongsTo belongsTo, BomStructureType structure, UUID org, WhoUpdated wu) throws RelizaException, JsonProcessingException{
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid).orElseThrow();
		RebomOptions mergeOptions = new RebomOptions(belongsTo, tldOnly, structure);
		UUID releaseBomId = matchOrGenerateSingleBomForRelease(rd, mergeOptions, wu);
		if(null == releaseBomId){
			throw new RelizaException("No SBOMs found!");
		}
		JsonNode mergedBomJsonNode = rebomService.findBomById(releaseBomId, org);
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
				sceRebomIds = getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry()).get().getArtifacts().stream()
				.filter(scea -> rd.getComponent().equals(scea.componentUuid()))
				.map(scea -> artifactService.getArtifactData(scea.artifactUuid()))
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
		log.debug("RGDEBUG: generateComponentReleaseBomForConfig bomIds: {}", bomIds);
		// Call add bom on list

		if(bomIds.size() > 0){
			ComponentData pd = getComponentService.getComponentData(rd.getComponent()).get();
			OrganizationData od = organizationService.getOrganizationData(rd.getOrg()).get();
			var rebomOptions = new RebomOptions(pd.getName(), od.getName(), rd.getVersion(), rebomMergeOptions.belongsTo(), rebomMergeOptions.hash(), rebomMergeOptions.tldOnly(), rebomMergeOptions.structure(), rebomMergeOptions.notes(), StripBom.TRUE,"", "" );
			rebomId = rebomService.mergeAndStoreBoms(bomIds, rebomOptions, od.getUuid());
			
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
		List<ReleaseBom> reboms = releaseRebomService.getReleaseBoms(rd);
		// match with request
		// log.info("rebomMergeOptions: {}",rebomMergeOptions);
		ReleaseBom matchedBom = reboms.stream()
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
				var morerds = sharedReleaseService.unwindReleaseDependencies(rd);
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
						var rebomOptions = new RebomOptions(pd.getName(), od.getName(), rd.getVersion(),  rebomMergeOptions.belongsTo(), rebomMergeOptions.hash(), rebomMergeOptions.tldOnly(), rebomMergeOptions.structure(), rebomMergeOptions.notes(), StripBom.TRUE, "", "");
						UUID rebomId = rebomService.mergeAndStoreBoms(bomIds, rebomOptions, od.getUuid());
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
			Optional<SourceCodeEntryData> osced = getSourceCodeEntryService.getSourceCodeEntryData(sourceCodeEntryUuid);
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
			List<DeliverableData> dds = getDeliverableService.getDeliverableDataList(deliverableUuids);
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
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(dr.getRelease());
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
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
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
			osced = getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry());
		
		if(osced.isPresent())
			commitDate = osced.get().getDateActual();

		return commitDate;
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
			ossReleaseService.updateReleaseLifecycle(rd.getUuid(), ReleaseLifecycle.CANCELLED, WhoUpdated.getAutoWhoUpdated());
		});
	}
	
	@Transactional
	public List<UUID> uploadSceArtifacts (List<Map<String, Object>> arts, OrganizationData od, SceDto sceDto,
			ComponentData cd, String version, WhoUpdated wu) throws RelizaException {
		return artifactService.uploadListOfArtifacts(od, arts, new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.SCE, sceDto.getCommit(), StripBom.FALSE), wu);
	}
	
	@Transactional
	public Optional<SourceCodeEntryData> parseSceFromReleaseCreate (SceDto sceDto, List<UUID> artIds, BranchData bd,
			String branchStr,
			String version,
			WhoUpdated wu) throws IOException {
		UUID orgUuid = bd.getOrg();
		List<SCEArtifact> sceArts= new LinkedList<>();
		if (null != artIds) sceArts = artIds.stream().map(x -> new SCEArtifact(x, bd.getComponent())).toList();
		sceDto.setArtifacts(sceArts);
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

			List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(brReleases, org);
			Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
			
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

	public ComponentJsonDto getChangelogBetweenReleases(UUID uuid1, UUID uuid2, UUID org, AggregationType aggregated, String userTimeZone) throws RelizaException{
		ComponentJsonDto changelog = null;
		//gets one extra element at the end for product component comparision
		List<ReleaseData> rds = sharedReleaseService.listAllReleasesBetweenReleases(uuid1,  uuid2);
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

		
		List<ReleaseData> componentComponentReleaseDataList = sharedReleaseService.getReleaseDataList(componentReleaseUuids, org);
		List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(componentComponentReleaseDataList, org);
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
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
		Release release = ossReleaseService.updateRelease(releaseDto, true, wu);
		return ReleaseData.dataFromRecord(release);
	}
	
	private void addRebom(ReleaseData releaseData, ReleaseBom rebom, WhoUpdated wu) throws RelizaException {
		List<ReleaseBom> newBoms = new ArrayList<>(); 
		List<ReleaseBom> currentBoms = releaseRebomService.getReleaseBoms(releaseData);
		var currentBomSize = currentBoms.size();
		// find and replace existing bom matching the current merge crieteria
		// TODO: delete replaced boms
		List<ReleaseBom> filteredBoms = currentBoms.stream().filter(bom -> 
			!(bom.rebomMergeOptions().equals(rebom.rebomMergeOptions()))
		).toList();
		newBoms.addAll(filteredBoms);
		newBoms.add(rebom);
		log.debug("RGDEBUG: add rebom on release: {}, new Bom: {}, replaced: {}", releaseData.getUuid(), rebom.rebomId(), currentBomSize  == newBoms.size());
		releaseRebomService.setReboms(releaseData, newBoms, wu);
		// ReleaseDto releaseDto = Utils.OM.convertValue(Utils.dataToRecord(releaseData), ReleaseDto.class);
		// ossReleaseService.updateRelease(releaseDto, true, wu);
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
		Optional<Release> rOpt = sharedReleaseService.getRelease(releaseUuid);
		if (rOpt.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(rOpt.get());
			// remove artifact from release only if release is in draft state.
			if(ReleaseLifecycle.isAssemblyAllowed(rd.getLifecycle())){
				List<UUID> artifacts = rd.getArtifacts();
				artifacts.remove(artifactUuid);
				rd.setArtifacts(artifacts);
				Map<String,Object> recordData = Utils.dataToRecord(rd);
				ossReleaseService.saveRelease(rOpt.get(), recordData, wu);
				removed = true;
			}
			
		}
		
		if (removed) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isPresent()) {
				List<Release> or = sharedReleaseService.findReleasesByReleaseArtifact(artifactUuid, oad.get().getOrg());
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
		Optional<Release> rOpt = sharedReleaseService.getRelease(releaseUuid);
		if (null != artifactUuid && rOpt.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(rOpt.get());
				List<UUID> artifacts = rd.getArtifacts();
				artifacts.add(artifactUuid);
				rd.setArtifacts(artifacts);
				ReleaseUpdateEvent rue = new ReleaseUpdateEvent(ReleaseUpdateScope.ARTIFACT, ReleaseUpdateAction.ADDED,
						null, null, artifactUuid, ZonedDateTime.now(), wu);
				rd.addUpdateEvent(rue);
				Map<String,Object> recordData = Utils.dataToRecord(rd);
				ossReleaseService.saveRelease(rOpt.get(), recordData, wu);
				added = true;			
		}
		return added;
	}
	@Transactional
	public Boolean replaceArtifact(UUID replaceArtifactUuid ,UUID artifactUuid, UUID releaseUuid, WhoUpdated wu) {
		Boolean added = false;
		Optional<Release> rOpt = sharedReleaseService.getRelease(releaseUuid);
		if (null != artifactUuid && rOpt.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(rOpt.get());
				List<UUID> artifacts = rd.getArtifacts();
				artifacts.remove(replaceArtifactUuid);
				artifacts.add(artifactUuid);
				rd.setArtifacts(artifacts);
				ReleaseUpdateEvent rue = new ReleaseUpdateEvent(ReleaseUpdateScope.ARTIFACT, ReleaseUpdateAction.ADDED,
						null, null, artifactUuid, ZonedDateTime.now(), wu);
				rd.addUpdateEvent(rue);
				Map<String,Object> recordData = Utils.dataToRecord(rd);
				ossReleaseService.saveRelease(rOpt.get(), recordData, wu);
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

		Set<ReleaseData> components = sharedReleaseService.unwindReleaseDependencies(rd);
		
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
					ord = sharedReleaseService.getReleaseData(cp.getRelease());
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
				ossReleaseService.createRelease(releaseDto, WhoUpdated.getAutoWhoUpdated());
			} catch (Exception e) {
				log.error("Exception on creating programmatic release, feature set = " + bd.getUuid() + ", manual trigger");
			}
		}
	}
	
	public Optional<ReleaseData> getReleaseDataFromProgrammaticInput (ReleaseApprovalProgrammaticInput rapi) throws RelizaException {
		Optional<ReleaseData> ord = Optional.empty();
		if (null != rapi.release()) {
			ord = sharedReleaseService.getReleaseData(rapi.release());
		} else {
			ord = getReleaseDataByComponentAndVersion(rapi.component(), rapi.version());
		}
		return ord;
	}
	
	public void computeReleaseMetrics (UUID releaseId) {
		Optional<Release> or = sharedReleaseService.getRelease(releaseId);
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
		var allReleaseArts = artifactGatherService.gatherReleaseArtifacts(rd);
		allReleaseArts.forEach(aid -> {
			var ad = artifactService.getArtifactData(aid);
			rmd.mergeWithByContent(ad.get().getMetrics());
		});
		rmd.mergeWithByContent(rollUpProductReleaseMetrics(rd));
		if (null == originalMetrics.getLastScanned() || lastScanned.isAfter(originalMetrics.getLastScanned())) {
			if (null == lastScanned) lastScanned = ZonedDateTime.now();
			rmd.setLastScanned(lastScanned);
			rd.setMetrics(rmd);
			Map<String,Object> recordData = Utils.dataToRecord(rd);
			ossReleaseService.saveRelease(r, recordData, WhoUpdated.getAutoWhoUpdated());
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
		var releasesByUpdateDate = repository.findReleasesForMetricsComputeByUpdate();
		Set<UUID> dedupProcessedReleases = new HashSet<>();
		computeMetricsForReleaseList(releasesByArt, dedupProcessedReleases, lastScanned);
		computeMetricsForReleaseList(releasesBySce, dedupProcessedReleases, lastScanned);
		computeMetricsForReleaseList(releasesByOutboundDel, dedupProcessedReleases, lastScanned);
		computeMetricsForReleaseList(releasesByUpdateDate, dedupProcessedReleases, lastScanned);
		log.info("PSDEBUG: processed releases size = " + dedupProcessedReleases.size());
		
		var productReleases = findProductReleasesFromComponentsForMetrics(dedupProcessedReleases);
		computeMetricsForReleaseList(productReleases, dedupProcessedReleases, lastScanned);
		
		log.info("PSDEBUG: processed product releases size = " + productReleases.size());
	}
	
	private List<Release> findProductReleasesFromComponentsForMetrics (Set<UUID> dedupProcessedReleases) {
		Set<UUID> dedupReleases = new HashSet<>();
		List<Release> productReleases = new LinkedList<>();
		if (null != dedupProcessedReleases && !dedupProcessedReleases.isEmpty()) {
			dedupProcessedReleases.forEach(dpr -> {
				ReleaseData rd = sharedReleaseService.getReleaseData(dpr).get();
				List<Release> wipProducts = repository.findProductsByRelease(rd.getOrg().toString(),
						rd.getUuid().toString());
				if (null != wipProducts && !wipProducts.isEmpty()) {
					wipProducts.forEach(p -> {
						if (!dedupProcessedReleases.contains(p.getUuid()) && !dedupReleases.contains(p.getUuid())) {
							productReleases.add(p);
							dedupReleases.add(p.getUuid());
						}
					});
				}
			});
		}
		return productReleases;
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
		log.debug("RGDEBUG: Reconcile Merged Sboms Routine started for release: {}", rd.getUuid());
		Set<ReleaseData> rds = sharedReleaseService.greedylocateProductsOfRelease(rd);
		// log.info("greedy located rds: {}", rds);
		// Set<ReleaseData> allRds = locateAllProductsOfRelease(rd, new HashSet<>());
		// log.info("allRds rds: {}", allRds);
		for (ReleaseData r : rds) {
			List<ReleaseBom> reboms = releaseRebomService.getReleaseBoms(rd);
			if(null != reboms && reboms.size() > 0){
				for (ReleaseBom releaseBom : reboms) {
					try {
						matchOrGenerateSingleBomForRelease(r, releaseBom.rebomMergeOptions(), true, rd.getUuid(), wu);
					} catch (RelizaException e) {
						log.error("Exception on reconcileMergedSbomRoutine: {}", e);
					}
				}
			}
		}
		log.info("Reconcile Routine end");

	}
	
}
