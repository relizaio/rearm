/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.ReleaseData;
import io.reliza.model.VariantData;

/**
 * The gather of a release's own SBOM artifacts keeps one artifact per internal BOM id, the first in
 * gather order (deliverables, then the source code entry, then the release), in every list it answers: {@code ownBomArtifactsOf} names the fingerprint's
 * {@code artifact:} line by that first artifact, and the id lists the merge reads stay distinct, the
 * release-attached one included. Collaborators mocked.
 */
class ReleaseServiceOwnBomArtifactsTest {

	private static final UUID ORG = UUID.randomUUID();

	private ReleaseService svc;
	private final Map<UUID, ComponentData> components = new HashMap<>();
	private final Map<UUID, ArtifactData> artifacts = new HashMap<>();
	private final List<DeliverableData> deliverables = new ArrayList<>();

	@BeforeEach
	void setUp() {
		svc = new ReleaseService(null);
		GetComponentService getComponentService = mock(GetComponentService.class);
		ArtifactService artifactService = mock(ArtifactService.class);
		GetDeliverableService getDeliverableService = mock(GetDeliverableService.class);
		VariantService variantService = mock(VariantService.class);
		when(getComponentService.getComponentData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(components.get(inv.<UUID>getArgument(0))));
		when(artifactService.getArtifactData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(artifacts.get(inv.<UUID>getArgument(0))));
		when(variantService.getBaseVariantForRelease(any(ReleaseData.class))).thenReturn(new VariantData());
		when(getDeliverableService.getDeliverableDataList(any())).thenAnswer(inv -> List.copyOf(deliverables));
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		ReflectionTestUtils.setField(svc, "getSourceCodeEntryService", mock(GetSourceCodeEntryService.class));
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		ReflectionTestUtils.setField(svc, "variantService", variantService);
	}

	private ReleaseData release() {
		ComponentData cd = new ComponentData();
		ReflectionTestUtils.setField(cd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(cd, "org", ORG);
		cd.setName("own-boms");
		cd.setType(ComponentType.COMPONENT);
		components.put(cd.getUuid(), cd);
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", cd.getUuid());
		rd.setVersion("1.0.0");
		rd.setArtifacts(new ArrayList<>());
		return rd;
	}

	private ArtifactData bom(UUID bomId) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		ad.setInternalBom(new InternalBom(bomId, ArtifactBelongsTo.RELEASE));
		artifacts.put(ad.getUuid(), ad);
		return ad;
	}

	@Test
	void twoArtifactsOfOneBomAreOneSourceNamedByTheFirst() {
		ReleaseData rd = release();
		UUID shared = UUID.randomUUID();
		ArtifactData first = bom(shared);
		ArtifactData second = bom(shared);
		ArtifactData other = bom(UUID.randomUUID());
		rd.setArtifacts(new ArrayList<>(List.of(first.getUuid(), second.getUuid(), other.getUuid())));

		assertEquals(List.of(first.getUuid(), other.getUuid()),
				svc.ownBomArtifactsOf(rd, null, null).stream().map(ArtifactData::getUuid).toList());
		ReleaseService.OwnBomIds ids = svc.gatherOwnBomIds(rd, ComponentType.COMPONENT, ReleaseService.sourcesOf(null),
				List.of());
		List<UUID> expected = List.of(shared, other.getInternalBom().id());
		assertEquals(expected, ids.all());
		assertEquals(expected, ids.releaseAttached());
	}

	@Test
	void aBomOnADeliverableAndOnTheReleaseIsNamedByTheDeliverablesArtifact() {
		ReleaseData rd = release();
		UUID shared = UUID.randomUUID();
		ArtifactData onDeliverable = bom(shared);
		ArtifactData onRelease = bom(shared);
		ArtifactData other = bom(UUID.randomUUID());
		DeliverableData dd = new DeliverableData();
		dd.setUuid(UUID.randomUUID());
		dd.setArtifacts(new ArrayList<>(List.of(onDeliverable.getUuid())));
		deliverables.add(dd);
		rd.setArtifacts(new ArrayList<>(List.of(onRelease.getUuid(), other.getUuid())));

		assertEquals(List.of(onDeliverable.getUuid(), other.getUuid()),
				svc.ownBomArtifactsOf(rd, null, null).stream().map(ArtifactData::getUuid).toList());
		assertEquals(List.of(shared, other.getInternalBom().id()), svc.gatherOwnBomIds(rd, ComponentType.COMPONENT,
				ReleaseService.sourcesOf(null), List.of()).all());
	}
}
