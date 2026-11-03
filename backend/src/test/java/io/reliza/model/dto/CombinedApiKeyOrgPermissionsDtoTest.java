/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CombinedApiKeyOrgPermissionsDto.PermissionSource;
import io.reliza.model.dto.CombinedApiKeyOrgPermissionsDto.PermissionSourceKind;
import io.reliza.model.dto.CombinedApiKeyOrgPermissionsDto.SourcedPermission;
import io.reliza.service.OrganizationService;

/**
 * The sources and the order of the combined key read (TPF-123 architecture-3 row 4, unit part): pure, no
 * Spring. The effective set is built with the same combinePermissions the authorization path runs.
 */
public class CombinedApiKeyOrgPermissionsDtoTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID KEY = UUID.randomUUID();
	private static final UUID BOARD_A = UUID.randomUUID();

	/** A parsed group row of ORG with a uuid and one grant. */
	private static ApiKeyGroupData group(PermissionScope scope, UUID object, PermissionType type, PermissionFunction fn) {
		ApiKeyGroupData d = ApiKeyGroupData.apiKeyGroupDataFactory(CreateApiKeyGroupDto.builder()
				.name("g-" + UUID.randomUUID()).org(ORG).build());
		d.addApiKey(KEY);
		d.setPermission(scope, object, type, List.of(fn), null);
		ApiKeyGroup r = new ApiKeyGroup();
		r.setUuid(UUID.randomUUID());
		r.setRecordData(Utils.dataToRecord(d));
		return ApiKeyGroupData.dataFromRecord(r);
	}

	private static UserPermission up(PermissionScope scope, UUID object) {
		return UserPermission.permissionFactory(ORG, scope, object, PermissionType.READ_ONLY, null, null);
	}

	@Test
	public void eachRowNamesItsOwnAndGroupSourcesAndRowsComeOrganizationWideFirst() {
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(ORG);
		akd.setPermission(ORG, PermissionScope.ORGANIZATION, ORG, PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null);
		ApiKeyGroupData g1 = group(PermissionScope.ORGANIZATION, ORG, PermissionType.READ_WRITE, PermissionFunction.ARTIFACT_DOWNLOAD);
		ApiKeyGroupData g2 = group(PermissionScope.BOARD, BOARD_A, PermissionType.READ_ONLY, PermissionFunction.BOARD_READ);
		Set<UserPermission> own = akd.getPermissions(ORG).getOrgPermissionsAsSet(ORG);
		Set<UserPermission> effective = OrganizationService.combinePermissions(own, ORG,
				List.of(g1.getOrgPermissions(ORG), g2.getOrgPermissions(ORG))).getOrgPermissionsAsSet(ORG);

		CombinedApiKeyOrgPermissionsDto dto = CombinedApiKeyOrgPermissionsDto.of(KEY, ORG, effective, akd, List.of(g1, g2));

		assertEquals(KEY, dto.apiKeyUuid());
		assertEquals(ORG, dto.org());
		assertEquals(2, dto.permissions().size(), dto.toString());
		SourcedPermission orgRow = dto.permissions().get(0);
		assertEquals(PermissionScope.ORGANIZATION, orgRow.scope());
		assertEquals(PermissionType.READ_WRITE, orgRow.type());
		assertEquals(Set.of(PermissionFunction.RESOURCE, PermissionFunction.ARTIFACT_DOWNLOAD), orgRow.functions());
		assertEquals(List.of(
				new PermissionSource(PermissionSourceKind.OWN, null, null, PermissionType.READ_ONLY,
						Set.of(PermissionFunction.RESOURCE), Set.of()),
				new PermissionSource(PermissionSourceKind.GROUP, g1.getUuid(), g1.getName(), PermissionType.READ_WRITE,
						Set.of(PermissionFunction.ARTIFACT_DOWNLOAD), Set.of())), orgRow.sources());
		SourcedPermission boardRow = dto.permissions().get(1);
		assertEquals(PermissionScope.BOARD, boardRow.scope());
		assertEquals(BOARD_A, boardRow.object());
		assertEquals(List.of(new PermissionSource(PermissionSourceKind.GROUP, g2.getUuid(), g2.getName(),
				PermissionType.READ_ONLY, Set.of(PermissionFunction.BOARD_READ), Set.of())), boardRow.sources());
	}

	@Test
	public void rowsSortByDescendingScopeOrdinalThenObjectText() {
		UUID lo = new UUID(0L, 1L);
		UUID hi = new UUID(Long.MAX_VALUE, 2L);
		Set<UserPermission> effective = new HashSet<>(List.of(
				up(PermissionScope.RELEASE, UUID.randomUUID()), up(PermissionScope.COMPONENT, UUID.randomUUID()),
				up(PermissionScope.BOARD, hi), up(PermissionScope.ORGANIZATION, ORG), up(PermissionScope.BOARD, lo)));

		List<SourcedPermission> rows = CombinedApiKeyOrgPermissionsDto.of(KEY, ORG, effective, null, null).permissions();

		assertEquals(List.of(PermissionScope.ORGANIZATION, PermissionScope.BOARD, PermissionScope.BOARD,
				PermissionScope.COMPONENT, PermissionScope.RELEASE), rows.stream().map(SourcedPermission::scope).toList());
		assertTrue(String.valueOf(rows.get(1).object()).compareTo(String.valueOf(rows.get(2).object())) < 0,
				"two rows of one scope sort by object uuid text: " + rows);
		assertEquals(String.valueOf(lo).compareTo(String.valueOf(hi)) < 0 ? lo : hi, rows.get(1).object());
	}

	@Test
	public void aRowNoSourceHoldsHasEmptySourcesAndNullsBecomeEmpty() {
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(ORG);
		CombinedApiKeyOrgPermissionsDto dto = CombinedApiKeyOrgPermissionsDto.of(KEY, ORG,
				Set.of(up(PermissionScope.BOARD, BOARD_A)), akd, null);
		assertEquals(1, dto.permissions().size());
		SourcedPermission row = dto.permissions().get(0);
		assertTrue(row.sources().isEmpty(), row.toString());
		assertTrue(row.functions().isEmpty());
		assertTrue(row.approvals().isEmpty());
		assertNull(row.meta());

		assertTrue(CombinedApiKeyOrgPermissionsDto.of(KEY, ORG, null, akd, null).permissions().isEmpty());
		assertTrue(CombinedApiKeyOrgPermissionsDto.of(KEY, ORG, null, null, List.of()).permissions().isEmpty());
	}
}
