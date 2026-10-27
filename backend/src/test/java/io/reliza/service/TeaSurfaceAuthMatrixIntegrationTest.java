/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.service.tea.TeaOpenApiSchemas;

/**
 * Task TEA-6, design 4.8 cases 24, 26 and 36: the auth matrix per endpoint class over real HTTP.
 * Hidden, cancelled, disabled, unpublished, unknown and another organization's objects are the
 * same 404 OBJECT_UNKNOWN for every caller; PRIVATE asks anonymous and invalid-token callers to
 * authenticate and conceals itself from a key that does not cover it.
 */
public class TeaSurfaceAuthMatrixIntegrationTest extends TeaSurfaceIntegrationTestBase {

	private enum Expect { OK, AUTH, NOT_FOUND }

	private static final List<String> RELEASE_FAMILY = List.of("", "/cle", "/collections", "/collection/latest", "/collection/1");

	/** The design's table: PUBLIC serves all; PRIVATE: 401, 401, 404, 200; everything concealed: 404. */
	private static Expect expected(String object, String caller) {
		if ("pub".equals(object)) return Expect.OK;
		if ("priv".equals(object)) {
			return switch (caller) {
				case "anonymous", "bad" -> Expect.AUTH;
				case "other" -> Expect.NOT_FOUND;
				default -> Expect.OK;
			};
		}
		return Expect.NOT_FOUND;
	}

	private void check(TeaWorld w, String path, String object, String schema) throws Exception {
		for (Map.Entry<String, String> c : callers(w).entrySet()) {
			HttpResponse<String> r = get(w.base() + path, c.getValue());
			String what = c.getKey() + " GET " + path + " (" + object + ")";
			switch (expected(object, c.getKey())) {
				case OK -> {
					assertEquals(200, r.statusCode(), what + ": " + r.body());
					assertServedHeaders(r, null != c.getValue() && !BAD.equals(c.getValue()), what);
					if (null != schema) assertConforms(r.body(), schema);
				}
				case AUTH -> assertNoStore401(r, "bad".equals(c.getKey()), what);
				case NOT_FOUND -> assertObjectUnknown(r, what);
			}
		}
	}

	private static String schemaOf(String kind, String suffix) {
		return switch (suffix) {
			case "" -> kind + "-release-with-collection";
			case "/cle" -> "cle";
			case "/collections" -> "paginated-collection-response";
			default -> "collection";
		};
	}

	// 24, class O: the component release family
	@Test
	void componentReleaseFamilyAnswersTheMatrix() throws Exception {
		TeaWorld w = world();
		Map<String, UUID> objects = new LinkedHashMap<>();
		objects.put("pub", w.pub().head());
		objects.put("priv", w.priv().head());
		objects.put("hid", w.hid().head());
		objects.put("low", w.low().head());
		objects.put("off", w.off().head());
		objects.put("unp", w.unpRelease());
		objects.put("random", UUID.randomUUID());
		objects.put("foreign", w.foreignHead());
		objects.put("prodAsComponent", w.prod().head());
		for (Map.Entry<String, UUID> o : objects.entrySet()) {
			for (String suffix : RELEASE_FAMILY) {
				check(w, "/componentRelease/" + o.getValue() + suffix, o.getKey(), schemaOf("component", suffix));
			}
		}
	}

	// 24, class O: the product release family, the product and the components
	@Test
	void productReleaseFamilyProductAndComponentsAnswerTheMatrix() throws Exception {
		TeaWorld w = world();
		// prod resolves to the PRIVATE organization profile, so it reads as the priv row
		for (String suffix : RELEASE_FAMILY) {
			check(w, "/productRelease/" + w.prod().head() + suffix, "priv", schemaOf("product", suffix));
			check(w, "/productRelease/" + w.pub().head() + suffix, "componentAsProduct", null);
			check(w, "/productRelease/" + UUID.randomUUID() + suffix, "random", null);
		}
		check(w, "/product/" + w.prod().teaComponent(), "priv", "product");
		check(w, "/product/" + w.prod().teaComponent() + "/cle", "priv", "cle");
		check(w, "/product/" + w.pub().teaComponent(), "componentAsProduct", null);
		check(w, "/product/" + UUID.randomUUID(), "random", null);
		Map<String, Obj> comps = new LinkedHashMap<>();
		comps.put("pub", w.pub());
		comps.put("priv", w.priv());
		comps.put("hid", w.hid());
		comps.put("low", w.low());
		comps.put("off", w.off());
		for (Map.Entry<String, Obj> c : comps.entrySet()) {
			check(w, "/component/" + c.getValue().teaComponent(), c.getKey(), "component");
			check(w, "/component/" + c.getValue().teaComponent() + "/cle", c.getKey(), "cle");
		}
		check(w, "/component/" + w.prod().teaComponent(), "productAsComponent", null);
		check(w, "/component/" + w.pub().component(), "internalUuid", null);
	}

	// 24, class L
	@Test
	void listingsShowThePublicSubsetPlusWhatTheKeyCovers() throws Exception {
		TeaWorld w = world();
		for (String path : List.of("/products", "/productReleases", "/components", "/componentReleases",
				"/product/" + w.prod().teaComponent() + "/releases", "/component/" + w.priv().teaComponent() + "/releases")) {
			assertNoStore401(get(w.base() + path, BAD), true, "bad GET " + path);
		}
		// the listing rule comes first: before the parent object's decision and before the parameters
		assertNoStore401(get(w.base() + "/component/" + UUID.randomUUID() + "/releases", BAD), true, "bad, unknown parent");
		assertNoStore401(get(w.base() + "/product/" + w.pub().teaComponent() + "/releases", BAD), true, "bad, wrong-kind parent");
		assertNoStore401(get(w.base() + "/products?pageToken=x", BAD), true, "bad, with a page token");
		HttpResponse<String> anon = get(w.base() + "/componentReleases", null);
		assertEquals(200, anon.statusCode(), anon.body());
		assertServedHeaders(anon, false, "anonymous /componentReleases");
		assertConforms(anon.body(), "paginated-component-release-response");
		assertEquals(List.of(w.pub().head().toString()), uuidsOf(anon));
		HttpResponse<String> cover = get(w.base() + "/componentReleases", w.cover());
		assertServedHeaders(cover, true, "cover /componentReleases");
		assertEquals(Set2.of(w.pub().head(), w.priv().head()), Set2.ofStrings(uuidsOf(cover)));
		HttpResponse<String> other = get(w.base() + "/componentReleases", w.other());
		assertEquals(List.of(w.pub().head().toString()), uuidsOf(other));

		HttpResponse<String> comps = get(w.base() + "/components", null);
		assertConforms(comps.body(), "paginated-component-response");
		assertEquals(List.of(w.pub().teaComponent().toString()), uuidsOf(comps));
		assertEquals(Set2.of(w.pub().teaComponent(), w.priv().teaComponent()), Set2.ofStrings(uuidsOf(get(w.base() + "/components", w.cover()))));
		assertEquals(List.of(w.pub().teaComponent().toString()), uuidsOf(get(w.base() + "/components", w.other())));

		// 26: a product whose releases are all PRIVATE
		HttpResponse<String> products = get(w.base() + "/products", null);
		assertConforms(products.body(), "paginated-product-response");
		assertTrue(uuidsOf(products).isEmpty(), products.body());
		assertEquals(List.of(w.prod().teaComponent().toString()), uuidsOf(get(w.base() + "/products", w.cover())));
		assertTrue(uuidsOf(get(w.base() + "/products", w.other())).isEmpty());
		assertTrue(uuidsOf(get(w.base() + "/productReleases", null)).isEmpty());
		HttpResponse<String> prs = get(w.base() + "/productReleases", w.cover());
		assertConforms(prs.body(), "paginated-product-release-response");
		assertEquals(List.of(w.prod().head().toString()), uuidsOf(prs));
		assertNoStore401(get(w.base() + "/product/" + w.prod().teaComponent(), null), false, "anonymous product");

		assertNoStore401(get(w.base() + "/component/" + w.priv().teaComponent() + "/releases", null), false, "anon priv releases");
		assertObjectUnknown(get(w.base() + "/component/" + w.priv().teaComponent() + "/releases", w.other()), "other priv releases");
		HttpResponse<String> privReleases = get(w.base() + "/component/" + w.priv().teaComponent() + "/releases", w.cover());
		assertEquals(List.of(w.priv().head().toString()), uuidsOf(privReleases));
		HttpResponse<String> prodReleases = get(w.base() + "/product/" + w.prod().teaComponent() + "/releases", w.cover());
		assertEquals(List.of(w.prod().head().toString()), uuidsOf(prodReleases));
		assertEquals(Boolean.FALSE, json(prodReleases).get("hasNext"));
		assertFalse(json(prodReleases).containsKey("nextPageToken"));
	}

	// 36, the matrix part (round 2): the leak case is TeaSurfaceCleIntegrationTest
	@Test
	@SuppressWarnings("unchecked")
	void cleAnswersByTheAuthMatrix() throws Exception {
		TeaWorld w = world();
		ossReleaseService.updateReleaseLifecycle(w.prod().release(), io.reliza.model.ReleaseData.ReleaseLifecycle.GENERAL_AVAILABILITY, WU);
		ossReleaseService.updateReleaseLifecycle(w.pub().release(), io.reliza.model.ReleaseData.ReleaseLifecycle.GENERAL_AVAILABILITY, WU);
		ossReleaseService.updateReleaseLifecycle(w.priv().release(), io.reliza.model.ReleaseData.ReleaseLifecycle.GENERAL_AVAILABILITY, WU);
		assertNoStore401(get(w.base() + "/component/" + w.priv().teaComponent() + "/cle", null), false, "anon priv cle");
		HttpResponse<String> pubCle = get(w.base() + "/component/" + w.pub().teaComponent() + "/cle", null);
		assertEquals(200, pubCle.statusCode(), pubCle.body());
		assertConforms(pubCle.body(), "cle");
		List<Map<String, Object>> events = (List<Map<String, Object>>) json(pubCle).get("events");
		assertTrue(events.stream().anyMatch(e -> "released".equals(e.get("type"))), pubCle.body());
		assertTrue(events.stream().noneMatch(e -> e.containsKey("versions") && ((List<?>) e.get("versions")).isEmpty()),
				"no empty versions: " + pubCle.body());
		HttpResponse<String> privCle = get(w.base() + "/component/" + w.priv().teaComponent() + "/cle", w.cover());
		assertEquals(200, privCle.statusCode(), privCle.body());
		assertFalse(((List<?>) json(privCle).get("events")).isEmpty());
		HttpResponse<String> prodCle = get(w.base() + "/product/" + w.prod().teaComponent() + "/cle", w.cover());
		assertEquals(200, prodCle.statusCode(), prodCle.body());
		assertConforms(prodCle.body(), "cle");
	}

	/** Order-free comparison of uuid lists. */
	static final class Set2 {
		static java.util.Set<String> of(UUID... u) {
			java.util.Set<String> s = new java.util.HashSet<>();
			for (UUID x : u) s.add(x.toString());
			return s;
		}

		static java.util.Set<String> ofStrings(List<String> l) {
			return new java.util.HashSet<>(new ArrayList<>(l));
		}
	}

	static {
		// fail fast if the fixture is missing, rather than inside the first matrix case
		TeaOpenApiSchemas.names();
	}
}
