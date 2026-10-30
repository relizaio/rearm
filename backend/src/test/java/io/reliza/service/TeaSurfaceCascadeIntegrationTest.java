/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-6, design 4.8 cases 25 and 26: the cascade and the owner cap over HTTP. A product never
 * pins a reader to a child release the reader is refused; a child published only through the
 * cascade is listed, and conceals with its product.
 */
public class TeaSurfaceCascadeIntegrationTest extends TeaSurfaceIntegrationTestBase {

	@Test
	void theProductPinsOnlyWhatTheCallerIsServedAndItsCascadeChildrenFollowIt() throws Exception {
		saveOrgProfile(TeaVisibility.PUBLIC, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		String t = tag();
		World pub = componentReleaseWithPurl(org, "pkg:generic/tea6-cpub-" + t + "@1.0.0", List.of());
		World priv = componentReleaseWithPurl(org, "pkg:generic/tea6-cpriv-" + t + "@1.0.0", List.of());
		saveComponentProfile(priv.component(), TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE);
		UUID prodRelease = productWithPurl(List.of(pub.release(), priv.release()), null);
		publish(prodRelease, TeaPublishMode.PUBLISH);
		UUID prodHead = teaPublicationService.getPublicationOfRelease(prodRelease).orElseThrow().getUuid();
		Obj p = obj(pub, null);
		Obj q = obj(priv, null);
		ApiKey coverKey = externalApiKeyService.create(org.getUuid(), "cover-" + t, null, WU);
		externalApiKeyService.setGrants(coverKey.getUuid(), List.of(new PermissionDto(org.getUuid(), PermissionScope.COMPONENT,
				priv.component(), PermissionType.READ_ONLY, Set.of(PermissionFunction.TEA_READ), List.of())), WU);
		String cover = token(coverKey);
		String teaOrg = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getTeaUuid().toString();
		String base = root() + "/tea/" + teaOrg + "/v1.0.0";

		HttpResponse<String> anon = get(base + "/productRelease/" + prodHead, null);
		assertEquals(200, anon.statusCode(), anon.body());
		assertConforms(anon.body(), "product-release-with-collection");
		JsonNode comps = Utils.OM.readTree(anon.body()).get("components");
		assertEquals(2, comps.size(), anon.body());
		assertEquals(p.teaComponent().toString(), comps.get(0).get("uuid").asString());
		assertEquals(p.head().toString(), comps.get(0).get("release").asString(), "the PUBLIC child is pinned");
		assertEquals(q.teaComponent().toString(), comps.get(1).get("uuid").asString());
		assertFalse(comps.get(1).has("release"), "the PRIVATE child is not pinned for an anonymous reader");
		JsonNode keyed = Utils.OM.readTree(get(base + "/productRelease/" + prodHead, cover).body()).get("components");
		assertEquals(q.head().toString(), keyed.get(1).get("release").asString(), "pinned for a covering key");

		assertNoStore401(get(base + "/componentRelease/" + q.head(), null), false, "anonymous PRIVATE child");
		assertEquals(200, get(base + "/componentRelease/" + q.head(), cover).statusCode());
		// 26: a component published only through the cascade is listed
		assertTrue(uuidsOf(get(base + "/components", null)).contains(p.teaComponent().toString()));

		teaPublicationService.hide(prodRelease, null, WU);
		assertObjectUnknown(get(base + "/componentRelease/" + p.head(), null), "a cascade child conceals with its product");
		assertObjectUnknown(get(base + "/productRelease/" + prodHead, null), "the hidden product");
		publish(pub.release(), TeaPublishMode.PUBLISH_OR_REPUBLISH);
		assertEquals(200, get(base + "/componentRelease/" + p.head(), null).statusCode(), "a direct publish serves it again");
	}
}
