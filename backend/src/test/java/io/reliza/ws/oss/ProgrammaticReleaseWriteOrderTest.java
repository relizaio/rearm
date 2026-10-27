/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.ExecutionResult;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.ws.App;
import io.reliza.ws.DeliverableDataFetcher;
import io.reliza.ws.IntegrityDataFetcher;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;
import io.reliza.ws.ReleaseDatafetcher;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.AttestationData.SubjectType;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentLock;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.ReleaseData;
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
import io.reliza.model.Link.ContentEnum;
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
import io.reliza.ws.oss.OssReleaseDatafetcher;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-21 (TPF-17 child 3): the fourteen key fields of {@code ReleaseDatafetcher},
 * {@code OssReleaseDatafetcher}, {@code DeliverableDataFetcher} and {@code IntegrityDataFetcher}
 * verify the credential, then check the system state, then look their object up. A sealed or
 * unlicensed instance answers one sentence for a missing and an existing object to every valid key;
 * a Basic credential that does not verify reads {@code Not authorized} for both, on any instance;
 * a valid key on a healthy instance reads what it read before. Calls go through the DGS executor
 * (the refusal text as the wire carries it), in the bearer form (the principal the programmatic
 * filter leaves) and the Basic form (the resolver parses and verifies the header itself).
 *
 * <p>CE copy of the shared test: row 9 ({@code approveReleaseProgrammatic}) is left out, it is a
 * "not part of ReARM CE" stub on CE, and so is the completeness check over the schema's root fields.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ProgrammaticReleaseWriteOrderTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String NOT_AUTHORIZED = "Not authorized";
	private static final String SEALED = "System is sealed. Please unseal the system first.";
	private static final String UNLICENSED = "License is invalid or expired. Please upload a valid license.";

	@Autowired private TestInitializer testInitializer;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private VariantService variantService;
	@Autowired private DeliverableService deliverableService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyAccessService apiKeyAccessService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentLockService componentLockService;
	@Autowired private AttestationService attestationService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;

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

	private static Map<String, Object> m(Object... kv) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], String.valueOf(kv[i + 1]));
		return out;
	}

	private static final List<Row> ROWS = List.of(
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
					i -> m("r", i.release()), KeyKind.FREEFORM, Names.RELEASE));

	static Stream<Row> rows() {
		return ROWS.stream();
	}

	private static Row row(int n) {
		return ROWS.stream().filter(r -> r.n() == n).findFirst().orElseThrow();
	}

	// ---------- fixtures ----------

	/**
	 * A component with its VCS and base branch, a commit, a DRAFT release {@code 1.0.0} with one
	 * outbound deliverable; a COMPONENT key, an ORGANIZATION_RW key and a FREEFORM key with org-wide
	 * READ_WRITE RESOURCE, with the three keys' secrets; a READ_ONLY FREEFORM key; a second component
	 * with a FREEFORM key that may write only it and a COMPONENT key of its own.
	 */
	private record Tenant(UUID org, Component component, Branch branch, UUID release, UUID sce, String commit,
			ApiKey componentKey, ApiKey orgKey, ApiKey freeformKey, String componentSecret, String orgSecret,
			String freeformSecret, ApiKey readOnlyKey, Component sibling, ApiKey narrowKey, ApiKey siblingComponentKey) {}

	/** An agent key with AGENT READ_WRITE at the organisation, its root agent and an OPEN session of it. */
	private record Caller(ApiKey key, AgentSessionData session) {}

	/** Tenants A and B, an agent caller in each, and an AGENT-level lock in A. */
	private record World(Tenant a, Tenant b, Caller ca, Caller cb, UUID lock) {
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

	private World world() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		return new World(a, b, caller(a.org()), caller(b.org()), agentLock(a.org()));
	}

	private Tenant tenant() throws RelizaException {
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

	private Component component(UUID org) throws RelizaException {
		return componentService.createComponent("order_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	private ApiKey freeformKey(UUID org, PermissionScope scope, UUID object, PermissionType type)
			throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "order test freeform key", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org, scope, object, type,
				Set.of(PermissionFunction.RESOURCE), null)), WU);
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
	}

	private Caller caller(UUID org) throws RelizaException {
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
	private UUID agentLock(UUID org) throws RelizaException {
		ComponentLock lock = componentLockService.lockComponent(component(org).getUuid(), "order test",
				UnlockLevel.AGENT, AttestationRequirement.NONE, WU);
		return lock.uuid();
	}

	// ---------- calls ----------

	private ExecutionResult execute(MockHttpServletRequest request, HttpHeaders headers, String op,
			Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		return dgsQueryExecutor.execute(op, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	/** Verified-principal form: what the filter leaves after it checked a bearer token. */
	private ExecutionResult asKey(ApiKey key, String op, Map<String, Object> vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.21"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return execute(request, headers, op, vars);
	}

	/** Basic form: no principal attribute; the resolver parses and verifies the header itself. */
	private ExecutionResult asBasic(String keyId, String secret, String op, Map<String, Object> vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		return execute(request, headers, op, vars);
	}

	private ExecutionResult call(Row r, ApiKey key, Ids ids) {
		return asKey(key, r.op(), r.vars().apply(ids));
	}

	private static String freeformId(UUID org, String keyOrder) {
		return ApiTypeEnum.FREEFORM.name() + "__" + org + "__ord__" + keyOrder;
	}

	/** A key id no key carries, of the type the row accepts. */
	private static String forgedId(Row r) {
		return KeyKind.ORGANIZATION_RW == r.kind()
				? ApiTypeEnum.ORGANIZATION_RW.name() + "__" + UUID.randomUUID()
				: freeformId(UUID.randomUUID(), "0");
	}

	private static String randomSecret() {
		return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
	}

	/** Exactly one refusal, nothing under the field; returns its message. */
	private static String refusal(ExecutionResult r, String field) {
		assertFalse(r.getErrors().isEmpty(), field + ": expected a refusal, got " + String.valueOf((Object) r.getData()));
		assertEquals(1, r.getErrors().size(), field + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		assertNull(null == data ? null : data.get(field), field + ": a refused call returns nothing");
		return r.getErrors().get(0).getMessage();
	}

	private static Object result(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), field + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		return data.get(field);
	}

	private int approvalEventCount(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow().getApprovalEvents().size();
	}

	private int releaseCount(UUID component) {
		return sharedReleaseService.listReleaseDatasOfComponent(component, 1000, 0).size();
	}

	private ComponentLock.Status lockStatus(UUID org, UUID lock) {
		return componentLockService.locate(org, lock).orElseThrow().lock().status();
	}

	private int attestationCount(Tenant t) {
		return attestationService.attestationsOfSubject(t.org(), SubjectType.SCE, t.sce()).size();
	}

	private long accessRows(UUID org, UUID keyUuid) {
		return apiKeyAccessService.listKeyAccessByOrg(org).stream()
				.filter(a -> keyUuid.equals(a.getApiKeyUuid())).count();
	}


	// ---------- case 1: the sealed sentence first, for every valid key ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void case1SealedSentenceFirstForEveryValidKey(Row r) throws RelizaException {
		World w = world();
		Ids a = w.idsOfA();
		int votes = approvalEventCount(w.a().release());
		licenseStatus.setSystemSealed(true);

		String ownMissing = refusal(call(r, w.keyOfA(r.kind()), a.random(r.names())), r.field());
		String ownReal = refusal(call(r, w.keyOfA(r.kind()), a), r.field());
		assertEquals(SEALED, ownMissing, r.field());
		assertEquals(ownMissing, ownReal, r.field() + ": missing and existing read alike while sealed");

		String foreignMissing = refusal(call(r, w.keyOfB(r.kind()), a.random(r.names())), r.field());
		String foreignReal = refusal(call(r, w.keyOfB(r.kind()), a), r.field());
		assertEquals(SEALED, foreignMissing, r.field());
		assertEquals(foreignMissing, foreignReal, r.field() + ": a foreign key learns nothing while sealed");

		if (r.n() == 7 || r.n() == 10) {
			for (ApiKey legacy : List.of(w.a().componentKey(), w.a().orgKey())) {
				assertEquals(SEALED, refusal(call(r, legacy, a.random(r.names())), r.field()),
						r.field() + " with " + legacy.getObjectType());
			}
		}
		if (r.n() == 9) {
			assertEquals(votes, approvalEventCount(w.a().release()), "no vote is recorded while sealed");
		}
	}

	@Test
	public void case1TheLicenceSentenceComesFirstToo() throws RelizaException {
		Row r = row(3);
		World w = world();
		Ids a = w.idsOfA();
		licenseStatus.setLicenseValid(false);
		String ownMissing = refusal(call(r, w.keyOfA(r.kind()), a.random(r.names())), r.field());
		assertEquals(UNLICENSED, ownMissing);
		assertEquals(ownMissing, refusal(call(r, w.keyOfA(r.kind()), a), r.field()));
		assertEquals(ownMissing, refusal(call(r, w.keyOfB(r.kind()), a.random(r.names())), r.field()));
		assertEquals(ownMissing, refusal(call(r, w.keyOfB(r.kind()), a), r.field()));
	}

	// ---------- case 2: a foreign key, and a key one permission short, on a healthy instance ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void case2ForeignKeyAndMissingPermissionStillRefused(Row r) throws RelizaException {
		World w = world();
		Ids a = w.idsOfA();
		boolean targetService = r.n() == 7 || r.n() == 10;
		String expected = targetService ? CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE : NOT_AUTHORIZED;

		assertEquals(expected, refusal(call(r, w.keyOfB(r.kind()), a), r.field()), r.field() + ": foreign key");

		ApiKey short1 = switch (r.n()) {
			case 1, 2, 3, 4 -> w.a().narrowKey();
			case 10 -> w.a().siblingComponentKey();
			case 11, 12 -> caller(w.a().org()).key();
			default -> w.a().readOnlyKey();
		};
		assertEquals(expected, refusal(call(r, short1, a), r.field()), r.field() + ": one permission short");
	}

	// ---------- case 3: the happy path per row ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	@SuppressWarnings("unchecked")
	public void case3HappyPath(Row r) throws RelizaException {
		World w = world();
		Ids a = w.idsOfA();
		switch (r.n()) {
			case 1, 2 -> assertNotNull(result(call(r, w.keyOfA(r.kind()), a), r.field()), r.field());
			case 3 -> {
				Map<String, Object> rel = (Map<String, Object>) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertNotNull(rel, r.field());
				assertEquals(w.a().release().toString(), rel.get("uuid"));
				assertEquals("1.0.0", rel.get("version"));
			}
			case 4 -> {
				String doc = (String) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertNotNull(doc);
				assertFalse("{}".equals(doc) || doc.isBlank(), doc);
			}
			case 5 -> {
				Map<String, Object> rel = (Map<String, Object>) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertEquals("1.0.1", rel.get("version"));
				assertTrue(sharedReleaseService.getReleaseData(UUID.fromString((String) rel.get("uuid"))).isPresent());
			}
			case 6 -> {
				List<Map<String, Object>> rels = (List<Map<String, Object>>) result(call(r, w.keyOfA(r.kind()), a),
						r.field());
				assertEquals(1, rels.size());
				assertEquals("1.0.2", rels.get(0).get("version"));
			}
			case 7 -> {
				// a file part does not travel through the executor: the resolver is called directly
				Map<String, Object> input = new HashMap<>();
				input.put(CommonVariables.RELEASE_FIELD, w.a().release().toString());
				input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
				ReleaseData rd = releaseDatafetcher.addArtifactProg(asEnv(w.a().freeformKey(), "artifactInput", input));
				assertEquals(w.a().release(), rd.getUuid());
				assertEquals(1, rd.getArtifacts().size());
			}
			case 8 -> assertEquals(Boolean.TRUE, result(call(r, w.keyOfA(r.kind()), a), r.field()));
			case 9 -> assertEquals(NOT_AUTHORIZED, refusal(call(r, w.keyOfA(r.kind()), a), r.field()),
					"a key without an approval role (no approval policy either) is refused; the vote itself is the harness's");
			case 10 -> {
				String op = "mutation($d: AddODeliverableInput!) { addOutboundDeliverablesProgrammatic(deliverables: $d)"
						+ " { uuid } }";
				Map<String, Object> vars = new HashMap<>();
				vars.put("d", deliverablesInput(w.a().release()));
				Map<String, Object> rel = (Map<String, Object>) result(asKey(w.a().orgKey(), op, vars), r.field());
				assertEquals(w.a().release().toString(), rel.get("uuid"));
			}
			case 11 -> {
				Map<String, Object> att = (Map<String, Object>) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertEquals("AGENT", att.get("actorType"));
				assertEquals(w.ca().session().getUuid().toString(), att.get("agentSession"));
			}
			case 12 -> {
				Map<String, Object> lock = (Map<String, Object>) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertEquals("RELEASED", lock.get("status"));
				assertEquals(ComponentLock.Status.RELEASED, lockStatus(w.a().org(), w.lock()));
			}
			// The tenant's FREEFORM key holds RESOURCE, not ARTIFACT_DOWNLOAD. The valid-key happy path
			// of both SBOM fields is ReleaseSbomExportProgrammaticIntegrationTest,
			// ReleaseSbomScoreProgrammaticIntegrationTest, ReleaseSbomProgrammaticAccessIntegrationTest and
			// ReleaseSbomProgrammaticOptionsIntegrationTest (keys with ARTIFACT_DOWNLOAD, rebom mocked).
			case 13, 14 -> assertEquals(NOT_AUTHORIZED, refusal(call(r, w.keyOfA(r.kind()), a), r.field()),
					r.field() + ": a key without ARTIFACT_DOWNLOAD is refused after the state check");
			default -> throw new IllegalStateException(r.toString());
		}
	}

	private static Map<String, Object> externalArtifact() {
		String name = "order-doc-" + UUID.randomUUID();
		Map<String, Object> link = new HashMap<>();
		link.put("uri", "https://example.com/" + name);
		link.put("content", ContentEnum.OCTET_STREAM.name());
		Map<String, Object> a = new HashMap<>();
		a.put("type", ArtifactType.USER_DOCUMENT.name());
		a.put("displayIdentifier", name);
		a.put("storedIn", StoredIn.EXTERNALLY.name());
		a.put("downloadLinks", new ArrayList<>(List.of(link)));
		a.put("file", new MockMultipartFile("file", name + ".txt", "text/plain", "doc".getBytes(StandardCharsets.UTF_8)));
		return a;
	}

	private static Map<String, Object> deliverablesInput(UUID release) {
		Map<String, Object> deliverable = new HashMap<>();
		deliverable.put("displayIdentifier", "order-outbound-" + UUID.randomUUID());
		deliverable.put("type", CdxType.FILE.name());
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.RELEASE_FIELD, release.toString());
		input.put("deliverables", new ArrayList<>(List.of(deliverable)));
		return input;
	}

	/** A resolver environment carrying the principal the programmatic filter leaves on the request. */
	private DgsDataFetchingEnvironment asEnv(ApiKey key, String argument, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.21"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	// ---------- case 4: today's not-found texts for a key of the organisation, healthy ----------

	@Test
	public void case4NotFoundTextsUnchangedOnAHealthyInstance() throws RelizaException {
		World w = world();
		Ids a = w.idsOfA();
		for (int n : List.of(1, 2, 3)) {
			Row r = row(n);
			Ids missing = a.random(Names.COMPONENT);
			assertEquals("Component " + missing.component() + " not found",
					refusal(call(r, w.keyOfA(r.kind()), missing), r.field()), r.field());
		}
		Ids missingRelease = a.random(Names.RELEASE);
		assertEquals("Release not found: " + missingRelease.release(),
				refusal(call(row(8), w.a().freeformKey(), missingRelease), row(8).field()));
		assertEquals(CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE,
				refusal(call(row(7), w.a().freeformKey(), missingRelease), row(7).field()));
		for (int n : List.of(13, 14)) {
			assertEquals(NOT_AUTHORIZED, refusal(call(row(n), w.a().freeformKey(), missingRelease), row(n).field()),
					row(n).field() + ": an unknown release uuid reads as one the key may not read");
		}

		Ids missingSession = a.random(Names.SESSION);
		for (int n : List.of(11, 12)) {
			Row r = row(n);
			assertEquals(NOT_AUTHORIZED, refusal(call(r, w.ca().key(), missingSession), r.field()),
					r.field() + ": a missing session reads as a foreign one after the reorder");
		}
		UUID missingLock = UUID.randomUUID();
		Ids realSessionMissingLock = new Ids(a.component(), a.release(), a.session(), missingLock, a.branch(), a.commit());
		assertEquals("Lock not found: " + missingLock,
				refusal(call(row(12), w.ca().key(), realSessionMissingLock), row(12).field()));
	}

	// ---------- case 5: a refused call writes nothing ----------

	@Test
	public void case5ARefusedCallWritesNothing() throws RelizaException {
		World w = world();
		Ids a = w.idsOfA();
		int releases = releaseCount(w.a().component().getUuid());
		int votes = approvalEventCount(w.a().release());
		int attestations = attestationCount(w.a());
		licenseStatus.setSystemSealed(true);

		for (int n : List.of(5, 6, 11, 12)) {
			Row r = row(n);
			assertEquals(SEALED, refusal(call(r, w.keyOfA(r.kind()), a), r.field()), r.field());
		}
		licenseStatus.setSystemSealed(false);
		assertEquals(releases, releaseCount(w.a().component().getUuid()), "no release is created");
		assertEquals(votes, approvalEventCount(w.a().release()), "no vote is recorded");
		assertEquals(ComponentLock.Status.ACTIVE, lockStatus(w.a().org(), w.lock()), "the lock stays active");
		assertEquals(attestations, attestationCount(w.a()), "no attestation is filed");
	}

	// ---------- case 6: an unverified Basic credential is refused before any lookup ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void case6AnUnverifiedBasicCredentialReadsNotAuthorizedForEveryObject(Row r) throws RelizaException {
		World w = world();
		Ids a = w.idsOfA();
		int votes = approvalEventCount(w.a().release());
		String id = forgedId(r);
		String secret = randomSecret();

		String missing = refusal(asBasic(id, secret, r.op(), r.vars().apply(a.random(r.names()))), r.field());
		String real = refusal(asBasic(id, secret, r.op(), r.vars().apply(a)), r.field());
		assertEquals(NOT_AUTHORIZED, missing, r.field());
		assertEquals(missing, real, r.field() + ": missing and existing read alike to a forged credential");
		if (r.n() == 9) {
			assertEquals(votes, approvalEventCount(w.a().release()), "no vote is recorded");
		}
	}

	// ---------- case 7: names, a real id with a wrong secret, the state, and no access row ----------

	@Test
	public void case7WrongSecretLearnsNoNameNoObjectAndNoState() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		long accessBefore = accessRows(t.org(), t.freeformKey().getUuid());

		// an organisation's uuid as a legacy key id, with a secret it does not have: names in that org
		Row byName = row(2);
		String orgId = ApiTypeEnum.ORGANIZATION_RW.name() + "__" + t.org();
		String realName = refusal(asBasic(orgId, randomSecret(), byName.op(), m("c", ComponentData.dataFromRecord(t.component()).getName())),
				byName.field());
		String unknownName = refusal(asBasic(orgId, randomSecret(), byName.op(), m("c", "no-such-" + UUID.randomUUID())),
				byName.field());
		assertEquals(NOT_AUTHORIZED, realName);
		assertEquals(realName, unknownName);
		// the same on a write: addReleaseProgrammatic resolves a name in the org the id names
		// (an unknown one read "Component cannot be resolved: ..." before the key was checked)
		String createOp = "mutation($c: ID, $b: String) { addReleaseProgrammatic(release: { version: \"1.0.4\","
				+ " component: $c, branch: $b }) { uuid } }";
		String createRealName = refusal(asBasic(orgId, randomSecret(), createOp,
				m("c", ComponentData.dataFromRecord(t.component()).getName(), "b", t.branch().getUuid())),
				"addReleaseProgrammatic");
		String createUnknownName = refusal(asBasic(orgId, randomSecret(), createOp,
				m("c", "no-such-" + UUID.randomUUID(), "b", t.branch().getUuid())), "addReleaseProgrammatic");
		assertEquals(NOT_AUTHORIZED, createRealName);
		assertEquals(createRealName, createUnknownName);

		// the tenant's real FREEFORM key id with a wrong secret, healthy and then sealed
		Row latest = row(3);
		String realId = freeformId(t.org(), t.freeformKey().getKeyOrder());
		Ids a = w.idsOfA();
		for (boolean sealed : List.of(false, true)) {
			licenseStatus.setSystemSealed(sealed);
			String real = refusal(asBasic(realId, randomSecret(), latest.op(), latest.vars().apply(a)), latest.field());
			String missing = refusal(asBasic(realId, randomSecret(), latest.op(),
					latest.vars().apply(a.random(Names.COMPONENT))), latest.field());
			assertEquals(NOT_AUTHORIZED, real, "sealed=" + sealed + ": the state is told to verified credentials only");
			assertEquals(real, missing, "sealed=" + sealed);
		}
		licenseStatus.setSystemSealed(false);
		assertEquals(accessBefore, accessRows(t.org(), t.freeformKey().getUuid()),
				"a call refused at verification writes no access row");
	}

	// ---------- case 8: a valid Basic credential still works ----------

	@Test
	@SuppressWarnings("unchecked")
	public void case8AValidBasicCredentialStillWorks() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = w.idsOfA();
		String freeformId = freeformId(t.org(), t.freeformKey().getKeyOrder());

		Row latest = row(3);
		Map<String, Object> rel = (Map<String, Object>) result(asBasic(freeformId, t.freeformSecret(), latest.op(),
				latest.vars().apply(a)), latest.field());
		assertEquals(t.release().toString(), rel.get("uuid"));
		assertEquals("1.0.0", rel.get("version"));
		Ids missing = a.random(Names.COMPONENT);
		assertEquals("Component " + missing.component() + " not found", refusal(asBasic(freeformId, t.freeformSecret(),
				latest.op(), latest.vars().apply(missing)), latest.field()));

		String op = "mutation($c: ID, $b: String) { addReleaseProgrammatic(release: { version: \"1.0.3\", component: $c,"
				+ " branch: $b }) { uuid version } }";
		Map<String, Object> created = (Map<String, Object>) result(asBasic(ApiTypeEnum.ORGANIZATION_RW.name() + "__" + t.org(),
				t.orgSecret(), op, m("c", a.component(), "b", a.branch())), "addReleaseProgrammatic");
		assertEquals("1.0.3", created.get("version"));
		assertTrue(sharedReleaseService.getReleaseData(UUID.fromString((String) created.get("uuid"))).isPresent());
	}
}
