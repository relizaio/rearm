/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import io.reliza.common.Utils;
import io.reliza.common.VendoredIdentifier;
import io.reliza.model.Organization;
import io.reliza.model.SbomComponent;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.SbomComponentRepository;

/** FDAW-15 row 8 (REQ-8): who passes the five overlay verbs and the three reads, over the real schema. */
class SbomComponentOverlayGraphqlIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final String SET = "mutation($c: ID!, $o: SbomComponentOverlayInput!) {"
			+ " setSbomComponentOverlay(sbomComponentUuid: $c, overlay: $o) { uuid } }";
	private static final String BULK = "mutation($org: ID!, $s: SupportBulkSelectorInput!) {"
			+ " bulkSetSbomComponentSupplierBySelector(orgUuid: $org, selector: $s, supplier: \"Debian\") { appliedCount } }";
	private static final String DESCRIBE = "mutation($r: ID!, $n: String!, $v: String, $o: SbomComponentOverlayInput!) {"
			+ " describeVendoredComponent(releaseUuid: $r, name: $n, version: $v, overlay: $o) { key described } }";
	private static final String REMOVE = "mutation($r: ID!, $n: String!, $v: String) {"
			+ " removeVendoredDescription(releaseUuid: $r, name: $n, version: $v, reason: \"wrong\") }";
	private static final String SUPPORT = "mutation($r: ID!, $n: String!, $v: String) {"
			+ " setVendoredComponentSupport(releaseUuid: $r, name: $n, version: $v, levelOfSupport: ACTIVELY_MAINTAINED)"
			+ " { uuid } }";
	private static final String UNIDENTIFIED = "query($r: ID!) { releaseUnidentifiedComponents(releaseUuid: $r)"
			+ " { key name describable described refusal } }";
	private static final String IDENTIFIER = "query($r: ID!, $n: String!, $v: String) {"
			+ " vendoredIdentifier(releaseUuid: $r, name: $n, version: $v) }";
	private static final String BY_NAME = "query($r: ID!, $n: String!) { vendoredDescriptionsByName(releaseUuid: $r, name: $n)"
			+ " { kind name version description } }";

	@Autowired private SbomComponentRepository sbomComponentRepository;

	private record Matrix(Fixture f, UserData writer, UserData reader, UserData componentRead, UserData componentWrite,
			UserData outsider, SbomComponent row) {}

	private UserData userWith(Organization org, PermissionScope scope, UUID object, PermissionType type) throws Exception {
		UserData u = user(org, PermissionScope.ORGANIZATION, org.getUuid(), List.of());
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.NONE, List.of(), null, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, type,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private Matrix matrix() throws Exception {
		Fixture f = fixture();
		Organization org = f.org();
		UUID component = f.component().getUuid();
		Organization other = testInitializer.obtainOrganization();
		SbomComponent row = new SbomComponent();
		row.setOrg(org.getUuid());
		row.setCanonicalPurl("pkg:deb/debian/matrix-" + UUID.randomUUID() + "@1");
		Map<String, Object> rd = new HashMap<>();
		rd.put("name", "matrix");
		row.setRecordData(rd);
		return new Matrix(f, userWith(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE),
				userWith(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY),
				userWith(org, PermissionScope.COMPONENT, component, PermissionType.READ_ONLY),
				userWith(org, PermissionScope.COMPONENT, component, PermissionType.READ_WRITE),
				userWith(other, PermissionScope.ORGANIZATION, other.getUuid(), PermissionType.READ_WRITE),
				sbomComponentRepository.save(row));
	}

	private static Map<String, Object> vars(Object... pairs) {
		Map<String, Object> vars = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) vars.put((String) pairs[i], pairs[i + 1]);
		return vars;
	}

	private static Map<String, Object> node(UUID release, String name, Object... more) {
		Map<String, Object> vars = vars("r", release.toString(), "n", name, "v", "1");
		vars.putAll(vars(more));
		return vars;
	}

	private static Map<String, Object> supplier(String supplier) {
		return Map.of("supplier", supplier);
	}

	private static Map<String, Object> selector() {
		return vars("form", "NAMESPACE", "type", "deb", "namespace", "debian");
	}

	private long written(UUID org) {
		return jdbcTemplate.queryForObject("SELECT (SELECT count(*) FROM rearm.sbom_component_overlays WHERE org = ?)"
				+ " + (SELECT count(*) FROM rearm.sbom_component_overlay_audit WHERE org = ?)"
				+ " + (SELECT count(*) FROM rearm.support_bulk_acts WHERE org = ?)"
				+ " + (SELECT count(*) FROM rearm.sbom_component_support WHERE org = ?)"
				+ " + (SELECT count(*) FROM rearm.sbom_components WHERE org = ? AND canonical_purl LIKE 'pkg:generic/%')",
				Long.class, org, org, org, org, org);
	}

	private static void refused(ExecutionResult r, String... anyOf) {
		assertFalse(r.getErrors().isEmpty(), "passed: " + r.toSpecification());
		String message = r.getErrors().get(0).getMessage();
		assertTrue(List.of(anyOf).stream().anyMatch(message::equals), message);
	}

	private static void passed(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
	}

	private static final String[] GATE = {"Not authorized"};
	private static final String[] GATE_OR_NOT_FOUND = {"Not authorized", "release not found"};

	@Test
	void everyVerbIsRefusedBelowOrganizationWriteAndWritesNothing() throws Exception {
		Matrix m = matrix();
		UUID release = m.f().release();
		UUID org = m.f().org().getUuid();
		for (UserData u : List.of(m.reader(), m.componentRead(), m.componentWrite(), m.outsider())) {
			String[] expected = u == m.outsider() ? GATE_OR_NOT_FOUND : GATE;
			refused(as(u, SET, vars("c", m.row().getUuid().toString(), "o", supplier("Acme"))), GATE);
			refused(as(u, BULK, vars("org", org.toString(), "s", selector())), GATE);
			refused(as(u, DESCRIBE, node(release, "libdosecalc", "o", supplier("Acme"))), expected);
			refused(as(u, REMOVE, node(release, "libdosecalc")), expected);
			refused(as(u, SUPPORT, node(release, "libdosecalc")), expected);
		}
		assertEquals(0, written(org));
	}

	@Test
	void theOrganizationWriterPassesEveryVerb() throws Exception {
		Matrix m = matrix();
		UUID release = m.f().release();
		UUID org = m.f().org().getUuid();
		String key = VendoredIdentifier.mint(org, "libdosecalc", "1");
		passed(as(m.writer(), SET, vars("c", m.row().getUuid().toString(), "o", supplier("Acme"))));
		passed(as(m.writer(), BULK, vars("org", org.toString(), "s", selector())));
		ExecutionResult described = as(m.writer(), DESCRIBE, node(release, "libdosecalc", "o", supplier("Acme")));
		passed(described);
		Map<?, ?> dto = (Map<?, ?>) fieldOf(described, "describeVendoredComponent");
		assertEquals(key, dto.get("key"));
		assertEquals(Boolean.TRUE, dto.get("described"));
		passed(as(m.writer(), SUPPORT, node(release, "libdosecalc")));
		passed(as(m.writer(), REMOVE, node(release, "libdosecalc")));
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.sbom_components WHERE org = ? AND"
				+ " canonical_purl = ?", Long.class, org, key));
	}

	@Test
	void theReleaseReadsAnswerAMemberAndRefuseANonMemberAndWriteNothing() throws Exception {
		Matrix m = matrix();
		UUID release = m.f().release();
		UUID org = m.f().org().getUuid();
		String longName = "n".repeat(513);
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","components":[
			  {"type":"library","name":"libdosecalc","version":"1"},
			  {"type":"library","name":"libdosecalc","purl":"libdosecalc"},
			  {"type":"library","name":"%s","version":"1"},
			  {"type":"library","name":"lib"},
			  {"type":"library","name":"lib","version":"null"}
			]}""".formatted(longName)));

		ExecutionResult listed = as(m.componentRead(), UNIDENTIFIED, vars("r", release.toString()));
		passed(listed);
		List<?> rows = (List<?>) fieldOf(listed, "releaseUnidentifiedComponents");
		assertEquals(4, rows.size(), rows.toString());
		Map<?, ?> first = (Map<?, ?>) rows.get(0);
		assertEquals(VendoredIdentifier.mint(org, "libdosecalc", "1"), first.get("key"));
		assertEquals(Boolean.TRUE, first.get("describable"));
		Map<?, ?> tooLong = (Map<?, ?>) rows.get(1);
		assertNull(tooLong.get("key"));
		assertEquals(Boolean.FALSE, tooLong.get("describable"));
		assertEquals("name is longer than 512 characters", tooLong.get("refusal"));

		assertEquals(VendoredIdentifier.mint(org, "libdosecalc", "1"),
				data(as(m.componentRead(), IDENTIFIER, node(release, "libdosecalc")), "vendoredIdentifier"));
		assertEquals(0, written(org));
		refused(as(m.outsider(), UNIDENTIFIED, vars("r", release.toString())), GATE_OR_NOT_FOUND);
		refused(as(m.outsider(), IDENTIFIER, node(release, "libdosecalc")), GATE_OR_NOT_FOUND);
	}

	/** F-3: a release with no SBOM in its closure answers an empty list, not a thrown NO_SBOM_ARTIFACTS. */
	@Test
	void aReleaseWithNoSbomAnswersAnEmptyListNotAnError() throws Exception {
		Matrix m = matrix();
		UUID release = release(m.f().org(), m.f().component(), List.of());

		ExecutionResult listed = as(m.componentRead(), UNIDENTIFIED, vars("r", release.toString()));
		passed(listed);
		List<?> rows = (List<?>) fieldOf(listed, "releaseUnidentifiedComponents");
		assertEquals(List.of(), rows);
	}

	@Test
	void theDescriptionsByNameAnswerOrganizationWritersOnlyAndOnlyTheirOwnVendoredRows() throws Exception {
		Matrix m = matrix();
		UUID release = m.f().release();
		Map<String, Object> first = node(release, "libdosecalc", "o", Map.of("description", "first"));
		passed(as(m.writer(), DESCRIBE, first));
		Map<String, Object> second = node(release, "libdosecalc", "o", Map.of("description", "second"));
		second.put("v", "2");
		passed(as(m.writer(), DESCRIBE, second));
		passed(as(m.writer(), SET, vars("c", m.row().getUuid().toString(), "o", supplier("Acme"))));
		Matrix elsewhere = matrix();
		passed(as(elsewhere.writer(), DESCRIBE, node(elsewhere.f().release(), "libdosecalc", "o", Map.of("description", "theirs"))));

		Map<String, Object> vars = vars("r", release.toString(), "n", "libdosecalc");
		UserData admin = userWith(m.f().org(), PermissionScope.ORGANIZATION, m.f().org().getUuid(), PermissionType.ADMIN);
		for (UserData u : List.of(m.writer(), admin)) {
			ExecutionResult r = as(u, BY_NAME, vars);
			passed(r);
			List<?> rows = (List<?>) fieldOf(r, "vendoredDescriptionsByName");
			assertEquals(List.of("2", "1"), rows.stream().map(o -> ((Map<?, ?>) o).get("version")).toList());
			assertEquals(List.of("second", "first"), rows.stream().map(o -> ((Map<?, ?>) o).get("description")).toList());
		}
		assertEquals(List.of(), fieldOf(as(m.writer(), BY_NAME, vars("r", release.toString(), "n", "unknown")),
				"vendoredDescriptionsByName"));
		refused(as(m.componentRead(), BY_NAME, vars), GATE);
		refused(as(m.componentWrite(), BY_NAME, vars), GATE);
		refused(as(m.outsider(), BY_NAME, vars), GATE_OR_NOT_FOUND);
	}

	@Test
	void noMutationTakesAKeyAPurlOrAnOrgAndNoneIsOnTheApiKeySchema() throws Exception {
		Matrix m = matrix();
		ExecutionResult r = as(m.writer(), "{ __type(name: \"Mutation\") { fields { name args { name } } } }", Map.of());
		passed(r);
		List<?> fields = (List<?>) ((Map<?, ?>) fieldOf(r, "__type")).get("fields");
		List<String> verbs = List.of("setSbomComponentOverlay", "describeVendoredComponent", "removeVendoredDescription",
				"setVendoredComponentSupport", "bulkSetSbomComponentSupplierBySelector");
		int seen = 0;
		for (Object o : fields) {
			Map<?, ?> field = (Map<?, ?>) o;
			if (!verbs.contains(field.get("name"))) continue;
			seen++;
			for (Object a : (List<?>) field.get("args")) {
				String arg = (String) ((Map<?, ?>) a).get("name");
				assertFalse(List.of("key", "purl", "canonicalPurl", "org").contains(arg), field.get("name") + " takes " + arg);
			}
		}
		assertEquals(verbs.size(), seen);
		String programmatic = Files.readString(Path.of("src/main/resources/schema/programmatic.graphqls"));
		for (String verb : verbs) assertFalse(programmatic.contains(verb), verb);
	}
}
