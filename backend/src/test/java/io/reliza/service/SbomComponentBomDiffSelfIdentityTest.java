/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.AcollectionData.DiffComponent;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SbomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;

/**
 * TPF-146: {@link SbomComponentService#collectReleaseInventory} leaves out the release's own
 * identities at every version -- the versionless key of the single root of each of its own
 * canonical artifacts and of each PURL identifier on its component(s) -- besides the rows flagged
 * root, and both consumers ({@link SbomComponentService#postReconcileBomDiff}: the cached changelog
 * and the notification) see the corrected inventory. Row numbers are those of design section 6.2
 * (design/TPF-146/architecture-4.md).
 */
class SbomComponentBomDiffSelfIdentityTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID OTHER = UUID.randomUUID();
	private static final UUID BRANCH = UUID.randomUUID();

	private static final String APP_PREFIX = "pkg:maven/io.reliza/rearm-pro-backend";
	private static final String APP_OLD = APP_PREFIX + "@2026-10-pro-cve-sweep.0";
	private static final String OCI = "pkg:oci/rearm-backend?repository_url=registry.relizahub.com/reliza/rearm-backend";
	private static final String SPRING = "pkg:maven/org.springframework/spring-core@6.2.1";
	private static final String CPE_ROOT = "cpe:2.3:a:reliza:device-fw:2.0:*:*:*:*:*:*:*";
	private static final String LEFT_PAD = "pkg:npm/left-pad@1.3.0";
	private static final String CPE_PART = "cpe:2.3:a:acme:bootloader:1.4:*:*:*:*:*:*:*";

	private SharedReleaseService sharedReleaseService;
	private GetComponentService getComponentService;
	private ReleaseArtifactIndexRepository indexRepository;
	private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	private SbomComponentRepository componentRepository;
	private AcollectionService acollectionService;
	private ReleaseRepository releaseRepository;
	private ReleaseChangeHook releaseChangeHook;
	private SbomComponentService service;

	/** Org-wide sbom_components rows, one per canonical purl. */
	private final Map<UUID, SbomComponent> store = new HashMap<>();
	private final Map<String, SbomComponent> byPurl = new HashMap<>();
	/** release uuid -> canonical artifacts indexed for it. */
	private final Map<UUID, List<UUID>> indexByRelease = new HashMap<>();
	/** canonical artifact -> its artifact_sbom_components rows. */
	private final Map<UUID, List<ArtifactSbomComponent>> rowsByCanonical = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		indexRepository = mock(ReleaseArtifactIndexRepository.class);
		componentRepository = mock(SbomComponentRepository.class);
		artifactSbomComponentRepository = mock(ArtifactSbomComponentRepository.class);
		service = new SbomComponentService(componentRepository, artifactSbomComponentRepository,
				indexRepository, mock(ArtifactCanonicalMapRepository.class),
				mock(SbomComponentSupportRepository.class), mock(SbomComponentSupportAuditRepository.class));
		sharedReleaseService = mock(SharedReleaseService.class);
		getComponentService = mock(GetComponentService.class);
		acollectionService = mock(AcollectionService.class);
		releaseRepository = mock(ReleaseRepository.class);
		releaseChangeHook = mock(ReleaseChangeHook.class);
		ReflectionTestUtils.setField(service, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "acollectionService", acollectionService);
		ReflectionTestUtils.setField(service, "releaseRepository", releaseRepository);
		ReflectionTestUtils.setField(service, "releaseChangeHook", releaseChangeHook);
		when(releaseRepository.claimBomDiffNotification(any())).thenReturn(1);

		when(componentRepository.findByOrgAndUuidIn(eq(ORG.toString()), anyString())).thenAnswer(inv -> {
			Set<String> wanted = new LinkedHashSet<>(Arrays.asList(((String) inv.getArgument(1)).split(",")));
			List<SbomComponent> out = new ArrayList<>();
			for (SbomComponent sc : store.values()) {
				if (wanted.contains(sc.getUuid().toString())) out.add(sc);
			}
			return out;
		});
		when(indexRepository.findByOrgAndReleaseUuidIn(eq(ORG), anyCollection())).thenAnswer(inv -> {
			Collection<UUID> releases = inv.getArgument(1);
			List<ReleaseArtifactIndex> out = new ArrayList<>();
			for (UUID r : releases) {
				for (UUID canonical : indexByRelease.getOrDefault(r, List.of())) {
					ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
					idx.setCanonicalArtifactUuid(canonical);
					out.add(idx);
				}
			}
			return out;
		});
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuidIn(eq(ORG), anyCollection()))
				.thenAnswer(inv -> {
					Collection<UUID> canonicals = inv.getArgument(1);
					List<ArtifactSbomComponent> out = new ArrayList<>();
					for (UUID c : canonicals) out.addAll(rowsByCanonical.getOrDefault(c, List.of()));
					return out;
				});
	}

	// ------------------------------------------------------------------ vocabulary

	private static String app(int n) {
		return APP_PREFIX + "@2026-10-tpf-142-tea-custom-domain." + n;
	}

	private static String lodash(int m) {
		return "pkg:npm/lodash@4.17." + m;
	}

	private static String lib(String v) {
		return "pkg:maven/io.reliza/reliza-commons@" + v;
	}

	private static String versionOf(String purl) {
		int at = purl.indexOf('@');
		if (at < 0) return null;
		int q = purl.indexOf('?', at);
		return q < 0 ? purl.substring(at + 1) : purl.substring(at + 1, q);
	}

	/** The org-wide row of a canonical purl; flagged root when {@code root}. */
	private SbomComponent sc(String canonicalPurl, boolean root) {
		SbomComponent existing = byPurl.get(canonicalPurl);
		if (existing != null) return existing;
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rd = new HashMap<>();
		String version = versionOf(canonicalPurl);
		if (version != null) rd.put("version", version);
		if (root) rd.put("isRoot", true);
		sc.setRecordData(rd);
		store.put(sc.getUuid(), sc);
		byPurl.put(canonicalPurl, sc);
		return sc;
	}

	private SbomComponent sc(String canonicalPurl) {
		return sc(canonicalPurl, false);
	}

	private UUID canonical() {
		UUID c = UUID.randomUUID();
		rowsByCanonical.put(c, new ArrayList<>());
		return c;
	}

	/** An artifact_sbom_components row of {@code canonical} with one in-edge per parent. */
	private void row(UUID canonical, SbomComponent sc, SbomComponent... parents) {
		ArtifactSbomComponent r = new ArtifactSbomComponent();
		r.setCanonicalArtifactUuid(canonical);
		r.setSbomComponentUuid(sc.getUuid());
		List<Map<String, Object>> ps = new ArrayList<>();
		for (SbomComponent p : parents) {
			Map<String, Object> edge = new HashMap<>();
			edge.put("sourceSbomComponentUuid", p.getUuid().toString());
			edge.put("sourceCanonicalPurl", p.getCanonicalPurl());
			edge.put("relationshipType", "DEPENDS_ON");
			ps.add(edge);
		}
		r.setParents(ps);
		rowsByCanonical.get(canonical).add(r);
	}

	/** SCE_OLD: the deduplicated source SBOM, root APP_OLD (flagged), SPRING under it. */
	private UUID sceOld() {
		UUID c = canonical();
		SbomComponent root = sc(APP_OLD, true);
		row(c, root);
		row(c, sc(SPRING), root);
		return c;
	}

	/** IMG(n, m): image root OCI (flagged), APP(n) and LODASH(m) under it. */
	private UUID img(int n, int m) {
		UUID c = canonical();
		SbomComponent root = sc(OCI, true);
		row(c, root);
		row(c, sc(app(n)), root);
		row(c, sc(lodash(m)), root);
		return c;
	}

	/** IMG(n, m) plus LEFT_PAD under the image root: an added dependency next to LODASH(m) (TPF-147). */
	private UUID imgWithLeftPad(int n, int m) {
		UUID c = canonical();
		SbomComponent root = sc(OCI, true);
		row(c, root);
		row(c, sc(app(n)), root);
		row(c, sc(lodash(m)), root);
		row(c, sc(LEFT_PAD), root);
		return c;
	}

	private static Set<String> purls(Set<DiffComponent> changes) {
		Set<String> out = new LinkedHashSet<>();
		for (DiffComponent dc : changes) out.add(dc.purl());
		return out;
	}

	private ComponentData componentData(UUID uuid, UUID org, ComponentType type, RearmIdentifier... ids) {
		ComponentData cd = new ComponentData();
		ReflectionTestUtils.setField(cd, "uuid", uuid);
		cd.setType(type);
		cd.setOrg(org);
		cd.setIdentifiers(new ArrayList<>(Arrays.asList(ids)));
		return cd;
	}

	private ReleaseData release(ComponentData cd, UUID... canonicals) {
		UUID component = cd.getUuid();
		when(getComponentService.getComponentData(component)).thenReturn(Optional.of(cd));
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", component);
		ReflectionTestUtils.setField(rd, "branch", BRANCH);
		ReflectionTestUtils.setField(rd, "lifecycle", ReleaseLifecycle.ASSEMBLED);
		when(sharedReleaseService.getReleaseDataLight(rd.getUuid())).thenReturn(Optional.of(rd));
		indexByRelease.put(rd.getUuid(), new ArrayList<>(Arrays.asList(canonicals)));
		return rd;
	}

	private ReleaseData release(ComponentType type, RearmIdentifier[] ids, UUID... canonicals) {
		return release(componentData(UUID.randomUUID(), ORG, type, ids), canonicals);
	}

	private ReleaseData release(UUID... canonicals) {
		return release(ComponentType.COMPONENT, new RearmIdentifier[0], canonicals);
	}

	private static RearmIdentifier purlId(String value) {
		return new RearmIdentifier(RearmIdentifierType.PURL, value);
	}

	private Set<String> inventory(ReleaseData rd) {
		return service.collectReleaseInventory(rd.getUuid()).keySet();
	}

	private static Set<String> setOf(String... purls) {
		return new LinkedHashSet<>(Arrays.asList(purls));
	}

	private static ArtifactChangelog lodashOnly(int added, int removed) {
		return new ArtifactChangelog(Set.of(new DiffComponent(lodash(added), "4.17." + added)),
				Set.of(new DiffComponent(lodash(removed), "4.17." + removed)));
	}

	private ArtifactChangelog cachedChangelog(ReleaseData rd, ReleaseData compared) {
		ArgumentCaptor<ArtifactChangelog> captor = ArgumentCaptor.forClass(ArtifactChangelog.class);
		verify(acollectionService).cacheReleaseChangelog(eq(rd.getUuid()), eq(compared.getUuid()), captor.capture());
		return captor.getValue();
	}

	private ArtifactChangelog notifiedChangelog(ReleaseData rd) {
		ArgumentCaptor<ArtifactChangelog> captor = ArgumentCaptor.forClass(ArtifactChangelog.class);
		verify(releaseChangeHook).onReleaseBomDiff(eq(rd), captor.capture());
		return captor.getValue();
	}

	private static void assertNoSelfPurls(ArtifactChangelog cl) {
		List<DiffComponent> all = new ArrayList<>(cl.added());
		all.addAll(cl.removed());
		for (DiffComponent dc : all) {
			assertFalse(dc.purl().startsWith(APP_PREFIX), dc.purl());
			assertFalse(dc.purl().startsWith("pkg:oci/"), dc.purl());
		}
	}

	private void predecessor(ReleaseData rd, ReleaseData prev) {
		when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(BRANCH, rd.getUuid())).thenReturn(prev.getUuid());
	}

	private void successor(ReleaseData rd, ReleaseData next) {
		when(sharedReleaseService.findNextReleasesOfBranchForRelease(BRANCH, rd.getUuid())).thenReturn(next.getUuid());
	}

	// ------------------------------------------------------------------ rows

	/** Row 1: the evidence shape -- APP_OLD and OCI by the flag, APP(3) by the self key. */
	@Test
	void row1EvidenceShape() {
		ReleaseData n = release(sceOld(), img(3, 21));
		assertEquals(setOf(SPRING, lodash(21)), inventory(n));
	}

	/** Row 2: the image jar across two releases; cache and notification list neither jar. */
	@Test
	void row2ImageJarAcrossTwoReleasesCacheAndNotification() {
		UUID sce = sceOld();
		ReleaseData n = release(sce, img(3, 21));
		ReleaseData prev = release(sce, img(2, 20));
		predecessor(n, prev);

		service.postReconcileBomDiff(n.getUuid());

		ArtifactChangelog cached = cachedChangelog(n, prev);
		ArtifactChangelog notified = notifiedChangelog(n);
		assertEquals(lodashOnly(21, 20), cached);
		assertEquals(lodashOnly(21, 20), notified);
		assertNoSelfPurls(cached);
		assertNoSelfPurls(notified);
	}

	/**
	 * Row 2b: the release's only change is its own jar, so the cached changelog is empty on both sides,
	 * and an empty diff neither notifies nor spends the one-shot claim (TPF-147 design 6.2 row 6).
	 */
	@Test
	void row2bOwnJarOnlyChangeIsEmptyAndSpendsNoClaim() {
		UUID sce = sceOld();
		ReleaseData n = release(sce, img(3, 21));
		ReleaseData prev = release(sce, img(2, 21));
		predecessor(n, prev);

		service.postReconcileBomDiff(n.getUuid());

		ArtifactChangelog empty = new ArtifactChangelog(Set.of(), Set.of());
		assertEquals(empty, cachedChangelog(n, prev));
		verify(releaseRepository, never()).claimBomDiffNotification(any());
		verifyNoInteractions(releaseChangeHook);
	}

	/**
	 * TPF-147 design 6.2 row 7: an empty first diff leaves the claim unspent, so a later reconcile that
	 * finds a real change claims and notifies exactly once.
	 */
	@Test
	void emptyThenNonEmptyNotifiesOnce() {
		UUID sce = sceOld();
		ReleaseData n = release(sce, img(3, 21));
		ReleaseData prev = release(sce, img(2, 21));
		predecessor(n, prev);

		service.postReconcileBomDiff(n.getUuid());
		verify(releaseRepository, never()).claimBomDiffNotification(any());
		verifyNoInteractions(releaseChangeHook);

		// n's image now carries LODASH(22); the predecessor keeps LODASH(21): a real change
		indexByRelease.put(n.getUuid(), new ArrayList<>(List.of(sce, img(3, 22))));
		service.postReconcileBomDiff(n.getUuid());

		verify(releaseRepository, times(1)).claimBomDiffNotification(n.getUuid());
		assertEquals(lodashOnly(22, 21), notifiedChangelog(n));
	}

	/**
	 * TPF-147 design 6.2 row 8: an add-only diff (a newly introduced dependency next to an unchanged
	 * one) claims and notifies, with nothing on the removed side.
	 */
	@Test
	void addOnlyDiffClaimsAndNotifies() {
		UUID sce = sceOld();
		ReleaseData n = release(sce, imgWithLeftPad(3, 21));
		ReleaseData prev = release(sce, img(2, 21));
		predecessor(n, prev);

		service.postReconcileBomDiff(n.getUuid());

		verify(releaseRepository, times(1)).claimBomDiffNotification(n.getUuid());
		ArtifactChangelog notified = notifiedChangelog(n);
		assertEquals(Set.of(LEFT_PAD), purls(notified.added()));
		assertEquals(Set.of(), notified.removed());
		assertNoSelfPurls(notified);
	}

	/** TPF-147 design 6.2 row 9: the one-shot holds; a claim already spent never notifies. */
	@Test
	void claimAlreadySpentDoesNotNotify() {
		when(releaseRepository.claimBomDiffNotification(any())).thenReturn(0);
		UUID sce = sceOld();
		ReleaseData n = release(sce, imgWithLeftPad(3, 21));
		ReleaseData prev = release(sce, img(2, 21));
		predecessor(n, prev);

		service.postReconcileBomDiff(n.getUuid());

		verify(releaseRepository, times(1)).claimBomDiffNotification(n.getUuid());
		verify(releaseChangeHook, never()).onReleaseBomDiff(any(), any());
	}

	/** Row 3: a source SBOM deduplicated to an older canonical still supplies the maven key. */
	@Test
	void row3DeduplicatedSourceSbomSuppliesKey() {
		ReleaseData n = release(sceOld(), img(3, 21));
		Set<String> inv = inventory(n);
		assertFalse(inv.contains(app(3)));
		assertFalse(inv.contains(APP_OLD));

		// Control: the image alone gives only the oci key, so APP(3) is listed.
		ReleaseData imageOnly = release(img(3, 21));
		assertTrue(inventory(imageOnly).contains(app(3)));
	}

	/** Row 4: an image-only component with a declared PURL identifier, and its controls. */
	@Test
	void row4ImageOnlyComponentWithPurlIdentifier() {
		ComponentData cd = componentData(UUID.randomUUID(), ORG, ComponentType.COMPONENT,
				purlId("pkg:maven/io.reliza/rearm-pro-backend"));
		ReleaseData n = release(cd, img(3, 21));
		assertEquals(setOf(lodash(21)), inventory(n));

		cd.setIdentifiers(List.of(purlId("pkg:maven/io.reliza/rearm-pro-backend@1.0.0?type=jar")));
		assertEquals(setOf(lodash(21)), inventory(n));

		cd.setIdentifiers(List.of());
		assertEquals(setOf(app(3), lodash(21)), inventory(n));

		cd.setIdentifiers(List.of(new RearmIdentifier(RearmIdentifierType.CPE,
				"cpe:2.3:a:reliza:rearm-pro-backend:*:*:*:*:*:*:*:*")));
		assertEquals(setOf(app(3), lodash(21)), inventory(n));

		cd.setIdentifiers(List.of(purlId("pkg:")));
		Set<String> inv = assertDoesNotThrow(() -> inventory(n));
		assertEquals(setOf(app(3), lodash(21)), inv);
	}

	/** Row 5: a product whose child carries its own jar; inventory, cache and notification. */
	@Test
	void row5ProductChildCarriesItsOwnJar() {
		UUID sce = sceOld();
		ReleaseData a = release(sce, img(3, 21));
		ReleaseData aPrev = release(sce, img(2, 20));
		ReleaseData p = release(ComponentType.PRODUCT, new RearmIdentifier[0]);
		ReleaseData pPrev = release(ComponentType.PRODUCT, new RearmIdentifier[0]);
		when(sharedReleaseService.unwindReleaseDependencies(p)).thenReturn(Set.of(a));
		when(sharedReleaseService.unwindReleaseDependencies(pPrev)).thenReturn(Set.of(aPrev));
		predecessor(p, pPrev);

		assertEquals(setOf(SPRING, lodash(21)), inventory(p));

		service.postReconcileBomDiff(p.getUuid());
		assertEquals(lodashOnly(21, 20), cachedChangelog(p, pPrev));
		assertEquals(lodashOnly(21, 20), notifiedChangelog(p));
	}

	/** Row 5b: a nested product; one batched component read over the whole unwind. */
	@Test
	void row5bNestedProductUnionOverUnwind() {
		ComponentData aCd = componentData(UUID.randomUUID(), ORG, ComponentType.COMPONENT,
				purlId("pkg:maven/io.reliza/rearm-pro-backend"));
		ReleaseData a = release(aCd, img(3, 21));
		ComponentData p2Cd = componentData(UUID.randomUUID(), ORG, ComponentType.PRODUCT);
		ReleaseData p2 = release(p2Cd);
		ReleaseData p = release(ComponentType.PRODUCT, new RearmIdentifier[0]);
		when(sharedReleaseService.unwindReleaseDependencies(p)).thenReturn(new LinkedHashSet<>(List.of(p2, a)));
		when(getComponentService.getListOfComponentData(anyCollection())).thenReturn(List.of(p2Cd, aCd));

		assertEquals(setOf(lodash(21)), inventory(p));
		verify(getComponentService, times(1)).getListOfComponentData(eq(Set.of(p2Cd.getUuid(), aCd.getUuid())));
	}

	/** Row 6: an unrelated version bump is remove + add; another namespace is not self. */
	@Test
	void row6UnrelatedBumpAndOtherNamespace() {
		ComponentData cd = componentData(UUID.randomUUID(), ORG, ComponentType.COMPONENT,
				purlId("pkg:maven/io.reliza/rearm-pro-backend"));
		ReleaseData n = release(cd, img(3, 21));
		ComponentData cdPrev = componentData(cd.getUuid(), ORG, ComponentType.COMPONENT,
				purlId("pkg:maven/io.reliza/rearm-pro-backend"));
		ReleaseData prev = release(cdPrev, img(3, 20));
		predecessor(n, prev);

		service.postReconcileBomDiff(n.getUuid());
		assertEquals(lodashOnly(21, 20), cachedChangelog(n, prev));

		UUID other = img(3, 21);
		String otherNs = "pkg:maven/io.reliza.other/rearm-pro-backend@1";
		row(other, sc(otherNs), sc(OCI));
		ReleaseData withOther = release(componentData(UUID.randomUUID(), ORG, ComponentType.COMPONENT,
				purlId("pkg:maven/io.reliza/rearm-pro-backend")), other);
		assertEquals(setOf(lodash(21), otherNs), inventory(withOther));
	}

	/** Row 7: the existing root flag, and condition 2 (a flagged library with an in-edge adds no key). */
	@Test
	void row7RootFlagAndFlaggedLibraryWithInEdge() {
		UUID sceN = canonical();
		SbomComponent appOld = sc(APP_OLD, true);
		row(sceN, appOld);
		row(sceN, sc(SPRING), appOld);
		row(sceN, sc(lib("1.1")), appOld);
		UUID sceN1 = canonical();
		row(sceN1, appOld);
		row(sceN1, sc(SPRING), appOld);
		row(sceN1, sc(lib("1.0"), true), appOld);
		ReleaseData n = release(sceN);
		ReleaseData prev = release(sceN1);
		predecessor(n, prev);

		assertEquals(setOf(SPRING), inventory(prev));
		assertEquals(setOf(SPRING, lib("1.1")), inventory(n));

		service.postReconcileBomDiff(n.getUuid());
		ArtifactChangelog cached = cachedChangelog(n, prev);
		assertEquals(Set.of(new DiffComponent(lib("1.1"), "1.1")), cached.added());
		assertEquals(Set.of(), cached.removed());
	}

	/** Row 7, CPE part: a flagged CPE root is left out, a non-root CPE component beside it stays. */
	@Test
	void row7FlaggedCpeRootExcludedNonRootCpeKept() {
		UUID g = canonical();
		SbomComponent cpeRoot = sc(CPE_ROOT, true);
		row(g, cpeRoot);
		row(g, sc(CPE_PART), cpeRoot);
		ReleaseData n = release(g);
		assertEquals(setOf(CPE_PART), inventory(n));
	}

	/** Row 7c: the in-edge condition inside one inventory. */
	@Test
	void row7cInEdgeConditionInsideOneInventory() {
		UUID c = canonical();
		SbomComponent appOld = sc(APP_OLD, true);
		row(c, appOld);
		row(c, sc(SPRING), appOld);
		row(c, sc(lib("1.0"), true), appOld);
		row(c, sc(lib("1.1")), appOld);
		row(c, sc(app(3)), appOld);
		ReleaseData n = release(c);
		assertEquals(setOf(SPRING, lib("1.1")), inventory(n));
	}

	/** Row 7d: a flagged orphan beside a connected root -- two candidates, no root key. */
	@Test
	void row7dFlaggedOrphanBesideConnectedRoot() {
		UUID c = canonical();
		SbomComponent appOld = sc(APP_OLD, true);
		row(c, appOld);
		row(c, sc(SPRING), appOld);
		row(c, sc(lib("1.0"), true));
		row(c, sc(lib("1.1")));
		ReleaseData n = release(c);
		Set<String> inv = inventory(n);
		assertTrue(inv.contains(lib("1.1")));
		assertEquals(setOf(SPRING, lib("1.1")), inv);
	}

	/** Row 7e: no edges, one flagged root -- the key is added. */
	@Test
	void row7eNoEdgesOneFlaggedRoot() {
		UUID e = canonical();
		row(e, sc(APP_OLD, true));
		row(e, sc(SPRING));
		row(e, sc(app(3)));
		ReleaseData n = release(e);
		assertEquals(setOf(SPRING), inventory(n));
	}

	/** Row 7f: no edges, two flagged rows -- no root key; the exact skip still applies. */
	@Test
	void row7fNoEdgesTwoFlaggedRows() {
		UUID e = canonical();
		row(e, sc(APP_OLD, true));
		row(e, sc(SPRING));
		row(e, sc(app(3)));
		row(e, sc(lib("1.0"), true));
		row(e, sc(lib("1.1")));
		ReleaseData n = release(e);
		assertEquals(setOf(SPRING, app(3), lib("1.1")), inventory(n));
	}

	/** Row 7g: a flagged CPE row counts as a candidate although its key is null. */
	@Test
	void row7gFlaggedCpeRowCountsAsCandidate() {
		UUID g = canonical();
		row(g, sc(CPE_ROOT, true));
		row(g, sc(lib("1.0"), true));
		row(g, sc(lib("1.1")));
		ReleaseData n = release(g);
		Set<String> inv = assertDoesNotThrow(() -> inventory(n));
		assertEquals(setOf(lib("1.1")), inv);
	}

	/** Row 8: the reconcile of N-1 refreshes N's cached diff (2b) and notifies nothing. */
	@Test
	void row8PredecessorReconcileRefreshesSuccessor() {
		UUID sce = sceOld();
		ReleaseData n = release(sce, img(3, 21));
		ReleaseData prev = release(sce, img(2, 20));
		successor(prev, n);

		service.postReconcileBomDiff(prev.getUuid());

		assertEquals(lodashOnly(21, 20), cachedChangelog(n, prev));
		verifyNoInteractions(releaseChangeHook);
	}

	/** Row 9: cost -- no query added for a component release; one batched read for a product. */
	@Test
	void row9NoAddedQueryForComponentRelease() {
		ReleaseData n = release(sceOld(), img(3, 21));
		inventory(n);
		verify(getComponentService, times(1)).getComponentData(n.getComponent());
		verify(getComponentService, never()).getListOfComponentData(any());
		verify(indexRepository, times(1)).findByOrgAndReleaseUuidIn(eq(ORG), anyCollection());
		verify(artifactSbomComponentRepository, times(1)).findByOrgAndCanonicalArtifactUuidIn(eq(ORG), anyCollection());
		verify(componentRepository, times(1)).findByOrgAndUuidIn(eq(ORG.toString()), anyString());
		verifyNoMoreInteractions(indexRepository, artifactSbomComponentRepository, componentRepository);
	}

	/** Row 9, product part: getListOfComponentData exactly once for the row 5 product. */
	@Test
	void row9OneBatchedComponentReadForProduct() {
		UUID sce = sceOld();
		ReleaseData a = release(sce, img(3, 21));
		ReleaseData p = release(ComponentType.PRODUCT, new RearmIdentifier[0]);
		when(sharedReleaseService.unwindReleaseDependencies(p)).thenReturn(Set.of(a));
		when(getComponentService.getListOfComponentData(anyCollection())).thenReturn(List.of());
		inventory(p);
		verify(getComponentService, times(1)).getListOfComponentData(any());
	}

	/** Row 10: a null identifier list is empty and harmless. */
	@Test
	void row10NullIdentifiers() {
		ComponentData cd = componentData(UUID.randomUUID(), ORG, ComponentType.COMPONENT);
		cd.setIdentifiers(null);
		assertEquals(List.of(), cd.getIdentifiers());
		ReleaseData n = release(cd, sceOld(), img(3, 21));
		Set<String> inv = assertDoesNotThrow(() -> inventory(n));
		assertEquals(setOf(SPRING, lodash(21)), inv);
	}

	/** Row 10: a component of another organization reaching the list contributes nothing. */
	@Test
	void row10ForeignComponentIdentifiersIgnored() {
		ComponentData aCd = componentData(UUID.randomUUID(), ORG, ComponentType.COMPONENT,
				purlId("pkg:maven/io.reliza/rearm-pro-backend"));
		ReleaseData a = release(aCd, img(3, 21));
		ComponentData p2Cd = componentData(UUID.randomUUID(), ORG, ComponentType.PRODUCT);
		ReleaseData p2 = release(p2Cd);
		ComponentData foreign = componentData(UUID.randomUUID(), OTHER, ComponentType.COMPONENT,
				purlId("pkg:npm/lodash"));
		ReleaseData p = release(ComponentType.PRODUCT, new RearmIdentifier[0]);
		when(sharedReleaseService.unwindReleaseDependencies(p)).thenReturn(new LinkedHashSet<>(List.of(p2, a)));
		when(getComponentService.getListOfComponentData(anyCollection())).thenReturn(List.of(p2Cd, aCd, foreign));

		assertEquals(setOf(lodash(21)), inventory(p));
	}

	/** Row 11: a stored canonical the key cannot parse stays in the inventory. */
	@Test
	void row11UnparseableStoredCanonicalStays() {
		UUID c = img(3, 21);
		row(c, sc("pkg:maven/%ZZbad@1"), sc(OCI));
		ReleaseData n = release(c);
		Set<String> inv = assertDoesNotThrow(() -> inventory(n));
		assertTrue(inv.contains("pkg:maven/%ZZbad@1"));
	}

	/** Row 12: the accepted exclusion -- a bundled older copy of the app jar is excluded. */
	@Test
	void row12BundledOldCopyExcluded() {
		UUID c = img(3, 21);
		row(c, sc(APP_PREFIX + "@2025.01.0"), sc(app(3)));
		ComponentData cd = componentData(UUID.randomUUID(), ORG, ComponentType.COMPONENT,
				purlId("pkg:maven/io.reliza/rearm-pro-backend"));
		ReleaseData n = release(cd, c);
		assertEquals(setOf(lodash(21)), inventory(n));
	}
}
