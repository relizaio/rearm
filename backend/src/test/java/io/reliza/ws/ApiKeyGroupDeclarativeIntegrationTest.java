/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.DeclarativePruneMode;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.repositories.ApiKeyGroupRepository;
import io.reliza.service.ApiKeyGroupService;

/**
 * The RD3-11 stronger-than-caller checks compare the target key's EFFECTIVE permissions, its own and
 * its ACTIVE API key groups' (TPF-91 architecture-5 decision 8, rows 13 and 14): a non-admin
 * CONFIGURATION_WRITE caller cannot mint for, archive, change, re-activate or prune a declared key that
 * a group makes stronger than itself, approval roles included; the export and the list keep the key's
 * own grants. Row 14 runs through the programmatic fetchers, as row 13 does.
 *
 * <p>A key has two secret slots (ApiKeyData.MAX_SECRETS), so a refused mint is shown by the stored
 * secrets being unchanged and a later allowed one by rotating a slot.
 */
@SuppressWarnings("unchecked")
public class ApiKeyGroupDeclarativeIntegrationTest extends ApiKeyDeclarativeTestBase {

	@Autowired ApiKeyGroupService apiKeyGroupService;
	@Autowired ApiKeyGroupRepository apiKeyGroupRepository;

	private ApiKeyGroupData group(Organization org, Collection<UUID> members, UserGroupPermissionDto... permissions)
			throws RelizaException {
		ApiKeyGroupData g = apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder()
				.name("akg-" + UUID.randomUUID()).org(org.getUuid()).build(), WU);
		return apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.apiKeys(Set.copyOf(members)).permissions(Arrays.asList(permissions)).build(), WU);
	}

	private void members(ApiKeyGroupData g, UUID... keys) throws RelizaException {
		apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).apiKeys(Set.of(keys)).build(), WU);
	}

	private void permissions(ApiKeyGroupData g, UserGroupPermissionDto... permissions) throws RelizaException {
		apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.permissions(Arrays.asList(permissions)).build(), WU);
	}

	private void restore(ApiKeyGroupData g) throws RelizaException {
		apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.status(ApiKeyGroupStatus.ACTIVE).build(), WU);
	}

	private static UserGroupPermissionDto orgWide(Organization org, PermissionType type) {
		return UserGroupPermissionDto.builder().scope(PermissionScope.ORGANIZATION).objectId(org.getUuid()).type(type).build();
	}

	/** Each stored secret as slot and hash: equal before and after means nothing was minted. */
	private Set<String> secrets(Organization org, String name) {
		return ApiKeyData.dataFromRecord(keyNamed(org, name)).getSecrets().stream()
				.map(s -> s.getSlot() + ":" + s.getHash()).collect(Collectors.toSet());
	}

	private static String errors(ExecutionResult r) {
		return r.getErrors().toString();
	}

	private ExecutionResult mintCall(ApiKey caller, String key, int slot, boolean rotate) {
		return as(caller, MINT, Map.of("k", key, "s", slot, "r", rotate));
	}

	// ---------- row 13 ----------

	@Test
	public void aKeyStrongThroughItsGroupIsStrongerThanAWriter() throws Exception {
		Organization org = org();
		ApiKey admin = admin(org);
		ApiKey writer = caller(org, PermissionType.READ_WRITE, PermissionFunction.CONFIGURATION_WRITE);
		assertEquals("CREATE", change(apply(admin, file(key("name", "grouped", "type", "FREEFORM",
				"permissions", key("type", "READ_ONLY"))), false), "grouped").get("action"));
		ApiKey k = keyNamed(org, "grouped");

		// Before any group: the own grants fit under the writer.
		assertEquals(true, mint(writer, "grouped", 1, false).get("minted"));
		assertEquals(1, ApiKeyData.dataFromRecord(keyNamed(org, "grouped")).getSecrets().size());

		ApiKeyGroupData g = group(org, Set.of(k.getUuid()), orgWide(org, PermissionType.ADMIN));
		Set<String> held = secrets(org, "grouped");
		ExecutionResult mint = mintCall(writer, "grouped", 2, false);
		assertTrue(errors(mint).contains("cannot mint a secret for a key stronger than the caller"), errors(mint));
		assertTrue(errors(mint).contains("organization-wide ADMIN reduced to READ_WRITE"), errors(mint));
		assertEquals(held, secrets(org, "grouped"), "nothing minted");
		ExecutionResult archive = as(writer, ARCHIVE, Map.of("n", "grouped"));
		assertTrue(errors(archive).contains("cannot archive a key stronger than the caller"), errors(archive));
		assertEquals("ACTIVE", declared(admin, "grouped").get("status"));
		assertTrue(((String) change(apply(writer, file(key("name", "grouped", "notes", "mine now")), false), "grouped")
				.get("message")).startsWith("cannot change a key stronger than the caller"));
		assertEquals(null, declared(admin, "grouped").get("notes"), "notes unchanged");
		assertEquals("UNCHANGED", change(apply(writer, file(key("name", "grouped", "permissions", key("type", "READ_ONLY"))), false),
				"grouped").get("action"), "nothing changes, no check: the writer's file may still name the key");

		// Re-activation is a change.
		data(as(admin, ARCHIVE, Map.of("n", "grouped")), "archiveApiKeyProgrammatic");
		assertTrue(((String) change(apply(writer, file(key("name", "grouped", "status", "ACTIVE")), false), "grouped")
				.get("message")).startsWith("cannot change a key stronger than the caller"));
		assertEquals("INACTIVE", declared(admin, "grouped").get("status"));
		Map<String, Object> revived = change(apply(admin, file(key("name", "grouped", "status", "ACTIVE")), false), "grouped");
		assertEquals("UPDATE", revived.get("action"));
		assertEquals(List.of("status"), revived.get("fields"), "an administrator has no excess");

		// The prune of an authoritative file.
		OrganizationData.Settings prune = new OrganizationData.Settings();
		prune.setDeclarativePrune(DeclarativePruneMode.ARCHIVE);
		organizationService.updateSettings(org.getUuid(), prune, WU);
		Map<String, Object> pruned = change(apply(writer, authoritativeFile(key("name", "writers-own")), false), "grouped");
		assertEquals("ERROR", pruned.get("action"), pruned.toString());
		assertTrue(((String) pruned.get("message")).contains("cannot archive a key stronger than the caller"), pruned.toString());
		assertEquals("ACTIVE", declared(admin, "grouped").get("status"));

		// The export and the list keep the own grants.
		Map<String, Object> spec = data(as(admin, EXPORT, Map.of("k", List.of("grouped"))), "exportApiKeysProgrammatic");
		Map<String, Object> exported = ((List<Map<String, Object>>) spec.get("keys")).get(0);
		assertEquals("READ_ONLY", ((Map<String, Object>) exported.get("permissions")).get("type"));
		assertEquals("READ_ONLY", ((Map<String, Object>) declared(admin, "grouped").get("permissions")).get("type"));

		// Allowed once the key leaves the group.
		members(g);
		assertEquals(true, mint(writer, "grouped", 2, false).get("minted"));
		Map<String, Object> notes = change(apply(writer, file(key("name", "grouped", "notes", "mine now")), false), "grouped");
		assertEquals("UPDATE", notes.get("action"), notes.toString());
		assertEquals(List.of("notes"), notes.get("fields"));

		// Allowed while the group is INACTIVE; refused again once it is restored.
		members(g, k.getUuid());
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
		Set<String> beforeRotation = secrets(org, "grouped");
		assertEquals(true, mint(writer, "grouped", 1, true).get("minted"));
		assertNotEquals(beforeRotation, secrets(org, "grouped"), "slot 1 rotated");
		restore(g);
		assertTrue(errors(as(writer, ARCHIVE, Map.of("n", "grouped"))).contains("cannot archive a key stronger than the caller"));

		// Approval roles a group holds are in the demand (security F-4).
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
		ApiKeyGroupData gq = group(org, Set.of(k.getUuid()), UserGroupPermissionDto.builder().scope(PermissionScope.ORGANIZATION)
				.objectId(org.getUuid()).type(PermissionType.NONE).approvals(List.of("QA")).build());
		Set<String> n = secrets(org, "grouped");
		ExecutionResult qaMint = mintCall(writer, "grouped", 1, true);
		assertTrue(errors(qaMint).contains("cannot mint a secret for a key stronger than the caller"), errors(qaMint));
		assertTrue(errors(qaMint).contains("approvals [QA] not held"), errors(qaMint));
		assertFalse(errors(qaMint).contains("reduced to"), "only the approval loop refuses: " + errors(qaMint));
		assertEquals(n, secrets(org, "grouped"));
		ExecutionResult qaArchive = as(writer, ARCHIVE, Map.of("n", "grouped"));
		assertTrue(errors(qaArchive).contains("cannot archive a key stronger than the caller"), errors(qaArchive));
		assertTrue(errors(qaArchive).contains("approvals [QA] not held"), errors(qaArchive));
		assertEquals("ACTIVE", declared(admin, "grouped").get("status"));
		String qaChange = (String) change(apply(writer, file(key("name", "grouped", "notes", "qa now")), false), "grouped").get("message");
		assertTrue(qaChange.startsWith("cannot change a key stronger than the caller"), qaChange);
		assertTrue(qaChange.endsWith("approvals [QA] not held"), qaChange);
		assertEquals("mine now", declared(admin, "grouped").get("notes"));
		Map<String, Object> ownSpec = data(as(admin, EXPORT, Map.of("k", List.of("grouped"))), "exportApiKeysProgrammatic");
		Object ownApprovals = ((Map<String, Object>) ((List<Map<String, Object>>) ownSpec.get("keys")).get(0).get("permissions")).get("approvals");
		assertTrue(null == ownApprovals || ((List<?>) ownApprovals).isEmpty(), "the export keeps the own grants: " + ownApprovals);

		// Positive control (1): the writer granted QA on its own row, level and functions kept in the same entry.
		apiKeyService.setPermissionsOnApiKey(writer.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE,
				Set.of(PermissionFunction.CONFIGURATION_WRITE), Set.of("QA"))), WU);
		assertEquals(true, mint(writer, "grouped", 1, true).get("minted"));
		assertNotEquals(n, secrets(org, "grouped"));

		// Positive control (2): a plain second writer, refused while K is in GQ, mints once K is out.
		ApiKey writer2 = caller(org, PermissionType.READ_WRITE, PermissionFunction.CONFIGURATION_WRITE);
		Set<String> n1 = secrets(org, "grouped");
		assertTrue(errors(mintCall(writer2, "grouped", 2, true)).contains("approvals [QA] not held"));
		assertEquals(n1, secrets(org, "grouped"));
		assertEquals(true, mint(admin, "grouped", 2, true).get("minted"), "an administrator is unaffected");
		members(gq);
		assertEquals(true, mint(writer2, "grouped", 2, true).get("minted"));
	}

	// ---------- row 14 ----------

	@Test
	public void theEffectiveDemandsEdges() throws Exception {
		Organization org = org();
		ApiKey admin = admin(org);
		ApiKey writer = caller(org, PermissionType.READ_WRITE, PermissionFunction.CONFIGURATION_WRITE);
		apply(admin, file(key("name", "grouped", "type", "FREEFORM", "permissions", key("type", "READ_ONLY"))), false);
		ApiKey k = keyNamed(org, "grouped");

		// (a) the combined level is the higher one, and equal is not stronger
		ApiKeyGroupData g = group(org, Set.of(k.getUuid()), orgWide(org, PermissionType.READ_WRITE));
		assertEquals(true, mint(writer, "grouped", 1, false).get("minted"));
		permissions(g, orgWide(org, PermissionType.ADMIN));
		assertTrue(errors(mintCall(writer, "grouped", 1, true)).contains("stronger than the caller"));

		// (d) the archive under the ADMIN group, then once the group is INACTIVE
		assertTrue(errors(as(writer, ARCHIVE, Map.of("n", "grouped"))).contains("cannot archive a key stronger than the caller"));
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
		Map<String, Object> archived = data(as(writer, ARCHIVE, Map.of("n", "grouped")), "archiveApiKeyProgrammatic");
		assertEquals("INACTIVE", archived.get("status"));
	}

	@Test
	public void aGroupOfAnotherOrganizationAddsNothingToTheDemand() throws Exception {
		Organization org = org();
		Organization org2 = org();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		ApiKey admin = admin(org);
		ApiKey writer = caller(org, PermissionType.READ_WRITE, PermissionFunction.CONFIGURATION_WRITE);
		apply(admin, file(key("name", "grouped", "type", "FREEFORM", "permissions", key("type", "READ_ONLY"))), false);
		ApiKey k = keyNamed(org, "grouped");
		group(org, Set.of(k.getUuid()), orgWide(org, PermissionType.READ_WRITE));
		ApiKeyGroupData foreign = group(org2, Set.of(), orgWide(org2, PermissionType.ADMIN));
		ApiKeyGroup row = apiKeyGroupRepository.findById(foreign.getUuid()).orElseThrow();
		ApiKeyGroupData d = ApiKeyGroupData.dataFromRecord(row);
		d.addApiKey(k.getUuid());
		row.setRecordData(Utils.dataToRecord(d));
		apiKeyGroupRepository.save(row);
		assertEquals(true, mint(writer, "grouped", 1, false).get("minted"), "the org2 group is not in the demand");
	}
}
