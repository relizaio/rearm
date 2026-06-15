/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.WhoUpdated;
import io.reliza.model.NotificationRead;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationReadRepository;

/**
 * Phase 14 — service-layer tests for {@link NotificationReadService}. Cover
 * the idempotent upsert, bulk variant, unread toggle, bulk read-state
 * projector, and the postgres uuid[] literal builder. Visibility-filter
 * SQL is exercised against a live DB in the integration test suite; the
 * unit tests here are mock-based.
 */
class NotificationReadServiceTest {

    private NotificationReadRepository readRepo;
    private NotificationDeliveryRepository deliveryRepo;
    private io.reliza.service.AuditService auditService;
    private NotificationReadService service;
    private WhoUpdated wu;

    @BeforeEach
    void wireMocks() throws Exception {
        readRepo = mock(NotificationReadRepository.class);
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        auditService = mock(io.reliza.service.AuditService.class);
        when(readRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        wu = mock(WhoUpdated.class);
        when(wu.getLastUpdatedBy()).thenReturn(UUID.randomUUID());

        service = new NotificationReadService();
        inject("readRepo", readRepo);
        inject("deliveryRepo", deliveryRepo);
        inject("auditService", auditService);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = NotificationReadService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    void markReadFirstCallCreatesRow() throws RelizaException {
        UUID userUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        when(readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid))
                .thenReturn(Optional.empty());

        NotificationRead result = service.markRead(userUuid, deliveryUuid, wu);

        assertNotNull(result);
        assertEquals(userUuid, result.getUserUuid());
        assertEquals(deliveryUuid, result.getDeliveryUuid());
        verify(readRepo, times(1)).save(any());
        // First-create path emits an audit record.
        verify(auditService, times(1)).createAndSaveAuditRecord(
                eq(io.reliza.common.CommonVariables.TableName.NOTIFICATION_READ), any());
    }

    @Test
    void markReadIsIdempotent() throws RelizaException {
        UUID userUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        NotificationRead existing = new NotificationRead();
        existing.setUuid(UUID.randomUUID());
        existing.setUserUuid(userUuid);
        existing.setDeliveryUuid(deliveryUuid);
        when(readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid))
                .thenReturn(Optional.of(existing));

        // First call returns existing — no save.
        NotificationRead first = service.markRead(userUuid, deliveryUuid, wu);
        assertEquals(existing.getUuid(), first.getUuid());
        verify(readRepo, never()).save(any());

        // Second call same thing.
        NotificationRead second = service.markRead(userUuid, deliveryUuid, wu);
        assertEquals(existing.getUuid(), second.getUuid());
        verify(readRepo, never()).save(any());

        // Audit emission rides the create branch; idempotent reads must
        // not produce duplicate audit rows or each open-tab refresh
        // would amplify into the audit log.
        verify(auditService, never()).createAndSaveAuditRecord(
                eq(io.reliza.common.CommonVariables.TableName.NOTIFICATION_READ), any());
    }

    @Test
    void markReadRejectsNullArgs() {
        UUID u = UUID.randomUUID();
        assertThrows(RelizaException.class, () -> service.markRead(null, u, wu));
        assertThrows(RelizaException.class, () -> service.markRead(u, null, wu));
    }

    @Test
    void markManyReadSkipsAlreadyRead() throws RelizaException {
        UUID userUuid = UUID.randomUUID();
        UUID alreadyRead = UUID.randomUUID();
        UUID unread1 = UUID.randomUUID();
        UUID unread2 = UUID.randomUUID();
        NotificationRead existing = new NotificationRead();
        existing.setUuid(UUID.randomUUID());
        when(readRepo.findByUserUuidAndDeliveryUuid(userUuid, alreadyRead))
                .thenReturn(Optional.of(existing));
        when(readRepo.findByUserUuidAndDeliveryUuid(userUuid, unread1))
                .thenReturn(Optional.empty());
        when(readRepo.findByUserUuidAndDeliveryUuid(userUuid, unread2))
                .thenReturn(Optional.empty());

        int created = service.markManyRead(userUuid, List.of(alreadyRead, unread1, unread2), wu);

        assertEquals(2, created);
        verify(readRepo, times(2)).save(any());
    }

    @Test
    void markManyReadEmptyInputIsNoOp() throws RelizaException {
        UUID userUuid = UUID.randomUUID();
        int created = service.markManyRead(userUuid, List.of(), wu);
        assertEquals(0, created);
        verify(readRepo, never()).save(any());
    }

    @Test
    void markUnreadReturnsTrueWhenRowRemoved() throws RelizaException {
        UUID userUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        UUID rowUuid = UUID.randomUUID();
        NotificationRead existing = new NotificationRead();
        existing.setUuid(rowUuid);
        when(readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid))
                .thenReturn(Optional.of(existing));

        boolean result = service.markUnread(userUuid, deliveryUuid);

        assertTrue(result);
        verify(readRepo).deleteById(rowUuid);
    }

    @Test
    void markUnreadReturnsFalseWhenAlreadyUnread() throws RelizaException {
        UUID userUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        when(readRepo.findByUserUuidAndDeliveryUuid(userUuid, deliveryUuid))
                .thenReturn(Optional.empty());

        boolean result = service.markUnread(userUuid, deliveryUuid);

        assertFalse(result);
        verify(readRepo, never()).deleteById(any());
    }

    @Test
    void findReadDeliveryUuidsForUserProjectsBulk() {
        UUID userUuid = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        NotificationRead nr1 = new NotificationRead();
        nr1.setUserUuid(userUuid);
        nr1.setDeliveryUuid(d1);
        NotificationRead nr3 = new NotificationRead();
        nr3.setUserUuid(userUuid);
        nr3.setDeliveryUuid(d3);
        // Bulk IN query returns the read rows for the present uuids.
        when(readRepo.findByUserUuidAndDeliveryUuidIn(eq(userUuid), any()))
                .thenReturn(List.of(nr1, nr3));

        Set<UUID> readSet = service.findReadDeliveryUuidsForUser(userUuid, List.of(d1, d2, d3));

        assertEquals(2, readSet.size());
        assertTrue(readSet.contains(d1));
        assertFalse(readSet.contains(d2));
        assertTrue(readSet.contains(d3));
    }

    @Test
    void findReadDeliveryUuidsForUserEmptyInputSkipsDb() {
        UUID userUuid = UUID.randomUUID();
        Set<UUID> readSet = service.findReadDeliveryUuidsForUser(userUuid, List.of());
        assertTrue(readSet.isEmpty());
        verify(readRepo, never()).findByUserUuidAndDeliveryUuidIn(any(), any());
    }

    @Test
    void findReadAtForUserReturnsTimestamps() {
        UUID userUuid = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        java.time.ZonedDateTime t1 = java.time.ZonedDateTime.now().minusHours(2);
        java.time.ZonedDateTime t2 = java.time.ZonedDateTime.now().minusMinutes(5);
        NotificationRead nr1 = new NotificationRead();
        nr1.setUserUuid(userUuid);
        nr1.setDeliveryUuid(d1);
        nr1.setReadAt(t1);
        NotificationRead nr2 = new NotificationRead();
        nr2.setUserUuid(userUuid);
        nr2.setDeliveryUuid(d2);
        nr2.setReadAt(t2);
        when(readRepo.findByUserUuidAndDeliveryUuidIn(eq(userUuid), any()))
                .thenReturn(List.of(nr1, nr2));

        var map = service.findReadAtForUser(userUuid, List.of(d1, d2));

        assertEquals(2, map.size());
        assertEquals(t1, map.get(d1));
        assertEquals(t2, map.get(d2));
    }

    @Test
    void toPgUuidArrayLiteralEmptyListYieldsEmptyArray() {
        // {} parses as an empty postgres uuid[] and = ANY('{}') is always
        // false. The visibility SQL relies on this to short-circuit
        // non-admin non-member callers to zero rows.
        assertEquals("{}", NotificationReadService.toPgUuidArrayLiteral(null));
        assertEquals("{}", NotificationReadService.toPgUuidArrayLiteral(List.of()));
    }

    @Test
    void toPgUuidArrayLiteralFormatsCommaSeparated() {
        UUID a = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID b = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String literal = NotificationReadService.toPgUuidArrayLiteral(List.of(a, b));
        assertEquals("{11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222}", literal);
    }

    @Test
    void markReadRunsInItsOwnTransaction() throws Exception {
        // Resolve-marks-read invokes markRead from an afterCommit
        // synchronization, where the completed transaction context is
        // still bound. A REQUIRED call there joins the dead transaction
        // and its writes are silently discarded (the mark-read log fires
        // but no row commits — observed on the sandbox during the Phase
        // 4b smoke). REQUIRES_NEW is load-bearing; this guards against a
        // well-meaning revert to the default propagation.
        var method = NotificationReadService.class.getMethod(
                "markRead", UUID.class, UUID.class, WhoUpdated.class);
        var tx = method.getAnnotation(Transactional.class);
        assertNotNull(tx, "markRead must be @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation(),
                "markRead must run REQUIRES_NEW — it is called from afterCommit "
                + "synchronizations where REQUIRED writes are silently lost");
    }

    @Test
    void countUnreadDelegatesToRepoWithPerspectivesLiteral() {
        UUID userUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        UUID perspectiveUuid = UUID.randomUUID();
        // Inbox countInbox signature now: (org, userUuid, isOrgAdmin,
        // perspectivesLiteral, unreadOnly, status, eventType). countUnread
        // passes null for both status and eventType.
        when(deliveryRepo.countInbox(eq(orgUuid), eq(userUuid), eq(false), any(), eq(true), eq(null), eq(null)))
                .thenReturn(7L);

        long count = service.countUnread(userUuid, orgUuid, List.of(perspectiveUuid), false);

        assertEquals(7L, count);
    }
}
