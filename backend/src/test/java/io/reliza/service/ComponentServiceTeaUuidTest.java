/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.repositories.ComponentRepository;
import jakarta.persistence.EntityManager;

/**
 * Task TEA-2, design 4.5 case 32, amended by TEA-5 round 4 (T-10): a component's TEA id is minted
 * once, under the lock, by one conditional statement that never goes through the funnel (case
 * 90); the funnel carries the stored id forward under the row lock, so it neither erases a mint
 * nor introduces an id (case 91); the writers that reach the funnel run in one transaction.
 */
class ComponentServiceTeaUuidTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private ComponentRepository repository;
	private GetComponentService getComponentService;
	private AuditService auditService;
	private EntityManager entityManager;
	private ComponentService service;
	private Component component;

	@BeforeEach
	void setUp() {
		repository = mock(ComponentRepository.class);
		getComponentService = mock(GetComponentService.class);
		auditService = mock(AuditService.class);
		entityManager = mock(EntityManager.class);
		service = new ComponentService(repository, "https://rearm.example.com");
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "auditService", auditService);
		ReflectionTestUtils.setField(service, "entityManager", entityManager);
		// no organization to resolve a sid policy against: updateComponent's collision check returns early
		ReflectionTestUtils.setField(service, "getOrganizationService", mock(GetOrganizationService.class));

		component = new Component();
		ComponentData cd = new ComponentData();
		cd.setName("tea-component");
		cd.setOrg(UUID.randomUUID());
		cd.setType(ComponentType.COMPONENT);
		component.setRecordData(Utils.dataToRecord(cd));
		when(repository.findById(component.getUuid())).thenReturn(Optional.of(component));
		// main's updateComponent reads the row through lockedComponentRow
		when(repository.findByIdWriteLocked(component.getUuid())).thenReturn(Optional.of(component));
		when(getComponentService.getComponent(component.getUuid())).thenReturn(Optional.of(component));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		// TEA-7 round 5: the funnel's locked read is the whole row; by default the row is the managed record
		when(repository.lockRowForUpdate(any())).thenAnswer(inv -> Optional.of(rowJson(null)));
	}

	/** The row as the locked read answers it: the managed record, with the row's TEA id. */
	private String rowJson(UUID rowTeaUuid) {
		java.util.Map<String, Object> row = new java.util.LinkedHashMap<>(component.getRecordData());
		row.remove("teaUuid");
		if (null != rowTeaUuid) row.put("teaUuid", rowTeaUuid.toString());
		return Utils.OM.writeValueAsString(row);
	}

	private void setStoredTeaUuid(UUID teaUuid) {
		ComponentData cd = ComponentData.dataFromRecord(component);
		cd.setTeaUuid(teaUuid);
		component.setRecordData(Utils.dataToRecord(cd));
	}

	/** The statement's effect on the row, which the refresh after it reads back. */
	private void refreshReads(UUID[] stored) {
		doAnswer(inv -> {
			if (null != stored[0]) setStoredTeaUuid(stored[0]);
			return null;
		}).when(entityManager).refresh(component);
	}

	// ---------------------------------------------------------------- 90

	@Test
	void aComponentWithoutAnIdIsMintedByOneStatementAndNeverSaved() throws Exception {
		UUID[] stored = new UUID[1];
		when(repository.mintTeaUuid(eq(component.getUuid()), anyString())).thenAnswer(inv -> {
			stored[0] = UUID.fromString(inv.getArgument(1));
			return 1;
		});
		refreshReads(stored);
		int revision = component.getRevision();

		UUID minted = service.ensureTeaUuid(component.getUuid(), WU);

		assertNotNull(minted);
		assertEquals(stored[0], minted, "the id returned is the one the statement wrote");
		InOrder order = inOrder(repository, entityManager);
		order.verify(repository).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), component.getUuid().toString());
		order.verify(repository).findById(component.getUuid());
		order.verify(entityManager).refresh(component);
		order.verify(repository).mintTeaUuid(component.getUuid(), minted.toString());
		order.verify(entityManager).refresh(component);
		verify(repository, never()).save(any());
		verify(repository, never()).lockRowForUpdate(any());
		verify(auditService, never()).createAndSaveAuditRecord(any(TableName.class), any());
		assertEquals(revision, component.getRevision(), "a mint bumps no revision");
		assertEquals(minted, ComponentData.dataFromRecord(component).getTeaUuid(), "the managed instance reads the id");
	}

	@Test
	void aComponentWithAnIdKeepsItAndNothingIsWritten() throws Exception {
		UUID existing = UUID.randomUUID();
		setStoredTeaUuid(existing);

		assertEquals(existing, service.ensureTeaUuid(component.getUuid(), WU));

		verify(repository, never()).mintTeaUuid(any(), any());
		verify(repository, never()).save(any());
		InOrder order = inOrder(repository, entityManager);
		order.verify(repository).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), component.getUuid().toString());
		order.verify(repository).findById(component.getUuid());
		order.verify(entityManager).refresh(component);
	}

	@Test
	void aStatementThatWritesNothingAnswersTheIdTheRowHas() throws Exception {
		UUID other = UUID.randomUUID();
		when(repository.mintTeaUuid(eq(component.getUuid()), anyString())).thenReturn(0);
		// the first refresh (before the check) reads no id, the second (after the statement) one
		doAnswer(inv -> null).doAnswer(inv -> {
			setStoredTeaUuid(other);
			return null;
		}).when(entityManager).refresh(component);

		assertEquals(other, service.ensureTeaUuid(component.getUuid(), WU));
		verify(repository, never()).save(any());
	}

	@Test
	void aStatementThatWritesNothingOnARowWithoutAnIdIsNotFound() {
		when(repository.mintTeaUuid(eq(component.getUuid()), anyString())).thenReturn(0);

		RelizaException e = assertThrows(RelizaException.class, () -> service.ensureTeaUuid(component.getUuid(), WU));

		assertTrue(e.getMessage().contains("not found") && e.getMessage().contains(component.getUuid().toString()),
				e.getMessage());
	}

	@Test
	void aConstraintErrorOfTheStatementIsARelizaExceptionNamingTheKindTheUuidAndTheCause() {
		when(repository.mintTeaUuid(eq(component.getUuid()), anyString()))
				.thenThrow(new DataIntegrityViolationException("duplicate key value violates components_tea_uuid"));

		RelizaException e = assertThrows(RelizaException.class, () -> service.ensureTeaUuid(component.getUuid(), WU));

		assertTrue(e.getMessage().startsWith("the TEA id of component " + component.getUuid() + " could not be assigned: ")
				&& e.getMessage().contains("components_tea_uuid"), e.getMessage());
		verify(repository, never()).save(any());
	}

	@Test
	void aMissingComponentIsRefusedAfterTheLock() {
		UUID missing = UUID.randomUUID();
		when(repository.findById(missing)).thenReturn(Optional.empty());

		assertThrows(RelizaException.class, () -> service.ensureTeaUuid(missing, WU));

		verify(repository).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), missing.toString());
		verify(repository, never()).mintTeaUuid(any(), any());
	}

	// ---------------------------------------------------------------- 91

	private ComponentData savedRecord() {
		ArgumentCaptor<Component> saved = ArgumentCaptor.forClass(Component.class);
		verify(repository).save(saved.capture());
		return ComponentData.dataFromRecord(saved.getValue());
	}

	@Test
	void anUpdateOfACopyWithoutTheIdWritesTheStoredIdBack() throws Exception {
		UUID x = UUID.randomUUID();
		when(repository.lockRowForUpdate(component.getUuid())).thenAnswer(inv -> Optional.of(rowJson(x)));

		service.updateComponent(ComponentDto.builder().uuid(component.getUuid()).name("renamed").build(), WU);

		ComponentData saved = savedRecord();
		assertEquals(x, saved.getTeaUuid(), "a mint that committed after the caller's read is kept");
		assertEquals("renamed", saved.getName());
		InOrder order = inOrder(repository, auditService);
		// the funnel locks through findByIdWriteLocked (TEA-7 round 8, F-3); the carry's native read is the one lockRowForUpdate
		order.verify(repository).findByIdWriteLocked(component.getUuid());
		order.verify(repository, times(1)).lockRowForUpdate(component.getUuid());
		order.verify(auditService).createAndSaveAuditRecord(TableName.COMPONENTS, component);
		order.verify(repository).save(any());
	}

	@Test
	void anUpdateCarryingAnIdTheRowDoesNotHaveWritesNone() throws Exception {
		setStoredTeaUuid(UUID.randomUUID()); // the caller's copy carries a forged id

		service.updateComponent(ComponentDto.builder().uuid(component.getUuid()).name("renamed").build(), WU);

		ComponentData saved = savedRecord();
		assertEquals(null, saved.getTeaUuid());
		assertFalse(component.getRecordData().containsKey("teaUuid"));
	}

	@Test
	void aNewComponentTakesNoLockedRead() throws Exception {
		Component fresh = new Component();
		ComponentData cd = ComponentData.dataFromRecord(component);
		when(getComponentService.getComponent(fresh.getUuid())).thenReturn(Optional.empty());
		ReflectionTestUtils.invokeMethod(service, "saveComponent", fresh, Utils.dataToRecord(cd), WU);
		verify(repository, never()).lockRowForUpdate(any());
		verify(repository, times(1)).save(fresh);
	}

	@Test
	void theWritersThatReachTheFunnelRunInOneTransaction() throws Exception {
		for (String name : new String[] {"archiveComponent", "updateComponentVcsRepo", "updateComponent", "setPerspectives",
				"setComponentVisibility", "setComponentVersion", "setLocks", "updateComponentResourceGroup",
				"stampDeclarativeProvenance", "ensureTeaUuid"}) {
			boolean annotated = false;
			for (Method m : ComponentService.class.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.isAnnotationPresent(Transactional.class)) annotated = true;
			}
			assertTrue(annotated, name + " must be @Transactional");
		}
		Method archive = ArtifactService.class.getMethod("archiveArtifact", UUID.class, WhoUpdated.class);
		assertTrue(archive.isAnnotationPresent(Transactional.class), "archiveArtifact must be @Transactional");
	}
}
