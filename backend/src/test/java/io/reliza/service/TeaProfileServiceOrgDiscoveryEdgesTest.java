/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;
import io.reliza.service.TeaProfileService.TeaOrgDiscovery;

/**
 * TPF-142 mutation round: the rows of orgDiscovery that TeaProfileServiceTest.orgDiscovery leaves open. A mode with
 * nothing to serve it advertises nothing (no TEA id, no assigned host), and the scheme of a DOMAIN base is https on a
 * custom host and the baseuri's own on the instance host.
 */
class TeaProfileServiceOrgDiscoveryEdgesTest extends TeaProfileServiceTestBase {

	private static final String HOST = "tea-a.test";

	private void assigned(String host) {
		when(teaHosts.hostOfOrg(org)).thenReturn(Optional.of(host));
	}

	@Test
	void legacyUuidWithoutATeaIdAdvertisesNothing() throws RelizaException {
		od.setTeaServing(TeaServing.LEGACY_UUID);
		TeaOrgDiscovery d = service.orgDiscovery(org);
		assertNull(d.teaUuid());
		assertNull(d.apiBase());
		assertNull(d.wellKnownDocument());
	}

	@Test
	void domainWithoutAnAssignedHostAdvertisesNothing() throws RelizaException {
		od.setTeaUuid(UUID.randomUUID());
		od.setTeaServing(TeaServing.DOMAIN);
		od.setTeaWellKnownHosting(TeaWellKnownHosting.DOMAIN);
		TeaOrgDiscovery d = service.orgDiscovery(org);
		assertNull(d.assignedHost());
		assertNull(d.apiBase());
		assertNull(d.wellKnownDocument());
		assertNull(d.wellKnownUrl());
	}

	@Test
	void domainWithoutATeaIdAdvertisesNothingEvenWithAnAssignedHost() throws RelizaException {
		od.setTeaServing(TeaServing.DOMAIN);
		od.setTeaWellKnownHosting(TeaWellKnownHosting.DOMAIN);
		assigned(HOST);
		TeaOrgDiscovery d = service.orgDiscovery(org);
		assertEquals(HOST, d.assignedHost());
		assertNull(d.apiBase());
		assertNull(d.wellKnownDocument());
		assertNull(d.wellKnownUrl());
	}

	@Test
	void aCustomHostIsAdvertisedOnHttpsWhateverTheBaseuriSchemeIs() throws RelizaException {
		props.setBaseuri("http://rearm.example.com/");
		od.setTeaUuid(UUID.randomUUID());
		od.setTeaServing(TeaServing.DOMAIN);
		od.setTeaWellKnownHosting(TeaWellKnownHosting.DOMAIN);
		assigned(HOST);
		TeaOrgDiscovery d = service.orgDiscovery(org);
		assertEquals("https://tea-a.test/tea", d.apiBase());
		assertEquals("https://tea-a.test/.well-known/tea", d.wellKnownUrl());
	}

	@Test
	void theInstanceHostIsAdvertisedOnTheBaseurisOwnScheme() throws RelizaException {
		props.setBaseuri("http://rearm.example.com/");
		od.setTeaUuid(UUID.randomUUID());
		od.setTeaServing(TeaServing.DOMAIN);
		od.setTeaWellKnownHosting(TeaWellKnownHosting.NONE);
		assigned("rearm.example.com");
		when(teaHosts.isBaseuriHost("rearm.example.com")).thenReturn(true);
		TeaOrgDiscovery d = service.orgDiscovery(org);
		assertEquals("http://rearm.example.com/tea", d.apiBase());
		assertNull(d.wellKnownUrl(), "hosting NONE: no document URL");
	}
}
