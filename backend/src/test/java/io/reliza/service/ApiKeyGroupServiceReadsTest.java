/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.Utils;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.repositories.ApiKeyGroupRepository;

/**
 * What the group reads check again on the rows they were handed (task TPF-91, mutation round). The by-key
 * query already filters organization, membership and ACTIVE, so these checks guard a row the query should
 * not return: a mocked repository returns one, and the row must add nothing to a key's permissions.
 */
public class ApiKeyGroupServiceReadsTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID KEY = UUID.randomUUID();

	private final ApiKeyGroupRepository repository = mock(ApiKeyGroupRepository.class);

	private ApiKeyGroupService service() {
		ApiKeyGroupService s = new ApiKeyGroupService();
		ReflectionTestUtils.setField(s, "apiKeyGroupRepository", repository);
		return s;
	}

	/** A group row of {@code org} with the given status and members, holding one org-wide grant tagged by its approval {@code tag}. */
	private static ApiKeyGroup row(UUID org, ApiKeyGroupStatus status, String tag, UUID... members) {
		ApiKeyGroupData d = ApiKeyGroupData.apiKeyGroupDataFactory(CreateApiKeyGroupDto.builder()
				.name("g-" + tag + "-" + UUID.randomUUID()).org(org).build());
		d.setStatus(status);
		for (UUID m : members) d.addApiKey(m);
		d.setPermission(PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY, List.of(tag));
		ApiKeyGroup r = new ApiKeyGroup();
		r.setUuid(UUID.randomUUID());
		r.setRecordData(Utils.dataToRecord(d));
		return r;
	}

	private static Set<String> tags(List<Set<UserPermission>> sets) {
		return sets.stream().flatMap(Set::stream).flatMap(p -> p.getApprovals().stream()).collect(Collectors.toSet());
	}

	@Test
	public void onlyAnActiveGroupOfTheOrganizationHoldingTheKeyAddsPermissions() {
		when(repository.findByApiKeyAndOrg(anyString(), anyString())).thenReturn(List.of(
				row(ORG, ApiKeyGroupStatus.ACTIVE, "good", KEY),
				row(ORG, ApiKeyGroupStatus.INACTIVE, "inactive", KEY),
				row(UUID.randomUUID(), ApiKeyGroupStatus.ACTIVE, "foreign", KEY),
				row(ORG, ApiKeyGroupStatus.ACTIVE, "notmember", UUID.randomUUID())));

		List<Set<UserPermission>> sets = service().permissionSetsForKey(KEY, ORG);
		assertEquals(1, sets.size(), sets.toString());
		assertEquals(Set.of("good"), tags(sets));
	}

	@Test
	public void noKeyOrNoOrganizationAddsNothingAndReadsNothing() {
		assertTrue(service().permissionSetsForKey(null, ORG).isEmpty());
		assertTrue(service().permissionSetsForKey(KEY, null).isEmpty());
	}

	@Test
	public void theActiveListLeavesOutInactiveGroupsAndTheFullListKeepsThem() {
		when(repository.findAllByOrganization(anyString())).thenReturn(List.of(
				row(ORG, ApiKeyGroupStatus.ACTIVE, "on"), row(ORG, ApiKeyGroupStatus.INACTIVE, "off")));

		assertEquals(1, service().getApiKeyGroupsByOrganization(ORG).size());
		assertEquals(ApiKeyGroupStatus.ACTIVE, service().getApiKeyGroupsByOrganization(ORG).get(0).getStatus());
		assertEquals(2, service().getAllApiKeyGroupsByOrganization(ORG).size());
	}
}
