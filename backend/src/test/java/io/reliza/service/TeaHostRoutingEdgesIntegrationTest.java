/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;

/**
 * TPF-142 mutation round: what the host-routing classes leave open. Every controller is mounted on the host base,
 * and an organization that is archived, or that has no TEA id yet, is served on neither its domain's TEA paths nor its
 * domain's /.well-known/tea, though its host is assigned and its mode is DOMAIN. Since TPF-149 a serving write other
 * than NONE mints the id, so the no-id row is one the TPF-142 code wrote before it, and a fresh choice is served at once.
 */
public class TeaHostRoutingEdgesIntegrationTest extends TeaHostRoutingIntegrationTestBase {


	private HttpResponse<String> onHost(String path, String host, String bearer) throws Exception {
		return get(root() + path, bearer, on(host));
	}

	@Test
	void theReleaseListsAndTheProductReleaseAreMountedOnTheHostBase() throws Exception {
		TeaWorld w = world();
		assign(B_HOST, org.getUuid());
		serve(org.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE);

		HttpResponse<String> componentReleases = onHost(HOST_BASE + "/componentReleases", B_HOST, null);
		assertEquals(200, componentReleases.statusCode(), componentReleases.body());
		assertTrue(uuidsOf(componentReleases).contains(w.pub().head().toString()), componentReleases.body());
		HttpResponse<String> productReleases = onHost(HOST_BASE + "/productReleases", B_HOST, w.cover());
		assertEquals(200, productReleases.statusCode(), productReleases.body());
		assertEquals(List.of(w.prod().head().toString()), uuidsOf(productReleases));
		HttpResponse<String> productRelease = onHost(HOST_BASE + "/productRelease/" + w.prod().head(), B_HOST, w.cover());
		assertEquals(200, productRelease.statusCode(), productRelease.body());
	}

	/** TPF-149 row 10: a DOMAIN row without an id, as the TPF-142 code left it (not through setTeaServing), stays dark. */
	@Test
	void aDomainOrganizationWithoutATeaIdIsNotServed() throws Exception {
		Organization o = testInitializer.obtainOrganization();
		assertNull(getOrganizationService.getOrganizationData(o.getUuid()).orElseThrow().getTeaUuid(), "precondition: no TEA id");
		assign(A_HOST, o.getUuid());
		jdbcTemplate.update("UPDATE rearm.organizations SET record_data = jsonb_set(jsonb_set(record_data, '{"
				+ OrganizationData.TEA_SERVING_FIELD + "}', '\"DOMAIN\"'), '{" + OrganizationData.TEA_WELL_KNOWN_HOSTING_FIELD
				+ "}', '\"DOMAIN\"') WHERE uuid = ?", o.getUuid());

		assertObjectUnknown(onHost(HOST_BASE + "/components", A_HOST, null), "no TEA id: nothing to serve");
		assertEquals(404, get(root() + "/.well-known/tea", null, on(A_HOST)).statusCode(), "and no document");
		assertNull(getOrganizationService.getOrganizationData(o.getUuid()).orElseThrow().getTeaUuid(), "nothing reading the row mints");
	}

	/** TPF-149 row 11: a serving choice on an organization without an id mints it and serves the chosen shape at once. */
	@Test
	@SuppressWarnings("unchecked")
	void aDomainChoiceOnAnOrganizationWithoutATeaIdIsServedAtOnce() throws Exception {
		Organization o = testInitializer.obtainOrganization();
		assign(A_HOST, o.getUuid());
		serve(o.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.DOMAIN);
		assertNotNull(getOrganizationService.getOrganizationData(o.getUuid()).orElseThrow().getTeaUuid());
		HttpResponse<String> components = onHost(HOST_BASE + "/components", A_HOST, null);
		assertEquals(200, components.statusCode(), components.body());
		HttpResponse<String> document = get(root() + "/.well-known/tea", null, on(A_HOST));
		assertEquals(200, document.statusCode(), document.body());
		List<Map<String, Object>> endpoints = (List<Map<String, Object>>) json(document).get("endpoints");
		assertEquals("https://tea-a.test/tea", endpoints.get(0).get("url"));

		Organization p = testInitializer.obtainOrganization();
		serve(p.getUuid(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE);
		UUID t = getOrganizationService.getOrganizationData(p.getUuid()).orElseThrow().getTeaUuid();
		assertNotNull(t);
		HttpResponse<String> uuidBase = get(root() + "/tea/" + t + "/v1.0.0/components", null, Map.of());
		assertEquals(200, uuidBase.statusCode(), uuidBase.body());
	}
}
