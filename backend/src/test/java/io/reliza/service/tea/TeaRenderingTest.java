/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Link;
import io.reliza.model.ParentRelease;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaComponent;
import io.reliza.model.tea.TeaComponentRef;
import io.reliza.model.tea.TeaComponentRelease;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.model.tea.TeaReleaseDistribution;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.VariantService;
import io.reliza.ws.tea.TeaJson;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-6, design 4.3 cases 5 to 9: rendering against the 1.0.0 schemas. Task TEA-19 adds case 10 (design 5.5
 * rows 34 to 36): a distribution url is served only when TeaExternalUrl admits it.
 */
class TeaRenderingTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final String Z_SECONDS = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$";

	private TeaTransformerService service;
	private SharedReleaseService releases;
	private GetComponentService components;
	private VariantService variants;
	private GetDeliverableService deliverables;
	private TeaPublicationService pubs;

	@BeforeEach
	void wire() {
		service = new TeaTransformerService();
		releases = mock(SharedReleaseService.class);
		components = mock(GetComponentService.class);
		variants = mock(VariantService.class);
		deliverables = mock(GetDeliverableService.class);
		pubs = mock(TeaPublicationService.class);
		ReflectionTestUtils.setField(service, "sharedReleaseService", releases);
		ReflectionTestUtils.setField(service, "getComponentService", components);
		ReflectionTestUtils.setField(service, "variantService", variants);
		ReflectionTestUtils.setField(service, "getDeliverableService", deliverables);
		ReflectionTestUtils.setField(service, "teaPublicationService", pubs);
		when(variants.getBaseVariantForRelease(any(ReleaseData.class))).thenReturn(new VariantData());
	}

	private static String json(Object o) {
		return TeaJson.MAPPER.writeValueAsString(o);
	}

	private static ComponentData component(ComponentType type, UUID org, boolean teaId) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setType(type);
		cd.setName("name-" + cd.getUuid().toString().substring(0, 4));
		if (teaId) cd.setTeaUuid(UUID.randomUUID());
		cd.setIdentifiers(new ArrayList<>(List.of(new RearmIdentifier(RearmIdentifierType.CPE, "cpe:2.3:a:x:y:1"),
				new RearmIdentifier(RearmIdentifierType.TEI, "tei://a.example.com/uuid/" + UUID.randomUUID()),
				new RearmIdentifier(RearmIdentifierType.PURL, "pkg:generic/y@1"),
				new RearmIdentifier(RearmIdentifierType.COMPLIANCE_DOCUMENT, "https://example.com/doc"),
				new RearmIdentifier(RearmIdentifierType.SERIAL, "SN-1"))));
		return cd;
	}

	private static ReleaseData release(ComponentData cd, List<RearmIdentifier> ids) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", cd.getOrg());
		ReflectionTestUtils.setField(rd, "component", cd.getUuid());
		ReflectionTestUtils.setField(rd, "version", "1.2.3");
		ReflectionTestUtils.setField(rd, "createdDate", ZonedDateTime.parse("2026-10-08T10:15:30.123+02:00"));
		ReflectionTestUtils.setField(rd, "identifiers", new ArrayList<>(ids));
		return rd;
	}

	private static TeaPublicationData head(ReleaseData rd, ComponentData cd, String tei) {
		TeaPublicationData p = new TeaPublicationData();
		p.setUuid(UUID.randomUUID());
		p.setOrg(cd.getOrg());
		p.setRelease(rd.getUuid());
		p.setComponent(cd.getUuid());
		p.setComponentType(cd.getType());
		p.setState(TeaPublicationState.PUBLISHED);
		p.setLatestVersion(1);
		p.setTei(tei);
		p.setFirstPublishedDate(ZonedDateTime.parse("2026-10-09T12:00:00.987+02:00"));
		return p;
	}

	// 5
	@Test
	void productsAndComponentsCarryTheirTeaIdsAndFilteredIdentifiers() {
		ComponentData prod = component(ComponentType.PRODUCT, ORG, true);
		TeaProduct p = service.transformProductToTea(prod);
		assertEquals(prod.getTeaUuid(), p.getUuid());
		assertEquals(List.of(TeaIdentifierType.CPE, TeaIdentifierType.TEI, TeaIdentifierType.PURL),
				p.getIdentifiers().stream().map(TeaIdentifier::getIdType).toList());
		assertConforms(json(p), "product");
		ComponentData comp = component(ComponentType.COMPONENT, ORG, true);
		TeaComponent c = service.transformComponentToTea(comp);
		assertEquals(comp.getTeaUuid(), c.getUuid());
		assertTrue(c.getIdentifiers().stream().anyMatch(i -> TeaIdentifierType.COMPLIANCE_DOCUMENT == i.getIdType()));
		assertConforms(json(c), "component");
		comp.setIdentifiers(new ArrayList<>());
		String empty = json(service.transformComponentToTea(comp));
		assertTrue(empty.contains("\"identifiers\":[]"), empty);
		assertConforms(empty, "component");
	}

	// 6
	@Test
	void aProductReleaseRendersFromItsHeadWithTheStampedTeiFirst() {
		ComponentData prod = component(ComponentType.PRODUCT, ORG, true);
		String stamped = "tei://products.example.com/uuid/x";
		ReleaseData rd = release(prod, List.of(new RearmIdentifier(RearmIdentifierType.TEI, stamped),
				new RearmIdentifier(RearmIdentifierType.PURL, "pkg:generic/p@1"),
				new RearmIdentifier(RearmIdentifierType.COMPLIANCE_DOCUMENT, "https://example.com/d")));
		TeaPublicationData h = head(rd, prod, stamped);
		TeaProductRelease r = service.transformProductReleaseToTea(h, rd, prod, List.of());
		String out = json(r);
		JsonNode n = TeaJson.MAPPER.readTree(out);
		assertEquals(h.getUuid().toString(), n.get("uuid").asString());
		assertEquals(prod.getTeaUuid().toString(), n.get("product").asString());
		assertEquals("2026-10-09T10:00:00Z", n.get("createdDate").asString(), "the first publish, UTC, to the second");
		assertEquals("2026-10-08T08:15:30Z", n.get("releaseDate").asString(), "the release's creation");
		assertTrue(n.get("createdDate").asString().matches(Z_SECONDS));
		assertFalse(n.has("preRelease"));
		assertEquals(List.of(stamped, "pkg:generic/p@1"), r.getIdentifiers().stream().map(TeaIdentifier::getIdValue).toList(),
				"the stamped TEI once, first, and no compliance document on a product release");
		assertTrue(out.contains("\"components\":[]"), out);
		assertConforms(out, "product-release");

		TeaProductRelease unstamped = service.transformProductReleaseToTea(head(rd, prod, null), release(prod, List.of()), prod, null);
		assertTrue(unstamped.getIdentifiers().isEmpty());
		assertConforms(json(unstamped), "product-release");
	}

	private DeliverableData deliverable(String notes, String link, DigestRecord... digests) {
		DeliverableData dd = new DeliverableData();
		dd.setUuid(UUID.randomUUID());
		dd.setNotes(notes);
		dd.setIdentifiers(new ArrayList<>(List.of(new RearmIdentifier(RearmIdentifierType.PURL, "pkg:oci/x@sha256:ab"),
				new RearmIdentifier(RearmIdentifierType.SERIAL, "SN"))));
		SoftwareDeliverableMetadata sdm = new SoftwareDeliverableMetadata();
		sdm.setDigestRecords(new LinkedHashSet<>(List.of(digests)));
		if (null != link) {
			Link l = new Link();
			l.setUri(link);
			sdm.setDownloadLinks(new ArrayList<>(List.of(l)));
		}
		dd.setSoftwareMetadata(sdm);
		when(deliverables.getDeliverableData(dd.getUuid())).thenReturn(Optional.of(dd));
		return dd;
	}

	// 7
	@Test
	void aComponentReleaseCarriesDistributionsFromItsOutboundDeliverables() {
		ComponentData comp = component(ComponentType.COMPONENT, ORG, true);
		ReleaseData rd = release(comp, List.of(new RearmIdentifier(RearmIdentifierType.COMPLIANCE_DOCUMENT, "https://example.com/d")));
		TeaPublicationData h = head(rd, comp, null);
		TeaComponentRelease bare = service.transformComponentReleaseToTea(h, rd, comp);
		String bareJson = json(bare);
		assertFalse(TeaJson.MAPPER.readTree(bareJson).has("distributions"), bareJson);
		assertEquals(comp.getTeaUuid(), bare.getComponent());
		assertEquals(comp.getName(), bare.getComponentName());
		assertEquals(TeaIdentifierType.COMPLIANCE_DOCUMENT, bare.getIdentifiers().get(0).getIdType());
		assertConforms(bareJson, "component-release");

		String hex = "ab".repeat(32);
		DeliverableData d1 = deliverable("the image", "https://registry.example.com/x",
				new DigestRecord(DigestAlgorithm.SHA_256, hex.toUpperCase(), DigestScope.ORIGINAL_FILE),
				new DigestRecord(DigestAlgorithm.MD5, "9e107d9d372bb6826bd81d3542a419d6", DigestScope.ORIGINAL_FILE),
				new DigestRecord(DigestAlgorithm.SHA_1, "not-hex", DigestScope.ORIGINAL_FILE));
		DeliverableData d2 = deliverable(null, "relative/path",
				new DigestRecord(DigestAlgorithm.MD5, "9e107d9d372bb6826bd81d3542a419d6", DigestScope.ORIGINAL_FILE));
		VariantData vd = new VariantData();
		vd.setOutboundDeliverables(new LinkedHashSet<>(List.of(d1.getUuid(), d2.getUuid())));
		when(variants.getBaseVariantForRelease(any(ReleaseData.class))).thenReturn(vd);

		TeaComponentRelease r = service.transformComponentReleaseToTea(h, rd, comp);
		List<TeaReleaseDistribution> ds = r.getDistributions();
		assertEquals(2, ds.size());
		assertNotEquals(ds.get(0).getDistributionId(), ds.get(1).getDistributionId());
		assertNotEquals(d1.getUuid(), ds.get(0).getDistributionId(), "never the internal uuid");
		assertEquals(ds.get(0).getDistributionId(), service.transformComponentReleaseToTea(h, rd, comp).getDistributions().get(0)
				.getDistributionId(), "stable across renderings");
		assertEquals("https://registry.example.com/x", ds.get(0).getUrl().toString());
		assertNull(ds.get(1).getUrl(), "a relative link is no url");
		assertEquals(List.of(hex), ds.get(0).getChecksums().stream().map(c -> c.getAlgValue()).toList(),
				"lowercase hex only; MD5 and non-hex dropped");
		assertNull(ds.get(1).getChecksums(), "nothing survives, no checksums");
		assertEquals("the image", ds.get(0).getDescription());
		assertEquals(List.of(TeaIdentifierType.PURL), ds.get(0).getIdentifiers().stream().map(TeaIdentifier::getIdType).toList());
		assertConforms(json(r), "component-release");
	}

	// 10 (task TEA-19, rows 34 and 35): the distribution url passes TeaExternalUrl or is left out, read live
	@Test
	void aDistributionUrlIsServedOnlyWhenTheExternalUrlRuleAdmitsIt() {
		ComponentData comp = component(ComponentType.COMPONENT, ORG, true);
		ReleaseData rd = release(comp, List.of());
		TeaPublicationData h = head(rd, comp, null);
		String hex = "ab".repeat(32);
		List<String> links = List.of("https://files.example/x.zip", "javascript:alert(1)", "https://user:pw@files.example/x",
				"/relative/x.zip", " https://files.example/y.zip ");
		List<DeliverableData> ds = new ArrayList<>();
		for (int i = 0; i < links.size(); i++) {
			ds.add(deliverable("row " + (char) ('a' + i), links.get(i),
					new DigestRecord(DigestAlgorithm.SHA_256, hex, DigestScope.ORIGINAL_FILE)));
		}
		VariantData vd = new VariantData();
		vd.setOutboundDeliverables(new LinkedHashSet<>(ds.stream().map(DeliverableData::getUuid).toList()));
		when(variants.getBaseVariantForRelease(any(ReleaseData.class))).thenReturn(vd);

		TeaComponentRelease r = service.transformComponentReleaseToTea(h, rd, comp);

		List<TeaReleaseDistribution> out = r.getDistributions();
		assertEquals(5, out.size());
		assertEquals("https://files.example/x.zip", out.get(0).getUrl().toString());
		for (int i = 1; i < 5; i++) assertNull(out.get(i).getUrl(), links.get(i));
		for (int i = 0; i < 5; i++) {
			assertEquals(TeaTransformerService.distributionIdOf(ds.get(i).getUuid()), out.get(i).getDistributionId());
			assertEquals("row " + (char) ('a' + i), out.get(i).getDescription());
			assertEquals(List.of(hex), out.get(i).getChecksums().stream().map(c -> c.getAlgValue()).toList());
		}
		String serialised = json(r);
		JsonNode tree = TeaJson.MAPPER.readTree(serialised).get("distributions");
		assertTrue(tree.get(0).has("url"), serialised);
		for (int i = 1; i < 5; i++) assertFalse(tree.get(i).has("url"), "no key, not null: " + serialised);
		for (String absent : List.of("javascript:alert(1)", "user:pw", "/relative/x.zip")) {
			assertFalse(serialised.contains(absent), absent + ": " + serialised);
		}
		assertConforms(serialised, "component-release");

		// row 35: the rendering reads the deliverable as it stands; the omission was the whitespace, not the host
		Link unpadded = new Link();
		unpadded.setUri("https://files.example/y.zip");
		ds.get(4).getSoftwareMetadata().setDownloadLinks(new ArrayList<>(List.of(unpadded)));
		TeaComponentRelease again = service.transformComponentReleaseToTea(h, rd, comp);
		assertEquals("https://files.example/y.zip", again.getDistributions().get(4).getUrl().toString());
		assertTrue(TeaJson.MAPPER.readTree(json(again)).get("distributions").get(4).has("url"));
	}

	// 10 (task TEA-19, row 36): admittedUri answers the stored string unchanged, or null
	@Test
	void admittedUriIsTheExternalUrlRuleWithoutTrimOrNormalisation() {
		assertNull(TeaTransformerService.admittedUri(null));
		assertNull(TeaTransformerService.admittedUri(""));
		for (String refused : List.of("javascript:alert(1)", "https://user:pw@files.example/x", "/relative/x.zip",
				" https://files.example/y.zip ", "https://files.example/" + "a".repeat(4075))) {
			assertNull(TeaTransformerService.admittedUri(refused), refused);
		}
		for (String admitted : List.of("https://files.example/x.zip", "HTTPS://FILES.EXAMPLE/x")) {
			assertEquals(admitted, TeaTransformerService.admittedUri(admitted).toString());
		}
	}

	// 8
	@Test
	void aProductListsOwnOrganizationChildrenPinnedOnlyWhenServed() {
		ComponentData prod = component(ComponentType.PRODUCT, ORG, true);
		ComponentData c1 = component(ComponentType.COMPONENT, ORG, true);
		ComponentData cHidden = component(ComponentType.COMPONENT, ORG, true);
		ComponentData cNoId = component(ComponentType.COMPONENT, ORG, false);
		ComponentData cForeign = component(ComponentType.COMPONENT, UUID.randomUUID(), true);
		ReleaseData r1 = release(c1, List.of());
		ReleaseData r1b = release(c1, List.of());
		ReleaseData rHidden = release(cHidden, List.of());
		ReleaseData rNoId = release(cNoId, List.of());
		ReleaseData rForeign = release(cForeign, List.of());
		UUID missing = UUID.randomUUID();
		for (ReleaseData r : List.of(r1, r1b, rHidden, rNoId, rForeign)) when(releases.getReleaseData(r.getUuid())).thenReturn(Optional.of(r));
		when(releases.getReleaseData(missing)).thenReturn(Optional.empty());
		for (ComponentData c : List.of(c1, cHidden, cNoId, cForeign)) when(components.getComponentData(c.getUuid())).thenReturn(Optional.of(c));
		TeaPublicationData h1 = head(r1, c1, null);
		TeaPublicationData h1b = head(r1b, c1, null);
		TeaPublicationData hHidden = head(rHidden, cHidden, null);
		hHidden.setState(TeaPublicationState.HIDDEN);
		when(pubs.getPublicationOfRelease(r1.getUuid())).thenReturn(Optional.of(h1));
		when(pubs.getPublicationOfRelease(r1b.getUuid())).thenReturn(Optional.of(h1b));
		when(pubs.getPublicationOfRelease(rHidden.getUuid())).thenReturn(Optional.of(hHidden));
		ReleaseData pr = release(prod, List.of());
		ReflectionTestUtils.setField(pr, "parentReleases", new ArrayList<>(List.of(ParentRelease.minimalParentReleaseFactory(r1.getUuid()),
				ParentRelease.minimalParentReleaseFactory(rHidden.getUuid()), ParentRelease.minimalParentReleaseFactory(rNoId.getUuid()),
				ParentRelease.minimalParentReleaseFactory(rForeign.getUuid()), ParentRelease.minimalParentReleaseFactory(missing),
				ParentRelease.minimalParentReleaseFactory(r1b.getUuid()))));

		List<TeaComponentRef> served = service.componentRefs(pr, prod, p -> true);
		assertEquals(List.of(c1.getTeaUuid(), cHidden.getTeaUuid(), c1.getTeaUuid()), served.stream().map(TeaComponentRef::getUuid).toList());
		assertEquals(h1.getUuid(), served.get(0).getRelease());
		assertNull(served.get(1).getRelease(), "a hidden child is never pinned");
		assertEquals(h1b.getUuid(), served.get(2).getRelease());
		List<TeaComponentRef> refused = service.componentRefs(pr, prod, p -> false);
		assertEquals(3, refused.size());
		assertTrue(refused.stream().allMatch(r -> null == r.getRelease()), "never a pin the reader is refused");
		ReflectionTestUtils.setField(pr, "parentReleases", new ArrayList<>());
		assertTrue(service.componentRefs(pr, prod, p -> true).isEmpty());
		assertConforms(json(service.transformProductReleaseToTea(head(pr, prod, null), pr, prod, served)), "product-release");
	}

	// 9
	@Test
	void cleFromGivenReleasesOnlyWithoutEmptyOptionalLists() {
		ComponentData comp = component(ComponentType.COMPONENT, ORG, true);
		comp.setVersionSchema("semver");
		ReleaseData given = release(comp, List.of());
		given.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
				ReleaseLifecycle.ASSEMBLED.name(), ReleaseLifecycle.GENERAL_AVAILABILITY.name(), null,
				ZonedDateTime.parse("2026-01-01T00:00:00Z"), mock(WhoUpdated.class)));
		ReleaseData other = release(comp, List.of());
		ReflectionTestUtils.setField(other, "version", "9.9.9");
		other.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
				ReleaseLifecycle.ASSEMBLED.name(), ReleaseLifecycle.GENERAL_AVAILABILITY.name(), null,
				ZonedDateTime.parse("2026-02-01T00:00:00Z"), mock(WhoUpdated.class)));
		TeaCle cle = service.transformComponentOrProductToCle(comp, List.of(given));
		assertEquals(1, cle.getEvents().size());
		assertEquals("1.2.3", cle.getEvents().get(0).getVersion());
		String out = json(cle);
		JsonNode ev = TeaJson.MAPPER.readTree(out).get("events").get(0);
		for (String absent : List.of("versions", "identifiers", "references")) assertFalse(ev.has(absent), absent + ": " + out);
		assertConforms(out, "cle");

		when(releases.listReleaseDatasOfComponent(any(UUID.class), any(Integer.class), any(Integer.class))).thenReturn(List.of(given, other));
		assertEquals(2, service.transformComponentToCle(comp).getEvents().size(), "the internal export reads every release");
		assertConforms(json(service.transformReleaseToCle(other)), "cle");
	}
}
