/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.OrganizationData.IgnoreViolation;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * updateOrganizationIgnoreViolation patch semantics: each of the three lists is
 * independent -- an omitted (null) list leaves the stored one unchanged, an
 * empty list clears it, a provided list replaces it. Before the fix an omitted
 * list was stored as empty, so updating one list silently cleared the others.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class OrganizationIgnoreViolationPatchTest {

	private static final List<String> LICENSE = List.of("pkg:npm/license-.*");
	private static final List<String> SECURITY = List.of("pkg:maven/security/.*");
	private static final List<String> OPERATIONAL = List.of("pkg:pypi/operational-.*");

	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TestInitializer testInitializer;

	private final WhoUpdated wu = WhoUpdated.getTestWhoUpdated();
	private UUID orgUuid;

	@BeforeEach
	void seed() {
		orgUuid = testInitializer.obtainOrganization().getUuid();
		organizationService.updateIgnoreViolation(orgUuid, LICENSE, SECURITY, OPERATIONAL, wu);
	}

	private IgnoreViolation stored() {
		return getOrganizationService.getOrganizationData(orgUuid).get().getIgnoreViolation();
	}

	@Test
	void omittedListsAreLeftUnchanged() {
		List<String> newSecurity = List.of("pkg:golang/.*");
		organizationService.updateIgnoreViolation(orgUuid, null, newSecurity, null, wu);

		IgnoreViolation iv = stored();
		assertEquals(LICENSE, iv.getLicenseViolationRegexIgnore(), "omitted license list must be kept");
		assertEquals(newSecurity, iv.getSecurityViolationRegexIgnore(), "provided list must replace the stored one");
		assertEquals(OPERATIONAL, iv.getOperationalViolationRegexIgnore(), "omitted operational list must be kept");
	}

	@Test
	void emptyListClearsOnlyThatList() {
		organizationService.updateIgnoreViolation(orgUuid, List.of(), null, null, wu);

		IgnoreViolation iv = stored();
		assertTrue(iv.getLicenseViolationRegexIgnore().isEmpty(), "an explicit empty list must clear");
		assertEquals(SECURITY, iv.getSecurityViolationRegexIgnore());
		assertEquals(OPERATIONAL, iv.getOperationalViolationRegexIgnore());
	}

	@Test
	void allNullIsANoOp() {
		organizationService.updateIgnoreViolation(orgUuid, null, null, null, wu);

		IgnoreViolation iv = stored();
		assertEquals(LICENSE, iv.getLicenseViolationRegexIgnore());
		assertEquals(SECURITY, iv.getSecurityViolationRegexIgnore());
		assertEquals(OPERATIONAL, iv.getOperationalViolationRegexIgnore());
	}

	@Test
	void omittedListsOnAnOrgWithNoSettingsStayEmpty() {
		UUID freshOrg = testInitializer.obtainOrganization().getUuid();
		organizationService.updateIgnoreViolation(freshOrg, LICENSE, null, null, wu);

		IgnoreViolation iv = getOrganizationService.getOrganizationData(freshOrg).get().getIgnoreViolation();
		assertEquals(LICENSE, iv.getLicenseViolationRegexIgnore());
		assertTrue(iv.getSecurityViolationRegexIgnore().isEmpty());
		assertTrue(iv.getOperationalViolationRegexIgnore().isEmpty());
	}
}
