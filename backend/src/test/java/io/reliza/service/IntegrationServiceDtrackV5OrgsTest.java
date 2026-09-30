/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.DependencyTrackVersion;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.repositories.IntegrationRepository;

/**
 * The orgs the affected-ranges sweep refreshes as undated: those whose
 * Dependency-Track integration is enabled and detected as version 5, in one
 * query, and an unreadable integration does not take the others down with it.
 */
class IntegrationServiceDtrackV5OrgsTest {

	private static Integration dtrack(UUID org, DependencyTrackVersion version, boolean enabled) {
		IntegrationData d = new IntegrationData();
		d.setOrg(org);
		d.setType(IntegrationType.DEPENDENCYTRACK);
		d.setIdentifier(CommonVariables.BASE_INTEGRATION_IDENTIFIER);
		d.setDtrackVersion(version);
		d.setIsEnabled(enabled);
		Integration i = new Integration();
		i.setRecordData(Utils.dataToRecord(d));
		return i;
	}

	@Test
	void onlyEnabledDetectedVersion5OrgsCount() {
		UUID v5 = UUID.randomUUID();
		Integration unreadable = new Integration();
		unreadable.setRecordData(Map.of("org", "not-a-uuid", "type", IntegrationType.DEPENDENCYTRACK.name()));
		IntegrationRepository repository = mock(IntegrationRepository.class);
		when(repository.listBaseIntegrationsByType(IntegrationType.DEPENDENCYTRACK.name())).thenReturn(List.of(
				dtrack(v5, DependencyTrackVersion.V5, true),
				unreadable,
				dtrack(UUID.randomUUID(), DependencyTrackVersion.V4, true),
				dtrack(UUID.randomUUID(), null, true),
				dtrack(UUID.randomUUID(), DependencyTrackVersion.V5, false)));

		assertEquals(Set.of(v5), new IntegrationService(repository).listOrgsWithDtrackV5());
	}
}
