/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.ReleaseRebomRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.service.SbomUpdateGuard.UploadKind;
import io.reliza.service.SbomWriteAuthority;

/**
 * SCORE-52 T-20 (ADR-7): rule B's reads, at the guard with repository spies (own context). An
 * organization-wide principal reads nothing. A refused update stops at the first unwritable holder.
 * An update of S held directly by A probes containers once per row and never resolves a commit; a
 * component's write is asked once per request. A new serialNumber makes one V95 read and one rebom
 * read; a re-upload makes one V95 read and holder reads for the authorizing rows only, stopping at
 * the first unwritable holder; the {@code release_reboms} read happens only on step 2 (b)'s copy
 * branch.
 */
class SbomUpdateGuardHotPathTest extends SbomUpdateGuardTestBase {

	@MockitoSpyBean private ArtifactRepository artifactRepository;
	@MockitoSpyBean private ReleaseRepository releaseRepository;
	@MockitoSpyBean private DeliverableRepository deliverableRepository;
	@MockitoSpyBean private SourceCodeEntryRepository sourceCodeEntryRepository;
	@MockitoSpyBean private ReleaseRebomRepository releaseRebomRepository;

	/** Writes {@code writable} only, and counts every canWrite question. */
	private static final class Counting implements SbomWriteAuthority {
		private final boolean orgWide;
		private final Set<UUID> writable;
		final List<UUID> asked = new ArrayList<>();

		Counting(boolean orgWide, UUID... writable) {
			this.orgWide = orgWide;
			this.writable = Set.of(writable);
		}

		@Override
		public boolean orgWideWrite() {
			return orgWide;
		}

		@Override
		public boolean canWrite(UUID component) {
			asked.add(component);
			return writable.contains(component);
		}

		@Override
		public String describe() {
			return "TEST";
		}
	}

	private void clearSpies() {
		clearInvocations(artifactRepository, releaseRepository, deliverableRepository, sourceCodeEntryRepository,
				releaseRebomRepository, rebomService, guard);
		rebom.latestVersionReads().clear();
	}

	private UploadKind check(World w, UUID serial, int version, SbomWriteAuthority authority) throws RelizaException {
		return updateGuard.refuseUnauthorizedUpdate(w.org().getUuid(), serial, serial.toString(), version, authority,
				"urn:uuid:" + serial);
	}

	private UUID copyRow(UUID org, UUID serial) throws RelizaException {
		ArtifactDto dto = ArtifactDto.builder().org(org).type(ArtifactType.BOM).bomFormat(BomFormat.CYCLONEDX)
				.displayIdentifier("score52-copy-" + UUID.randomUUID())
				.internalBom(new InternalBom(serial, ArtifactBelongsTo.RELEASE)).version("1").build();
		dto.setBomCopy(true);
		return artifactService.createArtifact(dto, WU).getUuid();
	}

	@Test
	void anOrganizationWideUploadReadsNothing() throws Exception {
		World w = world();
		clearSpies();
		assertEquals(UploadKind.AUTHOR, check(w, w.serial(), 2, new Counting(true)));
		verifyNoRuleReads();
		verify(guard, never()).holdersOfArtifact(any(), any());
	}

	/**
	 * None of rule B's reads. Per method, not verifyNoInteractions: a scheduled job of the shared
	 * context may touch a repository spy while a test runs.
	 */
	private void verifyNoRuleReads() throws RelizaException {
		verify(artifactRepository, never()).findBomArtifactsByOrgAndInternalBom(anyString(), anyString());
		verify(artifactRepository, never()).findContainingArtifacts(anyString());
		verify(releaseRepository, never()).findReleasesHoldingArtifact(anyString());
		verify(releaseRepository, never()).findReleasesSharingDeliverableArtifact(anyString());
		verify(releaseRepository, never()).findReleaseBySce(any(), any());
		verify(deliverableRepository, never()).findDeliverablesByArtifact(anyString());
		verify(sourceCodeEntryRepository, never()).findSceArtifactHolders(anyString());
		verify(releaseRebomRepository, never()).existsListingRebomId(anyString());
		verify(rebomService, never()).latestStoredBomVersion(any(), any());
	}

	@Test
	void aRefusedUpdateStopsAtTheFirstUnwritableHolder() throws Exception {
		World w = world();
		listDirectly(w.releaseB(), w.x());
		Counting none = new Counting(false);
		clearSpies();
		assertThrows(SbomUpdateNotPermittedException.class, () -> check(w, w.serial(), 2, none));
		assertEquals(1, none.asked.size(), none.asked.toString());
	}

	@Test
	void aDirectlyHeldUpdateProbesContainersOncePerRowAndResolvesNoCommit() throws Exception {
		World w = world();
		Counting writesA = new Counting(false, w.a().getUuid());
		clearSpies();
		assertEquals(UploadKind.AUTHOR, check(w, w.serial(), 2, writesA));
		verify(artifactRepository, times(1)).findContainingArtifacts(anyString());
		verify(releaseRepository, never()).findReleaseBySce(any(), any());
	}

	@Test
	void aComponentsWriteIsAskedOncePerRequest() throws Exception {
		World w = world();
		UUID secondRow = bomArtifact(w.org().getUuid(), w.serial(), "1");
		release(w.org(), w.a(), List.of(secondRow));
		Counting writesA = new Counting(false, w.a().getUuid());
		clearSpies();
		assertEquals(UploadKind.AUTHOR, check(w, w.serial(), 2, writesA));
		assertEquals(List.of(w.a().getUuid()), writesA.asked);
		verify(guard, times(2)).holdersOfArtifact(any(), any());
	}

	@Test
	void aNewSerialNumberMakesOneV95ReadAndOneRebomRead() throws Exception {
		World w = world();
		UUID fresh = UUID.randomUUID();
		clearSpies();
		assertEquals(UploadKind.AUTHOR, check(w, fresh, 1, new Counting(false)));
		verify(artifactRepository, times(1)).findBomArtifactsByOrgAndInternalBom(anyString(), anyString());
		verify(rebomService, times(1)).latestStoredBomVersion(any(), any());
		verify(releaseRebomRepository, never()).existsListingRebomId(anyString());
		verify(guard, never()).holdersOfArtifact(any(), any());
	}

	@Test
	void aReUploadReadsTheAuthorizingRowsHoldersOnlyAndNoRebom() throws Exception {
		World w = world();
		UUID copy = copyRow(w.org().getUuid(), w.serial());
		listDirectly(w.releaseB(), copy);
		clearSpies();
		assertEquals(UploadKind.AUTHOR, check(w, w.serial(), 1, new Counting(false, w.a().getUuid())));
		verify(artifactRepository, times(1)).findBomArtifactsByOrgAndInternalBom(anyString(), anyString());
		verify(rebomService, never()).latestStoredBomVersion(any(), any());
		verify(releaseRebomRepository, never()).existsListingRebomId(anyString());
		verify(guard, times(1)).holdersOfArtifact(any(), eq(w.x()));
		verify(guard, never()).holdersOfArtifact(any(), eq(copy));
	}

	@Test
	void aReUploadsWalkStopsAtTheFirstAuthorizingHolderItCannotWrite() throws Exception {
		World w = world();
		UUID secondRow = bomArtifact(w.org().getUuid(), w.serial(), "1");
		listDirectly(w.releaseB(), secondRow);
		clearSpies();
		assertEquals(UploadKind.COPY, check(w, w.serial(), 1, new Counting(false)));
		verify(guard, times(1)).holdersOfArtifact(any(), any());
	}

	@Test
	void theReleaseRebomsReadHappensOnlyOnStepTwoCopyBranch() throws Exception {
		World w = world();
		UUID rebomOnly = UUID.randomUUID();
		rebom.storeWithoutArtifact(rebomOnly, 1, cdx("urn:uuid:" + rebomOnly, 1));
		clearSpies();
		assertEquals(UploadKind.AUTHOR, check(w, rebomOnly, 1, new Counting(false)));
		verify(releaseRebomRepository, times(1)).existsListingRebomId(rebomOnly.toString());
	}
}
