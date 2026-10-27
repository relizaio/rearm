/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.model.ApiKey;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaCleEventType;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.tea.TeaTransformerService;

/**
 * Task TEA-6, design round 2 case 36 (T-2): {@code /component/{uuid}/cle} and
 * {@code /product/{uuid}/cle} carry the events of the releases served to the caller and of no
 * other. One object per endpoint holds a served release and one release in each unserved state
 * (published then hidden, never published, published then CANCELLED), every one of them crossed
 * into GENERAL_AVAILABILITY, so each would contribute a {@code released} event if it were included;
 * the version strings are the handle. The organization profile is PUBLIC, so anonymous reads are
 * served and the restriction, not a 401, is what is checked.
 */
public class TeaSurfaceCleIntegrationTest extends TeaSurfaceIntegrationTestBase {

	@Autowired TeaTransformerService teaTransformerService;

	/** The served version first, then the hidden, the never-published and the cancelled one. */
	record Versions(String served, String hidden, String unpublished, String cancelled) {
		List<String> unserved() {
			return List.of(hidden, unpublished, cancelled);
		}

		Set<String> all() {
			return Set.of(served, hidden, unpublished, cancelled);
		}
	}

	private static int n() {
		return ThreadLocalRandom.current().nextInt(100000);
	}

	private void ga(UUID release) throws Exception {
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.GENERAL_AVAILABILITY, WU);
	}

	/** Moves {@code served}, {@code hidden}, {@code unpublished} and {@code cancelled} into their states. */
	private void spread(UUID served, UUID hidden, UUID unpublished, UUID cancelled) throws Exception {
		publish(served, TeaPublishMode.PUBLISH);
		ga(served);
		publish(hidden, TeaPublishMode.PUBLISH);
		teaPublicationService.hide(hidden, null, WU);
		ga(hidden);
		ga(unpublished);
		publish(cancelled, TeaPublishMode.PUBLISH);
		ga(cancelled);
		ossReleaseService.updateReleaseLifecycle(cancelled, ReleaseLifecycle.CANCELLED, WU);
	}

	@SuppressWarnings("unchecked")
	private static void assertOnlyServedEvents(HttpResponse<String> r, Versions v, boolean keyed, String what) {
		assertEquals(200, r.statusCode(), what + ": " + r.body());
		assertConforms(r.body(), "cle");
		assertServedHeaders(r, keyed, what);
		List<Map<String, Object>> events = (List<Map<String, Object>>) json(r).get("events");
		Set<String> released = events.stream().filter(e -> "released".equals(e.get("type")))
				.map(e -> (String) e.get("version")).collect(Collectors.toSet());
		assertEquals(Set.of(v.served()), released, what + ": " + r.body());
		for (Map<String, Object> e : events) {
			Set<String> versions = new HashSet<>();
			if (e.get("version") instanceof String s) versions.add(s);
			if (e.get("versions") instanceof List<?> l) {
				for (Object o : l) if (((Map<String, Object>) o).get("version") instanceof String s) versions.add(s);
			}
			for (String u : v.unserved()) assertFalse(versions.contains(u), what + ": " + u + " in " + e);
		}
		for (String u : v.unserved()) assertFalse(r.body().contains(u), what + ": " + u + " anywhere: " + r.body());
	}

	private static void assertReleases(HttpResponse<String> r, UUID head, String what) {
		assertEquals(200, r.statusCode(), what + ": " + r.body());
		assertEquals(List.of(head.toString()), uuidsOf(r), what);
	}

	private static Set<String> releasedVersions(TeaCle cle) {
		return cle.getEvents().stream().filter(e -> e.getType() == TeaCleEventType.RELEASED)
				.map(e -> e.getVersion()).collect(Collectors.toSet());
	}

	@Test
	void bothCleEndpointsCarryOnlyTheServedReleasesEvents() throws Exception {
		saveOrgProfile(TeaVisibility.PUBLIC, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		String t = tag();

		World multi = componentReleaseWithPurl(org, "pkg:generic/tea6-multi-" + t + "@1.0.0", List.of());
		Versions cv = new Versions(stored(multi.release()).getVersion(), "1.1." + n(), "1.2." + n(), "1.3." + n());
		UUID v2 = anotherRelease(multi, cv.hidden());
		UUID v3 = anotherRelease(multi, cv.unpublished());
		UUID v4 = anotherRelease(multi, cv.cancelled());
		spread(multi.release(), v2, v3, v4);

		UUID p1 = productWithPurl(List.of(multi.release()), "pkg:generic/tea6-prod2-" + t + "@2.0.0");
		Versions pv = new Versions(stored(p1).getVersion(), "2.1." + n(), "2.2." + n(), "2.3." + n());
		UUID p2 = anotherProductRelease(p1, pv.hidden());
		UUID p3 = anotherProductRelease(p1, pv.unpublished());
		UUID p4 = anotherProductRelease(p1, pv.cancelled());
		spread(p1, p2, p3, p4);

		ComponentData multiCd = getComponentService.getComponentData(multi.component()).orElseThrow();
		ComponentData prodCd = getComponentService.getComponentData(stored(p1).getComponent()).orElseThrow();
		UUID multiHead = teaPublicationService.getPublicationOfRelease(multi.release()).orElseThrow().getUuid();
		UUID prodHead = teaPublicationService.getPublicationOfRelease(p1).orElseThrow().getUuid();

		ApiKey orgwideKey = externalApiKeyService.create(org.getUuid(), "orgwide-" + t, null, WU);
		externalApiKeyService.setGrants(orgwideKey.getUuid(), List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.TEA_READ),
				List.of())), WU);
		String teaOrg = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getTeaUuid().toString();
		String base = root() + "/tea/" + teaOrg + "/v1.0.0";

		Map<String, String> callers = new LinkedHashMap<>();
		callers.put("anonymous", null);
		callers.put("bad", BAD);
		callers.put("orgwide", token(orgwideKey));
		for (Map.Entry<String, String> c : callers.entrySet()) {
			boolean keyed = "orgwide".equals(c.getKey());
			assertOnlyServedEvents(get(base + "/component/" + multiCd.getTeaUuid() + "/cle", c.getValue()), cv, keyed,
					c.getKey() + " component cle");
			assertOnlyServedEvents(get(base + "/product/" + prodCd.getTeaUuid() + "/cle", c.getValue()), pv, keyed,
					c.getKey() + " product cle");
			// the served heads the CLE is built from, pinned beside it; a listing refuses an invalid token first
			// (round 1, decision 9), so the garbage bearer gets its 401 there
			if (BAD.equals(c.getValue())) {
				assertNoStore401(get(base + "/component/" + multiCd.getTeaUuid() + "/releases", BAD), true, "bad component releases");
				assertNoStore401(get(base + "/product/" + prodCd.getTeaUuid() + "/releases", BAD), true, "bad product releases");
				continue;
			}
			assertReleases(get(base + "/component/" + multiCd.getTeaUuid() + "/releases", c.getValue()), multiHead,
					c.getKey() + " component releases");
			assertReleases(get(base + "/product/" + prodCd.getTeaUuid() + "/releases", c.getValue()), prodHead,
					c.getKey() + " product releases");
		}

		// the derivation itself is not what restricts: the GraphQL export's path reads every release
		assertEquals(cv.all(), releasedVersions(teaTransformerService.transformComponentToCle(multiCd)));
		assertEquals(pv.all(), releasedVersions(teaTransformerService.transformProductToCle(prodCd)));
	}
}
