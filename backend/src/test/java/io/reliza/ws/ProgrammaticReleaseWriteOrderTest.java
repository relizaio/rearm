/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentLock;
import io.reliza.model.Link.ContentEnum;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.TeaPublicationService;
import io.reliza.ws.oss.OssReleaseDatafetcher;

/**
 * TPF-21 (TPF-17 child 3): the twenty key fields of {@code ReleaseDatafetcher},
 * {@code OssReleaseDatafetcher}, {@code DeliverableDataFetcher}, {@code IntegrityDataFetcher} and
 * {@code TeaPublicationDataFetcher} (TPF-98 rows 15 and 16, TEA-25 rows 17 to 20) verify the credential,
 * then check the system state, then look their object up. A sealed or
 * unlicensed instance answers one sentence for a missing and an existing object to every valid key;
 * a Basic credential that does not verify reads {@code Not authorized} for both, on any instance;
 * a valid key on a healthy instance reads what it read before. Calls go through the DGS executor
 * (the refusal text as the wire carries it), in the bearer form (the principal the programmatic
 * filter leaves) and the Basic form (the resolver parses and verifies the header itself).
 *
 * <p>The happy path of {@code approveReleaseProgrammatic} needs an effective approval policy and an
 * approval-role grant; it is covered by the harness feature {@code approvals_programmatic.feature}
 * (rearm-integration-tests), and this class pins only its refusals.
 *
 * <p>The four TEA rows read the release in the verified key's organisation, so a foreign key reads
 * an existing release of another organisation as a missing one. The test organisations carry no TEA
 * profile; the valid-key publish path is {@code TeaPublicationIntegrationTest} and the TEA-12 harness.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ProgrammaticReleaseWriteOrderTest extends ProgrammaticReleaseWriteOrderTestBase {

	@Autowired private TeaPublicationService teaPublicationService;

	/** The TEA rows: their release read is scoped to the verified key's organisation (TEA-25). */
	private static boolean teaRow(Row r) {
		return r.n() >= 17;
	}

	/**
	 * CE: the parameterized cases run every row but 9. approveReleaseProgrammatic is a stub on CE, which
	 * has no approvals, so its order cannot be checked here (SCORE-72 3.6; CE's own
	 * ws/oss/ProgrammaticReleaseWriteOrderTest leaves it out too). Hides the base's rows().
	 */
	static Stream<Row> rows() {
		return ProgrammaticReleaseWriteOrderTestBase.rows().filter(r -> r.n() != 9);
	}

	// ---------- completeness ----------

	@Test
	public void theTableCoversEveryKeyFieldOfTheFiveFetchers() throws IOException {
		String sdl = new String(new ClassPathResource("schema/programmatic.graphqls").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		Map<String, Set<String>> roots = ProgrammaticSchemaRegistry.rootFieldsOfExtensions(sdl);
		Set<String> programmaticRoots = new TreeSet<>(roots.getOrDefault("Query", Set.of()));
		programmaticRoots.addAll(roots.getOrDefault("Mutation", Set.of()));

		Set<String> expected = new TreeSet<>();
		for (Class<?> fetcher : List.of(ReleaseDatafetcher.class, OssReleaseDatafetcher.class,
				DeliverableDataFetcher.class, IntegrityDataFetcher.class, TeaPublicationDataFetcher.class)) {
			for (Method method : fetcher.getDeclaredMethods()) {
				DgsData d = method.getAnnotation(DgsData.class);
				if (null != d && ("Query".equals(d.parentType()) || "Mutation".equals(d.parentType()))
						&& d.field().contains("Programmatic")) {
					expected.add(d.field());
				}
			}
		}
		assertTrue(programmaticRoots.containsAll(expected),
				"every key field of the five fetchers is a programmatic root field: " + expected);
		// Authorizes before any lookup: isFreeformKeyAuthorizedForAgenticSessionRead validates first
		// (TPF-17 parent row 21), so it needs no statement and has no row.
		expected.remove("agenticReleaseProgrammatic");
		assertEquals(20, expected.size(), expected.toString());
		assertEquals(expected, new TreeSet<>(ROWS.stream().map(Row::field).toList()));
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
		World w = world();
		Ids a = w.idsOfA();
		licenseStatus.setLicenseValid(false);
		for (int n : List.of(3, 20)) {
			Row r = row(n);
			String ownMissing = refusal(call(r, w.keyOfA(r.kind()), a.random(r.names())), r.field());
			assertEquals(UNLICENSED, ownMissing, r.field());
			assertEquals(ownMissing, refusal(call(r, w.keyOfA(r.kind()), a), r.field()), r.field());
			assertEquals(ownMissing, refusal(call(r, w.keyOfB(r.kind()), a.random(r.names())), r.field()), r.field());
			assertEquals(ownMissing, refusal(call(r, w.keyOfB(r.kind()), a), r.field()), r.field());
		}
	}

	// ---------- case 2: a foreign key, and a key one permission short, on a healthy instance ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void case2ForeignKeyAndMissingPermissionStillRefused(Row r) throws RelizaException {
		World w = world();
		Ids a = w.idsOfA();
		boolean targetService = r.n() == 7 || r.n() == 10;
		String expected = targetService ? CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE : NOT_AUTHORIZED;
		// TEA-25 decision 2: the TEA rows read the release in the key's organisation
		String foreign = teaRow(r) ? "Release not found: " + a.release() : expected;

		assertEquals(foreign, refusal(call(r, w.keyOfB(r.kind()), a), r.field()), r.field() + ": foreign key");

		// row 17: RESOURCE on the sibling component does not cover this release; rows 18 to 20: the
		// read-only key holds RESOURCE without PUBLISH_EXTERNALLY
		ApiKey short1 = switch (r.n()) {
			case 1, 2, 3, 4, 15, 16, 17 -> w.a().narrowKey();
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
			case 15 -> {
				String doc = (String) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertNotNull(doc);
				assertFalse("{}".equals(doc) || doc.isBlank(), doc);
				assertTrue(doc.contains("\"1.0.0\""), doc);
			}
			case 16 -> {
				List<Map<String, Object>> rels = (List<Map<String, Object>>) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertEquals(1, rels.size(), rels.toString());
				assertEquals(w.a().release().toString(), rels.get(0).get("uuid"));
				assertEquals("1.0.0", rels.get(0).get("version"));
				assertNotNull(rels.get(0).get("createdDate"));
			}
			// canPublish is not pinned: the test organisation has no TEA profile, which the preview
			// reports as a refusal of its own
			case 17 -> {
				Map<String, Object> preview = (Map<String, Object>) result(call(r, w.keyOfA(r.kind()), a), r.field());
				assertNotNull(preview, r.field());
				assertEquals(w.a().release().toString(), preview.get("release"));
			}
			// The tenant's FREEFORM key holds RESOURCE, which does not satisfy PUBLISH_EXTERNALLY. The
			// valid-key happy path is TeaPublicationIntegrationTest.publishNeedsPublishExternallyOrOrganizationAdmin
			// and the TEA-12 harness; case 9 drives a key with the function through the gate.
			case 18, 19, 20 -> assertEquals(NOT_AUTHORIZED, refusal(call(r, w.keyOfA(r.kind()), a), r.field()),
					r.field() + ": a key without PUBLISH_EXTERNALLY is refused after the state check");
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
		for (int n : List.of(1, 2, 3, 15, 16)) {
			Row r = row(n);
			Ids missing = a.random(Names.COMPONENT);
			assertEquals("Component " + missing.component() + " not found",
					refusal(call(r, w.keyOfA(r.kind()), missing), r.field()), r.field());
		}
		Ids missingRelease = a.random(Names.RELEASE);
		assertEquals("Release not found: " + missingRelease.release(),
				refusal(call(row(8), w.a().freeformKey(), missingRelease), row(8).field()));
		// CE: no row 9 check. approveReleaseProgrammatic is a stub on CE, which has no approvals (SCORE-72 3.6).
		assertEquals(CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE,
				refusal(call(row(7), w.a().freeformKey(), missingRelease), row(7).field()));
		for (int n : List.of(13, 14)) {
			assertEquals(NOT_AUTHORIZED, refusal(call(row(n), w.a().freeformKey(), missingRelease), row(n).field()),
					row(n).field() + ": an unknown release uuid reads as one the key may not read");
		}
		for (int n : List.of(17, 18, 19, 20)) {
			assertEquals("Release not found: " + missingRelease.release(),
					refusal(call(row(n), w.a().freeformKey(), missingRelease), row(n).field()),
					row(n).field() + ": the text is unchanged and now follows the gate");
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

		// CE: row 9 (approveReleaseProgrammatic) is left out, a stub on CE (SCORE-72 3.6).
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
		// TEA-25 F-3: the same on a TEA row, healthy and then sealed
		Row view = row(17);
		for (boolean sealed : List.of(false, true)) {
			licenseStatus.setSystemSealed(sealed);
			String real = refusal(asBasic(realId, randomSecret(), view.op(), view.vars().apply(a)), view.field());
			String missing = refusal(asBasic(realId, randomSecret(), view.op(),
					view.vars().apply(a.random(Names.RELEASE))), view.field());
			assertEquals(NOT_AUTHORIZED, real, view.field() + " sealed=" + sealed);
			assertEquals(real, missing, view.field() + " sealed=" + sealed);
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

	// ---------- case 9: TEA acts with the function reach the service after the gate ----------

	@Test
	public void case9TeaActsWithTheFunctionReachTheServiceAfterTheGate() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = w.idsOfA();
		ApiKey publisher = freeformKey(t.org(), PermissionScope.ORGANIZATION, t.org(), PermissionType.READ_WRITE,
				Set.of(PermissionFunction.RESOURCE, PermissionFunction.PUBLISH_EXTERNALLY));

		licenseStatus.setSystemSealed(true);
		for (int n : List.of(18, 19, 20)) {
			Row r = row(n);
			assertEquals(SEALED, refusal(call(r, publisher, a), r.field()), r.field());
		}
		licenseStatus.setSystemSealed(false);
		assertTrue(teaPublicationService.getPublicationOfRelease(t.release()).isEmpty(),
				"a refused act writes no publication row");

		// healthy: the verified key passed the gate, the scoped read found the release, the object
		// helper admitted PUBLISH_EXTERNALLY and the service answered its own refusal; row 18 would
		// publish and is covered by TeaPublicationIntegrationTest and the TEA-12 harness
		String hide = refusal(call(row(20), publisher, a), row(20).field());
		assertTrue(hide.contains("is not published on TEA"), hide);
		String republish = refusal(call(row(19), publisher, a), row(19).field());
		assertTrue(republish.contains("is not published on TEA; publish it first"), republish);
	}
}
