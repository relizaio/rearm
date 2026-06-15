/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.CommonVariables;
import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.AcollectionData.DiffComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.repositories.NotificationOutboxEventRepository;

/**
 * Unit surface for the shared release producer hook. Each test stubs
 * the outbox repo (plus the read services {@code buildReleaseRef} needs)
 * and asserts on the captured outbox row's event type, dedup key, and
 * serialized payload.
 *
 * <p>After the producer refactor the outbox row is written by
 * {@link ReleaseNotificationSupport}; this test wires a real support
 * instance (with the same mocked collaborators) into a real
 * {@link ReleaseChangeHookImpl} so the captured {@code outboxRepo.save(...)}
 * assertions are preserved 1:1.
 *
 * <p>Notable scenarios:
 * <ul>
 *   <li>Create / lifecycle / BOM-diff each emit their own event type with
 *       a distinct dedup key (new lifecycle is part of the lifecycle key
 *       so DRAFT&rarr;ASSEMBLED doesn't collapse against itself).</li>
 *   <li>External-org and null-org releases never notified on the legacy
 *       path — the hook preserves that exclusion.</li>
 *   <li>BOM-diff keeps the legacy both-sides gate: nothing emits unless
 *       there is at least one added AND one removed component.</li>
 *   <li>A missing component short-circuits to no-op (no half-built ref).</li>
 *   <li>Outbox repo throw is swallowed (a failed notification must not
 *       roll back the release write that triggered it).</li>
 * </ul>
 */
class ReleaseChangeHookImplTest {

    private NotificationOutboxEventRepository outboxRepo;
    private GetComponentService getComponentService;
    private BranchService branchService;
    private ReleaseNotificationSupport support;
    private ReleaseChangeHookImpl hook;

    @BeforeEach
    void wireMocks() throws Exception {
        outboxRepo = mock(NotificationOutboxEventRepository.class);
        getComponentService = mock(GetComponentService.class);
        branchService = mock(BranchService.class);
        GetSourceCodeEntryService getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
        VcsRepositoryService vcsRepositoryService = mock(VcsRepositoryService.class);
        UserService userService = mock(UserService.class);

        support = new ReleaseNotificationSupport(userService);
        injectSupport("outboxRepo", outboxRepo);
        injectSupport("getComponentService", getComponentService);
        injectSupport("branchService", branchService);
        injectSupport("getSourceCodeEntryService", getSourceCodeEntryService);
        injectSupport("vcsRepositoryService", vcsRepositoryService);

        hook = new ReleaseChangeHookImpl();
        injectHook("support", support);
    }

    private void injectSupport(String field, Object value) throws Exception {
        Field f = ReleaseNotificationSupport.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(support, value);
    }

    private void injectHook(String field, Object value) throws Exception {
        Field f = ReleaseChangeHookImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(hook, value);
    }

    @Test
    void onReleaseCreatedEmitsRow() {
        stubComponent(ComponentType.COMPONENT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());

        hook.onReleaseCreated(release(UUID.randomUUID(), ReleaseLifecycle.DRAFT), false);

        NotificationOutboxEvent saved = captureSaved();
        assertEquals(NotificationEventType.RELEASE_CREATED, saved.getEventType());
        assertEquals(NotificationOutboxStatus.PENDING, saved.getStatus());
        assertEquals(NotificationDeliveryOrigin.REAL, saved.getOrigin());
        assertTrue(saved.getDedupKey().startsWith("release:created:"),
                "Unexpected dedup key: " + saved.getDedupKey());
        assertEquals(Boolean.FALSE, saved.getRecordData().get("scheduled"));
        assertEquals("myapp", release(saved).get("componentName"));
        assertEquals("v2.0", release(saved).get("version"));
    }

    @Test
    void onReleaseCreatedScheduledSetsScheduledTrue() {
        stubComponent(ComponentType.PRODUCT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());

        hook.onReleaseCreated(release(UUID.randomUUID(), ReleaseLifecycle.PENDING), true);

        NotificationOutboxEvent saved = captureSaved();
        assertEquals(Boolean.TRUE, saved.getRecordData().get("scheduled"));
    }

    @Test
    void onReleaseCreatedSkipsExternalOrg() {
        ReleaseData rd = new ReleaseData();
        setField(rd, "org", CommonVariables.EXTERNAL_PROJ_ORG_UUID);
        setField(rd, "component", UUID.randomUUID());

        hook.onReleaseCreated(rd, false);

        verify(outboxRepo, never()).save(any());
    }

    @Test
    void onReleaseCreatedSkipsNullOrg() {
        ReleaseData rd = new ReleaseData();
        setField(rd, "component", UUID.randomUUID());

        hook.onReleaseCreated(rd, false);

        verify(outboxRepo, never()).save(any());
    }

    @Test
    void onReleaseCreatedSkipsWhenComponentMissing() {
        when(getComponentService.getComponentData(any())).thenReturn(Optional.empty());

        hook.onReleaseCreated(release(UUID.randomUUID(), ReleaseLifecycle.DRAFT), false);

        verify(outboxRepo, never()).save(any());
    }

    @Test
    void onReleaseLifecycleChangedKeysOnNewLifecycle() {
        stubComponent(ComponentType.COMPONENT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());

        hook.onReleaseLifecycleChanged(release(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED),
                ReleaseLifecycle.DRAFT, ReleaseLifecycle.ASSEMBLED);

        NotificationOutboxEvent saved = captureSaved();
        assertEquals(NotificationEventType.RELEASE_LIFECYCLE_CHANGED, saved.getEventType());
        assertTrue(saved.getDedupKey().startsWith("release:lc:"),
                "Unexpected dedup key: " + saved.getDedupKey());
        assertTrue(saved.getDedupKey().endsWith(":ASSEMBLED"),
                "Dedup key should carry the new lifecycle: " + saved.getDedupKey());
        assertEquals("DRAFT", saved.getRecordData().get("oldLifecycle"));
        assertEquals("ASSEMBLED", saved.getRecordData().get("newLifecycle"));
    }

    @Test
    void onReleaseLifecycleChangedSkipsNullNewLifecycle() {
        hook.onReleaseLifecycleChanged(release(UUID.randomUUID(), ReleaseLifecycle.DRAFT),
                ReleaseLifecycle.DRAFT, null);

        verify(outboxRepo, never()).save(any());
    }

    @Test
    void onReleaseBomDiffEmitsWhenBothSidesNonEmpty() {
        stubComponent(ComponentType.COMPONENT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        ArtifactChangelog changelog = new ArtifactChangelog(
                Set.of(new DiffComponent("pkg:npm/added@1.0", "1.0")),
                Set.of(new DiffComponent("pkg:npm/removed@0.9", "0.9")));

        hook.onReleaseBomDiff(release(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED), changelog);

        NotificationOutboxEvent saved = captureSaved();
        assertEquals(NotificationEventType.RELEASE_BOM_DIFF, saved.getEventType());
        assertTrue(saved.getDedupKey().startsWith("release:bomdiff:"),
                "Unexpected dedup key: " + saved.getDedupKey());
        assertNotNull(saved.getRecordData().get("added"));
        assertNotNull(saved.getRecordData().get("removed"));
    }

    @Test
    void onReleaseBomDiffSkipsWhenOneSideEmpty() {
        // Mirrors the legacy gate: an add-only (or remove-only) reconcile
        // is not a "diff" worth paging on.
        ArtifactChangelog addOnly = new ArtifactChangelog(
                Set.of(new DiffComponent("pkg:npm/added@1.0", "1.0")),
                Set.of());

        hook.onReleaseBomDiff(release(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED), addOnly);

        verify(outboxRepo, never()).save(any());
    }

    @Test
    void onReleaseBomDiffSkipsNullChangelog() {
        hook.onReleaseBomDiff(release(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED), null);

        verify(outboxRepo, never()).save(any());
    }

    @Test
    void survivesOutboxRepoThrow() {
        stubComponent(ComponentType.COMPONENT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        when(outboxRepo.save(any())).thenThrow(new RuntimeException("outbox down"));

        // No exception escapes the hook.
        hook.onReleaseCreated(release(UUID.randomUUID(), ReleaseLifecycle.DRAFT), false);

        verify(outboxRepo, times(1)).save(any());
    }

    // ---------- helpers ----------

    private void stubComponent(ComponentType type) {
        ComponentData cd = new ComponentData();
        cd.setUuid(UUID.randomUUID());
        cd.setName("myapp");
        cd.setType(type);
        when(getComponentService.getComponentData(any())).thenReturn(Optional.of(cd));
    }

    private static ReleaseData release(UUID component, ReleaseLifecycle lifecycle) {
        // org / component / branch / uuid all have private (audit-controlled)
        // setters, so we reflect them in. The uuid stays null — the producer
        // reads it for the dedup key but tests assert on key prefixes/
        // suffixes rather than the uuid itself, so that's fine.
        ReleaseData rd = new ReleaseData();
        setField(rd, "org", UUID.randomUUID());
        setField(rd, "component", component);
        setField(rd, "branch", UUID.randomUUID());
        rd.setVersion("v2.0");
        rd.setLifecycle(lifecycle);
        return rd;
    }

    private static void setField(Object target, String field, Object value) {
        try {
            Field f = findField(target.getClass(), field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + field, e);
        }
    }

    private static Field findField(Class<?> type, String field) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
                // walk up the hierarchy
            }
        }
        throw new NoSuchFieldException(field);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> release(NotificationOutboxEvent saved) {
        return (java.util.Map<String, Object>) saved.getRecordData().get("release");
    }

    private NotificationOutboxEvent captureSaved() {
        ArgumentCaptor<NotificationOutboxEvent> captor = ArgumentCaptor.forClass(NotificationOutboxEvent.class);
        verify(outboxRepo, times(1)).save(captor.capture());
        NotificationOutboxEvent saved = captor.getValue();
        assertNotNull(saved);
        return saved;
    }
}
