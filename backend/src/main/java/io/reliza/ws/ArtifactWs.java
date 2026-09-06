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
import io.reliza.model.ComponentData;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.ReleaseData;
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
import io.reliza.service.GetComponentService;
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

	@Autowired
	private GetComponentService getComponentService;

    @GetMapping("api/manual/v1/artifact/{uuid}/download")
    public Mono<ResponseEntity<byte[]>> downloadArtifact(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @org.springframework.web.bind.annotation.RequestParam(value = "version", required = false) Integer version,
        @org.springframework.web.bind.annotation.RequestParam(value = "releaseUuid", required = false) UUID releaseUuid,
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
        return sharedArtifactService.downloadArtifact(oad.get(), resolveDeviceLifecycle(releases, releaseUuid));

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
        @org.springframework.web.bind.annotation.RequestParam(value = "releaseUuid", required = false) UUID releaseUuid,
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
        return sharedArtifactService.downloadArtifact(oad.get(), resolveDeviceLifecycle(releases, releaseUuid));
    }

    /**
     * Resolve the device support window for the support export from the artifact's releases
     * (FDA-Readiness-1 PR5). The download is artifact-scoped, but the device-support-risk verdict
     * only makes sense against a single PRODUCT (device) release's support window, so:
     * <ul>
     *   <li>gate to PRODUCT releases -- on a plain component/library release {@code eos}/{@code eol}
     *       is that library's own lifecycle, not a device horizon, so the device concept
     *       does not apply;</li>
     *   <li>if the caller passes a {@code releaseUuid} that resolves to one of those PRODUCT
     *       releases, use it (the UI launches the download from a specific release);</li>
     *   <li>otherwise use it only when the artifact maps to exactly one PRODUCT release -- several
     *       device releases with no explicit pointer is ambiguous, so we return null and stamp
     *       nothing rather than pick the wrong device.</li>
     * </ul>
     * A null result means "no device disclosure" -- the raw component milestone dates are still
     * injected. Returns null too when the resolved device declares no support horizon at all.
     */
    private DeviceLifecycle resolveDeviceLifecycle(List<ReleaseData> releases, UUID releaseUuid) {
        if (releases == null || releases.isEmpty()) {
            return null;
        }
        List<ReleaseData> deviceReleases = releases.stream()
            .filter(r -> r.getComponent() != null
                && getComponentService.getComponentData(r.getComponent())
                    .map(cd -> cd.getType() == ComponentData.ComponentType.PRODUCT)
                    .orElse(false))
            .collect(Collectors.toList());
        if (deviceReleases.isEmpty()) {
            return null;
        }
        ReleaseData device = null;
        if (releaseUuid != null) {
            device = deviceReleases.stream()
                .filter(r -> releaseUuid.equals(r.getUuid()))
                .findFirst()
                .orElse(null);
        } else if (deviceReleases.size() == 1) {
            device = deviceReleases.get(0);
        }
        if (device == null) {
            return null;
        }
        DeviceLifecycle dl = new DeviceLifecycle(device.getEos(), device.getEol());
        return dl.declaresAnyDate() ? dl : null;
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
