/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;

/**
 * SyntheticSbomService.requeueFanOutForArtifact, the operator nudge of the scan status: it requeues the artifact's
 * canonical through requeueFanOutForCanonical and writes nothing else (no bucket, no component, no artifact).
 */
@ExtendWith(MockitoExtension.class)
class SyntheticSbomServiceFanOutRequeueTest {

	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SyntheticDtrackBucketRepository bucketRepository;
	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private SharedArtifactService sharedArtifactService;

	@InjectMocks private SyntheticSbomService service;

	private final UUID org = UUID.randomUUID();
	private final UUID artifact = UUID.randomUUID();
	private final UUID canonical = UUID.randomUUID();

	/** Whatever the outcome, the requeue is the one write: no bucket, component or artifact is touched. */
	@AfterEach
	void writesNothingElse() {
		verifyNoInteractions(bucketRepository, sbomComponentRepository, artifactSbomComponentRepository,
				sharedArtifactService);
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForComponents(any(), any());
		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(any(), any(), any(), any());
		verify(artifactCanonicalMapRepository, never()).save(any());
	}

	private ArtifactData artifactOf(ArtifactType type) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", artifact);
		ad.setOrg(org);
		ad.setType(type);
		return ad;
	}

	@Test
	void aMappedBomRequeuesItsCanonical() throws RelizaException {
		ArtifactCanonicalMap own = new ArtifactCanonicalMap();
		own.setOrg(org);
		own.setArtifactUuid(artifact);
		own.setCanonicalArtifactUuid(canonical);
		when(artifactCanonicalMapRepository.findByArtifactUuid(artifact)).thenReturn(Optional.of(own));
		when(artifactCanonicalMapRepository.requeueFanOutForCanonical(org, canonical)).thenReturn(3);

		assertEquals(3, service.requeueFanOutForArtifact(artifactOf(ArtifactType.BOM)));
		verify(artifactCanonicalMapRepository).requeueFanOutForCanonical(org, canonical);
	}

	@Test
	void anUnmappedBomIsRefusedAndNothingIsRequeued() {
		when(artifactCanonicalMapRepository.findByArtifactUuid(artifact)).thenReturn(Optional.empty());

		RelizaException e = assertThrows(RelizaException.class,
				() -> service.requeueFanOutForArtifact(artifactOf(ArtifactType.BOM)));
		assertTrue(e.getMessage().contains("not mapped"), e.getMessage());
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForCanonical(any(), any());
	}

	@Test
	void anArtifactThatIsNotABomIsRefusedBeforeAnyRead() {
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.requeueFanOutForArtifact(artifactOf(ArtifactType.VEX)));
		assertTrue(e.getMessage().contains("Only a BOM"), e.getMessage());
		verifyNoInteractions(artifactCanonicalMapRepository);
	}
}
