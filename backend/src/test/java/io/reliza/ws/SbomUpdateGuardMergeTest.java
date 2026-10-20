/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.GraphQLError;
import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ApiKey;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-52 T-29 (round 3, F-4): merge serialNumbers and rebom-only serialNumbers. A stored release
 * or product merge M is listed by a {@code release_reboms} row and recorded by no artifact: its new
 * version is refused to every principal that is not organization-wide (no holder authorizes it),
 * and passes for one with no read. A verbatim copy of M is a copy. A serialNumber rebom stored for a
 * request that rolled back is a first upload for its retry and authors. A coverage-filtered merge is
 * in no {@code release_reboms} row, so a copy of it authors. T-29 (g) is
 * SbomUpdateGuardDefenceInDepthTest's failed and malformed reads; (h) is
 * SbomUpdateGuardPaddedSerialNumberTest's trimmed read.
 */
class SbomUpdateGuardMergeTest extends SbomUpdateGuardProgrammaticTestBase {

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	/** rebom merges into {@code merged}, stored at version 1 and recorded by no artifact. */
	private void rebomMergesInto(UUID merged) {
		rebom.storeWithoutArtifact(merged, 1, cdx(urn(merged), 1));
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenReturn(merged);
	}

	/** Exports {@code release}'s merged SBOM, which stores a release_reboms row unless filtered. */
	private void export(World w, UUID release, List<ArtifactCoverageType> exclude) throws Exception {
		releaseService.exportReleaseSbom(release, false, false, null, BomStructureType.FLAT, BomMediaType.JSON,
				w.org().getUuid(), WU, exclude);
	}

	private long releaseRebomRowsListing(UUID merged) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.release_reboms WHERE record_data::text LIKE ?",
				Long.class, "%" + merged + "%");
	}

	/** A's release has a stored merge M. */
	private UUID storedMergeOfA(World w) throws Exception {
		UUID m = UUID.randomUUID();
		rebomMergesInto(m);
		export(w, w.releaseA(), null);
		assertEquals(1, releaseRebomRowsListing(m));
		return m;
	}

	private void refusedEverywhereButOrganizationWide(World w, UUID m, UUID heldBy) throws Exception {
		ApiKey keyB = componentKey(w, w.b());
		assertRefusedUpload(() -> progUpload(keyB, w.releaseB(), cdx(urn(m), 2)));
		List<ReleaseUpdateEvent> events = guardEvents(w.releaseB());
		assertEquals(1, events.size(), events.toString());

		Map<String, Object> viaRelease = releaseInputOf(w.b(), "52.29." + UUID.randomUUID());
		viaRelease.put("artifacts", entries(cdx(urn(m), 2)));
		assertRefusedUpload(() -> progReleases(keyB, viaRelease));

		GraphQLError error = onlyError(manualUpload(writerOn(w.org(), w.a().getUuid(), w.b().getUuid()),
				w.releaseB(), cdx(urn(m), 2)));
		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"), error.toString());
		assertRefusedUpload(() -> progUpload(componentKey(w, w.a()), heldBy, cdx(urn(m), 2)));
		assertEquals(0, rebom.versionsStored(m));
	}

	// (a)
	@Test
	void aStoredReleaseMergesNextVersionIsRefusedToEveryHolderWriter() throws Exception {
		World w = world();
		UUID m = storedMergeOfA(w);

		refusedEverywhereButOrganizationWide(w, m, w.releaseA());

		clearInvocations(rebomService);
		export(w, w.releaseA(), null);
		verify(rebomService, atLeastOnce()).findBomByIdJson(eq(m), any());
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	// (b)
	@Test
	void anOrganizationWidePrincipalPassesWithNoRead() throws Exception {
		World w = world();
		UUID m = storedMergeOfA(w);
		rebom.latestVersionReads().clear();
		clearInvocations(guard);

		progUpload(orgRwKey(w), w.releaseB(), cdx(urn(m), 2));
		assertTrue(manualUpload(member(w.org(), PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.ADMIN),
				w.releaseB(), cdx(urn(m), 3)).getErrors().isEmpty());

		assertEquals(List.of(), rebom.latestVersionReads());
		verify(guard, never()).holdersOfArtifact(any(), any());
		assertEquals(2, rebom.versionsStored(m));
	}

	// (c)
	@Test
	void aVerbatimCopyOfAMergeIsACopyAndItsNextVersionIsRefused() throws Exception {
		World w = world();
		UUID m = storedMergeOfA(w);
		ApiKey keyB = componentKey(w, w.b());

		progUpload(keyB, w.releaseB(), cdx(urn(m), 1));
		assertTrue(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		assertRefusedUpload(() -> progUpload(keyB, w.releaseB(), cdx(urn(m), 2)));
	}

	// (d)
	@Test
	void aStoredProductMergesNextVersionIsRefused() throws Exception {
		World w = world();
		Component product = componentService.createComponent("score52-product-" + UUID.randomUUID(),
				w.org().getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		UUID p = ossReleaseService.createRelease(ReleaseDto.builder().component(product.getUuid())
				.org(w.org().getUuid()).branch(branchService.getBaseBranchOfComponent(product.getUuid()).orElseThrow()
						.getUuid()).version("52.29.d").status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.parentReleases(new ArrayList<>(List.of(ParentRelease.minimalParentReleaseFactory(w.releaseA()))))
				.build(), WU).getUuid();
		UUID m2 = UUID.randomUUID();
		rebomMergesInto(m2);
		export(w, p, null);
		assertTrue(releaseRebomRowsListing(m2) >= 1);

		assertRefusedUpload(() -> progUpload(componentKey(w, w.a()), w.releaseA(), cdx(urn(m2), 2)));
		assertRefusedUpload(() -> progUpload(componentKey(w, product), p, cdx(urn(m2), 2)));
		assertEquals(0, rebom.versionsStored(m2));
	}

	// (e)
	@Test
	void aRolledBackFirstUploadIsAFirstUploadForItsRetry() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		rebom.storeWithoutArtifact(s, 1, cdx(urn(s), 1));

		assertRefusedUpload(() -> progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(s), 2)));
		ApiKey keyA = componentKey(w, w.a());
		progUpload(keyA, w.releaseA(), cdx(urn(s), 1));
		assertFalse(isCopy(addedRow(w.releaseA(), List.of(w.x()))));
		progUpload(keyA, w.releaseA(), cdx(urn(s), 2));
		assertEquals(1, rebom.versionsStored(s));
	}

	// (f)
	@Test
	void aCopyOfACoverageFilteredMergeAuthors() throws Exception {
		World w = world();
		UUID m = UUID.randomUUID();
		rebomMergesInto(m);
		export(w, w.releaseA(), List.of(ArtifactCoverageType.DEV));
		assertEquals(0, releaseRebomRowsListing(m));

		progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(m), 1));
		assertFalse(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
	}
}
