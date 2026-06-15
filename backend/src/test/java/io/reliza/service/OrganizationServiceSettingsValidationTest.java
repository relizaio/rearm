/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;

/**
 * Pin the {@code updateSettings} bounds check for
 * {@code notificationRetentionDays} (Phase 6c): values outside 14..730 must
 * throw {@link RelizaException} with the bounds in the message (the GraphQL
 * exception handler carries it to tenants), before any write happens. The
 * 14-day floor is load-bearing — it keeps the retention sweep clear of the
 * email digest's maximum parking window plus the delivery retry curve.
 */
public class OrganizationServiceSettingsValidationTest {

	private OrganizationService service;
	private WhoUpdated wu;

	@BeforeEach
	void setUp() throws Exception {
		service = new OrganizationService(null);

		GetOrganizationService getOrgService = mock(GetOrganizationService.class);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettings()).thenReturn(null);
		when(getOrgService.getOrganizationData(any(UUID.class))).thenReturn(Optional.of(od));
		// getOrganization left unstubbed (Optional.empty by Mockito default):
		// in-bounds values pass validation and fail later at the entity
		// lookup with a distinguishable message — see boundary test below.
		Field f = OrganizationService.class.getDeclaredField("getOrganizationService");
		f.setAccessible(true);
		f.set(service, getOrgService);

		wu = mock(WhoUpdated.class);
	}

	private OrganizationData.Settings retentionPatch(int days) {
		OrganizationData.Settings patch = new OrganizationData.Settings();
		patch.setNotificationRetentionDays(days);
		return patch;
	}

	@Test
	void retentionBelowMinimumIsRejected() {
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.updateSettings(UUID.randomUUID(), retentionPatch(13), wu));
		assertTrue(e.getMessage().contains("14") && e.getMessage().contains("730"),
				"bounds must reach the tenant; got: " + e.getMessage());
	}

	@Test
	void retentionAboveMaximumIsRejected() {
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.updateSettings(UUID.randomUUID(), retentionPatch(731), wu));
		assertTrue(e.getMessage().contains("notificationRetentionDays"),
				"got: " + e.getMessage());
	}

	@Test
	void zeroAndNegativeRetentionAreRejected() {
		assertThrows(RelizaException.class,
				() -> service.updateSettings(UUID.randomUUID(), retentionPatch(0), wu));
		assertThrows(RelizaException.class,
				() -> service.updateSettings(UUID.randomUUID(), retentionPatch(-90), wu));
	}

	@Test
	void boundaryValuesPassValidation() {
		// 14 and 730 must clear the bounds check; the flow then fails at the
		// (unstubbed) entity lookup, proving validation did not reject them.
		for (int days : new int[] {14, 730}) {
			RelizaException e = assertThrows(RelizaException.class,
					() -> service.updateSettings(UUID.randomUUID(), retentionPatch(days), wu));
			assertTrue(e.getMessage().contains("Organization entity not found"),
					days + " must be accepted by the bounds check; got: " + e.getMessage());
		}
	}
}
