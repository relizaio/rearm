/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SbomProfile;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SbomProfileData.SbomOptionalDependencies;
import io.reliza.model.SbomProfileData.SbomSourceKind;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.SbomProfileData.SbomVulnerabilityDocuments;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.SettingsScope;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SbomProfileInputDto;
import io.reliza.repositories.SbomProfileRepository;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * The shared fixture of the SBOM profile unit tests (task TEA-27, design 4.3 and 4.5): the real
 * service, the real engine and the real resolver over a repository backed by an in-memory list, so a
 * save, a delete and a resolution can each be observed; the channels are a list of consumers.
 */
abstract class SbomProfileServiceTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	protected SbomProfileService service;
	protected SbomProfileResolver resolver;
	protected ScopedSettingsResolver engine;
	protected SbomProfileRepository repository;
	protected OssPerspectiveService perspectives;
	protected GetComponentService components;
	protected GetOrganizationService orgs;
	protected AuditService auditService;
	protected SupportInjectionService supportInjectionService;
	protected SupportInjectionSetting injection = SupportInjectionSetting.DISABLED;
	protected final List<SbomProfileConsumer> consumers = new ArrayList<>();

	protected final UUID org = UUID.randomUUID();
	protected final List<SbomProfile> stored = new ArrayList<>();
	protected OrganizationData od;
	protected ComponentData cd;
	protected final TeaPerspectiveRef alpha = new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", PerspectiveType.PERSPECTIVE);
	protected final TeaPerspectiveRef beta = new TeaPerspectiveRef(UUID.randomUUID(), "Beta", PerspectiveType.PERSPECTIVE);

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		service = new SbomProfileService();
		resolver = new SbomProfileResolver();
		engine = new ScopedSettingsResolver();
		repository = mock(SbomProfileRepository.class);
		perspectives = mock(OssPerspectiveService.class);
		components = mock(GetComponentService.class);
		orgs = mock(GetOrganizationService.class);
		auditService = mock(AuditService.class);
		supportInjectionService = mock(SupportInjectionService.class);
		lenient().when(supportInjectionService.isInjectionEnabled(any()))
				.thenAnswer(inv -> injection == SupportInjectionSetting.ENABLED);
		ObjectProvider<SbomProfileConsumer> provider = mock(ObjectProvider.class);
		lenient().when(provider.orderedStream()).thenAnswer(inv -> consumers.stream());

		ReflectionTestUtils.setField(engine, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(engine, "getComponentService", components);
		ReflectionTestUtils.setField(service, "repository", repository);
		ReflectionTestUtils.setField(service, "auditService", auditService);
		ReflectionTestUtils.setField(service, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(service, "getComponentService", components);
		ReflectionTestUtils.setField(service, "getOrganizationService", orgs);
		ReflectionTestUtils.setField(service, "supportInjectionService", supportInjectionService);
		ReflectionTestUtils.setField(service, "scopedSettingsResolver", engine);
		ReflectionTestUtils.setField(service, "consumers", provider);
		ReflectionTestUtils.setField(resolver, "scopedSettingsResolver", engine);
		ReflectionTestUtils.setField(resolver, "sbomProfileService", service);
		ReflectionTestUtils.setField(resolver, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(resolver, "getComponentService", components);
		ReflectionTestUtils.setField(resolver, "supportInjectionService", supportInjectionService);

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
		lenient().when(perspectives.teaPerspectiveObject(beta.uuid())).thenReturn(Optional.of(orgObject(org)));

		lenient().when(repository.findOrgProfile(eq(org), anyString())).thenAnswer(inv -> stored.stream()
				.filter(p -> SettingsScope.ORGANIZATION == p.getScope() && null == p.getObjectUuid()
						&& p.getName().equals(inv.getArgument(1))).findFirst());
		lenient().when(repository.findByScopeObjectName(eq(org), anyString(), any(), anyString())).thenAnswer(inv -> stored
				.stream().filter(p -> p.getScope().name().equals(inv.getArgument(1)) && inv.getArgument(2).equals(p.getObjectUuid())
						&& p.getName().equals(inv.getArgument(3))).findFirst());
		lenient().when(repository.listOfOrganizationScope(org)).thenAnswer(inv -> stored.stream()
				.filter(p -> SettingsScope.ORGANIZATION == p.getScope()).sorted(Comparator.comparing(SbomProfile::getName))
				.toList());
		lenient().when(repository.listByScopeObject(eq(org), anyString(), any())).thenAnswer(inv -> stored.stream()
				.filter(p -> p.getScope().name().equals(inv.getArgument(1)) && inv.getArgument(2).equals(p.getObjectUuid()))
				.sorted(Comparator.comparing(SbomProfile::getName)).toList());
		lenient().when(repository.listByScopeObjectsAndName(eq(org), anyString(), any(), anyString())).thenAnswer(inv -> {
			Set<String> ids = Set.copyOf(Arrays.asList((String[]) inv.getArgument(2)));
			return stored.stream().filter(p -> p.getScope().name().equals(inv.getArgument(1)) && null != p.getObjectUuid()
					&& ids.contains(p.getObjectUuid().toString()) && p.getName().equals(inv.getArgument(3))).toList();
		});
		lenient().when(repository.listByOrg(org)).thenAnswer(inv -> List.copyOf(stored));
		lenient().when(repository.listByObject(eq(org), any())).thenAnswer(inv -> stored.stream()
				.filter(p -> inv.getArgument(1).equals(p.getObjectUuid())).toList());
		lenient().when(repository.save(any())).thenAnswer(inv -> {
			SbomProfile p = inv.getArgument(0);
			if (!stored.contains(p)) stored.add(p);
			return p;
		});
		lenient().doAnswer(inv -> stored.remove((SbomProfile) inv.getArgument(0))).when(repository).delete(any());
	}

	protected static RelizaObject orgObject(UUID org) {
		return new RelizaObject() {
			public UUID getUuid() { return UUID.randomUUID(); }
			public UUID getOrg() { return org; }
			public UUID getResourceGroup() { return null; }
		};
	}

	/** Every recipe field set to a valid value: the baseline each case changes one thing of. */
	protected static SbomProfileInputDto recipe() {
		return SbomProfileInputDto.builder()
				.dependencyDepth(SbomDependencyDepth.FULL)
				.optionalDependencies(SbomOptionalDependencies.INCLUDE)
				.structure(BomStructureType.FLAT)
				.sources(Set.of(SbomSourceKind.DELIVERABLE, SbomSourceKind.RELEASE, SbomSourceKind.SOURCE_CODE))
				.excludedCoverage(Set.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST))
				.supportMetadata(SbomSupportMetadata.EXCLUDE)
				.internalMetadata(SbomInternalMetadata.EXCLUDE)
				.vulnerabilityDocuments(SbomVulnerabilityDocuments.NONE)
				.build();
	}

	/** A stored row of {@code name} at (scope, object), written straight into the in-memory repository. */
	protected SbomProfile row(SettingsScope scope, UUID object, String name, ScopedSettingsMode mode, UUID followed,
			SbomDependencyDepth depth) {
		SbomProfileData d = SbomProfileData.builtInDefault(org);
		d.setUuid(UUID.randomUUID());
		d.setScope(scope);
		d.setObject(object);
		d.setName(name);
		d.setDescription(null);
		d.setMode(mode);
		d.setFollowedPerspective(followed);
		if (ScopedSettingsMode.FOLLOW_PERSPECTIVE == mode) {
			d = new SbomProfileData();
			d.setUuid(UUID.randomUUID());
			d.setOrg(org);
			d.setScope(scope);
			d.setObject(object);
			d.setName(name);
			d.setMode(mode);
			d.setFollowedPerspective(followed);
		} else {
			d.setDependencyDepth(depth);
		}
		SbomProfile e = new SbomProfile();
		e.setUuid(d.getUuid());
		e.setOrg(org);
		e.setScope(scope);
		e.setObjectUuid(object);
		e.setName(name);
		e.setRecordData(d.toRecordData());
		stored.add(e);
		return e;
	}

	protected SbomProfile orgRow(String name, SbomDependencyDepth depth) {
		return row(SettingsScope.ORGANIZATION, null, name, null, null, depth);
	}

	protected SbomProfile perspectiveRow(TeaPerspectiveRef p, String name, SbomDependencyDepth depth) {
		return row(SettingsScope.PERSPECTIVE, p.uuid(), name, null, null, depth);
	}

	protected SbomProfile componentRow(String name, SbomDependencyDepth depth) {
		return row(SettingsScope.COMPONENT, cd.getUuid(), name, ScopedSettingsMode.OVERRIDE, null, depth);
	}

	protected SbomProfile followRow(String name, TeaPerspectiveRef followed) {
		return row(SettingsScope.COMPONENT, cd.getUuid(), name, ScopedSettingsMode.FOLLOW_PERSPECTIVE, followed.uuid(),
				null);
	}

	protected void injection(SupportInjectionSetting s) {
		injection = s;
	}
}
