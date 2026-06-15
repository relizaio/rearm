/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.WhoUpdated;
import io.reliza.model.NotificationRead;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationReadRepository;
import io.reliza.service.AuditService;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-(user × delivery) mark-as-read state for the Phase 14 Inbox MVP
 * (notifications-framework.md §8.1).
 *
 * <p>The inbox visibility query — which deliveries a given user can see —
 * lives on {@link NotificationDeliveryRepository#findInboxPage}; this
 * service owns only the read-state side of it (mark / unmark / bulk
 * read / read-state projection helper). Splitting the two keeps the
 * audit-log query in {@link NotificationDeliveryService} untouched.
 *
 * <h3>Concurrency</h3>
 * <ul>
 *   <li>{@code NotificationRead} uses Hibernate {@code @Version} on
 *       {@code revision}. Services MUST NOT call
 *       {@code setRevision(getRevision()+1)} — let JPA flush the bump.
 *   </li>
 *   <li>Idempotency for {@link #markRead} is enforced via the unique
 *       constraint {@code notification_reads_user_delivery_unique} —
 *       a duplicate call is a no-op upsert (return the existing row).
 *   </li>
 *   <li>{@link #markRead} runs {@code REQUIRES_NEW}: the Phase 4a
 *       resolve-marks-read flow invokes it from an afterCommit
 *       synchronization, where a {@code REQUIRED} call joins the
 *       already-committed transaction context and its writes are
 *       silently discarded (observed on the sandbox: the "Marked
 *       notification read" log fired but no row persisted). A fresh
 *       transaction guarantees a real commit; the GraphQL mark-read
 *       mutation call is effectively outermost, so its semantics are
 *       unchanged.</li>
 * </ul>
 *
 * <h3>What this service does NOT do</h3>
 * <ul>
 *   <li>Authorize the caller. Auth lives at the {@code DataFetcher} layer
 *       so the service can be reused by future producer-side helpers
 *       (e.g. "mark all on this subscription as read after PREVIEW window
 *       closes") without re-validating JWT context.</li>
 *   <li>Cascade-delete on user removal or delivery deletion. The
 *       {@code @Table} declares no foreign keys per
 *       {@code coding_principles.md}; cleanup is the operator's call
 *       (or a future scheduled sweeper).</li>
 * </ul>
 */
@Service
@Slf4j
public class NotificationReadService {

	@Autowired
	private NotificationReadRepository readRepo;

	@Autowired
	private NotificationDeliveryRepository deliveryRepo;

	@Autowired
	private AuditService auditService;

	/**
	 * Idempotent upsert. Returns the persisted row whether it was created
	 * now or already present. The {@code read_at} timestamp is set on
	 * first create and left untouched on subsequent calls — a re-read
	 * is a no-op, not a touch (the read_at column tracks the FIRST read,
	 * not the latest, so a "you read this 3 days ago" UI cue would have
	 * a stable anchor).
	 *
	 * <p>The unique constraint on (user_uuid, delivery_uuid) guards against
	 * a race where two concurrent {@code markRead} calls for the same
	 * (user, delivery) both find {@code Optional.empty()} on the lookup.
	 * The loser's insert violates the constraint; the method catches it
	 * and re-fetches the winner's row, and with REQUIRES_NEW any rollback
	 * stays confined to this call — callers need no retry, the end state
	 * is identical either way.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public NotificationRead markRead(UUID userUuid, UUID deliveryUuid, WhoUpdated wu) throws RelizaException {
		if (userUuid == null) throw new RelizaException("userUuid is required");
		if (deliveryUuid == null) throw new RelizaException("deliveryUuid is required");
		Optional<NotificationRead> existing = readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid);
		if (existing.isPresent()) {
			// Idempotent — return the original row. Do NOT touch the
			// read_at timestamp; a stable "first read" anchor matters
			// more than tracking re-reads for the inbox MVP.
			return existing.get();
		}
		NotificationRead nr = new NotificationRead();
		nr.setUuid(UUID.randomUUID());
		nr.setUserUuid(userUuid);
		nr.setDeliveryUuid(deliveryUuid);
		// recordData is intentionally nullable on this junction table
		// (the flat columns carry the read-state); but
		// WhoUpdated.injectWhoUpdatedData expects a non-null map to put
		// the audit attribution into. Initialize empty so the inject
		// has somewhere to write.
		nr.setRecordData(new HashMap<>());
		nr = (NotificationRead) WhoUpdated.injectWhoUpdatedData(nr, wu);
		try {
			NotificationRead saved = readRepo.save(nr);
			// Audit the read action — read-state mutations are
			// operator-visible ("did Alice acknowledge this delivery?")
			// and the layer-1 review on Phase 14 flagged the missing
			// emission as a real convention break vs sibling services.
			// Captures the post-save state since this is a create —
			// there's no pre-state to preserve.
			auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_READ, saved);
			log.info("Marked notification read user={} delivery={}", userUuid, deliveryUuid);
			return saved;
		} catch (DataIntegrityViolationException e) {
			// Concurrent double-click race: both calls observed an empty
			// findByUserUuidAndDeliveryUuid, both tried to insert. The
			// unique constraint rejects the loser. Re-fetch to return
			// the winning row so the contract stays "always returns the
			// row for this (user, delivery) pair." Idempotent end state.
			log.debug("markRead race winner already inserted; returning existing row");
			return readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid)
					.orElseThrow(() -> new RelizaException(
							"markRead race: constraint violated but no row found"));
		}
	}

	/**
	 * Bulk variant of {@link #markRead}. Each (user, delivery) pair is
	 * idempotent. The whole call runs in one transaction so a partial
	 * failure doesn't leave the inbox view inconsistent between two
	 * page renders. Returns the count of newly-created rows (not the
	 * total count visited — caller can compute that if needed).
	 */
	@Transactional
	public int markManyRead(UUID userUuid, Collection<UUID> deliveryUuids, WhoUpdated wu) throws RelizaException {
		if (userUuid == null) throw new RelizaException("userUuid is required");
		if (deliveryUuids == null || deliveryUuids.isEmpty()) return 0;
		int created = 0;
		for (UUID deliveryUuid : deliveryUuids) {
			if (deliveryUuid == null) continue;
			Optional<NotificationRead> existing = readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid);
			if (existing.isPresent()) continue;
			NotificationRead nr = new NotificationRead();
			nr.setUuid(UUID.randomUUID());
			nr.setUserUuid(userUuid);
			nr.setDeliveryUuid(deliveryUuid);
			nr.setRecordData(new HashMap<>());
			nr = (NotificationRead) WhoUpdated.injectWhoUpdatedData(nr, wu);
			NotificationRead saved = readRepo.save(nr);
			auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_READ, saved);
			created++;
		}
		if (created > 0) {
			log.info("Bulk-marked {} notification(s) read for user={}", created, userUuid);
		}
		return created;
	}

	/**
	 * Delete-if-present. Returns {@code true} when a row was actually
	 * removed, {@code false} when the (user, delivery) pair was already
	 * unread. The contract intentionally hides the underlying lookup
	 * cost — the caller doesn't need to distinguish "never read" from
	 * "previously unread" outcomes.
	 */
	@Transactional
	public boolean markUnread(UUID userUuid, UUID deliveryUuid) throws RelizaException {
		if (userUuid == null) throw new RelizaException("userUuid is required");
		if (deliveryUuid == null) throw new RelizaException("deliveryUuid is required");
		Optional<NotificationRead> existing = readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid);
		if (existing.isEmpty()) return false;
		// Capture the row state pre-delete so the audit retains the
		// first-read anchor (read_at) for forensic queries — once the
		// row is deleted there's no other place that timestamp lives.
		auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_READ, existing.get());
		readRepo.deleteById(existing.get().getUuid());
		log.info("Marked notification unread user={} delivery={}", userUuid, deliveryUuid);
		return true;
	}

	/**
	 * Bulk-project read-state for an inbox page render. Returns the set
	 * of delivery UUIDs the user has marked read out of the supplied
	 * list. The fetcher uses this to set {@code readAt} on each
	 * {@code NotificationInboxItem} without an N+1 over the page.
	 *
	 * <p>Empty input → empty output (no DB hit).
	 */
	public Set<UUID> findReadDeliveryUuidsForUser(UUID userUuid, Collection<UUID> deliveryUuids) {
		if (userUuid == null || deliveryUuids == null || deliveryUuids.isEmpty()) {
			return Collections.emptySet();
		}
		// Single bulk-IN query against the (user_uuid, delivery_uuid)
		// composite unique index. One round-trip vs. N indexed lookups.
		List<NotificationRead> rows = readRepo.findByUserUuidAndDeliveryUuidIn(userUuid, deliveryUuids);
		Set<UUID> readUuids = new HashSet<>(rows.size());
		for (NotificationRead row : rows) readUuids.add(row.getDeliveryUuid());
		return readUuids;
	}

	/**
	 * Bulk variant of {@link #findReadDeliveryUuidsForUser} that also
	 * returns the per-row {@code read_at} timestamp so the inbox
	 * projector can surface the real first-read anchor instead of a
	 * stand-in. Same single-IN round-trip as the set variant.
	 */
	public Map<UUID, ZonedDateTime> findReadAtForUser(UUID userUuid, Collection<UUID> deliveryUuids) {
		if (userUuid == null || deliveryUuids == null || deliveryUuids.isEmpty()) {
			return Collections.emptyMap();
		}
		List<NotificationRead> rows = readRepo.findByUserUuidAndDeliveryUuidIn(userUuid, deliveryUuids);
		Map<UUID, ZonedDateTime> out = new HashMap<>(rows.size());
		for (NotificationRead row : rows) out.put(row.getDeliveryUuid(), row.getReadAt());
		return out;
	}

	/**
	 * Cleanup hook for a delivery that's being permanently removed (not
	 * currently exercised by any caller, but exposed so a future
	 * row-retention sweeper can keep the junction table from accumulating
	 * orphan rows after a delivery hard-delete).
	 */
	@Transactional
	public void deleteAllForDelivery(UUID deliveryUuid) {
		if (deliveryUuid == null) return;
		readRepo.deleteByDeliveryUuid(deliveryUuid);
	}

	/**
	 * Convenience guard used by the {@code markNotificationRead} fetcher
	 * before authorizing — confirms the delivery actually exists. Returns
	 * the row so the caller can extract {@code org} for the auth check.
	 */
	public Optional<io.reliza.model.NotificationDelivery> getDelivery(UUID deliveryUuid) {
		if (deliveryUuid == null) return Optional.empty();
		return deliveryRepo.findById(deliveryUuid);
	}

	/**
	 * Unread count restricted to deliveries the user is permitted to see
	 * (org-admin → all org deliveries; perspective-member → deliveries
	 * whose payload's affectedReleases[*].perspectives intersect the
	 * user's perspective set; non-admin non-member → zero). Powers the
	 * inbox-tab badge.
	 *
	 * <p>Delegated to the repository's {@code countInbox} variant so the
	 * visibility predicate stays in one place.
	 */
	public long countUnread(UUID userUuid, UUID orgUuid, List<UUID> userPerspectives, boolean isOrgAdmin) {
		if (userUuid == null || orgUuid == null) return 0L;
		return deliveryRepo.countInbox(
				orgUuid, userUuid, isOrgAdmin,
				toPgUuidArrayLiteral(userPerspectives),
				/*unreadOnly*/ true, /*status*/ null, /*eventType*/ null);
	}

	/**
	 * Build a Postgres {@code uuid[]} array literal from a Java list. The
	 * native repo queries bind this as a String and use
	 * {@code CAST(:x AS uuid[])} on the SQL side; Spring Data + Hibernate
	 * don't bind {@code List<UUID>} cleanly to a {@code uuid[]} param
	 * without a custom type, so the explicit literal form is the lightest
	 * touch. Empty list → {@code "{}"} which postgres parses as an empty
	 * uuid[] and {@code = ANY('{}')} is always false (the
	 * {@code findInboxPage} predicate uses that to short-circuit
	 * non-admin non-member callers to zero rows).
	 */
	public static String toPgUuidArrayLiteral(Collection<UUID> uuids) {
		if (uuids == null || uuids.isEmpty()) return "{}";
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (UUID u : uuids) {
			if (u == null) continue;
			if (!first) sb.append(',');
			sb.append(u.toString());
			first = false;
		}
		sb.append('}');
		return sb.toString();
	}
}
