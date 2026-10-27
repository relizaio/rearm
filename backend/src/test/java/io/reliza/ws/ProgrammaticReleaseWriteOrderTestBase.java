/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.AttestationData.SubjectType;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentLock;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AttestationService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentLockService;
import io.reliza.service.ComponentService;
import io.reliza.service.DeliverableService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.VariantService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Shared rig of {@link ProgrammaticReleaseWriteOrderTest} (TPF-21) and {@link ProgrammaticReleaseByCommitTest}
 * (TPF-98): the field table of the twenty key fields of {@code ReleaseDatafetcher},
 * {@code OssReleaseDatafetcher}, {@code DeliverableDataFetcher}, {@code IntegrityDataFetcher} and
 * {@code TeaPublicationDataFetcher}, two
 * tenants with a component, a commit, a release on it and the keys those fields serve, and the two call
 * forms (the principal the programmatic filter leaves after a bearer, and a Basic header the resolver
 * parses and verifies itself).
 */
abstract class ProgrammaticReleaseWriteOrderTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	protected static final String NOT_AUTHORIZED = "Not authorized";
	protected static final String SEALED = "System is sealed. Please unseal the system first.";
	protected static final String UNLICENSED = "License is invalid or expired. Please upload a valid license.";

	@Autowired protected TestInitializer testInitializer;
	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected ReleaseDatafetcher releaseDatafetcher;
	@Autowired protected ComponentService componentService;
	@Autowired protected BranchService branchService;
	@Autowired protected VcsRepositoryService vcsRepositoryService;
	@Autowired protected SourceCodeEntryService sourceCodeEntryService;
	@Autowired protected OssReleaseService ossReleaseService;
	@Autowired protected SharedReleaseService sharedReleaseService;
	@Autowired protected VariantService variantService;
	@Autowired protected DeliverableService deliverableService;
	@Autowired protected ApiKeyService apiKeyService;
	@Autowired protected ApiKeyAccessService apiKeyAccessService;
	@Autowired protected AgentIdentityService agentIdentityService;
	@Autowired protected AgentService agentService;
	@Autowired protected AgentSessionService agentSessionService;
	@Autowired protected ComponentLockService componentLockService;
	@Autowired protected AttestationService attestationService;
	@Autowired protected LicenseStatus licenseStatus;
	@Autowired protected ApplicationContext applicationContext;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; each case sets the state it needs. */
	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	// ---------- the field table ----------

	/** Which key a row is called with. */
	enum KeyKind { FREEFORM, ORGANIZATION_RW, SESSION }

	/** Which object a row names, so a case can swap it for a random uuid. */
	enum Names { COMPONENT, RELEASE, SESSION }

	/** The objects a call can name. */
	record Ids(UUID component, UUID release, UUID session, UUID lock, UUID branch, String commit) {
		Ids random(Names names) {
			return switch (names) {
				case COMPONENT -> new Ids(UUID.randomUUID(), release, session, lock, branch, commit);
				case RELEASE -> new Ids(component, UUID.randomUUID(), session, lock, branch, commit);
				case SESSION -> new Ids(component, release, UUID.randomUUID(), lock, branch, commit);
			};
		}
	}

	record Row(int n, String field, String op, Function<Ids, Map<String, Object>> vars, KeyKind kind, Names names) {
		@Override
		public String toString() {
			return n + " " + field;
		}
	}

	protected static Map<String, Object> m(Object... kv) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], String.valueOf(kv[i + 1]));
		return out;
	}

	protected static final List<Row> ROWS = List.of(
			new Row(1, "getReleaseByHashProgrammatic",
					"query($c: ID) { getReleaseByHashProgrammatic(hash: \"sha256:0\", componentId: $c) }",
					i -> m("c", i.component()), KeyKind.FREEFORM, Names.COMPONENT),
			new Row(2, "getReleaseByReleaseVersionProgrammatic",
					"query($c: ID!) { getReleaseByReleaseVersionProgrammatic(version: \"1.0.0\", componentId: $c) }",
					i -> m("c", i.component()), KeyKind.FREEFORM, Names.COMPONENT),
			new Row(3, "getLatestReleaseProgrammatic",
					"query($c: ID) { getLatestReleaseProgrammatic(release: { component: $c }) { uuid version } }",
					i -> m("c", i.component()), KeyKind.FREEFORM, Names.COMPONENT),
			new Row(4, "getLatestReleaseProgrammaticCdx",
					"query($c: ID) { getLatestReleaseProgrammaticCdx(release: { component: $c }) }",
					i -> m("c", i.component()), KeyKind.FREEFORM, Names.COMPONENT),
			new Row(5, "addReleaseProgrammatic",
					"mutation($c: ID, $b: String) { addReleaseProgrammatic(release: { version: \"1.0.1\", component: $c,"
							+ " branch: $b }) { uuid version } }",
					i -> m("c", i.component(), "b", i.branch()), KeyKind.FREEFORM, Names.COMPONENT),
			new Row(6, "addReleasesProgrammatic",
					"mutation($c: ID, $b: String) { addReleasesProgrammatic(releases: [{ version: \"1.0.2\", component: $c,"
							+ " branch: $b }]) { uuid version } }",
					i -> m("c", i.component(), "b", i.branch()), KeyKind.FREEFORM, Names.COMPONENT),
			new Row(7, "addArtifactProgrammatic",
					"mutation($r: ID) { addArtifactProgrammatic(artifactInput: { release: $r }) { uuid } }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			new Row(8, "releasecompletionfinalizerProgrammatic",
					"mutation($r: ID!) { releasecompletionfinalizerProgrammatic(release: $r) }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			new Row(9, "approveReleaseProgrammatic",
					"mutation($r: ID) { approveReleaseProgrammatic(releaseApprovals: { release: $r, approvals: [] }) { uuid } }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			new Row(10, "addOutboundDeliverablesProgrammatic",
					"mutation($r: ID) { addOutboundDeliverablesProgrammatic(deliverables: { release: $r, deliverables: [] })"
							+ " { uuid } }",
					i -> m("r", i.release()), KeyKind.ORGANIZATION_RW, Names.RELEASE),
			new Row(11, "attestProgrammatic",
					"mutation($c: ID, $commit: String!, $s: ID!) { attestProgrammatic(componentRef: { component: $c },"
							+ " commit: $commit, verdict: MINE, session: $s) { uuid actorType agentSession } }",
					i -> m("c", i.component(), "commit", i.commit(), "s", i.session()), KeyKind.SESSION, Names.SESSION),
			new Row(12, "releaseLockProgrammatic",
					"mutation($l: ID!, $s: ID!) { releaseLockProgrammatic(lockUuid: $l, reason: \"order test\", session: $s)"
							+ " { uuid status } }",
					i -> m("l", i.lock(), "s", i.session()), KeyKind.SESSION, Names.SESSION),
			// TPF-34: the two SBOM fields reached main from the SBOM-score branch after this table
			new Row(13, "releaseSbomExportProgrammatic",
					"mutation($r: ID) { releaseSbomExportProgrammatic(release: $r) }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			new Row(14, "releaseSbomScoreProgrammatic",
					"query($r: ID) { releaseSbomScoreProgrammatic(release: $r, profiles: []) }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			// TPF-98 (TPF-72 child 1): the two by-commit fields share by-version's prefix through a helper
			new Row(15, "getReleaseByCommitProgrammatic",
					"query($c: ID!, $commit: String!) { getReleaseByCommitProgrammatic(commit: $commit, componentId: $c) }",
					i -> m("c", i.component(), "commit", i.commit()), KeyKind.FREEFORM, Names.COMPONENT),
			new Row(16, "getReleasesByCommitProgrammatic",
					"query($c: ID!, $commit: String!) { getReleasesByCommitProgrammatic(commit: $commit, componentId: $c)"
							+ " { uuid version createdDate } }",
					i -> m("c", i.component(), "commit", i.commit()), KeyKind.FREEFORM, Names.COMPONENT),
			// TEA-25: TEA's programmatic publication fields (TeaPublicationDataFetcher)
			new Row(17, "releaseTeaPublicationViewProgrammatic",
					"query($r: ID!) { releaseTeaPublicationViewProgrammatic(release: $r) { release canPublish } }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			new Row(18, "publishReleaseOnTeaProgrammatic",
					"mutation($r: ID!) { publishReleaseOnTeaProgrammatic(release: $r, comment: \"order test\")"
							+ " { outcome publication { uuid } } }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			new Row(19, "republishReleaseOnTeaProgrammatic",
					"mutation($r: ID!) { republishReleaseOnTeaProgrammatic(release: $r, comment: \"order test\")"
							+ " { outcome publication { uuid } } }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE),
			new Row(20, "hideReleaseOnTeaProgrammatic",
					"mutation($r: ID!) { hideReleaseOnTeaProgrammatic(release: $r, comment: \"order test\") { uuid state } }",
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE));

	static Stream<Row> rows() {
		return ROWS.stream();
	}

	protected static Row row(int n) {
		return ROWS.get(n - 1);
	}

	// ---------- fixtures ----------

	/**
	 * A component with its VCS and base branch, a commit, a DRAFT release {@code 1.0.0} with one
	 * outbound deliverable; a COMPONENT key, an ORGANIZATION_RW key and a FREEFORM key with org-wide
	 * READ_WRITE RESOURCE, with the three keys' secrets; a READ_ONLY FREEFORM key; a second component
	 * with a FREEFORM key that may write only it and a COMPONENT key of its own.
	 */
	record Tenant(UUID org, Component component, Branch branch, UUID release, UUID sce, String commit,
			ApiKey componentKey, ApiKey orgKey, ApiKey freeformKey, String componentSecret, String orgSecret,
			String freeformSecret, ApiKey readOnlyKey, Component sibling, ApiKey narrowKey, ApiKey siblingComponentKey) {}

	/** An agent key with AGENT READ_WRITE at the organisation, its root agent and an OPEN session of it. */
	record Caller(ApiKey key, AgentSessionData session) {}

	/** Tenants A and B, an agent caller in each, and an AGENT-level lock in A. */
	record World(Tenant a, Tenant b, Caller ca, Caller cb, UUID lock) {
		Ids idsOfA() {
			return new Ids(a.component().getUuid(), a.release(), ca.session().getUuid(), lock, a.branch().getUuid(),
					a.commit());
		}

		Ids idsOfB() {
			return new Ids(b.component().getUuid(), b.release(), cb.session().getUuid(), lock, b.branch().getUuid(),
					b.commit());
		}

		ApiKey keyOfA(KeyKind kind) {
			return keyOf(a, ca, kind);
		}

		ApiKey keyOfB(KeyKind kind) {
			return keyOf(b, cb, kind);
		}

		private static ApiKey keyOf(Tenant t, Caller c, KeyKind kind) {
			return switch (kind) {
				case FREEFORM -> t.freeformKey();
				case ORGANIZATION_RW -> t.orgKey();
				case SESSION -> c.key();
			};
		}
	}

	protected World world() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		return new World(a, b, caller(a.org()), caller(b.org()), agentLock(a.org()));
	}

	protected Tenant tenant() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// the component carries its VCS so attestProgrammatic can resolve the commit against it
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org,
				"github.com/example/order-" + UUID.randomUUID(), VcsType.GIT, WU);
		Component component = componentService.createComponent("order_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", vcs, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		String commit = UUID.randomUUID().toString().replace("-", "") + "cccccccc";
		UUID sce = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder()
				.branch(branch.getUuid()).vcs(vcs).organizationUuid(org).commit(commit).build(), WU).getUuid();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("1.0.0").sourceCodeEntry(sce)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
		UUID variant = variantService.getBaseVariantForRelease(release).getUuid();
		UUID deliverable = deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("order-deliverable-" + UUID.randomUUID()).branch(branch.getUuid())
				.type(CdxType.FILE).build(), WU).getUuid();
		variantService.addOutboundDeliverables(List.of(deliverable), variant, WU);
		ApiKey componentKey = apiKeyService.createObjectApiKey(component.getUuid(), ApiTypeEnum.COMPONENT, org,
				null, "order test component key", WU);
		ApiKey orgKey = apiKeyService.createObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org,
				null, "order test org key", WU);
		ApiKey freeformKey = freeformKey(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE);
		// secrets for the CLI's Basic-header path (same key rows)
		String componentSecret = apiKeyService.setObjectApiKey(component.getUuid(), ApiTypeEnum.COMPONENT, org,
				null, "order test component key", false, WU);
		String orgSecret = apiKeyService.setObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org,
				null, "order test org key", false, WU);
		String freeformSecret = apiKeyService.addApiKeySecret(freeformKey.getUuid(), null, WU);
		ApiKey readOnlyKey = freeformKey(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY);
		Component sibling = component(org);
		ApiKey narrowKey = freeformKey(org, PermissionScope.COMPONENT, sibling.getUuid(), PermissionType.READ_WRITE);
		ApiKey siblingComponentKey = apiKeyService.createObjectApiKey(sibling.getUuid(), ApiTypeEnum.COMPONENT, org,
				null, "order test sibling component key", WU);
		return new Tenant(org, component, branch, release, sce, commit,
				apiKeyService.getApiKey(componentKey.getUuid()).orElseThrow(),
				apiKeyService.getApiKey(orgKey.getUuid()).orElseThrow(),
				apiKeyService.getApiKey(freeformKey.getUuid()).orElseThrow(),
				componentSecret, orgSecret, freeformSecret, readOnlyKey, sibling, narrowKey, siblingComponentKey);
	}

	protected Component component(UUID org) throws RelizaException {
		return componentService.createComponent("order_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	protected ApiKey freeformKey(UUID org, PermissionScope scope, UUID object, PermissionType type)
			throws RelizaException {
		return freeformKey(org, scope, object, type, Set.of(PermissionFunction.RESOURCE));
	}

	protected ApiKey freeformKey(UUID org, PermissionScope scope, UUID object, PermissionType type,
			Set<PermissionFunction> functions) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "order test freeform key", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org, scope, object, type,
				functions, null)), WU);
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
	}

	protected Caller caller(UUID org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "order test agent key", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org,
				PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT),
				null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org, IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org, identity, "order-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org, agent.getUuid(), key.getUuid(),
				"order-" + UUID.randomUUID(), "order test", null, null, WU);
		return new Caller(apiKeyService.getApiKey(key.getUuid()).orElseThrow(), session);
	}

	/** A lock a key may release, on a component of its own so it holds back no release of the tenant's. */
	protected UUID agentLock(UUID org) throws RelizaException {
		ComponentLock lock = componentLockService.lockComponent(component(org).getUuid(), "order test",
				UnlockLevel.AGENT, AttestationRequirement.NONE, WU);
		return lock.uuid();
	}

	// ---------- calls ----------

	protected ExecutionResult execute(MockHttpServletRequest request, HttpHeaders headers, String op,
			Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		return dgsQueryExecutor.execute(op, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	/** Verified-principal form: what the filter leaves after it checked a bearer token. */
	protected ExecutionResult asKey(ApiKey key, String op, Map<String, Object> vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.21"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return execute(request, headers, op, vars);
	}

	/** Basic form: no principal attribute; the resolver parses and verifies the header itself. */
	protected ExecutionResult asBasic(String keyId, String secret, String op, Map<String, Object> vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		return execute(request, headers, op, vars);
	}

	protected ExecutionResult call(Row r, ApiKey key, Ids ids) {
		return asKey(key, r.op(), r.vars().apply(ids));
	}

	protected static String freeformId(UUID org, String keyOrder) {
		return ApiTypeEnum.FREEFORM.name() + "__" + org + "__ord__" + keyOrder;
	}

	/** A key id no key carries, of the type the row accepts. */
	protected static String forgedId(Row r) {
		return KeyKind.ORGANIZATION_RW == r.kind()
				? ApiTypeEnum.ORGANIZATION_RW.name() + "__" + UUID.randomUUID()
				: freeformId(UUID.randomUUID(), "0");
	}

	protected static String randomSecret() {
		return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
	}

	/**
	 * Exactly one refusal, nothing under the field; returns its message. A field of non-null type (for
	 * example {@code getReleasesByCommitProgrammatic}) also carries the engine's own
	 * NullValueInNonNullableField error for the null the refusal leaves; that one is not a refusal: at
	 * most one occurs, it is on the field's path, and it is set aside (TPF-98 and TEA-25, united by TPF-102).
	 */
	protected static String refusal(ExecutionResult r, String field) {
		assertFalse(r.getErrors().isEmpty(), field + ": expected a refusal, got " + String.valueOf((Object) r.getData()));
		List<GraphQLError> engine = r.getErrors().stream().filter(e -> "NullValueInNonNullableField".equals(
				String.valueOf(null == e.getExtensions() ? null : e.getExtensions().get("classification")))).toList();
		assertTrue(engine.size() <= 1, field + ": " + r.getErrors());
		engine.forEach(e -> assertEquals(List.of(field), e.getPath(), field + ": the engine's null error is on the field"));
		List<GraphQLError> refusals = r.getErrors().stream().filter(e -> !engine.contains(e)).toList();
		assertEquals(1, refusals.size(), field + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		assertNull(null == data ? null : data.get(field), field + ": a refused call returns nothing");
		return refusals.get(0).getMessage();
	}

	protected static Object result(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), field + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		return data.get(field);
	}

	protected int approvalEventCount(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow().getApprovalEvents().size();
	}

	protected int releaseCount(UUID component) {
		return sharedReleaseService.listReleaseDatasOfComponent(component, 1000, 0).size();
	}

	protected ComponentLock.Status lockStatus(UUID org, UUID lock) {
		return componentLockService.locate(org, lock).orElseThrow().lock().status();
	}

	protected int attestationCount(Tenant t) {
		return attestationService.attestationsOfSubject(t.org(), SubjectType.SCE, t.sce()).size();
	}

	protected long accessRows(UUID org, UUID keyUuid) {
		return apiKeyAccessService.listKeyAccessByOrg(org).stream()
				.filter(a -> keyUuid.equals(a.getApiKeyUuid())).count();
	}
}
