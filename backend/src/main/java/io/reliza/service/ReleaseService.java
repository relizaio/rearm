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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.cyclonedx.model.vulnerability.Vulnerability.Rating;
import org.cyclonedx.model.vulnerability.Vulnerability.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisState;
import io.reliza.model.ArtifactData;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ArtifactData.DigestRecord;
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
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.ReleaseData.UpdateReleaseStrength;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AnalyticsDtos.VegaDateValue;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.oss.OssReleaseService;

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
	private GetOrganizationService getOrganizationService;
	
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
	private DependencyPatternService dependencyPatternService;

	@Autowired
	private ReleaseMetricsComputeService releaseMetricsComputeService;
	
	@Autowired
	private VulnAnalysisService vulnAnalysisService;

	private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);
			
	private final ReleaseRepository repository;
	
	private static final String HELM_MIME_TYPE = "application/vnd.cncf.helm.config.v1+json"; // refer to https://github.com/opencontainers/artifacts/blob/main/artifact-authors.md
  
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
	
	private List<Release> listReleasesOfComponentAfterDate (UUID componentUuid, ZonedDateTime cutOffDate) {
		return repository.findReleasesOfComponentAfterDate(componentUuid.toString(), cutOffDate);
	}
	
	private List<Release> listReleasesOfBranchAfterDate (UUID branchUuid, ZonedDateTime cutOffDate) {
		return repository.findReleasesOfBranchAfterDate(branchUuid.toString(), cutOffDate);
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
	public void createReleaseFromVersion(SceDto sourceCodeEntry, List<SceDto> commitList,
			String nextVersion, ReleaseLifecycle lifecycleResolved, BranchData bd, WhoUpdated wu) throws Exception{

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
		
		try {
			List<TeaIdentifier> releaseIdentifiers = sharedReleaseService.resolveReleaseIdentifiersFromComponent(nextVersion, cd);
			releaseDtoBuilder.version(nextVersion)
							.lifecycle(lifecycleResolved)
							.identifiers(releaseIdentifiers);
			ossReleaseService.createRelease(releaseDtoBuilder.build(), wu);
		} catch (RelizaException re) {
			throw re;
		}
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
		// Group by component UUID - when same component has multiple branches, keep the first one for status checking
		final Map<UUID, ChildComponent> childComponents = dependencies.stream()
			.collect(Collectors.toMap(x -> x.getUuid(), Function.identity(), (existing, replacement) -> existing));
		
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
	 * Aggregates number of releases per time unit per defined time frame for a component
	 * @param componentUuid
	 * @param cutOffDate
	 * @return
	 */
	public List<VegaDateValue> getReleaseCreateOverTimeAnalyticsByComponent(UUID componentUuid, ZonedDateTime cutOffDate) {
		List<Release> releases = listReleasesOfComponentAfterDate(componentUuid, cutOffDate);
		return releases.stream()
				.map(r -> new VegaDateValue(r.getCreatedDate().toString(), Long.valueOf(1))).toList();
	}
	
	/**
	 * Aggregates number of releases per time unit per defined time frame for a branch
	 * @param branchUuid
	 * @param cutOffDate
	 * @return
	 */
	public List<VegaDateValue> getReleaseCreateOverTimeAnalyticsByBranch(UUID branchUuid, ZonedDateTime cutOffDate) {
		List<Release> releases = listReleasesOfBranchAfterDate(branchUuid, cutOffDate);
		return releases.stream()
				.map(r -> new VegaDateValue(r.getCreatedDate().toString(), Long.valueOf(1))).toList();
	}
	
	/**
	 * Aggregates number of releases per time unit per defined time frame for a perspective
	 * @param perspectiveUuid
	 * @param cutOffDate
	 * @return
	 */
	public List<VegaDateValue> getReleaseCreateOverTimeAnalyticsByPerspective(UUID perspectiveUuid, ZonedDateTime cutOffDate) {
		List<UUID> componentUuids = getComponentService.listComponentsByPerspective(perspectiveUuid).stream()
				.map(ComponentData::getUuid).toList();
		
		List<Release> releases = new java.util.ArrayList<>();
		for (UUID componentUuid : componentUuids) {
			releases.addAll(listReleasesOfComponentAfterDate(componentUuid, cutOffDate));
		}
		
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
			var orgData = getOrganizationService.getOrganizationData(ord.get().getOrg()).get();
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

	public String exportReleaseSbom(UUID releaseUuid, Boolean tldOnly, Boolean ignoreDev, ArtifactBelongsTo belongsTo, BomStructureType structure, BomMediaType mediaType, UUID org, WhoUpdated wu, List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) throws RelizaException, JsonProcessingException{
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid).orElseThrow();
		if (null == tldOnly) tldOnly = false;
		final RebomOptions mergeOptions = new RebomOptions(belongsTo, tldOnly, ignoreDev, structure);
		UUID releaseBomId = matchOrGenerateSingleBomForRelease(rd, mergeOptions, wu, excludeCoverageTypes);
		if(null == releaseBomId){
			throw new RelizaException("No SBOMs found!");
		}
		String mergedBom = "";
		if (mediaType == BomMediaType.JSON){
			JsonNode mergedBomJsonNode = rebomService.findBomByIdJson(releaseBomId, org);
			mergedBom = mergedBomJsonNode.toString();
		} else if (mediaType == BomMediaType.CSV) {
			mergedBom = rebomService.findBomByIdCsv(releaseBomId, org);
		} else if (mediaType == BomMediaType.EXCEL) {
			mergedBom = rebomService.findBomByIdExcel(releaseBomId, org);
		}
		return mergedBom;
	}
	
	private boolean isArtifactExcludedByCoverageType(ArtifactData ad, List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) {
		if (excludeCoverageTypes == null || excludeCoverageTypes.isEmpty() || ad.getTags() == null) return false;
		Set<String> excludeValues = excludeCoverageTypes.stream().map(Enum::name).collect(Collectors.toSet());
		return ad.getTags().stream()
			.anyMatch(t -> CommonVariables.ARTIFACT_COVERAGE_TYPE_TAG_KEY.equals(t.key()) && excludeValues.contains(t.value()));
	}

	private UUID generateComponentReleaseBomForConfig(ReleaseData rd, RebomOptions rebomMergeOptions, WhoUpdated wu, List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) throws RelizaException{
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
				.filter(art -> !isArtifactExcludedByCoverageType(art.get(), excludeCoverageTypes))
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
				.filter(art -> !isArtifactExcludedByCoverageType(art.get(), excludeCoverageTypes))
				.map(a -> a.get().getInternalBom().id())
				.distinct()
				.toList();
			if(null != sceRebomIds && sceRebomIds.size() > 0) bomIds.addAll(sceRebomIds);
		}
		
		if(null == typeFilter || typeFilter.equals(ArtifactBelongsTo.RELEASE)){
			List<UUID> releaseRebomIds  = null;
			releaseRebomIds = rd.getArtifacts().stream().map(a -> artifactService.getArtifactData(a))
			.filter(art -> art.isPresent() && null != art.get().getInternalBom())
			.filter(art -> !isArtifactExcludedByCoverageType(art.get(), excludeCoverageTypes))
			.map(a -> a.get().getInternalBom().id())
			.distinct()
			.toList();
			if(null != releaseRebomIds && releaseRebomIds.size() > 0) bomIds.addAll(releaseRebomIds);

		}
		log.debug("RGDEBUG: generateComponentReleaseBomForConfig bomIds: {}", bomIds);
		// Call add bom on list

		if(bomIds.size() > 0){
			ComponentData pd = getComponentService.getComponentData(rd.getComponent()).get();
			OrganizationData od = getOrganizationService.getOrganizationData(rd.getOrg()).get();
			String purl = null;
			Optional<TeaIdentifier> purlId = Optional.empty();
			if (null != rd.getIdentifiers()) purlId = rd.getIdentifiers().stream().filter(id -> id.getIdType() == TeaIdentifierType.PURL).findFirst();
			if (purlId.isPresent()) purl = purlId.get().getIdValue();
			var rebomOptions = new RebomOptions(pd.getName(), od.getName(), rd.getVersion(), rebomMergeOptions.belongsTo(), rebomMergeOptions.hash(), rebomMergeOptions.tldOnly(), rebomMergeOptions.ignoreDev(), rebomMergeOptions.structure(), rebomMergeOptions.notes(), StripBom.TRUE,"", "", purl, RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT);
			rebomId = rebomService.mergeAndStoreBoms(bomIds, rebomOptions, od.getUuid());
			
			addRebom(rd, new ReleaseBom(rebomId, rebomMergeOptions), wu);
		}else if (bomIds.size() > 0){
			rebomId = bomIds.get(0);
		}
		
		return rebomId;
	}
	
	
	// TODO shouldn't be called as get as it may mutate data
	// returns a single rebomId if present or recursively gather, merge and save as a single bom to return a single rebomId
	private UUID matchOrGenerateSingleBomForRelease(ReleaseData rd, RebomOptions rebomMergeOptions, WhoUpdated wu, List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) throws RelizaException {
		return matchOrGenerateSingleBomForRelease(rd, rebomMergeOptions, false, null, wu, excludeCoverageTypes);
	}
	private UUID matchOrGenerateSingleBomForRelease(ReleaseData rd, RebomOptions rebomMergeOptions, Boolean forced, UUID componentFilter, WhoUpdated wu) throws RelizaException {
		return matchOrGenerateSingleBomForRelease(rd, rebomMergeOptions, forced, componentFilter, wu, null);
	}
	private UUID matchOrGenerateSingleBomForRelease(ReleaseData rd, RebomOptions rebomMergeOptions, Boolean forced, UUID componentFilter, WhoUpdated wu, List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) throws RelizaException {
		// for component structure and 
		UUID retRebomId = null;
		boolean hasExcludeCoverageTypes = excludeCoverageTypes != null && !excludeCoverageTypes.isEmpty();
		List<ReleaseBom> reboms = releaseRebomService.getReleaseBoms(rd);
		// match with request
		// log.info("rebomMergeOptions: {}",rebomMergeOptions);
		ReleaseBom matchedBom = hasExcludeCoverageTypes ? null : reboms.stream()
			.filter(rb -> null != rb.rebomMergeOptions()
			    && Objects.equals(rb.rebomMergeOptions().belongsTo(), rebomMergeOptions.belongsTo()) 
			    && Objects.equals(rb.rebomMergeOptions().tldOnly(), rebomMergeOptions.tldOnly())
			    && Objects.equals(rb.rebomMergeOptions().ignoreDev(), rebomMergeOptions.ignoreDev())
			    && Objects.equals(rb.rebomMergeOptions().structure(), rebomMergeOptions.structure())
			).findFirst().orElse(null);

		if(matchedBom == null || forced){
			ComponentData pd = getComponentService.getComponentData(rd.getComponent()).get();
			if(pd.getType().equals(ComponentType.COMPONENT)){
				retRebomId = generateComponentReleaseBomForConfig(rd, rebomMergeOptions, wu, excludeCoverageTypes);
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
						return matchOrGenerateSingleBomForRelease(r, rebomMergeOptions, forceComponent, null, wu, excludeCoverageTypes);
					} catch (RelizaException e) {
						log.error("error on getting release in matchOrGenerateSingleBomForRelease", e);
						return new UUID(0,0);
					}
				}).filter(Objects::nonNull).filter(r -> !(new UUID(0,0)).equals(r)).collect(Collectors.toCollection(LinkedList::new));
				if(bomIds != null && !bomIds.isEmpty()){
					if(bomIds.size() == 1){
						retRebomId = bomIds.getFirst();
					} else {
						var od = getOrganizationService.getOrganizationData(rd.getOrg()).get();
						String purl = null;
						Optional<TeaIdentifier> purlId = Optional.empty();
						if (null != rd.getIdentifiers()) purlId = rd.getIdentifiers().stream().filter(id -> id.getIdType() == TeaIdentifierType.PURL).findFirst();
						if (purlId.isPresent()) purl = purlId.get().getIdValue();
						var rebomOptions = new RebomOptions(pd.getName(), od.getName(), rd.getVersion(),  rebomMergeOptions.belongsTo(), rebomMergeOptions.hash(), rebomMergeOptions.tldOnly(), rebomMergeOptions.ignoreDev(), rebomMergeOptions.structure(), rebomMergeOptions.notes(), StripBom.TRUE, "", "", purl);
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
				
				// Set the semantic version as-is
				c.setVersion(version);
				
				// For container components, add Docker-safe version as a property
				if (cycloneComponentType == Component.Type.CONTAINER && StringUtils.isNotEmpty(version)) {
					String containerSafeVersion = Utils.dockerTagSafeVersion(version);
					var p = new Property();
					p.setName("reliza:containerSafeVersion");
					p.setValue(containerSafeVersion);
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
		// TODO resolve purls
		return artifactService.uploadListOfArtifacts(od, arts, new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.SCE, sceDto.getCommit(), StripBom.FALSE, null), wu);
	}
	
	@Transactional
	public Optional<SourceCodeEntryData> parseSceFromReleaseCreate (SceDto sceDto, List<UUID> artIds, BranchData bd,
			String branchStr,
			String version,
			WhoUpdated wu) throws IOException, RelizaException {
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
		Release release = ossReleaseService.updateRelease(releaseDto, UpdateReleaseStrength.FULL, wu);
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
	
	/**
	 * Process and upload release artifacts, then attach them to the release
	 */
	@Transactional
	public void processReleaseArtifacts(List<Map<String, Object>> artifactsList, ReleaseData rd, 
			ComponentData cd, OrganizationData od, String version, WhoUpdated wu) throws RelizaException {
		if (null == artifactsList || artifactsList.isEmpty()) {
			return;
		}
		
		// Extract PURL from release identifiers
		String purl = null;
		if (null != rd.getIdentifiers()) {
			Optional<TeaIdentifier> purlId = rd.getIdentifiers().stream()
				.filter(id -> id.getIdType() == TeaIdentifierType.PURL)
				.findFirst();
			if (purlId.isPresent()) {
				purl = purlId.get().getIdValue();
			}
		}
		
		// Upload artifacts
		List<UUID> artIds = artifactService.uploadListOfArtifacts(
			od, artifactsList,
			new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.RELEASE, null, StripBom.FALSE, purl),
			wu
		);
		
		// Attach artifacts to release
		for (UUID artId : artIds) {
			addArtifact(artId, rd.getUuid(), wu);
		}
	}
	
	/**
	 * Process and upload deliverable artifacts, then attach them to the deliverable
	 */
	@Transactional
	public void processDeliverableArtifacts(List<Map<String, Object>> delArtsList, ComponentData cd, 
			OrganizationData od, String version, WhoUpdated wu) throws RelizaException {
		if (null == delArtsList || delArtsList.isEmpty()) {
			return;
		}
		
		for (Map<String, Object> delArts : delArtsList) {
			String deliverableIdStr = (String) delArts.get("deliverable");
			if (StringUtils.isEmpty(deliverableIdStr)) {
				throw new RelizaException("'deliverable' field is required in deliverableArtifacts");
			}
			
			UUID deliverableId = UUID.fromString(deliverableIdStr);
			DeliverableData dd = getDeliverableService.getDeliverableData(deliverableId)
				.orElseThrow(() -> new RelizaException("Deliverable not found: " + deliverableId));
			
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> artifactsList = (List<Map<String, Object>>) delArts.get("artifacts");
			if (null == artifactsList || artifactsList.isEmpty()) {
				throw new RelizaException("'artifacts' list cannot be empty in deliverableArtifacts");
			}
			
			// Extract PURL from deliverable identifiers
			String purl = null;
			if (null != dd.getIdentifiers()) {
				Optional<TeaIdentifier> purlId = dd.getIdentifiers().stream()
					.filter(id -> id.getIdType() == TeaIdentifierType.PURL)
					.findFirst();
				if (purlId.isPresent()) {
					purl = purlId.get().getIdValue();
				}
			}
			
			// Extract digest from deliverable's software metadata
			String hash = null;
			if (null != dd.getSoftwareMetadata() && null != dd.getSoftwareMetadata().getDigestRecords() 
					&& !dd.getSoftwareMetadata().getDigestRecords().isEmpty()) {
				Optional<DigestRecord> sha256 = dd.getSoftwareMetadata().getDigestRecords().stream()
					.filter(dr -> dr.algo() == TeaChecksumType.SHA_256)
					.findFirst();
				if (sha256.isPresent()) {
					hash = sha256.get().digest();
				}
			}
			
			// Upload artifacts
			List<UUID> artIds = artifactService.uploadListOfArtifacts(
				od, artifactsList,
				new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.DELIVERABLE, hash, StripBom.FALSE, purl),
				wu
			);
			
			// Attach artifacts to deliverable
			for (UUID artId : artIds) {
				deliverableService.addArtifact(deliverableId, artId, wu);
			}
		}
	}
	
	/**
	 * Process and upload SCE artifacts, then attach them to the source code entry
	 */
	@Transactional
	public void processSceArtifacts(List<Map<String, Object>> sceArtsList, ComponentData cd, 
			OrganizationData od, String version, WhoUpdated wu) throws RelizaException {
		if (null == sceArtsList || sceArtsList.isEmpty()) {
			return;
		}
		
		for (Map<String, Object> sceArts : sceArtsList) {
			String sceIdStr = (String) sceArts.get("sce");
			if (StringUtils.isEmpty(sceIdStr)) {
				throw new RelizaException("'sce' field is required in sceArtifacts");
			}
			
			UUID sceUuid = UUID.fromString(sceIdStr);
			SourceCodeEntryData sced = getSourceCodeEntryService.getSourceCodeEntryData(sceUuid)
				.orElseThrow(() -> new RelizaException("Source Code Entry not found: " + sceUuid));
			
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> artifactsList = (List<Map<String, Object>>) sceArts.get("artifacts");
			if (null == artifactsList || artifactsList.isEmpty()) {
				throw new RelizaException("'artifacts' list cannot be empty in sceArtifacts");
			}
			
			// Upload artifacts
			List<UUID> artIds = artifactService.uploadListOfArtifacts(
				od, artifactsList,
				new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.SCE, sced.getCommit(), StripBom.FALSE, null),
				wu
			);
			
			// Attach artifacts to SCE
			for (UUID artId : artIds) {
				SCEArtifact sceArt = new SCEArtifact(artId, cd.getUuid());
				sourceCodeEntryService.addArtifact(sceUuid, sceArt, wu);
			}
		}
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
	public void autoIntegrateFeatureSetOnDemand (BranchData bd) {
		// check that status of this child project is not ignored
		log.info("PSDEBUG: autointegrate feature set on demand for bd = " + bd.getUuid());
		// Use effective dependencies which includes pattern-resolved dependencies
		List<ChildComponent> dependencies = dependencyPatternService.resolveEffectiveDependencies(bd);
		// log.info("autointegrate on-demand, dependencies found: {}", dependencies);
		
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
					// obtain latest ASSEMBLED release to match automatic auto-integrate behavior
					ord = sharedReleaseService.getReleaseDataOfBranch(bd.getOrg(), cp.getBranch(), ReleaseLifecycle.ASSEMBLED);
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
			// create new product release using shared method
			ossReleaseService.createProductRelease(bd, bd.getOrg(), parentReleases);
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
	
	public void computeReleaseMetrics (UUID releaseId, boolean onRescan) {
		Optional<Release> or = sharedReleaseService.getRelease(releaseId);
		if (or.isPresent()) {
			if (onRescan) {
				releaseMetricsComputeService.computeReleaseMetricsOnRescan(or.get());
			} else {
				releaseMetricsComputeService.computeReleaseMetricsOnNonRescan(or.get());
			}
		} else {
			log.warn("Attempted to compute metrics for non-existent release = " + releaseId);
		}
	}
	
	protected void computeMetricsForAllUnprocessedReleases () {
		log.debug("[compute metrics scheduler]: start compute metrics run");
		// Snapshot the cutoff timestamp at the start to ensure all artifact-based queries use the same stable value
		Double cutoffTimestamp = repository.findMaxReleaseLastScannedTimestamp();
		if (cutoffTimestamp == null) {
			cutoffTimestamp = 0.0;
		}
		log.debug("Using cutoff timestamp {} for metrics computation", cutoffTimestamp);
		
		var releasesByArt = repository.findReleasesForMetricsComputeByArtifactDirect(cutoffTimestamp);
		log.debug("[compute metrics scheduler]: releases by art size = " + releasesByArt.size());
		for (var r : releasesByArt) log.debug("[compute metrics scheduler]: release by art uuid = " + r.getUuid());
		var releasesBySce = repository.findReleasesForMetricsComputeBySce(cutoffTimestamp);
		log.debug("[compute metrics scheduler]: releases by sce size = " + releasesByArt.size());
		for (var r : releasesBySce) log.debug("[compute metrics scheduler]: release by sce uuid = " + r.getUuid());
		var releasesByOutboundDel = repository.findReleasesForMetricsComputeByOutboundDeliverables(cutoffTimestamp);
		log.debug("[compute metrics scheduler]: releases by outbound del size = " + releasesByArt.size());
		for (var r : releasesByOutboundDel) log.debug("[compute metrics scheduler]: release by od uuid = " + r.getUuid());
		var releasesByUpdateDate = repository.findReleasesForMetricsComputeByUpdate();
		log.debug("[compute metrics scheduler]: releases by updated date del size = " + releasesByUpdateDate.size());
		for (var r : releasesByUpdateDate) log.debug("[compute metrics scheduler]: release by upd uuid = " + r.getUuid());
		Set<UUID> dedupProcessedReleases = new HashSet<>();
		computeMetricsForReleaseList(releasesByArt, dedupProcessedReleases);
		computeMetricsForReleaseList(releasesBySce, dedupProcessedReleases);
		computeMetricsForReleaseList(releasesByOutboundDel, dedupProcessedReleases);
		computeMetricsForReleaseList(releasesByUpdateDate, dedupProcessedReleases);
		log.debug("processed releases size for metrics = " + dedupProcessedReleases.size());
		
		var productReleases = findProductReleasesFromComponentsForMetrics(dedupProcessedReleases);
		computeMetricsForReleaseList(productReleases, dedupProcessedReleases);
		log.debug("[compute metrics scheduler]: end compute metrics run");
		log.debug("processed product releases size for metrics = " + productReleases.size());
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
			Set<UUID> dedupProcessedReleases) {
		releaseList.forEach(r -> {
			if (!dedupProcessedReleases.contains(r.getUuid())) {
				releaseMetricsComputeService.computeReleaseMetricsOnRescan(r);
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
	
	/**
	 * Generate CycloneDX 1.6 VDR from release metrics vulnerability details
	 * @param releaseUuid Release UUID
	 * @param includeSuppressed Whether to include suppressed vulnerabilities (FALSE_POSITIVE, NOT_AFFECTED)
	 * @return VDR JSON string
	 */
	public String generateVdr(UUID releaseUuid, Boolean includeSuppressed) throws Exception {
		Optional<ReleaseData> releaseOpt = sharedReleaseService.getReleaseData(releaseUuid);
		if (releaseOpt.isEmpty()) {
			throw new RelizaException("Release not found; uuid: " + releaseUuid.toString());
		}
		return generateVdr(releaseOpt.get(), includeSuppressed);
	}
	
	/**
	 * Generate CycloneDX 1.6 VDR from release metrics vulnerability details
	 * @param releaseData Release data
	 * @param includeSuppressed Whether to include suppressed vulnerabilities (FALSE_POSITIVE, NOT_AFFECTED)
	 * @return VDR JSON string
	 */
	public String generateVdr(ReleaseData releaseData, Boolean includeSuppressed) throws Exception {
		Bom bom = new Bom();
		
		// Set metadata
		OrganizationData orgData = getOrganizationService.getOrganizationData(releaseData.getOrg()).orElse(null);
		ComponentData componentData = getComponentService.getComponentData(releaseData.getComponent()).orElse(null);
		
		Component bomComponent = new Component();
		if (componentData != null) {
			bomComponent.setName(componentData.getName());
		}
		bomComponent.setType(Type.APPLICATION);
		bomComponent.setVersion(releaseData.getVersion());
		
		String orgName = orgData != null ? orgData.getName() : "Unknown";
		Utils.setRearmBomMetadata(bom, orgName, bomComponent);
		
		// Transform vulnerabilities to CycloneDX format
		ReleaseMetricsDto metrics = releaseData.getMetrics();
		if (metrics != null && metrics.getVulnerabilityDetails() != null) {
			// Collect all unique PURLs and release UUIDs from FindingSourceDto
			Set<String> allPurls = new LinkedHashSet<>();
			Set<UUID> allReleaseUuids = new LinkedHashSet<>();
			
			for (VulnerabilityDto vulnDto : metrics.getVulnerabilityDetails()) {
				if (vulnDto.purl() != null) {
					allPurls.add(vulnDto.purl());
				}
				if (vulnDto.sources() != null) {
					for (FindingSourceDto source : vulnDto.sources()) {
						if (source.release() != null && !source.release().equals(releaseData.getUuid())) {
							allReleaseUuids.add(source.release());
						}
					}
				}
			}
			
			// Build components list: PURLs as library components + releases as application components
			List<Component> components = new ArrayList<>();
			
			// Add PURL components (type=library, bom-ref=purl)
			for (String purl : allPurls) {
				Component purlComponent = new Component();
				purlComponent.setType(Type.LIBRARY);
				purlComponent.setName(purl);
				purlComponent.setPurl(purl);
				purlComponent.setBomRef(purl);
				components.add(purlComponent);
			}
			
			// Add release components (type=application) using OBOM logic
			// Also build a map of releaseUuid -> bomRef for affects references
			Map<UUID, String> releaseBomRefMap = new java.util.HashMap<>();
			
			for (UUID releaseUuid : allReleaseUuids) {
				Optional<ReleaseData> relOpt = sharedReleaseService.getReleaseData(releaseUuid);
				if (relOpt.isPresent()) {
					ReleaseData rd = relOpt.get();
					
					// Determine bom-ref: use PURL if available, otherwise use release UUID
					String bomRef = null;
					if (rd.getIdentifiers() != null) {
						Optional<TeaIdentifier> purlId = rd.getIdentifiers().stream()
								.filter(id -> id.getIdType() == TeaIdentifierType.PURL)
								.findFirst();
						if (purlId.isPresent()) {
							bomRef = purlId.get().getIdValue();
						}
					}
					if (bomRef == null) {
						bomRef = releaseUuid.toString();
					}
					releaseBomRefMap.put(releaseUuid, bomRef);
					
					// Create component using OBOM-like logic
					Component releaseComponent = new Component();
					releaseComponent.setType(Type.APPLICATION);
					releaseComponent.setBomRef(bomRef);
					releaseComponent.setVersion(rd.getVersion());
					
					Optional<ComponentData> compOpt = getComponentService.getComponentData(rd.getComponent());
					if (compOpt.isPresent()) {
						releaseComponent.setName(compOpt.get().getName());
					}
					
					// Set PURL if available
					if (bomRef != null && bomRef.startsWith("pkg:")) {
						releaseComponent.setPurl(bomRef);
					}
					
					components.add(releaseComponent);
				}
			}
			
			bom.setComponents(components);
			
			// Fetch analysis records for this release (without dependencies)
			Map<String, VulnAnalysisData> analysisMap = new java.util.HashMap<>();
			try {
				List<VulnAnalysisData> allAnalyses = vulnAnalysisService.findAllVulnAnalysisAffectingRelease(releaseData.getUuid());
				for (VulnAnalysisData analysis : allAnalyses) {
					String key = computeAnalysisKey(analysis.getLocation(), analysis.getFindingId());
					analysisMap.put(key, analysis);
				}
			} catch (Exception e) {
				log.debug("Could not fetch analysis records for release {}: {}", releaseData.getUuid(), e.getMessage());
			}
			
			// Also fetch analysis records for each dependency release
			Map<UUID, Map<String, VulnAnalysisData>> releaseAnalysisMaps = new java.util.HashMap<>();
			for (UUID releaseUuid : allReleaseUuids) {
				try {
					Map<String, VulnAnalysisData> relAnalysisMap = new java.util.HashMap<>();
					List<VulnAnalysisData> relAnalyses = vulnAnalysisService.findAllVulnAnalysisAffectingRelease(releaseUuid);
					for (VulnAnalysisData analysis : relAnalyses) {
						String key = computeAnalysisKey(analysis.getLocation(), analysis.getFindingId());
						relAnalysisMap.put(key, analysis);
					}
					releaseAnalysisMaps.put(releaseUuid, relAnalysisMap);
				} catch (Exception e) {
					log.debug("Could not fetch analysis records for dependency release {}: {}", releaseUuid, e.getMessage());
				}
			}
			
			// Build VDR context for transformation
			VdrContext vdrContext = new VdrContext(analysisMap, releaseAnalysisMaps, releaseBomRefMap, releaseData.getUuid());
			
			// Transform vulnerabilities - split by analysis state if sources have different states
			List<Vulnerability> vulnerabilities = new ArrayList<>();
			
			for (VulnerabilityDto vulnDto : metrics.getVulnerabilityDetails()) {
				List<Vulnerability> vulnEntries = transformVulnerabilityToVdr(vulnDto, vdrContext, includeSuppressed);
				vulnerabilities.addAll(vulnEntries);
			}
			
			bom.setVulnerabilities(vulnerabilities);
		}
		
		// Generate JSON using CycloneDX 1.6
		BomJsonGenerator generator = BomGeneratorFactory.createJson(org.cyclonedx.Version.VERSION_16, bom);
		return generator.toJsonString();
	}
	
	/**
	 * Context object for VDR transformation containing analysis maps and release mappings
	 */
	private record VdrContext(
		Map<String, VulnAnalysisData> selfAnalysisMap,
		Map<UUID, Map<String, VulnAnalysisData>> releaseAnalysisMaps,
		Map<UUID, String> releaseBomRefMap,
		UUID selfReleaseUuid
	) {}
	
	/**
	 * Transform internal VulnerabilityDto to CycloneDX Vulnerability entries.
	 * If sources have different analysis states, creates separate vulnerability entries for each state.
	 * @param vulnDto The vulnerability DTO
	 * @param vdrContext Context containing analysis maps and release mappings
	 * @param includeSuppressed Whether to include suppressed vulnerabilities
	 * @return List of vulnerability entries (may be multiple if sources have different analysis states)
	 */
	private List<Vulnerability> transformVulnerabilityToVdr(VulnerabilityDto vulnDto, VdrContext vdrContext, Boolean includeSuppressed) {
		List<Vulnerability> result = new ArrayList<>();
		
		// Group sources by analysis state
		Map<AnalysisState, List<FindingSourceDto>> sourcesByState = new java.util.HashMap<>();
		
		if (vulnDto.sources() != null && !vulnDto.sources().isEmpty()) {
			for (FindingSourceDto source : vulnDto.sources()) {
				AnalysisState state = source.analysisState();
				sourcesByState.computeIfAbsent(state, k -> new ArrayList<>()).add(source);
			}
		} else {
			// No sources - use the vulnerability's own analysis state
			sourcesByState.put(vulnDto.analysisState(), new ArrayList<>());
		}
		
		// Create a vulnerability entry for each distinct analysis state
		for (Map.Entry<AnalysisState, List<FindingSourceDto>> entry : sourcesByState.entrySet()) {
			AnalysisState analysisState = entry.getKey();
			List<FindingSourceDto> sourcesForState = entry.getValue();
			
			// Skip suppressed vulnerabilities unless includeSuppressed is true
			if (!Boolean.TRUE.equals(includeSuppressed) 
					&& (analysisState == AnalysisState.FALSE_POSITIVE 
					|| analysisState == AnalysisState.NOT_AFFECTED)) {
				continue;
			}
			
			Vulnerability vuln = buildVdrVulnerabilityEntry(vulnDto, analysisState, sourcesForState, vdrContext);
			result.add(vuln);
		}
		
		return result;
	}
	
	/**
	 * Build a single CycloneDX Vulnerability entry for a specific analysis state
	 */
	private Vulnerability buildVdrVulnerabilityEntry(VulnerabilityDto vulnDto, AnalysisState analysisState, 
			List<FindingSourceDto> sourcesForState, VdrContext vdrContext) {
		Vulnerability vuln = new Vulnerability();
		
		// Set vulnerability ID
		vuln.setId(vulnDto.vulnId());
		
		// Set source based on vulnerability ID prefix
		Source source = new Source();
		if (vulnDto.vulnId() != null) {
			if (vulnDto.vulnId().startsWith("CVE-")) {
				source.setName("NVD");
				source.setUrl("https://nvd.nist.gov/vuln/detail/" + vulnDto.vulnId());
			} else if (vulnDto.vulnId().startsWith("GHSA-")) {
				source.setName("GitHub Advisory");
				source.setUrl("https://github.com/advisories/" + vulnDto.vulnId());
			} else {
				source.setName("Other");
			}
		}
		vuln.setSource(source);
		
		// Set severity rating
		if (vulnDto.severity() != null) {
			Rating rating = new Rating();
			rating.setSeverity(mapVdrSeverity(vulnDto.severity()));
			vuln.setRatings(List.of(rating));
		}
		
		// Build affects list - include both PURL and release bom-refs
		List<Vulnerability.Affect> affects = new ArrayList<>();
		
		// Always add the PURL as an affect
		if (vulnDto.purl() != null) {
			Vulnerability.Affect purlAffect = new Vulnerability.Affect();
			purlAffect.setRef(vulnDto.purl());
			affects.add(purlAffect);
		}
		
		// Add release bom-refs from sources with this analysis state
		Set<String> addedRefs = new HashSet<>();
		if (vulnDto.purl() != null) {
			addedRefs.add(vulnDto.purl()); // Don't duplicate PURL
		}
		
		for (FindingSourceDto srcDto : sourcesForState) {
			if (srcDto.release() != null && !srcDto.release().equals(vdrContext.selfReleaseUuid())) {
				String bomRef = vdrContext.releaseBomRefMap().get(srcDto.release());
				if (bomRef != null && !addedRefs.contains(bomRef)) {
					Vulnerability.Affect releaseAffect = new Vulnerability.Affect();
					releaseAffect.setRef(bomRef);
					affects.add(releaseAffect);
					addedRefs.add(bomRef);
				}
			}
		}
		
		if (!affects.isEmpty()) {
			vuln.setAffects(affects);
		}
		
		// Set analysis state and details
		if (analysisState != null) {
			Vulnerability.Analysis analysis = new Vulnerability.Analysis();
			analysis.setState(mapVdrAnalysisState(analysisState));
			
			// Look up analysis details - first try self release, then dependency releases
			VulnAnalysisData analysisData = null;
			if (vulnDto.purl() != null && vulnDto.vulnId() != null) {
				String key = computeAnalysisKey(vulnDto.purl(), vulnDto.vulnId());
				
				// Try self release first
				analysisData = vdrContext.selfAnalysisMap().get(key);
				
				// If not found, try dependency releases that have this analysis state
				if (analysisData == null) {
					for (FindingSourceDto srcDto : sourcesForState) {
						if (srcDto.release() != null) {
							Map<String, VulnAnalysisData> relMap = vdrContext.releaseAnalysisMaps().get(srcDto.release());
							if (relMap != null) {
								analysisData = relMap.get(key);
								if (analysisData != null) {
									break;
								}
							}
						}
					}
				}
			}
			
			if (analysisData != null) {
				// Set justification if present
				if (analysisData.getAnalysisJustification() != null) {
					analysis.setJustification(mapVdrAnalysisJustification(analysisData.getAnalysisJustification()));
				}
				// Set detail from the latest history entry if present
				List<VulnAnalysisData.AnalysisHistory> history = analysisData.getAnalysisHistory();
				if (history != null && !history.isEmpty()) {
					String latestDetail = history.get(history.size() - 1).getDetails();
					if (StringUtils.isNotEmpty(latestDetail)) {
						analysis.setDetail(latestDetail);
					}
				}
			}
			
			vuln.setAnalysis(analysis);
		}
		
		return vuln;
	}

	private String computeAnalysisKey(String purl, String vulnId) {
		return Utils.minimizePurl(purl) + "|" + vulnId;
	}
	
	/**
	 * Map internal severity to CycloneDX severity
	 */
	private org.cyclonedx.model.vulnerability.Vulnerability.Rating.Severity mapVdrSeverity(VulnerabilitySeverity severity) {
		return switch (severity) {
			case CRITICAL -> org.cyclonedx.model.vulnerability.Vulnerability.Rating.Severity.CRITICAL;
			case HIGH -> org.cyclonedx.model.vulnerability.Vulnerability.Rating.Severity.HIGH;
			case MEDIUM -> org.cyclonedx.model.vulnerability.Vulnerability.Rating.Severity.MEDIUM;
			case LOW -> org.cyclonedx.model.vulnerability.Vulnerability.Rating.Severity.LOW;
			case UNASSIGNED -> org.cyclonedx.model.vulnerability.Vulnerability.Rating.Severity.UNKNOWN;
		};
	}
	
	/**
	 * Map internal analysis state to CycloneDX analysis state
	 */
	private Vulnerability.Analysis.State mapVdrAnalysisState(AnalysisState state) {
		return switch (state) {
			case EXPLOITABLE -> Vulnerability.Analysis.State.EXPLOITABLE;
			case IN_TRIAGE -> Vulnerability.Analysis.State.IN_TRIAGE;
			case FALSE_POSITIVE -> Vulnerability.Analysis.State.FALSE_POSITIVE;
			case NOT_AFFECTED -> Vulnerability.Analysis.State.NOT_AFFECTED;
		};
	}
	
	/**
	 * Map internal analysis justification to CycloneDX analysis justification
	 */
	private Vulnerability.Analysis.Justification mapVdrAnalysisJustification(AnalysisJustification justification) {
		return switch (justification) {
			case CODE_NOT_PRESENT -> Vulnerability.Analysis.Justification.CODE_NOT_PRESENT;
			case CODE_NOT_REACHABLE -> Vulnerability.Analysis.Justification.CODE_NOT_REACHABLE;
			case REQUIRES_CONFIGURATION -> Vulnerability.Analysis.Justification.REQUIRES_CONFIGURATION;
			case REQUIRES_DEPENDENCY -> Vulnerability.Analysis.Justification.REQUIRES_DEPENDENCY;
			case REQUIRES_ENVIRONMENT -> Vulnerability.Analysis.Justification.REQUIRES_ENVIRONMENT;
			case PROTECTED_BY_COMPILER -> Vulnerability.Analysis.Justification.PROTECTED_BY_COMPILER;
			case PROTECTED_AT_RUNTIME -> Vulnerability.Analysis.Justification.PROTECTED_AT_RUNTIME;
			case PROTECTED_AT_PERIMETER -> Vulnerability.Analysis.Justification.PROTECTED_AT_PERIMETER;
			case PROTECTED_BY_MITIGATING_CONTROL -> Vulnerability.Analysis.Justification.PROTECTED_BY_MITIGATING_CONTROL;
		};
	}
	
}
