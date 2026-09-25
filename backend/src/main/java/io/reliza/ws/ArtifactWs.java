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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.DownloadLogData.DownloadConfig;
import io.reliza.model.DownloadLogData.DownloadSubjectType;
import io.reliza.model.DownloadLogData.DownloadType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.service.ArtifactService;
import io.reliza.service.DeviceLifecycleHook;
import io.reliza.service.AuthorizationService;
import io.reliza.service.DownloadLogService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SupportInjectionService;
import io.reliza.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class ArtifactWs {

    @Autowired
    private ArtifactService artifactService;

    // Optional: the implementation is Pro-side, and CE has no devices.
    @Autowired(required = false)
    private DeviceLifecycleHook deviceLifecycleHook;

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
	private SupportInjectionService supportInjectionService;

	/**
	 * Parse and validate this download's per-export metadata flags.
	 *
	 * <p>One helper for both the manual and the programmatic endpoint so the two cannot answer
	 * the same request differently -- which is the failure the single injection seam exists to
	 * prevent, one layer down. Both parameters omitted is the pre-existing contract: every CLI
	 * and API caller written before they existed lands on {@code callerSilent()} and gets the
	 * same bytes it always got.
	 *
	 * <p>Not applied to either RAW download. That path serves the publisher's uploaded bytes
	 * against an advertised checksum; a query parameter that edited them would break the only
	 * promise it makes, so the flags are not accepted there rather than accepted and ignored.
	 */
	private ExportMetadataOptions exportMetadataFor(UUID orgUuid, Boolean includeSupportMetadata,
			Boolean includeInternalMetadata) throws RelizaException {
		ExportMetadataOptions options = ExportMetadataOptions
				.fromCallerInput(includeSupportMetadata, includeInternalMetadata);
		supportInjectionService.assertExportMetadataRequestable(orgUuid, options);
		return options;
	}

    @GetMapping("api/manual/v1/artifact/{uuid}/download")
    public Mono<ResponseEntity<byte[]>> downloadArtifact(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @RequestParam(value = "version", required = false) Integer version,
        @RequestParam(value = "releaseUuid", required = false) UUID releaseUuid,
        @RequestParam(value = "includeSupportMetadata", required = false) Boolean includeSupportMetadata,
        @RequestParam(value = "includeInternalMetadata", required = false) Boolean includeInternalMetadata,
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

        // BEFORE the log row, deliberately. A refused request downloaded nothing, and a
        // download log that records it would report a document the caller never received --
        // which is the one thing an auditor reconstructing a submission must be able to trust.
        // The GraphQL export orders these the same way.
        ExportMetadataOptions exportMetadata =
            exportMetadataFor(ro.getOrg(), includeSupportMetadata, includeInternalMetadata);
        WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
        downloadLogService.createDownloadLog(ro.getOrg(), DownloadType.ARTIFACT_DOWNLOAD,
            DownloadSubjectType.ARTIFACT, oad.get().getUuid(), wu,
            DownloadConfig.builder().artifactUuid(uuid).artifactVersion(version)
                .includeSupportMetadata(exportMetadata.supportMetadata().toCallerInput())
                .includeInternalMetadata(exportMetadata.internalMetadata().toCallerInput()).build());
        return sharedArtifactService.downloadArtifact(oad.get(),
            resolveDeviceLifecycle(releases, releaseUuid), exportMetadata);

    }
    @GetMapping("api/manual/v1/artifact/{uuid}/rawdownload")
    public Mono<ResponseEntity<byte[]>> downloadRawArtifact(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @RequestParam(value = "version", required = false) Integer version,
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
        @RequestParam(value = "version", required = false) Integer version,
        @RequestParam(value = "releaseUuid", required = false) UUID releaseUuid,
        @RequestParam(value = "includeSupportMetadata", required = false) Boolean includeSupportMetadata,
        @RequestParam(value = "includeInternalMetadata", required = false) Boolean includeInternalMetadata,
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
        return sharedArtifactService.downloadArtifact(oad.get(), resolveDeviceLifecycle(releases, releaseUuid),
            exportMetadataFor(ad.getOrg(), includeSupportMetadata, includeInternalMetadata));
    }

    /**
     * The device window for an artifact, from {@link io.reliza.service.DeviceLifecycleHook} (D7).
     *
     * <p><b>The "exactly one PRODUCT release" rule is gone.</b> This used to take the
     * release's own {@code eos}/{@code eol} only when precisely one PRODUCT release was in
     * play, or when {@code ?releaseUuid} named one -- which meant the augmented download of
     * an artifact attached to a product carried the device properties while the SAME artifact
     * fetched from a component page carried none. That was a rule about how many releases
     * happened to reference a file, standing in for a question about which device the file is
     * in, and it needed a paragraph of walkthrough text to stop reading as a bug.
     *
     * <p>Now the artifact resolves through its release's product component, and the only
     * remaining null case is genuine ambiguity -- several products whose declared windows
     * DISAGREE, where stamping either would attribute one device's commitment to another's
     * software. See {@code DeviceLifecycleHook.forArtifactReleases}.
     */
    private DeviceLifecycle resolveDeviceLifecycle(List<ReleaseData> releases, UUID releaseUuid) {
        // Absent in CE, where there are no devices to make a commitment about, so the artifact is
        // served without device properties rather than with empty ones.
        return null == deviceLifecycleHook ? null : deviceLifecycleHook.forArtifactReleases(releases, releaseUuid);
    }

    @GetMapping("api/programmatic/v1/artifact/{uuid}/rawdownload")
    public Mono<ResponseEntity<byte[]>> downloadRawArtifactProgrammatic(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @RequestParam(value = "version", required = false) Integer version,
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
