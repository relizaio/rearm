/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.MetricsVulnerabilityTestBase;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.ws.App;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * TPF-131 (architecture round 6, 6.2 row 4): a database error inside an enrichment read (here a
 * real statement timeout raised in the read's transaction) defers the event as UNRESOLVED instead
 * of failing it, because the enrichment reads run in their own read-only REQUIRES_NEW transaction
 * and the per-event transaction stays usable for the deferral. Before, the error aborted the
 * per-event transaction and the drain terminated the event as FAILED.
 */
@SpringBootTest(classes = {App.class})
class NotificationFanOutSlowEnrichmentIntegrationTest extends MetricsVulnerabilityTestBase {

	@MockitoSpyBean private ArtifactRepository artifactRepositorySpy;

	@Autowired private NotificationFanOutService fanOutService;
	@Autowired private NotificationOutboxEventRepository outboxRepo;
	@Autowired private NotificationSubscriptionRepository subscriptionRepo;

	@PersistenceContext private EntityManager entityManager;

	@AfterEach
	void restoreSpy() {
		Mockito.reset(artifactRepositorySpy);
	}

	private void subscription(UUID org) {
		NotificationSubscriptionData data = new NotificationSubscriptionData(org, null, "tpf131-slow",
				NotificationSubscriptionStatus.ACTIVE, List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES), null,
				List.of(new RouteConfig(NotificationSeverity.LOW, null, null, List.of(UUID.randomUUID()), null, null,
						null, Boolean.FALSE)),
				null, null);
		NotificationSubscription sub = new NotificationSubscription();
		sub.setUuid(UUID.randomUUID());
		sub.setRecordData(Utils.OM.convertValue(data, Map.class));
		subscriptionRepo.save(sub);
	}

	private UUID vulnEvent(UUID org, String cve, int minutesOld) {
		NotificationOutboxEvent e = new NotificationOutboxEvent();
		e.setUuid(UUID.randomUUID());
		e.setOrg(org);
		e.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
		e.setStatus(NotificationOutboxStatus.PENDING);
		e.setOccurredAt(ZonedDateTime.now().minusYears(20).minusMinutes(minutesOld));
		e.setDedupKey("tpf131-slow:" + UUID.randomUUID());
		e.setRecordData(Map.of("vulnPrimaryId", cve, "severity", NotificationSeverity.CRITICAL.name()));
		return outboxRepo.save(e).getUuid();
	}

	private NotificationOutboxEvent reload(UUID event) {
		return outboxRepo.findById(event).orElseThrow();
	}

	@Test
	void aStatementTimeoutInTheEnrichmentDefersTheEventInsteadOfFailingIt() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String s = suffix();
		String slowCve = "CVE-2026-SLOW-" + s;
		String okCve = "CVE-2026-OK-" + s;
		String p = "pkg:npm/slow-" + s + "@1";
		ComponentAndBranch cb = component(org, ComponentType.COMPONENT);
		UUID artifact = artifact(org, List.of(vuln(p, slowCve), vuln(p, okCve)));
		UUID release = release(org, cb, List.of(), List.of());
		mergeReleaseRecord(release, "{\"artifacts\": [\"" + artifact + "\"]}");
		subscription(org);

		// A real statement timeout inside the read's transaction: SET LOCAL then a sleep past it.
		doAnswer(inv -> {
			entityManager.createNativeQuery("SELECT set_config('statement_timeout', '50ms', true)").getSingleResult();
			entityManager.createNativeQuery("SELECT pg_sleep(1)").getSingleResult();
			return inv.callRealMethod();
		}).when(artifactRepositorySpy).findArtifactsWithVulnId(eq(org.toString()), eq(slowCve));

		UUID slow = vulnEvent(org, slowCve, 2);
		UUID ok = vulnEvent(org, okCve, 1);
		fanOutService.drainBatch(5);

		NotificationOutboxEvent deferred = reload(slow);
		assertEquals(NotificationOutboxStatus.PENDING, deferred.getStatus(),
				"a failed enrichment read defers the event (UNRESOLVED); it must not go FAILED");
		assertEquals(1, deferred.getEnrichmentAttemptCount());
		assertTrue(deferred.getNextAttemptAt().isAfter(ZonedDateTime.now()), "deferred into the future");
		assertFalse(deferred.getRecordData().containsKey("affectedReleases"),
				"a deferred event persists no (empty) affectedReleases");
		assertEquals(NotificationOutboxStatus.FANNED_OUT, reload(ok).getStatus(),
				"the resolvable neighbour in the same batch is delivered");

		// The read works again: once due, the deferred event resolves and fans out.
		Mockito.reset(artifactRepositorySpy);
		outboxRepo.deferForEnrichment(slow, deferred.getEnrichmentAttemptCount(), -60);
		fanOutService.drainBatch(5);
		NotificationOutboxEvent delivered = reload(slow);
		assertEquals(NotificationOutboxStatus.FANNED_OUT, delivered.getStatus());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> affected = (List<Map<String, Object>>) delivered.getRecordData().get("affectedReleases");
		assertEquals(List.of(release.toString()), affected.stream().map(m -> String.valueOf(m.get("uuid"))).toList());
	}
}
