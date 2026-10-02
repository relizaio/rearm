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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.RelizaObject;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaDependencyDepth;
import io.reliza.model.TeaProfileData.TeaInternalMetadata;
import io.reliza.model.TeaProfileData.TeaOptionalDependencies;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaSbomSource;
import io.reliza.model.TeaProfileData.TeaSupportMetadata;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaProfileData.TeaVulnerabilityDocuments;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.repositories.TeaProfileRepository;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.ws.RelizaConfigProps;

/**
 * Task TEA-2, design 4.2: TEA profile writes, validation, upsert, deletes and discovery. The
 * repository is backed by an in-memory list so an upsert and a delete can be observed.
 */
class TeaProfileServiceTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private TeaProfileService service;
	private TeaProfileRepository repository;
	private OssPerspectiveService perspectives;
	private GetComponentService components;
	private GetOrganizationService orgs;
	private OrganizationService organizationService;
	private AuditService auditService;
	private SupportInjectionService supportInjectionService;
	private SupportInjectionSetting injection = SupportInjectionSetting.DISABLED;
	private RelizaConfigProps props;

	private final UUID org = UUID.randomUUID();
	private final List<TeaProfile> stored = new ArrayList<>();
	private OrganizationData od;
	private ComponentData cd;
	private final TeaPerspectiveRef alpha = new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef beta = new TeaPerspectiveRef(UUID.randomUUID(), "Beta", PerspectiveType.PERSPECTIVE);

	@BeforeEach
	void setUp() {
		service = new TeaProfileService();
		repository = mock(TeaProfileRepository.class);
		perspectives = mock(OssPerspectiveService.class);
		components = mock(GetComponentService.class);
		orgs = mock(GetOrganizationService.class);
		organizationService = mock(OrganizationService.class);
		auditService = mock(AuditService.class);
		supportInjectionService = mock(SupportInjectionService.class);
		lenient().when(supportInjectionService.isInjectionEnabled(any()))
				.thenAnswer(inv -> injection == SupportInjectionSetting.ENABLED);
		props = new RelizaConfigProps();
		props.setBaseuri("https://rearm.example.com/");
		ReflectionTestUtils.setField(service, "repository", repository);
		ReflectionTestUtils.setField(service, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(service, "getComponentService", components);
		ReflectionTestUtils.setField(service, "getOrganizationService", orgs);
		ReflectionTestUtils.setField(service, "organizationService", organizationService);
		ReflectionTestUtils.setField(service, "auditService", auditService);
		ReflectionTestUtils.setField(service, "supportInjectionService", supportInjectionService);
		service.setProps(props);

		od = new OrganizationData();
		od.setUuid(org);
		od.setName("org");
		lenient().when(orgs.getOrganizationData(org)).thenAnswer(inv -> Optional.of(od));

		cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setName("widget");
		lenient().when(components.getComponentData(cd.getUuid())).thenReturn(Optional.of(cd));
		lenient().when(perspectives.teaPerspectivesSupported()).thenReturn(true);
		lenient().when(perspectives.teaPerspectivesOfComponent(any())).thenReturn(List.of(alpha, beta));
		lenient().when(perspectives.teaPerspectiveRef(alpha.uuid())).thenReturn(Optional.of(alpha));
		lenient().when(perspectives.teaPerspectiveRef(beta.uuid())).thenReturn(Optional.of(beta));
		lenient().when(perspectives.teaPerspectiveObject(alpha.uuid())).thenReturn(Optional.of(orgObject(org)));

		lenient().when(repository.findOrgProfile(org)).thenAnswer(inv -> stored.stream()
				.filter(p -> "ORGANIZATION".equals(p.getScope()) && null == p.getObjectUuid()).findFirst());
		lenient().when(repository.findByScopeObject(eq(org), anyString(), any())).thenAnswer(inv -> stored.stream()
				.filter(p -> p.getScope().equals(inv.getArgument(1)) && inv.getArgument(2).equals(p.getObjectUuid()))
				.findFirst());
		lenient().when(repository.listByObject(eq(org), any())).thenAnswer(inv -> stored.stream()
				.filter(p -> inv.getArgument(1).equals(p.getObjectUuid())).toList());
		lenient().when(repository.save(any())).thenAnswer(inv -> {
			TeaProfile p = inv.getArgument(0);
			if (!stored.contains(p)) stored.add(p);
			return p;
		});
		lenient().doAnswer(inv -> stored.remove((TeaProfile) inv.getArgument(0))).when(repository).delete(any());
	}

	private static RelizaObject orgObject(UUID org) {
		return new RelizaObject() {
			public UUID getUuid() { return UUID.randomUUID(); }
			public UUID getOrg() { return org; }
			public UUID getResourceGroup() { return null; }
		};
	}

	/** Every profile field set to a valid value: the baseline each case changes one thing of. */
	private static TeaProfileInputDto full() {
		return TeaProfileInputDto.builder()
				.publishing(TeaPublishing.DISABLED)
				.visibility(TeaVisibility.PRIVATE)
				.dependencyDepth(TeaDependencyDepth.FULL)
				.optionalDependencies(TeaOptionalDependencies.INCLUDE)
				.structure(BomStructureType.FLAT)
				.sources(EnumSet.allOf(TeaSbomSource.class))
				.excludedCoverage(EnumSet.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST))
				.supportMetadata(TeaSupportMetadata.EXCLUDE)
				.internalMetadata(TeaInternalMetadata.EXCLUDE)
				.rawArtifacts(TeaRawArtifacts.NONE)
				.productComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT)
				.minimumLifecycle(ReleaseLifecycle.ASSEMBLED)
				.tei(TeaTei.DISABLED)
				.vulnerabilityDocuments(TeaVulnerabilityDocuments.NONE)
				.build();
	}

	private static TeaProfileInputDto override() {
		return full().toBuilder().mode(TeaComponentProfileMode.OVERRIDE).build();
	}

	private RelizaException refused(TeaProfileScope scope, UUID object, TeaProfileInputDto in) {
		return assertThrows(RelizaException.class, () -> service.saveProfile(org, scope, object, in, WU));
	}

	private void injection(SupportInjectionSetting s) {
		injection = s;
	}

	@Test
	void theOrganizationScopeTakesNoObjectAndNoMode() {
		assertTrue(refused(TeaProfileScope.ORGANIZATION, UUID.randomUUID(), full()).getMessage().contains("object"));
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, override()).getMessage().contains("mode"));
	}

	@Test
	void thePerspectiveScopeNeedsARealPerspectiveOfTheOrg() throws RelizaException {
		when(perspectives.teaPerspectivesSupported()).thenReturn(false);
		assertTrue(refused(TeaProfileScope.PERSPECTIVE, alpha.uuid(), full()).getMessage().contains("ReARM Pro"));
		when(perspectives.teaPerspectivesSupported()).thenReturn(true);

		UUID product = UUID.randomUUID();
		when(perspectives.teaPerspectiveRef(product))
				.thenReturn(Optional.of(new TeaPerspectiveRef(product, "Shop", PerspectiveType.PRODUCT)));
		assertTrue(refused(TeaProfileScope.PERSPECTIVE, product, full()).getMessage().contains("COMPONENT-scope profile"));

		UUID foreign = UUID.randomUUID();
		when(perspectives.teaPerspectiveRef(foreign))
				.thenReturn(Optional.of(new TeaPerspectiveRef(foreign, "Theirs", PerspectiveType.PERSPECTIVE)));
		when(perspectives.teaPerspectiveObject(foreign)).thenReturn(Optional.of(orgObject(UUID.randomUUID())));
		assertTrue(refused(TeaProfileScope.PERSPECTIVE, foreign, full()).getMessage().contains("not found"));

		TeaProfileData saved = service.saveProfile(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), full(), WU);
		assertEquals(alpha.uuid(), saved.getObject());
		assertEquals(TeaProfileScope.PERSPECTIVE, saved.getScope());
		assertNull(saved.getMode());
	}

	@Test
	void theComponentScopeNeedsAValidMode() throws RelizaException {
		UUID c = cd.getUuid();
		assertTrue(refused(TeaProfileScope.COMPONENT, c, full()).getMessage().contains("mode"));
		assertTrue(refused(TeaProfileScope.COMPONENT, c, override().toBuilder().followedPerspective(alpha.uuid()).build())
				.getMessage().contains("followedPerspective"));
		TeaProfileInputDto follow = TeaProfileInputDto.builder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE).build();
		assertTrue(refused(TeaProfileScope.COMPONENT, c, follow).getMessage().contains("followedPerspective"));

		UUID elsewhere = UUID.randomUUID();
		when(perspectives.teaPerspectiveRef(elsewhere))
				.thenReturn(Optional.of(new TeaPerspectiveRef(elsewhere, "Elsewhere", PerspectiveType.PERSPECTIVE)));
		String message = refused(TeaProfileScope.COMPONENT, c, follow.toBuilder().followedPerspective(elsewhere).build())
				.getMessage();
		assertTrue(message.contains("Elsewhere"), message);
		assertTrue(message.contains("Alpha, Beta"), "lists the candidates by name: " + message);

		TeaProfileData saved = service.saveProfile(org, TeaProfileScope.COMPONENT, c,
				full().toBuilder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE).followedPerspective(beta.uuid())
						.publishing(TeaPublishing.ENABLED).build(), WU);
		assertEquals(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, saved.getMode());
		assertEquals(beta.uuid(), saved.getFollowedPerspective());
		for (var f : TeaProfileService.REQUIRED_FIELDS.entrySet()) {
			assertNull(f.getValue().apply(saved), f.getKey() + " is stored null on a FOLLOW row");
		}
		assertNull(saved.getTeiDomain());
		verify(organizationService, never()).ensureTeaUuid(any(), any());
	}

	static Stream<String> requiredFields() {
		return TeaProfileService.REQUIRED_FIELDS.keySet().stream();
	}

	@ParameterizedTest
	@MethodSource("requiredFields")
	void everyProfileFieldIsRequiredOnAnOverride(String field) {
		@SuppressWarnings("unchecked")
		Map<String, Object> m = Utils.OM.convertValue(override(), Map.class);
		m.remove(field);
		TeaProfileInputDto in = Utils.OM.convertValue(m, TeaProfileInputDto.class);
		assertEquals(field + " is required", refused(TeaProfileScope.COMPONENT, cd.getUuid(), in).getMessage());
	}

	@Test
	void sourcesMayNotBeEmptyButExcludedCoverageMay() throws RelizaException {
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, full().toBuilder().sources(Set.of()).build())
				.getMessage().contains("sources"));
		TeaProfileData saved = service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				full().toBuilder().excludedCoverage(Set.of()).build(), WU);
		assertTrue(saved.getExcludedCoverage().isEmpty());
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class, names = { "CANCELLED", "REJECTED", "PENDING", "DRAFT" })
	void aMinimumLifecycleBelowAssembledIsTooLow(ReleaseLifecycle rl) {
		String m = refused(TeaProfileScope.ORGANIZATION, null, full().toBuilder().minimumLifecycle(rl).build()).getMessage();
		assertTrue(m.contains("minimumLifecycle") && m.contains("too low"), m);
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class,
			names = { "END_OF_MARKETING", "END_OF_DISTRIBUTION", "END_OF_SUPPORT", "END_OF_LIFE" })
	void aPostShipmentMinimumLifecycleNamesGeneralAvailability(ReleaseLifecycle rl) {
		String m = refused(TeaProfileScope.ORGANIZATION, null, full().toBuilder().minimumLifecycle(rl).build()).getMessage();
		assertTrue(m.contains("use GENERAL_AVAILABILITY"), m);
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class, names = { "ASSEMBLED", "READY_TO_SHIP", "GENERAL_AVAILABILITY" })
	void theAcceptedMinimumLifecycles(ReleaseLifecycle rl) throws RelizaException {
		assertEquals(rl, service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				full().toBuilder().minimumLifecycle(rl).build(), WU).getMinimumLifecycle());
	}

	@Test
	void theTeiDomainRules() throws RelizaException {
		TeaProfileInputDto uuidTei = full().toBuilder().tei(TeaTei.UUID).build();
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, uuidTei).getMessage().contains("teiDomain"));
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, uuidTei.toBuilder().teiDomain("Products.Example.com").build())
				.getMessage().contains("lowercase"));
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, uuidTei.toBuilder().teiDomain("localhost").build())
				.getMessage().contains("dot"));
		assertEquals("products.example.com", service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				uuidTei.toBuilder().teiDomain("products.example.com").build(), WU).getTeiDomain());
		assertNull(service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				full().toBuilder().teiDomain("products.example.com").build(), WU).getTeiDomain(),
				"tei DISABLED stores no domain");
	}

	@Test
	void supportMetadataIncludeNeedsTheOrgInjection() throws RelizaException {
		TeaProfileInputDto include = full().toBuilder().supportMetadata(TeaSupportMetadata.INCLUDE).build();
		injection(SupportInjectionSetting.DISABLED);
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, include).getMessage().contains("supportInjection"));
		injection(SupportInjectionSetting.ENABLED);
		assertEquals(TeaSupportMetadata.INCLUDE,
				service.saveProfile(org, TeaProfileScope.ORGANIZATION, null, include, WU).getSupportMetadata());
	}

	@Test
	void theFirstEnabledSaveMintsTheOrgTeaId() throws RelizaException {
		service.saveProfile(org, TeaProfileScope.ORGANIZATION, null, full(), WU);
		verify(organizationService, never()).ensureTeaUuid(any(), any());

		service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				full().toBuilder().publishing(TeaPublishing.ENABLED).build(), WU);
		verify(organizationService, times(1)).ensureTeaUuid(org, WU);

		od.setTeaUuid(UUID.randomUUID());
		service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(),
				override().toBuilder().publishing(TeaPublishing.ENABLED).build(), WU);
		verify(organizationService, times(1)).ensureTeaUuid(any(), any());
	}

	@Test
	void aSecondSaveUpdatesTheSameRow() throws RelizaException {
		TeaProfileData first = service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), override(), WU);
		assertEquals(0, first.getRevision());
		verify(auditService, never()).createAndSaveAuditRecord(any(), any());

		TeaProfileData second = service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(),
				override().toBuilder().visibility(TeaVisibility.PUBLIC).build(), WU);
		assertEquals(first.getUuid(), second.getUuid());
		assertEquals(1, second.getRevision());
		assertEquals(TeaVisibility.PUBLIC, second.getVisibility());
		assertEquals(1, stored.size());
		verify(auditService, times(1)).createAndSaveAuditRecord(eq(TableName.TEA_PROFILE), any());
		assertEquals(cd.getUuid(), stored.get(0).getObjectUuid(), "the key columns follow the record");
		assertEquals("COMPONENT", stored.get(0).getScope());
		assertEquals(org, stored.get(0).getOrg());
	}

	@Test
	void deletingRows() throws RelizaException {
		assertThrows(RelizaException.class, () -> service.deleteProfile(org, TeaProfileScope.ORGANIZATION, null, WU));
		service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), override(), WU);
		assertTrue(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), WU));
		assertTrue(stored.isEmpty());
		assertFalse(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), WU));
	}

	@Test
	void deleteProfilesOfObjectRemovesOnlyThatObjectsRows() throws RelizaException {
		service.saveProfile(org, TeaProfileScope.ORGANIZATION, null, full(), WU);
		service.saveProfile(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), full(), WU);
		service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), override(), WU);
		service.deleteProfilesOfObject(org, alpha.uuid(), WU);
		assertEquals(2, stored.size());
		assertTrue(stored.stream().noneMatch(p -> alpha.uuid().equals(p.getObjectUuid())));
	}

	@Test
	void thePublishedCountIsASeamUntilTea5() {
		assertEquals(0, service.countPublishedReleasesResolvingTo(org, TeaProfileScope.ORGANIZATION, null));
		assertEquals(0, service.countPublishedReleasesResolvingTo(org, TeaProfileScope.COMPONENT, cd.getUuid()));
	}

	@Test
	void orgDiscovery() throws RelizaException {
		TeaProfileService.TeaOrgDiscovery before = service.orgDiscovery(org);
		assertNull(before.teaUuid());
		assertNull(before.apiBase());
		assertNull(before.wellKnownDocument());

		UUID tea = UUID.randomUUID();
		od.setTeaUuid(tea);
		TeaProfileService.TeaOrgDiscovery after = service.orgDiscovery(org);
		String base = "https://rearm.example.com/tea/" + tea;
		assertEquals(tea, after.teaUuid());
		assertEquals(base, after.apiBase());
		assertNotNull(after.wellKnownDocument());
		assertEquals(Utils.OM.readTree("{\"schemaVersion\":1,\"endpoints\":[{\"url\":\"" + base
				+ "\",\"versions\":[\"1.0.0\"],\"priority\":1}]}"), Utils.OM.readTree(after.wellKnownDocument()));
	}
}
