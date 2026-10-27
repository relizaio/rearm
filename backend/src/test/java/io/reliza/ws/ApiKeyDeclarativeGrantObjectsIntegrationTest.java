/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.service.TeaProfileService;
import io.reliza.model.ApiKey;
import io.reliza.model.Organization;
import io.reliza.service.saas.PerspectiveService;

/**
 * TPF-15 (architecture D7): an API_KEYS file whose key grants a PERSPECTIVE object checks that object as the user
 * lane does, before the strength check and the dry-run return: a deleted, foreign or unknown perspective is that
 * key's ERROR change, "Perspective not found: <uuid>", and nothing of that key is created or changed; the other keys
 * of the file apply. Case numbers are those of the TPF-15 architecture, section 4.4.
 */
@SuppressWarnings("unchecked")
public class ApiKeyDeclarativeGrantObjectsIntegrationTest extends ApiKeyDeclarativeTestBase {

	private static final String NOT_FOUND = "Perspective not found: ";

	@Autowired private PerspectiveService perspectiveService;

	private record Perspectives(UUID live, UUID archived, UUID foreign) {}

	private Perspectives perspectives(Organization org) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		UUID live = perspectiveService.createPerspective(org.getUuid(), "tpf15-live-" + tag, WU).getUuid();
		UUID archived = perspectiveService.createPerspective(org.getUuid(), "tpf15-gone-" + tag, WU).getUuid();
		perspectiveService.deletePerspective(archived, TeaProfileService.TeaCaller.ORG_ADMIN, WU);
		UUID foreign = perspectiveService.createPerspective(org().getUuid(), "tpf15-b-" + tag, WU).getUuid();
		return new Perspectives(live, archived, foreign);
	}

	private static Map<String, Object> grantingKey(String name, UUID perspective) {
		return key("name", name, "type", "FREEFORM", "permissions", key("type", "READ_ONLY", "objects",
				List.of(key("scope", "PERSPECTIVE", "object", perspective.toString(), "type", "READ_WRITE"))));
	}

	/** The object grants of a declared key, as its export shows them. */
	private List<Map<String, Object>> exportedObjects(ApiKey caller, String name) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("k", List.of(name));
		Map<String, Object> export = data(as(caller, EXPORT, vars), "exportApiKeysProgrammatic");
		List<Map<String, Object>> keys = (List<Map<String, Object>>) export.get("keys");
		assertEquals(1, keys.size(), keys.toString());
		return (List<Map<String, Object>>) ((Map<String, Object>) keys.get(0).get("permissions")).get("objects");
	}

	@Test
	public void case22_aKeyGrantingADeletedPerspectiveIsNotCreatedAndFailsAlone() throws Exception {
		Organization org = org();
		ApiKey admin = admin(org);
		Perspectives p = perspectives(org);

		for (boolean dryRun : List.of(true, false)) {
			Map<String, Object> result = apply(admin, file(grantingKey("dead-grant", p.archived())), dryRun);
			Map<String, Object> change = change(result, "dead-grant");
			assertEquals("ERROR", change.get("action"), result.toString());
			assertEquals(NOT_FOUND + p.archived(), change.get("message"));
			assertEquals(0, result.get("created"), "dryRun " + dryRun);
			assertEquals(1, result.get("errors"), "dryRun " + dryRun);
			assertTrue(apiKeyService.findByDeclaredName(org.getUuid(), "dead-grant").isEmpty());
		}

		Map<String, Object> two = apply(admin, file(grantingKey("dead-grant", p.archived()),
				key("name", "org-wide", "type", "FREEFORM", "permissions", key("type", "READ_ONLY"))), false);
		assertEquals("ERROR", change(two, "dead-grant").get("action"), two.toString());
		assertEquals("CREATE", change(two, "org-wide").get("action"), two.toString());
		assertEquals(1, two.get("created"));
		assertEquals(1, two.get("errors"));
		assertTrue(apiKeyService.findByDeclaredName(org.getUuid(), "dead-grant").isEmpty());
		assertTrue(apiKeyService.findByDeclaredName(org.getUuid(), "org-wide").isPresent());
	}

	@Test
	public void case23_anUpdateToAForeignOrDeletedPerspectiveIsRefusedAndTheGrantsKept() throws Exception {
		Organization org = org();
		ApiKey admin = admin(org);
		Perspectives p = perspectives(org);
		Map<String, Object> created = apply(admin, file(grantingKey("ci", p.live())), false);
		assertEquals("CREATE", change(created, "ci").get("action"), created.toString());
		assertEquals(List.of(p.live().toString()), exportedObjects(admin, "ci").stream().map(o -> o.get("object")).toList());

		for (UUID bad : List.of(p.foreign(), p.archived())) {
			Map<String, Object> result = apply(admin, file(grantingKey("ci", bad)), false);
			assertEquals("ERROR", change(result, "ci").get("action"), result.toString());
			assertEquals(NOT_FOUND + bad, change(result, "ci").get("message"));
			assertEquals(0, result.get("updated"));
			assertEquals(List.of(p.live().toString()), exportedObjects(admin, "ci").stream().map(o -> o.get("object")).toList(),
					"the stored grants are unchanged after " + bad);
		}
	}

	@Test
	public void case24_aKeyGrantingALivePerspectiveIsCreatedAndReappliesUnchanged() throws Exception {
		Organization org = org();
		ApiKey admin = admin(org);
		Perspectives p = perspectives(org);
		Map<String, Object> created = apply(admin, file(grantingKey("ok", p.live())), false);
		assertEquals("CREATE", change(created, "ok").get("action"), created.toString());
		assertEquals(0, created.get("errors"));
		List<Map<String, Object>> objects = exportedObjects(admin, "ok");
		assertEquals(1, objects.size(), objects.toString());
		assertEquals("PERSPECTIVE", objects.get(0).get("scope"));
		assertEquals(p.live().toString(), objects.get(0).get("object"));
		assertEquals("READ_WRITE", objects.get(0).get("type"));

		Map<String, Object> again = apply(admin, file(grantingKey("ok", p.live())), false);
		assertEquals("UNCHANGED", change(again, "ok").get("action"), again.toString());
	}
}
