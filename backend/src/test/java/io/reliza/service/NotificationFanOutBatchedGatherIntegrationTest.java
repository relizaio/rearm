/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Deliverable;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.Variant;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.MetricsVulnerabilityTestBase;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.repositories.VariantRepository;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;
import io.reliza.ws.App;

/**
 * TPF-131 (architecture round 6, 6.2 row 3; security F-8): the notification fan-out resolves the
 * affected releases with the batched {@link SharedReleaseService#gatherReleaseIdsForArtifacts}, and
 * the batched set equals the per-artifact loop's set: releases reached directly, through an SCE,
 * through a deliverable (a product release), and through a generated VDR part bound in the
 * release's syntheticArtifacts (the release R4 no other artifact of which carries the CVE). A
 * release of a second organization that reaches one of the artifacts through its own SCE row is
 * returned by the batched SCE statement (it has no org predicate) and dropped by the org-scoped
 * hydration. With 1,200 artifacts the resolve runs three chunks of four statements.
 */
@SpringBootTest(classes = {App.class})
class NotificationFanOutBatchedGatherIntegrationTest extends MetricsVulnerabilityTestBase {

	@MockitoSpyBean private SharedReleaseService sharedReleaseServiceSpy;
	@MockitoSpyBean private ReleaseRepository releaseRepositorySpy;

	@Autowired private NotificationFanOutService fanOutService;
	@Autowired private NotificationOutboxEventRepository outboxRepo;
	@Autowired private NotificationSubscriptionRepository subscriptionRepo;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private SourceCodeEntryRepository sourceCodeEntryRepository;
	@Autowired private DeliverableRepository deliverableRepository;
	@Autowired private VariantRepository variantRepository;
	@Autowired private SyntheticArtifactService syntheticArtifactService;

	private void subscription(UUID org) {
		NotificationSubscriptionData data = new NotificationSubscriptionData(org, null, "tpf131-gather",
				NotificationSubscriptionStatus.ACTIVE, List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES), null,
				List.of(new RouteConfig(NotificationSeverity.LOW, null, null, List.of(UUID.randomUUID()), null, null,
						null, Boolean.FALSE)),
				null, null);
		NotificationSubscription sub = new NotificationSubscription();
		sub.setUuid(UUID.randomUUID());
		sub.setRecordData(Utils.OM.convertValue(data, Map.class));
		subscriptionRepo.save(sub);
	}

	/** A PENDING NEW_VULN event with no affectedReleases, backdated to the head of the batch. */
	private UUID vulnEvent(UUID org, String cve) {
		NotificationOutboxEvent e = new NotificationOutboxEvent();
		e.setUuid(UUID.randomUUID());
		e.setOrg(org);
		e.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
		e.setStatus(NotificationOutboxStatus.PENDING);
		e.setOccurredAt(ZonedDateTime.now().minusYears(20));
		e.setDedupKey("tpf131-gather:" + UUID.randomUUID());
		e.setRecordData(Map.of("vulnPrimaryId", cve, "severity", NotificationSeverity.CRITICAL.name()));
		return outboxRepo.save(e).getUuid();
	}

	@SuppressWarnings("unchecked")
	private Set<UUID> affectedReleases(UUID event) {
		NotificationOutboxEvent e = outboxRepo.findById(event).orElseThrow();
		assertEquals(NotificationOutboxStatus.FANNED_OUT, e.getStatus(), "the event must have been delivered");
		List<Map<String, Object>> list = (List<Map<String, Object>>) e.getRecordData().get("affectedReleases");
		List<UUID> uuids = list.stream().map(m -> UUID.fromString(String.valueOf(m.get("uuid")))).toList();
		assertEquals(uuids.size(), new HashSet<>(uuids).size(), "each release once: " + uuids);
		return new HashSet<>(uuids);
	}

	private SourceCodeEntry sce(UUID org, Collection<UUID> artifacts, UUID component) {
		SourceCodeEntry sce = new SourceCodeEntry();
		sce.setUuid(UUID.randomUUID());
		sce.setCreatedDate(ZonedDateTime.now());
		sce.setLastUpdatedDate(ZonedDateTime.now());
		sce.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", org.toString());
		List<Map<String, String>> entries = new ArrayList<>();
		for (UUID a : artifacts) {
			entries.add(Map.of("artifactUuid", a.toString(), "componentUuid", component.toString()));
		}
		recordData.put("artifacts", entries);
		sce.setRecordData(recordData);
		return sourceCodeEntryRepository.save(sce);
	}

	private Deliverable deliverable(UUID org, UUID artifact) {
		Deliverable d = new Deliverable();
		d.setUuid(UUID.randomUUID());
		d.setCreatedDate(ZonedDateTime.now());
		d.setLastUpdatedDate(ZonedDateTime.now());
		d.setSchemaVersion(0);
		d.setRecordData(new HashMap<>(Map.of("org", org.toString(), "artifacts", List.of(artifact.toString()))));
		return deliverableRepository.save(d);
	}

	private Variant variant(UUID org, UUID release, UUID deliverable) {
		Variant v = new Variant();
		v.setUuid(UUID.randomUUID());
		v.setCreatedDate(ZonedDateTime.now());
		v.setLastUpdatedDate(ZonedDateTime.now());
		v.setSchemaVersion(0);
		v.setRecordData(new HashMap<>(Map.of("org", org.toString(), "release", release.toString(),
				"outboundDeliverables", List.of(deliverable.toString()))));
		return variantRepository.save(v);
	}

	@Test
	void theBatchedGatherEqualsTheLoopIncludingTheSyntheticOnlyReleaseAndNoOtherOrg() throws Exception {
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		String s = suffix();
		String cve = "CVE-2026-" + s;
		String p = "pkg:npm/gather-" + s + "@1.0.0";
		ComponentAndBranch cb = component(orgA, ComponentType.COMPONENT);
		ComponentAndBranch product = component(orgA, ComponentType.PRODUCT);

		UUID a1 = artifact(orgA, List.of(vuln(p, cve)));
		UUID a2 = artifact(orgA, List.of(vuln(p, cve)));
		UUID a3 = artifact(orgA, List.of(vuln(p, cve)));
		// S: a submission's generated VDR part, as the real path stores it (the release's findings in its metrics).
		UUID syntheticVdr = artifact(orgA, ArtifactType.VDR, List.of(vuln(p, cve)));
		UUID noFindings = artifact(orgA, List.of(vuln(p, "CVE-OTHER-" + s)));

		// R1: A1 attached directly.
		UUID r1 = release(orgA, cb, List.of(), List.of());
		mergeReleaseRecord(r1, "{\"artifacts\": [\"" + a1 + "\"]}");
		// R2: A2 through an SCE of R2's component.
		UUID r2 = release(orgA, cb, List.of(), List.of());
		SourceCodeEntry sceA = sce(orgA, List.of(a2), cb.component().getUuid());
		mergeReleaseRecord(r2, "{\"sourceCodeEntry\": \"" + sceA.getUuid() + "\"}");
		// R3: a product release reaching A3 through a deliverable and a variant.
		UUID r3 = release(orgA, product, List.of(), List.of());
		variant(orgA, r3, deliverable(orgA, a3).getUuid());
		// R4: S bound as generated, its inventory an artifact without the CVE.
		UUID r4 = release(orgA, cb, List.of(), List.of());
		mergeReleaseRecord(r4, "{\"artifacts\": [\"" + noFindings + "\"]}");
		Release r4Row = sharedReleaseService.getRelease(r4).orElseThrow();
		ReleaseData r4Data = ReleaseData.dataFromRecord(r4Row);
		assertTrue(syntheticArtifactService.stageBind(r4Data, syntheticVdr, WU));
		ossReleaseService.saveRelease(r4Row, r4Data, WU, false, AcollectionMode.SKIP);
		// RB: a release of org B reaching A2 through org B's own SCE row; it carries no finding itself.
		ComponentAndBranch cbB = component(orgB, ComponentType.COMPONENT);
		UUID rb = release(orgB, cbB, List.of(), List.of());
		SourceCodeEntry sceB = sce(orgB, List.of(a2), cbB.component().getUuid());
		mergeReleaseRecord(rb, "{\"sourceCodeEntry\": \"" + sceB.getUuid() + "\"}");

		// The loop's set: the per-artifact gather over every artifact carrying the id, S included.
		List<UUID> carriers = artifactRepository.findArtifactsWithVulnId(orgA.toString(), cve);
		assertEquals(Set.of(a1, a2, a3, syntheticVdr), new HashSet<>(carriers));
		Set<UUID> loop = new HashSet<>();
		for (UUID a : carriers) {
			sharedReleaseService.gatherReleasesForArtifact(a, orgA).forEach(rd -> loop.add(rd.getUuid()));
		}
		assertEquals(Set.of(r1, r2, r3, r4), loop);
		// The batched SCE statement has no org predicate: it does return RB, which hydration drops.
		assertTrue(sharedReleaseService.gatherReleaseIdsForArtifacts(List.of(a2), orgA).contains(rb));

		subscription(orgA);
		UUID event = vulnEvent(orgA, cve);
		fanOutService.drainBatch(50);

		Set<UUID> delivered = affectedReleases(event);
		assertEquals(Set.of(r1, r2, r3, r4), delivered,
				"R4 is reachable only through its generated VDR part (F-8); RB belongs to another org");
		assertEquals(loop, delivered, "the batched gather equals the per-artifact loop");
		assertFalse(delivered.contains(rb));
	}

	@Test
	void twelveHundredArtifactsResolveInThreeChunksOfFourStatements() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String s = suffix();
		String cve = "CVE-2026-" + s;
		ComponentAndBranch cb = component(org, ComponentType.COMPONENT);
		jdbc.update("INSERT INTO rearm.artifacts (uuid, record_data, metrics) SELECT gen_random_uuid(),"
				+ " jsonb_build_object('org', ?::text), jsonb_build_object('vulnerabilityDetails',"
				+ " jsonb_build_array(jsonb_build_object('purl', 'pkg:npm/chunk-' || g || '@1', 'vulnId', ?::text)))"
				+ " FROM generate_series(1, 1200) g", org.toString(), cve);
		List<UUID> artifacts = artifactRepository.findArtifactsWithVulnId(org.toString(), cve);
		assertEquals(1200, artifacts.size());
		UUID release = release(org, cb, List.of(), List.of());
		SourceCodeEntry shared = sce(org, artifacts, cb.component().getUuid());
		mergeReleaseRecord(release, "{\"sourceCodeEntry\": \"" + shared.getUuid() + "\"}");

		subscription(org);
		UUID event = vulnEvent(org, cve);
		fanOutService.drainBatch(50);

		assertEquals(Set.of(release), affectedReleases(event));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<UUID>> chunks = ArgumentCaptor.forClass(Collection.class);
		verify(sharedReleaseServiceSpy, times(3)).gatherReleaseIdsForArtifacts(chunks.capture(), eq(org));
		assertEquals(List.of(500, 500, 200), chunks.getAllValues().stream().map(Collection::size).toList());
		verify(releaseRepositorySpy, times(3)).findReleasesByReleaseArtifacts(any(), eq(org.toString()));
		verify(releaseRepositorySpy, times(3)).findReleasesBySyntheticArtifacts(any(), eq(org.toString()));
		verify(sharedReleaseServiceSpy, never()).gatherReleasesForArtifact(any(), eq(org));
	}
}
