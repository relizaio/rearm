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
import io.reliza.exceptions.SubmissionRefusedException;
import io.reliza.model.ComponentData;
import io.reliza.model.PackageKind;
import io.reliza.model.ReleaseData;
import io.reliza.model.SubmissionActResult;
import io.reliza.model.SubmissionData;
import io.reliza.model.SubmissionPreview;
import io.reliza.model.SubmissionRefusal;
import io.reliza.model.SubmissionState;
import io.reliza.model.SubmissionVersionData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SubmissionHook;
import io.reliza.service.UserService;

/**
 * GraphQL entry points of regulatory submissions (task TEA-35, decisions E1 and E2), shared by both
 * editions. Every root field authenticates, reads the object it names by uuid, answers "not found"
 * unless the caller is in the object's organization (a key: the key's organization), judges the
 * permission (acts: READ_WRITE on the release's component; release reads: READ on the release; the
 * component list and the preview: READ on the component) and only then calls the hook with the
 * object's organization. The four child resolvers run no check of their own: the field that answered
 * their parent authorized it. With no hook (CE) the release- and component-keyed fields still run the
 * full check and answer empty lists or EDITION_UNSUPPORTED; the submission-keyed ones, which cannot
 * resolve their object, answer null or EDITION_UNSUPPORTED after authentication alone.
 */
@DgsComponent
public class SubmissionDataFetcher {

	static final String PRO_ONLY = "regulatory submissions are a ReARM Pro feature";

	@Autowired private UserService userService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetComponentService getComponentService;

	@Autowired(required = false)
	private SubmissionHook hook;

	// ------------------------------------------------------------------ user reads

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "submissionsOfRelease")
	public List<SubmissionData> submissionsOfRelease(@InputArgument("release") UUID release) throws RelizaException {
		return submissionsOfRelease(currentUser(), release);
	}

	List<SubmissionData> submissionsOfRelease(UserData ud, UUID release) throws RelizaException {
		ReleaseData rd = releaseOf(ud, release);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				rd.getUuid(), List.of(rd), CallType.READ);
		return null == hook ? List.of() : hook.listOfRelease(rd.getOrg(), rd.getUuid());
	}

	/**
	 * The package kinds a release offers (the release page shows one regulatory row per kind); the
	 * field that answered the release authorized it, as for the other child resolvers. CE: none.
	 */
	@DgsData(parentType = "Release", field = "regulatoryPackageKinds")
	public List<PackageKind> regulatoryPackageKinds(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return null == hook || null == rd ? List.of() : hook.regulatoryKindsOfRelease(rd);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "submissionsOfComponent")
	public List<SubmissionData> submissionsOfComponent(@InputArgument("component") UUID component,
			@InputArgument("kind") PackageKind kind, @InputArgument("state") SubmissionState state)
			throws RelizaException {
		return submissionsOfComponent(currentUser(), component, kind, state);
	}

	List<SubmissionData> submissionsOfComponent(UserData ud, UUID component, PackageKind kind, SubmissionState state)
			throws RelizaException {
		ComponentData cd = componentOf(ud, component);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.READ);
		return null == hook ? List.of() : hook.listOfComponent(cd.getOrg(), cd.getUuid(), kind, state);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "submission")
	public SubmissionData submission(@InputArgument("uuid") UUID uuid) throws RelizaException {
		return submission(currentUser(), uuid);
	}

	SubmissionData submission(UserData ud, UUID uuid) throws RelizaException {
		if (null == hook) return null;
		return readableHead(ud, uuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "submissionVersion")
	public SubmissionVersionData submissionVersion(@InputArgument("submission") UUID submission,
			@InputArgument("version") Integer version) throws RelizaException {
		return submissionVersion(currentUser(), submission, version);
	}

	SubmissionVersionData submissionVersion(UserData ud, UUID submission, Integer version) throws RelizaException {
		if (null == hook) return null;
		SubmissionData head = readableHead(ud, submission);
		return null == version ? null : hook.getVersion(head.getOrg(), head.getUuid(), version).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "submissionPreview")
	public SubmissionPreview submissionPreview(@InputArgument("release") UUID release,
			@InputArgument("kind") PackageKind kind) throws RelizaException {
		return submissionPreview(currentUser(), release, kind);
	}

	SubmissionPreview submissionPreview(UserData ud, UUID release, PackageKind kind) throws RelizaException {
		ReleaseData rd = releaseOf(ud, release);
		ComponentData cd = componentOfRelease(rd);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.READ);
		if (null == hook) {
			return new SubmissionPreview(false, SubmissionRefusal.EDITION_UNSUPPORTED, PRO_ONLY, 0, null, null, null,
					List.of());
		}
		return hook.preview(rd.getUuid(), kind);
	}

	// ------------------------------------------------------------------ user acts

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createSubmission")
	public SubmissionActResult createSubmission(@InputArgument("release") UUID release,
			@InputArgument("kind") PackageKind kind, @InputArgument("reason") String reason) throws RelizaException {
		return createSubmission(currentUser(), release, kind, reason);
	}

	SubmissionActResult createSubmission(UserData ud, UUID release, PackageKind kind, String reason)
			throws RelizaException {
		ReleaseData rd = releaseOf(ud, release);
		ComponentData cd = componentOfRelease(rd);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.WRITE);
		if (null == hook) {
			throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, rd.getUuid(), "Release "
					+ cd.getName() + " " + rd.getVersion() + " (" + rd.getUuid() + "): " + PRO_ONLY);
		}
		return hook.create(rd.getUuid(), kind, reason, WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "resubmitSubmission")
	public SubmissionActResult resubmitSubmission(@InputArgument("submission") UUID submission,
			@InputArgument("reason") String reason, @InputArgument("expectedLatestVersion") Integer expectedLatestVersion)
			throws RelizaException {
		return resubmitSubmission(currentUser(), submission, reason, expectedLatestVersion);
	}

	SubmissionActResult resubmitSubmission(UserData ud, UUID submission, String reason, Integer expectedLatestVersion)
			throws RelizaException {
		if (null == hook) throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, null, PRO_ONLY);
		SubmissionData head = writableHead(ud, submission);
		return hook.resubmit(head.getOrg(), head.getUuid(), reason, expectedLatestVersion, WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "withdrawSubmission")
	public SubmissionActResult withdrawSubmission(@InputArgument("submission") UUID submission,
			@InputArgument("reason") String reason) throws RelizaException {
		return withdrawSubmission(currentUser(), submission, reason);
	}

	SubmissionActResult withdrawSubmission(UserData ud, UUID submission, String reason) throws RelizaException {
		if (null == hook) throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, null, PRO_ONLY);
		SubmissionData head = writableHead(ud, submission);
		return hook.withdraw(head.getOrg(), head.getUuid(), reason, WhoUpdated.getWhoUpdated(ud));
	}

	// ------------------------------------------------------------------ programmatic reads

	@DgsData(parentType = "Query", field = "submissionsOfReleaseProgrammatic")
	public List<SubmissionData> submissionsOfReleaseProgrammatic(@InputArgument("release") UUID release,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		return submissionsOfReleaseForKey(verifiedKey(dfe, CallType.READ), release);
	}

	List<SubmissionData> submissionsOfReleaseForKey(VerifiedProgrammaticKey vk, UUID release) throws RelizaException {
		ReleaseData rd = releaseOfKey(vk, release);
		authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(vk.principal(), PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
		return null == hook ? List.of() : hook.listOfRelease(rd.getOrg(), rd.getUuid());
	}

	@DgsData(parentType = "Query", field = "submissionsOfComponentProgrammatic")
	public List<SubmissionData> submissionsOfComponentProgrammatic(@InputArgument("component") UUID component,
			@InputArgument("kind") PackageKind kind, @InputArgument("state") SubmissionState state,
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		return submissionsOfComponentForKey(verifiedKey(dfe, CallType.READ), component, kind, state);
	}

	List<SubmissionData> submissionsOfComponentForKey(VerifiedProgrammaticKey vk, UUID component, PackageKind kind,
			SubmissionState state) throws RelizaException {
		if (null == component) throw new RelizaException("component is required");
		ComponentData cd = getComponentService.getComponentData(component, vk.org())
				.orElseThrow(() -> new RelizaException("Component not found: " + component));
		authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(vk.principal(), PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.READ);
		return null == hook ? List.of() : hook.listOfComponent(cd.getOrg(), cd.getUuid(), kind, state);
	}

	@DgsData(parentType = "Query", field = "submissionProgrammatic")
	public SubmissionData submissionProgrammatic(@InputArgument("uuid") UUID uuid, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		return submissionForKey(verifiedKey(dfe, CallType.READ), uuid);
	}

	SubmissionData submissionForKey(VerifiedProgrammaticKey vk, UUID uuid) throws RelizaException {
		if (null == hook) return null;
		return readableHeadForKey(vk, uuid);
	}

	@DgsData(parentType = "Query", field = "submissionVersionProgrammatic")
	public SubmissionVersionData submissionVersionProgrammatic(@InputArgument("submission") UUID submission,
			@InputArgument("version") Integer version, DgsDataFetchingEnvironment dfe) throws RelizaException {
		return submissionVersionForKey(verifiedKey(dfe, CallType.READ), submission, version);
	}

	SubmissionVersionData submissionVersionForKey(VerifiedProgrammaticKey vk, UUID submission, Integer version)
			throws RelizaException {
		if (null == hook) return null;
		SubmissionData head = readableHeadForKey(vk, submission);
		return null == version ? null : hook.getVersion(head.getOrg(), head.getUuid(), version).orElse(null);
	}

	// ------------------------------------------------------------------ child resolvers (no check of their own)

	/** The release's submissions; the field that answered the Release authorized it. */
	@DgsData(parentType = "Release", field = "submissions")
	public List<SubmissionData> submissionsOfReleaseField(DgsDataFetchingEnvironment dfe) {
		return submissionsOf(dfe.<ReleaseData>getSource());
	}

	List<SubmissionData> submissionsOf(ReleaseData rd) {
		if (null == hook || null == rd) return List.of();
		return hook.listOfRelease(rd.getOrg(), rd.getUuid());
	}

	@DgsData(parentType = "Submission", field = "versions")
	public List<SubmissionVersionData> versions(DgsDataFetchingEnvironment dfe) {
		return versionsOf(dfe.<SubmissionData>getSource());
	}

	List<SubmissionVersionData> versionsOf(SubmissionData s) {
		if (null == hook || null == s) return List.of();
		return hook.listVersions(s.getOrg(), s.getUuid());
	}

	@DgsData(parentType = "Submission", field = "latest")
	public SubmissionVersionData latest(DgsDataFetchingEnvironment dfe) {
		return latestOf(dfe.<SubmissionData>getSource());
	}

	SubmissionVersionData latestOf(SubmissionData s) {
		if (null == hook || null == s || s.getLatestVersion() < 1) return null;
		return hook.getVersion(s.getOrg(), s.getUuid(), s.getLatestVersion()).orElse(null);
	}

	@DgsData(parentType = "Submission", field = "releaseVersion")
	public String releaseVersion(DgsDataFetchingEnvironment dfe) {
		return releaseVersionOf(dfe.<SubmissionData>getSource());
	}

	String releaseVersionOf(SubmissionData s) {
		if (null == hook || null == s || null == s.getRelease()) return null;
		return sharedReleaseService.getReleaseDataLight(s.getRelease()).map(ReleaseData::getVersion).orElse(null);
	}

	// ------------------------------------------------------------------ helpers

	UserData currentUser() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new RelizaException("User not found"));
	}

	/** The release by uuid, "not found" when it is missing or the user is not in its organization. */
	private ReleaseData releaseOf(UserData ud, UUID release) throws RelizaException {
		if (null == release) throw new RelizaException("release is required");
		Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(release);
		if (rd.isEmpty() || !ud.isInOrganization(rd.get().getOrg())) {
			throw new RelizaException("Release not found: " + release);
		}
		return rd.get();
	}

	private ComponentData componentOf(UserData ud, UUID component) throws RelizaException {
		if (null == component) throw new RelizaException("component is required");
		Optional<ComponentData> cd = getComponentService.getComponentData(component);
		if (cd.isEmpty() || !ud.isInOrganization(cd.get().getOrg())) {
			throw new RelizaException("Component not found: " + component);
		}
		return cd.get();
	}

	private ComponentData componentOfRelease(ReleaseData rd) throws RelizaException {
		return getComponentService.getComponentData(rd.getComponent())
				.orElseThrow(() -> new RelizaException("Component not found: " + rd.getComponent()));
	}

	/** The head by uuid (the hook's one read without an organization), "not found" unless the user is in its organization. */
	private SubmissionData headOf(UserData ud, UUID submission) throws RelizaException {
		if (null == submission) throw new RelizaException("submission is required");
		Optional<SubmissionData> head = hook.getSubmission(submission);
		if (head.isEmpty() || !ud.isInOrganization(head.get().getOrg())) {
			throw new RelizaException("Submission not found: " + submission);
		}
		return head.get();
	}

	/** RELEASE READ on the head's release. */
	private SubmissionData readableHead(UserData ud, UUID submission) throws RelizaException {
		SubmissionData head = headOf(ud, submission);
		ReleaseData rd = sharedReleaseService.getReleaseData(head.getRelease())
				.orElseThrow(() -> new RelizaException("Submission not found: " + submission));
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				rd.getUuid(), List.of(rd), CallType.READ);
		return head;
	}

	/** COMPONENT WRITE on the head's component. */
	private SubmissionData writableHead(UserData ud, UUID submission) throws RelizaException {
		SubmissionData head = headOf(ud, submission);
		ComponentData cd = getComponentService.getComponentData(head.getComponent())
				.orElseThrow(() -> new RelizaException("Submission not found: " + submission));
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.WRITE);
		return head;
	}

	private ReleaseData releaseOfKey(VerifiedProgrammaticKey vk, UUID release) throws RelizaException {
		if (null == release) throw new RelizaException("release is required");
		return sharedReleaseService.getReleaseData(release, vk.org())
				.orElseThrow(() -> new RelizaException("Release not found: " + release));
	}

	/** The head in the key's organization ("not found" otherwise), then the key's READ on its release. */
	private SubmissionData readableHeadForKey(VerifiedProgrammaticKey vk, UUID submission) throws RelizaException {
		if (null == submission) throw new RelizaException("submission is required");
		Optional<SubmissionData> head = hook.getSubmission(submission);
		if (head.isEmpty() || !head.get().getOrg().equals(vk.org())) {
			throw new RelizaException("Submission not found: " + submission);
		}
		ReleaseData rd = sharedReleaseService.getReleaseData(head.get().getRelease(), vk.org())
				.orElseThrow(() -> new RelizaException("Submission not found: " + submission));
		authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(vk.principal(), PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
		return head.get();
	}

	/**
	 * The calling key verified and the instance operational, before anything the caller named is
	 * read (the TPF-17 order of the TEA fetcher).
	 */
	VerifiedProgrammaticKey verifiedKey(DgsDataFetchingEnvironment dfe, CallType ct) throws RelizaException {
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
}
