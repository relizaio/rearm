/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.LogSafe;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.DownloadLogData.DownloadConfig;
import io.reliza.model.DownloadLogData.DownloadSubjectType;
import io.reliza.model.DownloadLogData.DownloadType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.service.ArtifactDownloadAccessService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.DownloadLogService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SupportInjectionService;
import io.reliza.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The artifact byte endpoints. Every handler authenticates first, then checks the system state,
 * and only then looks the artifact up (TPF-12), so the answer never says whether a uuid exists to
 * a caller who may not download it:
 * <ul>
 * <li>no credential, or one that does not verify: 401, before any lookup;</li>
 * <li>a verified caller on a sealed or unlicensed instance: 503 for every uuid;</li>
 * <li>a verified caller and an unknown artifact, or one they may not download: 403 for both, with
 *     the same headers;</li>
 * <li>an unknown version of an artifact the caller may download: 404.</li>
 * </ul>
 * Every status is returned, never thrown: a thrown refusal is forwarded to /error, which a caller
 * authenticated in the handler (not by the filter chain) reaches anonymously and reads as a 401,
 * and that is what made "unknown" and "forbidden" differ on the programmatic pair.
 */
@Slf4j
@RestController
public class ArtifactWs {

    /**
     * The programmatic download pair ({@code .../download} and {@code .../rawdownload}). One constant for
     * the programmatic security chain's matcher ({@link App#programmaticFilterChain}) and the path set of
     * {@link ProgrammaticAuthenticationFilter}, so the chain and the filter cannot disagree on a path
     * (TPF-13).
     */
    public static final String PROGRAMMATIC_ARTIFACT_PATTERN = "/api/programmatic/v1/artifact/**";

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ArtifactDownloadAccessService artifactDownloadAccessService;

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
        Gate gate = userGate(uuid, request);
        if (null != gate.refusal()) return gate.refusal();
        ArtifactData ad = gate.artifact();
        Optional<ArtifactData> oad = (version == null)
            ? Optional.of(ad)
            : artifactService.getArtifactDataByVersion(uuid, version);
		if (oad.isEmpty()) return notFound();

        // BEFORE the log row, deliberately. A refused request downloaded nothing, and a
        // download log that records it would report a document the caller never received --
        // which is the one thing an auditor reconstructing a submission must be able to trust.
        // The GraphQL export orders these the same way.
        ExportMetadataOptions exportMetadata =
            exportMetadataFor(ad.getOrg(), includeSupportMetadata, includeInternalMetadata);
        WhoUpdated wu = WhoUpdated.getWhoUpdated(gate.user());
        downloadLogService.createDownloadLog(ad.getOrg(), DownloadType.ARTIFACT_DOWNLOAD,
            DownloadSubjectType.ARTIFACT, oad.get().getUuid(), wu,
            DownloadConfig.builder().artifactUuid(uuid).artifactVersion(version)
                .includeSupportMetadata(exportMetadata.supportMetadata().toCallerInput())
                .includeInternalMetadata(exportMetadata.internalMetadata().toCallerInput()).build());
        return sharedArtifactService.downloadArtifact(oad.get(),
            artifactDownloadAccessService.resolveDeviceLifecycle(gate.releases(), releaseUuid), exportMetadata);

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
        Gate gate = userGate(uuid, request);
        if (null != gate.refusal()) return gate.refusal();
        ArtifactData ad = gate.artifact();
		Optional<ArtifactData> oad = (version == null)
            ? Optional.of(ad)
            : artifactService.getArtifactDataByVersion(uuid, version);
		if (oad.isEmpty()) return notFound();

        WhoUpdated wuRaw = WhoUpdated.getWhoUpdated(gate.user());
        downloadLogService.createDownloadLog(ad.getOrg(), DownloadType.RAW_ARTIFACT_DOWNLOAD,
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
        Gate gate = keyGate(uuid, headers, request);
        if (null != gate.refusal()) return gate.refusal();
        ArtifactData ad = gate.artifact();
        Optional<ArtifactData> oad = (version == null)
            ? Optional.of(ad)
            : artifactService.getArtifactDataByVersion(uuid, version);
        if (oad.isEmpty()) return notFound();
        // BEFORE the log row, as on the manual endpoint: a refused request downloaded nothing. The row is
        // written under the key, with the CLI-session person as actor (FreeformKeyVerification#whoUpdated).
        ExportMetadataOptions exportMetadata =
            exportMetadataFor(ad.getOrg(), includeSupportMetadata, includeInternalMetadata);
        downloadLogService.createDownloadLog(ad.getOrg(), DownloadType.ARTIFACT_DOWNLOAD,
            DownloadSubjectType.ARTIFACT, oad.get().getUuid(), gate.key().whoUpdated(),
            DownloadConfig.builder().artifactUuid(uuid).artifactVersion(version)
                .includeSupportMetadata(exportMetadata.supportMetadata().toCallerInput())
                .includeInternalMetadata(exportMetadata.internalMetadata().toCallerInput()).build());
        return sharedArtifactService.downloadArtifact(oad.get(),
            artifactDownloadAccessService.resolveDeviceLifecycle(gate.releases(), releaseUuid), exportMetadata);
    }

    /**
     * The same rule as {@link ArtifactDownloadAccessService#authorizeUserDownload} for an API key on the
     * programmatic endpoints. Throws, as that one does; {@link #keyGate} turns a throw into a returned
     * status. The verification it returns carries the actor the download-log row is written under.
     */
    private FreeformKeyVerification authorizeKeyDownload(AuthHeaderParse ahp, ArtifactData ad, Set<UUID> components) throws RelizaException {
        if (components.isEmpty()) {
            return authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.ARTIFACT_DOWNLOAD,
                PermissionScope.ORGANIZATION, ad.getOrg(), List.of(ad), CallType.READ);
        } else {
            return authorizationService.isFreeformKeyAuthorizedForAnyObjectGraphQL(ahp, PermissionFunction.ARTIFACT_DOWNLOAD,
                PermissionScope.COMPONENT, components, List.of(ad));
        }
    }

    /**
     * The shared preamble of a download: either {@code refusal} is set and is returned as is, or the
     * caller is verified and may download {@code artifact} (the latest version), held by
     * {@code releases}. {@code user} is the signed-in user on the manual pair and null on the
     * programmatic pair; {@code key} is the authorized key on the programmatic pair (its
     * {@link FreeformKeyVerification#whoUpdated()} is the download-log actor) and null on the manual pair.
     */
    private record Gate(Mono<ResponseEntity<byte[]>> refusal, ArtifactData artifact, List<ReleaseData> releases, UserData user,
            FreeformKeyVerification key) {
        static Gate refused(Mono<ResponseEntity<byte[]>> refusal) {
            return new Gate(refusal, null, null, null, null);
        }
    }

    /**
     * The manual pair: the chain has verified the JWT; the credential here is the user it resolves to.
     * Then system state, then the lookup, then authorization, in that order, so neither an unknown
     * uuid nor the system state is answered before the caller is known.
     */
    private Gate userGate(UUID uuid, ServletWebRequest request) {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        Optional<UserData> oud = userService.getUserDataByAuth(auth);
        if (oud.isEmpty()) return Gate.refused(unauthorized(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE,
            ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY));
        UserData ud = oud.get();
        String caller = String.valueOf(ud.getUuid());
        try {
            authorizationService.validateSystemOperational(CallType.READ);
        } catch (RelizaException e) {
            logRefusal(uuid, caller, request, e.getMessage());
            return Gate.refused(unavailable());
        }
        Optional<ArtifactData> oad = artifactService.getArtifactData(uuid);
        if (oad.isEmpty()) {
            logRefusal(uuid, caller, request, "artifact not found");
            return Gate.refused(forbidden());
        }
        ArtifactData ad = oad.get();
        List<ReleaseData> releases = sharedReleaseService.gatherReleasesForArtifact(uuid, ad.getOrg());
        Set<UUID> components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
        try {
            artifactDownloadAccessService.authorizeUserDownload(ud, ad, components);
        } catch (AccessDeniedException e) {
            logRefusal(uuid, caller, request, e.getMessage());
            return Gate.refused(forbidden());
        } catch (RelizaException e) {
            // the helper checks system state again on its first line: only a state change since the check above lands here
            logRefusal(uuid, caller, request, e.getMessage());
            return Gate.refused(unavailable());
        }
        return new Gate(null, ad, releases, ud, null);
    }

    /**
     * The programmatic pair: these paths are on the programmatic chain (TPF-13). For a bearer access token
     * {@link ProgrammaticAuthenticationFilter} has already verified it and left the principal as a request
     * attribute (a bad bearer, or no credential, never reaches this handler); since TPF-27 the filter verifies
     * HTTP Basic the same way (through {@link BasicKeyVerifier}, which {@link LegacyGraphqlBasicFilter} shares on the
     * browser chain's /graphql since TPF-29), so the verification below takes the filter's principal (a short-circuit), and
     * stays as the check for any caller that reaches this handler without the filter. Either way the key is verified before the artifact
     * (and so its organization) is known; {@link AuthorizationService#verifyProgrammaticKey} needs no
     * organization. Then system state, the lookup and authorization, as on the manual pair.
     */
    private Gate keyGate(UUID uuid, HttpHeaders headers, ServletWebRequest request) {
        AuthHeaderParse ahp = authorizationService.authenticateProgrammatic(headers, request);
        if (null == ahp) return Gate.refused(unauthorized(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE,
            ProgrammaticAuthenticationFilter.NO_CREDENTIAL_BODY));
        VerifiedProgrammaticKey vk;
        try {
            vk = authorizationService.verifyProgrammaticKey(ahp);
        } catch (AccessDeniedException e) {
            logRefusal(uuid, ahp.canonicalId(), request, e.getMessage());
            return Gate.refused(unauthorized(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE,
                ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY));
        }
        String caller = vk.principal().canonicalId();
        try {
            authorizationService.validateSystemOperational(CallType.READ);
        } catch (RelizaException e) {
            logRefusal(uuid, caller, request, e.getMessage());
            return Gate.refused(unavailable());
        }
        Optional<ArtifactData> oad = artifactService.getArtifactData(uuid);
        if (oad.isEmpty()) {
            logRefusal(uuid, caller, request, "artifact not found");
            return Gate.refused(forbidden());
        }
        ArtifactData ad = oad.get();
        List<ReleaseData> releases = sharedReleaseService.gatherReleasesForArtifact(uuid, ad.getOrg());
        Set<UUID> components = releases.stream().map(x -> x.getComponent()).collect(Collectors.toSet());
        FreeformKeyVerification authorized;
        try {
            authorized = authorizeKeyDownload(vk.principal(), ad, components);
        } catch (AccessDeniedException e) {
            logRefusal(uuid, caller, request, e.getMessage());
            return Gate.refused(forbidden());
        } catch (RelizaException e) {
            // the helper checks system state again on its first line: only a state change since the check above lands here
            logRefusal(uuid, caller, request, e.getMessage());
            return Gate.refused(unavailable());
        }
        return new Gate(null, ad, releases, null, authorized);
    }

    /**
     * The server-side trace of a refusal, now that the response no longer carries the reason: the key's
     * canonical identity or the user uuid, the remote address and the refusal message, rendered through
     * the bounded rendering of {@link LogSafe} here at the sink for every caller (TPF-24). Never a secret, never artifact content.
     */
    private static void logRefusal(UUID uuid, String caller, ServletWebRequest request, String reason) {
        String remoteAddr = (null == request || null == request.getRequest()) ? null : request.getRequest().getRemoteAddr();
        log.warn("SECURITY: artifact download refused, artifact {} caller {} from {}: {}", uuid, caller, remoteAddr, LogSafe.text(reason));
    }

    /** No credential, or one that does not verify; the two shapes are the programmatic filter's. */
    private static Mono<ResponseEntity<byte[]>> unauthorized(String challenge, String body) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A verified caller and an artifact they may not download, or one that does not exist: one answer
     * for both, so the status says nothing about which uuids exist.
     */
    private static Mono<ResponseEntity<byte[]>> forbidden() {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, "no-store").build());
    }

    /**
     * A sealed system or an invalid license, for every uuid. No Retry-After: the state ends when an
     * operator acts, not after an interval. The reason goes to the server log, not the response.
     */
    private static Mono<ResponseEntity<byte[]>> unavailable() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.CACHE_CONTROL, "no-store").build());
    }

    /**
     * An unknown version of an artifact the caller is authorized to download is a 404. Reached only
     * after authorization: an unknown artifact is a 403 (see {@link #forbidden()}). Returned rather
     * than thrown, like every status here.
     */
    private static Mono<ResponseEntity<byte[]>> notFound() {
        return Mono.just(ResponseEntity.notFound().build());
    }

    @GetMapping("api/programmatic/v1/artifact/{uuid}/rawdownload")
    public Mono<ResponseEntity<byte[]>> downloadRawArtifactProgrammatic(
        @RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        @RequestParam(value = "version", required = false) Integer version,
        ServletWebRequest request,
        HttpServletResponse response
    ) throws Exception {
        Gate gate = keyGate(uuid, headers, request);
        if (null != gate.refusal()) return gate.refusal();
        ArtifactData ad = gate.artifact();
        Optional<ArtifactData> oad = (version == null)
            ? Optional.of(ad)
            : artifactService.getArtifactDataByVersion(uuid, version);
        if (oad.isEmpty()) return notFound();
        downloadLogService.createDownloadLog(ad.getOrg(), DownloadType.RAW_ARTIFACT_DOWNLOAD,
            DownloadSubjectType.ARTIFACT, oad.get().getUuid(), gate.key().whoUpdated(),
            DownloadConfig.builder().artifactUuid(uuid).artifactVersion(version).build());
        return sharedArtifactService.downloadRawArtifact(oad.get());
    }

}
