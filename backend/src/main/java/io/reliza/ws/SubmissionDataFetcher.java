/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
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

import lombok.extern.slf4j.Slf4j;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SubmissionRefusedException;
import io.reliza.model.ComponentData;
import io.reliza.model.NewVersionReason;
import io.reliza.model.PackageKind;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.SubmissionActData;
import io.reliza.model.SubmissionActKind;
import io.reliza.model.SubmissionActResult;
import io.reliza.model.SubmissionData;
import io.reliza.model.SubmissionData.VersionState;
import io.reliza.model.SubmissionDraftData;
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
 * object's organization. The child resolvers run no check of their own: the field that answered
 * their parent authorized it. A draft is not for a key: the three child resolvers a key can reach
 * (Release.drafts, Submission.acts, Release.updateEvents) leave a draft out unless the caller is a
 * signed-in user (FDAW-29 3.3b). With no hook (CE) the release- and component-keyed fields still run the
 * full check and answer empty lists or EDITION_UNSUPPORTED; the submission-keyed ones, which cannot
 * resolve their object, answer null or EDITION_UNSUPPORTED after authentication alone.
 */
@Slf4j
@DgsComponent
public class SubmissionDataFetcher {

	static final String PRO_ONLY = "regulatory submissions are a ReARM Pro feature";
	static final String DRAFT_NOT_FOUND = "Draft not found";
	static final String REPLACES_NOT_FOUND = "replaces: submission not found";

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

	/**
	 * The package kinds switched on for a component (the product tab offers Create only for these);
	 * the field that answered the component authorized it, as for its release twin (no annotation of its
	 * own: a component a key read answers it too). CE: none.
	 */
	@DgsData(parentType = "Component", field = "regulatoryPackageKinds")
	public List<PackageKind> componentRegulatoryPackageKinds(DgsDataFetchingEnvironment dfe) {
		return regulatoryKindsOf(dfe.<ComponentData>getSource());
	}

	List<PackageKind> regulatoryKindsOf(ComponentData cd) {
		return null == hook || null == cd ? List.of() : hook.regulatoryKindsOfComponent(cd);
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

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "draft")
	public SubmissionDraftData draft(@InputArgument("uuid") UUID uuid) throws RelizaException {
		return draft(currentUser(), uuid);
	}

	SubmissionDraftData draft(UserData ud, UUID uuid) throws RelizaException {
		if (null == hook) return null;
		return readableDraft(ud, uuid);
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

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "startDraft")
	public SubmissionActResult startDraft(@InputArgument("release") UUID release,
			@InputArgument("kind") PackageKind kind, @InputArgument("reasonCategory") NewVersionReason reasonCategory,
			@InputArgument("reasonText") String reasonText, @InputArgument("replacesSubmission") UUID replacesSubmission,
			@InputArgument("replacesVersion") Integer replacesVersion) throws RelizaException {
		return startDraft(currentUser(), release, kind, reasonCategory, reasonText, replacesSubmission, replacesVersion);
	}

	SubmissionActResult startDraft(UserData ud, UUID release, PackageKind kind, NewVersionReason reasonCategory,
			String reasonText, UUID replacesSubmission, Integer replacesVersion) throws RelizaException {
		ReleaseData rd = releaseOf(ud, release);
		ComponentData cd = componentOfRelease(rd);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.WRITE);
		if (null == hook) {
			throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, rd.getUuid(), "Release "
					+ cd.getName() + " " + rd.getVersion() + " (" + rd.getUuid() + "): " + PRO_ONLY);
		}
		if (null != replacesSubmission || null != replacesVersion) {
			replaceableHead(ud, rd, replacesSubmission, replacesVersion);
		}
		return hook.startDraft(rd.getUuid(), kind, reasonCategory, reasonText, replacesSubmission, replacesVersion,
				WhoUpdated.getWhoUpdated(ud));
	}

	/**
	 * A replaces pair names a head the caller may write, of the release's organization and component.
	 * Missing, foreign, not permitted and other-component targets answer one text, so the refusal says
	 * nothing about another component's heads (FDAW-1 F-21, F-23). The version's own state is the
	 * service's check, under the lease.
	 */
	private void replaceableHead(UserData ud, ReleaseData rd, UUID submission, Integer version)
			throws SubmissionRefusedException {
		SubmissionData head;
		try {
			head = null == submission || null == version ? null : writableHead(ud, submission);
		} catch (RelizaException | AccessDeniedException e) {
			head = null;
		}
		if (null == head || !rd.getOrg().equals(head.getOrg()) || !rd.getComponent().equals(head.getComponent())) {
			throw new SubmissionRefusedException(SubmissionRefusal.REPLACES_NOT_FOUND, rd.getUuid(), REPLACES_NOT_FOUND);
		}
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "discardDraft")
	public SubmissionActResult discardDraft(@InputArgument("draft") UUID draft, @InputArgument("reason") String reason)
			throws RelizaException {
		return discardDraft(currentUser(), draft, reason);
	}

	SubmissionActResult discardDraft(UserData ud, UUID draft, String reason) throws RelizaException {
		if (null == hook) throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, null, PRO_ONLY);
		SubmissionDraftData d = writableDraft(ud, draft);
		return hook.discardDraft(d.getUuid(), reason, WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "confirmDraft")
	public SubmissionActResult confirmDraft(@InputArgument("draft") UUID draft,
			@InputArgument("expectedRevision") Integer expectedRevision) throws RelizaException {
		return confirmDraft(currentUser(), draft, expectedRevision);
	}

	SubmissionActResult confirmDraft(UserData ud, UUID draft, int expectedRevision) throws RelizaException {
		if (null == hook) throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, null, PRO_ONLY);
		SubmissionDraftData d = writableDraft(ud, draft);
		return hook.confirmDraft(d.getUuid(), expectedRevision, WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "returnDraftToEditing")
	public SubmissionActResult returnDraftToEditing(@InputArgument("draft") UUID draft,
			@InputArgument("reason") String reason) throws RelizaException {
		return returnDraftToEditing(currentUser(), draft, reason);
	}

	SubmissionActResult returnDraftToEditing(UserData ud, UUID draft, String reason) throws RelizaException {
		if (null == hook) throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, null, PRO_ONLY);
		SubmissionDraftData d = writableDraft(ud, draft);
		return hook.returnDraftToEditing(d.getUuid(), reason, WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "cancelSubmission")
	public SubmissionActResult cancelSubmission(@InputArgument("submission") UUID submission,
			@InputArgument("reason") String reason) throws RelizaException {
		return cancelSubmission(currentUser(), submission, reason);
	}

	SubmissionActResult cancelSubmission(UserData ud, UUID submission, String reason) throws RelizaException {
		if (null == hook) throw new SubmissionRefusedException(SubmissionRefusal.EDITION_UNSUPPORTED, null, PRO_ONLY);
		SubmissionData head = writableHead(ud, submission);
		return hook.cancelSubmission(head.getOrg(), head.getUuid(), reason, WhoUpdated.getWhoUpdated(ud));
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

	/** The release's drafts, for a signed-in user only: a key is told nothing of a draft, and the hook is not asked. */
	@DgsData(parentType = "Release", field = "drafts")
	public List<SubmissionDraftData> draftsOfReleaseField(DgsDataFetchingEnvironment dfe) {
		return draftsOf(dfe.<ReleaseData>getSource());
	}

	List<SubmissionDraftData> draftsOf(ReleaseData rd) {
		if (null == hook || null == rd || !signedInUser()) return List.of();
		return hook.listDraftsOfRelease(rd.getOrg(), rd.getUuid());
	}

	/** The release's update events; for anyone but a signed-in user, without a draft's own. */
	@DgsData(parentType = "Release", field = "updateEvents")
	public List<ReleaseUpdateEvent> updateEventsOfReleaseField(DgsDataFetchingEnvironment dfe) {
		return updateEventsOf(dfe.<ReleaseData>getSource());
	}

	List<ReleaseUpdateEvent> updateEventsOf(ReleaseData rd) {
		if (null == rd || null == rd.getUpdateEvents()) return null;
		if (signedInUser()) return rd.getUpdateEvents();
		return rd.getUpdateEvents().stream().filter(e -> !SubmissionDraftData.isDraftEvent(e)).toList();
	}

	/** The draft's act record, oldest first; a child of a draft the caller already read. */
	@DgsData(parentType = "SubmissionDraft", field = "acts")
	public List<SubmissionActData> actsOfDraftField(DgsDataFetchingEnvironment dfe) {
		return actsOfDraft(dfe.<SubmissionDraftData>getSource());
	}

	List<SubmissionActData> actsOfDraft(SubmissionDraftData d) {
		if (null == hook || null == d) return List.of();
		return hook.listActsOfDraft(d.getOrg(), d.getUuid());
	}

	@DgsData(parentType = "Submission", field = "versions")
	public List<SubmissionVersionData> versions(DgsDataFetchingEnvironment dfe) {
		return versionsOf(dfe.<SubmissionData>getSource());
	}

	List<SubmissionVersionData> versionsOf(SubmissionData s) {
		if (null == hook || null == s) return List.of();
		return hook.listVersions(s.getOrg(), s.getUuid());
	}

	/** Each version's state: the head's stored list, or the one a migrated head implies (FDAW-1 D2, D6). */
	@DgsData(parentType = "Submission", field = "versionStates")
	public List<VersionState> versionStates(DgsDataFetchingEnvironment dfe) {
		return versionStatesOf(dfe.<SubmissionData>getSource());
	}

	List<VersionState> versionStatesOf(SubmissionData s) {
		if (null == hook || null == s) return List.of();
		return s.versionStatesOrDerived();
	}

	/**
	 * The head's act record, oldest first; a child of a head the caller already read (FDAW-1 D2). For
	 * anyone but a signed-in user, without the acts of a draft in progress.
	 */
	@DgsData(parentType = "Submission", field = "acts")
	public List<SubmissionActData> acts(DgsDataFetchingEnvironment dfe) {
		return actsOf(dfe.<SubmissionData>getSource());
	}

	List<SubmissionActData> actsOf(SubmissionData s) {
		if (null == hook || null == s) return List.of();
		List<SubmissionActData> acts = hook.listActs(s.getOrg(), s.getUuid());
		if (signedInUser()) return acts;
		return acts.stream().filter(a -> !SubmissionActKind.DRAFT_LANE.contains(a.getKind())).toList();
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

	@DgsData(parentType = "Submission", field = "createdByName")
	public String createdByName(DgsDataFetchingEnvironment dfe) {
		SubmissionData s = dfe.getSource();
		return null == s ? null : userNameOf(s.getCreatedBy(), s.getOrg());
	}

	@DgsData(parentType = "Submission", field = "lastActByName")
	public String lastActByName(DgsDataFetchingEnvironment dfe) {
		SubmissionData s = dfe.getSource();
		return null == s ? null : userNameOf(s.getLastActBy(), s.getOrg());
	}

	@DgsData(parentType = "Submission", field = "withdrawnByName")
	public String withdrawnByName(DgsDataFetchingEnvironment dfe) {
		SubmissionData s = dfe.getSource();
		return null == s ? null : userNameOf(s.getWithdrawnBy(), s.getOrg());
	}

	@DgsData(parentType = "SubmissionVersion", field = "createdByName")
	public String versionCreatedByName(DgsDataFetchingEnvironment dfe) {
		SubmissionVersionData v = dfe.getSource();
		return null == v ? null : userNameOf(v.getCreatedBy(), v.getOrg());
	}

	/**
	 * Who acted, for a reader (task FDAW-4, B14): a current active member of the organization answers
	 * their name, else their email there; anyone else (removed, deactivated, unknown) answers their
	 * stored name or null, never an email. The email is read only from the membership-checked record:
	 * {@code UserData.getEmail(org)} falls back to the primary email, so it is not the boundary.
	 */
	String userNameOf(UUID user, UUID org) {
		if (null == user) return null;
		try {
			Optional<UserData> member = null == org ? Optional.empty() : userService.getUserDataWithOrg(user, org);
			if (member.isPresent()) {
				return StringUtils.defaultIfEmpty(member.get().getName(), member.get().getEmail(org));
			}
			return userService.getUserData(user).map(UserData::getName).filter(StringUtils::isNotEmpty).orElse(null);
		} catch (Exception e) {
			log.error("Could not resolve the name of user {} on a submission: {}", user, e.getMessage(), e);
			return null;
		}
	}

	// ------------------------------------------------------------------ helpers

	UserData currentUser() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new RelizaException("User not found"));
	}

	/**
	 * Whether the caller is a signed-in user. The programmatic chain never puts a
	 * {@link JwtAuthenticationToken} in the context and a lost context reads as no user, so every filter
	 * on this fails closed.
	 */
	private static boolean signedInUser() {
		return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken;
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

	/** The draft by uuid (by uuid alone, as the head), one refusal when it is missing or the user is not in its organization. */
	private SubmissionDraftData draftOf(UserData ud, UUID draft) throws SubmissionRefusedException {
		Optional<SubmissionDraftData> d = null == draft ? Optional.empty() : hook.getDraft(draft);
		if (d.isEmpty() || !ud.isInOrganization(d.get().getOrg())) throw draftNotFound();
		return d.get();
	}

	private static SubmissionRefusedException draftNotFound() {
		return new SubmissionRefusedException(SubmissionRefusal.DRAFT_NOT_FOUND, null, DRAFT_NOT_FOUND);
	}

	/** RELEASE READ on the draft's release, as {@link #readableHead}. */
	private SubmissionDraftData readableDraft(UserData ud, UUID draft) throws RelizaException {
		SubmissionDraftData d = draftOf(ud, draft);
		ReleaseData rd = sharedReleaseService.getReleaseData(d.getRelease()).orElseThrow(
				SubmissionDataFetcher::draftNotFound);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				rd.getUuid(), List.of(rd), CallType.READ);
		return d;
	}

	/** COMPONENT WRITE on the draft's component, as {@link #writableHead}. */
	private SubmissionDraftData writableDraft(UserData ud, UUID draft) throws RelizaException {
		SubmissionDraftData d = draftOf(ud, draft);
		ComponentData cd = getComponentService.getComponentData(d.getComponent()).orElseThrow(
				SubmissionDataFetcher::draftNotFound);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.WRITE);
		return d;
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
