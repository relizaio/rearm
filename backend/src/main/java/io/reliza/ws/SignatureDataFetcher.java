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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityData;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.CommitterData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SignatureVerificationData;
import io.reliza.model.SignatureVerificationData.SignatureSubjectType;
import io.reliza.model.SigningKeyData;
import io.reliza.model.SigningKeyData.SigningKeyOwnerType;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.CommitterService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SignatureVerificationService;
import io.reliza.service.SigningKeyService;
import io.reliza.service.UserService;
import io.reliza.service.SignatureVerifier;

/**
 * GraphQL surface for committer / signing-key / signature-verification
 * entities. Queries and most mutations are user-authenticated. The
 * one programmatic surface, {@code enrollSigningKeyProgrammatic}, lets
 * an AI agent self-enrol its own SSH/GPG public key on first run via
 * its FREEFORM AGENT key — restricted to {@code ownerType=AGENT} and
 * {@code ownerUuid} = the root agent resolved from the calling key.
 * Operator-driven JWT enrolment remains the path for COMMITTER and
 * for any cross-agent operations.
 */
@DgsComponent
public class SignatureDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private CommitterService committerService;

	@Autowired
	private SigningKeyService signingKeyService;

	@Autowired
	private SignatureVerificationService signatureVerificationService;

	@Autowired
	private AgentService agentService;

	@Autowired
	private SignatureVerifier signatureVerifier;

	@Autowired
	private AgentIdentityService agentIdentityService;

	// ----- Queries -----

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "committer")
	public CommitterData committer(@InputArgument("uuid") UUID uuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<CommitterData> ocd = committerService.getCommitterData(uuid);
		RelizaObject ro = ocd.orElse(null);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
		return ocd.orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "committersOfOrg")
	public List<CommitterData> committersOfOrg(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(od.orElse(null)), CallType.READ);
		return committerService.listByOrg(orgUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "signingKey")
	public SigningKeyData signingKey(@InputArgument("uuid") UUID uuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<SigningKeyData> okd = signingKeyService.getSigningKeyData(uuid);
		RelizaObject ro = okd.orElse(null);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
		return okd.orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "signingKeysOfOwner")
	public List<SigningKeyData> signingKeysOfOwner(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("ownerType") SigningKeyOwnerType ownerType,
			@InputArgument("ownerUuid") UUID ownerUuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(od.orElse(null)), CallType.READ);
		return signingKeyService.listByOwner(orgUuid, ownerType, ownerUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "signingKeysOfOrg")
	public List<SigningKeyData> signingKeysOfOrg(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(od.orElse(null)), CallType.READ);
		return signingKeyService.listByOrg(orgUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "signatureVerification")
	public SignatureVerificationData signatureVerification(@InputArgument("uuid") UUID uuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<SignatureVerificationData> ovd = signatureVerificationService.getData(uuid);
		RelizaObject ro = ovd.orElse(null);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.READ);
		return ovd.orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "signatureVerificationsForSubject")
	public List<SignatureVerificationData> signatureVerificationsForSubject(
			@InputArgument("subjectType") SignatureSubjectType subjectType,
			@InputArgument("subjectUuid") UUID subjectUuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		List<SignatureVerificationData> verdicts = signatureVerificationService.listBySubject(subjectType, subjectUuid);
		// Empty list = no verdicts for this subject yet; no read happens
		// so no auth check is needed (would otherwise FORBID on null org).
		if (verdicts.isEmpty()) return verdicts;
		// Authorize against the verdicts themselves — getMatchingOrg(ros)
		// walks every entry and requires a non-null shared org, so a
		// cross-org leak via mixed verdicts is forbidden by construction.
		UUID orgUuid = verdicts.get(0).getOrg();
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, new java.util.ArrayList<>(verdicts), CallType.READ);
		return verdicts;
	}

	// ----- Field resolvers -----

	@DgsData(parentType = "Agent", field = "signingKeys")
	public List<SigningKeyData> agentSigningKeys(DgsDataFetchingEnvironment dfe) {
		AgentData a = dfe.getSource();
		if (a == null) return List.of();
		return signingKeyService.listByOwner(a.getOrg(), SigningKeyOwnerType.AGENT, a.getUuid());
	}

	@DgsData(parentType = "Committer", field = "signingKeys")
	public List<SigningKeyData> committerSigningKeys(DgsDataFetchingEnvironment dfe) {
		CommitterData c = dfe.getSource();
		if (c == null) return List.of();
		return signingKeyService.listByOwner(c.getOrg(), SigningKeyOwnerType.COMMITTER, c.getUuid());
	}

	/**
	 * Latest signature verdict for a SCE — exposed on
	 * {@code SourceCodeEntry.signature} and read by CEL as
	 * {@code commit.signature.*}. Returns null when no verification
	 * has been performed against this SCE.
	 *
	 * Field names on {@code SceSignature} are user-facing ({@code state}
	 * not {@code verdict}; {@code signedByOwnerType} not {@code ownerType})
	 * so we map from the persistence shape here rather than renaming the
	 * Java fields.
	 */
	@DgsData(parentType = "SourceCodeEntry", field = "signature")
	public java.util.Map<String, Object> sceSignature(DgsDataFetchingEnvironment dfe) {
		SourceCodeEntryData sce = dfe.getSource();
		if (sce == null) return null;
		Optional<SignatureVerificationData> latest = signatureVerificationService
				.findLatestBySubject(SignatureSubjectType.SCE, sce.getUuid());
		if (latest.isEmpty()) return null;
		SignatureVerificationData v = latest.get();
		java.util.Map<String, Object> out = new java.util.HashMap<>();
		out.put("state", v.getVerdict() != null ? v.getVerdict().name() : "UNSIGNED");
		out.put("format", v.getFormat() != null ? v.getFormat().name() : null);
		out.put("signedByOwnerType", v.getOwnerType() != null ? v.getOwnerType().name() : null);
		out.put("signedByOwnerUuid", v.getOwnerUuid() != null ? v.getOwnerUuid().toString() : null);
		out.put("verifiedAt", v.getVerifiedAt());
		out.put("keyFingerprint", v.getKeyFingerprint());
		return out;
	}

	// ----- Mutations -----

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "upsertCommitter")
	public CommitterData upsertCommitter(@InputArgument("input") java.util.Map<String, Object> input) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		CommitterData seed = Utils.OM.convertValue(input, CommitterData.class);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(seed.getOrg());
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, seed.getOrg(), List.of(od.orElse(null)), CallType.WRITE);
		return committerService.upsert(seed, WhoUpdated.getWhoUpdated(oud.get()));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "archiveCommitter")
	public CommitterData archiveCommitter(@InputArgument("uuid") UUID uuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<CommitterData> ocd = committerService.getCommitterData(uuid);
		RelizaObject ro = ocd.orElse(null);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.WRITE);
		return committerService.setStatus(uuid, CommitterData.CommitterStatus.ARCHIVED, WhoUpdated.getWhoUpdated(oud.get()));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "enrollSigningKey")
	public SigningKeyData enrollSigningKey(@InputArgument("input") java.util.Map<String, Object> input) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		SigningKeyData seed = Utils.OM.convertValue(input, SigningKeyData.class);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(seed.getOrg());
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, seed.getOrg(), List.of(od.orElse(null)), CallType.WRITE);
		// owner sanity check — owner must exist and belong to the org
		ensureOwnerInOrg(seed);
		return signingKeyService.enrol(seed, WhoUpdated.getWhoUpdated(oud.get()));
	}

	/**
	 * Agent self-enrolment of a signing key. FREEFORM AGENT auth.
	 *
	 * <p><b>Call-type carve-out.</b> The auth check below runs at
	 * {@link io.reliza.common.CommonVariables.CallType#ESSENTIAL_READ}
	 * rather than {@code WRITE}, matching the rest of the agent-flow
	 * programmatic surface. That intentionally lets a FREEFORM
	 * {@code AGENT} key granted at {@code READ_ONLY} call this
	 * endpoint — a deliberate exception to the usual "READ_ONLY can't
	 * mutate" intuition. It's safe because the *real* authorisation
	 * gate is the chain check below, not the call-type threshold:
	 * an agent can only enrol a key onto *itself*, never onto another
	 * agent.
	 *
	 * <p>Hard constraints (any violation is an
	 * {@link AccessDeniedException}):
	 * <ul>
	 *   <li>{@code input.ownerType} must be {@code AGENT} — committers
	 *       still require the JWT-authenticated {@code enrollSigningKey}.</li>
	 *   <li>{@code input.ownerUuid} must be an Agent in the calling
	 *       key's org (the org is never client-supplied — it's always the
	 *       one the key resolves to) whose {@code agentIdentity} matches
	 *       the {@link AgentIdentity} resolved from the calling FREEFORM
	 *       key (via the {@code agent_identity_credentials} table — the
	 *       key uuid is the credential value). This is what prevents an
	 *       agent from enrolling a key onto another agent that happens to
	 *       share the org.</li>
	 *   <li>The calling key must carry {@code PermissionFunction.AGENT}
	 *       at {@code ORGANIZATION} scope (the same check
	 *       {@code sessionInitializeProgrammatic} runs).</li>
	 * </ul>
	 */
	@DgsData(parentType = "Mutation", field = "enrollSigningKeyProgrammatic")
	public SigningKeyData enrollSigningKeyProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(
				requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID orgUuid = authCtx.orgUuid();
		if (ahp == null) throw new AccessDeniedException("Invalid authorization");
		if (ahp.getType() != ApiTypeEnum.FREEFORM) {
			throw new AccessDeniedException("Only FREEFORM API keys are supported for agent operations");
		}
		if (orgUuid == null) throw new AccessDeniedException("Could not resolve org for key");

		Map<String, Object> signingKey = dfe.getArgument("signingKey");
		if (signingKey == null) throw new RelizaException("signingKey is required");
		SigningKeyData seed = Utils.OM.convertValue(signingKey, SigningKeyData.class);

		// Org is never client-supplied here (AgentSigningKeyInput has no
		// org field) — it's always the org the calling key resolves to.
		seed.setOrg(orgUuid);

		if (seed.getOwnerType() != SigningKeyOwnerType.AGENT) {
			throw new AccessDeniedException("Programmatic enrolment is restricted to ownerType=AGENT");
		}
		if (seed.getOwnerUuid() == null) {
			throw new RelizaException("ownerUuid is required");
		}

		OrganizationData od = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("Org not found: " + orgUuid));
		// ESSENTIAL_READ matches the other agent-flow programmatic
		// endpoints. The actual write-authorization for this call is
		// the inline owner-identity chain check below (ownerType=AGENT,
		// ownerUuid is the calling key's own root agent) — the call
		// type threshold is only used to verify the FREEFORM key
		// carries the AGENT permission function.
		FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
				ahp, PermissionFunction.AGENT, PermissionScope.ORGANIZATION, orgUuid,
				List.of(od), CallType.ESSENTIAL_READ);

		// Resolve the calling key's identity, then verify the target
		// agent belongs to that identity. Without this check, a key in
		// the same org could enrol a key onto an agent owned by a
		// different identity (e.g. a different CI bot) and forge its
		// commit signatures.
		AgentIdentityData identity = agentIdentityService.findOrRegisterByCredential(
				orgUuid,
				AgentIdentityCredential.IdentityType.REARM_API_KEY,
				fkv.apiKeyUuid().toString(),
				fkv.whoUpdated());
		Optional<AgentData> targetAgent = agentService.getAgentData(seed.getOwnerUuid());
		if (targetAgent.isEmpty() || !orgUuid.equals(targetAgent.get().getOrg())) {
			throw new AccessDeniedException("Target agent not found in this org");
		}
		if (!identity.getUuid().equals(targetAgent.get().getAgentIdentity())) {
			throw new AccessDeniedException(
					"Calling key does not own the target agent — cannot enrol a signing key against an agent owned by a different identity");
		}

		return signingKeyService.enrol(seed, fkv.whoUpdated());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "revokeSigningKey")
	public SigningKeyData revokeSigningKey(@InputArgument("uuid") UUID uuid) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<SigningKeyData> okd = signingKeyService.getSigningKeyData(uuid);
		RelizaObject ro = okd.orElse(null);
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.WRITE);
		return signingKeyService.revoke(uuid, WhoUpdated.getWhoUpdated(oud.get()));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateSigningKeyIdentity")
	public SigningKeyData updateSigningKeyIdentity(@InputArgument("uuid") UUID uuid,
			@InputArgument("identity") String identity) throws RelizaException {
		var oud = userService.getUserDataByAuth(currentAuth());
		Optional<SigningKeyData> okd = signingKeyService.getSigningKeyData(uuid);
		RelizaObject ro = okd.orElse(null);
		// Org-admin only — editing the allowed-signers principal changes
		// what the verifier will accept, so it's a tighter gate than the
		// WRITE used for enrol/revoke.
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.ADMIN);
		return signingKeyService.updateIdentity(uuid, identity, WhoUpdated.getWhoUpdated(oud.get()));
	}

	// ----- helpers -----

	private JwtAuthenticationToken currentAuth() {
		return (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
	}

	private void ensureOwnerInOrg(SigningKeyData seed) throws RelizaException {
		if (seed.getOwnerType() == SigningKeyOwnerType.AGENT) {
			Optional<AgentData> ad = agentService.getAgentData(seed.getOwnerUuid());
			if (ad.isEmpty() || !ad.get().getOrg().equals(seed.getOrg())) {
				throw new RelizaException("Agent owner not found in this org");
			}
		} else if (seed.getOwnerType() == SigningKeyOwnerType.COMMITTER) {
			Optional<CommitterData> cd = committerService.getCommitterData(seed.getOwnerUuid());
			if (cd.isEmpty() || !cd.get().getOrg().equals(seed.getOrg())) {
				throw new RelizaException("Committer owner not found in this org");
			}
		}
	}
}
