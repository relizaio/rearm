/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.repositories.ComponentRepository;

/**
 * Task TEA-8, design round 2 D11: the organization's listing by perspective drops a member of
 * another organization instead of throwing, so one stale foreign row cannot deny the organization
 * its own listing. GetComponentService already keeps the perspective's own organization's members;
 * this pins the second line of the rule.
 */
class ComponentServicePerspectiveListingTest {

	private static ComponentData member(UUID org) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setName("tea8-" + cd.getUuid());
		cd.setType(ComponentType.COMPONENT);
		return cd;
	}

	@Test
	void aMemberOfAnotherOrganizationIsDroppedNotThrownOn() {
		UUID org = UUID.randomUUID();
		UUID perspective = UUID.randomUUID();
		ComponentData own = member(org);
		ComponentData foreign = member(UUID.randomUUID());
		GetComponentService getComponentService = mock(GetComponentService.class);
		when(getComponentService.listComponentsByPerspective(perspective)).thenReturn(List.of(foreign, own));
		ComponentService service = new ComponentService(mock(ComponentRepository.class), "https://rearm.example.com");
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);

		List<ComponentData> listed = service.listComponentDataByOrganizationAndPerspective(org, perspective,
				ComponentType.COMPONENT);

		assertEquals(List.of(own.getUuid()), listed.stream().map(ComponentData::getUuid).toList());
	}
}
