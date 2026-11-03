/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.repositories.SystemInfoRepository;
import io.reliza.ws.RelizaConfigProps;

/**
 * TPF-142 mutation round: what TeaHostAssignmentServiceTest leaves open in the host normalisation, the instance-host
 * test, the organization-to-host lookup and the refusals of assign and unassign, on the same Mockito collaborators.
 */
class TeaHostAssignmentServiceEdgesTest {

	private RelizaConfigProps props;
	private SystemInfoRepository systemInfoRepository;
	private final UUID a = UUID.randomUUID();

	@BeforeEach
	void wire() {
		props = new RelizaConfigProps();
		props.setBaseuri("https://rearm.localhost");
		props.setTeaCustomDomains("tea-a.test,tea-b.test,tea-c.test");
		systemInfoRepository = mock(SystemInfoRepository.class);
	}

	private TeaHostAssignmentService service() {
		TeaHostAssignmentService s = new TeaHostAssignmentService(props, mock(SystemInfoService.class), systemInfoRepository,
				mock(OrganizationRepository.class), mock(GetOrganizationService.class));
		s.init();
		return s;
	}

	@Test
	void aLabelWithAnUnderscoreIsNotAHostname() {
		assertEquals(Optional.empty(), TeaHostAssignmentService.normalizeHost("a_b.test"));
		assertEquals(Optional.empty(), TeaHostAssignmentService.normalizeHost("tea_a.test"));
		assertEquals(Optional.of("a-b.test"), TeaHostAssignmentService.normalizeHost("a-b.test"));
	}

	@Test
	void aColonWithNoPortIsNotAHostname() {
		assertEquals(Optional.empty(), TeaHostAssignmentService.normalizeHost("tea-a.test:"));
		assertEquals(Optional.of("tea-a.test"), TeaHostAssignmentService.normalizeHost("tea-a.test:8443"));
	}

	@Test
	void withoutADnsNameBaseuriNoInvalidValueIsTheInstanceHost() {
		props.setBaseuri("http://127.0.0.1:8080");
		TeaHostAssignmentService s = service();
		assertEquals(Optional.empty(), s.baseuriHost());
		assertFalse(s.isBaseuriHost("not a host!"));
		assertFalse(s.isBaseuriHost(null));
		assertFalse(s.isAssignable("not a host!"));
		assertTrue(s.isAssignable("tea-a.test"));
	}

	@Test
	void anAssignmentValueMatchesItsOrganizationWhateverTheCaseOfTheUuid() {
		assertEquals(Optional.of("tea-a.test"), service().hostOfOrg(Map.of("tea-a.test", a.toString().toUpperCase()), a));
	}

	@Test
	void anOrganizationMappedToTwoHostsReadsTheFirstInSortedOrder() {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("tea-c.test", a.toString());
		map.put("tea-a.test", a.toString());
		assertEquals(Optional.of("tea-a.test"), service().hostOfOrg(map, a));
	}

	@Test
	void noOrganizationHoldsNoHost() {
		assertEquals(Optional.empty(), service().hostOfOrg(Map.of("tea-a.test", a.toString()), null));
	}

	@Test
	void unassigningAValueThatIsNoHostnameSaysItIsNotAssigned() {
		RelizaException e = assertThrows(RelizaException.class, () -> service().unassign("not a host!", null));
		assertEquals("Host not a host! is not assigned", e.getMessage());
	}

	@Test
	void assigningNoOrganizationIsRefusedBeforeTheRowIsLocked() {
		RelizaException e = assertThrows(RelizaException.class, () -> service().assign("tea-a.test", null, null));
		assertEquals(TeaHostAssignmentService.ORGANIZATION_NOT_FOUND, e.getMessage());
		verify(systemInfoRepository, never()).currentTeaHostAssignmentsForUpdate();
	}
}
