/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CommonVariables.ApiKeyGroupKind;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import tools.jackson.databind.JsonNode;

/**
 * FDAW-37 row 18, the HTTP half: a key with no grants of its own reads through the ACTIVE CUSTOMER group
 * holding it, exactly what the group grants, and stops at the next request with the same token. On the
 * TEA-6 world, whose organization profile is PRIVATE, plus one published PRIVATE component q outside prod.
 */
public class TeaSurfaceCustomerGroupIntegrationTest extends TeaSurfaceIntegrationTestBase {

	@Autowired ApiKeyGroupService apiKeyGroupService;

	private record Customer(ApiKey key, ApiKeyGroupData group, String token) {}

	private Customer customer(PermissionScope scope, UUID object) throws Exception {
		ApiKey key = externalApiKeyService.create(org.getUuid(), "customer-" + tag(), null, WU);
		ApiKeyGroupData g = apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder().name("customer-" + UUID.randomUUID())
				.org(org.getUuid()).kind(ApiKeyGroupKind.CUSTOMER).build(), WU);
		g = apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).apiKeys(Set.of(key.getUuid()))
				.permissions(List.of(UserGroupPermissionDto.builder().scope(scope).objectId(object).type(PermissionType.READ_ONLY).build()))
				.build(), WU);
		return new Customer(key, g, token(key));
	}

	private Obj publishedQ() throws Exception {
		String purl = "pkg:generic/tea6-q-" + tag() + "@1.0.0";
		World q = componentReleaseWithPurl(org, purl, List.of());
		publish(q.release(), TeaPublishMode.PUBLISH);
		return obj(q, purl);
	}

	private void assertReads(String url, String bearer, String what) throws Exception {
		HttpResponse<String> r = get(url, bearer);
		assertEquals(200, r.statusCode(), what + ": " + r.body());
		assertServedHeaders(r, true, what);
	}

	private static String prodRelease(TeaWorld w) {
		return w.base() + "/productRelease/" + w.prod().head();
	}

	private static String componentRelease(TeaWorld w, Obj o) {
		return w.base() + "/componentRelease/" + o.head();
	}

	/** Every entry of the head's latest collection: both keys get the same answer, byte for byte. */
	private int assertSameEntries(TeaWorld w, UUID head, String mine, String cover) throws Exception {
		TeaPublishedCollectionData latest = teaPublicationService.getLatestCollection(head).orElseThrow();
		int entries = 0;
		for (JsonNode artifact : Utils.OM.readTree(latest.getDocument()).get("artifacts")) {
			String mediaType = artifact.get("formats").get(0).get("mediaType").asString();
			String url = w.base() + "/artifact/" + artifact.get("uuid").asString() + "/latest/download?mediaType="
					+ URLEncoder.encode(mediaType, StandardCharsets.UTF_8);
			HttpResponse<byte[]> theirs = getBytes(url, cover, null);
			HttpResponse<byte[]> ours = getBytes(url, mine, null);
			assertEquals(200, theirs.statusCode(), "the own-grant key reads " + url);
			assertEquals(200, ours.statusCode(), "the customer key reads " + url);
			assertArrayEquals(theirs.body(), ours.body(), url);
			entries++;
		}
		return entries;
	}

	@Test
	void eachCustomerReadsWhatItsGroupGrantsAndNothingElse() throws Exception {
		TeaWorld w = world();
		Obj q = publishedQ();
		Customer custP = customer(PermissionScope.COMPONENT, w.prod().component());
		Customer custQ = customer(PermissionScope.COMPONENT, q.component());
		Customer custOrg = customer(PermissionScope.ORGANIZATION, org.getUuid());

		// custP reads prod as the own-grant key does, and priv through the product's tree
		for (String url : List.of(w.base() + "/product/" + w.prod().teaComponent(), prodRelease(w),
				prodRelease(w) + "/collections", prodRelease(w) + "/collection/1", prodRelease(w) + "/collection/latest")) {
			HttpResponse<String> theirs = get(url, w.cover());
			assertEquals(200, theirs.statusCode(), url + ": " + theirs.body());
			assertReads(url, custP.token(), url);
			assertEquals(theirs.body(), get(url, custP.token()).body(), url);
		}
		assertFalse(0 == assertSameEntries(w, w.prod().head(), custP.token(), w.cover()), "the product's collection holds entries");
		assertReads(componentRelease(w, w.priv()), custP.token(), "custP on priv");
		assertFalse(0 == assertSameEntries(w, w.priv().head(), custP.token(), w.cover()), "the component's collection holds entries");
		assertObjectUnknown(get(componentRelease(w, q), custP.token()), "custP on q");
		assertObjectUnknown(get(w.base() + "/component/" + q.teaComponent(), custP.token()), "custP on the component q");

		// custQ: the grant of one customer reads for that customer only
		assertReads(componentRelease(w, q), custQ.token(), "custQ on q");
		assertReads(w.base() + "/component/" + q.teaComponent(), custQ.token(), "custQ on the component q");
		assertObjectUnknown(get(prodRelease(w), custQ.token()), "custQ on prod");
		assertObjectUnknown(get(w.base() + "/product/" + w.prod().teaComponent(), custQ.token()), "custQ on the product");
		assertObjectUnknown(get(componentRelease(w, w.priv()), custQ.token()), "custQ on priv");

		// custOrg: the organization-scope admit
		assertReads(prodRelease(w), custOrg.token(), "custOrg on prod");
		assertReads(componentRelease(w, q), custOrg.token(), "custOrg on q");
	}

	@Test
	void deactivatingARestoringAndARemovalApplyAtTheNextRequestWithTheSameToken() throws Exception {
		TeaWorld w = world();
		Customer custP = customer(PermissionScope.COMPONENT, w.prod().component());
		assertReads(prodRelease(w), custP.token(), "before");

		apiKeyGroupService.deactivateApiKeyGroup(custP.group().getUuid(), WU);
		assertObjectUnknown(get(prodRelease(w), custP.token()), "the group INACTIVE");
		assertObjectUnknown(get(componentRelease(w, w.priv()), custP.token()), "the group INACTIVE, the tree too");

		update(custP, UpdateApiKeyGroupDto.builder().status(ApiKeyGroupStatus.ACTIVE));
		assertReads(prodRelease(w), custP.token(), "restored");

		update(custP, UpdateApiKeyGroupDto.builder().apiKeys(Set.of()));
		assertObjectUnknown(get(prodRelease(w), custP.token()), "the key removed from the group");
		assertEquals(200, get(prodRelease(w), w.cover()).statusCode(), "the own-grant key is untouched");
	}

	private void update(Customer c, UpdateApiKeyGroupDto.UpdateApiKeyGroupDtoBuilder b) throws RelizaException {
		apiKeyGroupService.updateApiKeyGroup(b.groupId(c.group().getUuid()).build(), WU);
	}
}
