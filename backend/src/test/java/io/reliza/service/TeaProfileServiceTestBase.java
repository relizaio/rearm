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

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.RelizaObject;
import io.reliza.model.SbomProfileData;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.repositories.TeaProfileRepository;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.ws.RelizaConfigProps;

/**
 * The shared fixture of the TEA profile service unit tests (tasks TEA-2 and TEA-9): the
 * repository is backed by an in-memory list so an upsert and a delete can be observed, and the
 * resolver is a mock whose every answer defaults to the built-in defaults (PRIVATE, DISABLED).
 * Task TEA-27: the SBOM profile resolver is a mock that answers every name RESOLVED to the built-in
 * {@code default} recipe unless a case puts its own answer in {@link #names}.
 */
abstract class TeaProfileServiceTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	protected TeaProfileService service;
	protected TeaProfileRepository repository;
	protected OssPerspectiveService perspectives;
	protected GetComponentService components;
	protected GetOrganizationService orgs;
	protected OrganizationService organizationService;
	protected AuditService auditService;
	protected SupportInjectionService supportInjectionService;
	protected SupportInjectionSetting injection = SupportInjectionSetting.DISABLED;
	protected RelizaConfigProps props;
	protected TeaProfileResolver resolver;
	protected SbomProfileResolver sbomResolver;
	/** The SBOM profile resolutions per name a case states; a name not here resolves to the built-in recipe. */
	protected final Map<String, SbomProfileResolution> names = new java.util.HashMap<>();

	protected final UUID org = UUID.randomUUID();
	protected final List<TeaProfile> stored = new ArrayList<>();
	protected OrganizationData od;
	protected ComponentData cd;
	protected final TeaPerspectiveRef alpha = new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", PerspectiveType.PERSPECTIVE);
	protected final TeaPerspectiveRef beta = new TeaPerspectiveRef(UUID.randomUUID(), "Beta", PerspectiveType.PERSPECTIVE);

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
		sbomResolver = mock(SbomProfileResolver.class);
		lenient().when(sbomResolver.resolveForComponent(any(), anyString())).thenAnswer(inv -> nameAnswer(inv.getArgument(1)));
		lenient().when(sbomResolver.resolveForComponent(any(), anyString(), any(), any()))
				.thenAnswer(inv -> nameAnswer(inv.getArgument(1)));
		lenient().when(sbomResolver.effectiveOfScope(any(), any(), any(), anyString()))
				.thenAnswer(inv -> nameAnswer(inv.getArgument(3)));
		ReflectionTestUtils.setField(service, "sbomProfileResolver", sbomResolver);
		service.setProps(props);
		resolver = mock(TeaProfileResolver.class);
		lenient().when(resolver.resolveForComponent(any())).thenAnswer(inv -> defaults());
		lenient().when(resolver.resolveForComponentWithRow(any(), any())).thenAnswer(inv -> defaults());
		lenient().when(resolver.effectiveOfScope(any(), any(), any())).thenAnswer(inv -> defaults());
		lenient().when(resolver.parentOfScope(any(), any(), any())).thenAnswer(inv -> defaults());
		lenient().when(resolver.resolveForComponentWithPerspectives(any(), any())).thenAnswer(inv -> defaults());
		lenient().when(resolver.resolveForComponentWithoutPerspectiveRow(any(), any())).thenAnswer(inv -> defaults());
		ReflectionTestUtils.setField(service, "teaProfileResolver", resolver);

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
				.filter(p -> TeaProfileScope.ORGANIZATION == p.getScope() && null == p.getObjectUuid()).findFirst());
		lenient().when(repository.findByScopeObject(eq(org), anyString(), any())).thenAnswer(inv -> stored.stream()
				.filter(p -> p.getScope().name().equals(inv.getArgument(1)) && inv.getArgument(2).equals(p.getObjectUuid()))
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

	protected static RelizaObject orgObject(UUID org) {
		return new RelizaObject() {
			public UUID getUuid() { return UUID.randomUUID(); }
			public UUID getOrg() { return org; }
			public UUID getResourceGroup() { return null; }
		};
	}

	/** Every profile field set to a valid value: the baseline each case changes one thing of. */
	protected static TeaProfileInputDto full() {
		return TeaProfileInputDto.builder()
				.publishing(TeaPublishing.DISABLED)
				.visibility(TeaVisibility.PRIVATE)
				.rawArtifacts(TeaRawArtifacts.NONE)
				.productComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT)
				.minimumLifecycle(ReleaseLifecycle.ASSEMBLED)
				.tei(TeaTei.DISABLED)
				.profiles(List.of(SbomProfileData.DEFAULT_NAME))
				.build();
	}

	/** The answer of the mocked SBOM profile resolver for {@code name}. */
	protected SbomProfileResolution nameAnswer(String name) {
		SbomProfileResolution r = names.get(name);
		if (null != r) return r;
		SbomProfileData d = SbomProfileData.builtInDefault(org);
		d.setName(name);
		return new SbomProfileResolution(ScopedResolutionStatus.RESOLVED, ScopedSettingsSource.DEFAULT, null, d, List.of());
	}

	protected static SbomProfileResolution notFound() {
		return new SbomProfileResolution(ScopedResolutionStatus.NOT_FOUND, null, null, null, List.of());
	}

	protected SbomProfileResolution nameConflict() {
		return new SbomProfileResolution(ScopedResolutionStatus.CONFLICT, ScopedSettingsSource.PERSPECTIVE, null, null,
				List.of(alpha, beta));
	}

	protected static TeaProfileInputDto override() {
		return full().toBuilder().mode(TeaComponentProfileMode.OVERRIDE).build();
	}

	protected RelizaException refused(TeaProfileScope scope, UUID object, TeaProfileInputDto in) {
		return refused(scope, object, in, TeaCaller.ORG_ADMIN);
	}

	protected RelizaException refused(TeaProfileScope scope, UUID object, TeaProfileInputDto in, TeaCaller caller) {
		return assertThrows(RelizaException.class, () -> service.saveProfile(org, scope, object, in, caller, WU));
	}

	protected TeaProfileData save(TeaProfileScope scope, UUID object, TeaProfileInputDto in, TeaCaller caller)
			throws RelizaException {
		return service.saveProfile(org, scope, object, in, caller, WU);
	}

	/** The built-in defaults as the resolver answers them: RESOLVED, source DEFAULT, PRIVATE, DISABLED. */
	protected TeaResolvedProfile defaults() {
		return resolution(TeaProfileSource.DEFAULT, null, TeaVisibility.PRIVATE, TeaPublishing.DISABLED);
	}

	/** A RESOLVED resolution from {@code source} whose profile has the given visibility and publishing. */
	protected TeaResolvedProfile resolution(TeaProfileSource source, UUID sourceObject, TeaVisibility visibility,
			TeaPublishing publishing) {
		TeaProfileData p = TeaProfileData.builtInDefault(org);
		p.setVisibility(visibility);
		p.setPublishing(publishing);
		return TeaResolvedProfile.resolved(source, sourceObject, p);
	}

	protected TeaResolvedProfile conflict() {
		return new TeaResolvedProfile(TeaResolutionStatus.CONFLICT, TeaProfileSource.PERSPECTIVE, null, null,
				List.of(alpha, beta));
	}

	/** A stored row at (scope, object), written straight into the in-memory repository. */
	protected TeaProfile storedRow(TeaProfileScope scope, UUID object, TeaComponentProfileMode mode,
			TeaVisibility visibility, TeaPublishing publishing) {
		TeaProfileData d = TeaProfileData.builtInDefault(org);
		d.setUuid(UUID.randomUUID());
		d.setScope(scope);
		d.setObject(object);
		d.setMode(mode);
		d.setVisibility(visibility);
		d.setPublishing(publishing);
		TeaProfile e = new TeaProfile();
		e.setUuid(d.getUuid());
		e.setOrg(org);
		e.setScope(scope);
		e.setObjectUuid(object);
		e.setRecordData(d.toRecordData());
		stored.add(e);
		return e;
	}

	protected void injection(SupportInjectionSetting s) {
		injection = s;
	}

}
