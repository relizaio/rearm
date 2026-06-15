/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Organization;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * DB-backed regression tests for the inbox visibility predicate on
 * {@link NotificationDeliveryRepository} — the native SQL shared by
 * {@code findInboxPage} / {@code countInbox} /
 * {@code countDeliveryVisibleToUser}. The predicate is pure SQL, so
 * Mockito-level tests can't pin it; these run against the real test
 * Postgres (each test gets a fresh org from {@link TestInitializer}).
 *
 * <p>Pins, per arm:
 * <ul>
 *   <li><b>Targeted arm</b> (Phase 4a, {@code target_user IS NOT NULL}):
 *       a per-user targeted row is visible ONLY to its target user — not
 *       to another non-admin, not via the org-admin arm (admins would
 *       otherwise see N identical copies of one approval request), and
 *       not cross-org.</li>
 *   <li><b>Subscription arms</b> ({@code target_user IS NULL}): org-admin
 *       sees the row with zero perspectives; a non-admin sees it only via
 *       payload-perspective intersection; a non-admin with no matching
 *       perspective sees nothing.</li>
 *   <li>{@code countDeliveryVisibleToUser} (mark-read auth path) agrees
 *       with the listing on every case above.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class NotificationInboxVisibilityQueryTest {

	@Autowired private NotificationDeliveryRepository deliveryRepo;
	@Autowired private NotificationOutboxEventRepository outboxRepo;
	@Autowired private TestInitializer testInitializer;

	private static final String NO_PERSPECTIVES = "{}";

	/**
	 * Cross-org isolation can't be meaningfully exercised on the OSS/CE
	 * edition (single-org); skip such cases cleanly there so the
	 * CE-mirrored copy of this test stays green.
	 */
	private static void assumeProEdition() {
		org.junit.jupiter.api.Assumptions.assumeFalse(
			io.reliza.common.oss.LicensingConstants.isOssEdition(),
			"Pro-only feature; skipped on OSS edition");
	}

	@Test
	public void targetedRowVisibleOnlyToItsTargetUser() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();
		NotificationOutboxEvent event = saveApprovalRequestedEvent(org, null);
		NotificationDelivery targeted = saveTargetedDelivery(event, alice);

		assertTrue(inbox(org, alice, false, NO_PERSPECTIVES).contains(targeted.getUuid()),
				"target user must see their own targeted row");
		assertFalse(inbox(org, bob, false, NO_PERSPECTIVES).contains(targeted.getUuid()),
				"non-target non-admin must NOT see another user's targeted row");
		assertFalse(inbox(org, bob, true, NO_PERSPECTIVES).contains(targeted.getUuid()),
				"org admin must NOT see targeted rows via the admin arm — "
				+ "one request snapshots N recipients into N rows and the admin "
				+ "already sees the event once via its subscription-routed delivery");

		assertTrue(deliveryRepo.existsDeliveryVisibleToUser(org, targeted.getUuid(), alice, false, NO_PERSPECTIVES),
				"mark-read auth check must agree: target user can act on the row");
		assertFalse(deliveryRepo.existsDeliveryVisibleToUser(org, targeted.getUuid(), bob, true, NO_PERSPECTIVES),
				"mark-read auth check must agree: even an admin cannot act on someone else's targeted row");
	}

	@Test
	public void targetedRowNotVisibleCrossOrg() {
		assumeProEdition();
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		UUID alice = UUID.randomUUID();
		NotificationOutboxEvent event = saveApprovalRequestedEvent(orgA, null);
		NotificationDelivery targeted = saveTargetedDelivery(event, alice);

		assertFalse(inbox(orgB, alice, true, NO_PERSPECTIVES).contains(targeted.getUuid()),
				"a delivery must never surface in another org's inbox, even for its target user as admin");
		assertFalse(deliveryRepo.existsDeliveryVisibleToUser(orgB, targeted.getUuid(), alice, true, NO_PERSPECTIVES),
				"mark-read auth check must reject a delivery uuid presented under the wrong org");
	}

	@Test
	public void subscriptionRowVisibleViaAdminAndPerspectiveArmsOnly() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID member = UUID.randomUUID();
		UUID outsider = UUID.randomUUID();
		UUID perspective = UUID.randomUUID();
		NotificationOutboxEvent event = saveApprovalRequestedEvent(org, List.of(perspective.toString()));
		NotificationDelivery routed = saveSubscriptionRoutedDelivery(event);

		String memberPerspectives = "{" + perspective + "}";
		assertTrue(inbox(org, outsider, true, NO_PERSPECTIVES).contains(routed.getUuid()),
				"org admin sees subscription-routed rows with zero perspectives");
		assertTrue(inbox(org, member, false, memberPerspectives).contains(routed.getUuid()),
				"non-admin sees the row when payload perspectives intersect their membership");
		assertFalse(inbox(org, outsider, false, NO_PERSPECTIVES).contains(routed.getUuid()),
				"non-admin with no perspective membership sees nothing");

		assertTrue(deliveryRepo.existsDeliveryVisibleToUser(org, routed.getUuid(), member, false, memberPerspectives));
		assertFalse(deliveryRepo.existsDeliveryVisibleToUser(org, routed.getUuid(), outsider, false, NO_PERSPECTIVES));
	}

	private Set<UUID> inbox(UUID org, UUID user, boolean isOrgAdmin, String perspectives) {
		return deliveryRepo.findInboxPage(org, user, isOrgAdmin, perspectives,
						/*unreadOnly*/ false, /*status*/ null, /*eventType*/ null, 100, 0)
				.stream().map(NotificationDelivery::getUuid).collect(Collectors.toSet());
	}

	private NotificationOutboxEvent saveApprovalRequestedEvent(UUID org, List<String> perspectives) {
		NotificationOutboxEvent event = new NotificationOutboxEvent();
		event.setOrg(org);
		event.setEventType(NotificationEventType.APPROVAL_REQUESTED);
		event.setStatus(NotificationOutboxStatus.FANNED_OUT);
		event.setDedupKey("approval:requested:" + UUID.randomUUID() + ":" + UUID.randomUUID());
		event.setRecordData(perspectives == null
				? Map.of()
				: Map.of("affectedReleases", List.of(Map.of("perspectives", perspectives))));
		return outboxRepo.save(event);
	}

	/** Mirrors the fan-out's targeted writer: born SENT, no subscription/channel. */
	private NotificationDelivery saveTargetedDelivery(NotificationOutboxEvent event, UUID targetUser) {
		NotificationDelivery d = new NotificationDelivery();
		d.setOrg(event.getOrg());
		d.setOutboxEventUuid(event.getUuid());
		d.setTargetUser(targetUser);
		d.setStatus(NotificationDeliveryStatus.SENT);
		d.setSentAt(ZonedDateTime.now());
		d.setDedupKey(event.getDedupKey());
		return deliveryRepo.save(d);
	}

	private NotificationDelivery saveSubscriptionRoutedDelivery(NotificationOutboxEvent event) {
		NotificationDelivery d = new NotificationDelivery();
		d.setOrg(event.getOrg());
		d.setOutboxEventUuid(event.getUuid());
		d.setSubscriptionUuid(UUID.randomUUID());
		d.setChannelUuid(UUID.randomUUID());
		d.setStatus(NotificationDeliveryStatus.SENT);
		d.setSentAt(ZonedDateTime.now());
		d.setDedupKey(event.getDedupKey());
		return deliveryRepo.save(d);
	}
}
