/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.BranchSuffixMode;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.repositories.VersionAssignmentRepository;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.versioning.Version.VersionStringComparator;
import io.reliza.ws.oss.TestInitializer;

/**
 * Concurrent version assignment on one branch.
 *
 * <p>20 parallel getversion calls on one branch used to fail as "Not authorized" (the retry gave
 * up after three collisions) and to issue chained "-0-0-0" suffixes, some semver-lower than a
 * version already issued: concurrent mints read the same latest version, collided, and the retry
 * fell through to the "-N" suffix fallback. Mints on one component are now serialized, and an
 * issued version is never below one already issued on the branch.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class VersionAssignmentConcurrencyTest {

	private static final int CONCURRENT_MINTS = 20;

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VersionAssignmentService versionAssignmentService;
	@Autowired private VersionAssignmentRepository versionAssignmentRepository;
	@Autowired private TestInitializer testInitializer;

	private Component semverComponent(String name) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		return componentService.createComponent(name + "_" + UUID.randomUUID().toString().substring(0, 8),
				org.getUuid(), ComponentType.COMPONENT, "semver", "semver", null, WhoUpdated.getTestWhoUpdated());
	}

	@Test
	public void concurrentMintsOnOneBranchAreDistinctIncreasingAndUnsuffixed() throws Exception {
		Component comp = semverComponent("concurrentMints");
		Branch base = branchService.getBaseBranchOfComponent(comp.getUuid()).get();

		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_MINTS);
		CountDownLatch startTogether = new CountDownLatch(1);
		List<Future<VersionAssignment>> results = new ArrayList<>();
		try {
			for (int i = 0; i < CONCURRENT_MINTS; i++) {
				results.add(pool.submit((Callable<VersionAssignment>) () -> {
					startTogether.await();
					// The getversion path's variant: it is the one that surfaced "Not authorized".
					return versionAssignmentService.getSetNewVersionWrapper(base.getUuid(), null, null, null,
							VersionTypeEnum.DEV, null, false).orElseThrow();
				}));
			}
			startTogether.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "mints did not finish");
		} finally {
			pool.shutdownNow();
		}
		List<VersionAssignment> minted = new ArrayList<>();
		for (Future<VersionAssignment> f : results) minted.add(f.get());

		List<String> expected = IntStream.range(0, CONCURRENT_MINTS).mapToObj(i -> "0.0." + i).toList();
		List<String> byVersion = minted.stream().map(VersionAssignment::getVersion)
				.sorted(new VersionStringComparator("semver").reversed()).toList();
		assertEquals(expected, byVersion, "20 distinct versions, one apart, none suffixed");
		// Strictly increasing in the order they were issued, not just as a set.
		List<VersionAssignment> byIssue = new ArrayList<>(minted);
		byIssue.sort(Comparator.comparing(VersionAssignment::getCreatedDate));
		assertEquals(expected, byIssue.stream().map(VersionAssignment::getVersion).toList(),
				"each mint above every one issued before it");
	}

	@Test
	public void concurrentMintsOnTwoBranchesSharingOneSequenceAreAllDistinct() throws Exception {
		// NO_APPEND branches share the component's version sequence; versions are unique per
		// component, so the two branches race each other as much as themselves.
		Component comp = pinnedNoAppendComponent();
		Branch base = branchService.getBaseBranchOfComponent(comp.getUuid()).get();
		Branch foo = branchService.createBranch("foo", comp.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		pin(foo, "semver");

		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_MINTS);
		CountDownLatch startTogether = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		try {
			for (int i = 0; i < CONCURRENT_MINTS; i++) {
				Branch b = i % 2 == 0 ? base : foo;
				results.add(pool.submit((Callable<String>) () -> {
					startTogether.await();
					return mint(b);
				}));
			}
			startTogether.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "mints did not finish");
		} finally {
			pool.shutdownNow();
		}
		List<String> versions = new ArrayList<>();
		for (Future<String> f : results) versions.add(f.get());
		versions.sort(new VersionStringComparator("semver").reversed());
		assertEquals(IntStream.range(0, CONCURRENT_MINTS).mapToObj(i -> "0.0." + i).toList(), versions,
				"one shared sequence, no version lost to a collision, none suffixed");
	}

	/**
	 * A date-only calendar schema cannot move within a day, so the second mint of the day takes
	 * the "-N" counter. That is the designed outcome, not a collision to refuse.
	 */
	@Test
	public void aDateOnlyCalendarSchemaStillCountsWithinADay() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component comp = componentService.createComponent("dateOnly_" + UUID.randomUUID().toString().substring(0, 8),
				org.getUuid(), ComponentType.COMPONENT, "YYYY.0M.0D", "YYYY.0M.0D", null, WhoUpdated.getTestWhoUpdated());
		Branch base = branchService.getBaseBranchOfComponent(comp.getUuid()).get();
		String first = mint(base);
		assertEquals(first + "-0", mint(base));
		assertEquals(first + "-1", mint(base));
	}

	/**
	 * The suffix fallback stays for what it is for: a base version taken by ANOTHER branch of the
	 * component. Two NO_APPEND branches on one Branch-free schema derive the same version; the
	 * second takes the next "-N".
	 */
	@Test
	public void aCollisionWithAnotherBranchStillTakesASuffix() throws RelizaException {
		Component comp = pinnedNoAppendComponent();
		Branch base = branchService.getBaseBranchOfComponent(comp.getUuid()).get();
		pin(base, "1.2.3");
		Branch foo = branchService.createBranch("foo", comp.getUuid(), BranchType.FEATURE, WhoUpdated.getTestWhoUpdated());
		pin(foo, "1.2.3");

		assertEquals("1.2.3", mint(base));
		String fooVersion = mint(foo);
		assertTrue(fooVersion.startsWith("1.2.3-"), "taken by the base branch, so a suffixed version: " + fooVersion);
	}

	/**
	 * A version strictly below one the branch already issued is refused, never issued: the next
	 * version the branch's pin derives after its pin moved back below an issued version.
	 */
	@Test
	public void aVersionBelowOneAlreadyIssuedOnTheBranchIsNeverIssued() throws RelizaException {
		Component comp = pinnedNoAppendComponent();
		Branch base = branchService.getBaseBranchOfComponent(comp.getUuid()).get();
		pin(base, "1.2.patch");
		assertEquals("1.2.0", mint(base));
		assertEquals("1.2.1", mint(base));
		pin(base, "1.1.patch");

		RelizaException e = assertThrows(RelizaException.class, () -> versionAssignmentService.getSetNewVersionWrapper(
				base.getUuid(), null, null, null, VersionTypeEnum.DEV, null, false));
		assertTrue(e.getMessage().endsWith(", which is below 1.2.1 already issued on this branch; refusing to"
				+ " issue it"), e.getMessage());
		assertEquals(2, versionAssignmentRepository.findVersionAssignmentsByComponentAndVersionLike(
				comp.getUuid(), VersionTypeEnum.DEV.name(), "1.%").size(), "nothing else was saved");
	}

	/**
	 * Two concurrent setNextVersion calls both found no OPEN assignment and both inserted one;
	 * every later mint on the branch then failed on the two rows. Serialized by the same lock.
	 */
	@Test
	public void concurrentSetNextVersionLeavesOneOpenAssignment() throws Exception {
		Component comp = semverComponent("concurrentSetNext");
		Branch base = branchService.getBaseBranchOfComponent(comp.getUuid()).get();
		assertEquals("0.0.0", mint(base));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch startTogether = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		List<Throwable> refusals = Collections.synchronizedList(new ArrayList<>());
		try {
			for (String next : List.of("0.5.0", "0.6.0")) {
				results.add(pool.submit(() -> {
					startTogether.await();
					try {
						return versionAssignmentService.setNextVesion(base.getUuid(), next, VersionTypeEnum.DEV);
					} catch (RelizaException e) {
						// The second may be refused as not greater than the first's OPEN version.
						refusals.add(e);
						return false;
					}
				}));
			}
			startTogether.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		for (Future<Boolean> f : results) f.get();

		// Serialized, either order ends with one OPEN 0.6.0: 0.6.0 after 0.5.0 replaces it, and 0.5.0
		// after 0.6.0 is refused as not greater. Two OPEN rows made this mint throw.
		assertEquals("0.6.0", mint(base), "the mint takes the one OPEN version");
		assertEquals("0.6.1", mint(base), "and minting carries on from it");
	}

	private Component pinnedNoAppendComponent() throws RelizaException {
		Component comp = semverComponent("pinned");
		componentService.updateComponent(ComponentDto.builder()
				.uuid(comp.getUuid())
				.branchSuffixMode(BranchSuffixMode.NO_APPEND)
				.build(), WhoUpdated.getTestWhoUpdated());
		return comp;
	}

	private void pin(Branch b, String schema) throws RelizaException {
		branchService.updateBranch(BranchDto.builder().uuid(b.getUuid()).versionSchema(schema).build(),
				WhoUpdated.getTestWhoUpdated());
	}

	private String mint(Branch b) throws RelizaException {
		return versionAssignmentService.getSetNewVersionWrapper(b.getUuid(), null, null, null, VersionTypeEnum.DEV,
				null, false).orElseThrow().getVersion();
	}
}
