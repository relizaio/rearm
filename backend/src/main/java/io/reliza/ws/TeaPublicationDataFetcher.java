/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseData;
import io.reliza.model.SbomProfileData;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublicationData.TeaAggregateArtifact;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.model.tea.publication.TeaChecksumDocument;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaConcealReason;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishPreview;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.UserService;

/**
 * GraphQL entry points of the TEA publication engine (task TEA-5). Publish, re-publish and hide
 * need PUBLISH_EXTERNALLY at WRITE on the release's component (the RELEASE scope resolves to the
 * component; organization admins pass implicitly); the reads need READ on the release. The
 * programmatic twins take FREEFORM or USER keys only: an object-bound key carries no function
 * grants. Authorization happens here, before the service is called; a cascade is authorized on
 * the product alone. The programmatic twins verify the key and the instance state before the
 * release is read, and read it in the key's organisation (TEA-25, the TPF-17 order).
 */
@DgsComponent
public class TeaPublicationDataFetcher {

	@Autowired private UserService userService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private TeaPublicationService teaPublicationService;

	// ------------------------------------------------------------------ user surface

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseTeaPublicationView")
	public TeaPublishPreview releaseTeaPublicationView(@InputArgument("release") UUID release) throws RelizaException {
		ReleaseData rd = release(release);
		authorizationService.isUserAuthorizedForObjectGraphQL(currentUser(), PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
		return teaPublicationService.preview(rd.getUuid());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "teaPublication")
	public TeaPublicationData teaPublication(@InputArgument("uuid") UUID uuid) throws RelizaException {
		return readablePublication(currentUser(), uuid).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "teaPublicationCollections")
	public List<TeaPublishedCollectionData> teaPublicationCollections(@InputArgument("publication") UUID publication)
			throws RelizaException {
		Optional<TeaPublicationData> p = readablePublication(currentUser(), publication);
		return p.isEmpty() ? List.of() : teaPublicationService.listCollections(p.get().getUuid());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "publishReleaseOnTea")
	public TeaPublishResult publishReleaseOnTea(@InputArgument("release") UUID release,
			@InputArgument("comment") String comment) throws RelizaException {
		UserData ud = currentUser();
		ReleaseData rd = authorizeUserPublish(ud, release);
		return teaPublicationService.publish(new TeaPublishRequest(rd.getUuid(), TeaPublishMode.PUBLISH, comment,
				WhoUpdated.getWhoUpdated(ud)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "republishReleaseOnTea")
	public TeaPublishResult republishReleaseOnTea(@InputArgument("release") UUID release,
			@InputArgument("comment") String comment) throws RelizaException {
		UserData ud = currentUser();
		ReleaseData rd = authorizeUserPublish(ud, release);
		return teaPublicationService.publish(new TeaPublishRequest(rd.getUuid(), TeaPublishMode.REPUBLISH, comment,
				WhoUpdated.getWhoUpdated(ud)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "hideReleaseOnTea")
	public TeaPublicationData hideReleaseOnTea(@InputArgument("release") UUID release,
			@InputArgument("comment") String comment) throws RelizaException {
		UserData ud = currentUser();
		ReleaseData rd = authorizeUserPublish(ud, release);
		return teaPublicationService.hide(rd.getUuid(), comment, WhoUpdated.getWhoUpdated(ud));
	}

	// ------------------------------------------------------------------ programmatic surface

	@DgsData(parentType = "Query", field = "releaseTeaPublicationViewProgrammatic")
	public TeaPublishPreview releaseTeaPublicationViewProgrammatic(@InputArgument("release") UUID release,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		VerifiedProgrammaticKey vk = verifiedKey(dfe, CallType.READ);
		ReleaseData rd = release(release, vk.org());
		authorizeKey(vk.principal(), rd, PermissionFunction.RESOURCE, CallType.READ);
		return teaPublicationService.preview(rd.getUuid());
	}

	@DgsData(parentType = "Mutation", field = "publishReleaseOnTeaProgrammatic")
	public TeaPublishResult publishReleaseOnTeaProgrammatic(@InputArgument("release") UUID release,
			@InputArgument("comment") String comment, DgsDataFetchingEnvironment dfe) throws RelizaException {
		VerifiedProgrammaticKey vk = verifiedKey(dfe, CallType.WRITE);
		ReleaseData rd = release(release, vk.org());
		FreeformKeyVerification fkv = authorizeKey(vk.principal(), rd, PermissionFunction.PUBLISH_EXTERNALLY,
				CallType.WRITE);
		return teaPublicationService.publish(new TeaPublishRequest(rd.getUuid(), TeaPublishMode.PUBLISH, comment,
				fkv.whoUpdated()));
	}

	@DgsData(parentType = "Mutation", field = "republishReleaseOnTeaProgrammatic")
	public TeaPublishResult republishReleaseOnTeaProgrammatic(@InputArgument("release") UUID release,
			@InputArgument("comment") String comment, DgsDataFetchingEnvironment dfe) throws RelizaException {
		VerifiedProgrammaticKey vk = verifiedKey(dfe, CallType.WRITE);
		ReleaseData rd = release(release, vk.org());
		FreeformKeyVerification fkv = authorizeKey(vk.principal(), rd, PermissionFunction.PUBLISH_EXTERNALLY,
				CallType.WRITE);
		return teaPublicationService.publish(new TeaPublishRequest(rd.getUuid(), TeaPublishMode.REPUBLISH, comment,
				fkv.whoUpdated()));
	}

	@DgsData(parentType = "Mutation", field = "hideReleaseOnTeaProgrammatic")
	public TeaPublicationData hideReleaseOnTeaProgrammatic(@InputArgument("release") UUID release,
			@InputArgument("comment") String comment, DgsDataFetchingEnvironment dfe) throws RelizaException {
		VerifiedProgrammaticKey vk = verifiedKey(dfe, CallType.WRITE);
		ReleaseData rd = release(release, vk.org());
		FreeformKeyVerification fkv = authorizeKey(vk.principal(), rd, PermissionFunction.PUBLISH_EXTERNALLY,
				CallType.WRITE);
		return teaPublicationService.hide(rd.getUuid(), comment, fkv.whoUpdated());
	}

	// ------------------------------------------------------------------ field resolvers

	/** The release's publication head, or null; the parent release was authorized by its own query. */
	@DgsData(parentType = "Release", field = "teaPublication")
	public TeaPublicationData teaPublicationOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return null == rd ? null : teaPublicationService.getPublicationOfRelease(rd.getUuid()).orElse(null);
	}

	/** The spec string of a frozen checksum's type ({@code SHA-256}); the schema field stays a String. */
	@DgsData(parentType = "TeaPublishedChecksum", field = "algType")
	public String publishedChecksumAlgType(DgsDataFetchingEnvironment dfe) {
		TeaChecksumDocument c = dfe.getSource();
		return null == c || null == c.algType() ? null : c.algType().getValue();
	}

	@DgsData(parentType = "TeaPublication", field = "exposure")
	public TeaExposureState exposure(DgsDataFetchingEnvironment dfe) {
		TeaPublicationData p = dfe.getSource();
		return teaPublicationService.exposureOf(p).state();
	}

	@DgsData(parentType = "TeaPublication", field = "concealedBecause")
	public TeaConcealReason concealedBecause(DgsDataFetchingEnvironment dfe) {
		TeaPublicationData p = dfe.getSource();
		return teaPublicationService.exposureOf(p).concealedBecause();
	}

	@DgsData(parentType = "TeaPublication", field = "cascadeOfLabel")
	public String cascadeOfLabel(DgsDataFetchingEnvironment dfe) {
		TeaPublicationData p = dfe.getSource();
		return null == p || null == p.getCascadeOf() ? null : teaPublicationService.ownerLabel(p.getCascadeOf());
	}

	/**
	 * Deprecated by task TEA-28 (one aggregate per SBOM profile; read {@code aggregateArtifacts}): the
	 * {@code default} profile's artifact, else the first, else null.
	 */
	@DgsData(parentType = "TeaPublication", field = "aggregateArtifact")
	public UUID aggregateArtifact(DgsDataFetchingEnvironment dfe) {
		return aggregateArtifactOf(dfe.getSource());
	}

	static UUID aggregateArtifactOf(TeaPublicationData p) {
		if (null == p || null == p.getAggregateArtifacts() || p.getAggregateArtifacts().isEmpty()) return null;
		return p.getAggregateArtifacts().stream()
				.filter(a -> null != a && SbomProfileData.DEFAULT_NAME.equals(a.profileName())).findFirst()
				.or(() -> p.getAggregateArtifacts().stream().filter(java.util.Objects::nonNull).findFirst())
				.map(TeaAggregateArtifact::artifact).orElse(null);
	}

	@DgsData(parentType = "TeaPublication", field = "publicUrl")
	public String publicUrl(DgsDataFetchingEnvironment dfe) {
		TeaPublicationData p = dfe.getSource();
		return null == p ? null : TeaPublicationService.publicUrl(teaPublicationService.apiBaseOf(p.getOrg()), p);
	}

	// ------------------------------------------------------------------ helpers

	UserData currentUser() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new RelizaException("User not found"));
	}

	private ReleaseData release(UUID release) throws RelizaException {
		if (null == release) throw new RelizaException("release is required");
		return sharedReleaseService.getReleaseData(release)
				.orElseThrow(() -> new RelizaException("Release not found: " + release));
	}

	/** The release when it exists in {@code org}: a release of another organisation reads as a missing one. */
	private ReleaseData release(UUID release, UUID org) throws RelizaException {
		if (null == release) throw new RelizaException("release is required");
		return sharedReleaseService.getReleaseData(release, org)
				.orElseThrow(() -> new RelizaException("Release not found: " + release));
	}

	/** PUBLISH_EXTERNALLY at WRITE on the release, checked before the service runs. */
	private ReleaseData authorizeUserPublish(UserData ud, UUID release) throws RelizaException {
		ReleaseData rd = release(release);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.PUBLISH_EXTERNALLY,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.WRITE);
		return rd;
	}

	/** The publication when the user may read its release; unknown and unreadable both answer empty. */
	private Optional<TeaPublicationData> readablePublication(UserData ud, UUID uuid) {
		Optional<TeaPublicationData> p = teaPublicationService.getPublication(uuid);
		if (p.isEmpty()) return Optional.empty();
		Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(p.get().getRelease());
		if (rd.isEmpty() || !authorizationService.isUserAuthorizedForObject(ud, PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.get().getUuid(), List.of(rd.get()), CallType.READ)) {
			return Optional.empty();
		}
		return p;
	}

	/**
	 * The calling key verified and the instance operational, before anything the caller named is
	 * read (TEA-25, the TPF-17 order): a credential that does not verify reads Not authorized and
	 * learns nothing, and a sealed or unlicensed instance answers its sentence for every uuid alike.
	 */
	private VerifiedProgrammaticKey verifiedKey(DgsDataFetchingEnvironment dfe, CallType ct) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		ServletWebRequest servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(),
				servletWebRequest);
		AuthHeaderParse ahp = authCtx.ahp();
		if (null == ahp) throw new AccessDeniedException("Invalid authorization");
		VerifiedProgrammaticKey vk = authorizationService.verifyProgrammaticKey(ahp);
		authorizationService.validateSystemOperational(ct);
		return vk;
	}

	/**
	 * A FREEFORM or USER key with {@code function} at {@code ct} on the release; an object-bound key
	 * is refused by the RBAC key resolution ("FREEFORM or USER API key required").
	 */
	private FreeformKeyVerification authorizeKey(AuthHeaderParse ahp, ReleaseData rd, PermissionFunction function,
			CallType ct) throws RelizaException {
		return authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp, function, PermissionScope.RELEASE,
				rd.getUuid(), List.of(rd), ct);
	}
}
