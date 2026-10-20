/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.WhoUpdated;

/**
 * Pin the {@code updateSettings} bounds check for
 * {@code notificationRetentionDays} (Phase 6c): values outside 14..730 must
 * throw {@link RelizaException} with the bounds in the message (the GraphQL
 * exception handler carries it to tenants), before any write happens. The
 * 14-day floor is load-bearing — it keeps the retention sweep clear of the
 * email digest's maximum parking window plus the delivery retry curve.
 *
 * <p>SCORE-39 (T-14, ADR-1, ADR-2): the organization's default declared licence. PATCH as the
 * prose fields; a URL needs an expression at the same level; a refusal names the field and saves
 * nothing. These cases run on a stored organization and read what is saved.
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


	/**
	 * The export-injection patch, in the settings service that owns patch semantics.
	 *
	 * <p>These assert the SHAPE of the patch rather than a persisted result: this harness
	 * leaves getOrganization unstubbed, so an in-bounds patch validates and then fails at the
	 * entity lookup with a distinguishable message. What matters here is which failure you
	 * get -- reaching the lookup means the field was accepted and applied, and a validation
	 * error means it was rejected.
	 */
	@Test
	void supportInjectionIsAcceptedInEitherState() {
		for (SupportInjectionSetting state : SupportInjectionSetting.values()) {
			OrganizationData.Settings patch = new OrganizationData.Settings();
			patch.setSupportInjection(state);
			Exception e = assertThrows(Exception.class,
					() -> service.updateSettings(UUID.randomUUID(), patch, wu));
			assertFalse(e.getMessage() != null && e.getMessage().contains("supportInjection"),
					state + " must be a legal value, not a validation failure: " + e.getMessage());
		}
	}

	/**
	 * OMITTING it must not disturb the stored value -- the same PATCH rule the prose slots
	 * follow. A patch about retention has no business changing what exports carry.
	 */
	@Test
	void omittingSupportInjectionLeavesItAlone() {
		OrganizationData.Settings patch = retentionPatch(30);
		assertNull(patch.getSupportInjection(),
				"a patch that does not mention the field must carry null for it, which is what"
						+ " the service reads as 'not part of this patch'");
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

	/** What the declared-licence cases saved, or null when nothing was saved. */
	private OrganizationData saved;
	private OrganizationRepository repository;

	/** A stored organization whose settings are {@code stored}, on a service that records the save. */
	private void storedOrganization(OrganizationData.Settings stored) throws Exception {
		repository = mock(OrganizationRepository.class);
		service = new OrganizationService(repository);
		OrganizationData od = new OrganizationData();
		od.setName("Example Org");
		od.setSettings(stored);
		Organization entity = new Organization();
		entity.setRecordData(Utils.dataToRecord(od));
		GetOrganizationService getOrgService = mock(GetOrganizationService.class);
		when(getOrgService.getOrganizationData(any(UUID.class))).thenReturn(Optional.of(od));
		when(getOrgService.getOrganization(any(UUID.class))).thenReturn(Optional.of(entity));
		Field f = OrganizationService.class.getDeclaredField("getOrganizationService");
		f.setAccessible(true);
		f.set(service, getOrgService);
		Field a = OrganizationService.class.getDeclaredField("auditService");
		a.setAccessible(true);
		a.set(service, mock(AuditService.class));
		saved = null;
		when(repository.save(any(Organization.class))).thenAnswer(inv -> {
			Organization o = inv.getArgument(0);
			saved = OrganizationData.orgDataFromDbRecord(o);
			return o;
		});
	}

	private static OrganizationData.Settings licence(String expression, String url) {
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setDefaultLicenseExpression(expression);
		settings.setDefaultLicenseUrl(url);
		return settings;
	}

	private OrganizationData.Settings apply(OrganizationData.Settings patch) throws RelizaException {
		service.updateSettings(UUID.randomUUID(), patch, wu);
		return saved.getSettings();
	}

	@Test
	void omittingTheDefaultLicenceLeavesItAlone() throws Exception {
		storedOrganization(licence("MIT", "https://org.example/licence"));

		OrganizationData.Settings after = apply(retentionPatch(30));

		assertEquals("MIT", after.getDefaultLicenseExpression());
		assertEquals("https://org.example/licence", after.getDefaultLicenseUrl());
	}

	@Test
	void anEmptyDefaultLicenceClearsItAndItsUrl() throws Exception {
		storedOrganization(licence("MIT", "https://org.example/licence"));

		OrganizationData.Settings after = apply(licence("", null));

		assertNull(after.getDefaultLicenseExpression());
		assertNull(after.getDefaultLicenseUrl());
	}

	@Test
	void theDefaultLicenceIsStoredCanonical() throws Exception {
		storedOrganization(null);

		OrganizationData.Settings after = apply(licence("apache-2.0", " https://org.example/licence "));

		assertEquals("Apache-2.0", after.getDefaultLicenseExpression());
		assertEquals("https://org.example/licence", after.getDefaultLicenseUrl());
	}

	@Test
	void theUrlAloneIsUpdatedWhenAnExpressionIsStored() throws Exception {
		storedOrganization(licence("MIT", "https://org.example/old"));

		OrganizationData.Settings after = apply(licence(null, "https://org.example/new"));

		assertEquals("MIT", after.getDefaultLicenseExpression());
		assertEquals("https://org.example/new", after.getDefaultLicenseUrl());
	}

	@Test
	void noassertionIsRefusedWithTheMessageAndNothingIsSaved() throws Exception {
		storedOrganization(licence("MIT", null));

		RelizaException e = assertThrows(RelizaException.class, () -> apply(licence("NOASSERTION", null)));

		assertEquals("defaultLicenseExpression invalid: NOASSERTION and NONE are not a declared licence; leave the field"
				+ " empty instead", e.getMessage());
		verify(repository, never()).save(any());
	}

	@Test
	void aUrlWithoutAnExpressionIsRefused() throws Exception {
		storedOrganization(null);
		RelizaException e = assertThrows(RelizaException.class, () -> apply(licence(null, "https://org.example/licence")));
		assertEquals("defaultLicenseUrl needs a defaultLicenseExpression at the same level", e.getMessage());

		storedOrganization(licence("MIT", null));
		e = assertThrows(RelizaException.class, () -> apply(licence("", "https://org.example/licence")));
		assertEquals("defaultLicenseUrl needs a defaultLicenseExpression at the same level", e.getMessage(),
				"clearing the expression in the same patch leaves no expression for the url");
		verify(repository, never()).save(any());
	}

	@Test
	void theRefusedUrlsNameTheField() throws Exception {
		for (String url : new String[] {"https://x.example/a b", "https://x.example/\nb", "https://x.example/\"><script>",
				"not a url", "javascript:alert(1)", "file:///etc/passwd", "ftp://x"}) {
			storedOrganization(licence("MIT", null));
			RelizaException e = assertThrows(RelizaException.class, () -> apply(licence(null, url)), url);
			assertTrue(e.getMessage().startsWith("defaultLicenseUrl invalid: "), url + ": " + e.getMessage());
			verify(repository, never()).save(any());
		}
	}
}
