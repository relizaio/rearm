/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.ComponentRepository;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;

/**
 * TEA-5 round 4, case 92 (T-10), against the test database: a TEA id mint and a whole-record
 * save of the same row, each from its own request-shaped persistence context, both survive in
 * either order. The mint is one conditional statement that bumps nothing; every funnel writes the
 * stored id back from a locked read.
 */
public class TeaPublicationMintRaceIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private ComponentRepository componentRepository;
	@Autowired private OrganizationService organizationService;
	@Autowired private PlatformTransactionManager transactionManager;

	private int componentRevision(UUID component) {
		return jdbc.queryForObject("select revision from rearm.components where uuid = ?", Integer.class, component);
	}

	private int auditRows(UUID entity) {
		return jdbc.queryForObject("select count(*) from rearm.audit where entity_uuid = ?", Integer.class, entity);
	}

	private String storedTeaUuid(String table, UUID uuid) {
		return jdbc.queryForObject("select record_data->>'teaUuid' from rearm." + table + " where uuid = ?", String.class,
				uuid);
	}

	private void rename(UUID component, String name) throws Exception {
		componentService.updateComponent(ComponentDto.builder().uuid(component).name(name).build(), WU);
	}

	/** Runs {@code act} on the pool as its own request, around {@code release}. */
	private <T> Future<T> request(ExecutorService pool, UUID release, Callable<T> act) {
		return pool.submit(() -> asRequest(release, act));
	}

	private void blockNextMerge(CountDownLatch gate) {
		mergeEntered = new CountDownLatch(1);
		mergeGate = gate;
	}

	// ---------------------------------------------------------------- (a) the rename commits first

	@Test
	void aRenameCommittedBeforeTheFirstPublishIsKeptAndTheIdIsMinted() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		int revision = componentRevision(w.component());
		int audits = auditRows(w.component());
		CountDownLatch read = new CountDownLatch(1);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<List<Object>> r = request(pool, w.release(), () -> {
				read.countDown();
				assertTrue(go.await(60, TimeUnit.SECONDS));
				TeaPublishResult result = publish(w.release(), TeaPublishMode.PUBLISH);
				// the request's own context reads the id the mint returned
				ComponentData inContext = getComponentService.getComponentData(w.component()).orElseThrow();
				return List.of(result, inContext);
			});
			assertTrue(read.await(60, TimeUnit.SECONDS));
			asRequest(w.release(), () -> {
				rename(w.component(), "renamed-first");
				return null;
			});
			go.countDown();
			List<Object> out = r.get(120, TimeUnit.SECONDS);
			TeaPublishResult result = (TeaPublishResult) out.get(0);
			assertEquals(TeaPublishOutcome.PUBLISHED, result.outcome());
			assertEquals(1, result.collection().getVersion());
			ComponentData stored = getComponentService.getComponentData(w.component()).orElseThrow();
			assertEquals("renamed-first", stored.getName());
			assertNotNull(stored.getTeaUuid());
			assertEquals(stored.getTeaUuid(), ((ComponentData) out.get(1)).getTeaUuid());
		} finally {
			go.countDown();
			pool.shutdownNow();
		}
		assertEquals(revision + 1, componentRevision(w.component()), "one rename, no revision for the mint");
		assertEquals(audits + 1, auditRows(w.component()), "one audit row for the rename, none for the mint");
	}

	// ---------------------------------------------------------------- (b) the mint commits first

	@Test
	void aRenameFromAStaleContextAfterTheMintKeepsTheId() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		int revision = componentRevision(w.component());
		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// this request read the component before the publish minted its id
			asRequest(w.release(), () -> {
				blockNextMerge(gate);
				Future<TeaPublishResult> r = request(pool, w.release(), () -> publish(w.release(), TeaPublishMode.PUBLISH));
				assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the publish never reached the merge");
				assertNotNull(storedTeaUuid("components", w.component()), "the mint precedes the merge");
				rename(w.component(), "renamed-after-mint");
				gate.countDown();
				assertEquals(TeaPublishOutcome.PUBLISHED, r.get(120, TimeUnit.SECONDS).outcome());
				return null;
			});
		} finally {
			mergeGate = null;
			gate.countDown();
			pool.shutdownNow();
		}
		ComponentData stored = getComponentService.getComponentData(w.component()).orElseThrow();
		assertEquals("renamed-after-mint", stored.getName());
		assertNotNull(stored.getTeaUuid(), "the rename's save wrote the stored id back");
		assertTrue(teaPublicationService.getPublicationOfRelease(w.release()).isPresent());
		assertEquals(revision + 1, componentRevision(w.component()));
	}

	// ---------------------------------------------------------------- (c) the organization

	@Test
	void anOrganizationSettingSavedFromAStaleContextAfterTheMintKeepsBoth() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		// the profile save minted the organization's id; take it away so this publish is the first mint
		jdbc.update("update rearm.organizations set record_data = record_data - 'teaUuid' where uuid = ?", org.getUuid());
		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			asRequest(w.release(), () -> {
				blockNextMerge(gate);
				Future<TeaPublishResult> r = request(pool, w.release(), () -> publish(w.release(), TeaPublishMode.PUBLISH));
				assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the publish never reached the merge");
				assertNotNull(storedTeaUuid("organizations", org.getUuid()), "the mint precedes the merge");
				OrganizationData.Settings patch = new OrganizationData.Settings();
				patch.setNotificationRetentionDays(77);
				organizationService.updateSettings(org.getUuid(), patch, WU);
				gate.countDown();
				assertEquals(TeaPublishOutcome.PUBLISHED, r.get(120, TimeUnit.SECONDS).outcome());
				return null;
			});
		} finally {
			mergeGate = null;
			gate.countDown();
			pool.shutdownNow();
		}
		OrganizationData od = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow();
		assertEquals(77, od.getSettings().getNotificationRetentionDays());
		assertNotNull(od.getTeaUuid(), "the settings save wrote the stored id back");
	}

	// ---------------------------------------------------------------- (d) the ride-along

	@Test
	void aRideAlongsTagsAndItsIdBothSurviveAStaleContext() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		saveComponentProfile(w.component(), TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.ALL);
		String versionBefore = artifactService.getArtifactData(w.inventory()).orElseThrow().getVersion();
		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			asRequest(w.release(), () -> {
				sharedArtifactService.getArtifact(w.inventory()).orElseThrow(); // the stale copy, without an id
				blockNextMerge(gate);
				Future<TeaPublishResult> r = request(pool, w.release(), () -> publish(w.release(), TeaPublishMode.PUBLISH));
				assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the publish never reached the merge");
				artifactService.updateArtifactTags(w.inventory(), List.of(new TagRecord("during", "merge")), WU);
				gate.countDown();
				assertEquals(TeaPublishOutcome.PUBLISHED, r.get(120, TimeUnit.SECONDS).outcome());
				// the ride-along's id is minted now; this context still holds the row without it
				artifactService.updateArtifactTags(w.inventory(),
						List.of(new TagRecord("during", "merge"), new TagRecord("after", "mint")), WU);
				return null;
			});
		} finally {
			mergeGate = null;
			gate.countDown();
			pool.shutdownNow();
		}
		ArtifactData ad = artifactService.getArtifactData(w.inventory()).orElseThrow();
		assertEquals(List.of("during", "after"), ad.getTags().stream().map(TagRecord::key).toList());
		assertNotNull(ad.getTeaUuid(), "the tag updates wrote the stored id back");
		assertEquals(versionBefore, ad.getVersion(), "the mint is not a TEA revision");
		UUID publication = teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow().getUuid();
		TeaPublishedCollectionData v1 = teaPublicationService.getCollection(publication, 1).orElseThrow();
		TeaPublishedEntry real = v1.getEntries().stream().filter(e -> TeaArtifactOrigin.REAL == e.origin()).findFirst()
				.orElseThrow();
		assertEquals(ad.getTeaUuid(), real.teaUuid());
		assertEquals(TeaPublishOutcome.UNCHANGED, publish(w.release(), TeaPublishMode.REPUBLISH).outcome());
	}

	// ---------------------------------------------------------------- (e) the statement's guard

	@Test
	void theMintStatementWritesOnlyWhereTheIdIsNull() throws Exception {
		World w = componentRelease(null);
		String a = UUID.randomUUID().toString();
		String b = UUID.randomUUID().toString();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		assertEquals(1, (int) tx.<Integer>execute(s -> componentRepository.mintTeaUuid(w.component(), a)));
		assertEquals(0, (int) tx.<Integer>execute(s -> componentRepository.mintTeaUuid(w.component(), b)));
		assertEquals(a, storedTeaUuid("components", w.component()));
		assertEquals(a, tx.execute(s -> componentRepository.currentTeaUuidForUpdate(w.component()).orElseThrow()));

		// a JSON null reads as no id, to the guard and to the locked read alike
		jdbc.update("update rearm.components set record_data = jsonb_set(record_data, '{teaUuid}', 'null'::jsonb) "
				+ "where uuid = ?", w.component());
		assertTrue(tx.execute(s -> componentRepository.currentTeaUuidForUpdate(w.component())).isEmpty());
		assertEquals(1, (int) tx.<Integer>execute(s -> componentRepository.mintTeaUuid(w.component(), b)));
		assertEquals(b, storedTeaUuid("components", w.component()));
	}

	// ---------------------------------------------------------------- (f) two first publishes of one component

	@Test
	void twoFirstPublishesOfOneComponentMintOneIdAndWriteNoAuditRow() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID second = secondRelease(w, "pkg:generic/pump@1.0.1");
		int revision = componentRevision(w.component());
		int audits = auditRows(w.component());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Future<TeaPublishResult>> results = new ArrayList<>();
		try {
			for (UUID release : List.of(w.release(), second)) {
				results.add(request(pool, release, () -> {
					assertTrue(start.await(60, TimeUnit.SECONDS));
					return publish(release, TeaPublishMode.PUBLISH);
				}));
			}
			start.countDown();
			for (Future<TeaPublishResult> f : results) {
				assertEquals(TeaPublishOutcome.PUBLISHED, f.get(120, TimeUnit.SECONDS).outcome());
			}
		} finally {
			start.countDown();
			pool.shutdownNow();
		}
		assertNotNull(storedTeaUuid("components", w.component()));
		assertEquals(revision, componentRevision(w.component()), "a mint moves no revision");
		assertEquals(audits, auditRows(w.component()), "a mint writes no audit row");
	}

	/** Another ASSEMBLED release of the same component, carrying one SBOM. */
	private UUID secondRelease(World w, String purl) throws Exception {
		String version = "1.1." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(w.component())
				.branch(w.branch()).org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).version(version).build(), WU).getUuid();
		releasesByVersion.put(version, release);
		UUID inventory = seedBom("second-" + version + ".cdx.json");
		releaseService.addArtifact(inventory, release, WU);
		mergedPurls.put(release, new ArrayList<>(List.of(purl)));
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU);
		return release;
	}
}
