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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import io.reliza.model.PackageKind;
import io.reliza.model.SubmissionActOutcome;
import io.reliza.model.SubmissionNotice;
import io.reliza.model.SubmissionState;
import io.reliza.model.TeaPublicationData;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.oss.OssPerspectiveService;

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
    private OssPerspectiveService ossPerspectiveService;
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
        // The one filter of a component's stored perspectives (task TEA-8, D13), pass-through here
        // so the stamping cases see the component's set; the TEA-8 cases stub it per test.
        ossPerspectiveService = mock(OssPerspectiveService.class);
        when(ossPerspectiveService.assignablePerspectiveUuids(any(), any()))
                .thenAnswer(inv -> new ArrayList<UUID>(inv.<java.util.Collection<UUID>>getArgument(1)));
        injectSupport("ossPerspectiveService", ossPerspectiveService);

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

    /**
     * Producer-side stamping for inbox visibility (#20/#21): a release event's
     * payload must carry an {@code affectedReleases} entry whose
     * {@code componentUuid} (component-team arm) and {@code perspectives}
     * (perspective arm) the inbox visibility SQL walks. Without this, release
     * notifications are invisible to every non-admin inbox. Guards the
     * ReleaseNotificationSupport.buildAffectedReleases wiring that the
     * decoupled NotificationInboxVisibilityQueryTest can't see.
     */
    @Test
    void onReleaseCreatedStampsAffectedReleasesForInboxVisibility() {
        UUID componentUuid = UUID.randomUUID();
        UUID perspective = UUID.randomUUID();
        ComponentData cd = new ComponentData();
        cd.setUuid(componentUuid);
        cd.setName("myapp");
        cd.setType(ComponentType.COMPONENT);
        cd.setPerspectives(java.util.Set.of(perspective));
        when(getComponentService.getComponentData(any())).thenReturn(Optional.of(cd));
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());

        hook.onReleaseCreated(release(componentUuid, ReleaseLifecycle.DRAFT), false);

        NotificationOutboxEvent saved = captureSaved();
        java.util.List<?> ars = (java.util.List<?>) saved.getRecordData().get("affectedReleases");
        assertNotNull(ars, "release events must stamp affectedReleases for inbox visibility");
        assertEquals(1, ars.size());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ar = (java.util.Map<String, Object>) ars.get(0);
        assertEquals(componentUuid.toString(), String.valueOf(ar.get("componentUuid")),
                "component-team arm needs componentUuid stamped on the payload");
        java.util.Collection<?> persp = (java.util.Collection<?>) ar.get("perspectives");
        assertTrue(persp.stream().map(String::valueOf).anyMatch(s -> s.equals(perspective.toString())),
                "perspective arm needs the component's perspectives stamped");
    }

    // Task TEA-8 (design round 3, D13): only the component's assignable perspectives are stamped.

    @Test
    void onReleaseCreatedStampsOnlyAssignablePerspectives() {
        UUID org = UUID.randomUUID();
        UUID componentUuid = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        Set<UUID> stored = new LinkedHashSet<>(List.of(live, deleted));
        ComponentData cd = component(org, componentUuid, stored);
        when(getComponentService.getComponentData(any())).thenReturn(Optional.of(cd));
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        when(ossPerspectiveService.assignablePerspectiveUuids(org, stored)).thenReturn(List.of(live));

        hook.onReleaseCreated(release(componentUuid, ReleaseLifecycle.DRAFT), false);

        java.util.Map<String, Object> ar = onlyAffectedRelease(captureSaved());
        assertEquals(componentUuid.toString(), String.valueOf(ar.get("componentUuid")));
        assertEquals(Set.of(live.toString()), stampedPerspectives(ar));
    }

    @Test
    void onReleaseCreatedStampsNoPerspectiveWhenNoneIsAssignable() {
        UUID org = UUID.randomUUID();
        UUID componentUuid = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        ComponentData cd = component(org, componentUuid, Set.of(deleted));
        when(getComponentService.getComponentData(any())).thenReturn(Optional.of(cd));
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        when(ossPerspectiveService.assignablePerspectiveUuids(org, Set.of(deleted))).thenReturn(List.of());

        hook.onReleaseCreated(release(componentUuid, ReleaseLifecycle.DRAFT), false);

        // The component arm keeps working for a component whose only membership is stale.
        java.util.Map<String, Object> ar = onlyAffectedRelease(captureSaved());
        assertEquals(componentUuid.toString(), String.valueOf(ar.get("componentUuid")));
        assertEquals(Set.of(), stampedPerspectives(ar));
    }

    private static ComponentData component(UUID org, UUID uuid, Set<UUID> perspectives) {
        ComponentData cd = new ComponentData();
        cd.setUuid(uuid);
        cd.setOrg(org);
        cd.setName("myapp");
        cd.setType(ComponentType.COMPONENT);
        cd.setPerspectives(perspectives);
        return cd;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> onlyAffectedRelease(NotificationOutboxEvent saved) {
        List<?> ars = (List<?>) saved.getRecordData().get("affectedReleases");
        assertNotNull(ars);
        assertEquals(1, ars.size());
        return (java.util.Map<String, Object>) ars.get(0);
    }

    private static Set<String> stampedPerspectives(java.util.Map<String, Object> ar) {
        java.util.Collection<?> persp = (java.util.Collection<?>) ar.get("perspectives");
        return persp.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
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

    // ---------- TEA publication (task TEA-5, design 4.7 case 36) ----------

    private static final String API_BASE = "https://rearm.example.com/tea/0d7c4c5e-0000-0000-0000-000000000001";

    private TeaPublicationData teaPublication(ReleaseData rd) throws Exception {
        TeaPublicationService teaPublicationService = mock(TeaPublicationService.class);
        when(teaPublicationService.apiBaseOf(rd.getOrg())).thenReturn(API_BASE);
        injectHook("teaPublicationService", teaPublicationService);
        TeaPublicationData p = new TeaPublicationData();
        p.setUuid(UUID.randomUUID());
        p.setOrg(rd.getOrg());
        p.setRelease(rd.getUuid());
        p.setComponent(rd.getComponent());
        p.setComponentType(ComponentType.PRODUCT);
        p.setLatestVersion(3);
        return p;
    }

    @Test
    void onReleaseTeaPublishedEmitsOneRowWithThePayloadAndTheVersionKey() throws Exception {
        stubComponent(ComponentType.PRODUCT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        ReleaseData rd = release(UUID.randomUUID(), ReleaseLifecycle.GENERAL_AVAILABILITY);
        setField(rd, "uuid", UUID.randomUUID());
        TeaPublicationData p = teaPublication(rd);

        hook.onReleaseTeaPublished(rd, p, 3, TeaExposureState.PRIVATE, 2);

        NotificationOutboxEvent saved = captureSaved();
        assertEquals(NotificationEventType.RELEASE_TEA_PUBLISHED, saved.getEventType());
        assertEquals(rd.getOrg(), saved.getOrg());
        assertEquals("tea-published:" + p.getUuid() + ":3", saved.getDedupKey());
        java.util.Map<String, Object> data = saved.getRecordData();
        assertEquals(p.getUuid().toString(), String.valueOf(data.get("publication")));
        assertEquals("PUBLISHED", data.get("state"));
        assertEquals(3, ((Number) data.get("collectionVersion")).intValue());
        assertEquals("PRIVATE", data.get("exposure"));
        assertEquals(API_BASE + "/v1.0.0/productRelease/" + p.getUuid(), data.get("publicUrl"));
        assertEquals(2, ((Number) data.get("childrenPublished")).intValue());
        assertEquals("myapp", release(saved).get("componentName"));
        assertEquals("v2.0", release(saved).get("version"));
        assertEquals(rd.getUuid().toString(), String.valueOf(release(saved).get("releaseUuid")));
        assertNotNull(data.get("affectedReleases"), "TEA events stamp affectedReleases like every release event");
    }

    @Test
    void onReleaseTeaHiddenEmitsOneRowKeyedByTheMinute() throws Exception {
        stubComponent(ComponentType.COMPONENT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        ReleaseData rd = release(UUID.randomUUID(), ReleaseLifecycle.GENERAL_AVAILABILITY);
        setField(rd, "uuid", UUID.randomUUID());
        TeaPublicationData p = teaPublication(rd);
        p.setComponentType(ComponentType.COMPONENT);
        java.time.Instant before = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);

        hook.onReleaseTeaHidden(rd, p);

        java.time.Instant after = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        NotificationOutboxEvent saved = captureSaved();
        assertEquals(NotificationEventType.RELEASE_TEA_HIDDEN, saved.getEventType());
        String prefix = "tea-hidden:" + p.getUuid() + ":";
        assertTrue(saved.getDedupKey().startsWith(prefix), "Unexpected dedup key: " + saved.getDedupKey());
        java.time.Instant minute = java.time.Instant.parse(saved.getDedupKey().substring(prefix.length()));
        assertEquals(minute, minute.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), "the instant is truncated to minutes");
        assertTrue(!minute.isBefore(before) && !minute.isAfter(after), "the minute of the hide: " + minute);
        java.util.Map<String, Object> data = saved.getRecordData();
        assertEquals(p.getUuid().toString(), String.valueOf(data.get("publication")));
        assertEquals("HIDDEN", data.get("state"));
        assertEquals(null, data.get("collectionVersion"));
        assertEquals(null, data.get("exposure"));
        assertEquals(API_BASE + "/v1.0.0/componentRelease/" + p.getUuid(), data.get("publicUrl"));
        assertEquals(0, ((Number) data.get("childrenPublished")).intValue());
        assertEquals("myapp", release(saved).get("componentName"));
    }

    @Test
    void teaEventsSkipAMissingPublicationAndAnExternalRelease() throws Exception {
        ReleaseData rd = release(UUID.randomUUID(), ReleaseLifecycle.GENERAL_AVAILABILITY);
        hook.onReleaseTeaPublished(rd, null, 1, TeaExposureState.PUBLIC, 0);
        hook.onReleaseTeaHidden(rd, null);
        ReleaseData external = new ReleaseData();
        setField(external, "org", CommonVariables.EXTERNAL_PROJ_ORG_UUID);
        setField(external, "component", UUID.randomUUID());
        TeaPublicationData p = new TeaPublicationData();
        p.setUuid(UUID.randomUUID());
        hook.onReleaseTeaPublished(external, p, 1, TeaExposureState.PUBLIC, 0);
        hook.onReleaseTeaHidden(external, p);

        verify(outboxRepo, never()).save(any());
    }

    // ---------- helpers ----------

    /** Task TEA-35, design 4.4 case 12: one row per created version, the six payload fields, keyed by the version (E14). */
    @Test
    void onReleaseSubmissionCreatedEmitsOneRowWithTheSixFieldsAndTheVersionKey() {
        stubComponent(ComponentType.PRODUCT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        ReleaseData rd = release(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED);
        setField(rd, "uuid", UUID.randomUUID());
        UUID submission = UUID.randomUUID();

        hook.onReleaseSubmissionCreated(rd, new SubmissionNotice(submission, PackageKind.FDA_PREMARKET,
                SubmissionState.ACTIVE, 2, SubmissionActOutcome.RESUBMITTED));

        NotificationOutboxEvent saved = captureSaved();
        assertEquals(NotificationEventType.RELEASE_SUBMISSION_CREATED, saved.getEventType());
        assertEquals(rd.getOrg(), saved.getOrg());
        assertEquals("submission-created:" + submission + ":2", saved.getDedupKey());
        java.util.Map<String, Object> data = saved.getRecordData();
        assertEquals(Set.of("release", "submission", "kind", "state", "version", "act", "affectedReleases"),
                data.keySet(), "the payload's six fields and the stamped affected releases, and no other key");
        assertEquals(submission.toString(), String.valueOf(data.get("submission")));
        assertEquals("FDA_PREMARKET", data.get("kind"));
        assertEquals("ACTIVE", data.get("state"));
        assertEquals(2, ((Number) data.get("version")).intValue());
        assertEquals("RESUBMITTED", data.get("act"));
        assertEquals("myapp", release(saved).get("componentName"));
        assertEquals(rd.getUuid().toString(), String.valueOf(release(saved).get("releaseUuid")));
    }

    /** Task TEA-35, F-7: two withdrawals of one submission inside one minute write distinct keys, by version. */
    @Test
    void onReleaseSubmissionWithdrawnIsKeyedByTheTombstonedVersion() {
        stubComponent(ComponentType.PRODUCT);
        when(branchService.getBranchData(any())).thenReturn(Optional.empty());
        ReleaseData rd = release(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED);
        setField(rd, "uuid", UUID.randomUUID());
        UUID submission = UUID.randomUUID();

        hook.onReleaseSubmissionWithdrawn(rd, new SubmissionNotice(submission, PackageKind.FDA_PREMARKET,
                SubmissionState.WITHDRAWN, 2, SubmissionActOutcome.WITHDRAWN));
        hook.onReleaseSubmissionWithdrawn(rd, new SubmissionNotice(submission, PackageKind.FDA_PREMARKET,
                SubmissionState.WITHDRAWN, 3, SubmissionActOutcome.WITHDRAWN));

        ArgumentCaptor<NotificationOutboxEvent> captor = ArgumentCaptor.forClass(NotificationOutboxEvent.class);
        verify(outboxRepo, times(2)).save(captor.capture());
        List<NotificationOutboxEvent> rows = captor.getAllValues();
        assertEquals(NotificationEventType.RELEASE_SUBMISSION_WITHDRAWN, rows.get(0).getEventType());
        assertEquals("submission-withdrawn:" + submission + ":2", rows.get(0).getDedupKey());
        assertEquals("submission-withdrawn:" + submission + ":3", rows.get(1).getDedupKey());
        assertEquals("WITHDRAWN", rows.get(0).getRecordData().get("act"));
    }

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
