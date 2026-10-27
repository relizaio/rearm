/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.repositories.ComponentRepository;

/**
 * SCORE-71 T-12 (ADR-9): a PRODUCT component's supplier name change invalidates the stored merged rows of its
 * releases and of the products above them; a COMPONENT component's, an unchanged supplier, a licence change and
 * a refused supplier do not. The rig is {@link ComponentServiceDeclaredLicenseTest}'s, with the type a parameter.
 */
class ComponentServiceSupplierInvalidationTest {

	private static final UUID ORG = UUID.randomUUID();

	private ComponentService service;
	private ComponentRepository repository;
	private GetComponentService getComponentService;
	private ReleaseService releaseService;
	/** What was saved last, or null. */
	private ComponentData saved;

	@BeforeEach
	void wire() throws Exception {
		repository = mock(ComponentRepository.class);
		var ctor = ComponentService.class.getDeclaredConstructor(ComponentRepository.class, String.class);
		ctor.setAccessible(true);
		service = ctor.newInstance(repository, "http://test/");
		getComponentService = mock(GetComponentService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.empty());
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(service, "branchService", mock(BranchService.class));
		ReflectionTestUtils.setField(service, "vcsRepositoryService", mock(VcsRepositoryService.class));
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
		releaseService = mock(ReleaseService.class);
		ReflectionTestUtils.setField(service, "releaseService", releaseService);
		saved = null;
		when(repository.save(any(Component.class))).thenAnswer(inv -> {
			Component c = inv.getArgument(0);
			saved = ComponentData.dataFromRecord(c);
			return c;
		});
	}

	/** A stored component of {@code type} with the given supplier name. */
	private UUID stored(ComponentType type, String supplierName) {
		ComponentData cd = new ComponentData();
		cd.setName("component");
		cd.setOrg(ORG);
		cd.setType(type);
		cd.setSupplierName(supplierName);
		Component c = new Component();
		cd.setUuid(c.getUuid());
		c.setRecordData(Utils.dataToRecord(cd));
		when(getComponentService.getComponent(c.getUuid())).thenReturn(Optional.of(c));
		when(repository.findByIdWriteLocked(c.getUuid())).thenReturn(Optional.of(c));
		return c.getUuid();
	}

	private void update(UUID uuid, String expression, String supplierName) throws RelizaException {
		service.updateComponent(ComponentDto.builder().uuid(uuid).licenseExpression(expression)
				.supplierName(supplierName).build(), WhoUpdated.getAutoWhoUpdated());
	}

	@Test
	void aProductComponentsFirstSupplierInvalidates() throws Exception {
		UUID uuid = stored(ComponentType.PRODUCT, null);

		update(uuid, null, "Acme");

		verify(releaseService, times(1)).invalidateMergedSbomRowsOfComponent(
				argThat(cd -> uuid.equals(cd.getUuid()) && "Acme".equals(cd.getSupplierName())));
	}

	@Test
	void aProductComponentsChangedSupplierInvalidates() throws Exception {
		UUID uuid = stored(ComponentType.PRODUCT, "Acme");

		update(uuid, null, "Acme Boards");

		verify(releaseService, times(1)).invalidateMergedSbomRowsOfComponent(
				argThat(cd -> uuid.equals(cd.getUuid()) && "Acme Boards".equals(cd.getSupplierName())));
	}

	@Test
	void aComponentComponentsSupplierChangeDoesNotInvalidate() throws Exception {
		UUID uuid = stored(ComponentType.COMPONENT, null);

		update(uuid, null, "Acme");

		verifyNoInteractions(releaseService);
	}

	@Test
	void theSameSupplierOrNoneSentDoesNotInvalidate() throws Exception {
		UUID same = stored(ComponentType.PRODUCT, "Acme");
		update(same, null, "Acme");
		UUID untouched = stored(ComponentType.PRODUCT, "Acme");
		update(untouched, null, null);

		verifyNoInteractions(releaseService);
	}

	@Test
	void aLicenceChangeWithTheSupplierUntouchedDoesNotInvalidate() throws Exception {
		UUID uuid = stored(ComponentType.PRODUCT, "Acme");

		update(uuid, "MIT", null);

		verifyNoInteractions(releaseService);
	}

	@Test
	void aRefusedSupplierDoesNotInvalidateAndSavesNothing() {
		UUID uuid = stored(ComponentType.PRODUCT, "Acme");

		assertThrows(RelizaException.class, () -> update(uuid, null, "a".repeat(256)));

		verifyNoInteractions(releaseService);
		assertNull(saved);
	}
}
