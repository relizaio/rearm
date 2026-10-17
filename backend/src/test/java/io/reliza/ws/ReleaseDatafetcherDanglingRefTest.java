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
import static org.mockito.Mockito.times;
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

import io.reliza.model.AgentSessionData;
import io.reliza.model.BranchData;
import io.reliza.model.DeliverableData;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VariantData;
import io.reliza.service.BranchService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.SharedReleaseService;

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
 *
 * <p>The source-code resolvers (and sessions, intermediateFailedReleases) read
 * through the org-scoped GetSourceCodeEntryService.getReferenceableSceData[List]
 * with the release's org, so an entry owned by another organization degrades
 * the same way a missing one does; the scoping itself is pinned against the
 * database in ReleaseReferenceOrgValidationTest.
 */
class ReleaseDatafetcherDanglingRefTest {

	/** The org every release and entry below belongs to unless a test says otherwise. */
	private static final UUID ORG = UUID.randomUUID();

	private GetSourceCodeEntryService getSourceCodeEntryService;
	private SharedReleaseService sharedReleaseService;
	private BranchService branchService;
	private GetDeliverableService getDeliverableService;
	private ReleaseDatafetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		branchService = mock(BranchService.class);
		getDeliverableService = mock(GetDeliverableService.class);
		fetcher = new ReleaseDatafetcher();
		inject("getSourceCodeEntryService", getSourceCodeEntryService);
		inject("sharedReleaseService", sharedReleaseService);
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
		ReflectionTestUtils.setField(rd, "org", ORG);
		return rd;
	}

	private static SourceCodeEntryData sced(UUID uuid) throws Exception {
		return sced(uuid, ORG);
	}

	private static SourceCodeEntryData sced(UUID uuid, UUID org) throws Exception {
		// SourceCodeEntryData has a private no-arg constructor and private
		// setters; instantiate reflectively and stamp the uuid the same way.
		var ctor = SourceCodeEntryData.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		SourceCodeEntryData sced = ctor.newInstance();
		ReflectionTestUtils.setField(sced, "uuid", uuid);
		ReflectionTestUtils.setField(sced, "org", org);
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
		verify(getSourceCodeEntryService, never()).getReferenceableSceData(any(), any());
	}

	@Test
	void sceReturnsTheEntryResolvedForTheReleasesOrg() throws Exception {
		UUID sceUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setSourceCodeEntry(sceUuid);
		SourceCodeEntryData present = sced(sceUuid);
		when(getSourceCodeEntryService.getReferenceableSceData(sceUuid, ORG))
				.thenReturn(Optional.of(present));

		assertSame(present, fetcher.sceOfReleaseWithDep(dfeFor(rd)));
		verify(getSourceCodeEntryService, never()).getSourceCodeEntryData(any());
	}

	@Test
	void sceDegradesMissingOrOtherOrgReferenceToNullWithoutThrowing() {
		// BUG 6 core guard: the referenced SCE row is gone (or, since the org-scoped read, belongs
		// to another organization). Pre-fix this path did osced.get() on an empty Optional and threw
		// NoSuchElementException, failing the whole release query.
		UUID sceUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setSourceCodeEntry(sceUuid);
		when(getSourceCodeEntryService.getReferenceableSceData(sceUuid, ORG))
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
		verify(getSourceCodeEntryService, never()).getReferenceableSceDataList(any(), any());
	}

	@Test
	void commitsAreTheOrgScopedReadOfTheReleasesCommits() throws Exception {
		// The org-scoped batch read is what drops missing and other-org entries and keeps order
		// (pinned against the database in ReleaseReferenceOrgValidationTest); the resolver must hand
		// it the release's own commits and org and return its result as is.
		UUID presentUuid = UUID.randomUUID();
		UUID droppedUuid = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setCommits(List.of(presentUuid, droppedUuid));
		SourceCodeEntryData present = sced(presentUuid);
		when(getSourceCodeEntryService.getReferenceableSceDataList(List.of(presentUuid, droppedUuid), ORG))
				.thenReturn(List.of(present));

		List<SourceCodeEntryData>[] holder = new List[1];
		assertDoesNotThrow(() -> holder[0] = fetcher.commitsOfReleaseWithDep(dfeFor(rd)));
		assertEquals(List.of(present), holder[0]);
		verify(getSourceCodeEntryService, never()).getSourceCodeEntryData(any());
	}

	// ---------------- sessions ----------------

	@Test
	void sessionsFollowOnlyTheOrgScopedEntries() throws Exception {
		AgentSessionService agentSessionService = mock(AgentSessionService.class);
		inject("agentSessionService", agentSessionService);
		UUID ownUuid = UUID.randomUUID();
		UUID foreignUuid = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		rd.setCommits(List.of(foreignUuid, ownUuid));
		SourceCodeEntryData own = sced(ownUuid);
		ReflectionTestUtils.setField(own, "agentSession", session);
		when(getSourceCodeEntryService.getReferenceableSceDataList(List.of(foreignUuid, ownUuid), ORG))
				.thenReturn(List.of(own));
		AgentSessionData sd = mock(AgentSessionData.class);
		when(agentSessionService.getSessionData(session)).thenReturn(Optional.of(sd));

		assertEquals(List.of(sd), fetcher.sessionsOfRelease(dfeFor(rd)));
		verify(getSourceCodeEntryService, never()).getSourceCodeEntryData(any());
	}

	// ---------------- intermediateFailedReleases ----------------

	@Test
	void intermediateFailedReleasesReadAllCommitsOnceScopedToTheViewedReleasesOrg() throws Exception {
		// One org-scoped read for every failed release's entries, split back per release in each
		// release's own order (source code entry first); what the read leaves out is dropped.
		UUID sceA = UUID.randomUUID();
		UUID commitA = UUID.randomUUID();
		UUID sceB = UUID.randomUUID();
		UUID droppedB = UUID.randomUUID();
		ReleaseData rd = releaseData(UUID.randomUUID());
		ReleaseData failedA = releaseData(UUID.randomUUID());
		failedA.setSourceCodeEntry(sceA);
		failedA.setCommits(List.of(commitA));
		ReleaseData failedB = releaseData(UUID.randomUUID());
		failedB.setSourceCodeEntry(sceB);
		failedB.setCommits(List.of(droppedB, sceB));
		when(sharedReleaseService.findIntermediateFailedReleases(rd)).thenReturn(List.of(failedA, failedB));
		SourceCodeEntryData a1 = sced(sceA);
		SourceCodeEntryData a2 = sced(commitA);
		SourceCodeEntryData b1 = sced(sceB);
		when(getSourceCodeEntryService.getReferenceableSceDataList(
				new LinkedHashSet<>(List.of(sceA, commitA, sceB, droppedB)), ORG)).thenReturn(List.of(b1, a2, a1));

		var out = fetcher.intermediateFailedReleasesOfRelease(dfeFor(rd));
		assertEquals(2, out.size());
		assertEquals(List.of(a1, a2), out.get(0).commits());
		assertEquals(List.of(b1), out.get(1).commits());
		verify(getSourceCodeEntryService, times(1)).getReferenceableSceDataList(any(), any());
		verify(getSourceCodeEntryService, never()).getSourceCodeEntryData(any());
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
