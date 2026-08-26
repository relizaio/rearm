/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.common.Utils;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserGroup;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.repositories.UserGroupRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Transaction-boundary regression tests for {@link NotificationFanOutService#drainBatch}.
 *
 * <p><b>Why these need a real Spring context.</b> The failure they pin is
 * invisible to a Mockito test by construction: it is caused by Spring marking a
 * transaction rollback-only, and under mocks there is no transaction manager, so
 * the bug cannot occur no matter how the mocks are arranged. That is precisely
 * how it survived into production -- {@code NotificationFanOutServiceTest} has
 * 68 tests over this same method and none of them could ever have caught it.
 *
 * <p><b>The bug.</b> {@code drainBatch} used to wrap the whole batch in one
 * transaction and mark a failed event {@code FAILED} inside it. When fan-out
 * threw through a nested {@code @Transactional} call, the shared transaction was
 * already rollback-only, so the commit threw {@code UnexpectedRollbackException}
 * and rolled back the FAILED mark along with everything else. The event stayed
 * {@code PENDING} and the 5-second scheduler replayed it forever -- and since
 * {@code findPendingBatch} has no org filter, that stalled notification delivery
 * for every org, not just the one holding the bad row.
 *
 * <p><b>Two independent properties are pinned here</b>, because the fix has two
 * halves and either alone is insufficient:
 * <ol>
 *   <li><b>Containment at the source.</b> An unreadable {@code user_groups} row
 *       must cost only owner routing, not the event. This is the half that was
 *       missed first: per-event isolation ALONE turned a recoverable global
 *       stall into silent, permanent, per-org loss, because the ownership
 *       lookup poisoned the event's transaction from across a bean proxy and
 *       took every already-written delivery down with it -- including ones
 *       routed to channels named explicitly on the route. Containment now lives
 *       in {@code UserGroupService.getUserGroupsByOrganization} itself, which is
 *       the only place it can work: an exception escaping a
 *       {@code @Transactional} method marks the CALLER's transaction
 *       rollback-only on the way out, and no catch at the call site can undo
 *       that.
 *   <li><b>Isolation for the unknown.</b> Each event gets its own transaction,
 *       so a throw we have not anticipated costs one event rather than the
 *       batch. Induced with a spy rather than bad data on purpose -- the
 *       tolerant reads remove every poison we currently know about, and this
 *       boundary exists for the ones we do not.
 * </ol>
 *
 * <p>Worth knowing before adding another guard of this shape: whether a
 * try/catch around one of these lookups actually works depends entirely on
 * whether the call crosses a Spring proxy.
 * {@code UserGroupService.resolveTeamChannelUuids} gets away with a call-site
 * catch only because it reaches {@code getUserGroupData} by unqualified
 * SELF-invocation, which never touches the proxy.
 * {@code ComponentOwnershipService} calls the same class ACROSS beans and does
 * not. Same exception, same shape of guard, opposite outcome.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class NotificationDrainTransactionTest {

	@Autowired private NotificationFanOutService fanOutService;
	@Autowired private NotificationOutboxEventRepository outboxRepo;
	@Autowired private NotificationSubscriptionRepository subscriptionRepo;
	@Autowired private UserGroupRepository userGroupRepo;
	@Autowired private ComponentService componentService;
	@Autowired private NotificationDeliveryRepository deliveryRepo;

	// Spied, not mocked: every other test here needs its real behaviour; only
	// the isolation test overrides one call to induce a throw.
	@MockitoSpyBean private ComponentOwnershipService componentOwnershipService;
	@Autowired private TestInitializer testInitializer;

	/**
	 * A team row that {@code UserGroupData.dataFromRecord} refuses to read.
	 *
	 * <p>Seeded through the repository DELIBERATELY, unlike the component below:
	 * no service path can produce an unsupported {@code schemaVersion}, which is
	 * the whole point of the poison.
	 */
	private UUID unreadableTeam(UUID org) {
		UserGroup ug = new UserGroup();
		ug.setUuid(UUID.randomUUID());
		// Anything other than 0 is "not currently supported" and throws on read.
		ug.setSchemaVersion(1);
		ug.setRecordData(Map.of("name", "Poison Team", "org", org.toString(),
				"status", UserGroupStatus.ACTIVE.name()));
		return userGroupRepo.save(ug).getUuid();
	}

	/**
	 * A real component, so owner resolution has something to resolve. Created
	 * through the service like every other DB-backed test here: a hand-built
	 * row would keep "passing" if ComponentData ever gained a required field,
	 * while silently no longer resolving -- and the poison path would stop
	 * being exercised without any test going red.
	 */
	private UUID component(UUID org) throws RelizaException {
		return componentService.createComponent("drain-tx-comp-" + UUID.randomUUID(),
				org, ComponentType.COMPONENT, "semver", "Branch.Micro", null,
				WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	/**
	 * A channel uuid for a route to name.
	 *
	 * <p>No Integration row is created on purpose: fan-out treats a missing
	 * channel as "defer to the worker so it can write a FAILED History row", so a
	 * delivery row is still written -- which is exactly what these tests count.
	 * Building a full channel would add fixture surface without changing what is
	 * being asserted.
	 */
	private UUID channelInOrg(UUID org) {
		return UUID.randomUUID();
	}

	/** An ACTIVE subscription routing to the component owner AND a named channel. */
	private void subscriptionTargetingOwnerAndChannel(UUID org, UUID channel) {
		NotificationSubscriptionData data = new NotificationSubscriptionData(
				org, null, "drain-tx-test", NotificationSubscriptionStatus.ACTIVE,
				List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
				null,
				List.of(new RouteConfig(NotificationSeverity.LOW, null, null,
						List.of(channel), null, null, null, Boolean.TRUE)),
				null, null);
		NotificationSubscription sub = new NotificationSubscription();
		sub.setUuid(UUID.randomUUID());
		sub.setRecordData(Utils.OM.convertValue(data, Map.class));
		subscriptionRepo.save(sub);
	}

	private NotificationOutboxEvent pendingVulnEvent(UUID org, UUID componentUuid) {
		NotificationOutboxEvent e = new NotificationOutboxEvent();
		e.setUuid(UUID.randomUUID());
		e.setOrg(org);
		e.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
		e.setStatus(NotificationOutboxStatus.PENDING);
		// Backdated deliberately. findPendingBatch is "ORDER BY occurred_at LIMIT
		// :batchSize" with NO org filter, so in a full-suite run the PENDING rows
		// other test classes leave behind can fill the batch and starve this
		// event out of it entirely -- the assertions would then read PENDING and
		// fail for a reason that has nothing to do with the code under test.
		// Backdating puts this event at the head of the ordering deterministically.
		e.setOccurredAt(ZonedDateTime.now().minusYears(10));
		e.setDedupKey("drain-tx-test:" + UUID.randomUUID());
		e.setRecordData(Map.of(
				"vulnPrimaryId", "CVE-DRAIN-TX-" + UUID.randomUUID(),
				"severity", NotificationSeverity.CRITICAL.name(),
				"affectedReleases", List.of(Map.of(
						"uuid", UUID.randomUUID().toString(),
						"componentUuid", componentUuid.toString(),
						"component", "drain-tx-comp",
						"version", "v1", "branch", "main"))));
		return outboxRepo.save(e);
	}

	/**
	 * Cross-org isolation can't be exercised on the OSS/CE edition (single-org);
	 * skip cleanly there so the CE-mirrored copy of this test stays green.
	 */
	private static void assumeProEdition() {
		org.junit.jupiter.api.Assumptions.assumeFalse(
			io.reliza.common.oss.LicensingConstants.isOssEdition(),
			"Pro-only feature; skipped on OSS edition");
	}

	private NotificationOutboxStatus statusOf(UUID eventUuid) {
		return outboxRepo.findById(eventUuid).orElseThrow().getStatus();
	}

	/**
	 * Same shape as {@link #pendingVulnEvent} but with NO affectedReleases, so
	 * fan-out has to resolve them itself. The CVE is random and therefore
	 * carried by no artifact, which is exactly the "resolves to empty" case the
	 * affected-release guard exists for.
	 */
	private NotificationOutboxEvent unresolvableVulnEvent(UUID org) {
		NotificationOutboxEvent e = new NotificationOutboxEvent();
		e.setUuid(UUID.randomUUID());
		e.setOrg(org);
		e.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
		e.setStatus(NotificationOutboxStatus.PENDING);
		// Backdated for the same batch-ordering reason as pendingVulnEvent.
		e.setOccurredAt(ZonedDateTime.now().minusYears(10));
		e.setDedupKey("drain-defer-test:" + UUID.randomUUID());
		e.setRecordData(Map.of(
				"vulnPrimaryId", "CVE-DEFER-" + UUID.randomUUID(),
				"severity", NotificationSeverity.CRITICAL.name()));
		return outboxRepo.save(e);
	}

	private NotificationOutboxEvent reload(UUID eventUuid) {
		return outboxRepo.findById(eventUuid).orElseThrow();
	}

	/**
	 * The deferral must actually be enforced by the QUERY, not just intended by
	 * the service.
	 *
	 * <p>This is the one thing the mock-based fan-out tests structurally cannot
	 * prove: they stub {@code findPendingBatch} outright, so a broken
	 * {@code next_attempt_at <= :now} predicate would sail through them. If that
	 * filter did not bite, the 5-second drain would re-pick the row on every
	 * tick and burn all three attempts in ~15 seconds instead of ~3.5 minutes --
	 * silently defeating the fix while every unit test stayed green.
	 *
	 * <p>The third drain is what makes the second drain's assertion meaningful:
	 * it proves the row is reachable in the batch at all, so "attempt count did
	 * not move" was the filter doing its job rather than the event being starved
	 * out of a full batch by rows other test classes left behind.
	 */
	@Test
	public void aDeferredEventIsInvisibleToTheDrainUntilItsDelayElapses() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		subscriptionTargetingOwnerAndChannel(org, channelInOrg(org));
		UUID event = unresolvableVulnEvent(org).getUuid();

		fanOutService.drainBatch(50);

		NotificationOutboxEvent afterFirst = reload(event);
		assertEquals(NotificationOutboxStatus.PENDING, afterFirst.getStatus(),
				"an event whose releases could not be resolved yet must be deferred, not terminated");
		assertTrue(afterFirst.getEnrichmentAttemptCount() >= 1,
				"the first look must have deferred the event");
		assertTrue(afterFirst.getNextAttemptAt().isAfter(ZonedDateTime.now()),
				"next_attempt_at must be in the future for the filter to have anything to do");

		// Immediately again: the row is due in the future, so the query must not
		// return it and the count must not move.
		//
		// Compared against the value observed above rather than a literal 1, and
		// with >= rather than ==, on purpose: App carries an unconditional
		// @EnableScheduling and relizaprops.notificationOutboxDrainEnabled
		// defaults to true, so SchedulingService's real 5-second drain is LIVE
		// inside this Spring context and can also act on this row. Pinning exact
		// counts here would make the test a coin flip on scheduler timing rather
		// than a check of the predicate.
		int afterSecond = afterFirst.getEnrichmentAttemptCount();
		fanOutService.drainBatch(50);
		assertEquals(afterSecond, reload(event).getEnrichmentAttemptCount(),
				"a not-yet-due event must be invisible to findPendingBatch");

		// Make it due, and confirm it comes back -- proving the row was
		// reachable all along and the filter is what held it back.
		outboxRepo.deferForEnrichment(event, afterSecond, -60);

		fanOutService.drainBatch(50);
		assertTrue(reload(event).getEnrichmentAttemptCount() > afterSecond,
				"once due, the event must be picked up again");
	}

	/**
	 * End-to-end terminal case against a real database: attempts spent, releases
	 * still unresolvable, so the event is withheld rather than delivered -- and
	 * lands in SUPPRESSED, not FANNED_OUT, so the drop stays countable.
	 */
	@Test
	public void anEventThatAffectsNoReleaseIsSuppressedRatherThanDelivered() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		subscriptionTargetingOwnerAndChannel(org, channelInOrg(org));
		NotificationOutboxEvent e = unresolvableVulnEvent(org);
		e.setEnrichmentAttemptCount(NotificationFanOutService.MAX_ENRICHMENT_ATTEMPTS);
		outboxRepo.save(e);

		fanOutService.drainBatch(50);

		assertEquals(NotificationOutboxStatus.SUPPRESSED, statusOf(e.getUuid()));
		// Scoped to THIS event, not a global deliveryRepo.count(). drainBatch has
		// no org filter -- the property this class's javadoc calls out as the
		// reason the original bug was estate-wide -- so a global before/after
		// count would also capture rows fanned out for any other due event in the
		// shared test database, and fail while pointing at the wrong code.
		assertEquals(0, deliveryRepo.countByOutboxEventUuid(e.getUuid()),
				"a suppressed event must write no delivery rows at all");
	}

	/**
	 * An unreadable team row must cost ONLY owner routing -- not the event.
	 *
	 * <p>This is the finding that made the first version of this fix unshippable.
	 * Per-event transaction isolation alone turned a recoverable global stall
	 * into silent, permanent, per-org loss: the ownership lookup poisoned the
	 * event's transaction from across a bean proxy, so every delivery already
	 * written for that event -- including to a channel named explicitly on the
	 * route, with nothing to do with ownership -- was discarded at commit, the
	 * event went terminally FAILED, and nothing was ever retried. Confirmed live
	 * on the sandbox before the tolerant reads existed.
	 *
	 * <p>So the assertion that matters is not "the event failed cleanly" but
	 * "the event SUCCEEDED and the unrelated delivery survived".
	 */
	@Test
	public void anUnreadableTeamCostsOnlyOwnerRoutingNotTheWholeEvent() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		unreadableTeam(org);
		UUID channel = channelInOrg(org);
		subscriptionTargetingOwnerAndChannel(org, channel);
		UUID event = pendingVulnEvent(org, component(org)).getUuid();

		assertDoesNotThrow(() -> fanOutService.drainBatch(50));

		assertEquals(NotificationOutboxStatus.FANNED_OUT, statusOf(event),
				"an unreadable team must not fail the event -- owner routing simply "
				+ "resolves to nothing");
		assertEquals(1, deliveryRepo.findByOutboxEventUuid(event).size(),
				"the explicitly named channel must still be delivered to; losing it is "
				+ "the silent data loss this test exists to prevent");
	}

	/**
	 * Per-event transaction isolation, independent of any particular poison.
	 *
	 * <p>Deliberately induced with a spy rather than bad data: the tolerant reads
	 * above remove every poison we currently know about, and the point of the
	 * per-event boundary is containment of the ones we do not. One event throws;
	 * its neighbour in the same batch must still commit.
	 */
	@Test
	public void aThrowingEventIsContainedAndItsNeighbourStillCommits() throws RelizaException {
		assumeProEdition();
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		subscriptionTargetingOwnerAndChannel(orgA, channelInOrg(orgA));
		subscriptionTargetingOwnerAndChannel(orgB, channelInOrg(orgB));

		UUID doomed = pendingVulnEvent(orgA, component(orgA)).getUuid();
		UUID healthy = pendingVulnEvent(orgB, component(orgB)).getUuid();

		Mockito.doThrow(new IllegalStateException("simulated fan-out failure"))
				.when(componentOwnershipService)
				.resolveOwnerTeamChannels(Mockito.any(), Mockito.eq(orgA));

		assertDoesNotThrow(() -> fanOutService.drainBatch(50));

		assertEquals(NotificationOutboxStatus.FAILED, statusOf(doomed),
				"the throwing event must reach a terminal status, not sit PENDING and "
				+ "replay every 5 seconds forever");
		assertEquals(NotificationOutboxStatus.FANNED_OUT, statusOf(healthy),
				"its neighbour shares only the batch, and must no longer share the "
				+ "transaction");
		assertEquals(1, deliveryRepo.findByOutboxEventUuid(healthy).size(),
				"and must actually have delivered");
	}
}
