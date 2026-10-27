/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.model.AgentBoardData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.PermissionTemplate;
import io.reliza.model.PermissionTemplateData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreatePermissionTemplateDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.service.PermissionTemplateService;

/**
 * Permission templates on the browser lane (TPF-87, TPF-112 architecture round 4 section 5, rows 1 to 4
 * and 8): owner-only create, list, update and delete, membership, the shape bounds at save and the
 * sealed order.
 */
public class PermissionTemplateTest extends PermissionTemplateTestBase {

	// ---------- row 1: create, list, update, delete own ----------

	@Test
	public void row1CreateListUpdateAndDeleteOwn() throws Exception {
		World w = world();
		UUID t1 = create(w.alice(), t1(w));
		List<Map<String, Object>> listed = list(w.alice(), w.org());
		assertEquals(1, listed.size(), listed.toString());
		assertEquals(t1.toString(), listed.get(0).get("uuid"));
		assertEquals(w.alice().getUuid().toString(), listed.get(0).get("user"));
		assertEquals(Set.of(line(PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.READ_WRITE, PermissionFunction.LIFECYCLE_UPDATE),
				line(PermissionScope.COMPONENT, w.c().getUuid(), PermissionType.READ_ONLY)), stored(t1, w.org()));

		String renamed = "T1-renamed-" + UUID.randomUUID();
		Map<String, Object> updated = ok(update(w.alice(), updateInput(t1, renamed, null,
				List.of(componentRow(w.c().getUuid(), PermissionType.READ_ONLY)))), "updatePermissionTemplate");
		assertEquals(renamed, updated.get("name"));
		assertEquals("first", updated.get("description"), "a null field keeps its value");
		assertEquals(Set.of(line(PermissionScope.COMPONENT, w.c().getUuid(), PermissionType.READ_ONLY)), stored(t1, w.org()),
				"permissions replace the set: the ORGANIZATION row is gone");

		Map<String, Object> data = ((Map<String, Object>) delete(w.alice(), t1).getData());
		assertEquals(Boolean.TRUE, data.get("deletePermissionTemplate"));
		assertTrue(list(w.alice(), w.org()).isEmpty());
		assertTrue(permissionTemplateService.getPermissionTemplate(t1).isEmpty());
		assertEquals(2, auditService.getAuditForEntity(t1, TableName.PERMISSION_TEMPLATES, 1000, 0).size(),
				"one audit row for the update, one for the delete");
	}

	// ---------- row 2: another user's are not visible ----------

	@Test
	public void row2AnotherUsersTemplatesAreNotVisibleNorWritable() throws Exception {
		World w = world();
		UUID t1 = create(w.alice(), t1(w));
		PermissionTemplate before = rowOf(t1);
		assertTrue(list(w.bob(), w.org()).isEmpty(), "bob sees none of alice's templates");

		String unknownUpdate = onlyError(update(w.bob(), updateInput(UUID.randomUUID(), "x", null, null)));
		String unknownDelete = onlyError(delete(w.bob(), UUID.randomUUID()));
		assertEquals("Not authorized", unknownUpdate);
		assertEquals(unknownUpdate, onlyError(update(w.bob(), updateInput(t1, "stolen", null, null))));
		assertEquals(unknownDelete, onlyError(delete(w.bob(), t1)));
		assertEquals("Not authorized", unknownDelete);

		PermissionTemplate after = rowOf(t1);
		assertEquals(before.getRevision(), after.getRevision());
		assertEquals(before.getRecordData(), after.getRecordData(), "T1 is unchanged");
	}

	// ---------- row 3: membership, and a former member (F-7) ----------

	@Test
	public void row3AUserOfAnotherOrganizationCannotCreateNorList() throws Exception {
		World w = world();
		Organization other = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!other.getUuid().equals(w.org().getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		UserData stranger = member(other, PermissionType.ADMIN);
		ExecutionResult created = asPerson(stranger, CREATE, Map.of("t", createInput(w.org(), "S-" + UUID.randomUUID(), null, null)));
		// AccessDeniedException("Not a member of this organization") reaches the client as the uniform text (4.4)
		assertEquals("Not authorized", onlyError(created));
		assertTrue(permissionTemplateService.listForUser(stranger.getUuid(), w.org().getUuid()).isEmpty());
		Map<String, Object> vars = new HashMap<>();
		vars.put("o", w.org().getUuid().toString());
		assertEquals("Not authorized", onlyError(asPerson(stranger, LIST, vars)));
	}

	@Test
	public void row3AFormerMemberCanDeleteButNotValidateAgainstTheOrganization() throws Exception {
		World w = world();
		UserData carol = member(w.org(), PermissionType.READ_ONLY);
		UUID t3 = create(carol, createInput(w.org(), "T3-" + UUID.randomUUID(), null,
				List.of(orgRow(w.org(), PermissionType.READ_ONLY))));
		userService.removeUserFromOrg(w.org().getUuid(), carol.getUuid(), WU);
		UserData former = userService.getUserData(carol.getUuid()).orElseThrow();
		PermissionTemplate before = rowOf(t3);

		List<Map<String, Object>> bogusBoard = List.of(row(PermissionScope.BOARD, UUID.randomUUID(), PermissionType.READ_ONLY, null, null));
		String unknown = onlyError(update(former, updateInput(UUID.randomUUID(), null, null, bogusBoard)));
		String refused = onlyError(update(former, updateInput(t3, null, null, bogusBoard)));
		assertEquals("Not authorized", refused, "not the BOARD text");
		assertEquals(unknown, refused, "byte-equal to the unknown-uuid answer");
		PermissionTemplate after = rowOf(t3);
		assertEquals(before.getRevision(), after.getRevision());
		assertEquals(before.getRecordData(), after.getRecordData(), "T3 is unchanged");

		Map<String, Object> data = delete(former, t3).getData();
		assertEquals(Boolean.TRUE, data.get("deletePermissionTemplate"), "delete stays owner-only");
		assertTrue(permissionTemplateService.getPermissionTemplate(t3).isEmpty());
	}

	// ---------- row 4: shape at save (F-6 bounds, F-2 BOARD, approvals, name, cap) ----------

	private static String repeat(String s, int n) {
		return s.repeat(n);
	}

	@Test
	public void row4BoundsOnNameDescriptionAndRowsOnCreateAndUpdate() throws Exception {
		World w = world();
		UserData a = w.alice();
		String smiley = new String(Character.toChars(0x1F600)); // two UTF-16 code units, one code point
		UUID t = create(a, createInput(w.org(), "bounds-" + UUID.randomUUID(), null, null));

		// name: 129 refused, exactly 128 saves; blank refused
		String n129 = repeat("n", 129);
		assertEquals("A template's name is at most 128 characters", onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), n129, null, null)))));
		assertEquals("A template's name is at most 128 characters", onlyError(update(a, updateInput(t, n129, null, null))));
		String n128 = repeat("m", 120) + UUID.randomUUID().toString().substring(0, 8);
		assertEquals(128, n128.length());
		create(a, createInput(w.org(), n128, null, null));
		String n128b = repeat("u", 120) + UUID.randomUUID().toString().substring(0, 8);
		ok(update(a, updateInput(t, n128b, null, null)), "updatePermissionTemplate");
		assertEquals("Template name is required", onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), "   ", null, null)))));
		assertEquals("Template name is required", onlyError(update(a, updateInput(t, "", null, null))));

		// description: 513 code points refused (smiley and ASCII), 512 code points of the smiley save (F-8)
		String d513 = repeat(smiley, 513);
		String d512 = repeat(smiley, 512);
		String text = "A template's description is at most 512 characters";
		assertEquals(text, onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), "d-" + UUID.randomUUID(), d513, null)))));
		assertEquals(text, onlyError(update(a, updateInput(t, null, d513, null))));
		assertEquals(text, onlyError(update(a, updateInput(t, null, repeat("x", 513), null))));
		UUID withLong = create(a, createInput(w.org(), "d-" + UUID.randomUUID(), d512, null));
		String read = permissionTemplateService.getPermissionTemplateData(withLong).orElseThrow().getDescription();
		assertEquals(d512, read, "refused, never cut: 512 code points read back whole");
		ok(update(a, updateInput(t, null, d512, null)), "updatePermissionTemplate");

		// rows: 201 distinct COMPONENT rows refused, 200 save
		List<Map<String, Object>> r201 = new ArrayList<>();
		for (int i = 0; i < 201; i++) r201.add(componentRow(UUID.randomUUID(), PermissionType.READ_ONLY));
		List<Map<String, Object>> r200 = r201.subList(0, 200);
		String rows = "A template holds at most 200 permission rows";
		assertEquals(rows, onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), "r-" + UUID.randomUUID(), null, r201)))));
		assertEquals(rows, onlyError(update(a, updateInput(t, null, null, r201))));
		UUID r = create(a, createInput(w.org(), "r-" + UUID.randomUUID(), null, r200));
		assertEquals(200, stored(r, w.org()).size());
		ok(update(a, updateInput(t, null, null, r200)), "updatePermissionTemplate");
		assertEquals(200, stored(t, w.org()).size());
	}

	@Test
	public void row4GrantObjectsApprovalsNameAndCap() throws Exception {
		World w = world();
		UserData a = w.alice();
		UUID bogus = UUID.randomUUID();
		List<Map<String, Object>> badBoard = List.of(row(PermissionScope.BOARD, bogus, PermissionType.READ_ONLY, null, null));
		assertEquals("A BOARD grant names " + bogus + ", which is not a board of this organization",
				onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), "b-" + UUID.randomUUID(), null, badBoard)))));
		Organization other = testInitializer.obtainOrganization();
		if (!other.getUuid().equals(w.org().getUuid())) {
			Component target = componentService.createComponent("pt_" + UUID.randomUUID(), other.getUuid(),
					ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
			AgentBoardData foreign = agentBoardService.createBoard(other.getUuid(), "pt-" + UUID.randomUUID(), "foreign",
					List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
			assertEquals("A BOARD grant names " + foreign.getUuid() + ", which is not a board of this organization",
					onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), "b-" + UUID.randomUUID(), null,
							List.of(row(PermissionScope.BOARD, foreign.getUuid(), PermissionType.READ_ONLY, null, null)))))));
		}
		// a board of the organization saves
		create(a, createInput(w.org(), "b-" + UUID.randomUUID(), null,
				List.of(row(PermissionScope.BOARD, w.b().getUuid(), PermissionType.READ_ONLY, List.of("BOARD_READ"), null))));

		List<Map<String, Object>> badApprovals = List.of(row(PermissionScope.ORGANIZATION, w.org().getUuid(),
				PermissionType.READ_ONLY, null, List.of("NO_SUCH_ROLE_" + UUID.randomUUID())));
		assertEquals("Invalid approvals sent",
				onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), "a-" + UUID.randomUUID(), null, badApprovals)))));
		UUID t = create(a, createInput(w.org(), "a-" + UUID.randomUUID(), null, null));
		assertEquals("Invalid approvals sent", onlyError(update(a, updateInput(t, null, null, badApprovals))));
		assertEquals("A BOARD grant names " + bogus + ", which is not a board of this organization",
				onlyError(update(a, updateInput(t, null, null, badBoard))));

		String name = "dup-" + UUID.randomUUID();
		create(a, createInput(w.org(), name, null, null));
		assertEquals("A template with the name '" + name + "' already exists.",
				onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), name, null, null)))));
		assertEquals("A template with the name '" + name + "' already exists.", onlyError(update(a, updateInput(t, name, null, null))));

		// the cap: the user's 101st template in the organization is refused
		int held = permissionTemplateService.listForUser(a.getUuid(), w.org().getUuid()).size();
		for (int i = held; i < PermissionTemplateService.MAX_TEMPLATES_PER_ORG; i++) {
			permissionTemplateService.createPermissionTemplate(CreatePermissionTemplateDto.builder().org(w.org().getUuid())
					.name("cap-" + i + "-" + UUID.randomUUID()).permissions(List.<UserGroupPermissionDto>of()).build(), a.getUuid(), WU);
		}
		assertEquals(100, permissionTemplateService.listForUser(a.getUuid(), w.org().getUuid()).size());
		assertEquals("You already have 100 templates in this organization; delete one first.",
				onlyError(asPerson(a, CREATE, Map.of("t", createInput(w.org(), "cap-101", null, null)))));
	}

	// ---------- row 8: the sealed order ----------

	@Test
	public void row8ASealedSystemAnswersTheSameForAnUnknownAndAnExistingTemplate() throws Exception {
		World w = world();
		UUID t1 = create(w.alice(), t1(w));
		licenseStatus.setSystemSealed(true);
		try {
			String unknown = onlyError(update(w.alice(), updateInput(UUID.randomUUID(), "x", null, null)));
			String existing = onlyError(update(w.alice(), updateInput(t1, "x", null, null)));
			assertTrue(unknown.contains("System is sealed"), unknown);
			assertEquals(unknown, existing, "the system state is answered before the lookup");
		} finally {
			licenseStatus.setSystemSealed(false);
		}
		assertEquals(t1.toString(), list(w.alice(), w.org()).get(0).get("uuid"));
	}

	@Test
	public void theNullOrganizationListsEveryOrganization() throws Exception {
		World w = world();
		UUID t1 = create(w.alice(), t1(w));
		List<Map<String, Object>> all = list(w.alice(), null);
		assertTrue(all.stream().anyMatch(m -> t1.toString().equals(m.get("uuid"))), all.toString());
		PermissionTemplateData ptd = permissionTemplateService.getPermissionTemplateData(t1).orElseThrow();
		assertEquals(w.alice().getUuid(), ptd.getUser());
		assertEquals(w.org().getUuid(), ptd.getOrg());
	}
}
