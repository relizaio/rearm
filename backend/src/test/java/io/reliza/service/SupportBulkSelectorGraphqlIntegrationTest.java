/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.model.Organization;
import io.reliza.model.SupportBulkActKind;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;

/**
 * FDAW-14 rows 22 and 23 (REQ-14, REQ-15): the selector preview and write answer only an organisation
 * writer, a foreign release is refused before any count, and the receipt list clamps its limit.
 */
class SupportBulkSelectorGraphqlIntegrationTest extends ReleaseSbomJustificationTestBase {

	static final String COUNT = "query($o: ID!, $s: SupportBulkSelectorInput!, $r: ID) {"
			+ " supportBulkSelectorCount(orgUuid: $o, selector: $s, releaseUuid: $r) { matched overCap } }";
	static final String SWEEP = "mutation($o: ID!, $s: SupportBulkSelectorInput!, $r: ID, $u: JustifiedUnknownInput,"
			+ " $l: LevelOfSupport, $why: String) { bulkSetSbomComponentSupportBySelector(orgUuid: $o, selector: $s,"
			+ " releaseUuid: $r, levelOfSupport: $l, endOfSupportUnknown: $u, reason: $why) { appliedCount skippedCount"
			+ " failedCount batchId bulkAct matchedCount results { sbomComponentUuid outcome } } }";
	static final String ACTS = "query($o: ID!, $n: Int) { supportBulkActs(orgUuid: $o, limit: $n) { uuid kind selector"
			+ " matchedCount appliedCount listHash assertedBy assertedDate release } }";
	static final String PAGE = "query($r: ID!) { getReleaseSbomComponentsPage(releaseUuid: $r) {"
			+ " items { component { uuid assertedByName assessmentSource supportBulkAct endOfSupportUnknown { field code"
			+ " bulkAct assertedBy assertedByName } } } } }";

	private static Map<String, Object> deb() {
		return vars("form", "TYPE", "type", "deb");
	}

	private long writes() {
		return count("support_bulk_acts", "org = ?", org.getUuid())
				+ count("sbom_component_support_audit", "org = ?", org.getUuid())
				+ count("sbom_component_support", "org = ?", org.getUuid());
	}

	@Test
	void onlyAnOrganizationWriterCountsAndWrites() throws Exception {
		World w = componentRelease(null);
		components(org, w.release(), List.of("pkg:deb/debian/a@1", "pkg:deb/debian/b@1"), false);
		UserData componentWriter = userOf(org, PermissionScope.COMPONENT, w.component(), PermissionType.READ_WRITE);
		Organization other = testInitializer.obtainOrganization();
		UserData foreignWriter = userOf(other, PermissionScope.ORGANIZATION, other.getUuid(), PermissionType.READ_WRITE);
		UserData orgWriter = userOf(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE);
		Map<String, Object> countVars = vars("o", org.getUuid().toString(), "s", deb());
		Map<String, Object> sweepVars = vars("o", org.getUuid().toString(), "s", deb(), "l", "ACTIVELY_MAINTAINED");

		long before = writes();
		for (UserData denied : List.of(componentWriter, foreignWriter)) {
			assertFalse(asUser(denied, COUNT, countVars).getErrors().isEmpty());
			assertFalse(asUser(denied, SWEEP, sweepVars).getErrors().isEmpty());
		}
		assertEquals(before, writes(), "no audit, act or support row");

		Map<String, Object> c = data(asUser(orgWriter, COUNT, countVars), "supportBulkSelectorCount");
		assertEquals(2, c.get("matched"));
		assertEquals(false, c.get("overCap"));
		Map<String, Object> r = data(asUser(orgWriter, SWEEP, sweepVars), "bulkSetSbomComponentSupportBySelector");
		assertEquals(2, r.get("appliedCount"));
		assertEquals(2, r.get("matchedCount"));
		assertEquals(r.get("bulkAct"), r.get("batchId"), "a selector sweep's audit batch is its act");

		// a release of another organisation: refused after the gate, before any count
		World foreign = componentReleaseIn(other, null);
		Map<String, Object> scoped = vars("o", org.getUuid().toString(), "s", deb(), "r", foreign.release().toString());
		ExecutionResult refused = asUser(orgWriter, COUNT, scoped);
		assertEquals("release not found in this organization: " + foreign.release(),
				refused.getErrors().get(0).getMessage());
	}

	@Test
	void theReceiptListClampsItsLimitAndServesTheNewestFirst() throws Exception {
		World w = componentRelease(null);
		components(org, w.release(), List.of("pkg:deb/debian/a@1"), false);
		UserData orgWriter = userOf(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE);
		Map<String, Object> sweepVars = vars("o", org.getUuid().toString(), "s", deb(), "l", "ACTIVELY_MAINTAINED");
		Map<String, Object> first = data(asUser(orgWriter, SWEEP, sweepVars), "bulkSetSbomComponentSupportBySelector");
		Thread.sleep(20);
		Map<String, Object> second = data(asUser(orgWriter, SWEEP, sweepVars), "bulkSetSbomComponentSupportBySelector");

		List<Map<String, Object>> one = data(asUser(orgWriter, ACTS, vars("o", org.getUuid().toString(), "n", 0)),
				"supportBulkActs");
		assertEquals(1, one.size(), "limit 0 is clamped to 1");
		assertEquals(second.get("bulkAct"), one.get(0).get("uuid"), "newest first");
		List<Map<String, Object>> all = data(asUser(orgWriter, ACTS, vars("o", org.getUuid().toString(), "n", 10000)),
				"supportBulkActs");
		assertEquals(2, all.size());
		assertEquals(first.get("bulkAct"), all.get(1).get("uuid"));
		assertTrue(((String) all.get(0).get("selector")).contains("\"type\":\"deb\""), (String) all.get(0).get("selector"));
		assertEquals("LEVEL_OF_SUPPORT", all.get(0).get("kind"));
		assertEquals(2, ((List<?>) data(asUser(orgWriter, ACTS.replace(", $n: Int", "").replace(", limit: $n", ""),
				vars("o", org.getUuid().toString())), "supportBulkActs")).size(), "the default");
		assertEquals(200, SbomComponentService.SUPPORT_BULK_ACTS_MAX_LIMIT);

		Organization other = testInitializer.obtainOrganization();
		UserData foreignReader = userOf(other, PermissionScope.ORGANIZATION, other.getUuid(), PermissionType.READ_ONLY);
		assertFalse(asUser(foreignReader, ACTS, vars("o", org.getUuid().toString(), "n", 5)).getErrors().isEmpty());
	}

	/**
	 * T-1 (design row 23, THREAT_MODEL F-9): above 200 receipts, {@code limit: 10000} answers exactly 200,
	 * the newest. Without the upper clamp the 201st receipt comes back too.
	 */
	@Test
	void theReceiptListNeverServesMoreThanTheUpperClamp() throws Exception {
		UserData orgWriter = userOf(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE);
		int cap = SbomComponentService.SUPPORT_BULK_ACTS_MAX_LIMIT;
		for (int i = 0; i <= cap; i++) {
			supportBulkActService.record(new SupportBulkActService.Receipt(UUID.randomUUID(), org.getUuid(),
					SupportBulkActKind.LEVEL_OF_SUPPORT, null, null, Map.of("form", "TYPE", "type", "deb"),
					List.of("c-" + i), 1, orgWriter.getUuid(), Map.of()));
		}
		assertEquals(cap + 1, count("support_bulk_acts", "org = ?", org.getUuid()));
		List<Map<String, Object>> served = data(asUser(orgWriter, ACTS, vars("o", org.getUuid().toString(), "n", 10000)),
				"supportBulkActs");
		assertEquals(cap, served.size(), "limit 10000 is clamped to " + cap);
		assertEquals(cap, supportBulkActService.listNewest(org.getUuid(), Integer.MAX_VALUE).size());
	}

	@Test
	void theComponentPageServesTheUnknownTheActAndTheNames() throws Exception {
		World w = componentRelease(null);
		components(org, w.release(), List.of("pkg:deb/debian/a@1"), false);
		UserData orgWriter = userOf(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE);
		Map<String, Object> r = data(asUser(orgWriter, SWEEP, vars("o", org.getUuid().toString(), "s", deb(), "u",
				vars("code", "NO_COMMITTED_END_OF_SUPPORT"))), "bulkSetSbomComponentSupportBySelector");
		Map<String, Object> page = data(asUser(orgWriter, PAGE, vars("r", w.release().toString())),
				"getReleaseSbomComponentsPage");
		@SuppressWarnings("unchecked")
		Map<String, Object> c = (Map<String, Object>) ((List<Map<String, Object>>) page.get("items")).get(0)
				.get("component");
		assertEquals("MANUAL", c.get("assessmentSource"));
		assertEquals(orgWriter.getName(), c.get("assertedByName"));
		@SuppressWarnings("unchecked")
		Map<String, Object> u = (Map<String, Object>) c.get("endOfSupportUnknown");
		assertEquals("END_OF_SUPPORT", u.get("field"));
		assertEquals("NO_COMMITTED_END_OF_SUPPORT", u.get("code"));
		assertEquals(r.get("bulkAct"), u.get("bulkAct"));
		assertEquals(orgWriter.getName(), u.get("assertedByName"));
	}
}
