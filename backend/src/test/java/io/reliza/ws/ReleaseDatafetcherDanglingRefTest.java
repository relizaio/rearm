/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.model.BranchData;
import io.reliza.model.DeliverableData;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VariantData;
import io.reliza.service.BranchService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.GetSourceCodeEntryService;

/**
 * BUG 6 regression guards for the two Release-parent DGS field resolvers
 * that resolve source-code-entry references:
 * <ul>
 *   <li>{@code Release.sourceCodeEntryDetails} ({@link
 *       ReleaseDatafetcher#sceOfReleaseWithDep}) - a dangling SCE reference
 *       degrades to {@code null} instead of {@code .get()}-throwing
 *       {@link java.util.NoSuchElementException}.</li>
 *   <li>{@code Release.commitsDetails} ({@link
 *       ReleaseDatafetcher#commitsOfReleaseWithDep}) - a missing commit SCE
 *       is skipped rather than throwing on the first empty Optional.</li>
 *   <li>{@code Release.branchDetails} ({@link
 *       ReleaseDatafetcher#branchOfRelease}) - a dangling branch (or an
 *       unresolvable component base branch) degrades to {@code null}
 *       instead of {@code .get()}-throwing.</li>
 *   <li>{@code Release.inboundDeliverableDetails} ({@link
 *       ReleaseDatafetcher#inboundDeliverableDetailsOfReleaseWithDep}) - a
 *       missing inbound deliverable is skipped rather than throwing.</li>
 *   <li>{@code Variant.outboundDeliverableDetails} ({@link
 *       ReleaseDatafetcher#outboundDeliverableDetailsOfVariant}) - a missing
 *       outbound deliverable is skipped rather than throwing.</li>
 * </ul>
 * On the pre-fix code a missing row surfaced as a SERVICE_ERROR that
 * failed the WHOLE release query.
 */
class ReleaseDatafetcherDanglingRefTest {

	private GetSourceCodeEntryService getSourceCodeEntryService;
	private BranchService branchService;
	private GetDeliverableService getDeliverableService;
	private ReleaseDatafetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		branchService = mock(BranchService.class);
		getDeliverableService = mock(GetDeliverableService.class);
		fetcher = new ReleaseDatafetcher();
		inject("getSourceCodeEntryService", getSourceCodeEntryService);
		inject("branchService", branchService);
		inject("getDeliverableService", getDeliverableService);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = ReleaseDatafetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	private static DgsDataFetchingEnvironment dfeFor(Object source) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(source);
		return dfe;
	}

	private static ReleaseData releaseData(UUID uuid) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", uuid);
		return rd;
	}

	private static SourceCodeEntryData sced(UUID uuid) throws Exception {
		// SourceCodeEntryData has a private no-arg constructor and private
		// setters; instantiate reflectively and stamp the uuid the same way.
		var ctor = SourceCodeEntryData.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		SourceCodeEntryData sced = ctor.newInstance();
		ReflectionTestUtils.setField(sced, "uuid", uuid);
		return sced;
	}

	private static BranchData branchData(UUID uuid) throws Exception {
		// BranchData has a private no-arg constructor; instantiate reflectively
		// and stamp the uuid the same way the sced() helper does.
		var ctor = BranchData.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		BranchData bd = ctor.newInstance();
		ReflectionTestUtils.setField(bd, "uuid", uuid);
		return bd;
	}

	private static DeliverableData deliverableData(UUID uuid) throws Exception {
		var ctor = DeliverableData.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		DeliverableData dd = ctor.newInstance();
		ReflectionTestUtils.setField(dd, "uuid", uuid);
		return dd;
	}

	private static VariantData variantData(UUID uuid, List<UUID> outbound) throws Exception {
		var ctor = VariantData.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		VariantData vd = ctor.newInstance();
		ReflectionTestUtils.setField(vd, "uuid", uuid);
		// outboundDeliverables is a Set<UUID>; a LinkedHashSet keeps iteration
		// order deterministic for the present/missing assertions below.
		ReflectionTestUtils.setField(vd, "outboundDeliverables", new LinkedHashSet<>(outbound));
		return vd;
	}

	// ---------------- sourceCodeEntryDetails ----------------

	@Test
	void sceReturnsNullWhenReferenceAbsentAndNeverCallsService() {
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setSourceCodeEntry(null);

		assertNull(fetcher.sceOfReleaseWithDep(dfeFor(rd)));
		verify(getSourceCodeEntryService, never()).getSourceCodeEntryData(any());
	}

	@Test
	void sceReturnsResolvedEntryWhenPresent() throws Exception {
		UUID sceUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setSourceCodeEntry(sceUuid);
		SourceCodeEntryData present = sced(sceUuid);
		when(getSourceCodeEntryService.getSourceCodeEntryData(sceUuid))
				.thenReturn(Optional.of(present));

		assertSame(present, fetcher.sceOfReleaseWithDep(dfeFor(rd)));
	}

	@Test
	void sceDegradesMissingReferenceToNullWithoutThrowing() {
		// BUG 6 core guard: the referenced SCE row is gone. Pre-fix this
		// path did osced.get() on an empty Optional and threw
		// NoSuchElementException, failing the whole release query.
		UUID sceUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setSourceCodeEntry(sceUuid);
		when(getSourceCodeEntryService.getSourceCodeEntryData(sceUuid))
				.thenReturn(Optional.empty());

		SourceCodeEntryData[] holder = new SourceCodeEntryData[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.sceOfReleaseWithDep(dfeFor(rd)));
		assertNull(holder[0], "A dangling SCE reference must degrade to null, not throw");
	}

	// ---------------- commitsDetails ----------------

	@Test
	void commitsReturnsEmptyListWhenNoCommits() {
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setCommits(null);
		assertTrue(fetcher.commitsOfReleaseWithDep(dfeFor(rd)).isEmpty());

		rd.setCommits(List.of());
		assertTrue(fetcher.commitsOfReleaseWithDep(dfeFor(rd)).isEmpty());
		verify(getSourceCodeEntryService, never()).getSourceCodeEntryData(any());
	}

	@Test
	void commitsSkipsMissingSceAndReturnsOnlyResolvedOnes() throws Exception {
		// One resolvable commit + one dangling commit reference. Pre-fix the
		// dangling one .get()-threw on the first empty Optional and dropped
		// the entire list; now it is skipped and the present one survives.
		UUID presentUuid = UUID.randomUUID();
		UUID missingUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setCommits(List.of(presentUuid, missingUuid));
		SourceCodeEntryData present = sced(presentUuid);
		when(getSourceCodeEntryService.getSourceCodeEntryData(presentUuid))
				.thenReturn(Optional.of(present));
		when(getSourceCodeEntryService.getSourceCodeEntryData(missingUuid))
				.thenReturn(Optional.empty());

		List<SourceCodeEntryData>[] holder = new List[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.commitsOfReleaseWithDep(dfeFor(rd)));
		assertEquals(1, holder[0].size(), "Only the resolvable commit SCE should be returned");
		assertSame(present, holder[0].get(0));
	}

	@Test
	void commitsReturnsEmptyWhenAllCommitsMissing() {
		// Every commit reference dangles. Pre-fix the first empty Optional
		// .get()-threw and failed the whole release query; now the list simply
		// comes back empty.
		UUID missingA = UUID.randomUUID();
		UUID missingB = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setCommits(List.of(missingA, missingB));
		when(getSourceCodeEntryService.getSourceCodeEntryData(missingA)).thenReturn(Optional.empty());
		when(getSourceCodeEntryService.getSourceCodeEntryData(missingB)).thenReturn(Optional.empty());

		List<SourceCodeEntryData>[] holder = new List[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.commitsOfReleaseWithDep(dfeFor(rd)));
		assertTrue(holder[0].isEmpty(), "All-missing commit references must degrade to an empty list");
	}

	@Test
	void commitsPreserveOrderAcrossSkippedMiddleReference() {
		// [present_a, missing, present_b] must come back as [a, b] in that
		// order: the skip must not disturb the surviving elements' ordering.
		UUID uuidA = UUID.randomUUID();
		UUID uuidMissing = UUID.randomUUID();
		UUID uuidB = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setCommits(Arrays.asList(uuidA, uuidMissing, uuidB));

		SourceCodeEntryData[] holder = new SourceCodeEntryData[2];
		try {
			holder[0] = sced(uuidA);
			holder[1] = sced(uuidB);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		when(getSourceCodeEntryService.getSourceCodeEntryData(uuidA)).thenReturn(Optional.of(holder[0]));
		when(getSourceCodeEntryService.getSourceCodeEntryData(uuidMissing)).thenReturn(Optional.empty());
		when(getSourceCodeEntryService.getSourceCodeEntryData(uuidB)).thenReturn(Optional.of(holder[1]));

		List<SourceCodeEntryData>[] out = new List[1];
		assertDoesNotThrow(() -> out[0] = fetcher.commitsOfReleaseWithDep(dfeFor(rd)));
		assertEquals(2, out[0].size(), "Only the two resolvable commit SCEs should survive");
		assertSame(holder[0], out[0].get(0), "present_a must remain first");
		assertSame(holder[1], out[0].get(1), "present_b must remain second, after the skipped middle ref");
	}

	// ---------------- branchDetails ----------------

	@Test
	void branchReturnsResolvedBranchWhenPresent() throws Exception {
		UUID branchUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "branch", branchUuid);
		BranchData present = branchData(branchUuid);
		when(branchService.getBranchData(branchUuid)).thenReturn(Optional.of(present));

		assertSame(present, fetcher.branchOfRelease(dfeFor(rd)));
		verify(branchService, never()).getBaseBranchOfComponent(any());
	}

	@Test
	void branchDegradesMissingBranchReferenceToNullWithoutThrowing() {
		// BUG 6 core guard: the release points at a branch row that is gone.
		// Pre-fix this path did obd.get() on an empty Optional and threw
		// NoSuchElementException, failing the whole release query.
		UUID branchUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "branch", branchUuid);
		when(branchService.getBranchData(branchUuid)).thenReturn(Optional.empty());

		BranchData[] holder = new BranchData[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.branchOfRelease(dfeFor(rd)));
		assertNull(holder[0], "A dangling branch reference must degrade to null, not throw");
	}

	@Test
	void branchDegradesUnresolvableBaseBranchToNullWithoutThrowing() {
		// No direct branch on the release, so it falls back to the component
		// base branch, which is also unresolvable. Pre-fix this path did
		// obaseBranch.get() and threw before branchDataFromDbRecord.
		UUID componentUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "component", componentUuid);
		when(branchService.getBaseBranchOfComponent(componentUuid)).thenReturn(Optional.empty());

		BranchData[] holder = new BranchData[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.branchOfRelease(dfeFor(rd)));
		assertNull(holder[0], "An unresolvable component base branch must degrade to null, not throw");
		verify(branchService, never()).getBranchData(any());
	}

	// ---------------- inboundDeliverableDetails ----------------

	@Test
	void inboundReturnsEmptyListWhenNoDeliverables() {
		// The getInboundDeliverables() accessor defensively copies its backing
		// list, so it can never itself return null; the reachable "nothing to
		// resolve" case is an empty deliverable list, which must yield an empty
		// result and never touch the deliverable service.
		ReleaseData rd = releaseData(UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "inboundDeliverables", List.of());

		assertTrue(fetcher.inboundDeliverableDetailsOfReleaseWithDep(dfeFor(rd)).isEmpty());
		verify(getDeliverableService, never()).getDeliverableData(any());
	}

	@Test
	void inboundSkipsMissingDeliverableAndReturnsOnlyResolvedOnes() throws Exception {
		UUID presentUuid = UUID.randomUUID();
		UUID missingUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "inboundDeliverables", Arrays.asList(presentUuid, missingUuid));
		DeliverableData present = deliverableData(presentUuid);
		when(getDeliverableService.getDeliverableData(presentUuid)).thenReturn(Optional.of(present));
		when(getDeliverableService.getDeliverableData(missingUuid)).thenReturn(Optional.empty());

		List<DeliverableData>[] holder = new List[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.inboundDeliverableDetailsOfReleaseWithDep(dfeFor(rd)));
		assertEquals(1, holder[0].size(), "Only the resolvable inbound deliverable should be returned");
		assertSame(present, holder[0].get(0));
	}

	@Test
	void inboundReturnsEmptyWhenAllDeliverablesMissing() {
		// Every inbound deliverable reference dangles. Pre-fix the first empty
		// Optional .get()-threw and failed the whole release query.
		UUID missingA = UUID.randomUUID();
		UUID missingB = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "inboundDeliverables", Arrays.asList(missingA, missingB));
		when(getDeliverableService.getDeliverableData(missingA)).thenReturn(Optional.empty());
		when(getDeliverableService.getDeliverableData(missingB)).thenReturn(Optional.empty());

		List<DeliverableData>[] holder = new List[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.inboundDeliverableDetailsOfReleaseWithDep(dfeFor(rd)));
		assertTrue(holder[0].isEmpty(), "All-missing inbound deliverables must degrade to an empty list");
	}

	// ---------------- Variant.outboundDeliverableDetails ----------------

	@Test
	void outboundSkipsMissingDeliverableAndReturnsOnlyResolvedOnes() throws Exception {
		// Source here is a VariantData, not a ReleaseData. One resolvable +
		// one dangling outbound deliverable: pre-fix the dangling one
		// .get()-threw and failed the whole enclosing release query.
		UUID presentUuid = UUID.randomUUID();
		UUID missingUuid = UUID.randomUUID();
		VariantData vd = variantData(UUID.randomUUID(), Arrays.asList(presentUuid, missingUuid));
		DeliverableData present = deliverableData(presentUuid);
		when(getDeliverableService.getDeliverableData(presentUuid)).thenReturn(Optional.of(present));
		when(getDeliverableService.getDeliverableData(missingUuid)).thenReturn(Optional.empty());

		List<DeliverableData>[] holder = new List[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.outboundDeliverableDetailsOfVariant(dfeFor(vd)));
		assertEquals(1, holder[0].size(), "Only the resolvable outbound deliverable should be returned");
		assertSame(present, holder[0].get(0));
	}
}
