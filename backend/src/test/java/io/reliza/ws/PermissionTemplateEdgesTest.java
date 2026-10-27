/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.PermissionTemplate;
import io.reliza.model.PermissionTemplateData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.UpdatePermissionTemplateDto;
import io.reliza.repositories.PermissionTemplateRepository;

/**
 * Permission templates, what the first test round leaves open (TPF-112, mutation round): the trimmed
 * bounds, the stored form, the uniqueness rule (per user and organization, case sensitive, trimmed), the
 * order of a list, every field of the web document, revision and actor on an update, a null row, the
 * system state on every field, the Organization-not-found answer, a global administrator who is not a
 * member, an unreadable row, and the row lock an update takes.
 */
public class PermissionTemplateEdgesTest extends PermissionTemplateTestBase {

	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PermissionTemplateRepository permissionTemplateRepository;
	@Autowired private PlatformTransactionManager transactionManager;

	private UserData memberOfBoth(Organization a, Organization b) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("T " + tag, "t-" + tag + "@templates.io", true, List.of(a.getUuid(), b.getUuid()),
				"t-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private Map<String, Object> createdMap(UserData who, Map<String, Object> input) {
		return ok(asPerson(who, CREATE, Map.of("t", input)), "createPermissionTemplate");
	}

	private static String uid(Map<String, Object> m) {
		return (String) m.get("uuid");
	}

	// ---------- the bounds count trimmed text; the stored form is trimmed ----------

	@Test
	public void theBoundsCountTheTrimmedTextAndTheStoredFormIsTrimmed() throws Exception {
		World w = world();
		UUID t = create(w.alice(), createInput(w.org(), "n".repeat(128) + "   ", "d".repeat(512) + "   ", null));
		PermissionTemplateData stored = permissionTemplateService.getPermissionTemplateData(t).orElseThrow();
		assertEquals("n".repeat(128), stored.getName(), "128 characters after trim, stored without the padding");
		assertEquals("d".repeat(512), stored.getDescription());

		assertTrue(onlyError(asPerson(w.alice(), CREATE, Map.of("t", createInput(w.org(), "m".repeat(129), null, null)))).contains("at most 128"));
		assertTrue(onlyError(asPerson(w.alice(), CREATE, Map.of("t", createInput(w.org(), "ok-" + UUID.randomUUID(), "d".repeat(513), null)))).contains("at most 512"));

		// the same on update
		assertEquals("m".repeat(128), ok(update(w.alice(), updateInput(t, "m".repeat(128) + "  ", "e".repeat(512) + " ", null)), "updatePermissionTemplate").get("name"));
		assertTrue(onlyError(update(w.alice(), updateInput(t, "m".repeat(129), null, null))).contains("at most 128"));
		assertTrue(onlyError(update(w.alice(), updateInput(t, null, "e".repeat(513), null))).contains("at most 512"));

		Map<String, Object> padded = createdMap(w.alice(), createInput(w.org(), "  Padded-" + UUID.randomUUID() + "  ", "  some words  ", null));
		assertTrue(!((String) padded.get("name")).startsWith(" ") && !((String) padded.get("name")).endsWith(" "), "name stored trimmed: " + padded.get("name"));
		assertEquals("some words", padded.get("description"));
		UUID p = UUID.fromString(uid(padded));
		Map<String, Object> renamed = ok(update(w.alice(), updateInput(p, "  Renamed-" + UUID.randomUUID() + "  ", "  other words  ", null)), "updatePermissionTemplate");
		assertTrue(!((String) renamed.get("name")).endsWith(" ") && !((String) renamed.get("name")).startsWith(" "));
		assertEquals("other words", renamed.get("description"));
	}

	@Test
	public void anUpdateKeepsTheFieldsItDoesNotName() throws Exception {
		World w = world();
		String name = "keep-" + UUID.randomUUID();
		UUID t = create(w.alice(), createInput(w.org(), name, "described", List.of(
				orgRow(w.org(), PermissionType.READ_WRITE, "LIFECYCLE_UPDATE"), componentRow(w.c().getUuid(), PermissionType.READ_ONLY))));
		var rows = stored(t, w.org());

		Map<String, Object> onlyDescription = ok(update(w.alice(), updateInput(t, null, "changed", null)), "updatePermissionTemplate");
		assertEquals(name, onlyDescription.get("name"), "a null name keeps the name");
		assertEquals(rows, stored(t, w.org()), "no permissions list keeps the set");

		Map<String, Object> onlyName = ok(update(w.alice(), updateInput(t, name + "-2", null, null)), "updatePermissionTemplate");
		assertEquals("changed", onlyName.get("description"));
		assertEquals(rows, stored(t, w.org()));
		assertEquals(w.org().getUuid().toString(), onlyName.get("org"), "the organization never changes");
		assertEquals(w.alice().getUuid().toString(), onlyName.get("user"), "nor the owner");
	}

	// ---------- the web document ----------

	@Test
	@SuppressWarnings("unchecked")
	public void theWebDocumentCarriesEveryFieldAndNamesTheCallerAsTheActor() throws Exception {
		World w = world();
		Map<String, Object> created = createdMap(w.alice(), createInput(w.org(), "web-" + UUID.randomUUID(), "all fields",
				List.of(orgRow(w.org(), PermissionType.READ_WRITE, "LIFECYCLE_UPDATE"), componentRow(w.c().getUuid(), PermissionType.READ_ONLY))));
		assertEquals(w.org().getUuid().toString(), created.get("org"));
		assertEquals(w.alice().getUuid().toString(), created.get("user"));
		assertEquals(w.alice().getUuid().toString(), created.get("lastUpdatedBy"), "the create names the caller");
		assertNotNull(created.get("createdDate"));
		assertEquals("all fields", created.get("description"));
		List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) created.get("permissions")).get("permissions");
		assertEquals(2, rows.size(), rows.toString());
		assertTrue(rows.stream().anyMatch(r -> "COMPONENT".equals(r.get("scope")) && w.c().getUuid().toString().equals(r.get("object"))
				&& "READ_ONLY".equals(r.get("type"))), rows.toString());

		Map<String, Object> updated = ok(update(w.alice(), updateInput(UUID.fromString(uid(created)), null, "edited", null)), "updatePermissionTemplate");
		assertEquals(w.alice().getUuid().toString(), updated.get("lastUpdatedBy"), "the update names the caller");
		assertEquals(created.get("createdDate"), updated.get("createdDate"));
		assertEquals(2, ((List<?>) ((Map<String, Object>) updated.get("permissions")).get("permissions")).size());
	}

	@Test
	public void anUpdateIsOneRevisionOneAuditRowAndMovesTheDate() throws Exception {
		World w = world();
		UUID t = create(w.alice(), t1(w));
		PermissionTemplate before = permissionTemplateService.getPermissionTemplate(t).orElseThrow();
		assertEquals(0, before.getRevision());
		ZonedDateTime created = before.getLastUpdatedDate();
		Thread.sleep(30);

		ok(update(w.alice(), updateInput(t, null, "once", null)), "updatePermissionTemplate");
		PermissionTemplate after = permissionTemplateService.getPermissionTemplate(t).orElseThrow();
		assertEquals(1, after.getRevision(), "one revision for one update");
		assertTrue(after.getLastUpdatedDate().isAfter(created), "last_updated_date moves on an update");
		assertEquals(1, auditService.getAuditForEntity(t, CommonVariables.TableName.PERMISSION_TEMPLATES, 1000, 0).size());
		ok(update(w.alice(), updateInput(t, null, "twice", null)), "updatePermissionTemplate");
		assertEquals(2, permissionTemplateService.getPermissionTemplate(t).orElseThrow().getRevision());
	}

	// ---------- uniqueness ----------

	@Test
	public void theNameIsUniquePerUserAndOrganizationCaseSensitiveAndTrimmed() throws Exception {
		World w = world();
		Organization other = testInitializer.obtainOrganization();
		UserData both = memberOfBoth(w.org(), other);
		String base = "Dev-" + UUID.randomUUID();
		create(both, createInput(w.org(), base, null, null));

		String clash = onlyError(asPerson(both, CREATE, Map.of("t", createInput(w.org(), "  " + base + "  ", null, null))));
		assertTrue(clash.contains("already exists"), "a padded copy of the name is the same name: " + clash);
		create(both, createInput(w.org(), base.toLowerCase(), null, null));            // another case is another name
		create(w.bob(), createInput(w.org(), base, null, null));                       // another user, the same name
		create(both, createInput(other, base, null, null));                            // another organization, the same name

		// a rename onto a name taken in the same organization is refused; onto a name held in another is not
		UUID inOther = create(both, createInput(other, "elsewhere-" + UUID.randomUUID(), null, null));
		ok(update(both, updateInput(inOther, base + "-free", null, null)), "updatePermissionTemplate");
		UUID heldInFirst = create(both, createInput(w.org(), "second-" + UUID.randomUUID(), null, null));
		assertTrue(onlyError(update(both, updateInput(heldInFirst, base, null, null))).contains("already exists"));
		ok(update(both, updateInput(inOther, base.toLowerCase(), null, null)), "updatePermissionTemplate");   // held only in the first org
		ok(update(both, updateInput(heldInFirst, "  " + "second-renamed-" + UUID.randomUUID(), null, null)), "updatePermissionTemplate");
	}

	@Test
	public void aListIsOrderedByNameAndNamesItsOrganization() throws Exception {
		World w = world();
		Organization other = testInitializer.obtainOrganization();
		UserData both = memberOfBoth(w.org(), other);
		String tag = UUID.randomUUID().toString().substring(0, 6);
		create(both, createInput(w.org(), "bbb" + tag, null, null));
		create(both, createInput(w.org(), "aaa" + tag, null, null));
		create(both, createInput(w.org(), "ccc" + tag, null, null));
		create(both, createInput(other, "aab" + tag, null, null));
		create(both, createInput(other, "ddd" + tag, null, null));

		assertEquals(List.of("aaa" + tag, "bbb" + tag, "ccc" + tag), list(both, w.org()).stream().map(m -> (String) m.get("name")).toList(),
				"the first organization's templates only, by name");
		assertEquals(List.of("aab" + tag, "ddd" + tag), list(both, other).stream().map(m -> (String) m.get("name")).toList());
		assertEquals(List.of("aaa" + tag, "aab" + tag, "bbb" + tag, "ccc" + tag, "ddd" + tag),
				list(both, null).stream().map(m -> (String) m.get("name")).toList(), "every organization, by name");
	}

	// ---------- null rows, the organization, the global administrator ----------

	@Test
	public void aNullRowInThePermissionsListIsSkippedOnCreateAndUpdate() throws Exception {
		World w = world();
		List<Map<String, Object>> withNull = new ArrayList<>(Arrays.asList(null, componentRow(w.c().getUuid(), PermissionType.READ_ONLY)));
		UUID t = create(w.alice(), createInput(w.org(), "null-row-" + UUID.randomUUID(), null, withNull));
		assertEquals(java.util.Set.of(line(PermissionScope.COMPONENT, w.c().getUuid(), PermissionType.READ_ONLY)), stored(t, w.org()));

		List<Map<String, Object>> again = new ArrayList<>(Arrays.asList(componentRow(w.c().getUuid(), PermissionType.READ_WRITE), null));
		ok(update(w.alice(), updateInput(t, null, null, again)), "updatePermissionTemplate");
		assertEquals(java.util.Set.of(line(PermissionScope.COMPONENT, w.c().getUuid(), PermissionType.READ_WRITE)), stored(t, w.org()));
	}

	@Test
	public void anUnknownOrganizationIsNotFoundAndAGlobalAdministratorNeedNotBeAMember() throws Exception {
		World w = world();
		Map<String, Object> input = new HashMap<>(createInput(w.org(), "nowhere-" + UUID.randomUUID(), null, null));
		input.put("org", UUID.randomUUID().toString());
		assertEquals("Organization not found", onlyError(asPerson(w.alice(), CREATE, Map.of("t", input))));

		UserData stranger = member(testInitializer.obtainOrganization(), PermissionType.READ_ONLY);
		jdbcTemplate.update("UPDATE rearm.users SET record_data = jsonb_set(record_data, '{" + CommonVariables.GLOBAL_ADMIN_FIELD
				+ "}', 'true'::jsonb) WHERE uuid = ?", stranger.getUuid());
		UserData admin = userService.getUserData(stranger.getUuid()).orElseThrow();
		assertTrue(admin.isGlobalAdmin() && !admin.getOrganizations().contains(w.org().getUuid()));

		UUID t = create(admin, createInput(w.org(), "admin-" + UUID.randomUUID(), null, null));
		assertEquals(List.of(t.toString()), list(admin, w.org()).stream().map(PermissionTemplateEdgesTest::uid).toList());
		assertTrue(list(w.alice(), w.org()).stream().noneMatch(m -> t.toString().equals(m.get("uuid"))), "owner only");
	}

	@Test
	public void everyFieldAnswersTheSystemStateBeforeAnythingElse() throws Exception {
		World w = world();
		UUID t = create(w.alice(), t1(w));
		Map<String, Object> badOrg = new HashMap<>(createInput(w.org(), "x", null, null));
		badOrg.put("org", UUID.randomUUID().toString());
		for (boolean sealed : new boolean[] {true, false}) {
			if (sealed) licenseStatus.setSystemSealed(true); else licenseStatus.setLicenseValid(false);
			try {
				String expected = sealed ? "System is sealed" : "License is invalid";
				assertTrue(onlyError(asPerson(w.alice(), LIST, new HashMap<>(Map.of("o", w.org().getUuid().toString())))).contains(expected));
				assertTrue(onlyError(asPerson(w.alice(), LIST, new HashMap<>(Map.of("o", UUID.randomUUID().toString())))).contains(expected),
						"before the membership check");
				assertTrue(onlyError(asPerson(w.alice(), CREATE, Map.of("t", badOrg))).contains(expected), "before the organization lookup");
				assertTrue(onlyError(update(w.alice(), updateInput(t, "x", null, null))).contains(expected));
				assertTrue(onlyError(delete(w.alice(), t)).contains(expected));
				assertTrue(onlyError(delete(w.bob(), t)).contains(expected), "another user's delete answers the same");
			} finally {
				licenseStatus.setSystemSealed(false);
				licenseStatus.setLicenseValid(true);
			}
		}
		assertEquals(t.toString(), list(w.alice(), w.org()).get(0).get("uuid"), "nothing was changed");
	}

	// ---------- unreadable rows, null uuid ----------

	@Test
	public void anUnreadableRowIsSkippedAndANullUuidIsNotFound() throws Exception {
		World w = world();
		UUID good = create(w.alice(), t1(w));
		PermissionTemplateData template = permissionTemplateService.getPermissionTemplateData(good).orElseThrow();
		PermissionTemplate bad = new PermissionTemplate();
		bad.setUuid(UUID.randomUUID());
		bad.setSchemaVersion(1);
		Map<String, Object> data = new HashMap<>(Utils.dataToRecord(template));
		data.put("name", "unreadable-" + UUID.randomUUID());
		bad.setRecordData(data);
		permissionTemplateRepository.save(bad);
		try {
			assertEquals(List.of(good.toString()), list(w.alice(), w.org()).stream().map(PermissionTemplateEdgesTest::uid).toList(),
					"the healthy template is listed, the unreadable row costs only itself");
			assertEquals(List.of(good.toString()), list(w.alice(), null).stream().map(PermissionTemplateEdgesTest::uid).toList());
		} finally {
			permissionTemplateRepository.delete(bad);
		}

		assertTrue(permissionTemplateService.getPermissionTemplate(null).isEmpty());
		assertTrue(permissionTemplateService.getPermissionTemplateData(null).isEmpty());
		RelizaException e = assertThrows(RelizaException.class, () -> permissionTemplateService.deletePermissionTemplate(null, WU));
		assertEquals("Permission template not found: null", e.getMessage());
		RelizaException e2 = assertThrows(RelizaException.class,
				() -> permissionTemplateService.updatePermissionTemplate(UpdatePermissionTemplateDto.builder().build(), WU));
		assertEquals("Permission template not found: null", e2.getMessage());
	}

	// ---------- the row lock ----------

	private void inNewTransaction(Runnable work) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		tx.executeWithoutResult(s -> work.run());
	}

	private static UpdatePermissionTemplateDto change(UUID t, String name, String description) {
		return UpdatePermissionTemplateDto.builder().uuid(t).name(name).description(description).build();
	}

	private void updateUnchecked(UpdatePermissionTemplateDto dto) {
		try {
			permissionTemplateService.updatePermissionTemplate(dto, WU);
		} catch (RelizaException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	public void anUpdateWorksOnTheRowAsCommittedNotOnTheCopyItsTransactionReadEarlier() throws Exception {
		World w = world();
		UUID t = create(w.alice(), t1(w));
		String renamed = "renamed-" + UUID.randomUUID();
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			permissionTemplateService.getPermissionTemplate(t).orElseThrow();               // the pre-lock copy, now managed
			inNewTransaction(() -> updateUnchecked(change(t, renamed, null)));          // another transaction renames and commits
			updateUnchecked(change(t, null, "later"));                                    // this one changes the description only
		});
		PermissionTemplateData stored = permissionTemplateService.getPermissionTemplateData(t).orElseThrow();
		assertEquals(renamed, stored.getName(), "the rename another transaction committed is kept");
		assertEquals("later", stored.getDescription());
	}

	@Test
	public void anUpdateWaitsForTheRowLockAndKeepsWhatWasCommittedMeanwhile() throws Exception {
		World w = world();
		UUID t = create(w.alice(), t1(w));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> update = new TransactionTemplate(transactionManager).execute(s -> {
				jdbcTemplate.queryForList("select uuid from rearm.permission_templates where uuid = ? for update", t);
				Future<?> f = executor.submit(() -> updateUnchecked(change(t, "after-the-lock-" + UUID.randomUUID(), null)));
				try {
					Thread.sleep(2_500);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertFalse(f.isDone(), "the update waits for the lock the other transaction holds");
				jdbcTemplate.update("update rearm.permission_templates set record_data = jsonb_set(record_data, '{description}', "
						+ "to_jsonb(cast(? as text))) where uuid = ?", "committed-while-locked", t);
				return f;
			});
			update.get(60, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}
		PermissionTemplateData stored = permissionTemplateService.getPermissionTemplateData(t).orElseThrow();
		assertEquals("committed-while-locked", stored.getDescription(), "what was committed while the update waited is kept");
		assertTrue(stored.getName().startsWith("after-the-lock-"));
	}
}
