/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.Utils;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import jakarta.persistence.EntityManager;

/** Task TEA-2, design 4.5 case 31: the organization's TEA id is minted once, under the lock. */
class OrganizationServiceTeaUuidTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private OrganizationRepository repository;
	private GetOrganizationService getOrganizationService;
	private OrganizationService service;
	private Organization org;

	@BeforeEach
	void setUp() {
		repository = mock(OrganizationRepository.class);
		getOrganizationService = mock(GetOrganizationService.class);
		service = new OrganizationService(repository);
		ReflectionTestUtils.setField(service, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));

		org = new Organization();
		OrganizationData od = new OrganizationData();
		od.setName("tea-org");
		org.setRecordData(Utils.dataToRecord(od));
		when(repository.findById(org.getUuid())).thenReturn(Optional.of(org));
		when(getOrganizationService.getOrganization(org.getUuid())).thenReturn(Optional.of(org));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void anOrgWithoutAnIdGetsOneSavedOnce() throws Exception {
		UUID minted = service.ensureTeaUuid(org.getUuid(), WU);
		assertNotNull(minted);
		ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
		verify(repository, times(1)).save(saved.capture());
		assertEquals(minted, OrganizationData.orgDataFromDbRecord(saved.getValue()).getTeaUuid());

		InOrder order = inOrder(repository);
		order.verify(repository).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), org.getUuid().toString());
		order.verify(repository).findById(org.getUuid());
		order.verify(repository).save(any());
	}

	@Test
	void anOrgWithAnIdKeepsItAndNothingIsSaved() throws Exception {
		UUID existing = UUID.randomUUID();
		OrganizationData od = OrganizationData.orgDataFromDbRecord(org);
		od.setTeaUuid(existing);
		org.setRecordData(Utils.dataToRecord(od));

		assertEquals(existing, service.ensureTeaUuid(org.getUuid(), WU));
		verify(repository, never()).save(any());
		InOrder order = inOrder(repository);
		order.verify(repository).lockTeaIdMint(anyInt(), anyString());
		order.verify(repository).findById(org.getUuid());
	}
}
