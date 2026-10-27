/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;

/**
 * Permission templates applied by value (TPF-87, TPF-112 architecture round 4 section 5, rows 4b, 5, 6
 * and 7): what approveCliLogin makes of a template's rows. The clamp at approval is the control, not the
 * save.
 */
public class PermissionTemplateApprovalTest extends PermissionTemplateTestBase {

	// ---------- row 4b: a COMPONENT row naming nothing is stored and carried at approval ----------

	@Test
	public void row4bAComponentRowNamingNothingIsStoredAndCarriedOntoTheKey() throws Exception {
		World w = world();
		UUID x = UUID.randomUUID();
		UUID t = create(w.alice(), createInput(w.org(), "x-" + UUID.randomUUID(), null,
				List.of(componentRow(x, PermissionType.READ_ONLY))));
		Set<String> key = approveFromTemplate(w.alice(), w.org(), PermissionType.READ_WRITE, t);
		assertTrue(key.contains(line(PermissionScope.COMPONENT, x, PermissionType.READ_ONLY)), key.toString());
	}

	// ---------- rows 5 and 7: approval yields the template's permissions; applied by value ----------

	@Test
	public void rows5And7ApprovalCarriesTheTemplateAndLaterEditsChangeNothingApproved() throws Exception {
		World w = world();
		UUID t1 = create(w.alice(), t1(w));
		Set<String> expected = Set.of(
				line(PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.READ_WRITE, PermissionFunction.LIFECYCLE_UPDATE),
				line(PermissionScope.COMPONENT, w.c().getUuid(), PermissionType.READ_ONLY));
		assertEquals(expected, approveFromTemplate(w.alice(), w.org(), PermissionType.READ_WRITE, t1));
		UUID key = keyOfLastApproval;

		ok(update(w.alice(), updateInput(t1, null, null, List.of(orgRow(w.org(), PermissionType.READ_ONLY)))), "updatePermissionTemplate");
		assertEquals(Boolean.TRUE, ((Map<String, Object>) delete(w.alice(), t1).getData()).get("deletePermissionTemplate"));
		assertEquals(expected, keyPermissions(key, w.org()), "the key approved from T1 still carries READ_WRITE");
	}

	// ---------- row 6: above the user's rights is clipped at approval, not at save ----------

	@Test
	public void row6AboveTheUsersRightsIsClippedAtApprovalNotAtSave() throws Exception {
		World w = world();
		UUID t2 = create(w.bob(), createInput(w.org(), "T2-" + UUID.randomUUID(), null,
				List.of(orgRow(w.org(), PermissionType.ADMIN))));
		assertEquals(Set.of(line(PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.ADMIN)), stored(t2, w.org()),
				"stored as saved");
		assertEquals(Set.of(line(PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.READ_ONLY)),
				approveFromTemplate(w.bob(), w.org(), PermissionType.ADMIN, t2));
	}
}
