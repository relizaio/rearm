/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData;
import io.reliza.model.PullRequestData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.BranchService;
import io.reliza.service.PullRequestService;
import io.reliza.ws.saas.SaasComponentDataFetcher;

/**
 * TPF-23 (TPF-17 child 5): the five programmatic CI writes (getversion, createcomponent with and
 * without a perspective, syncbranches, pullrequest upsert) verify the credential, then check the
 * system state, then look their reference up. Calls go through the DGS executor (the wire text), as
 * the bearer principal the filter leaves or as a Basic header the resolver verifies itself.
 */
class ProgrammaticComponentWriteOrderTest extends PerspectiveCreatePathsTestBase {

	private static final String NOT_AUTHORIZED = "Not authorized";
	private static final String SEALED = "System is sealed. Please unseal the system first.";
	private static final String UNLICENSED = "License is invalid or expired. Please upload a valid license.";

	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired BranchService branchService;
	@Autowired PullRequestService pullRequestService;
	@Autowired ApiKeyAccessService apiKeyAccessService;

	// ---------- the field table ----------

	/** Which keys a row serves besides FREEFORM. */
	enum KeyKind { FREEFORM, ORGANIZATION_RW, COMPONENT }

	/** The references a call can name: a component, a VCS (uuid and URI) and a perspective. */
	record Ids(UUID component, UUID vcs, String vcsUri, UUID perspective) {
		static Ids random() {
			return new Ids(UUID.randomUUID(), UUID.randomUUID(), "https://github.com/tpf23/none-" + UUID.randomUUID(),
					UUID.randomUUID());
		}
	}

	/** One field: its first form, the vcsUri form where it has one (rows 1, 4, 5), the key kinds it serves. */
	record Row(int n, String field, String op, Function<Ids, Map<String, Object>> vars, String byUriOp,
			Set<KeyKind> kinds) {
		Map<String, Object> byUri(Ids ids) {
			return ProgrammaticComponentWriteOrderTest.vars("u", ids.vcsUri());
		}

		@Override
		public String toString() {
			return n + " " + field;
		}
	}

	private static Map<String, Object> vars(Object... kv) {
		Map<String, Object> out = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], null == kv[i + 1] ? null : String.valueOf(kv[i + 1]));
		return out;
	}

	private static final List<Row> ROWS = List.of(
			new Row(1, "getNewVersionProgrammatic",
					"mutation($c: ID) { getNewVersionProgrammatic(newVersionInput: { component: $c, branch: \"main\","
							+ " onlyVersion: true }) { version } }",
					i -> vars("c", i.component()),
					"mutation($u: String) { getNewVersionProgrammatic(newVersionInput: { vcsUri: $u, repoPath: \"\","
							+ " branch: \"main\", onlyVersion: true }) { version } }",
					Set.of(KeyKind.FREEFORM, KeyKind.ORGANIZATION_RW, KeyKind.COMPONENT)),
			new Row(2, "createComponentProgrammatic",
					"mutation($n: String!, $v: ID) { createComponentProgrammatic(component: { name: $n, type: COMPONENT,"
							+ " versionSchema: \"semver\", vcs: $v }) { uuid } }",
					i -> vars("n", "tpf23-" + tag(), "v", i.vcs()), null,
					Set.of(KeyKind.FREEFORM, KeyKind.ORGANIZATION_RW)),
			new Row(3, "createComponentInPerspectiveProgrammatic",
					"mutation($n: String!, $p: ID!) { createComponentInPerspectiveProgrammatic(component: { name: $n,"
							+ " type: COMPONENT, versionSchema: \"semver\" }, perspectiveUuid: $p) { uuid } }",
					i -> vars("n", "tpf23-" + tag(), "p", i.perspective()), null,
					Set.of(KeyKind.FREEFORM)),
			new Row(4, "synchronizeLiveBranches",
					"mutation($c: String) { synchronizeLiveBranches(synchronizeBranchInput: { component: $c,"
							+ " liveBranches: [\"main\"] }) }",
					i -> vars("c", i.component()),
					"mutation($u: String) { synchronizeLiveBranches(synchronizeBranchInput: { vcsUri: $u, repoPath: \"\","
							+ " liveBranches: [\"main\"] }) }",
					Set.of(KeyKind.FREEFORM, KeyKind.ORGANIZATION_RW, KeyKind.COMPONENT)),
			new Row(5, "upsertPullRequestProgrammatic",
					"mutation($c: ID) { upsertPullRequestProgrammatic(input: { identity: \"1\", state: \"OPEN\","
							+ " component: $c }) { uuid } }",
					i -> vars("c", i.component()),
					"mutation($u: String) { upsertPullRequestProgrammatic(input: { identity: \"1\", state: \"OPEN\","
							+ " vcsUri: $u }) { uuid } }",
					Set.of(KeyKind.FREEFORM, KeyKind.ORGANIZATION_RW, KeyKind.COMPONENT)));

	static Stream<Row> rows() { return ROWS.stream(); }

	private static Row row(int n) { return ROWS.get(n - 1); }

	// ---------- fixtures ----------

	/** The base world, A's component on a VCS, A's keys and secrets, B's org-wide writer, A's bare key. */
	record Fx(World w, ComponentData compA, String uriA, String freeformSecretA, ApiKey orgKeyA,
			ApiKey componentKeyA, String componentSecretA, ApiKey writerB, ApiKey bareA) {
		Ids idsOfA() {
			return new Ids(compA.getUuid(), compA.getVcs(), uriA, w.pA());
		}

		ApiKey keyOfA(KeyKind kind) {
			return switch (kind) {
				case FREEFORM -> w.freeformA();
				case ORGANIZATION_RW -> orgKeyA;
				case COMPONENT -> componentKeyA;
			};
		}
	}

	private Fx fx() throws RelizaException {
		World w = world();
		String uriA = "https://github.com/tpf23/repo-" + tag();
		ComponentData compA = componentService.createComponentFromVcsUri(w.orgA(), uriA, null, null, null, "semver",
				"Branch.Micro", WU);
		String freeformSecretA = apiKeyService.addApiKeySecret(w.freeformA().getUuid(), null, WU);
		ApiKey orgKeyA = apiKeyService.getApiKeyByObjUuidTypeOrder(w.orgA(), ApiTypeEnum.ORGANIZATION_RW, null, w.orgA())
				.orElseThrow();
		String componentSecretA = apiKeyService.setObjectApiKey(compA.getUuid(), ApiTypeEnum.COMPONENT, w.orgA(), null,
				"order test", false, WU);
		ApiKey componentKeyA = apiKeyService.getApiKeyByObjUuidTypeOrder(compA.getUuid(), ApiTypeEnum.COMPONENT, null,
				w.orgA()).orElseThrow();
		ApiKey writerB = freeformKey(w.orgB(), PermissionType.READ_WRITE, Set.of(PermissionFunction.RESOURCE));
		ApiKey bareA = freeformKey(w.orgA(), PermissionType.READ_ONLY, Set.of());
		return new Fx(w, compA, uriA, freeformSecretA, orgKeyA, componentKeyA, componentSecretA, writerB, bareA);
	}

	private ApiKey freeformKey(UUID org, PermissionType type, Set<PermissionFunction> functions) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org, UUID.randomUUID().toString(),
				"tpf23 freeform key", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null,
				List.of(new PermissionDto(org, PermissionScope.ORGANIZATION, org, type, functions, null)), WU);
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
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
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.23"));
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

	private ExecutionResult call(Row r, ApiKey key, Ids ids) { return asKey(key, r.op(), r.vars().apply(ids)); }

	private ExecutionResult callByUri(Row r, ApiKey key, Ids ids) { return asKey(key, r.byUriOp(), r.byUri(ids)); }

	private static String freeformId(UUID org, String keyOrder) {
		return ApiTypeEnum.FREEFORM.name() + "__" + org + "__ord__" + keyOrder;
	}

	private static String orgRwId(UUID org) { return ApiTypeEnum.ORGANIZATION_RW.name() + "__" + org; }

	private static String componentId(UUID component) { return ApiTypeEnum.COMPONENT.name() + "__" + component; }

	private static String randomSecret() {
		return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
	}

	/** A Basic call with a secret the key id does not have; returns the refusal. */
	private String forged(String keyId, String op, Map<String, Object> vars, String field) {
		return refusal(asBasic(keyId, randomSecret(), op, vars), field);
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

	/** A success with a non-null {@code member} under the field. */
	@SuppressWarnings("unchecked")
	private static String member(ExecutionResult r, String field, String member, String why) {
		Map<String, Object> out = (Map<String, Object>) result(r, field);
		assertNotNull(null == out ? null : out.get(member), why + ": " + out);
		return (String) out.get(member);
	}

	private static String uuidOf(ExecutionResult r, String field, String why) { return member(r, field, "uuid", why); }

	private static String versionOf(ExecutionResult r) { return member(r, row(1).field(), "version", "version"); }

	private int activeBranches(UUID component) {
		return branchService.listBranchesOfComponent(component, StatusEnum.ACTIVE).size();
	}

	private Set<UUID> prsOf(UUID vcs) {
		return pullRequestService.listByTargetRepository(vcs).stream().map(PullRequestData::getUuid)
				.collect(Collectors.toSet());
	}

	private long accessRows(UUID org, UUID keyUuid) {
		return apiKeyAccessService.listKeyAccessByOrg(org).stream().filter(a -> keyUuid.equals(a.getApiKeyUuid())).count();
	}

	// ---------- completeness ----------

	@Test
	void theTableCoversEveryKeyFieldOfTheFourFetchers() throws IOException {
		String sdl = new String(new ClassPathResource("schema/programmatic.graphqls").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		Map<String, Set<String>> roots = ProgrammaticSchemaRegistry.rootFieldsOfExtensions(sdl);
		Set<String> programmaticRoots = new TreeSet<>(roots.getOrDefault("Query", Set.of()));
		programmaticRoots.addAll(roots.getOrDefault("Mutation", Set.of()));

		Set<String> expected = new TreeSet<>();
		for (Class<?> fetcher : List.of(ComponentDataFetcher.class, SaasComponentDataFetcher.class,
				BranchDataFetcher.class, PullRequestDataFetcher.class)) {
			for (Method method : fetcher.getDeclaredMethods()) {
				DgsData d = method.getAnnotation(DgsData.class);
				if (null != d && ("Query".equals(d.parentType()) || "Mutation".equals(d.parentType()))) {
					expected.add(d.field());
				}
			}
		}
		expected.retainAll(programmaticRoots);
		// no exclusion: every programmatic root field of the four fetchers carries the two statements
		assertEquals(5, expected.size(), expected.toString());
		assertEquals(expected, new TreeSet<>(ROWS.stream().map(Row::field).toList()));
	}

	// ---------- case 1: the sealed sentence first, for every valid key ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	void case1SealedSentenceFirstForEveryValidKey(Row r) throws RelizaException {
		Fx f = fx();
		licenseStatus.setSystemSealed(true);
		assertOneSentence(r, f, f.idsOfA(), SEALED);
	}

	@Test
	void case1TheLicenceSentenceComesFirstToo() throws RelizaException {
		Fx f = fx();
		licenseStatus.setLicenseValid(false);
		assertOneSentence(row(4), f, f.idsOfA(), UNLICENSED);
	}

	/** Own and foreign key, random and real reference, and on rows 1 and 4 the vcsUri form too. */
	private void assertOneSentence(Row r, Fx f, Ids a, String sentence) {
		for (ApiKey key : List.of(f.w().freeformA(), f.writerB())) {
			String missing = refusal(call(r, key, Ids.random()), r.field());
			assertEquals(sentence, missing, r.field() + " random");
			assertEquals(missing, refusal(call(r, key, a), r.field()), r.field() + ": missing and existing read alike");
			if (r.n() == 1 || r.n() == 4) {
				assertEquals(sentence, refusal(callByUri(r, key, Ids.random()), r.field()), r.field() + " unknown vcsUri");
				assertEquals(sentence, refusal(callByUri(r, key, a), r.field()), r.field() + " real vcsUri");
			}
		}
	}

	// ---------- case 2: a foreign key, and a key one permission short, on a healthy instance ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	void case2ForeignKeyAndMissingPermissionStillRefused(Row r) throws RelizaException {
		Fx f = fx();
		Ids a = f.idsOfA();
		String foreign = r.n() == 3 ? NOT_FOUND + f.w().pA() : NOT_AUTHORIZED;
		assertEquals(foreign, refusal(call(r, f.writerB(), a), r.field()), r.field() + ": foreign key");
		assertEquals(NOT_AUTHORIZED, refusal(call(r, f.bareA(), a), r.field()), r.field() + ": one permission short");
		if (r.n() == 3) {
			assertEquals(NOT_AUTHORIZED, refusal(call(r, f.orgKeyA(), a), r.field()), "a legacy key on the perspective row");
		}
	}

	// ---------- case 3: the happy path per row, and the legacy branches ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	void case3HappyPath(Row r) throws RelizaException {
		Fx f = fx();
		Ids a = f.idsOfA();
		UUID compA = f.compA().getUuid();
		for (KeyKind kind : r.kinds()) {
			ApiKey key = f.keyOfA(kind);
			String why = r.field() + " with " + kind;
			switch (r.n()) {
				case 1 -> versionOf(asKey(key, r.op(), vars("c", KeyKind.COMPONENT == kind ? null : compA)));
				case 2 -> {
					Set<UUID> before = components(f.w().orgA());
					String uuid = uuidOf(asKey(key, "mutation($c: CreateComponentInput!) { createComponentProgrammatic("
							+ "component: $c) { uuid } }", Map.of("c", componentInput("tpf23-" + tag(), null, vcsUri()))), r.field(), why);
					assertEquals(UUID.fromString(uuid), createdSince(f.w().orgA(), before).getUuid(), why);
				}
				case 3 -> {
					Map<String, Object> vars = new HashMap<>();
					vars.put("c", componentInput("tpf23-" + tag(), null, null));
					vars.put("p", f.w().pA().toString());
					String uuid = uuidOf(asKey(key, "mutation($c: CreateComponentInput!, $p: ID!) {"
							+ " createComponentInPerspectiveProgrammatic(component: $c, perspectiveUuid: $p) { uuid } }", vars), r.field(), why);
					assertEquals(Set.of(f.w().pA()), getComponentService.getComponentData(UUID.fromString(uuid)).orElseThrow()
							.getPerspectives(), why);
				}
				case 4 -> {
					int branches = activeBranches(compA);
					assertEquals(Boolean.TRUE, result(call(r, key, a), r.field()), why);
					assertEquals(branches, activeBranches(compA), why);
				}
				case 5 -> {
					String uuid = uuidOf(call(r, key, a), r.field(), why);
					assertTrue(prsOf(f.compA().getVcs()).contains(UUID.fromString(uuid)), why);
				}
				default -> throw new IllegalStateException(r.toString());
			}
		}
	}

	// ---------- case 4: today's not-found texts for a key of the organisation, healthy ----------

	@Test
	void case4NotFoundTextsUnchangedOnAHealthyInstance() throws RelizaException {
		Fx f = fx();
		ApiKey key = f.w().freeformA();
		Ids missing = Ids.random();
		String r1 = refusal(callByUri(row(1), key, missing), row(1).field());
		assertTrue(r1.startsWith("Component cannot be resolved: "), r1);
		assertTrue(refusal(call(row(2), key, missing), row(2).field()).startsWith("Internal server error"),
				"the orElseThrow on a missing vcs uuid, pinned for TPF-18");
		assertEquals(NOT_FOUND + missing.perspective(), refusal(call(row(3), key, missing), row(3).field()));
		assertEquals(Boolean.TRUE, result(call(row(4), key, missing), row(4).field()),
				"the silent success for a uuid that is no component, pinned for TPF-18");
		String r4 = refusal(callByUri(row(4), key, missing), row(4).field());
		assertTrue(r4.startsWith("VCS repository "), r4);
		assertEquals("Component not found: " + missing.component(), refusal(call(row(5), key, missing), row(5).field()));
	}

	// ---------- case 5: a refused call writes nothing ----------

	@Test
	void case5ARefusedCallWritesNothing() throws RelizaException {
		Fx f = fx();
		Ids a = f.idsOfA();
		ApiKey key = f.w().freeformA();
		UUID compA = f.compA().getUuid();
		// a live branch the call would archive if it ran
		branchService.findBranchByName(compA, "feature/tpf23-" + tag(), true, WU);
		int branches = activeBranches(compA);
		Set<UUID> prs = prsOf(f.compA().getVcs());
		Snapshot before = snapshot(f.w());
		licenseStatus.setSystemSealed(true);

		assertEquals(SEALED, refusal(asKey(key, "mutation($in: GetNewVersionInput!) { getNewVersionProgrammatic("
				+ "newVersionInput: $in) { version } }", Map.of("in", newVersionInput(vcsUri(), f.w().pA()))), row(1).field()));
		for (int n : List.of(2, 3, 4, 5)) {
			assertEquals(SEALED, refusal(call(row(n), key, a), row(n).field()), row(n).field());
		}
		licenseStatus.setSystemSealed(false);
		assertNothingWritten(f.w(), before);
		assertEquals(branches, activeBranches(compA), "no branch is archived");
		assertEquals(prs, prsOf(f.compA().getVcs()), "no pull request is upserted");
	}

	// ---------- case 6: an unverified Basic credential is refused before any lookup ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	void case6AnUnverifiedBasicCredentialReadsNotAuthorizedForEveryReference(Row r) throws RelizaException {
		Fx f = fx();
		Ids a = f.idsOfA();
		// the third id is forged but carries a real organisation in its text
		List<String> ids = new ArrayList<>(List.of(freeformId(UUID.randomUUID(), "0"), orgRwId(UUID.randomUUID()),
				orgRwId(f.w().orgA())));
		if (r.kinds().contains(KeyKind.COMPONENT)) {
			ids.addAll(List.of(componentId(UUID.randomUUID()), componentId(f.compA().getUuid())));
		}
		for (String id : ids) {
			for (Ids ref : List.of(Ids.random(), a)) {
				assertEquals(NOT_AUTHORIZED, forged(id, r.op(), r.vars().apply(ref), r.field()), r.field() + " as " + id);
			}
		}
		for (Ids ref : (r.n() == 1 || r.n() == 4) ? List.of(Ids.random(), a) : List.<Ids>of()) {
			assertEquals(NOT_AUTHORIZED, forged(orgRwId(f.w().orgA()), r.byUriOp(), r.byUri(ref), r.field()), "vcsUri form");
		}
	}

	// ---------- case 7: a real id with a wrong secret, the state, and no access row ----------

	@Test
	void case7WrongSecretLearnsNoNameNoPerspectiveAndNoState() throws RelizaException {
		Fx f = fx();
		Ids a = f.idsOfA();
		long freeformRows = accessRows(f.w().orgA(), f.w().freeformA().getUuid());
		long orgRows = accessRows(f.w().orgA(), f.orgKeyA().getUuid());
		String realFreeform = freeformId(f.w().orgA(), f.w().freeformA().getKeyOrder());
		String realOrgRw = orgRwId(f.w().orgA());
		Row r1 = row(1);
		Row r3 = row(3);
		for (boolean sealed : List.of(false, true)) {
			licenseStatus.setSystemSealed(sealed);
			for (Ids ref : List.of(a, Ids.random())) {
				assertEquals(NOT_AUTHORIZED, forged(realFreeform, r1.byUriOp(), r1.byUri(ref), r1.field()), "sealed=" + sealed);
				assertEquals(NOT_AUTHORIZED, forged(realOrgRw, r3.op(), r3.vars().apply(ref), r3.field()), "sealed=" + sealed);
			}
		}
		licenseStatus.setSystemSealed(false);
		assertEquals(freeformRows, accessRows(f.w().orgA(), f.w().freeformA().getUuid()), "no access row for a wrong secret");
		assertEquals(orgRows, accessRows(f.w().orgA(), f.orgKeyA().getUuid()), "no access row for a wrong secret");
	}

	// ---------- case 8: a valid Basic credential still works ----------

	@Test
	void case8AValidBasicCredentialStillWorks() throws RelizaException {
		Fx f = fx();
		Ids a = f.idsOfA();
		Row r1 = row(1);
		versionOf(asBasic(orgRwId(f.w().orgA()), f.w().orgSecretA(), r1.op(), r1.vars().apply(a)));
		Row r4 = row(4);
		assertEquals(Boolean.TRUE, result(asBasic(freeformId(f.w().orgA(), f.w().freeformA().getKeyOrder()),
				f.freeformSecretA(), r4.op(), r4.vars().apply(a)), r4.field()));
		Row r5 = row(5);
		String pr = uuidOf(asBasic(componentId(f.compA().getUuid()), f.componentSecretA(), r5.op(), r5.vars().apply(a)),
				r5.field(), r5.field());
		assertTrue(prsOf(f.compA().getVcs()).contains(UUID.fromString(pr)));
	}
}
