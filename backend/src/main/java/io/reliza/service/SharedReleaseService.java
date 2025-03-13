/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.GenericReleaseData;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseVersionComparator;
import io.reliza.repositories.ReleaseRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SharedReleaseService {

	@Autowired
	BranchService branchService;
	
	@Autowired
	GetComponentService getComponentService;
	
	protected final static Integer DEFAULT_NUM_RELEASES = 300;
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
	
	protected List<GenericReleaseData> listReleaseDataOfBranch (UUID branchUuid, UUID orgUuid, ReleaseLifecycle lifecycle, Integer limit) {
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
}
