/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.dto.SyncDtrackStatusResponseDto;
import io.reliza.model.RelizaObject;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.RebomService;
import io.reliza.service.RebomService.EnrichmentTriggerResult;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class ArtifactDataFetcher {
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private ArtifactService artifactService;
	
	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private SharedArtifactService sharedArtifactService;
	
	@Autowired
	private RebomService rebomService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "artifact")
	public ArtifactData getArtifact(@InputArgument("artifactUuid") String artifactUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID artifactUuid = UUID.fromString(artifactUuidStr);
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		var releases = sharedReleaseService.gatherReleasesForArtifact(artifactUuid, oad.get().getOrg());
		var components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
		authorizationService.isUserAuthorizedForAnyObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, components, List.of(ro), CallType.READ);
		return oad.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "artifactVersionHistory")
	public List<ArtifactData> getArtifactVersionHistory(@InputArgument("artifactUuid") String artifactUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID artifactUuid = UUID.fromString(artifactUuidStr);
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		var releases = sharedReleaseService.gatherReleasesForArtifact(artifactUuid, oad.get().getOrg());
		var components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
		authorizationService.isUserAuthorizedForAnyObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, components, List.of(ro), CallType.READ);
		
		ArtifactData currentArtifact = oad.get();
		List<ArtifactData> versionHistory = new LinkedList<>();
		
		// Convert version snapshots back to ArtifactData objects
		if (currentArtifact.getPreviousVersions() != null) {
			// Return in reverse chronological order (newest first)
			List<ArtifactData.ArtifactVersionSnapshot> snapshots = currentArtifact.getPreviousVersions();
			for (int i = snapshots.size() - 1; i >= 0; i--) {
				ArtifactData.ArtifactVersionSnapshot snapshot = snapshots.get(i);
				ArtifactData historicalArtifact = ArtifactData.ArtifactVersionSnapshot.fromSnapshot(snapshot);
				versionHistory.add(historicalArtifact);
			}
		}
		
		return versionHistory;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "artifactTypes")
	public Set<String> getArtifactTypes() {
		var valList = Arrays.asList(ArtifactType.values());
		return valList.stream().map(v -> v.toString()).collect(Collectors.toSet());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "artifactBomLatestVersion")
	public String getArtifactBomLatestVersion(
			@InputArgument("artUuid") String artifactUuidStr)
			throws RelizaException
	{
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		UUID artifactUuid = UUID.fromString(artifactUuidStr);
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		var releases = sharedReleaseService.gatherReleasesForArtifact(artifactUuid, oad.get().getOrg());
		var components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
		authorizationService.isUserAuthorizedForAnyObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, components, List.of(ro), CallType.READ);
		return artifactService.getArtifactBomLatestVersion(oad.get().getInternalBom().id(), oad.get().getOrg());
	}
	
	@DgsData(parentType = "Artifact", field = "artifactDetails")
	public List<ArtifactData> artifactsOfArtifact(DgsDataFetchingEnvironment dfe)  {
		ArtifactData ad = dfe.getSource();
		List<ArtifactData> artList = new LinkedList<>();
		if (null != ad.getArtifacts()) {
			for (UUID artUuid : ad.getArtifacts()) {
				artList.add(artifactService
						.getArtifactData(artUuid)
						.get());
			}
		}
		return artList;
	}

	// @Transactional
	// @PreAuthorize("isAuthenticated()")
	// @DgsData(parentType = "Mutation", field = "updateArtifactManual")
	// public void updateArtifactManual(DgsDataFetchingEnvironment dfe) throws Exception {
	// 	JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
	// 	var oud = userService.getUserDataByAuth(auth);


	// 	Map<String, Object> variables = dfe.getVariables();
	// 	Map<String, Object> artifactInput = (Map<String, Object>) variables.get("artifactInput");
	// 	String artifactUuidStr = (String) variables.get("artifactUuid");
	// 	UUID artifactUuid = UUID.fromString(artifactUuidStr);

	// 	Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(UUID.fromString((String)artifactInput.get("release")));
	// 	ReleaseData rd = ord.get();
	// 	RelizaObject ro = ord.isPresent() ? ord.get() : null;
	// 	UUID orgUuid = ord.isPresent() ? ord.get().getOrg() : null;

	// 	var oad = artifactService.getArtifactData(artifactUuid);

	// 	WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

	// 	authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);


	// 	ComponentData cd = getComponentService.getComponentData(rd.getComponent()).orElseThrow();
	// 	OrganizationData od = organizationService.getOrganizationData(rd.getOrg()).orElseThrow();
		
	// 	Map<String, Object> artifact = (Map<String, Object>) artifactInput.get("artifact");
	// 	MultipartFile multipartFile = null;
	// 	if (artifact != null && artifact.containsKey("file")) {
	// 		multipartFile = (MultipartFile) artifact.get("file");
			
	// 	}

	// 	if (artifact != null && multipartFile != null) {
	// 		artifact.remove("file");
	// 	}

	// 	ArtifactDto artDto = Utils.OM.convertValue(artifact, ArtifactDto.class);
	// 	List<String> validationErrors =  new ArrayList<>();

	// 	if(null == artDto.getType()){
	// 		validationErrors.add("Artifact Type is required.");
	// 	}

	// 	if(null == artDto.getDisplayIdentifier()){
	// 		validationErrors.add("Display Identifier is required.");
	// 	}
			
	// 	if(ArtifactType.BOM.equals(artDto.getType())
	// 		|| ArtifactType.VEX.equals(artDto.getType())
	// 		|| ArtifactType.VDR.equals(artDto.getType())
	// 		|| ArtifactType.ATTESTATION.equals(artDto.getType())
	// 	){
	// 		if(null == artDto.getBomFormat())
	// 		{
	// 			validationErrors.add("Bom Format must be specified");
	// 		}
	// 	}
		
	// 	if(StoredIn.EXTERNALLY.equals(artDto.getStoredIn())
	// 		&& artDto.getDownloadLinks().isEmpty())
	// 	{
	// 		validationErrors.add("External Artifacts must specify atleast one Download Link");
	// 	}

	// 	if(!validationErrors.isEmpty()){
	// 		throw new RelizaException(validationErrors.stream().collect(Collectors.joining(", ")));
	// 	}

	// 	if (multipartFile != null) {
	// 		String hash = null != artDto.getDigests() ? artDto.getDigests().stream().findFirst().orElse(null) : null;
	// 		artifactService.updateArtifactManual(oad.get(), artDto, orgUuid, multipartFile.getResource(),  new RebomOptions(cd.getName(), od.getName(), rd.getVersion(), oad.get().getInternalBom().belongsTo(), hash, artDto.getStripBom()), wu);
	// 	}


	// 	// lessen the validations
	// 	// copy the artifacy objecy
	// 	// 
	// }
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "syncDtrackStatus")
	public SyncDtrackStatusResponseDto syncDtrackStatus(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ADMIN);
		
		log.info("User {} initiated DTrack status sync for organization {}", oud.get().getUuid(), orgUuid);
		
		return artifactService.syncDtrackStatus(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "triggerEnrichment")
	public EnrichmentTriggerResult triggerEnrichment(
			@InputArgument("artifact") UUID artifactUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
		
		if (oad.isEmpty()) {
			return new EnrichmentTriggerResult(false, "Artifact not found", null);
		}
		
		ArtifactData ad = oad.get();
		
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, ad.getOrg(), List.of(ad), CallType.ADMIN);
		
		// Check if artifact has internal BOM
		if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) {
			return new EnrichmentTriggerResult(false, "Artifact does not have an internal BOM", null);
		}
		
		return rebomService.triggerEnrichment(ad.getInternalBom().id(), ad.getOrg());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "refetchDependencyTrackMetrics")
	public boolean refetchDependencyTrackMetrics (
			@InputArgument("artifact") UUID art) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<Artifact> oa = sharedArtifactService.getArtifact(art);
		
		RelizaObject ro = oa.isPresent() ? ArtifactData.dataFromRecord(oa.get()) : null;
		var releases = sharedReleaseService.gatherReleasesForArtifact(art, ro.getOrg());
		var components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
		authorizationService.isUserAuthorizedForAnyObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, components, List.of(ro), CallType.WRITE);

		return artifactService.fetchDependencyTrackDataForArtifact(oa.get());
	}

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateArtifactTags")
	public ArtifactData updateArtifactTags(
			@InputArgument("artifact") UUID artifactUuid,
			DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
		if (oad.isEmpty()) {
			throw new RuntimeException("Artifact not found: " + artifactUuid);
		}
		ArtifactData ad = oad.get();
		var releases = sharedReleaseService.gatherReleasesForArtifact(artifactUuid, ad.getOrg());
		var components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
		authorizationService.isUserAuthorizedForAnyObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, components, List.of(ad), CallType.WRITE);
		
		List<Map<String, Object>> tagsInput = dfe.getArgument("tags");
		List<TagRecord> newTags = tagsInput.stream()
			.map(t -> new TagRecord((String) t.get("key"), (String) t.get("value")))
			.collect(Collectors.toList());
		
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		try {
			artifactService.updateArtifactTags(artifactUuid, newTags, wu);
		} catch (RelizaException e) {
			log.error("Exception on updateArtifactTags", e);
			throw new RuntimeException(e.getMessage());
		}
		return artifactService.getArtifactData(artifactUuid).get();
	}

}