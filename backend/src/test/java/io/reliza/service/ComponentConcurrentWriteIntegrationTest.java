package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.VisibilitySetting;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentLock;
import io.reliza.model.DeclarativeProvenance;

/**
 * TPF-38: two writers of one component both keep their change, and every writer decides from the
 * row as committed. Rows are numbered as in the design (architecture-1 section 4); the rig is in
 * {@link ComponentConcurrentWriteTestBase}.
 */
public class ComponentConcurrentWriteIntegrationTest extends ComponentConcurrentWriteTestBase {

	// ---------- rows 1 to 10: a held first writer, then a second writer of the same component ----------

	@Test
	@Timeout(60)
	public void row01_heldLockThenSettingsSave_keepsLockAndSetting() throws Exception {
		int rev = revision(c), aud = audits(c);
		ComponentLock[] l = new ComponentLock[1];
		held(() -> l[0] = lock(c, "held"), () -> x("race"));
		ComponentData cd = stored();
		assertTrue(activeLock(cd, l[0].uuid()), "the lock raised first was lost to the settings save");
		assertEquals("race", cd.getRepoPath());
		assertEquals(rev + 2, revision(c));
		assertEquals(aud + 2, audits(c));
	}

	@Test
	@Timeout(60)
	public void row02_heldSettingsSaveThenLock_keepsBoth() throws Exception {
		int rev = revision(c);
		ComponentLock[] l = new ComponentLock[1];
		held(() -> x("race"), () -> l[0] = lock(c, "second"));
		ComponentData cd = stored();
		assertTrue(activeLock(cd, l[0].uuid()));
		assertEquals("race", cd.getRepoPath());
		assertEquals(rev + 2, revision(c));
	}

	@Test
	@Timeout(60)
	public void row03_heldPerspectiveRemovalThenSettingsSave_staysRemoved() throws Exception {
		componentService.setPerspectives(c, Set.of(p), WU);
		held(() -> componentService.setPerspectives(c, Set.of(), WU), () -> x("race"));
		ComponentData cd = stored();
		assertTrue(cd.getPerspectives().isEmpty(), "the perspective removal was undone: " + cd.getPerspectives());
		assertEquals("race", cd.getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row04_heldSettingsSaveThenPerspectiveRemoval_keepsBoth() throws Exception {
		componentService.setPerspectives(c, Set.of(p), WU);
		held(() -> x("race"), () -> componentService.setPerspectives(c, Set.of(), WU));
		ComponentData cd = stored();
		assertTrue(cd.getPerspectives().isEmpty());
		assertEquals("race", cd.getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row05_heldArchiveThenSettingsSave_staysArchived() throws Exception {
		held(() -> componentService.archiveComponent(c, WU), () -> x("race"));
		ComponentData cd = stored();
		assertEquals(StatusEnum.ARCHIVED, cd.getStatus(), "the archive was undone by the settings save");
		assertEquals("race", cd.getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row06_heldSettingsSaveThenArchive_keepsBoth() throws Exception {
		held(() -> x("race"), () -> componentService.archiveComponent(c, WU));
		ComponentData cd = stored();
		assertEquals(StatusEnum.ARCHIVED, cd.getStatus());
		assertEquals("race", cd.getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row07_heldProvenanceStampThenSettingsSave_keepsBoth() throws Exception {
		DeclarativeProvenance prov = provenance();
		held(() -> componentService.stampDeclarativeProvenance(c, prov, WU), () -> x("race"));
		ComponentData cd = stored();
		assertEquals(prov.getSpecHash(), null == cd.getDeclarative() ? null : cd.getDeclarative().getSpecHash(),
				"the declarative provenance was lost to the settings save");
		assertEquals("race", cd.getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row08_heldSettingsSaveThenProvenanceStamp_keepsBoth() throws Exception {
		DeclarativeProvenance prov = provenance();
		held(() -> x("race"), () -> componentService.stampDeclarativeProvenance(c, prov, WU));
		ComponentData cd = stored();
		assertEquals(prov.getSpecHash(), cd.getDeclarative().getSpecHash());
		assertEquals("race", cd.getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row09_heldPerspectiveRemovalThenBoardSync_appliesTheDeltaToTheFreshSet() throws Exception {
		componentService.setPerspectives(c, Set.of(p), WU);
		held(() -> componentService.setPerspectives(c, Set.of(), WU),
				() -> boardPerspectiveService.joinDocumentComponent(board(q), c, WU));
		assertEquals(Set.of(q), stored().getPerspectives(), "the board sync put back a perspective removed first");
	}

	@Test
	@Timeout(60)
	public void row10_heldSettingsSaveThenUserScrub_keepsBoth() throws Exception {
		UUID u = newMember(), s = newMember();
		UUID action = seedAction(c, u, s);
		int rev = revision(c);
		held(() -> x("race"), () -> componentService.handleRemoveUserFromTriggers(org, u, WU));
		ComponentData cd = stored();
		assertEquals("race", cd.getRepoPath(), "the user scrub wrote its listed copy over the settings save");
		assertEquals(Set.of(s), actionUsers(cd, action));
		assertEquals(rev + 2, revision(c));
	}

	// ---------- row 11: a plain read is not blocked ----------

	@Test
	@Timeout(60)
	public void row11_plainReadsAreNotBlockedByAWriter() throws Exception {
		x("before");
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = holdUntil(ex, () -> x("held"), release);
			try {
				long t0 = System.nanoTime();
				new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
					versionAssignmentRepository.setLocalLockTimeout("2s");
					assertEquals("before", getComponentService.getComponentData(c).get().getRepoPath());
					assertFalse(componentLockService.isLocked(c, null));
				});
				assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(2), "a read in a transaction waited on the writer");
				long t1 = System.nanoTime();
				assertEquals("before", getComponentService.getComponentData(c).get().getRepoPath());
				assertTrue(System.nanoTime() - t1 < TimeUnit.SECONDS.toNanos(2), "a read with no transaction waited on the writer");
			} finally {
				release.countDown();
			}
			holder.get(30, TimeUnit.SECONDS);
		} finally {
			ex.shutdownNow();
		}
		assertEquals("held", stored().getRepoPath());
	}

	// ---------- rows 12 to 22: every writer re-reads a copy its transaction already holds ----------

	@Test
	@Timeout(60)
	public void row12_settingsSave_keepsALockCommittedAfterThePreRead() {
		ComponentLock[] l = new ComponentLock[1];
		preRead(() -> l[0] = lock(c, "marker"), seen -> x("w"));
		ComponentData cd = stored();
		assertTrue(activeLock(cd, l[0].uuid()), "updateComponent saved the pre-read copy");
		assertEquals("w", cd.getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row13_setPerspectives_rereads() {
		preRead(() -> x("marker"), seen -> componentService.setPerspectives(c, Set.of(p), WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(Set.of(p), stored().getPerspectives());
	}

	@Test
	@Timeout(60)
	public void row14_boardSync_rereads() {
		preRead(() -> x("marker"), seen -> boardPerspectiveService.joinDocumentComponent(board(p), c, WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(Set.of(p), stored().getPerspectives());
	}

	@Test
	@Timeout(60)
	public void row15_archive_rereads() {
		preRead(() -> x("marker"), seen -> componentService.archiveComponent(c, WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(StatusEnum.ARCHIVED, stored().getStatus());
	}

	@Test
	@Timeout(60)
	public void row16_provenanceStamp_rereads() {
		DeclarativeProvenance prov = provenance();
		preRead(() -> x("marker"), seen -> componentService.stampDeclarativeProvenance(c, prov, WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(prov.getSpecHash(), stored().getDeclarative().getSpecHash());
	}

	@Test
	@Timeout(60)
	public void row17_resourceGroup_rereads() {
		preRead(() -> x("marker"), seen -> componentService.updateComponentResourceGroup(c, CommonVariables.DEFAULT_RESOURCE_GROUP, WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(CommonVariables.DEFAULT_RESOURCE_GROUP, stored().getResourceGroup());
	}

	@Test
	@Timeout(60)
	public void row18_versionSchema_rereads() {
		preRead(() -> x("marker"), seen -> componentService.setComponentVersion(c, "Major.Minor", WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals("Major.Minor", stored().getVersionSchema());
	}

	@Test
	@Timeout(60)
	public void row19_visibility_rereads() {
		preRead(() -> x("marker"), seen -> componentService.setComponentVisibility(c, VisibilitySetting.ADMIN_INVITATION, WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(VisibilitySetting.ADMIN_INVITATION, stored().getVisibilitySetting());
	}

	@Test
	@Timeout(60)
	public void row20_vcsRepo_takesOnlyTheVcsFromTheCallersCopy() {
		UUID vcs = newVcs();
		ComponentData[] returned = new ComponentData[1];
		preRead(() -> x("marker"), seen -> returned[0] = componentService.updateComponentVcsRepo(seen, vcs, WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(vcs, stored().getVcs());
		assertEquals("marker", returned[0].getRepoPath());
	}

	@Test
	@Timeout(60)
	public void row21_userScrub_decidesOnTheFreshRow() throws Exception {
		UUID u = newMember(), s = newMember();
		UUID action = seedAction(c, u, s);
		preRead(() -> x("marker"), seen -> componentService.handleRemoveUserFromTriggers(org, u, WU));
		assertEquals("marker", stored().getRepoPath());
		assertEquals(Set.of(s), actionUsers(stored(), action));
	}

	@Test
	@Timeout(60)
	public void row22_setLocks_rereads() {
		preRead(() -> x("marker"), seen -> componentService.setLocks(c, List.of(), WU));
		assertEquals("marker", stored().getRepoPath());
		assertTrue(stored().getLocks().isEmpty());
	}

	// ---------- rows 23 to 25 ----------

	/** Every writer of rows 12 to 22 runs with no transaction of the caller's: the helper needs its own. */
	@Test
	@Timeout(60)
	public void row23_everyWriterRunsWithoutACallerTransaction() throws Exception {
		UUID a = newComponent();
		x(a, "w");
		assertEquals("w", stored(a).getRepoPath());

		UUID b = newComponent();
		componentService.setPerspectives(b, Set.of(p), WU);
		assertEquals(Set.of(p), stored(b).getPerspectives());

		UUID d = newComponent();
		boardPerspectiveService.joinDocumentComponent(board(p), d, WU);
		assertEquals(Set.of(p), stored(d).getPerspectives());

		UUID e = newComponent();
		assertTrue(componentService.archiveComponent(e, WU));
		assertEquals(StatusEnum.ARCHIVED, stored(e).getStatus());

		UUID f = newComponent();
		DeclarativeProvenance prov = provenance();
		componentService.stampDeclarativeProvenance(f, prov, WU);
		assertEquals(prov.getSpecHash(), stored(f).getDeclarative().getSpecHash());

		UUID g = newComponent();
		componentService.updateComponentResourceGroup(g, CommonVariables.DEFAULT_RESOURCE_GROUP, WU);
		assertEquals(CommonVariables.DEFAULT_RESOURCE_GROUP, stored(g).getResourceGroup());

		UUID h = newComponent();
		componentService.setComponentVersion(h, "Major.Minor", WU);
		assertEquals("Major.Minor", stored(h).getVersionSchema());

		UUID i = newComponent();
		componentService.setComponentVisibility(i, VisibilitySetting.ADMIN_INVITATION, WU);
		assertEquals(VisibilitySetting.ADMIN_INVITATION, stored(i).getVisibilitySetting());

		UUID j = newComponent();
		UUID vcs = newVcs();
		componentService.updateComponentVcsRepo(stored(j), vcs, WU);
		assertEquals(vcs, stored(j).getVcs());

		UUID k = newComponent();
		UUID u = newMember(), s = newMember();
		UUID action = seedAction(k, u, s);
		componentService.handleRemoveUserFromTriggers(org, u, WU);
		assertEquals(Set.of(s), actionUsers(stored(k), action));

		UUID m = newComponent();
		ComponentLock l = lock(m, "row23");
		componentService.setLocks(m, List.of(), WU);
		assertFalse(activeLock(stored(m), l.uuid()));
		assertTrue(stored(m).getLocks().isEmpty());
	}

	/** Six lock raises and six settings saves at once: every lock survives, every save gets its own revision. */
	@Test
	@Timeout(60)
	public void row24_sixLockRaisesAndSixSettingsSavesAtOnce() throws Exception {
		int rev = revision(c), aud = audits(c);
		CountDownLatch go = new CountDownLatch(1);
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		List<String> values = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(12);
		try {
			for (int i = 0; i < 6; i++) {
				String n = "n" + i;
				values.add(n);
				pool.submit(() -> {
					try {
						go.await();
						lock(c, n);
					} catch (Throwable t) {
						errors.add(t);
					}
				});
				pool.submit(() -> {
					try {
						go.await();
						new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
							versionAssignmentRepository.setLocalLockTimeout("30s");
							getComponentService.getComponentData(c);
							try {
								x(n);
							} catch (Exception e) {
								throw unchecked(e);
							}
						});
					} catch (Throwable t) {
						errors.add(t);
					}
				});
			}
			go.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "the writers did not finish");
		} finally {
			pool.shutdownNow();
		}
		assertTrue(errors.isEmpty(), "a writer failed: " + errors);
		ComponentData cd = stored();
		for (String n : values) {
			assertTrue(cd.getLocks().stream().anyMatch(l -> n.equals(l.reason()) && l.active()), "lock " + n + " was lost");
		}
		assertTrue(values.contains(cd.getRepoPath()), "repoPath " + cd.getRepoPath());
		assertEquals(rev + 12, revision(c));
		assertEquals(aud + 12, audits(c));
	}

	/** The catalog entry's shape: update, then stamp, in one transaction; the locked read flushes the first. */
	@Test
	@Timeout(60)
	public void row25_updateThenStampInOneTransaction_keepsBoth() {
		int rev = revision(c);
		DeclarativeProvenance prov = provenance();
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			try {
				x("entry");
				componentService.stampDeclarativeProvenance(c, prov, WU);
			} catch (Exception e) {
				throw unchecked(e);
			}
		});
		assertEquals("entry", stored().getRepoPath());
		assertEquals(prov.getSpecHash(), stored().getDeclarative().getSpecHash());
		// The stamp is in place since TPF-108 (architecture round 2, 5.3): the update's revision only.
		assertEquals(rev + 1, revision(c));
	}
}
