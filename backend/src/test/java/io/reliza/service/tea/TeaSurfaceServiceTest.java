/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.TeaAuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaConcealReason;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaSurfaceService.Decision;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.service.tea.TeaSurfaceService.TeaObject;
import io.reliza.service.tea.TeaSurfaceService.TeaOrg;
import io.reliza.service.tea.TeaSurfaceService.WellKnownAnswer;

/** Task TEA-6, design 4.4 cases 10 to 17: the read model, with every collaborator mocked. */
class TeaSurfaceServiceTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID ORG_TEA = UUID.randomUUID();
	private static final TeaOrg TORG = new TeaOrg(ORG, ORG_TEA, "https://tea.example.com/tea/" + ORG_TEA);
	private static final TeaPrincipal PRINCIPAL = new TeaPrincipal(UUID.randomUUID(), ORG, "EXTERNAL__x__ord__1", 1, "198.51.100.1");
	private static final TeaCaller ANON = TeaCaller.anonymous();
	private static final TeaCaller BAD = TeaCaller.invalidToken("x");
	private static final TeaCaller KEY = TeaCaller.authenticated(PRINCIPAL);

	private TeaSurfaceService service;
	private TeaPublicationService pubs;
	private TeaAuthorizationService auth;
	private OrganizationRepository orgRepo;
	private ComponentRepository compRepo;
	private SharedReleaseService releases;
	private UserService userService;
	private final Map<UUID, TeaPublicationExposure> exposures = new HashMap<>();

	@BeforeEach
	void wire() {
		service = new TeaSurfaceService();
		pubs = mock(TeaPublicationService.class);
		auth = mock(TeaAuthorizationService.class);
		orgRepo = mock(OrganizationRepository.class);
		compRepo = mock(ComponentRepository.class);
		releases = mock(SharedReleaseService.class);
		userService = mock(UserService.class);
		ReflectionTestUtils.setField(service, "teaPublicationService", pubs);
		ReflectionTestUtils.setField(service, "teaAuthorizationService", auth);
		ReflectionTestUtils.setField(service, "organizationRepository", orgRepo);
		ReflectionTestUtils.setField(service, "componentRepository", compRepo);
		ReflectionTestUtils.setField(service, "sharedReleaseService", releases);
		ReflectionTestUtils.setField(service, "userService", userService);
		when(pubs.exposureOf(any())).thenAnswer(inv -> {
			TeaPublicationData p = inv.getArgument(0);
			return exposures.get(p.getUuid());
		});
		when(releases.getReleaseData(any(UUID.class))).thenAnswer(inv -> {
			ReleaseData rd = new ReleaseData();
			ReflectionTestUtils.setField(rd, "uuid", inv.getArgument(0));
			return Optional.of(rd);
		});
		when(releases.findReleasesByOrgAndIdentifier(any(), any(), anyString())).thenReturn(List.of());
	}

	// ------------------------------------------------------------------ fixtures

	private static ComponentData component(ComponentType type, UUID org) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setType(type);
		cd.setName("c-" + cd.getUuid().toString().substring(0, 6));
		cd.setTeaUuid(UUID.randomUUID());
		return cd;
	}

	private static TeaProfileData profile(TeaProfileData.TeaVisibility v, TeaProfileData.TeaTei tei, String domain) {
		TeaProfileData p = new TeaProfileData();
		p.setPublishing(TeaProfileData.TeaPublishing.ENABLED);
		p.setVisibility(v);
		p.setTei(tei);
		p.setTeiDomain(domain);
		return p;
	}

	private TeaPublicationData head(ComponentData cd, TeaExposureState state) {
		return head(cd, state, profile(TeaExposureState.PUBLIC == state ? TeaProfileData.TeaVisibility.PUBLIC
				: TeaProfileData.TeaVisibility.PRIVATE, TeaProfileData.TeaTei.DISABLED, null));
	}

	private TeaPublicationData head(ComponentData cd, TeaExposureState state, TeaProfileData profile) {
		TeaPublicationData p = new TeaPublicationData();
		p.setUuid(UUID.randomUUID());
		p.setOrg(cd.getOrg());
		p.setRelease(UUID.randomUUID());
		p.setComponent(cd.getUuid());
		p.setComponentType(cd.getType());
		p.setState(TeaPublicationState.PUBLISHED);
		p.setLatestVersion(1);
		p.setFirstPublishedDate(ZonedDateTime.now());
		TeaResolvedProfile resolved = new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.ORGANIZATION, ORG,
				profile, List.of());
		exposures.put(p.getUuid(), new TeaPublicationExposure(state, TeaExposureState.CONCEALED == state ? TeaConcealReason.HIDDEN : null,
				cd, resolved, p, null));
		when(pubs.getPublication(p.getUuid())).thenReturn(Optional.of(p));
		when(pubs.getPublicationOfRelease(p.getRelease())).thenReturn(Optional.of(p));
		return p;
	}

	/** TEA-3's rule, for the fixtures that do not pin one answer. */
	private void realAuthRule() {
		when(auth.accessToComponent(any(), eq(ORG), any(), any())).thenAnswer(inv -> {
			TeaCaller c = inv.getArgument(0);
			ComponentData cd = inv.getArgument(2);
			TeaResolvedProfile r = inv.getArgument(3);
			if (TeaProfileData.TeaVisibility.PUBLIC == r.profile().getVisibility()) return TeaAccess.SERVE;
			return switch (c.state()) {
				case ANONYMOUS -> TeaAccess.UNAUTHENTICATED;
				case INVALID_TOKEN -> TeaAccess.INVALID_TOKEN;
				default -> coveredSet == null || coveredSet.contains(cd.getUuid()) ? TeaAccess.SERVE : TeaAccess.CONCEAL;
			};
		});
	}

	private Set<UUID> coveredSet;

	private void listOrg(TeaPublicationData... heads) {
		when(pubs.listPublicationsOfOrg(ORG, TeaPublicationState.PUBLISHED)).thenReturn(List.of(heads));
	}

	private void ofComponent(ComponentData cd, TeaPublicationData... heads) {
		Component c = new Component();
		c.setUuid(cd.getUuid());
		c.setRecordData(Utils.dataToRecord(cd));
		when(compRepo.findByTeaUuid(cd.getTeaUuid().toString())).thenReturn(Optional.of(c));
		when(pubs.listPublicationsOfComponent(ORG, cd.getUuid())).thenReturn(List.of(heads));
	}

	// ------------------------------------------------------------------ 10

	private static Organization orgRow(UUID tea, StatusEnum status) {
		OrganizationData od = new OrganizationData();
		od.setTeaUuid(tea);
		od.setStatus(status);
		Organization o = new Organization();
		o.setUuid(ORG);
		o.setRecordData(Utils.dataToRecord(od));
		return o;
	}

	@Test
	void orgOfBaseAnswersAKnownOrganizationOnly() {
		when(orgRepo.findByTeaUuid(ORG_TEA.toString())).thenReturn(Optional.of(orgRow(ORG_TEA, StatusEnum.ACTIVE)));
		when(pubs.apiBaseOf(ORG)).thenReturn(TORG.apiBase());
		assertEquals(TORG, service.orgOfBase(ORG_TEA.toString()).orElseThrow());
		assertTrue(service.orgOfBase(UUID.randomUUID().toString()).isEmpty(), "unknown");
		assertTrue(service.orgOfBase("not-a-uuid").isEmpty(), "malformed");
		assertTrue(service.orgOfBase(null).isEmpty(), "null");
		UUID archived = UUID.randomUUID();
		when(orgRepo.findByTeaUuid(archived.toString())).thenReturn(Optional.of(orgRow(archived, StatusEnum.ARCHIVED)));
		assertTrue(service.orgOfBase(archived.toString()).isEmpty(), "archived");
	}

	// ------------------------------------------------------------------ 11

	@Test
	void accessToConcealsBeforeAskingAndOtherwisePassesTheExposureThrough() {
		ComponentData cd = component(ComponentType.COMPONENT, ORG);
		TeaPublicationData hidden = head(cd, TeaExposureState.CONCEALED);
		assertEquals(TeaAccess.CONCEAL, service.accessTo(ANON, TORG, hidden));
		verify(auth, never()).accessToComponent(any(), any(), any(), any());

		TeaPublicationData pub = head(cd, TeaExposureState.PUBLIC);
		TeaPublicationExposure e = exposures.get(pub.getUuid());
		when(auth.accessToComponent(ANON, ORG, e.governingComponent(), e.resolved())).thenReturn(TeaAccess.SERVE);
		assertEquals(TeaAccess.SERVE, service.accessTo(ANON, TORG, pub));
		TeaPublicationData priv = head(cd, TeaExposureState.PRIVATE);
		TeaPublicationExposure pe = exposures.get(priv.getUuid());
		when(auth.accessToComponent(ANON, ORG, pe.governingComponent(), pe.resolved())).thenReturn(TeaAccess.UNAUTHENTICATED);
		assertEquals(TeaAccess.UNAUTHENTICATED, service.accessTo(ANON, TORG, priv));

		ComponentData foreignCd = component(ComponentType.COMPONENT, UUID.randomUUID());
		TeaPublicationData foreign = head(foreignCd, TeaExposureState.PUBLIC);
		assertEquals(TeaAccess.CONCEAL, service.accessTo(ANON, TORG, foreign));
		verify(pubs, never()).exposureOf(foreign);

		assertEquals(TeaAccess.CONCEAL, service.productRelease(ANON, TORG, pub.getUuid()).refusal(), "a component head asked as a product release");
		verify(pubs, times(1)).exposureOf(pub);
		assertEquals(TeaAccess.CONCEAL, service.componentRelease(ANON, TORG, UUID.randomUUID()).refusal(), "unknown");
		Decision<Head> d = service.componentRelease(ANON, TORG, pub.getUuid());
		assertTrue(d.served());
		assertEquals(pub, d.value().publication());
	}

	// ------------------------------------------------------------------ 12

	@Test
	void strongestPrefersServeThenUnauthenticatedThenInvalidToken() {
		assertEquals(TeaAccess.CONCEAL, TeaSurfaceService.strongest(List.of()));
		assertEquals(TeaAccess.INVALID_TOKEN, TeaSurfaceService.strongest(List.of(TeaAccess.CONCEAL, TeaAccess.INVALID_TOKEN)));
		assertEquals(TeaAccess.UNAUTHENTICATED, TeaSurfaceService.strongest(
				List.of(TeaAccess.CONCEAL, TeaAccess.INVALID_TOKEN, TeaAccess.UNAUTHENTICATED)));
		assertEquals(TeaAccess.SERVE, TeaSurfaceService.strongest(List.of(TeaAccess.CONCEAL, TeaAccess.SERVE, TeaAccess.UNAUTHENTICATED)));
	}

	// ------------------------------------------------------------------ 13

	@Test
	void anObjectDecidesByItsStrongestHead() {
		realAuthRule();
		assertEquals(TeaAccess.CONCEAL, service.product(ANON, TORG, UUID.randomUUID()).refusal(), "unknown");
		ComponentData comp = component(ComponentType.COMPONENT, ORG);
		ofComponent(comp, head(comp, TeaExposureState.PUBLIC));
		assertEquals(TeaAccess.CONCEAL, service.product(ANON, TORG, comp.getTeaUuid()).refusal(), "a component asked as a product");
		ComponentData foreign = component(ComponentType.PRODUCT, UUID.randomUUID());
		ofComponent(foreign, head(foreign, TeaExposureState.PUBLIC));
		assertEquals(TeaAccess.CONCEAL, service.product(ANON, TORG, foreign.getTeaUuid()).refusal(), "another org's product");
		ComponentData none = component(ComponentType.PRODUCT, ORG);
		ofComponent(none);
		assertEquals(TeaAccess.CONCEAL, service.product(ANON, TORG, none.getTeaUuid()).refusal(), "no published head");

		ComponentData privOnly = component(ComponentType.PRODUCT, ORG);
		ofComponent(privOnly, head(privOnly, TeaExposureState.PRIVATE), head(privOnly, TeaExposureState.CONCEALED));
		assertEquals(TeaAccess.UNAUTHENTICATED, service.product(ANON, TORG, privOnly.getTeaUuid()).refusal());
		assertEquals(TeaAccess.INVALID_TOKEN, service.product(BAD, TORG, privOnly.getTeaUuid()).refusal());
		coveredSet = null;
		Decision<TeaObject> covered = service.product(KEY, TORG, privOnly.getTeaUuid());
		assertTrue(covered.served());
		assertEquals(1, covered.value().servedHeads().size(), "the concealed head is not served");

		ComponentData mixed = component(ComponentType.PRODUCT, ORG);
		ofComponent(mixed, head(mixed, TeaExposureState.PUBLIC), head(mixed, TeaExposureState.PRIVATE));
		Decision<TeaObject> d = service.product(BAD, TORG, mixed.getTeaUuid());
		assertTrue(d.served(), "a PUBLIC head serves whatever token was presented");
		assertEquals(1, d.value().servedHeads().size());
	}

	// ------------------------------------------------------------------ 14

	@Test
	void listingsRefuseAnInvalidTokenFirstAndShowPublicPlusCovered() {
		Decision<List<Head>> bad = service.visibleHeads(BAD, TORG, ComponentType.COMPONENT);
		assertEquals(TeaAccess.INVALID_TOKEN, bad.refusal());
		verify(pubs, never()).listPublicationsOfOrg(any(), any());

		ComponentData c1 = component(ComponentType.COMPONENT, ORG);
		ComponentData c2 = component(ComponentType.COMPONENT, ORG);
		ComponentData p1 = component(ComponentType.PRODUCT, ORG);
		TeaPublicationData pub1 = head(c1, TeaExposureState.PUBLIC);
		TeaPublicationData priv1 = head(c1, TeaExposureState.PRIVATE);
		TeaPublicationData priv2 = head(c2, TeaExposureState.PRIVATE);
		TeaPublicationData gone = head(c2, TeaExposureState.CONCEALED);
		TeaPublicationData prodPriv = head(p1, TeaExposureState.PRIVATE);
		listOrg(pub1, priv1, priv2, gone, prodPriv);

		assertEquals(List.of(pub1), publications(service.visibleHeads(ANON, TORG, ComponentType.COMPONENT)));
		when(auth.coveredComponentUuids(PRINCIPAL, ORG)).thenReturn(null);
		assertEquals(List.of(pub1, priv1, priv2), publications(service.visibleHeads(KEY, TORG, ComponentType.COMPONENT)));
		when(auth.coveredComponentUuids(PRINCIPAL, ORG)).thenReturn(Set.of(c1.getUuid()));
		assertEquals(List.of(pub1, priv1), publications(service.visibleHeads(KEY, TORG, ComponentType.COMPONENT)));
		verify(auth, times(2)).coveredComponentUuids(PRINCIPAL, ORG);

		assertTrue(TeaSurfaceService.componentsOf(service.visibleHeads(ANON, TORG, ComponentType.PRODUCT).value()).isEmpty());
		when(auth.coveredComponentUuids(PRINCIPAL, ORG)).thenReturn(Set.of(p1.getUuid()));
		List<ComponentData> products = TeaSurfaceService.componentsOf(service.visibleHeads(KEY, TORG, ComponentType.PRODUCT).value());
		assertEquals(List.of(p1.getUuid()), products.stream().map(ComponentData::getUuid).toList());
		List<ComponentData> distinct = TeaSurfaceService.componentsOf(
				service.visibleHeads(ANON, TORG, ComponentType.COMPONENT).value());
		assertEquals(1, distinct.size());
	}

	private static List<TeaPublicationData> publications(Decision<List<Head>> d) {
		assertTrue(d.served());
		return d.value().stream().map(Head::publication).toList();
	}

	// ------------------------------------------------------------------ 15

	private static TeaProduct product(String name, UUID uuid, TeaIdentifier... ids) {
		TeaProduct p = new TeaProduct(uuid, name, new ArrayList<>(List.of(ids)));
		return p;
	}

	@Test
	void filtersAndSortWorkOnTheRenderedResults() {
		UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
		UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
		UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
		TeaProduct pa = product("beta", a, new TeaIdentifier(TeaIdentifierType.PURL, "x"));
		TeaProduct pb = product("alpha", b, new TeaIdentifier(TeaIdentifierType.CPE, "x"));
		TeaProduct pc = product("alpha", c, new TeaIdentifier(TeaIdentifierType.PURL, "y"));
		List<TeaProduct> all = List.of(pa, pb, pc);
		assertEquals(List.of(pa), TeaSurfaceService.filterByIdentifier(all, TeaProduct::getIdentifiers, TeaIdentifierType.PURL, "x"));
		assertEquals(List.of(pa, pb), TeaSurfaceService.filterByIdentifier(all, TeaProduct::getIdentifiers, null, "x"));
		assertEquals(List.of(pa, pc), TeaSurfaceService.filterByIdentifier(all, TeaProduct::getIdentifiers, TeaIdentifierType.PURL, null));
		assertEquals(all, TeaSurfaceService.filterByIdentifier(all, TeaProduct::getIdentifiers, null, null));
		assertTrue(TeaSurfaceService.filterByIdentifier(all, TeaProduct::getIdentifiers, TeaIdentifierType.COMPLIANCE_DOCUMENT, null).isEmpty());
		assertEquals(List.of(pb, pc, pa), TeaSurfaceService.sorted(all, TeaProduct::getName, TeaProduct::getUuid, false), "name then uuid");
		assertEquals(List.of(pa, pc, pb), TeaSurfaceService.sorted(all, TeaProduct::getName, TeaProduct::getUuid, true), "both reversed");
		assertEquals(List.of("B", "a"), TeaSurfaceService.sorted(List.of("a", "B"), s -> s, s -> a, false), "codepoint order");
	}

	// ------------------------------------------------------------------ 16

	@Test
	void discoveryFindsStampedLiveUuidAndPurlCandidatesNewestFirst() {
		realAuthRule();
		ComponentData prod = component(ComponentType.PRODUCT, ORG);
		TeaProfileData stamping = profile(TeaProfileData.TeaVisibility.PUBLIC, TeaProfileData.TeaTei.UUID, "products.example.com");
		TeaPublicationData stamped = head(prod, TeaExposureState.PUBLIC, stamping);
		stamped.setTei("tei://products.example.com/uuid/" + stamped.getUuid());
		listOrg(stamped);
		assertEquals(List.of(stamped), served(service.discover(ANON, TORG, tei(stamped.getTei()))), "step 1");
		// a stamp whose identifier names no publication: only step 1 can find it, character for character
		TeaPublicationData legacy = head(prod, TeaExposureState.PUBLIC, stamping);
		legacy.setTei("tei://products.example.com/uuid/" + UUID.randomUUID());
		listOrg(stamped, legacy);
		assertEquals(List.of(legacy), served(service.discover(ANON, TORG, tei(legacy.getTei()))));
		assertEquals(TeaAccess.CONCEAL, service.discover(ANON, TORG,
				tei(legacy.getTei().replace("products.example.com", "PRODUCTS.example.com"))).refusal(), "step 1 is case-sensitive");
		// step 3: the domain compared case-insensitively, refused under another domain
		assertEquals(List.of(stamped), served(service.discover(ANON, TORG, tei("tei://Products.Example.COM/uuid/" + stamped.getUuid()))));
		assertEquals(TeaAccess.CONCEAL, service.discover(ANON, TORG, tei("tei://other.example.com/uuid/" + stamped.getUuid())).refusal());
		TeaPublicationData unstamped = head(prod, TeaExposureState.PUBLIC);
		assertEquals(List.of(unstamped), served(service.discover(ANON, TORG, tei("tei://any.example.org/uuid/" + unstamped.getUuid()))),
				"no domain check with stamping off");
		// step 2: a live TEI identifier, PRODUCT heads only
		ComponentData comp = component(ComponentType.COMPONENT, ORG);
		TeaPublicationData compHead = head(comp, TeaExposureState.PUBLIC);
		ReleaseData live = new ReleaseData();
		ReflectionTestUtils.setField(live, "uuid", unstamped.getRelease());
		ReleaseData liveComp = new ReleaseData();
		ReflectionTestUtils.setField(liveComp, "uuid", compHead.getRelease());
		String liveTei = "tei://vendor.example.com/eanupc/4006381333931";
		when(releases.findReleasesByOrgAndIdentifier(ORG, RearmIdentifierType.TEI, liveTei)).thenReturn(List.of(live, liveComp));
		assertEquals(List.of(unstamped), served(service.discover(ANON, TORG, tei(liveTei))));
		// step 4: a purl TEI under the domain check
		String purl = "pkg:generic/x@1.0.0";
		ReleaseData byPurl = new ReleaseData();
		ReflectionTestUtils.setField(byPurl, "uuid", stamped.getRelease());
		when(releases.findReleasesByOrgAndIdentifier(ORG, RearmIdentifierType.PURL, purl)).thenReturn(List.of(byPurl));
		assertEquals(List.of(stamped), served(service.discover(ANON, TORG, tei("tei://products.example.com/purl/" + b64(purl)))));
		assertEquals(TeaAccess.CONCEAL, service.discover(ANON, TORG, tei("tei://evil.example.com/purl/" + b64(purl))).refusal());
		assertEquals(List.of(stamped), served(service.discoverByPurl(ANON, TORG, purl)));
		ReleaseData compByPurl = new ReleaseData();
		ReflectionTestUtils.setField(compByPurl, "uuid", compHead.getRelease());
		when(releases.findReleasesByOrgAndIdentifier(ORG, RearmIdentifierType.PURL, "pkg:generic/c@1")).thenReturn(List.of(compByPurl));
		assertEquals(TeaAccess.CONCEAL, service.discoverByPurl(ANON, TORG, "pkg:generic/c@1").refusal(), "a component release is no candidate");
		// another type finds nothing
		assertEquals(TeaAccess.CONCEAL, service.discover(ANON, TORG, tei("tei://products.example.com/hash/abc")).refusal());

		// dedupe and newest first; [PRIVATE, PUBLIC] anonymously serves the PUBLIC one; [PRIVATE] asks to authenticate
		TeaPublicationData older = head(prod, TeaExposureState.PUBLIC);
		older.setFirstPublishedDate(ZonedDateTime.now().minusDays(2));
		TeaPublicationData newer = head(prod, TeaExposureState.PUBLIC);
		newer.setFirstPublishedDate(ZonedDateTime.now().minusDays(1));
		TeaPublicationData privHead = head(prod, TeaExposureState.PRIVATE);
		String shared = "tei://vendor.example.com/eanupc/1";
		List<ReleaseData> rs = new ArrayList<>();
		for (TeaPublicationData p : List.of(older, newer, older, privHead)) {
			ReleaseData r = new ReleaseData();
			ReflectionTestUtils.setField(r, "uuid", p.getRelease());
			rs.add(r);
		}
		when(releases.findReleasesByOrgAndIdentifier(ORG, RearmIdentifierType.TEI, shared)).thenReturn(rs);
		assertEquals(List.of(newer, older), served(service.discover(ANON, TORG, tei(shared))));
		String onlyPrivate = "tei://vendor.example.com/eanupc/2";
		ReleaseData pr = new ReleaseData();
		ReflectionTestUtils.setField(pr, "uuid", privHead.getRelease());
		when(releases.findReleasesByOrgAndIdentifier(ORG, RearmIdentifierType.TEI, onlyPrivate)).thenReturn(List.of(pr));
		assertEquals(TeaAccess.UNAUTHENTICATED, service.discover(ANON, TORG, tei(onlyPrivate)).refusal());
		assertEquals(TeaAccess.CONCEAL, service.discover(ANON, TORG, tei("tei://vendor.example.com/eanupc/3")).refusal(), "none");
	}

	private static TeaTei tei(String s) {
		return TeaTei.parse(s).orElseThrow(() -> new AssertionError("not a TEI: " + s));
	}

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static List<TeaPublicationData> served(Decision<List<Head>> d) {
		assertTrue(d.served(), () -> "refused: " + d.refusal());
		return d.value().stream().map(Head::publication).toList();
	}

	// ------------------------------------------------------------------ 17

	@Test
	void theWellKnownDocumentIsTheOssInstancesOnly() {
		when(userService.getInstallationType()).thenReturn(InstallationType.OSS);
		UUID o1 = UUID.randomUUID();
		UUID o2 = UUID.randomUUID();
		UUID o3 = UUID.randomUUID();
		Organization r1 = orgRow(UUID.randomUUID(), StatusEnum.ACTIVE);
		r1.setUuid(o1);
		Organization r2 = orgRow(UUID.randomUUID(), StatusEnum.ACTIVE);
		r2.setUuid(o2);
		Organization r3 = orgRow(UUID.randomUUID(), StatusEnum.ARCHIVED);
		r3.setUuid(o3);
		when(orgRepo.listWithTeaUuid()).thenReturn(List.of(r1, r2, r3));
		when(pubs.apiBaseOf(o1)).thenReturn("https://b.example.com/tea/1");
		when(pubs.apiBaseOf(o2)).thenReturn("https://a.example.com/tea/2");
		when(pubs.apiBaseOf(o3)).thenReturn("https://c.example.com/tea/3");
		WellKnownAnswer a = service.wellKnown();
		assertNull(a.error());
		assertEquals("{\"schemaVersion\":1,\"endpoints\":[{\"url\":\"https://a.example.com/tea/2\",\"versions\":[\"1.0.0\"],\"priority\":1},"
				+ "{\"url\":\"https://b.example.com/tea/1\",\"versions\":[\"1.0.0\"],\"priority\":1}]}", a.document());
		TeaOpenApiSchemas.assertWellKnownConforms(a.document());

		when(orgRepo.listWithTeaUuid()).thenReturn(List.of());
		assertEquals(TeaUnknownErrorType.OBJECT_UNKNOWN, service.wellKnown().error());

		for (InstallationType t : List.of(InstallationType.SAAS, InstallationType.DEMO, InstallationType.MANAGED_SERVICE)) {
			OrganizationRepository fresh = mock(OrganizationRepository.class);
			ReflectionTestUtils.setField(service, "organizationRepository", fresh);
			when(userService.getInstallationType()).thenReturn(t);
			assertEquals(TeaUnknownErrorType.NOT_IMPLEMENTED, service.wellKnown().error(), t.name());
			verifyNoInteractions(fresh);
		}
	}
}
