/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.service.DeclaredLicenses.DeclaredLicense;
import io.reliza.service.DeclaredLicenses.DeclaredLicenseLevel;

/**
 * SCORE-39 T-8 (ADR-3): the declared licence of a release is its override, else its component's
 * licence with the component URL, else the organization default with the organization URL; blank is
 * unset at every level, the URL comes only from the level that won, and declared data resolves the
 * same for an EXTERNAL component and under the external sentinel organization.
 */
class DeclaredLicensesTest {

	private static OrganizationData org(String expression, String url) {
		OrganizationData od = new OrganizationData();
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setDefaultLicenseExpression(expression);
		settings.setDefaultLicenseUrl(url);
		od.setSettings(settings);
		return od;
	}

	private static ComponentData component(String expression, String url) {
		ComponentData cd = new ComponentData();
		cd.setLicenseExpression(expression);
		cd.setLicenseUrl(url);
		return cd;
	}

	private static ReleaseData release(String expression) {
		ReleaseData rd = new ReleaseData();
		rd.setLicenseExpression(expression);
		return rd;
	}

	@Test
	void theReleaseOverrideWinsWithoutAUrl() {
		assertEquals(new DeclaredLicense("MIT", null, DeclaredLicenseLevel.RELEASE),
				DeclaredLicenses.forRelease(org("AGPL-3.0-only", "https://org.example/l"),
						component("LicenseRef-Acme", "https://acme.example/l"), release("MIT")));
	}

	@Test
	void theComponentWinsOverTheOrganizationWithItsOwnUrl() {
		OrganizationData od = org("AGPL-3.0-only", "https://org.example/l");
		assertEquals(new DeclaredLicense("LicenseRef-Acme", "https://acme.example/l", DeclaredLicenseLevel.COMPONENT),
				DeclaredLicenses.forRelease(od, component("LicenseRef-Acme", "https://acme.example/l"), release(null)));
		assertEquals(new DeclaredLicense("LicenseRef-Acme", "https://acme.example/l", DeclaredLicenseLevel.COMPONENT),
				DeclaredLicenses.forRelease(od, component("LicenseRef-Acme", "https://acme.example/l"), release("  ")));
		assertEquals(new DeclaredLicense("Apache-2.0", null, DeclaredLicenseLevel.COMPONENT),
				DeclaredLicenses.forRelease(od, component("Apache-2.0", null), release(null)),
				"the organization's url never rides along with the component's expression");
	}

	@Test
	void theOrganizationDefaultAppliesWhenNothingBelowDeclares() {
		assertEquals(new DeclaredLicense("AGPL-3.0-only", "https://org.example/l", DeclaredLicenseLevel.ORGANIZATION),
				DeclaredLicenses.forRelease(org("AGPL-3.0-only", "https://org.example/l"), component(null, null), release(null)));
		assertEquals(new DeclaredLicense("AGPL-3.0-only", null, DeclaredLicenseLevel.ORGANIZATION),
				DeclaredLicenses.forRelease(org("AGPL-3.0-only", null), component("", "https://acme.example/l"), release("")));
	}

	@Test
	void nothingDeclaredIsNull() {
		assertNull(DeclaredLicenses.forRelease(org(null, null), component(null, null), release(null)));
		assertNull(DeclaredLicenses.forRelease(org(" ", "https://org.example/l"), component(" ", null), release(" ")));
	}

	@Test
	void anExternalComponentAndTheSentinelOrganizationResolveTheSame() {
		ComponentData external = component("Apache-2.0", "https://acme.example/l");
		external.setIsInternal(BelongsToOrganization.EXTERNAL);
		OrganizationData sentinel = org("AGPL-3.0-only", null);
		sentinel.setUuid(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		assertEquals(new DeclaredLicense("Apache-2.0", "https://acme.example/l", DeclaredLicenseLevel.COMPONENT),
				DeclaredLicenses.forRelease(sentinel, external, release(null)));
		assertEquals(new DeclaredLicense("AGPL-3.0-only", null, DeclaredLicenseLevel.ORGANIZATION),
				DeclaredLicenses.forRelease(sentinel, component(null, null), release(null)));
	}

	@Test
	void nullArgumentsAndNullSettingsAreSkipped() {
		assertNull(DeclaredLicenses.forRelease(null, null, null));
		assertEquals(new DeclaredLicense("MIT", null, DeclaredLicenseLevel.RELEASE),
				DeclaredLicenses.forRelease(null, null, release("MIT")));
		assertEquals(new DeclaredLicense("Apache-2.0", null, DeclaredLicenseLevel.COMPONENT),
				DeclaredLicenses.forRelease(null, component("Apache-2.0", null), null));
		assertEquals(new DeclaredLicense("AGPL-3.0-only", null, DeclaredLicenseLevel.ORGANIZATION),
				DeclaredLicenses.forRelease(org("AGPL-3.0-only", null), null, null));
		assertNull(DeclaredLicenses.forRelease(new OrganizationData(), component(null, null), release(null)),
				"an organization without settings declares nothing");
	}
}
