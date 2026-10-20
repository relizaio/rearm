/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

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
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.CreateVcsRepositoryDto;
import io.reliza.repositories.ComponentRepository;

/**
 * SCORE-39 T-15 (ADR-1, ADR-2, ADR-3): the component's declared licence, its URL and its supplier
 * name on create and on update. PATCH on update (null leaves alone, "" clears, clearing the
 * expression clears the URL); every refusal names its field and comes before anything is written.
 * The supplier name is refused, not escaped, when it carries markup or control characters.
 */
class ComponentServiceDeclaredLicenseTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final String ACME_URL = "https://acme.example/licence";

	private ComponentService service;
	private ComponentRepository repository;
	private GetComponentService getComponentService;
	private BranchService branchService;
	private VcsRepositoryService vcsRepositoryService;
	/** What was saved last, or null. */
	private ComponentData saved;

	@BeforeEach
	void wire() throws Exception {
		repository = mock(ComponentRepository.class);
		var ctor = ComponentService.class.getDeclaredConstructor(ComponentRepository.class, String.class);
		ctor.setAccessible(true);
		service = ctor.newInstance(repository, "http://test/");
		getComponentService = mock(GetComponentService.class);
		branchService = mock(BranchService.class);
		vcsRepositoryService = mock(VcsRepositoryService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.empty());
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(service, "branchService", branchService);
		ReflectionTestUtils.setField(service, "vcsRepositoryService", vcsRepositoryService);
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "entityManager", mock(jakarta.persistence.EntityManager.class));
		saved = null;
		when(repository.save(any(Component.class))).thenAnswer(inv -> {
			Component c = inv.getArgument(0);
			saved = ComponentData.dataFromRecord(c);
			return c;
		});
	}

	private static CreateComponentDto create(String expression, String url, String supplierName) {
		return CreateComponentDto.builder()
				.name("component")
				.organization(ORG)
				.type(ComponentType.COMPONENT)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.licenseExpression(expression)
				.licenseUrl(url)
				.supplierName(supplierName)
				.build();
	}

	/** A stored component with the three fields, for the update cases. */
	private UUID stored(String expression, String url, String supplierName) {
		ComponentData cd = new ComponentData();
		cd.setName("component");
		cd.setOrg(ORG);
		cd.setType(ComponentType.COMPONENT);
		cd.setLicenseExpression(expression);
		cd.setLicenseUrl(url);
		cd.setSupplierName(supplierName);
		Component c = new Component();
		cd.setUuid(c.getUuid());
		c.setRecordData(Utils.dataToRecord(cd));
		when(getComponentService.getComponent(c.getUuid())).thenReturn(Optional.of(c));
		// TPF-38: updateComponent reads the row under its lock and refreshes it
		when(repository.findByIdWriteLocked(c.getUuid())).thenReturn(Optional.of(c));
		return c.getUuid();
	}

	private ComponentData update(UUID uuid, String expression, String url, String supplierName) throws RelizaException {
		service.updateComponent(ComponentDto.builder().uuid(uuid).licenseExpression(expression).licenseUrl(url)
				.supplierName(supplierName).build(), WhoUpdated.getAutoWhoUpdated());
		return saved;
	}

	@Test
	void createWithTheThreeFieldsStoresThem() throws Exception {
		service.createComponent(create("LicenseRef-Acme-Commercial", ACME_URL, "  Acme Corp "), WhoUpdated.getAutoWhoUpdated());

		assertEquals("LicenseRef-Acme-Commercial", saved.getLicenseExpression());
		assertEquals(ACME_URL, saved.getLicenseUrl());
		assertEquals("Acme Corp", saved.getSupplierName());
	}

	@Test
	void createStoresTheExpressionInCanonicalForm() throws Exception {
		service.createComponent(create("mit  OR apache-2.0", null, null), WhoUpdated.getAutoWhoUpdated());

		assertEquals("MIT OR Apache-2.0", saved.getLicenseExpression());
	}

	@Test
	void createWithoutThemStoresNulls() throws Exception {
		service.createComponent(create(null, null, null), WhoUpdated.getAutoWhoUpdated());

		assertNull(saved.getLicenseExpression());
		assertNull(saved.getLicenseUrl());
		assertNull(saved.getSupplierName());
	}

	@Test
	void anInvalidCreateIsRefusedBeforeAnythingIsWritten() {
		CreateComponentDto cpd = create("Apache License 2.0", null, null);
		cpd.setVcsRepository(CreateVcsRepositoryDto.builder().name("repo").uri("https://git.example/repo").build());

		RelizaException e = assertThrows(RelizaException.class,
				() -> service.createComponent(cpd, WhoUpdated.getAutoWhoUpdated()));

		assertEquals("licenseExpression invalid: unknown licence id \"Apache\"", e.getMessage());
		verifyNoInteractions(vcsRepositoryService, branchService);
		verify(repository, never()).save(any());
	}

	@Test
	void updateWithNullLeavesEachAlone() throws Exception {
		UUID uuid = stored("MIT", ACME_URL, "Acme Corp");

		ComponentData after = update(uuid, null, null, null);

		assertEquals("MIT", after.getLicenseExpression());
		assertEquals(ACME_URL, after.getLicenseUrl());
		assertEquals("Acme Corp", after.getSupplierName());
	}

	@Test
	void updateWithEmptyStringsClearsEach() throws Exception {
		UUID uuid = stored("MIT", ACME_URL, "Acme Corp");

		ComponentData after = update(uuid, "", "", "");

		assertNull(after.getLicenseExpression());
		assertNull(after.getLicenseUrl());
		assertNull(after.getSupplierName());
	}

	@Test
	void clearingTheExpressionClearsTheUrl() throws Exception {
		UUID uuid = stored("MIT", ACME_URL, "Acme Corp");

		ComponentData after = update(uuid, "", null, null);

		assertNull(after.getLicenseExpression());
		assertNull(after.getLicenseUrl());
		assertEquals("Acme Corp", after.getSupplierName());
	}

	@Test
	void theUrlAloneIsUpdatedWhenAnExpressionIsStored() throws Exception {
		UUID uuid = stored("MIT", null, null);

		ComponentData after = update(uuid, null, ACME_URL, null);

		assertEquals("MIT", after.getLicenseExpression());
		assertEquals(ACME_URL, after.getLicenseUrl());
	}

	private void refused(String expression, String url, String supplierName, String message) {
		UUID uuid = stored(null, null, null);
		RelizaException e = assertThrows(RelizaException.class, () -> update(uuid, expression, url, supplierName));
		assertTrue(e.getMessage().startsWith(message), e.getMessage());
		verify(repository, never()).save(any());
	}

	@Test
	void anInvalidExpressionIsRefusedBeforeAnySave() {
		refused("NOASSERTION", null, null, "licenseExpression invalid: NOASSERTION and NONE");
		refused("mit or apache-2.0", null, null, "licenseExpression invalid: unexpected token \"or\"");
	}

	@Test
	void theRefusedUrlsAreRefusedBeforeAnySave() {
		for (String url : new String[] {"https://x.example/a b", "https://x.example/\nb", "https://x.example/\"><script>",
				"not a url", "javascript:alert(1)", "file:///etc/passwd", "ftp://x.example/l"}) {
			refused("MIT", url, null, "licenseUrl invalid: ");
		}
	}

	@Test
	void aUrlWithoutAnExpressionIsRefused() {
		refused(null, ACME_URL, null, "licenseUrl needs a licenseExpression at the same level");
	}

	@Test
	void aSupplierOverTheBoundWithMarkupOrAControlCharacterIsRefused() {
		refused(null, null, "a".repeat(256), "supplierName invalid: at most 255 characters");
		refused(null, null, "Acme <b>Inc</b>", "supplierName invalid: must not contain HTML or control characters");
		refused(null, null, "Acme\tInc", "supplierName invalid: must not contain HTML or control characters");
		// security review: invisible formatting, bidi and separator characters, lone surrogates
		for (String name : new String[] {"\u202EAcme Corp", "Acme\u200BCorp", "Acme\u2066Corp\u2069", "Acme\u2028Corp",
				"Acme\uD800Corp",
				// SCORE-41 T-4: format characters outside the BMP (tag characters, musical symbols,
				// Kaithi) and a lone low surrogate
				"Acme" + new String(Character.toChars(0xE0058)) + new String(Character.toChars(0xE0059)) + " Corp",
				"Acme" + new String(Character.toChars(0xE0001)) + "Corp",
				"Acme" + new String(Character.toChars(0x1D173)) + "Corp",
				"Acme" + new String(Character.toChars(0x110BD)) + "Corp",
				"Acme\uDC00Corp"}) {
			refused(null, null, name, "supplierName invalid: must not contain HTML or control characters");
		}
	}

	@Test
	void aSupplierWithLettersBeyondAsciiIsStored() throws Exception {
		UUID uuid = stored(null, null, null);

		assertEquals("M\u00fcller GmbH \uD83D\uDE80", update(uuid, null, null, "M\u00fcller GmbH \uD83D\uDE80").getSupplierName());
		String emoji = "Acme" + new String(Character.toChars(0x1F600)) + " Corp";
		assertEquals(emoji, update(uuid, null, null, emoji).getSupplierName());
	}

	@Test
	void aSupplierWithAnAmpersandIsStoredAsTyped() throws Exception {
		UUID uuid = stored(null, null, null);

		assertEquals("Johnson & Johnson", update(uuid, null, null, "Johnson & Johnson").getSupplierName());
		assertEquals("a".repeat(255), update(uuid, null, null, "a".repeat(255)).getSupplierName());
	}
}
