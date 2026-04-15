/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.DownloadLogData.DownloadConfig;
import io.reliza.model.DownloadLogData.DownloadSubjectType;
import io.reliza.model.DownloadLogData.DownloadType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.DownloadLogService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class ArtifactWs {

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private SharedArtifactService sharedArtifactService;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private UserService userService;
    
	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private DownloadLogService downloadLogService;

    @GetMapping("api/manual/v1/artifact/{uuid}/download")
    public Mono<ResponseEntity<byte[]>> downloadArtifact(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @org.springframework.web.bind.annotation.RequestParam(value = "version", required = false) Integer version,
        ServletWebRequest request,
        @AuthenticationPrincipal OAuth2User oAuth2User,
        HttpServletResponse response
    ) throws Exception {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> latestOad = artifactService.getArtifactData(uuid);
        log.debug("latestOad is present? {}", latestOad.isPresent());
        log.debug("latestOad is  {}", latestOad.get());
		RelizaObject ro = latestOad.isPresent() ? latestOad.get() : null;
		var releases = sharedReleaseService.gatherReleasesForArtifact(uuid, ro.getOrg());
		var components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
		authorizationService.isUserAuthorizedForAnyObjectGraphQL(oud.get(), PermissionFunction.ARTIFACT_DOWNLOAD, PermissionScope.COMPONENT, components, List.of(ro), CallType.READ);
		if (response.isCommitted()) return null;
        Optional<ArtifactData> oad = (version == null)
            ? latestOad
            : artifactService.getArtifactDataByVersion(uuid, version);
		if (oad.isEmpty()) {
            throw new RelizaException("Artifact not found; uuid: " + uuid.toString());
        }
        
        WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
        downloadLogService.createDownloadLog(ro.getOrg(), DownloadType.ARTIFACT_DOWNLOAD,
            DownloadSubjectType.ARTIFACT, oad.get().getUuid(), wu,
            DownloadConfig.builder().artifactUuid(uuid).artifactVersion(version).build());
        return sharedArtifactService.downloadArtifact(oad.get());
        
    }
    @GetMapping("api/manual/v1/artifact/{uuid}/rawdownload")
    public Mono<ResponseEntity<byte[]>> downloadRawArtifact(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @org.springframework.web.bind.annotation.RequestParam(value = "version", required = false) Integer version,
        ServletWebRequest request,
        @AuthenticationPrincipal OAuth2User oAuth2User,
        HttpServletResponse response
    ) throws Exception {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> latestOad = artifactService.getArtifactData(uuid);
        log.debug("latestOad is present? {}", latestOad.isPresent());
        log.debug("latestOad is  {}", latestOad.get());
		RelizaObject ro = latestOad.isPresent() ? latestOad.get() : null;
		var releases = sharedReleaseService.gatherReleasesForArtifact(uuid, ro.getOrg());
		var components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
		authorizationService.isUserAuthorizedForAnyObjectGraphQL(oud.get(), PermissionFunction.ARTIFACT_DOWNLOAD, PermissionScope.COMPONENT, components, List.of(ro), CallType.READ);
        if (response.isCommitted()) return null;
		Optional<ArtifactData> oad = (version == null)
            ? latestOad
            : artifactService.getArtifactDataByVersion(uuid, version);		
		if (oad.isEmpty()) {
            throw new RelizaException("Artifact not found; uuid: " + uuid.toString());
        }
        
        WhoUpdated wuRaw = WhoUpdated.getWhoUpdated(oud.get());
        downloadLogService.createDownloadLog(ro.getOrg(), DownloadType.RAW_ARTIFACT_DOWNLOAD,
            DownloadSubjectType.ARTIFACT, oad.get().getUuid(), wuRaw,
            DownloadConfig.builder().artifactUuid(uuid).artifactVersion(version).build());
        return sharedArtifactService.downloadRawArtifact(oad.get());

    }

    @GetMapping("api/programmatic/v1/artifact/{uuid}/download")
    public Mono<ResponseEntity<byte[]>> downloadArtifactProgrammatic(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @org.springframework.web.bind.annotation.RequestParam(value = "version", required = false) Integer version,
        ServletWebRequest request,
        HttpServletResponse response
    ) throws Exception {
        Optional<ArtifactData> latestOad = artifactService.getArtifactData(uuid);
        if (latestOad.isEmpty()) throw new RelizaException("Artifact not found; uuid: " + uuid);
        ArtifactData ad = latestOad.get();
        var releases = sharedReleaseService.gatherReleasesForArtifact(uuid, ad.getOrg());
        Set<UUID> components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
        var ahp = authorizationService.authenticateProgrammatic(headers, request);
        authorizationService.isFreeformKeyAuthorizedForAnyObjectGraphQL(
            ahp, PermissionFunction.ARTIFACT_DOWNLOAD, PermissionScope.COMPONENT, components, List.of(ad));
        Optional<ArtifactData> oad = (version == null)
            ? latestOad
            : artifactService.getArtifactDataByVersion(uuid, version);
        if (oad.isEmpty()) throw new RelizaException("Artifact not found; uuid: " + uuid);
        return sharedArtifactService.downloadArtifact(oad.get());
    }

    @GetMapping("api/programmatic/v1/artifact/{uuid}/rawdownload")
    public Mono<ResponseEntity<byte[]>> downloadRawArtifactProgrammatic(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @org.springframework.web.bind.annotation.RequestParam(value = "version", required = false) Integer version,
        ServletWebRequest request,
        HttpServletResponse response
    ) throws Exception {
        Optional<ArtifactData> latestOad = artifactService.getArtifactData(uuid);
        if (latestOad.isEmpty()) throw new RelizaException("Artifact not found; uuid: " + uuid);
        ArtifactData ad = latestOad.get();
        var releases = sharedReleaseService.gatherReleasesForArtifact(uuid, ad.getOrg());
        Set<UUID> components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
        var ahp = authorizationService.authenticateProgrammatic(headers, request);
        authorizationService.isFreeformKeyAuthorizedForAnyObjectGraphQL(
            ahp, PermissionFunction.ARTIFACT_DOWNLOAD, PermissionScope.COMPONENT, components, List.of(ad));
        Optional<ArtifactData> oad = (version == null)
            ? latestOad
            : artifactService.getArtifactDataByVersion(uuid, version);
        if (oad.isEmpty()) throw new RelizaException("Artifact not found; uuid: " + uuid);
        return sharedArtifactService.downloadRawArtifact(oad.get());
    }

}
