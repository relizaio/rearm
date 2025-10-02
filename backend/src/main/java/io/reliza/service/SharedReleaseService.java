/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.packageurl.PackageURL;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.GenericReleaseData;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ReleaseData.ReleaseDateComparator;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseVersionComparator;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.ReleaseService.CommitRecord;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SharedReleaseService {

	@Autowired
	BranchService branchService;
	
	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	GetSourceCodeEntryService getSourceCodeEntryService;
	
	@Autowired
	ArtifactService artifactService;
	
	public final static Integer DEFAULT_NUM_RELEASES = 300;
	private final static Integer DEFAULT_NUM_RELEASES_FOR_LATEST_RELEASE = 20;
	
	private final ReleaseRepository repository;
	
	SharedReleaseService(ReleaseRepository repository) {
		this.repository = repository;
	}
	
	public Optional<Release> getRelease (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<Release> getRelease (UUID releaseUuid, UUID orgUuid) {
		return repository.findReleaseByIdAndOrg(releaseUuid, orgUuid.toString());
	}
	
	public Optional<ReleaseData> getReleaseData (UUID uuid) {
		Optional<ReleaseData> ord = Optional.empty();
		Optional<Release> r = getRelease(uuid);
		if (r.isPresent()) {
			ord = Optional
							.of(
								ReleaseData
									.dataFromRecord(r
										.get()
								));
		}
		return ord;
	}
	
	public Optional<ReleaseData> getReleaseData (UUID uuid, UUID myOrgUuid) throws RelizaException {
		Optional<ReleaseData> orData = Optional.empty();
		Optional<Release> r = getRelease(uuid, myOrgUuid);
		if (r.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(r.get());
			orData = Optional.of(rd);
		}
		return orData;
	}

	/**
	 * This method returns latest Release data of specific branch or feature set
	 * @param branchUuid - UUID of desired branch or feature set
	 * @param et - Environment Type for which to check specific approvals, if empty method attempts to find latest release
	 * @return Optional of latest release data for specified environment; if not found returns empty Optional
	 */
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID branchUuid) {
		BranchData bd = branchService.getBranchData(branchUuid).get();
		return getReleaseDataOfBranch(bd.getOrg(), branchUuid, ReleaseLifecycle.ASSEMBLED);
	}
	
	/**
	 * This method returns the latest Release data of a specific branch or feature set
	 * which is NOT CANCELLED or REJECTED
	 * @param branchUuid - UUID of desired branch or feature set
	 * @return Optional of latest release data for specified branch, excluding CANCELLED or REJECTED; if not found returns empty Optional
	 */
	public Optional<ReleaseData> getLatestNonCancelledOrRejectedReleaseDataOfBranch(UUID branchUuid) {
		BranchData bd = branchService.getBranchData(branchUuid).orElseThrow();
		log.info("bd: {}", bd);
		List<ReleaseData> releases = listReleaseDataOfBranch(branchUuid, true); // sorted by version/date desc
		return releases.stream()
				.filter(rd -> rd.getLifecycle() != ReleaseData.ReleaseLifecycle.CANCELLED
						&& rd.getLifecycle() != ReleaseData.ReleaseLifecycle.REJECTED)
				.findFirst();
	}
	/**
	 * This method returns latest Release data of specific branch or feature set
	 * @param branchUuid - UUID of desired branch or feature set
	 * @param et - Environment Type for which to check specific approvals, if empty method attempts to find latest release
	 * @return Optional of latest release data for specified environment; if not found returns empty Optional
	 */
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID orgUuid, UUID branchUuid) {
		return getReleaseDataOfBranch(orgUuid, branchUuid, ReleaseLifecycle.ASSEMBLED);
	}
	
	/**
	 * 
	 * @param orgUuid needs to be included, because branch may belong to external org
	 * @param branchUuid
	 * @param et
	 * @param status
	 * @return
	 */
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID orgUuid, UUID branchUuid, ReleaseLifecycle lifecycle) {
		BranchData bd = branchService.getBranchData(branchUuid).get();
		if (null == orgUuid) orgUuid = bd.getOrg();
		ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();
		Optional<ReleaseData> ord = Optional.empty();
		List<GenericReleaseData> brReleaseData = listReleaseDataOfBranch(branchUuid, orgUuid, lifecycle, DEFAULT_NUM_RELEASES_FOR_LATEST_RELEASE);
		if (!brReleaseData.isEmpty()) {
			Collections.sort(brReleaseData, new ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
			try {
				ord = getReleaseData(brReleaseData.get(0).getUuid(), orgUuid);
			} catch (RelizaException e) {
				log.error("Exception on getting release data in latest of branch", e);
			}
		}
		return ord;
	}
	
	public List<ReleaseData> listReleaseDataOfBranch (UUID branchUuid) {
		return listReleaseDataOfBranch(branchUuid, false);
	}
	
	public List<ReleaseData> listReleaseDataOfBranch (UUID branchUuid, boolean sorted) {
		return listReleaseDataOfBranch(branchUuid, 300, sorted);
	}
	
	public List<ReleaseData> listReleaseDataOfBranch (UUID branchUuid, Integer numRecords, boolean sorted) {
		return listReleaseDataOfBranch(branchUuid, null, numRecords, sorted);
	}

	public List<ReleaseData> listReleaseDataOfBranch (UUID branchUuid, Integer prNumber, Integer numRecords, boolean sorted) {
		if (null == numRecords || 0 == numRecords) numRecords = DEFAULT_NUM_RELEASES;
		List<Release> releases = new ArrayList<>();
		List<UUID> sces = null;
		if(prNumber != null && prNumber > 0){
			BranchData bd = branchService.getBranchData(branchUuid).orElseThrow();
			sces = bd.getPullRequestData().get(prNumber).getCommits();
			log.info("sces: {}", sces);
			releases = listReleasesOfBranchWhereInSces(branchUuid, sces, numRecords, 0);
		} else 
			releases = listReleasesOfBranch(branchUuid, numRecords, 0);
			
		List<ReleaseData> rdList = releases
										.stream()
										.map(ReleaseData::dataFromRecord)
										.collect(Collectors.toList());
		if (sorted) {
			BranchData bd = branchService.getBranchData(branchUuid).get();
			ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();
			rdList.sort(new ReleaseData.ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
		}
		return rdList;
	}
	
	public List<GenericReleaseData> listReleaseDataOfBranch (UUID branchUuid, UUID orgUuid, ReleaseLifecycle lifecycle, Integer limit) {
		List<Release> releases = listReleasesOfBranch(branchUuid, limit, 0);
		List<GenericReleaseData> retList = releases
						.stream()
						.map(ReleaseData::dataFromRecord)
						.filter(r -> (null == lifecycle || r.getLifecycle().ordinal() >= lifecycle.ordinal()))
						.collect(Collectors.toList());
		return retList;
	}

	private List<Release> listReleasesOfBranch (UUID branchUuid,  Integer limit, Integer offset) {
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		String offsetAsStr = null;
		if (null == offset || offset < 0) {
			offsetAsStr = "0";
		} else {
			offsetAsStr = offset.toString();
		}
		return repository.findReleasesOfBranch(branchUuid.toString(), limitAsStr, offsetAsStr);
	}

	private List<Release> listReleasesOfBranchWhereInSces (UUID branchUuid, List<UUID> sces, Integer limit, Integer offset) {
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		String offsetAsStr = null;
		if (null == offset || offset < 0) {
			offsetAsStr = "0";
		} else {
			offsetAsStr = offset.toString();
		}

		List<Release> releases = new ArrayList<>();
		if(sces != null && sces.size()>0){
			List<String> scesString = sces.stream().map(sce -> sce.toString()).collect(Collectors.toList());
			releases =  repository.findReleasesOfBranchWhereInSce(branchUuid.toString(), scesString, limitAsStr, offsetAsStr);
		}
		return releases;
	}
	
	public List<ReleaseData> listReleaseDatasOfComponent (UUID componentUuid, Integer limit, Integer offset) {
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		String offsetAsStr = null;
		if (null == offset || offset < 0) {
			offsetAsStr = "0";
		} else {
			offsetAsStr = offset.toString();
		}
		var releases = repository.findReleasesOfComponent(componentUuid.toString(), limitAsStr, offsetAsStr);
		return releases.stream().map(ReleaseData::dataFromRecord).toList();
	}
	
	/**
	 * This method parses 10 most recent releases of a product (could be a component as well) to establish component level dependencies
	 * @param componentUuid
	 * @return
	 */
	public Set<UUID> obtainComponentsOfProductOrComponent (UUID componentUuid, Set<UUID> dedupComponents) {
		Set<UUID> components = new LinkedHashSet<>();
		var latestReleases = listReleaseDatasOfComponent(componentUuid, 10, 0);
		if (!latestReleases.isEmpty()) {
			Set<ReleaseData> releaseDeps = new LinkedHashSet<>();
			latestReleases.forEach(x -> releaseDeps.addAll(unwindReleaseDependencies(x)));
			releaseDeps.forEach(rd -> {
				if (!dedupComponents.contains(rd.getComponent())) {
					components.add(rd.getComponent());
					var recursiveComps = obtainComponentsOfProductOrComponent(rd.getComponent(), components);
					components.addAll(recursiveComps);
				}
			});
		}
		return components;
	}
	
	/**
	 * This method recursively checks all release components, then components of those releases and finally flattens all that to a list
	 * @param releaseUuid
	 * @return List of releases that are dependencies to the release we are unwinding
	 */
	public Set<ReleaseData> unwindReleaseDependencies (ReleaseData rd) {
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
	
	/**
	 * This method will locate release products with only 1st level depth, without recursion
	 * Much more efficient than locateAllProductsOfRelease
	 * @param rd
	 * @return
	 */
	public Set<ReleaseData> greedylocateProductsOfRelease (ReleaseData rd) {
		return greedylocateProductsOfRelease(rd, null, true);
	}
	
	
	protected Set<ReleaseData> greedylocateProductsOfReleaseCollection (Collection<ReleaseData> inputRds, UUID myOrg) {
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
					ord = getReleaseDataOfBranch(bd.getOrg(), cp.getBranch(), lifecycle);
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
			sces = getSourceCodeEntryService.getSceDataList(commitIds, List.of(org, CommonVariables.EXTERNAL_PROJ_ORG_UUID));
		}
		return sces;
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
	
	//both dates are inclusive
	private List<Release> listReleasesOfBranchBetweenDates (UUID branchUuid,  ZonedDateTime fromDateTime, ZonedDateTime toDateTime) {
		return repository.findReleasesOfBranchBetweenDates(branchUuid.toString(), Utils.stringifyZonedDateTimeForSql(fromDateTime),
				Utils.stringifyZonedDateTimeForSql(toDateTime));
	}
	

	public UUID findPreviousReleasesOfBranchForRelease (UUID branchUuid,  UUID release) {
		return repository.findPreviousReleasesOfBranchForRelease(branchUuid.toString(), release);
	}
	public UUID findNextReleasesOfBranchForRelease (UUID branchUuid,  UUID release) {
		return repository.findNextReleasesOfBranchForRelease(branchUuid.toString(), release);
	}
	

	/**
	 * This method attempts to prepare a map of commit id to message for all commits
	 * @param sces - List of sce data
	 * @param org - UUID of the org
	 * @param commitIdToMessageMap - Map of commit id to commit, message, and uri for all commits in the releases
	 * @return Map of commit id to message for all commits
	 */
	public Map<UUID, CommitRecord> getCommitMessageMapForSceDataList(List<SourceCodeEntryData> sces, List<VcsRepositoryData> vrds, UUID org){
		
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
	
	public List<TeaIdentifier> resolveReleaseIdentifiersFromComponent(ReleaseData rd) {
		ComponentData cd = getComponentService.getComponentData(rd.getComponent()).get();
		return resolveReleaseIdentifiersFromComponent(rd.getVersion(), cd);
	}
	
	public List<TeaIdentifier> resolveReleaseIdentifiersFromComponent(String releaseVersion, ComponentData cd) {
		List<TeaIdentifier> releaseIdentifier = new LinkedList<>();
		try {
			var compIdentifiers = cd.getIdentifiers();
			if (null != compIdentifiers && !compIdentifiers.isEmpty()) {
				Optional<TeaIdentifier> purlIdentifier = compIdentifiers.stream().filter(x -> x.getIdType() == TeaIdentifierType.PURL).findFirst();
				if (purlIdentifier.isPresent()) {
					PackageURL purlObj = new PackageURL(purlIdentifier.get().getIdValue());
					PackageURL versionedPurl = Utils.setVersionOnPurl(purlObj, releaseVersion);
					TeaIdentifier teaPurl = new TeaIdentifier();
					teaPurl.setIdType(TeaIdentifierType.PURL);
					teaPurl.setIdValue(versionedPurl.canonicalize());
					releaseIdentifier.add(teaPurl);
				}
			}
		} catch (Exception e) {
			log.error("Error on resolving release identifiers from component", e);
		}
		return releaseIdentifier;
	}
	
	private Set<UUID> gatherReleaseIdsForArtifact(UUID artifactUuid, UUID orgUuid){
		Set<UUID> releases = new HashSet<>();
		Set<UUID> allDirectReleases = findReleasesByReleaseArtifact(artifactUuid, orgUuid).stream().map(r -> r.getUuid()).collect(Collectors.toSet());
		Set<UUID> allSceReleases = repository.findReleasesSharingSceArtifact(artifactUuid.toString()).stream().map(r -> r.getUuid()).collect(Collectors.toSet());
		Set<UUID> allDeliverableReleases = repository.findReleasesSharingDeliverableArtifact(artifactUuid.toString()).stream().map(r -> r.getUuid()).collect(Collectors.toSet());
		releases.addAll(allDirectReleases);
		releases.addAll(allSceReleases);
		releases.addAll(allDeliverableReleases);
		return releases;
	}

	public List<ReleaseData> gatherReleasesForArtifact(UUID artifactUuid, UUID orgUuid){
		Set<UUID> releaseIds = gatherReleaseIdsForArtifact(artifactUuid, orgUuid);
		var releaseDatas = getReleaseDataList(releaseIds, orgUuid);
		return sortReleasesByBranchAndVersion(releaseDatas);
	}

	private List<ReleaseData> sortReleasesByBranchAndVersion(List<ReleaseData> releaseDatas) {
		// Group releases by branch
		Map<UUID, List<ReleaseData>> releasesByBranch = releaseDatas.stream()
			.collect(Collectors.groupingBy(ReleaseData::getBranch));
		
		// Sort each branch group and collect results
		List<ReleaseData> sortedReleases = new LinkedList<>();
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = new LinkedList<>(entry.getValue());

			Optional<BranchData> bdOpt = branchService.getBranchData(branchUuid);
			if (bdOpt.isPresent()) {
				BranchData bd = bdOpt.get();
				Optional<ComponentData> pdOpt = getComponentService.getComponentData(bd.getComponent());
				if (pdOpt.isPresent()) {
					ComponentData pd = pdOpt.get();
					Collections.sort(branchReleases, new ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
				}
			}
			sortedReleases.addAll(branchReleases);
		}
		return sortedReleases;
	}
	
	public List<Release> findReleasesByReleaseArtifact (UUID artifactUuid, UUID orgUuid) {
		return repository.findReleasesByReleaseArtifact(artifactUuid.toString(), orgUuid.toString());
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
	
	public List<Release> findReleasesBySce(UUID sce, UUID org) {
		return repository.findReleaseBySce(sce.toString(), org.toString());
	}
	
	public List<ReleaseData> findReleaseDatasBySce(UUID sce, UUID org) {
		return findReleasesBySce(sce,org).stream().map(ReleaseData::dataFromRecord).toList();
	}
	
	public List<ReleaseData> findReleaseDatasByDtrackProjects(Collection<UUID> dtrackProjects, final UUID org) {
		Set<UUID> arts = artifactService.listArtifactsByDtrackProjects(dtrackProjects).stream().map(x -> x.getUuid()).collect(Collectors.toSet());
		Set<UUID> releaseIds = new HashSet<>();
		arts.forEach(aId -> {
			releaseIds.addAll(gatherReleaseIdsForArtifact(aId, org));
		});
		var releaseDatas = getReleaseDataList(releaseIds, org);
		return sortReleasesByBranchAndVersion(releaseDatas);
	}
}
