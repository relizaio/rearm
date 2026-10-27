/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsData;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.ApiKeyAccessService;

/**
 * TPF-20 (TPF-17b): the seven session fields of {@link AgentDataFetcher} check the system state before
 * they look the session up, so a sealed or unlicensed instance answers a missing and an existing session
 * with the same sentence, to every key of every organisation. On a healthy instance nothing changes: the
 * organisation match, the visibility rule and every text stay as they were (TPF-18 owns the texts).
 *
 * <p>Round 2 (security F-1): the Basic lane reaches the fetcher unverified, so both helpers verify the
 * credential before anything else. A forged key id or a wrong secret reads {@code Not authorized} whatever
 * the session or organisation uuid and whatever the instance state (cases 6 to 8), and a valid Basic key
 * still works (case 9). Cases 1 to 5 use the bearer form, cases 6 to 9 the Basic form.
 *
 * <p>At the executor level, so each assertion is the message the wire carries. The happy path and the
 * readers the visibility rule admits are covered by {@code AgentBoardSessionReadsIntegrationTest} and
 * {@code AgentSessionWritesVisibilityTest}; this class adds one success per wrapper.
 */
class ProgrammaticSessionOrderTest extends AgentBoardEnforcementTestBase {

	private static final String SEALED = "System is sealed. Please unseal the system first.";
	private static final String UNLICENSED = "License is invalid or expired. Please upload a valid license.";

	@Autowired GraphQlSource graphQlSource;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired ApiKeyAccessService apiKeyAccessService;

	/** The clientSessionId form of row 4, served by authorizeProgrammaticOrgWrite. */
	private static final String USAGE_BY_CLIENT_ID = "mutation($c: String!) { sessionReportUsageProgrammatic(input: {"
			+ " clientSessionId: $c, clientSeq: 1, source: SELF_REPORTED,"
			+ " lines: [{ requests: 1, inputTokens: 1, outputTokens: 1 }] }) { __typename } }";
	/** A field that takes no session, served by authorizeProgrammaticOrgWrite. */
	private static final String INIT = "mutation { sessionInitializeProgrammatic(sessionInit: {"
			+ " agentName: \"order test\", agentModel: \"order-model\" }) { uuid } }";

	/** Every session field of the fetcher, with the smallest schema-valid operation naming {@code $s}. */
	private static final Map<String, String> FIELDS = new LinkedHashMap<>();
	static {
		FIELDS.put("sessionProgrammatic",
				"query($s: ID!) { sessionProgrammatic(sessionUuid: $s) { uuid } }");
		FIELDS.put("agentSessionInboxProgrammatic",
				"query($s: ID!) { agentSessionInboxProgrammatic(inboxRequest: { sessionUuid: $s }) { kind } }");
		FIELDS.put("sessionTouchProgrammatic",
				"mutation($s: ID!) { sessionTouchProgrammatic(sessionUuid: $s) { uuid } }");
		FIELDS.put("sessionReportUsageProgrammatic",
				"mutation($s: ID!) { sessionReportUsageProgrammatic(input: { sessionUuid: $s, clientSeq: 1,"
						+ " source: SELF_REPORTED, lines: [{ requests: 1, inputTokens: 1, outputTokens: 1 }] }) { __typename } }");
		FIELDS.put("sessionAddArtifactProgrammatic",
				"mutation($s: ID!) { sessionAddArtifactProgrammatic(addArtifact: { sessionUuid: $s, artifacts: [{}] }) { uuid } }");
		FIELDS.put("sessionUpdateMetaProgrammatic",
				"mutation($s: ID!) { sessionUpdateMetaProgrammatic(updateMeta: { uuid: $s }) { uuid } }");
		// terminal on success: last, so a case that walks the table never closes the session it still uses
		FIELDS.put("sessionCloseProgrammatic",
				"mutation($s: ID!) { sessionCloseProgrammatic(sessionUuid: $s) { uuid } }");
	}

	/** The programmatic fields of the fetcher that take no session (TPF-17 rows 70, 76, 77). */
	private static final Set<String> NO_SESSION = Set.of("sessionInitializeProgrammatic", "spawnSubAgentProgrammatic",
			"setModelOntologyModelCardProgrammatic");

	static Stream<Arguments> fields() {
		return FIELDS.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
	}

	private ExecutionResult call(ApiKey key, String op, UUID session) {
		return asKey(key, op, vars("s", session.toString()));
	}

	/**
	 * Exactly one refusal, nothing under the field; returns its message. A field of non-null type
	 * ({@code agentSessionInboxProgrammatic}) also carries the engine's own NullValueInNonNullableField
	 * error for the null the refusal leaves; that one is not a refusal and is set aside.
	 */
	private static String refusal(ExecutionResult r, String field) {
		assertFalse(r.getErrors().isEmpty(), field + ": expected a refusal, got " + String.valueOf((Object) r.getData()));
		List<GraphQLError> refusals = r.getErrors().stream()
				.filter(e -> !"NullValueInNonNullableField".equals(String.valueOf(e.getExtensions() == null ? null
						: e.getExtensions().get("classification"))))
				.toList();
		assertEquals(1, refusals.size(), field + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		assertNull(null == data ? null : data.get(field), field + ": a refused call returns nothing");
		return refusals.get(0).getMessage();
	}

	private ApiKey adminKey(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "session order admin", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.ADMIN, Set.of(PermissionFunction.AGENT),
				null)), WU);
		return key;
	}

	// ---------- completeness ----------

	@Test
	void theTableNamesEverySessionFieldOfTheFetcher() {
		Set<String> declared = new TreeSet<>();
		for (Method m : AgentDataFetcher.class.getDeclaredMethods()) {
			DgsData d = m.getAnnotation(DgsData.class);
			if (null != d && d.field().endsWith("Programmatic")) declared.add(d.field());
		}
		Set<String> expected = new TreeSet<>(FIELDS.keySet());
		expected.addAll(NO_SESSION);
		assertEquals(expected, declared, "a programmatic field of AgentDataFetcher needs a row here or a place in NO_SESSION");

		Set<String> sessionNaming = new TreeSet<>();
		for (GraphQLObjectType root : List.of(graphQlSource.schema().getQueryType(), graphQlSource.schema().getMutationType())) {
			for (GraphQLFieldDefinition f : root.getFieldDefinitions()) {
				if (SessionActivityInstrumentation.counts(root, f) && declared.contains(f.getName())) {
					sessionNaming.add(f.getName());
				}
			}
		}
		assertFalse(sessionNaming.isEmpty(), "the schema names session fields of the fetcher");
		assertTrue(FIELDS.keySet().containsAll(sessionNaming),
				"every session-naming field of the fetcher is in the table: " + sessionNaming);
		assertTrue(java.util.Collections.disjoint(NO_SESSION, sessionNaming), "NO_SESSION names no session field");
	}

	// ---------- case 1: the sealed sentence first, for every key ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("fields")
	void case1SealedAnswersMissingAndExistingAlike(String field, String op) throws RelizaException {
		World a = world();
		World b = world();
		Caller own = key(a);
		Caller foreign = key(b);
		licenseStatus.setSystemSealed(true);

		String missing = refusal(call(own.key(), op, UUID.randomUUID()), field);
		String existing = refusal(call(own.key(), op, own.session().getUuid()), field);
		assertEquals(SEALED, missing, field + ": a missing uuid reads the sealed sentence, not Session not found");
		assertEquals(missing, existing, field + ": the own key cannot tell a missing session from its own");
		assertTrue(missing.contains("sealed"), missing);

		String foreignMissing = refusal(call(foreign.key(), op, UUID.randomUUID()), field);
		String foreignExisting = refusal(call(foreign.key(), op, own.session().getUuid()), field);
		assertEquals(SEALED, foreignMissing, field);
		assertEquals(foreignMissing, foreignExisting, field + ": a foreign key learns nothing on a sealed instance");
	}

	@Test
	void case1UnlicensedAnswersMissingAndExistingAlike() throws RelizaException {
		World a = world();
		World b = world();
		Caller own = key(a);
		Caller foreign = key(b);
		String op = FIELDS.get("sessionProgrammatic");
		// the seal is checked before the licence, so unseal for the licence sentence
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(false);

		for (ApiKey k : List.of(own.key(), foreign.key())) {
			String missing = refusal(call(k, op, UUID.randomUUID()), "sessionProgrammatic");
			String existing = refusal(call(k, op, own.session().getUuid()), "sessionProgrammatic");
			assertEquals(UNLICENSED, missing, "a missing uuid reads the licence sentence");
			assertEquals(missing, existing, "missing and existing read alike on an unlicensed instance");
		}
	}

	// ---------- case 2: foreign and invisible keys still refused on a healthy instance ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("fields")
	void case2ForeignAndInvisibleKeysStillRefused(String field, String op) throws RelizaException {
		World a = world();
		World b = world();
		Caller own = key(a);
		Caller sibling = key(a);
		Caller foreign = key(b);

		assertEquals("Not authorized", refusal(call(foreign.key(), op, own.session().getUuid()), field),
				field + ": a key of another organisation is refused by the organisation match");
		assertEquals("Session not found: " + own.session().getUuid(),
				refusal(call(sibling.key(), op, own.session().getUuid()), field),
				field + ": another key of the organisation reads the unknown-uuid answer");
		AgentSessionData after = agentSessionService.getSessionData(own.session().getUuid()).orElseThrow();
		assertEquals(own.session().getStatus(), after.getStatus(), field + ": a refused call leaves the session as it was");
	}

	// ---------- case 3: one executor-level success per wrapper ----------

	@Test
	void case3TheLineIsHarmlessOnAHealthyInstance() throws RelizaException {
		World a = world();
		Caller own = key(a);
		UUID s = own.session().getUuid();

		Map<String, Object> read = data(call(own.key(), FIELDS.get("sessionProgrammatic"), s), "sessionProgrammatic");
		assertEquals(s.toString(), read.get("uuid"), "the own key reads its session");

		Map<String, Object> touched = data(call(own.key(), FIELDS.get("sessionTouchProgrammatic"), s),
				"sessionTouchProgrammatic");
		assertEquals(s.toString(), touched.get("uuid"), "the own key touches its session");

		ExecutionResult renamed = asKey(adminKey(a.org()),
				"mutation($s: ID!) { sessionUpdateMetaProgrammatic(updateMeta: { uuid: $s, title: \"renamed by an admin\" }) { uuid title } }",
				vars("s", s.toString()));
		Map<String, Object> meta = data(renamed, "sessionUpdateMetaProgrammatic");
		assertEquals("renamed by an admin", meta.get("title"), "an ADMIN key of the organisation renames it");
	}

	// ---------- case 4: not-found texts unchanged on a healthy instance ----------

	@Test
	void case4NotFoundTextsUnchanged() throws RelizaException {
		World a = world();
		World b = world();
		Caller own = key(a);
		Caller foreign = key(b);
		for (String field : List.of("sessionProgrammatic", "sessionCloseProgrammatic")) {
			for (ApiKey k : List.of(own.key(), foreign.key())) {
				UUID random = UUID.randomUUID();
				assertEquals("Session not found: " + random, refusal(call(k, FIELDS.get(field), random), field), field);
			}
		}
	}

	// ---------- case 5: a sealed refusal is not activity ----------

	@Test
	void case5ASealedRefusalIsNotActivity() throws RelizaException {
		World a = world();
		Caller own = key(a);
		UUID s = own.session().getUuid();
		double epoch = ZonedDateTime.now().minusHours(1).toEpochSecond();
		jdbcTemplate.update("UPDATE rearm.agent_sessions SET record_data = jsonb_set(record_data, '{lastActivityAt}',"
				+ " to_jsonb(?::numeric)) WHERE uuid = ?", epoch, s);
		ZonedDateTime before = agentSessionService.getSessionData(s).orElseThrow().getLastActivityAt();
		licenseStatus.setSystemSealed(true);

		assertEquals(SEALED, refusal(call(own.key(), FIELDS.get("sessionTouchProgrammatic"), s), "sessionTouchProgrammatic"));
		assertEquals(before.toInstant().getEpochSecond(),
				agentSessionService.getSessionData(s).orElseThrow().getLastActivityAt().toInstant().getEpochSecond(),
				"a sealed refusal does not touch lastActivityAt");
	}

	// ---------- the Basic form (cases 6 to 9) ----------

	/**
	 * Basic form: no principal attribute, so the resolver runs authenticateProgrammatic and parseAuthHeader
	 * exactly as production does (the filter passes Basic through unverified).
	 */
	private ExecutionResult asBasic(String keyId, String secret, String op, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		return dgsQueryExecutor.execute(op, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	/** The key id text of a fixture key, as AuthHeaderParse.fromVerifiedKey builds it. */
	private static String keyId(World w, ApiKey key) {
		return "FREEFORM__" + w.org().getUuid() + "__ord__" + key.getKeyOrder();
	}

	private static String forgedKeyId() {
		return "FREEFORM__" + UUID.randomUUID() + "__ord__0";
	}

	private static String forgedKeyIdInOrg(UUID org) {
		return "FREEFORM__" + UUID.randomUUID() + "__ORGANIZATION__" + org + "__ord__0";
	}

	private static String randomSecret() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private long accessRows(World w, ApiKey key) {
		return apiKeyAccessService.listKeyAccessByOrg(w.org().getUuid()).stream()
				.filter(a -> key.getUuid().equals(a.getApiKeyUuid())).count();
	}

	// ---------- case 6: an unverified Basic credential is refused before any lookup, per field ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("fields")
	void case6ForgedBasicKeyIdReadsNotAuthorizedForMissingAndExisting(String field, String op) throws RelizaException {
		World a = world();
		Caller own = key(a);
		UUID real = own.session().getUuid();

		String missing = refusal(asBasic(forgedKeyId(), randomSecret(), op, vars("s", UUID.randomUUID().toString())), field);
		String existing = refusal(asBasic(forgedKeyId(), randomSecret(), op, vars("s", real.toString())), field);
		assertEquals("Not authorized", missing, field + ": a forged key id reads Not authorized, not Session not found");
		assertEquals(missing, existing, field + ": a forged key id cannot tell a missing session from an existing one");
		AgentSessionData after = agentSessionService.getSessionData(real).orElseThrow();
		assertEquals(own.session().getStatus(), after.getStatus(), field + ": a refused call leaves the session as it was");
	}

	// ---------- case 7: a real key id with a wrong secret, the state, and no access row ----------

	@Test
	void case7WrongSecretReadsNotAuthorizedHealthyAndSealedAndWritesNoAccessRow() throws RelizaException {
		World a = world();
		Caller own = key(a);
		String id = keyId(a, own.key());
		String op = FIELDS.get("sessionProgrammatic");
		long rowsBefore = accessRows(a, own.key());

		for (boolean sealed : List.of(false, true)) {
			licenseStatus.setSystemSealed(sealed);
			String state = sealed ? "sealed" : "healthy";
			String existing = refusal(asBasic(id, randomSecret(), op, vars("s", own.session().getUuid().toString())),
					"sessionProgrammatic");
			String missing = refusal(asBasic(id, randomSecret(), op, vars("s", UUID.randomUUID().toString())),
					"sessionProgrammatic");
			assertEquals("Not authorized", existing, state + ": a wrong secret on a real session reads Not authorized");
			assertEquals("Not authorized", missing, state + ": a wrong secret on a missing session reads Not authorized");
		}
		assertEquals(rowsBefore, accessRows(a, own.key()), "a wrong secret writes no key-access row");
	}

	// ---------- case 8: the org helper, a forged id with an organisation segment ----------

	@Test
	void case8ForgedOrganisationSegmentReadsNotAuthorizedForRandomAndRealOrg() throws RelizaException {
		World a = world();
		Caller own = key(a);
		UUID realOrg = a.org().getUuid();

		Map<String, Object> c = vars("c", "order-" + UUID.randomUUID());
		String randomOrgUsage = refusal(asBasic(forgedKeyIdInOrg(UUID.randomUUID()), randomSecret(), USAGE_BY_CLIENT_ID, c),
				"sessionReportUsageProgrammatic");
		String realOrgUsage = refusal(asBasic(forgedKeyIdInOrg(realOrg), randomSecret(), USAGE_BY_CLIENT_ID, c),
				"sessionReportUsageProgrammatic");
		assertEquals("Not authorized", randomOrgUsage, "a forged org segment reads Not authorized, not Org not found");
		assertEquals(randomOrgUsage, realOrgUsage, "a forged key id cannot tell a missing organisation from an existing one");

		String randomOrgInit = refusal(asBasic(forgedKeyIdInOrg(UUID.randomUUID()), randomSecret(), INIT, Map.of()),
				"sessionInitializeProgrammatic");
		String realOrgInit = refusal(asBasic(forgedKeyIdInOrg(realOrg), randomSecret(), INIT, Map.of()),
				"sessionInitializeProgrammatic");
		assertEquals("Not authorized", randomOrgInit, "sessionInitializeProgrammatic: a forged org segment reads Not authorized");
		assertEquals(randomOrgInit, realOrgInit, "sessionInitializeProgrammatic: random and real organisation read alike");
		assertEquals(own.session().getStatus(),
				agentSessionService.getSessionData(own.session().getUuid()).orElseThrow().getStatus(),
				"the fixture session is untouched");
	}

	// ---------- case 9: a valid Basic key still works ----------

	@Test
	void case9AValidBasicKeyIsNotRefused() throws RelizaException {
		World a = world();
		Caller own = key(a);
		UUID s = own.session().getUuid();
		String id = keyId(a, own.key());
		String secret = apiKeyService.addApiKeySecret(own.key().getUuid(), null, WU);

		Map<String, Object> read = data(asBasic(id, secret, FIELDS.get("sessionProgrammatic"), vars("s", s.toString())),
				"sessionProgrammatic");
		assertEquals(s.toString(), read.get("uuid"), "a valid Basic key reads its session");
		UUID random = UUID.randomUUID();
		assertEquals("Session not found: " + random,
				refusal(asBasic(id, secret, FIELDS.get("sessionProgrammatic"), vars("s", random.toString())),
						"sessionProgrammatic"),
				"a valid Basic key still reads the unknown-uuid text for a missing session");

		Map<String, Object> touched = data(asBasic(id, secret, FIELDS.get("sessionTouchProgrammatic"),
				vars("s", s.toString())), "sessionTouchProgrammatic");
		assertEquals(s.toString(), touched.get("uuid"), "a valid Basic key touches its session");

		Map<String, Object> ack = data(asBasic(id, secret, USAGE_BY_CLIENT_ID,
				vars("c", own.session().getClientSessionId())), "sessionReportUsageProgrammatic");
		assertTrue(null != ack && null != ack.get("__typename"),
				"the org helper admits a valid Basic key on the clientSessionId form: " + ack);
	}
}
