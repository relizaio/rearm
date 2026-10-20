/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.repositories.NotificationSubscriptionRepository;

/**
 * A NUL character in client text is refused at input, naming the field (task TPF-47, architecture-7 section 4
 * item 2), through the executor on the user lane and the programmatic lane: in a mutation argument as a variable
 * and as a document literal, in a query argument, and, decoded, in the String arguments the server parses as a
 * JSON document (presetConfigJson, cdxJson, the latter in every framing the import's own reader reads), after the
 * fetcher's authorization; other control characters and
 * ordinary text keep their answers. The element index sites need a real agent, board and task and are
 * {@link NulInputAgentDocumentIntegrationTest}'s.
 */
public class NulInputGraphQlTest extends InputConversionGraphQlTestBase {

	@Autowired private NotificationSubscriptionRepository subscriptionRepo;

	private static final String CREATE = "mutation c($c: CreateComponentInput!) { createComponent(component: $c) { uuid } }";
	private static final String NUL_REFUSAL = "component.identifiers[0].idValue: must not contain U+0000";

	/** A CreateComponentInput whose first identifier's value is {@code idValue}; {@code org} left out when null. */
	private static Map<String, Object> componentInput(UUID org, String idValue) {
		Map<String, Object> c = new LinkedHashMap<>();
		if (null != org) c.put("org", org.toString());
		c.putAll(map("name", "tpf47-" + UUID.randomUUID(), "type", "COMPONENT", "versionSchema", "semver",
				"featureBranchVersioning", "Branch.Micro", "identifiers", List.of(map("idType", "PURL", "idValue", idValue))));
		return c;
	}

	private int componentsOf(UUID org) {
		return componentService.listComponentDataByOrganization(org, ComponentType.COMPONENT).size();
	}

	// item 2 row 1: the repro
	@Test
	void aNulInALinkUriIsRefusedByItsPath() throws RelizaException {
		UUID release = release();
		List<UUID> before = artifactsOf(release);
		Map<String, Object> a = artifact("EXTERNALLY");
		a.put("downloadLinks", List.of(map("uri", "https://example.com/x\u0000.md")));
		ExecutionResult r = dgsQueryExecutor.execute(ADD, map("in", addInput(release, a)));
		refused(r, "artifactInput.artifact.downloadLinks[0].uri: must not contain U+0000", "BAD_REQUEST",
				() -> artifactsOf(release), before);
	}

	// item 2 row 2: another nested field, user lane, as a variable
	@Test
	void aNulInAnIdentifierVariableIsRefused() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		int before = componentsOf(org);
		ExecutionResult r = dgsQueryExecutor.execute(CREATE, map("c", componentInput(org, "pkg:npm/a\u0000b")));
		refused(r, NUL_REFUSAL, "BAD_REQUEST", () -> componentsOf(org), before);
	}

	// item 2 row 3: the same as a literal in the document, the GraphQL escape
	@Test
	void aNulInAnIdentifierLiteralIsRefused() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		int before = componentsOf(org);
		String literal = "mutation { createComponent(component: {org: \"" + org + "\", name: \"tpf47-lit-" + UUID.randomUUID()
				+ "\", type: COMPONENT, versionSchema: \"semver\", featureBranchVersioning: \"Branch.Micro\", "
				+ "identifiers: [{idType: PURL, idValue: \"pkg:npm/a\\u0000b\"}]}) { uuid } }";
		ExecutionResult r = dgsQueryExecutor.execute(literal, Map.of());
		refused(r, NUL_REFUSAL, "BAD_REQUEST", () -> componentsOf(org), before);
	}

	// item 2 row 4: control, the other control characters are kept
	@Test
	void otherControlCharactersAreStoredUnchanged() throws RelizaException {
		UUID release = release();
		List<UUID> before = artifactsOf(release);
		Map<String, Object> a = artifact("EXTERNALLY");
		String sent = "tpf47\t\u0001\u001f\u007fé-" + UUID.randomUUID();
		a.put("displayIdentifier", sent);
		ExecutionResult r = dgsQueryExecutor.execute(ADD, map("in", addInput(release, a)));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		List<UUID> after = artifactsOf(release);
		assertEquals(before.size() + 1, after.size());
		UUID added = after.stream().filter(u -> !before.contains(u)).findFirst().orElseThrow();
		assertEquals(sent, artifactService.getArtifactData(added).orElseThrow().getDisplayIdentifier());
	}

	/** A FREEFORM key of the org holding org-wide READ_WRITE RESOURCE (PerspectiveCreatePathsTestBase's). */
	private ApiKey resourceKey(UUID org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org, UUID.randomUUID().toString(),
				"tpf47 freeform key", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org,
				PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, Set.of(PermissionFunction.RESOURCE), null)), WU);
		return key;
	}

	/** The programmatic lane through the engine: the key as the filter verified it (SessionActivityInstrumentationTest's rig). */
	private ExecutionResult asKey(ApiKey key, String query, Map<String, Object> variables) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.47"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, variables, Map.of(), headers, null, new ServletWebRequest(request));
	}

	// item 2 row 5: the programmatic lane through the engine
	@Test
	void aNulIsRefusedOnTheProgrammaticLane() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ApiKey key = resourceKey(org);
		int before = componentsOf(org);
		ExecutionResult r = asKey(key,
				"mutation c($c: CreateComponentInput!) { createComponentProgrammatic(component: $c) { uuid } }",
				map("c", componentInput(null, "pkg:npm/a\u0000b")));
		refused(r, NUL_REFUSAL, "BAD_REQUEST", () -> componentsOf(org), before);
	}

	// item 2 row 6: a query argument, user lane, as a document literal (T-2, decision 4)
	@Test
	@SuppressWarnings("unchecked")
	void aNulInAQueryArgumentIsRefused() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ExecutionResult r = dgsQueryExecutor.execute(
				"query { releasesByTags(orgUuid: \"" + org + "\", tagKey: \"k\\u0000\") { uuid } }", Map.of());
		refused(r, "tagKey: must not contain U+0000", "BAD_REQUEST", null, null);
		Map<String, Object> data = (Map<String, Object>) r.getData();
		assertTrue(null == data || null == data.get("releasesByTags"), String.valueOf(data));
	}

	// item 2 row 7: the same query with ordinary text, answered
	@Test
	@SuppressWarnings("unchecked")
	void theSameQueryWithOrdinaryTextIsAnswered() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ExecutionResult r = dgsQueryExecutor.execute(
				"query q($o: ID!, $k: String!) { releasesByTags(orgUuid: $o, tagKey: $k) { uuid } }",
				map("o", org.toString(), "k", "tpf47-no-such-tag-" + UUID.randomUUID()));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		assertEquals(List.of(), ((Map<String, Object>) r.getData()).get("releasesByTags"));
	}

	// item 2 row 8: a query argument on the programmatic lane (T-2)
	@Test
	void aNulInAQueryArgumentIsRefusedOnTheProgrammaticLane() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ExecutionResult r = asKey(resourceKey(org), "query q($k: String!) { agentTaskByKeyProgrammatic(key: $k) { uuid } }",
				map("k", "TPF-1\u0000"));
		refused(r, "key: must not contain U+0000", "BAD_REQUEST", null, null);
	}

	private static final String UPSERT_SUBSCRIPTION =
			"mutation s($in: NotificationSubscriptionInput!) { upsertNotificationSubscription(input: $in) { uuid } }";

	/** A subscription the service accepts: one event type and one owner-routed route; the preset text as given. */
	private static Map<String, Object> subscriptionInput(UUID org, String presetConfigJson) {
		return map("org", org.toString(), "name", "tpf47-sub-" + UUID.randomUUID(), "status", "ACTIVE",
				"eventTypes", List.of("NEW_VULN_AFFECTS_RELEASES"),
				"routes", List.of(map("channels", List.of(), "notifyComponentOwner", true)),
				"filter", map("mode", "PRESET", "presetConfigJson", presetConfigJson));
	}

	// item 2 row 9: presetConfigJson with the escape (T-1, site 1), after the fetcher's authorization (decision 10)
	@Test
	void aDecodedNulInThePresetConfigurationIsRefused() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		int before = subscriptionRepo.findByOrg(org).size();
		clearInvocations(authorizationService);
		ExecutionResult r = dgsQueryExecutor.execute(UPSERT_SUBSCRIPTION,
				map("in", subscriptionInput(org, "{\"k\":\"a\\u0000b\"}")));
		// The result type is non-null, so graphql-java adds NullValueInNonNullableField beside the refusal.
		assertFalse(r.getErrors().isEmpty(), "a refusal");
		GraphQLError err = r.getErrors().get(0);
		assertEquals("input.filter.presetConfigJson.k: must not contain U+0000", err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
		r.getErrors().forEach(e -> assertFalse(String.valueOf(e.getMessage()).contains("\u0000"), e.getMessage()));
		assertEquals(before, subscriptionRepo.findByOrg(org).size());
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(any(), eq(PermissionFunction.RESOURCE),
				eq(PermissionScope.ORGANIZATION), eq(org), any(), eq(CallType.ADMIN));
	}

	// item 2 row 10: control, a preset configuration stores and reads back
	@Test
	@SuppressWarnings("unchecked")
	void aPresetConfigurationStoresAndReadsBack() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ExecutionResult r = dgsQueryExecutor.execute(UPSERT_SUBSCRIPTION, map("in", subscriptionInput(org, "{\"k\":\"ok\"}")));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		String uuid = (String) ((Map<String, Object>) ((Map<String, Object>) r.getData())
				.get("upsertNotificationSubscription")).get("uuid");
		assertNotNull(uuid);
		NotificationSubscription stored = subscriptionRepo.findById(UUID.fromString(uuid)).orElseThrow();
		NotificationSubscriptionData d = Utils.OM.convertValue(stored.getRecordData(), NotificationSubscriptionData.class);
		assertEquals("ok", d.filter().presetConfig().get("k"));
	}

	private static final String IMPORT = "mutation i($o: ID!, $j: String!) "
			+ "{ importCyclonedxComponents(orgUuid: $o, cdxJson: $j) { componentName success error } }";

	/** CdxImportServiceIntegrationTest's BOM less the pedigree, hashes and VCS properties; the version as JSON text. */
	private static String bom(String name, String versionJson) {
		return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,\"components\":[{\"type\":\"library\","
				+ "\"name\":\"" + name + "\",\"version\":\"" + versionJson + "\",\"properties\":[{\"name\":"
				+ "\"reliza:rearmImport:rearmImportable\",\"value\":\"true\"}]}]}";
	}

	// item 2 row 11: cdxJson with the escape (T-1, site 2), after the fetcher's authorization (decision 10)
	@Test
	void aDecodedNulInTheCycloneDxDocumentIsRefused() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String name = "tpf47-cdx-" + UUID.randomUUID();
		int before = componentsOf(org);
		clearInvocations(authorizationService);
		ExecutionResult r = dgsQueryExecutor.execute(IMPORT, map("o", org.toString(), "j", bom(name, "1.0\\u0000")));
		refused(r, "cdxJson.components[0].version: must not contain U+0000", "BAD_REQUEST", () -> componentsOf(org), before);
		assertTrue(componentService.findComponentDataByOrgNameType(org, name, ComponentType.COMPONENT).isEmpty());
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(any(), eq(PermissionFunction.RESOURCE),
				eq(PermissionScope.ORGANIZATION), eq(org), any(), eq(CallType.WRITE));
	}

	// item 2 row 12: control, the BOM imports
	@Test
	@SuppressWarnings("unchecked")
	void theCycloneDxDocumentImports() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String name = "tpf47-cdx-ok-" + UUID.randomUUID();
		ExecutionResult r = dgsQueryExecutor.execute(IMPORT, map("o", org.toString(), "j", bom(name, "1.0.0")));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		List<Map<String, Object>> results = (List<Map<String, Object>>) ((Map<String, Object>) r.getData())
				.get("importCyclonedxComponents");
		assertEquals(1, results.size(), String.valueOf(results));
		assertEquals(Boolean.TRUE, results.get(0).get("success"), String.valueOf(results));
		assertTrue(componentService.findComponentDataByOrgNameType(org, name, ComponentType.COMPONENT).isPresent());
	}

	/** The four framings of a BOM that the import's Jackson 2 reader reads and Utils.OM (Jackson 3) does not. */
	private static List<String> framings(String bom) {
		return List.of(bom + " x", bom + "{}", bom + "}", "\uFEFF" + bom);
	}

	// item 2 row 14: cdxJson with the escape, framed so that the two readers disagreed (T-3, decision 11)
	@Test
	void aDecodedNulInAFramedCycloneDxDocumentIsRefused() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		for (int i = 0; i < 4; i++) {
			String name = "tpf47-cdx-framed-" + UUID.randomUUID();
			String j = framings(bom(name, "1.0\\u0000")).get(i);
			int before = componentsOf(org);
			ExecutionResult r = dgsQueryExecutor.execute(IMPORT, map("o", org.toString(), "j", j));
			refused(r, "cdxJson.components[0].version: must not contain U+0000", "BAD_REQUEST", () -> componentsOf(org),
					before);
			assertTrue(componentService.findComponentDataByOrgNameType(org, name, ComponentType.COMPONENT).isEmpty(),
					"framing " + i);
		}
	}

	// item 2 row 15: control, the same framings with no NUL import
	@Test
	@SuppressWarnings("unchecked")
	void aFramedCycloneDxDocumentWithoutNulImports() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		for (int i = 0; i < 4; i++) {
			String name = "tpf47-cdx-framed-ok-" + UUID.randomUUID();
			ExecutionResult r = dgsQueryExecutor.execute(IMPORT,
					map("o", org.toString(), "j", framings(bom(name, "1.0.0")).get(i)));
			assertTrue(r.getErrors().isEmpty(), "framing " + i + ", no errors: " + r.getErrors());
			List<Map<String, Object>> results = (List<Map<String, Object>>) ((Map<String, Object>) r.getData())
					.get("importCyclonedxComponents");
			assertEquals(1, results.size(), "framing " + i + ": " + results);
			assertEquals(Boolean.TRUE, results.get(0).get("success"), "framing " + i + ": " + results);
			assertTrue(componentService.findComponentDataByOrgNameType(org, name, ComponentType.COMPONENT).isPresent(),
					"framing " + i);
		}
	}
}
