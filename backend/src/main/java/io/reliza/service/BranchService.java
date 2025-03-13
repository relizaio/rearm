/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.common.LambdaExceptionWrappers.handlingConsumerWrapper;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.BranchData.PullRequestData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.repositories.BranchRepository;
import io.reliza.versioning.VersionType;

@Service
public class BranchService {
	
	@Autowired
    private GetComponentService getComponentService;
	
	@Autowired
    private AuditService auditService;

	private final BranchRepository repository;

	BranchService(BranchRepository repository) {
	    this.repository = repository;
	}
	
	public Optional<Branch> getBranch (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<BranchData> getBranchData (UUID uuid) {
		Optional<BranchData> bData = Optional.empty();
		Optional<Branch> b = getBranch(uuid);
		if (b.isPresent()) {
			bData = Optional
							.of(
								BranchData
									.branchDataFromDbRecord(b
										.get()
								));
		}
		return bData;
	} 
	
	public List<Branch> listBranchesOfComponent (UUID component, StatusEnum status) {
		List<Branch> brList = null;
		if (null == status) {
			brList = repository.findBranchesOfComponent(component.toString());
		} else {
			brList = repository.findBranchesOfComponentByStatus(component.toString(), status.toString());
		}
		return brList;
	}
	
	public List<BranchData> listBranchDataOfComponent (UUID component, StatusEnum status) {
		List<Branch> branchList = listBranchesOfComponent(component, status);
		return transformBranchToBranchData(branchList);
	}
	
	public List<Branch> listBranchesOfOrg (UUID orgUuid) {
		return repository.findBranchesOfOrg(orgUuid.toString());
	}

	public List<Branch> getBranches (Iterable<UUID> uuids) {
		return (List<Branch>) repository.findAllById(uuids);
	}
	
	public List<BranchData> getBranchDataList (Iterable<UUID> uuids) {
		List<Branch> branches = getBranches(uuids);
		return branches.stream().map(BranchData::branchDataFromDbRecord).collect(Collectors.toList());
	}
	
	public List<BranchData> listBranchDataOfOrg (UUID orgUuid) {
		var branches = listBranchesOfOrg(orgUuid);
		return branches
					.stream()
					.map(BranchData::branchDataFromDbRecord)
					.collect(Collectors.toList());
	}
	
	public boolean isBaseBranchOfProjExists (UUID component) {
		return getBaseBranchOfComponent(component).isPresent();
	}
	
	public Optional<Branch> getBaseBranchOfComponent (UUID component) {
		return repository.findBaseBranch(component
												.toString());
	}
	
	public Optional<Branch> findBranchByName (UUID component, String name) throws RelizaException {
		return findBranchByName (component, name, false, null);
	}
	
	public Set<UUID> findDeadBranches (UUID component, List<String> liveBranchNames) {
		Set<UUID> deadBranches = new HashSet<>();
		Set<String> cleanedLiveBranchNames = liveBranchNames.stream()
				.map(x -> Utils.cleanBranch(x)).map(x -> x.toLowerCase()).collect(Collectors.toSet());
		List<Branch> activeBranches = listBranchesOfComponent(component, StatusEnum.ACTIVE);
		Iterator<Branch> brIter = activeBranches.iterator();
		while (brIter.hasNext()) {
			Branch testBr = brIter.next();
			BranchData testBd = BranchData.branchDataFromDbRecord(testBr);
			String vcsBranch = StringUtils.isEmpty(testBd.getVcsBranch()) ? "" : testBd.getVcsBranch().toLowerCase();
			if (testBd.getType() != BranchType.BASE && !cleanedLiveBranchNames.contains(testBd.getName().toLowerCase()) 
					&& !cleanedLiveBranchNames.contains(vcsBranch)) {
				deadBranches.add(testBr.getUuid());
			}
		}
		return deadBranches;
	}
	
	public Optional<Branch> findBranchByName (UUID component, String name, boolean create, WhoUpdated wu) throws RelizaException {
		Optional<Branch> ob = Optional.empty();
		List<Branch> branches = listBranchesOfComponent(component, null);
		Iterator<Branch> brIter = branches.iterator();
		// TODO: disallow attachment of vcs branch to project branch with name matching name of other existing branch 
		while (ob.isEmpty() && brIter.hasNext()) {
			Branch testBr = brIter.next();
			BranchData testBd = BranchData.branchDataFromDbRecord(testBr);
			if (name.equalsIgnoreCase(testBd.getName()) || name.equalsIgnoreCase(testBd.getVcsBranch())) {
				ob = Optional.of(testBr);
			}
		}
		
		if (ob.isEmpty() && create) {
			// create new branch and return it
			// TODO: sort out handling of vcs repository (consider removing it altogether and tying to branch to branch)
			ComponentData cd = getComponentService.getComponentData(component).get();
			BranchType bt = BranchData.resolveBranchTypeByName(name);
			ob = Optional.of(
					createBranch(name, component, bt, cd.getVcs(), name, cd.getFeatureBranchVersioning(), cd.getMarketingVersionSchema(), wu)
			);
		}
		
		return ob;
	}
	
	public List<Branch> findBranchesByChildComponentBranch (UUID orgUuid, UUID component, UUID branchUuid) {
		return repository.findBranchesByChildComponentBranch(orgUuid.toString(), component.toString(), branchUuid.toString());
	}
	
	public List<BranchData> findBranchDataByChildComponentBranch (UUID orgUuid, UUID component, UUID branchUuid) {
		return transformBranchToBranchData(findBranchesByChildComponentBranch(orgUuid, component, branchUuid));
	}

	public List<Branch> findFeatureSetsByChildComponent (UUID orgUuid, UUID component) {
		return repository.findFeatureSetsByChildComponent(orgUuid.toString(), component.toString());
	}
	
	public List<BranchData> findBranchDataByChildComponent (UUID orgUuid, UUID component) {
		return transformBranchToBranchData(findFeatureSetsByChildComponent(orgUuid, component));
	}
	
	private List<BranchData> transformBranchToBranchData (Collection<Branch> branches) {
		return branches.stream()
				.map(BranchData::branchDataFromDbRecord)
				.collect(Collectors.toList());
	}

	public Branch createBranch (String name, ComponentData cd,
			UUID vcsRepoUuid, String vcsBranch, String versionPin, String marketingVersionPin, WhoUpdated wu) throws RelizaException {
		BranchType bt = BranchData.resolveBranchTypeByName(name);
		return createBranch(name, cd, bt, vcsRepoUuid, vcsBranch, versionPin, marketingVersionPin, wu);
	}
	
	public Branch createBranch (String name, ComponentData cd, BranchType type, 
			UUID vcsRepoUuid, String vcsBranch, String versionPin, String marketingVersionPin, WhoUpdated wu) throws RelizaException {
		// if no vcs data or version data provided, use parent project settings
		if (null == vcsRepoUuid || StringUtils.isEmpty(vcsBranch) || StringUtils.isEmpty(versionPin)) {
			if (StringUtils.isEmpty(versionPin)) {
				versionPin = cd.getVersionSchema();
			}
			if (null == vcsRepoUuid && cd.getType() == ComponentType.COMPONENT) {
				vcsRepoUuid = cd.getVcs();
			}
			if (null == vcsBranch && cd.getType() == ComponentType.COMPONENT) {
				vcsBranch = name;
			}
		}
		
		Branch b = new Branch();
		BranchData bd = BranchData.branchDataFactory(name, cd.getUuid(), cd.getOrg(),
				StatusEnum.ACTIVE, type, vcsRepoUuid, vcsBranch, versionPin, marketingVersionPin);
		
		Map<String,Object> recordData = Utils.dataToRecord(bd);
		return saveBranch(b, recordData, wu);
	}
	
	public Branch createBranch (String name, UUID component, BranchType type, 
			UUID vcsRepoUuid, String vcsBranch, String versionPin, String marketingVersionPin, WhoUpdated wu) throws RelizaException {
			ComponentData cd = getComponentService.getComponentData(component).get();
			return createBranch(name, cd, type, vcsRepoUuid, vcsBranch, versionPin, marketingVersionPin, wu);
	}
	
	public Branch createBranch (String name, UUID component, BranchType type,
					WhoUpdated wu) throws RelizaException {
		return createBranch(name, component, type, null, null, null, null, wu);
	}
	
	@Transactional
	private Branch saveBranch (Branch b, Map<String,Object> recordData, WhoUpdated wu) throws RelizaException {
		// let's add some validation here
		// per schema version 0 we require that schema version 0 has name and project
		if (null == recordData || recordData.isEmpty() || 
				!recordData.containsKey(CommonVariables.NAME_FIELD) ||
				!recordData.containsKey(CommonVariables.COMPONENT_FIELD)) {
			throw new RelizaException("Branch must have name and project in record data");
		}

		// if branch has version validate that 
		// add audit record for any updates
		Optional<Branch> existingRecord = getBranch(b.getUuid());
		if (existingRecord.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.BRANCHES, b);
			b.setRevision(b.getRevision() + 1);
			b.setLastUpdatedDate(ZonedDateTime.now());
		}
		b.setRecordData(recordData);
		b = (Branch) WhoUpdated.injectWhoUpdatedData(b, wu);
		return repository.save(b);
	}

	@Transactional
	public BranchData updateBranch (BranchDto branchDto, WhoUpdated wu) throws RelizaException {
		// TODO: find a way to prevent circular links
		BranchData retBd = null;
		Optional<Branch> bOpt = getBranch(branchDto.getUuid());
		if (bOpt.isPresent()) {
			Branch b = bOpt.get();
			BranchData bd = BranchData.branchDataFromDbRecord(b);
			if (StringUtils.isNotEmpty(branchDto.getName())) {
				bd.setName(branchDto.getName());
			}
			if (null != branchDto.getVcs()) {
				bd.setVcs(branchDto.getVcs());
			}
			if (null != branchDto.getVcsBranch()) {
				bd.setVcsBranch(branchDto.getVcsBranch());
			}
			if (null != branchDto.getVersionSchema()) {
				bd.setVersionSchema(branchDto.getVersionSchema());
			}
			if (null != branchDto.getMarketingVersionSchema()) {
				bd.setMarketingVersionSchema(branchDto.getMarketingVersionSchema());
			}
			if (null != branchDto.getMetadata()) {
				bd.setMetadata(branchDto.getMetadata());
			}
			if (null != branchDto.getAutoIntegrate()) {
				bd.setAutoIntegrate(branchDto.getAutoIntegrate());
			}
			if (null != branchDto.getDependencies()) {
				bd.setDependencies(branchDto.getDependencies());
			}
			if(null !=  branchDto.getPullRequestData()){
				bd.setPullRequestData(branchDto.getPullRequestData());
			}
			// don't convert from base to regular, only allow make base functionality
			if (BranchType.BASE == branchDto.getType()) {
				// previous base must become regular here
				bd.setType(BranchType.BASE);
				Branch oldBaseBranch = getBaseBranchOfComponent(bd.getComponent()).get();
				BranchData oldBaseBranchData = BranchData.branchDataFromDbRecord(oldBaseBranch);
				oldBaseBranchData.setType(BranchType.REGULAR);
				Map<String,Object> recordDataOld = Utils.dataToRecord(oldBaseBranchData);
				saveBranch(oldBaseBranch, recordDataOld, wu);
			} else if (null != bd.getType() && BranchType.BASE != bd.getType()) {
				bd.setType(branchDto.getType());
			}
			Map<String,Object> recordData = Utils.dataToRecord(bd);
			b = saveBranch(b, recordData, wu);
			retBd = BranchData.branchDataFromDbRecord(b);
		}
		return retBd;
	}
	
	public List<BranchData> moveBranchesOfComponentToNewOrg(UUID projectUuid, UUID orgUuid,
		WhoUpdated wu) throws RelizaException {
		// locate branches first
		List<Branch> brList = listBranchesOfComponent(projectUuid, null);
		List<BranchData> retList = new LinkedList<>();
		
		// now for each branch, set it to new org
		for (Branch b : brList) {
			BranchData bd = BranchData.branchDataFromDbRecord(b);
			bd.setOrg(orgUuid);
			// save
			saveBranch(b, Utils.dataToRecord(bd), wu);
			retList.add(bd);
		}
		return retList;
	}

	public BranchData addChildComponentsToBranch(BranchData branchData,
			List<ChildComponent> childComponents, WhoUpdated wu) throws RelizaException {
		// merge child projects - if a new one is present, add it, if we have in parameter the one which already existed, substitute with the new one
		
		// prep work - construct by project (without branches) and by branch set of existing dependencies
		List<ChildComponent> existingDeps = branchData.getDependencies();
		Map<UUID, ChildComponent> existingPerComponentCPs = new LinkedHashMap<>();
		Map<UUID, ChildComponent> existingPerBranchCPs = new LinkedHashMap<>();
		
		existingDeps.forEach(ed -> {
			if (null != ed.getBranch()) {
				existingPerBranchCPs.put(ed.getBranch(), ed);
			} else {
				existingPerComponentCPs.put(ed.getUuid(), ed);
			}
		});
		
		// now same processing over supllied deps
		Map<UUID, ChildComponent> suppliedPerComponentCPs = new LinkedHashMap<>();
		Map<UUID, ChildComponent> suppliedPerBranchCPs = new LinkedHashMap<>();
		childComponents.forEach(cp -> {
			if (null != cp.getBranch()) {
				suppliedPerBranchCPs.put(cp.getBranch(), cp);
			} else {
				suppliedPerComponentCPs.put(cp.getUuid(), cp);
			}
		});
		
		// combine
		existingPerComponentCPs.putAll(suppliedPerComponentCPs);
		existingPerBranchCPs.putAll(suppliedPerBranchCPs);
		
		// unwind
		List<ChildComponent> newDeps = new LinkedList<>(existingPerBranchCPs.values());
		newDeps.addAll(existingPerComponentCPs.values());
		
		
		// save
		BranchDto branchDto = BranchDto.builder()
								.uuid(branchData.getUuid())
								.dependencies(newDeps)
								.build();
		
		return updateBranch(branchDto, wu);
	}

	public Boolean archiveBranch(UUID branchUuid, WhoUpdated wu) throws RelizaException {
		Boolean archived = false;
		Optional<Branch> obr = getBranch(branchUuid);
		if (obr.isPresent()) {
			BranchData bd = BranchData.branchDataFromDbRecord(obr.get());
			if (BranchData.BranchType.BASE == bd.getType()) {
				throw new RelizaException("Cannot archive base branch");
			}
			bd.setStatus(StatusEnum.ARCHIVED);
			Map<String,Object> recordData = Utils.dataToRecord(bd);
			saveBranch(obr.get(), recordData, wu);
		}
		return archived;
	}

	public Branch cloneBranch (BranchData originalBranch, String name, String versionSchema, 
		BranchType bt, WhoUpdated wu) throws RelizaException {
			return cloneBranch(originalBranch, name, versionSchema, bt, wu, null);
	}
	
	public Branch cloneBranch (BranchData originalBranch, String name, String versionSchema, 
		BranchType bt, WhoUpdated wu, ChildComponent dependecyOverride ) throws RelizaException {
		
		Branch b = new Branch();
		if (null == bt) {
			if (originalBranch.getType() == BranchType.BASE) {
				bt = BranchData.resolveBranchTypeByName(originalBranch.getName());
			} else {
				bt = originalBranch.getType();
			}
		}
		BranchData bd = BranchData.branchDataFactory(name, originalBranch.getComponent(), 
				originalBranch.getOrg(), StatusEnum.ACTIVE, bt, originalBranch.getVcs(), 
				null, null, null);
		bd.setAutoIntegrate(originalBranch.getAutoIntegrate());
		bd.setCreatedType(wu.getCreatedType());
		
		if (StringUtils.isNotEmpty(versionSchema)) {
			bd.setVersionSchema(versionSchema);
		} else {
			bd.setVersionSchema(originalBranch.getVersionSchema());
		}

		if(dependecyOverride == null){
			bd.setDependencies(new LinkedList<>(originalBranch.getDependencies()));
		}else{
			List<ChildComponent> dependecies = originalBranch.getDependencies().stream().map(dep -> {
				if(dep.getUuid().equals(dependecyOverride.getUuid()))
					return dependecyOverride;
				return dep;
			}).collect(Collectors.toList());
			bd.setDependencies(new LinkedList<>(dependecies));
		}
		
		Map<String,Object> recordData = Utils.dataToRecord(bd);
		return saveBranch(b, recordData, wu);
	}

	public BranchData setPRDataOnBranch(BranchData branchData, PullRequestData pullRequestData, WhoUpdated wu) throws RelizaException {
		var branchPRData = branchData.getPullRequestData();
		if(branchPRData.containsKey(pullRequestData.getNumber())){
			branchPRData.remove(pullRequestData.getNumber());
		}
		branchPRData.put(pullRequestData.getNumber(), pullRequestData);
		
		if(pullRequestData.getState().equals(PullRequestState.OPEN)){
			//create new fs for this branch
			ChildComponent dependecyOverride = ChildComponent.builder().branch(branchData.getUuid())
			.uuid(branchData.getComponent())
			.status(StatusEnum.REQUIRED)
			.build();
			List<BranchData> existingFSs = findBranchDataByChildComponentBranch(branchData.getOrg(), branchData.getComponent(), branchData.getUuid());
			final Map<String, BranchData> existingFSsNameMap = existingFSs.stream().collect(Collectors.toMap(BranchData::getName, Function.identity()));
			List<BranchData> targetFSs = findBranchDataByChildComponentBranch(branchData.getOrg(), branchData.getComponent(), pullRequestData.getTargetBranch())
			.stream()
			.filter(fs -> fs.getAutoIntegrate().equals(AutoIntegrateState.ENABLED) && !fs.getType().equals(BranchType.PULL_REQUEST))
			.collect(Collectors.toList());
		
			if(targetFSs.size()> 0){
				targetFSs.stream()
					.forEach(
						handlingConsumerWrapper(fs -> {
							String name = fs.getName().replaceAll(" ", "_") + "-" +pullRequestData.getTitle().replaceAll(" ", "_");
							BranchData existingFSwithSameName = existingFSsNameMap.get(name);
							if(existingFSwithSameName == null || !existingFSwithSameName.getComponent().equals(fs.getComponent()))
								cloneBranch(fs, name, VersionType.FEATURE_BRANCH.getSchema(), BranchType.PULL_REQUEST, wu, dependecyOverride);
						},RelizaException.class)
					);
			}
		
			
					
		}else if(pullRequestData.getState().equals(PullRequestState.CLOSED)){
			//disable autointegrate
			List<BranchData> existingFSs = findBranchDataByChildComponentBranch(branchData.getOrg(), branchData.getComponent(), branchData.getUuid());
			existingFSs.stream().forEach(
				handlingConsumerWrapper(
				fs -> {
				BranchDto branchDto = BranchDto.builder()
								.uuid(fs.getUuid())
								.autoIntegrate(AutoIntegrateState.DISABLED)
								.build();
				updateBranch(branchDto, wu);
			}, RelizaException.class));
		}
		// save
		BranchDto branchDto = BranchDto.builder()
								.uuid(branchData.getUuid())
								.pullRequestData(branchPRData)
								.build();
		
		return updateBranch(branchDto, wu);
	}

	public void saveAll(List<Branch> branches){
		repository.saveAll(branches);
	}
	
	public BranchData getBranchDataFromBranchString(String branchStr, UUID projectId, WhoUpdated wu){
		UUID branchUuid = null;
		Optional<Branch> ob = Optional.empty();
		// normalize branch string
		branchStr = Utils.cleanBranch(branchStr);
		try {
			branchUuid = UUID.fromString(branchStr);
			ob = getBranch(branchUuid);
		} catch (IllegalArgumentException e) {
			// parse branch from name
			try {
				ob = findBranchByName(projectId, branchStr, true, wu);
			} catch (RelizaException re) {
				throw new AccessDeniedException(re.getMessage());
			}
			branchUuid = ob.get().getUuid();
		}
		if (ob.isEmpty()) {
			throw new IllegalStateException("submitted branch must exist");
		}
		BranchData bd = BranchData.branchDataFromDbRecord(ob.get());
		if (!bd.getComponent().equals(projectId)) {
			throw new AccessDeniedException("We do not know this branch in this project");
		}
		return bd;
	}
	
}
