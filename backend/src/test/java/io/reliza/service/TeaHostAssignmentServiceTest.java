/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.repositories.SystemInfoRepository;
import io.reliza.service.TeaHostAssignmentService.TeaHostAssignment;
import io.reliza.ws.RelizaConfigProps;

/**
 * TPF-142, architecture section 4 rows 9 and 10: the host normalisation, the allow-list parse and the assignment
 * refusals, with the system_info and organization collaborators mocked. The map the mocks hold stands for the row.
 */
class TeaHostAssignmentServiceTest {

	private RelizaConfigProps props;
	private SystemInfoService systemInfoService;
	private SystemInfoRepository systemInfoRepository;
	private OrganizationRepository organizationRepository;
	private GetOrganizationService getOrganizationService;
	/** The stored map, as the native FOR UPDATE read and the unlocked read answer it. */
	private final Map<String, String> stored = new LinkedHashMap<>();
	private final Map<UUID, OrganizationData> orgs = new LinkedHashMap<>();

	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();

	@BeforeEach
	void wire() {
		props = new RelizaConfigProps();
		props.setBaseuri("https://rearm.localhost");
		props.setTeaCustomDomains("tea-a.test,tea-b.test,tea-c.test");
		systemInfoService = mock(SystemInfoService.class);
		systemInfoRepository = mock(SystemInfoRepository.class);
		organizationRepository = mock(OrganizationRepository.class);
		getOrganizationService = mock(GetOrganizationService.class);
		when(systemInfoService.getTeaHostAssignments()).thenAnswer(inv -> new LinkedHashMap<>(stored));
		when(systemInfoRepository.currentTeaHostAssignmentsForUpdate())
				.thenAnswer(inv -> stored.isEmpty() ? Optional.empty() : Optional.of(Utils.OM.writeValueAsString(stored)));
		doAnswer(inv -> {
			stored.put(inv.getArgument(0), inv.<UUID>getArgument(1).toString());
			return null;
		}).when(systemInfoService).assignTeaHost(anyString(), any(UUID.class));
		doAnswer(inv -> {
			stored.remove(inv.<String>getArgument(0));
			return null;
		}).when(systemInfoService).unassignTeaHost(anyString());
		when(getOrganizationService.getOrganizationData(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(orgs.get(inv.<UUID>getArgument(0))));
		when(organizationRepository.currentStatusForUpdate(any(UUID.class))).thenAnswer(inv -> Optional
				.ofNullable(orgs.get(inv.<UUID>getArgument(0))).map(od -> null == od.getStatus() ? null : od.getStatus().name()));
		when(organizationRepository.currentTeaServingForUpdate(any(UUID.class))).thenAnswer(inv -> Optional
				.ofNullable(orgs.get(inv.<UUID>getArgument(0))).map(od -> null == od.getTeaServing() ? null : od.getTeaServing().name()));
		orgs.put(a, org(a, "Org A", StatusEnum.ACTIVE, null));
		orgs.put(b, org(b, "Org B", StatusEnum.ACTIVE, null));
	}

	private static OrganizationData org(UUID uuid, String name, StatusEnum status, TeaServing serving) {
		OrganizationData od = new OrganizationData();
		od.setUuid(uuid);
		od.setName(name);
		od.setStatus(status);
		od.setTeaServing(serving);
		return od;
	}

	private TeaHostAssignmentService service() {
		TeaHostAssignmentService s = new TeaHostAssignmentService(props, systemInfoService, systemInfoRepository,
				organizationRepository, getOrganizationService);
		s.init();
		return s;
	}

	private static String repeat(char c, int n) {
		return String.valueOf(c).repeat(n);
	}

	// ------------------------------------------------------------------ row 9

	@Test
	void normalizeHostKeepsDnsNamesOnly() {
		assertEquals(Optional.of("tea-b.test"), TeaHostAssignmentService.normalizeHost(" Tea-B.test. "));
		assertEquals(Optional.of("tea-b.test"), TeaHostAssignmentService.normalizeHost("tea-b.test:443"));
		assertEquals(Optional.of("localhost"), TeaHostAssignmentService.normalizeHost("localhost"));
		assertEquals(Optional.of("a-b.test"), TeaHostAssignmentService.normalizeHost("a-b.test"));
		// a 253-character name: four labels of 63, 63, 63 and 61
		String n253 = repeat('a', 63) + "." + repeat('b', 63) + "." + repeat('c', 63) + "." + repeat('d', 61);
		assertEquals(253, n253.length());
		assertEquals(Optional.of(n253), TeaHostAssignmentService.normalizeHost(n253));
		String n254 = repeat('a', 63) + "." + repeat('b', 63) + "." + repeat('c', 63) + "." + repeat('d', 62);
		assertEquals(254, n254.length());
		for (String bad : List.of("https://x", "a/b", "a b", "a@b", "xn--\u00e9", "", "tea}", "a\"b", "a,b", "*", "*.acme.example",
				"[::1]", "a:b:c", "x;y", "x=y", "1.2.3.4", "1.2.3.4:80", "-a.test", "a-.test", "a..b", repeat('a', 64) + ".test", n254,
				"tea-b.test.:443", "\u212atea.test")) {
			assertTrue(TeaHostAssignmentService.normalizeHost(bad).isEmpty(), "refused: " + bad);
		}
		assertTrue(TeaHostAssignmentService.normalizeHost(null).isEmpty());
	}

	@Test
	void theAllowListParsesOnceAndFailsFastOnABadEntry() {
		props.setTeaCustomDomains("tea-a.test,TEA-B.test,,tea-a.test,rearm.localhost");
		TeaHostAssignmentService s = service();
		assertEquals(Set.of("tea-a.test", "tea-b.test"), s.customHosts());
		assertEquals(Optional.of("rearm.localhost"), s.baseuriHost());
		assertFalse(s.isCustomHost("rearm.localhost"), "the baseuri host is never a custom host");
		assertTrue(s.isBaseuriHost("REARM.localhost:8443"));
		assertTrue(s.isCustomHost("TEA-A.test"));
		assertFalse(s.isCustomHost("tea-x.test"));
		assertFalse(s.isCustomHost(""));
		for (String entry : List.of("bad/host", "tea}", "a\"b")) {
			props.setTeaCustomDomains("tea-a.test," + entry);
			IllegalStateException e = assertThrows(IllegalStateException.class, this::service, entry);
			assertEquals("relizaprops.teaCustomDomains entry is not a bare hostname: " + entry, e.getMessage());
		}
		props.setTeaCustomDomains(null);
		assertTrue(service().customHosts().isEmpty(), "absent: no custom domain");
		props.setBaseuri("http://10.0.0.5:3000");
		assertTrue(service().baseuriHost().isEmpty(), "an IP baseuri is not assignable");
	}

	// ------------------------------------------------------------------ row 10

	@Test
	void assignRefusesOffListDuplicateSecondHostAndArchived() throws Exception {
		TeaHostAssignmentService s = service();
		assertEquals("host must be a bare lowercase DNS name",
				assertThrows(RelizaException.class, () -> s.assign("tea}", a, null)).getMessage());
		assertEquals("Host tea-x.test is not in the TEA custom domain allow-list (relizaprops.teaCustomDomains) and is not the instance host",
				assertThrows(RelizaException.class, () -> s.assign("tea-x.test", a, null)).getMessage());
		TeaHostAssignment row = s.assign("tea-a.test", a, null);
		assertEquals(new TeaHostAssignment("tea-a.test", a, "Org A", false, true, TeaServing.NONE), row);
		assertEquals(Map.of("tea-a.test", a.toString()), stored);
		assertEquals("Host tea-a.test is already assigned to organization Org A",
				assertThrows(RelizaException.class, () -> s.assign("tea-a.test", b, null)).getMessage());
		assertEquals("Organization Org A already holds host tea-a.test; unassign it first",
				assertThrows(RelizaException.class, () -> s.assign("tea-b.test", a, null)).getMessage());
		// idempotent: the same pair answers the row and writes nothing
		assertEquals(row, s.assign("TEA-A.test", a, null));
		verify(systemInfoService, times(1)).assignTeaHost(anyString(), any(UUID.class));

		// a stale unlisted entry still counts as the organization's host (the raw map decides)
		stored.clear();
		stored.put("tea-old.test", a.toString());
		assertEquals("Organization Org A already holds host tea-old.test; unassign it first",
				assertThrows(RelizaException.class, () -> s.assign("tea-b.test", a, null)).getMessage());

		UUID archived = UUID.randomUUID();
		orgs.put(archived, org(archived, "Gone", StatusEnum.ARCHIVED, null));
		assertEquals("Organization not found", assertThrows(RelizaException.class, () -> s.assign("tea-c.test", archived, null)).getMessage());
		assertEquals("Organization not found", assertThrows(RelizaException.class, () -> s.assign("tea-c.test", UUID.randomUUID(), null)).getMessage());
		// the baseuri host is assignable without being listed
		assertEquals(new TeaHostAssignment("rearm.localhost", b, "Org B", true, true, TeaServing.NONE), s.assign("rearm.localhost", b, null));
	}

	@Test
	void unassignRefusesALiveDomainHolderAndClearsAnArchivedOrMissingOne() throws Exception {
		TeaHostAssignmentService s = service();
		assertEquals("Host tea-a.test is not assigned", assertThrows(RelizaException.class, () -> s.unassign("tea-a.test", null)).getMessage());
		s.assign("tea-a.test", a, null);
		orgs.get(a).setTeaServing(TeaServing.DOMAIN);
		assertEquals("Organization Org A serves TEA on tea-a.test; set its TEA serving to NONE or LEGACY_UUID first",
				assertThrows(RelizaException.class, () -> s.unassign("tea-a.test", null)).getMessage());
		assertTrue(stored.containsKey("tea-a.test"));
		orgs.get(a).setTeaServing(TeaServing.NONE);
		assertTrue(s.unassign("Tea-A.test", null));
		assertTrue(stored.isEmpty());

		// an archived holder whose stored mode is DOMAIN does not hold the host
		s.assign("tea-a.test", a, null);
		orgs.get(a).setTeaServing(TeaServing.DOMAIN);
		orgs.get(a).setStatus(StatusEnum.ARCHIVED);
		assertTrue(s.unassign("tea-a.test", null));
		assertEquals(new TeaHostAssignment("tea-a.test", b, "Org B", false, true, TeaServing.NONE), s.assign("tea-a.test", b, null));

		// a holder row that is gone
		UUID gone = UUID.randomUUID();
		stored.put("tea-c.test", gone.toString());
		assertTrue(s.unassign("tea-c.test", null));
		assertFalse(stored.containsKey("tea-c.test"));
		verify(organizationRepository, times(4)).currentStatusForUpdate(any(UUID.class));
	}

	@Test
	void orgOfHostAndListReadAssignableHostsOnly() throws Exception {
		TeaHostAssignmentService s = service();
		stored.put("tea-b.test", b.toString());
		stored.put("tea-old.test", a.toString());
		stored.put("tea-c.test", "not-a-uuid");
		assertEquals(Optional.of(b), s.orgOfHost("TEA-B.test:443"));
		assertTrue(s.orgOfHost("tea-old.test").isEmpty(), "a mapped but unlisted host is no mapping");
		assertTrue(s.orgOfHost("tea-c.test").isEmpty(), "a corrupt value reads as absent");
		assertTrue(s.orgOfHost("tea-a.test").isEmpty(), "listed, unassigned");
		assertEquals(Optional.of("tea-b.test"), s.hostOfOrg(b));
		assertTrue(s.hostOfOrg(a).isEmpty(), "the unlisted entry is not the host A is served on");

		List<TeaHostAssignment> list = s.list();
		assertEquals(List.of("tea-a.test", "tea-b.test", "tea-c.test", "rearm.localhost", "tea-old.test"),
				list.stream().map(TeaHostAssignment::host).toList(), "custom hosts sorted, the baseuri host, then stale entries");
		assertEquals(new TeaHostAssignment("tea-a.test", null, null, false, true, null), list.get(0));
		assertEquals(new TeaHostAssignment("tea-b.test", b, "Org B", false, true, TeaServing.NONE), list.get(1));
		assertNull(list.get(2).org(), "a corrupt value lists as unassigned");
		assertTrue(list.get(3).isBaseuriHost());
		assertEquals(new TeaHostAssignment("tea-old.test", a, "Org A", false, false, TeaServing.NONE), list.get(4));
	}

	@Test
	void onAnEmptySystemInfoTableAssignCreatesTheRowThenLocksOnce() throws Exception {
		TeaHostAssignmentService s = service();
		s.assign("tea-a.test", a, null);
		InOrder order = inOrder(systemInfoService, systemInfoRepository);
		order.verify(systemInfoService).getTeaHostAssignments();
		order.verify(systemInfoRepository).currentTeaHostAssignmentsForUpdate();
		order.verify(systemInfoService).assignTeaHost("tea-a.test", a);
		verify(systemInfoRepository, times(1)).currentTeaHostAssignmentsForUpdate();
		verify(systemInfoService, never()).unassignTeaHost(anyString());
	}
}
