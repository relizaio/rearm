/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository.CanonicalPurlRow;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.service.SbomComponentService.ReleaseComponentPurls;

/**
 * {@link SbomComponentService#resolveReleaseComponentPurls}: the components of
 * the release's canonical artifacts, a PRODUCT's dependency releases folded in
 * as the component list does, and a queued reconcile on ANY of those releases
 * reported, because the fix-evidence reader (FDA-Readiness-2 6.4) must not read
 * "absent" from an inventory a pending reconcile is about to change.
 */
class SbomComponentReleaseInventoryTest {

	private static final UUID ORG = UUID.randomUUID();

	private SharedReleaseService sharedReleaseService;
	private GetComponentService getComponentService;
	private ReleaseArtifactIndexRepository indexRepository;
	private SbomComponentRepository componentRepository;
	private SbomComponentService service;

	@BeforeEach
	void setUp() {
		indexRepository = mock(ReleaseArtifactIndexRepository.class);
		componentRepository = mock(SbomComponentRepository.class);
		service = new SbomComponentService(componentRepository, mock(ArtifactSbomComponentRepository.class),
				indexRepository, mock(ArtifactCanonicalMapRepository.class),
				mock(SbomComponentSupportRepository.class), mock(SbomComponentSupportAuditRepository.class));
		sharedReleaseService = mock(SharedReleaseService.class);
		getComponentService = mock(GetComponentService.class);
		ReflectionTestUtils.setField(service, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
	}

	private ReleaseData release(ComponentType type, boolean reconcilePending) {
		UUID component = UUID.randomUUID();
		ComponentData cd = new ComponentData();
		cd.setType(type);
		when(getComponentService.getComponentData(component)).thenReturn(Optional.of(cd));
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", component);
		rd.setSbomReconcilePending(reconcilePending);
		when(sharedReleaseService.getReleaseDataLight(rd.getUuid())).thenReturn(Optional.of(rd));
		return rd;
	}

	private static ReleaseArtifactIndex index(UUID canonicalArtifact) {
		ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
		idx.setCanonicalArtifactUuid(canonicalArtifact);
		return idx;
	}

	private static CanonicalPurlRow purlRow(UUID uuid, String canonicalPurl) {
		return new CanonicalPurlRow() {
			@Override
			public UUID getUuid() {
				return uuid;
			}

			@Override
			public String getCanonicalPurl() {
				return canonicalPurl;
			}

			@Override
			public String getLatestVersion() {
				return null;
			}

			@Override
			public String getLatestVersionChecked() {
				return null;
			}
		};
	}

	@Test
	void aProductFoldsInItsDependenciesAndTheirPendingReconcile() {
		ReleaseData product = release(ComponentType.PRODUCT, false);
		ReleaseData dep = release(ComponentType.COMPONENT, true);
		when(sharedReleaseService.unwindReleaseDependencies(product)).thenReturn(new LinkedHashSet<>(List.of(dep)));
		UUID canonical = UUID.randomUUID();
		when(indexRepository.findByOrgAndReleaseUuidIn(ORG, new LinkedHashSet<>(List.of(product.getUuid(), dep.getUuid()))))
				.thenReturn(List.of(index(canonical)));
		UUID component = UUID.randomUUID();
		when(componentRepository.findCanonicalPurlsByOrgAndCanonicalArtifactUuidIn(ORG.toString(), canonical.toString()))
				.thenReturn(List.of(purlRow(component, "pkg:npm/a@1")));

		ReleaseComponentPurls purls = service.resolveReleaseComponentPurls(product.getUuid());

		// the product has no index row of its own; its dependency's are its inventory
		assertEquals(Map.of(component, "pkg:npm/a@1"), purls.canonicalByComponent());
		assertEquals(ORG, purls.org());
		assertTrue(purls.reconcilePending());
	}

	@Test
	void aComponentWithoutIndexRowsHasNoComponentsAndSkipsTheJoin() {
		ReleaseData rd = release(ComponentType.COMPONENT, false);
		when(indexRepository.findByOrgAndReleaseUuidIn(ORG, Set.of(rd.getUuid()))).thenReturn(List.of());

		ReleaseComponentPurls purls = service.resolveReleaseComponentPurls(rd.getUuid());

		assertEquals(Map.of(), purls.canonicalByComponent());
		assertFalse(purls.reconcilePending());
		verify(componentRepository, never()).findCanonicalPurlsByOrgAndCanonicalArtifactUuidIn(anyString(), anyString());
	}

	@Test
	void anUnknownReleaseHasNoInventory() {
		ReleaseComponentPurls purls = service.resolveReleaseComponentPurls(UUID.randomUUID());

		assertEquals(Map.of(), purls.canonicalByComponent());
		assertNull(purls.org());
		assertFalse(purls.reconcilePending());
	}
}
