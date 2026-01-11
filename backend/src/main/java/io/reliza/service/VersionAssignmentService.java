/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.AssignmentTypeEnum;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.VersionAssignmentRepository;
import io.reliza.versioning.Version;
import io.reliza.versioning.VersionApi.ActionEnum;
import io.reliza.versioning.VersionUtils;

@Service
public class VersionAssignmentService {

	@Autowired
    private GetComponentService getComponentService;
	
	@Autowired
    private BranchService branchService;
	
	@Autowired
    private SharedReleaseService sharedReleaseService;
	
	private static final Logger log = LoggerFactory.getLogger(VersionAssignmentService.class);
			
	private final VersionAssignmentRepository repository;
	
	VersionAssignmentService(VersionAssignmentRepository repository) {
	    this.repository = repository;
	}

	public List<VersionAssignment> listVersionAssignmentsByOrg (UUID orgUuid) {
		return repository.findByOrg(orgUuid);
	}
	
	public List<VersionAssignment> getVersionAssignments (Iterable<UUID> uuids) {
		return (List<VersionAssignment>) repository.findAllById(uuids);
	}

	public Optional<VersionAssignment> getVersionAssignment (UUID projectUuid, String version) {
		return getVersionAssignment(projectUuid, version, VersionTypeEnum.DEV);
	}
	
	public Optional<VersionAssignment> getVersionAssignment (UUID component, String version, VersionTypeEnum versionType) {
		return repository.findVersionAssignmentByComponentAndVersion(component, version, versionType.name());
	}
	public Optional<VersionAssignment> getLatestVersionAssignmentOfBranch (UUID branchUuid, int limit){
		return getLatestVersionAssignmentOfBranch(branchUuid, limit, VersionTypeEnum.DEV);
	}
	public Optional<VersionAssignment> getLatestVersionAssignmentOfBranch (UUID branchUuid, int limit, VersionTypeEnum versionType) {
		Optional<VersionAssignment> ova = Optional.empty();
		List<VersionAssignment> vas = repository.findLatestVersionAssignmentsWithLimit(branchUuid, versionType.name() ,limit);
		if (!vas.isEmpty()) {
			Collections.sort(vas);
			ova = Optional.of(vas.get(0));
		}
		return ova;
	}
	
	private Optional<VersionAssignment> getLatestVersionAssignmentOfBranch (UUID branchUuid, int limit, String componentSchema, String branchSchema, VersionTypeEnum versionType) {
		Optional<VersionAssignment> ova = Optional.empty();
		List<VersionAssignment> vas = new LinkedList<>();
		if (StringUtils.isEmpty(branchSchema) && StringUtils.isEmpty(componentSchema)) {
			ova = getLatestVersionAssignmentOfBranch(branchUuid, limit, versionType);
		} else if (StringUtils.isNotEmpty(branchSchema)) {
			vas = repository.findLatestVersionAssignmentsWithBranchSchemaAndLimitByBranch(branchUuid, limit, branchSchema, versionType.name());
		} 
		
		if (vas.isEmpty() && StringUtils.isNotEmpty(componentSchema)) {
			vas = repository.findLatestVersionAssignmentsWithComponentSchemaAndLimitByBranch(branchUuid, limit, componentSchema, versionType.name());
		}
		if (!vas.isEmpty()) {
			Collections.sort(vas);
			ova = Optional.of(vas.get(0));
		}
		return ova;
	}
	
	private Optional<VersionAssignment> getLatestVersionAssignmentOfProject (UUID branchUuid, UUID component, int limit, String schema, VersionTypeEnum versionType) {
		Optional<VersionAssignment> ova = Optional.empty();
		List<VersionAssignment> vas = repository.findLatestVersionAssignmentsWithSchemaAndLimitByComponent(component, limit, schema, versionType.name());
		if (!vas.isEmpty()) {
			Collections.sort(vas);
			ova = Optional.of(vas.get(0));
		}
		return ova;
	}
	
	// Note that this method doesn't check for duplicates and therefore may fail
	public Optional<VersionAssignment> createNewVersionAssignment (UUID branchUuid, String version, UUID releaseUuid) {
		return createNewVersionAssignment(branchUuid, version, releaseUuid, VersionTypeEnum.DEV);
	}
	public Optional<VersionAssignment> createNewVersionAssignment (UUID branchUuid, String version, UUID releaseUuid, VersionTypeEnum versionType) {
		Optional<VersionAssignment> retVersion = Optional.empty();
		VersionAssignment va = new VersionAssignment();
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		if (obd.isPresent()) {
			ComponentData pd = getComponentService.getComponentData(obd.get().getComponent()).get();
			String projectSchema = pd.getVersionSchema();
			String branchSchema = obd.get().getVersionSchema();
			if(versionType.equals(VersionTypeEnum.MARKETING)){
				projectSchema = StringUtils.isEmpty(pd.getMarketingVersionSchema()) ? projectSchema : pd.getMarketingVersionSchema();
				branchSchema = StringUtils.isEmpty(obd.get().getMarketingVersionSchema()) ? pd.getMarketingVersionSchema() : obd.get().getMarketingVersionSchema();
			}
			va.setBranch(branchUuid);
			va.setComponent(obd.get().getComponent());
			va.setVersion(version);
			va.setOrg(pd.getOrg());
			va.setVersionType(versionType);
			va.setAssignmentType(AssignmentTypeEnum.RESERVED);
			if (StringUtils.isNotEmpty(projectSchema)) {
				va.setVersionSchema(projectSchema);
			}
			if (StringUtils.isNotEmpty(branchSchema)) {
				va.setBranchSchema(branchSchema);
			}
			if (null != releaseUuid) {
				va.setRelease(releaseUuid);
				va.setAssignmentType(AssignmentTypeEnum.ASSIGNED);
			}
			retVersion = Optional.of(repository.save(va));
		}		
		return retVersion;
	}
	
	// TODO: add support for who updated
	@Transactional
	public Optional<VersionAssignment> getSetNewVersion (UUID branchUuid, ActionEnum bumpAction, String modifier, String metadata, VersionTypeEnum versionType) {
		Optional<VersionAssignment> retVersion = Optional.empty();
		// retrieve branch and project to get their schemas
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		if(obd.isEmpty())
			return retVersion;
		
		BranchData bd = obd.get();
		ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();
		String projectSchema = pd.getVersionSchema();
		String branchSchema = bd.getVersionSchema();
		// we require both project versioning schema and branch versioning schema to exist to generate a new version
		if (StringUtils.isEmpty(projectSchema) && StringUtils.isEmpty(branchSchema)) {
			log.warn("Cannot generate version, since versioning schemas not set, branch uuid = " + branchUuid);	
			return retVersion;
		}
			
		if (null == bumpAction) {
			bumpAction = ActionEnum.BUMP;
		}	
		
		VersionAssignment va = new VersionAssignment();
		
		Optional<VersionAssignment> openOva = getNextVersion(branchUuid, versionType);

		if(openOva.isPresent()){
			va = openOva.get();
		} else { // Normal flow of RESERVED version
			String followedVersion = null;
			var followedComponent = obd.get().getFollowedVersionComponent();
			if (followedComponent.isPresent()) {
				if (null != followedComponent.get().getRelease()) {
					var rd = sharedReleaseService.getReleaseData(followedComponent.get().getRelease()).get();
					followedVersion = rd.getVersion();
				} else {
					var ord = sharedReleaseService.getReleaseDataOfBranch(bd.getOrg(), followedComponent.get().getBranch(), ReleaseLifecycle.ASSEMBLED);
					if (ord.isPresent()) followedVersion = ord.get().getVersion();
				}
			}
			String versionString = getVersionBumpOnLatestForBranch(obd.get(), pd, bumpAction, modifier, metadata, followedVersion, versionType);
			va.setVersion(versionString);
		}

		// try to save
		va.setBranch(branchUuid);
		va.setComponent(bd.getComponent());
		va.setAssignmentType(AssignmentTypeEnum.RESERVED);
		va.setVersionSchema(projectSchema);
		va.setBranchSchema(branchSchema);
		va.setOrg(bd.getOrg());
		va.setVersionType(VersionTypeEnum.DEV);
		retVersion = Optional.of(repository.save(va));
		
		return retVersion;
	}

	private String getVersionBumpOnLatestForBranch(BranchData bd, ComponentData pd, ActionEnum bumpAction, String modifier, String metadata, String followedVersion, VersionTypeEnum versionType){
		String projectSchema = pd.getVersionSchema();
		String branchSchema = bd.getVersionSchema();
		if(versionType.equals(VersionTypeEnum.MARKETING)){
			projectSchema = StringUtils.isEmpty(pd.getMarketingVersionSchema()) ? projectSchema : pd.getMarketingVersionSchema();
			branchSchema = StringUtils.isEmpty(bd.getMarketingVersionSchema()) ? pd.getMarketingVersionSchema() : bd.getMarketingVersionSchema();
		}
		// obtain the latest version assignment for the branch
		Optional<VersionAssignment> latestOva = getLatestVersionAssignmentOfBranch(bd.getUuid(), 10, projectSchema, branchSchema, versionType);

		String versionString = constructVersionAssignmentStringSub(latestOva, projectSchema, branchSchema, bd, bumpAction, modifier, metadata, followedVersion);
		
		int retries = 3;
		while (retries > 0) {
			Optional<VersionAssignment> vaCheck = getVersionAssignment(pd.getUuid(), versionString, versionType);
			if (vaCheck.isPresent()) {
				versionString = getVersionStringFromVaCheckRetry(latestOva, vaCheck, projectSchema, branchSchema, bd, 
					bumpAction, modifier, metadata, followedVersion, versionType);
			} else {
				break;
			}
			latestOva = getLatestVersionAssignmentOfBranch(bd.getUuid(), 10, projectSchema, branchSchema, versionType);
			retries--;
		}
		return versionString;
	}

	private String getVersionStringFromVaCheckRetry (Optional<VersionAssignment> latestOva, Optional<VersionAssignment> vaCheck, String projectSchema, String branchSchema, 
			BranchData bd, ActionEnum bumpAction, String modifier, String metadata, String followedVersion, VersionTypeEnum versionType) {
			String versionString;
			if (StringUtils.isNotEmpty(followedVersion)) {
				versionString = constructVersionAssignmentStringSub(vaCheck, projectSchema, branchSchema, bd, bumpAction, modifier, metadata, followedVersion);
			} else {
				// obtain the latest version assignment for the projects
				latestOva = getLatestVersionAssignmentOfProject(bd.getUuid(), bd.getComponent(), 10, projectSchema, versionType);
				// if not found, try branch schema also
				if (latestOva.isEmpty()) {
					latestOva = getLatestVersionAssignmentOfProject(bd.getUuid(), bd.getComponent(), 10, branchSchema, versionType);
				}
				versionString = constructVersionAssignmentStringSub(latestOva, projectSchema, branchSchema, bd, bumpAction, modifier, metadata, followedVersion);
			}
			return versionString;
	}
	
	private String constructVersionAssignmentStringSub (Optional<VersionAssignment> latestOva, String projectSchema, String branchSchema, BranchData bd,
			ActionEnum bumpAction, String modifier, String metadata, String followedVersion) {
		Version v = null;
		String namespace = computeNamespaceForBranch(branchSchema, bd);
		if (StringUtils.isNotEmpty(followedVersion) && latestOva.isPresent() && VersionUtils.isVersionMatchingSchemaAndPin(branchSchema, followedVersion, latestOva.get().getVersion())) {
			log.debug("followedVersion = " + followedVersion + ", latestOvaVersion = " + latestOva.get().getVersion());
			v = Version.getVersionFromPinAndOldVersion(branchSchema, followedVersion, latestOva.get().getVersion(), bumpAction, namespace);
		} else if (StringUtils.isNotEmpty(followedVersion)) {
			v = Version.getVersionFromPin(branchSchema, followedVersion, namespace);
		} else if (latestOva.isPresent() && VersionUtils.isVersionMatchingSchemaAndPin(projectSchema, branchSchema, latestOva.get().getVersion())) {
			// use this latest version assignment, schema and pin to generate new one
			v = Version.getVersionFromPinAndOldVersion(projectSchema, branchSchema, latestOva.get().getVersion(), bumpAction, namespace);
		} else if (VersionUtils.isPinMatchingSchema(projectSchema, branchSchema)){
			// try to generate new version based on schema and pin
			v = Version.getVersionFromPin(projectSchema, branchSchema, namespace);
		} else if (latestOva.isPresent() && VersionUtils.isVersionMatchingSchema(branchSchema, latestOva.get().getVersion())) {
			// prev version is matching branch
			// generate version based on this branch only and prev version
			v = Version.getVersionFromPinAndOldVersion(branchSchema, branchSchema, latestOva.get().getVersion(), bumpAction, namespace);
		} else {
			// generate version solely based on this branch pin
			v = Version.getVersion(branchSchema);
			v.setBranch(bd.getName());
		}
		if (StringUtils.isNotEmpty(modifier) && !branchSchema.toLowerCase().contains("calvermodifier")) {
			v.setModifier(modifier);
		}
		v.setMetadata(metadata);
		return v.constructVersionString();
	}

	private String computeNamespaceForBranch(String branchSchema, BranchData bd) {
		if (bd.getType() == BranchType.BASE) {
			return null;
		}
		if (StringUtils.isEmpty(branchSchema)) {
			return null;
		}
		String schemaLower = branchSchema.toLowerCase();
		if (schemaLower.equals("semver") || schemaLower.equals("major.minor.patch") || schemaLower.equals("major.minor.micro")) {
			return bd.getName().toLowerCase().replaceAll("[^a-z0-9]", "_");
		}
		return null;
	}
	
	public VersionAssignment saveVersionAssignment (VersionAssignment va) {
		return repository.save(va);
	}
	
	public void checkAndUpdateVersionPinOnBranch(ComponentData pd, BranchData bd, String setVersionPin,WhoUpdated wu) throws RelizaException {
		// if version pin present and doesn't match the one currently on branch, update branch with new one
		if (StringUtils.isNotEmpty(setVersionPin) && !setVersionPin.equalsIgnoreCase(bd.getVersionSchema())) {
			// validate first
			if (VersionUtils.isPinMatchingSchema(pd.getVersionSchema(), setVersionPin)) {
				BranchDto branchDto = BranchDto.builder()
										.uuid(bd.getUuid())
										.versionSchema(setVersionPin)
										.build();
				bd = branchService.updateBranch(branchDto, wu);
			} else {
				// return our custom error code to user
				throw new RelizaException("Supplied pin does not match project schema");
			}
		}
		
		if (StringUtils.isEmpty(bd.getVersionSchema()) ||
			StringUtils.isEmpty(pd.getVersionSchema())) {
			throw new RelizaException("Versioning schema not set for project or branch or both");
		}
	}

	/**
	 * Sets next version on the branch by creating a VersionAssignment of AssignmentTypeEnum.OPEN, which gets consumed on priority by next release in line
	 * @param branchUuid
	 * @param versionString
	 */
	@Transactional
	public boolean setNextVesion(UUID branchUuid, String versionString){
		return setNextVesion(branchUuid, versionString, VersionTypeEnum.DEV);
	}
	@Transactional
	public boolean setNextVesion(UUID branchUuid, String versionString, VersionTypeEnum versionType){
		
		BranchData bd = branchService.getBranchData(branchUuid).get();
		ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();


		Version nextVersion = Version.getVersion(versionString, bd.getVersionSchema());
		

		Optional<VersionAssignment> latestOva = getLatestVersionAssignmentOfBranch(branchUuid, 10);
		if(latestOva.isPresent()){
			Version latestVersion =  Version.getVersion(latestOva.get().getVersion(), bd.getVersionSchema());
			if(nextVersion.compareTo(latestVersion) >= 0)
				throw new RuntimeException("Next Version must be greater than the latest version");
		}

		VersionAssignment va = new VersionAssignment();


		Optional<VersionAssignment> currentNextOva = getNextVersion(branchUuid, versionType);
		
		if(currentNextOva.isPresent()){
			Version currentNextVersion =  Version.getVersion(currentNextOva.get().getVersion(), bd.getVersionSchema());
			if(nextVersion.compareTo(currentNextVersion) >= 0)
				throw new RuntimeException("Next Version must be greater than the current next version");
			va = currentNextOva.get();
		}

		// Create OPEN Va
		
		va.setBranch(branchUuid);
		va.setVersion(versionString);
		va.setComponent(bd.getComponent());
		va.setAssignmentType(AssignmentTypeEnum.OPEN);
		va.setVersionSchema(pd.getVersionSchema());
		va.setBranchSchema(bd.getVersionSchema());
		va.setOrg(bd.getOrg());
		va.setVersionType(versionType);
		repository.save(va);
		return true;
	}

	@Transactional
	private Optional<VersionAssignment> getNextVersion(UUID branchUuid, VersionTypeEnum versionType){
		return repository.findOPENVersionAssignmentByBranch(branchUuid, versionType.name());
	}

	@Transactional
	public String getCurrentNextVersion(UUID branchUuid, VersionTypeEnum versionType){
		String currentNextVersion = "";
		log.info("versionType: {}", versionType);
		Optional<VersionAssignment> openOva = repository.findOPENVersionAssignmentByBranch(branchUuid, versionType.name());

		// if OPEN version is found
		if(openOva.isPresent())
		{
			currentNextVersion = openOva.get().getVersion();
			return currentNextVersion;
		}
		
		BranchData bd = branchService.getBranchData(branchUuid).get();
		ComponentData cd = getComponentService.getComponentData(bd.getComponent()).get();
		
		String followedVersion = null;
		var followedComponent = bd.getFollowedVersionComponent();
		if (followedComponent.isPresent()) {
			// we just pick latest release of this same branch and base on that
			var ord = sharedReleaseService.getReleaseDataOfBranch(bd.getOrg(), branchUuid, ReleaseLifecycle.CANCELLED);
			if (ord.isPresent()) followedVersion = ord.get().getVersion();
		}
		return getVersionBumpOnLatestForBranch(bd, cd, ActionEnum.BUMP, null, null, followedVersion, versionType);
	}


	public void saveAll(List<VersionAssignment> versionAssignments){
		repository.saveAll(versionAssignments);
	}

	public record GetNewVersionDto(
		UUID project,
		String branch,
		String modifier,
		String action,
		String metadata,
		String versionSchema,
		ReleaseLifecycle lifecycle,
		Boolean onlyVersion,
		SceDto sourceCodeEntry,
		List<SceDto> commits,
		VersionTypeEnum versionType
	) {}
}
