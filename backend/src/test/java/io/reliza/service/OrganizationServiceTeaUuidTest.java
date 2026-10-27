/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.saas.SaasOrganizationService;
import jakarta.persistence.EntityManager;

/**
 * Task TEA-2, design 4.5 case 31, amended by TEA-5 round 4 (T-10): the organization's TEA id is
 * minted once, under the lock, by one conditional statement (case 90); both organization
 * funnels, {@code OrganizationService.saveOrganization} and {@code SaasOrganizationService}'s
 * private copy, carry the stored id forward under the row lock (case 91).
 */
class OrganizationServiceTeaUuidTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private OrganizationRepository repository;
	private GetOrganizationService getOrganizationService;
	private AuditService auditService;
	private EntityManager entityManager;
	private OrganizationService service;
	private Organization org;

	@BeforeEach
	void setUp() {
		repository = mock(OrganizationRepository.class);
		getOrganizationService = mock(GetOrganizationService.class);
		auditService = mock(AuditService.class);
		entityManager = mock(EntityManager.class);
		service = new OrganizationService(repository);
		ReflectionTestUtils.setField(service, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(service, "auditService", auditService);
		ReflectionTestUtils.setField(service, "entityManager", entityManager);

		org = new Organization();
		OrganizationData od = new OrganizationData();
		od.setName("tea-org");
		org.setRecordData(Utils.dataToRecord(od));
		when(repository.findById(org.getUuid())).thenReturn(Optional.of(org));
		when(getOrganizationService.getOrganization(org.getUuid())).thenReturn(Optional.of(org));
		when(getOrganizationService.getOrganizationData(org.getUuid()))
				.thenAnswer(inv -> Optional.of(OrganizationData.orgDataFromDbRecord(org)));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(repository.currentTeaUuidForUpdate(any())).thenReturn(Optional.empty());
	}

	private void setStoredTeaUuid(UUID teaUuid) {
		OrganizationData od = OrganizationData.orgDataFromDbRecord(org);
		od.setTeaUuid(teaUuid);
		org.setRecordData(Utils.dataToRecord(od));
	}

	// ---------------------------------------------------------------- 90

	@Test
	void anOrgWithoutAnIdIsMintedByOneStatementAndNeverSaved() throws Exception {
		UUID[] stored = new UUID[1];
		when(repository.mintTeaUuid(eq(org.getUuid()), anyString())).thenAnswer(inv -> {
			stored[0] = UUID.fromString(inv.getArgument(1));
			return 1;
		});
		doAnswer(inv -> {
			if (null != stored[0]) setStoredTeaUuid(stored[0]);
			return null;
		}).when(entityManager).refresh(org);
		int revision = org.getRevision();

		UUID minted = service.ensureTeaUuid(org.getUuid(), WU);

		assertNotNull(minted);
		assertEquals(stored[0], minted);
		InOrder order = inOrder(repository, entityManager);
		order.verify(repository).lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), org.getUuid().toString());
		order.verify(repository).findById(org.getUuid());
		order.verify(entityManager).refresh(org);
		order.verify(repository).mintTeaUuid(org.getUuid(), minted.toString());
		order.verify(entityManager).refresh(org);
		verify(repository, never()).save(any());
		verify(auditService, never()).createAndSaveAuditRecord(any(TableName.class), any());
		assertEquals(revision, org.getRevision());
		assertEquals(minted, OrganizationData.orgDataFromDbRecord(org).getTeaUuid());
	}

	@Test
	void anOrgWithAnIdKeepsItAndNothingIsWritten() throws Exception {
		UUID existing = UUID.randomUUID();
		setStoredTeaUuid(existing);

		assertEquals(existing, service.ensureTeaUuid(org.getUuid(), WU));
		verify(repository, never()).mintTeaUuid(any(), any());
		verify(repository, never()).save(any());
	}

	@Test
	void aStatementThatWritesNothingAnswersTheRowsIdOrNotFound() throws Exception {
		when(repository.mintTeaUuid(eq(org.getUuid()), anyString())).thenReturn(0);
		RelizaException e = assertThrows(RelizaException.class, () -> service.ensureTeaUuid(org.getUuid(), WU));
		assertTrue(e.getMessage().contains("not found"), e.getMessage());

		UUID other = UUID.randomUUID();
		doAnswer(inv -> null).doAnswer(inv -> {
			setStoredTeaUuid(other);
			return null;
		}).when(entityManager).refresh(org);
		assertEquals(other, service.ensureTeaUuid(org.getUuid(), WU));
		verify(repository, never()).save(any());
	}

	@Test
	void aConstraintErrorOfTheStatementIsARelizaExceptionNamingTheKindTheUuidAndTheCause() {
		when(repository.mintTeaUuid(eq(org.getUuid()), anyString()))
				.thenThrow(new DataIntegrityViolationException("duplicate key value violates organizations_tea_uuid"));

		RelizaException e = assertThrows(RelizaException.class, () -> service.ensureTeaUuid(org.getUuid(), WU));

		assertTrue(e.getMessage().startsWith("the TEA id of organization " + org.getUuid() + " could not be assigned: ")
				&& e.getMessage().contains("organizations_tea_uuid"), e.getMessage());
	}

	// ---------------------------------------------------------------- 91

	private OrganizationData savedRecord(OrganizationRepository repo) {
		ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
		verify(repo).save(saved.capture());
		return OrganizationData.orgDataFromDbRecord(saved.getValue());
	}

	@Test
	void updateSettingsWritesTheStoredIdBackUnderTheLockBeforeTheAudit() throws Exception {
		UUID x = UUID.randomUUID();
		when(repository.currentTeaUuidForUpdate(org.getUuid())).thenReturn(Optional.of(x.toString()));

		service.updateSettings(org.getUuid(), new OrganizationData.Settings(), WU);

		assertEquals(x, savedRecord(repository).getTeaUuid());
		InOrder order = inOrder(repository, auditService);
		order.verify(repository).currentTeaUuidForUpdate(org.getUuid());
		order.verify(auditService).createAndSaveAuditRecord(TableName.ORGANIZATIONS, org);
		order.verify(repository).save(any());
	}

	@Test
	void updateSettingsCarryingAnIdTheRowDoesNotHaveWritesNone() throws Exception {
		setStoredTeaUuid(UUID.randomUUID());

		service.updateSettings(org.getUuid(), new OrganizationData.Settings(), WU);

		assertNull(savedRecord(repository).getTeaUuid());
		assertFalse(org.getRecordData().containsKey("teaUuid"));
	}

	/** The SaaS service over the mocked repository; its constructor is package-private. */
	private SaasOrganizationService saasService() throws Exception {
		var ctor = SaasOrganizationService.class.getDeclaredConstructor(OrganizationRepository.class);
		ctor.setAccessible(true);
		return ctor.newInstance(repository);
	}

	@Test
	void theSaasFunnelCarriesTheStoredIdForwardToo() throws Exception {
		SaasOrganizationService saas = saasService();
		ReflectionTestUtils.setField(saas, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(saas, "auditService", auditService);
		ReflectionTestUtils.setField(saas, "organizationService", mock(OrganizationService.class));
		// main's archiveOrganization reads the row under its write lock and refreshes it (TPF-48)
		ReflectionTestUtils.setField(saas, "entityManager", mock(jakarta.persistence.EntityManager.class));
		when(repository.findByIdWriteLocked(org.getUuid())).thenReturn(Optional.of(org));
		UUID x = UUID.randomUUID();
		when(repository.currentTeaUuidForUpdate(org.getUuid())).thenReturn(Optional.of(x.toString()));

		saas.archiveOrganization(org.getUuid(), WU);

		assertEquals(x, savedRecord(repository).getTeaUuid());
		InOrder order = inOrder(repository, auditService);
		order.verify(repository).currentTeaUuidForUpdate(org.getUuid());
		order.verify(auditService).createAndSaveAuditRecord(TableName.ORGANIZATIONS, org);
		order.verify(repository).save(any());
	}

	@Test
	void aNewOrganizationTakesNoLockedRead() throws Exception {
		SaasOrganizationService saas = saasService();
		ReflectionTestUtils.setField(saas, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(saas, "auditService", auditService);
		Organization fresh = new Organization();
		when(getOrganizationService.getOrganization(fresh.getUuid())).thenReturn(Optional.empty());

		ReflectionTestUtils.invokeMethod(saas, "saveOrganization", fresh,
				new java.util.HashMap<>(java.util.Map.of("name", "fresh")), WU);

		verify(repository, never()).currentTeaUuidForUpdate(any());
	}

	@Test
	void theSaasWritersThatReachTheFunnelRunInOneTransaction() throws Exception {
		for (String name : new String[] {"archiveOrganization", "addApprovalRole", "deleteApprovalRole"}) {
			boolean annotated = false;
			for (Method m : SaasOrganizationService.class.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.isAnnotationPresent(Transactional.class)) annotated = true;
			}
			assertTrue(annotated, name + " must be @Transactional");
		}
	}
}
