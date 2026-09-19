/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.CallType;
import org.apache.commons.lang3.StringUtils;

import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AttestationData;
import io.reliza.model.AttestationData.ActorType;
import io.reliza.model.AttestationData.SubjectType;
import io.reliza.model.AttestationData.Verdict;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentLock;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AttestationService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.BranchService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.dataloader.BatchLoader;
import org.dataloader.DataLoader;
import com.netflix.graphql.dgs.DgsDataLoader;
import io.reliza.service.CommitRecognitionService.ClaimState;
import java.time.ZonedDateTime;
import java.util.Comparator;
import io.reliza.model.AgentData;
import io.reliza.model.UserData;
import io.reliza.service.AgentService;
import io.reliza.service.CommitRecognitionService;
import io.reliza.service.CommitRecognitionService.Recognition;
import io.reliza.service.ComponentLockService;
import io.reliza.service.ComponentLockService.Located;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.PolicyLockHook;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

/**
 * Locks and attestations over GraphQL, both lanes.
 *
 * <p>The programmatic half matters as much as the user half here: the whole point of the
 * agent-unlockable lock is that an agent reads a refusal, claims the commit it broke, and gets
 * its own pipeline moving again. That only works if the mutation it needs is on the key lane.
 */
@Slf4j
@DgsComponent
public class IntegrityDataFetcher {

	@Autowired private AuthorizationService authorizationService;
	@Autowired private UserService userService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private ComponentLockService componentLockService;
	@Autowired private AttestationService attestationService;
	@Autowired private CommitRecognitionService commitRecognitionService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private AgentSessionService agentSessionService;

	/** Absent in CE: no rule raises a lock there, and no attestation releases one automatically. */
	@Autowired(required = false) private PolicyLockHook policyLockHook;

	// ---- Queries -----------------------------------------------------------------------------

	/**
	 * A lock plus where it lives.
	 *
	 * <p>Flat rather than a wrapper around the record: a lock is stored inside its component or
	 * branch row, so nothing in the record itself says which component it is about, and an
	 * organization-wide list without that is unreadable.
	 */
	public record LockView(UUID uuid, ComponentLock.Scope scope, UUID branch, String branchName,
			UUID component, String componentName, ComponentLock.Status status, String reason,
			ComponentLock.Origin origin, UUID outputEvent, List<ComponentLock.LockCause> causes,
			int droppedCauses, UnlockLevel unlockLevel, AttestationRequirement attestationRequirement,
			UnlockLevel escalatedLevel, UnlockLevel effectiveLevel,
			java.time.ZonedDateTime raisedAt, java.time.ZonedDateTime releasedAt,
			UUID releaseAttestation) {

		static LockView of(ComponentLock l, UUID component, String componentName, String branchName) {
			return new LockView(l.uuid(), l.scope(), l.branch(), branchName, component, componentName,
					l.status(), l.reason(), l.origin(), l.outputEvent(), l.causes(), l.droppedCauses(),
					l.unlockLevel(), l.attestationRequirement(), l.escalatedLevel(), l.effectiveLevel(),
					l.raisedAt(), l.releasedAt(), l.releaseAttestation());
		}
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "locks")
	public List<LockView> locks(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		authorizeOrg(orgUuid, CallType.READ);
		List<LockView> out = new ArrayList<>();
		for (ComponentData cd : componentService.listComponentDataByOrganization(orgUuid,
				ComponentType.COMPONENT, ComponentType.PRODUCT)) {
			if (cd.getLocks() != null) {
				cd.getLocks().forEach(l -> out.add(LockView.of(l, cd.getUuid(), cd.getName(), null)));
			}
			branchService.listBranchDataOfComponent(cd.getUuid(),
					io.reliza.common.CommonVariables.StatusEnum.ACTIVE).forEach(bd -> {
						if (bd.getLocks() == null) return;
						bd.getLocks().forEach(l ->
								out.add(LockView.of(l, cd.getUuid(), cd.getName(), bd.getName())));
					});
		}
		return out;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "attestations")
	public List<AttestationData> attestations(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("subjectType") SubjectType subjectType,
			@InputArgument("subjectUuid") UUID subjectUuid) throws RelizaException {
		authorizeOrg(orgUuid, CallType.READ);
		return attestationService.attestationsOfSubject(orgUuid, subjectType, subjectUuid);
	}

	/** What a lock is waiting on, gathered for the people who have to do something about it. */
	public record IntegrityInbox(List<LockView> activeLocks, List<UnrecognizedCommit> unrecognizedCommits,
			List<LockView> escalatedLocks) {}

	public record UnrecognizedCommit(UUID sourceCodeEntry, String commit, UUID component,
			String componentName, String detail, String claimState) {}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "integrityInbox")
	public IntegrityInbox integrityInbox(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		authorizeOrg(orgUuid, CallType.READ);
		List<LockView> active = new ArrayList<>();
		List<LockView> escalated = new ArrayList<>();
		List<UnrecognizedCommit> unrecognized = new ArrayList<>();
		for (Located located : componentLockService.listActive(orgUuid)) {
			ComponentLock lock = located.lock();
			String branchName = located.branchUuid() == null ? null
					: branchService.getBranchData(located.branchUuid()).map(bd -> bd.getName()).orElse(null);
			LockView view = LockView.of(lock, located.componentUuid(), located.componentName(), branchName);
			active.add(view);
			if (lock.escalatedLevel() != null) escalated.add(view);
			if (lock.causes() == null) continue;
			for (ComponentLock.LockCause cause : lock.causes()) {
				if (cause.subjectType() != ComponentLock.SubjectType.SCE) continue;
				Optional<SourceCodeEntryData> osce = getSourceCodeEntryService
						.getSourceCodeEntryData(cause.subjectUuid());
				if (osce.isEmpty()) continue;
				Recognition rec = commitRecognitionService.recognize(osce.get());
				if (rec.recognized()) continue;
				unrecognized.add(new UnrecognizedCommit(cause.subjectUuid(), osce.get().getCommit(),
						located.componentUuid(), located.componentName(), rec.detail(),
						rec.claimState() == null ? "NONE" : rec.claimState().name()));
			}
		}
		return new IntegrityInbox(active, unrecognized, escalated);
	}

	// ---- Field resolvers ---------------------------------------------------------------------

	// ---- User-lane mutations -----------------------------------------------------------------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "lockComponent")
	public LockView lockComponent(@InputArgument("componentUuid") UUID componentUuid,
			@InputArgument("reason") String reason,
			@InputArgument("unlockLevel") UnlockLevel unlockLevel,
			@InputArgument("attestationRequirement") AttestationRequirement requirement) throws RelizaException {
		WhoUpdated wu = authorizeComponent(componentUuid, CallType.ADMIN);
		ComponentLock lock = componentLockService.lockComponent(componentUuid, reason, unlockLevel,
				requirement, wu);
		return LockView.of(lock, componentUuid, componentName(componentUuid), null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "lockBranch")
	public LockView lockBranch(@InputArgument("branchUuid") UUID branchUuid,
			@InputArgument("reason") String reason,
			@InputArgument("unlockLevel") UnlockLevel unlockLevel,
			@InputArgument("attestationRequirement") AttestationRequirement requirement) throws RelizaException {
		UUID componentUuid = branchService.getBranchData(branchUuid)
				.orElseThrow(() -> new RelizaException("Branch not found: " + branchUuid)).getComponent();
		WhoUpdated wu = authorizeComponent(componentUuid, CallType.ADMIN);
		ComponentLock lock = componentLockService.lockBranch(branchUuid, reason, unlockLevel, requirement, wu);
		return LockView.of(lock, componentUuid, componentName(componentUuid),
				branchService.getBranchData(branchUuid).map(bd -> bd.getName()).orElse(null));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "releaseLock")
	public LockView releaseLock(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("lockUuid") UUID lockUuid,
			@InputArgument("reason") String reason,
			@InputArgument("override") Boolean override) throws RelizaException {
		var oud = currentUser();
		Located located = componentLockService.locate(orgUuid, lockUuid)
				.orElseThrow(() -> new RelizaException("Lock not found: " + lockUuid));
		boolean asOverride = Boolean.TRUE.equals(override);
		// The effective level decides the tier, and an override is always an administrator's act
		// regardless of what the lock says -- that is what makes it an override.
		CallType required = asOverride || located.lock().effectiveLevel() == UnlockLevel.ADMIN
				? CallType.ADMIN : CallType.WRITE;
		WhoUpdated wu = authorizeComponent(located.componentUuid(), required);
		if (asOverride && (reason == null || reason.isBlank())) {
			throw new RelizaException("An override needs a reason");
		}
		ComponentLock released = componentLockService.releaseLock(orgUuid, lockUuid, reason, asOverride,
				ActorType.USER, oud.getUuid(), null, wu);
		return LockView.of(released, located.componentUuid(), located.componentName(),
				located.branchUuid() == null ? null
						: branchService.getBranchData(located.branchUuid()).map(bd -> bd.getName()).orElse(null));
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional
	@DgsData(parentType = "Mutation", field = "attest")
	public AttestationData attest(@InputArgument("subjectType") SubjectType subjectType,
			@InputArgument("subjectUuid") UUID subjectUuid,
			@InputArgument("verdict") Verdict verdict,
			@InputArgument("note") String note) throws RelizaException {
		var oud = currentUser();
		UUID componentUuid = componentOfSubject(subjectType, subjectUuid);
		WhoUpdated wu = authorizeComponent(componentUuid, CallType.WRITE);
		UUID orgUuid = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found")).getOrg();
		AttestationData att = writeAcknowledgement(orgUuid, componentUuid, subjectType, subjectUuid,
				verdict, note, ActorType.USER, oud.getUuid(), null, wu);
		return att;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "revokeAttestation")
	public AttestationData revokeAttestation(@InputArgument("uuid") UUID uuid,
			@InputArgument("reason") String reason) throws RelizaException {
		var oud = currentUser();
		AttestationData existing = attestationService.getAttestationData(uuid)
				.orElseThrow(() -> new RelizaException("Attestation not found: " + uuid));
		OrganizationData od = getOrganizationService.getOrganizationData(existing.getOrg())
				.orElseThrow(() -> new RelizaException("Org not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, existing.getOrg(), List.of((RelizaObject) od), CallType.WRITE);
		return attestationService.revoke(uuid, oud.getUuid(), reason, WhoUpdated.getWhoUpdated(oud));
	}

	// ---- Programmatic mutations --------------------------------------------------------------

	@Transactional
	@DgsData(parentType = "Mutation", field = "attestProgrammatic")
	@SuppressWarnings("unchecked")
	public AttestationData attestProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> componentRef = dfe.getArgument("componentRef");
		String commit = dfe.getArgument("commit");
		String verdictRaw = dfe.getArgument("verdict");
		String note = dfe.getArgument("note");
		UUID sessionUuid = UUID.fromString((String) dfe.getArgument("session"));

		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		// The session is the actor, so the key has to own it. Without this check any agent key in
		// the organization could file claims in another agent's name, which would leave the
		// record saying something untrue -- the one thing an attestation must not do.
		WhoUpdated wu = authorizeProgrammaticSession(dfe, sessionUuid);
		if (sd.getStatus() != null && !"OPEN".equals(sd.getStatus().name())) {
			throw new RelizaException("Session " + sessionUuid + " is " + sd.getStatus()
					+ "; file the attestation under an open session");
		}
		UUID componentUuid = componentService.resolveComponentIdFromInput(
				(Map<String, Object>) (Map<?, ?>) componentRef, programmaticContext(dfe));
		SourceCodeEntryData sced = resolveCommit(componentUuid, commit);
		return writeAcknowledgement(sd.getOrg(), componentUuid, SubjectType.SCE, sced.getUuid(),
				Verdict.valueOf(verdictRaw), note, ActorType.AGENT, sd.getAgent(), sessionUuid, wu);
	}

	@DgsData(parentType = "Mutation", field = "releaseLockProgrammatic")
	public LockView releaseLockProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UUID lockUuid = UUID.fromString((String) dfe.getArgument("lockUuid"));
		String reason = dfe.getArgument("reason");
		UUID sessionUuid = UUID.fromString((String) dfe.getArgument("session"));
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid)
				.orElseThrow(() -> new RelizaException("Session not found: " + sessionUuid));
		// Named for the same reason attest names it: the LOCK_RELEASE row is the durable record
		// of who released the lock, and "some key in the org" is not an answer.
		WhoUpdated wu = authorizeProgrammaticSession(dfe, sessionUuid);
		UUID orgUuid = sd.getOrg();
		Located located = componentLockService.locate(orgUuid, lockUuid)
				.orElseThrow(() -> new RelizaException("Lock not found: " + lockUuid));
		// A key may only release what a key is allowed to release. Anything a person has to sign
		// off on says so, rather than quietly succeeding for whoever holds the key.
		if (located.lock().effectiveLevel() != UnlockLevel.AGENT) {
			// Refused, not unauthorized. The GraphQL handler deliberately flattens
			// AccessDeniedException to a bare "Not authorized" so that permission checks cannot
			// leak what exists -- which turned this, the one message that should tell an agent
			// exactly why it may not proceed, into the least informative answer in the feature.
			throw new ActionRefusedException("Lock " + lockUuid + " needs "
					+ located.lock().effectiveLevel() + " to release, so a key cannot do it"
					+ (located.lock().escalatedLevel() != null
							? " (raised from " + located.lock().unlockLevel()
									+ " because a cause is disowned or contested)" : "")
					+ ". Ask " + (located.lock().effectiveLevel() == UnlockLevel.ADMIN
							? "an administrator" : "a person") + " to release it.");
		}
		ComponentLock released = componentLockService.releaseLock(orgUuid, lockUuid, reason, false,
				ActorType.AGENT, sd.getAgent(), sessionUuid, wu);
		return LockView.of(released, located.componentUuid(), located.componentName(), null);
	}

	// ---- Shared internals --------------------------------------------------------------------

	/**
	 * Write the acknowledgement, then let the policy side act on it. Order matters: the
	 * requirement is evaluated from stored rows, so a lock re-evaluated before the write would
	 * see the claim missing and stay up until something else nudged it.
	 *
	 * <p>Both in one transaction, which is why the mutations carry {@code @Transactional}: they
	 * are one act as far as the caller is concerned, and an attestation that persisted while the
	 * release it should have triggered failed would leave a lock up with nothing to retry it.
	 */
	private AttestationData writeAcknowledgement(UUID orgUuid, UUID componentUuid, SubjectType subjectType,
			UUID subjectUuid, Verdict verdict, String note, ActorType actorType, UUID actorUuid,
			UUID sessionUuid, WhoUpdated wu) throws RelizaException {
		String commitSha = null;
		String version = null;
		if (subjectType == SubjectType.SCE) {
			commitSha = getSourceCodeEntryService.getSourceCodeEntryData(subjectUuid)
					.map(SourceCodeEntryData::getCommit).orElse(null);
			if (commitSha == null) throw new RelizaException("Commit not found: " + subjectUuid);
		} else if (subjectType == SubjectType.RELEASE) {
			version = sharedReleaseService.getReleaseData(subjectUuid)
					.orElseThrow(() -> new RelizaException("Release not found: " + subjectUuid)).getVersion();
		} else {
			throw new RelizaException("A commit acknowledgement is about a commit or a release");
		}
		String componentName = getComponentService.getComponentData(componentUuid)
				.map(ComponentData::getName).orElse(null);
		AttestationData att = attestationService.acknowledgeCommit(orgUuid, subjectType, subjectUuid,
				AttestationData.SubjectRef.of(componentUuid, componentName, commitSha, version),
				actorType, actorUuid, sessionUuid, verdict, note, wu);
		if (policyLockHook != null) {
			int released = policyLockHook.onAttestation(att);
			if (released > 0) log.info("Attestation {} released {} lock(s)", att.getUuid(), released);
		}
		return att;
	}

	/** A sha ReARM has never seen is refused: a commit that does not exist cannot be a cause. */
	private SourceCodeEntryData resolveCommit(UUID componentUuid, String commit) throws RelizaException {
		ComponentData cd = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		if (cd.getVcs() == null) {
			throw new RelizaException("Component " + cd.getName() + " has no VCS repository, so a commit "
					+ "cannot be resolved against it");
		}
		List<SourceCodeEntry> found = getSourceCodeEntryService
				.getSourceCodeEntriesByVcsAndCommits(cd.getVcs(), List.of(commit));
		if (found.isEmpty()) {
			throw new RelizaException("ReARM has no record of commit " + commit + " on component "
					+ cd.getName() + ", so there is nothing to attest to");
		}
		return SourceCodeEntryData.dataFromRecord(found.get(0));
	}

	private UUID componentOfSubject(SubjectType subjectType, UUID subjectUuid) throws RelizaException {
		if (subjectType == SubjectType.SCE) {
			return getSourceCodeEntryService.getSourceCodeEntryData(subjectUuid)
					.map(sced -> sced.getBranch())
					.flatMap(branchService::getBranchData)
					.map(bd -> bd.getComponent())
					.orElseThrow(() -> new RelizaException("Cannot resolve the component of commit " + subjectUuid));
		}
		if (subjectType == SubjectType.RELEASE) {
			return sharedReleaseService.getReleaseData(subjectUuid)
					.orElseThrow(() -> new RelizaException("Release not found: " + subjectUuid)).getComponent();
		}
		throw new RelizaException("Unsupported subject for this operation: " + subjectType);
	}

	private String componentName(UUID componentUuid) {
		return getComponentService.getComponentData(componentUuid).map(ComponentData::getName).orElse(null);
	}

	private io.reliza.model.UserData currentUser() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth)
				.orElseThrow(() -> new AccessDeniedException("Not authorized"));
	}

	private void authorizeOrg(UUID orgUuid, CallType ct) throws RelizaException {
		var oud = currentUser();
		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found: " + orgUuid));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of((RelizaObject) od), ct);
	}

	private WhoUpdated authorizeComponent(UUID componentUuid, CallType ct) throws RelizaException {
		var oud = currentUser();
		ComponentData cd = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, componentUuid, List.of((RelizaObject) cd), ct);
		return WhoUpdated.getWhoUpdated(oud);
	}

	private ProgrammaticAuthContext programmaticContext(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		return authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(), servletWebRequest);
	}

	/**
	 * The calling key holds AGENT on the org AND owns the session it is acting as. Both, because
	 * the first says it may act as an agent at all and the second says which one.
	 */
	private WhoUpdated authorizeProgrammaticSession(DgsDataFetchingEnvironment dfe, UUID sessionUuid)
			throws RelizaException {
		ProgrammaticAuthContext authCtx = programmaticContext(dfe);
		var ahp = authCtx.ahp();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		if (!ahp.isRbacKey()) throw new AccessDeniedException("An RBAC key is required for this operation");
		authorizeProgrammaticAgent(dfe, authCtx.orgUuid());
		return authorizationService.assertFreeformKeyOwnsSession(ahp, sessionUuid).whoUpdated();
	}

	private WhoUpdated authorizeProgrammaticAgent(DgsDataFetchingEnvironment dfe, UUID orgUuid)
			throws RelizaException {
		ProgrammaticAuthContext authCtx = programmaticContext(dfe);
		var ahp = authCtx.ahp();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		if (!ahp.isRbacKey()) throw new AccessDeniedException("An RBAC key is required for this operation");
		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found"));
		FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
				ahp, PermissionFunction.AGENT, PermissionScope.ORGANIZATION, orgUuid,
				List.of((RelizaObject) od), CallType.ESSENTIAL_READ);
		return fkv.whoUpdated();
	}

	// ---- SourceCodeEntry field resolvers ------------------------------------------------------

	/**
	 * Whether anybody can be held to this commit, on {@code SourceCodeEntry.recognized}.
	 *
	 * <p>The schema declared this field and nothing resolved it, so every commit row in the UI
	 * read "unclaimed" however many attestations it had -- DGS returns null for a field with no
	 * resolver and no matching property, and null is falsy.
	 *
	 * <p>Batched: a release page asks for this once per commit, and recognition costs three reads
	 * (attestations, signatures, blocked sessions). The loader collapses a table's worth of rows
	 * into one round of those reads per organization.
	 */
	@DgsData(parentType = "SourceCodeEntry", field = "recognized")
	public CompletionStage<Boolean> sceRecognized(DgsDataFetchingEnvironment dfe) {
		SourceCodeEntryData sce = dfe.getSource();
		if (sce == null || sce.getUuid() == null) return CompletableFuture.completedFuture(false);
		DataLoader<RecognitionKey, CommitStanding> loader = dfe.getDataLoader(RECOGNITION_LOADER);
		return loader.load(new RecognitionKey(sce.getOrg(), sce.getUuid()))
				.thenApply(st -> st != null && st.recognition().recognized());
	}

	/** What the attestations say about this commit, on {@code SourceCodeEntry.attestation}. */
	@DgsData(parentType = "SourceCodeEntry", field = "attestation")
	public CompletionStage<CommitAttestationView> sceAttestation(DgsDataFetchingEnvironment dfe) {
		SourceCodeEntryData sce = dfe.getSource();
		if (sce == null || sce.getUuid() == null) return CompletableFuture.completedFuture(null);
		DataLoader<RecognitionKey, CommitStanding> loader = dfe.getDataLoader(RECOGNITION_LOADER);
		return loader.load(new RecognitionKey(sce.getOrg(), sce.getUuid()))
				.thenApply(st -> st == null ? null : st.attestation());
	}

	/** Matches the {@code CommitAttestation} type; the claim, separate from signature and attribution. */
	public record CommitAttestationView(String state, String actorType, UUID actor, String actorName,
			ZonedDateTime claimedAt, String detail, List<CommitClaimView> claims) {}

	/** Matches {@code CommitClaim}: one principal's standing statement, with the row to revoke. */
	public record CommitClaimView(UUID uuid, String verdict, String actorType, UUID actor,
			String actorName, String note, ZonedDateTime createdDate) {}

	/** Recognition plus the standing claims behind it, which is what the read surfaces need. */
	public record CommitStanding(Recognition recognition, CommitAttestationView attestation) {}

	public record RecognitionKey(UUID org, UUID sce) {}

	static final String RECOGNITION_LOADER = "commitRecognitionLoader";

	/**
	 * One round of recognition reads per organization per request, however many commits the query
	 * names. Keys carry the org because recognition is scoped to it and a single query can span
	 * organizations in principle.
	 */
	@DgsDataLoader(name = RECOGNITION_LOADER)
	public class RecognitionBatchLoader implements BatchLoader<RecognitionKey, CommitStanding> {

		@Autowired private GetSourceCodeEntryService loaderSceService;
		@Autowired private CommitRecognitionService loaderRecognitionService;
		@Autowired private AttestationService loaderAttestationService;
		@Autowired private UserService loaderUserService;
		@Autowired private AgentService loaderAgentService;

		@Override
		public CompletionStage<List<CommitStanding>> load(List<RecognitionKey> keys) {
			Map<UUID, CommitStanding> resolved = new HashMap<>();
			Map<UUID, Set<UUID>> byOrg = new LinkedHashMap<>();
			for (RecognitionKey k : keys) {
				if (k.org() == null || k.sce() == null) continue;
				byOrg.computeIfAbsent(k.org(), o -> new LinkedHashSet<>()).add(k.sce());
			}
			for (Map.Entry<UUID, Set<UUID>> e : byOrg.entrySet()) {
				try {
					resolved.putAll(standingsFor(e.getKey(), e.getValue()));
				} catch (Exception ex) {
					log.error("Could not resolve commit recognition for org {}: {}", e.getKey(),
							ex.getMessage(), ex);
				}
			}
			List<CommitStanding> out = new ArrayList<>(keys.size());
			for (RecognitionKey k : keys) {
				CommitStanding st = resolved.get(k.sce());
				out.add(st != null ? st : new CommitStanding(
						Recognition.of(false, ClaimState.NONE, "unknown commit"), null));
			}
			return CompletableFuture.completedFuture(out);
		}

		private Map<UUID, CommitStanding> standingsFor(UUID org, Set<UUID> sceUuids) {
			List<SourceCodeEntryData> sces = loaderSceService.getSourceCodeEntryDataList(sceUuids);
			Map<UUID, Recognition> recognition = loaderRecognitionService.recognizeAll(org, sces);
			Map<UUID, List<AttestationData>> attestations = loaderAttestationService
					.attestationsBySubjects(org, AttestationData.SubjectType.SCE, List.copyOf(sceUuids));
			// One name lookup per distinct principal across the whole batch, not per claim: a
			// release page with twenty commits claimed by the same two people asks twice.
			Map<UUID, String> names = new HashMap<>();
			for (List<AttestationData> list : attestations.values()) {
				for (AttestationData a : loaderRecognitionService.latestPerActor(list)) {
					names.computeIfAbsent(a.getActorUuid(), u -> nameOf(a.getActorType(), u, org));
				}
			}
			Map<UUID, CommitStanding> out = new HashMap<>();
			for (UUID sceUuid : sceUuids) {
				Recognition r = recognition.getOrDefault(sceUuid,
						Recognition.of(false, ClaimState.NONE, "unknown commit"));
				List<AttestationData> rows = attestations.getOrDefault(sceUuid, List.of());
				List<CommitClaimView> claims = new ArrayList<>();
				for (AttestationData a : loaderRecognitionService.latestPerActor(rows)) {
					claims.add(new CommitClaimView(a.getUuid(),
							a.getVerdict() == null ? null : a.getVerdict().name(),
							a.getActorType() == null ? null : a.getActorType().name(),
							a.getActorUuid(), names.get(a.getActorUuid()), a.getNote(),
							a.getCreatedDate()));
				}
				claims.sort(Comparator.comparing(CommitClaimView::createdDate,
						Comparator.nullsLast(Comparator.reverseOrder())));
				AttestationData standing = loaderRecognitionService.standingClaim(rows).orElse(null);
				out.put(sceUuid, new CommitStanding(r, new CommitAttestationView(
						r.claimState() == null ? ClaimState.NONE.name() : r.claimState().name(),
						r.claimActorType() == null ? null : r.claimActorType().name(),
						r.claimActor(),
						r.claimActor() == null ? null : names.get(r.claimActor()),
						standing == null ? null : standing.getCreatedDate(),
						r.detail(), claims)));
			}
			return out;
		}

		/**
		 * Who a principal is, for a reader: the agent's name, or a user's name falling back to
		 * their email in this organization. Users routinely have no display name set, and
		 * "claimed" with nobody attached is the thing being fixed here -- an email identifies a
		 * person, a null does not.
		 */
		private String nameOf(AttestationData.ActorType type, UUID actor, UUID org) {
			if (actor == null) return null;
			try {
				if (type == AttestationData.ActorType.AGENT) {
					return loaderAgentService.getAgentData(actor).map(AgentData::getName).orElse(null);
				}
				return loaderUserService.getUserData(actor)
						.map(ud -> StringUtils.defaultIfEmpty(ud.getName(), ud.getEmail(org)))
						.orElse(null);
			} catch (Exception e) {
				log.error("Could not resolve the name of principal {}: {}", actor, e.getMessage(), e);
				return null;
			}
		}
	}
}
