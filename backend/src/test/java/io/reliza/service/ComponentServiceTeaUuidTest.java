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
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ComponentRepository;
import jakarta.persistence.EntityManager;

/** Task TEA-2, design 4.5 case 32: a component's TEA id is minted once, under the lock. */
class ComponentServiceTeaUuidTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private ComponentRepository repository;
	private GetComponentService getComponentService;
	private ComponentService service;
	private Component component;

	@BeforeEach
	void setUp() {
		repository = mock(ComponentRepository.class);
		getComponentService = mock(GetComponentService.class);
		service = new ComponentService(repository, "https://rearm.example.com");
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));

		component = new Component();
		ComponentData cd = new ComponentData();
		cd.setName("tea-component");
		cd.setOrg(UUID.randomUUID());
		cd.setType(ComponentType.COMPONENT);
		component.setRecordData(Utils.dataToRecord(cd));
		when(repository.findById(component.getUuid())).thenReturn(Optional.of(component));
		when(getComponentService.getComponent(component.getUuid())).thenReturn(Optional.of(component));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void aComponentWithoutAnIdGetsOneSavedOnce() throws Exception {
		UUID minted = service.ensureTeaUuid(component.getUuid(), WU);
		assertNotNull(minted);
		ArgumentCaptor<Component> saved = ArgumentCaptor.forClass(Component.class);
		verify(repository, times(1)).save(saved.capture());
		assertEquals(minted, ComponentData.dataFromRecord(saved.getValue()).getTeaUuid());

		InOrder order = inOrder(repository);
		order.verify(repository).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), component.getUuid().toString());
		order.verify(repository).findById(component.getUuid());
		order.verify(repository).save(any());
	}

	@Test
	void aComponentWithAnIdKeepsItAndNothingIsSaved() throws Exception {
		UUID existing = UUID.randomUUID();
		ComponentData cd = ComponentData.dataFromRecord(component);
		cd.setTeaUuid(existing);
		component.setRecordData(Utils.dataToRecord(cd));

		assertEquals(existing, service.ensureTeaUuid(component.getUuid(), WU));
		verify(repository, never()).save(any());
		InOrder order = inOrder(repository);
		order.verify(repository).lockTeaIdMint(anyInt(), anyString());
		order.verify(repository).findById(component.getUuid());
	}
}
