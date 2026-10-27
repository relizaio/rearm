/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyAccess;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.ApiKeyAccessRepository;
import io.reliza.ws.tea.LogEventCapture;
import io.reliza.ws.tea.TeaAuthenticationFilter;
import io.reliza.ws.tea.TeaRequests;

/**
 * Task TEA-18, design 5.4: a valid TEA token of an EXTERNAL key of another organization is no credential on this
 * organization's TEA surface. It is handled as a request without an Authorization header (the anonymous cache
 * header on PUBLIC, the anonymous 401 on PRIVATE, the PUBLIC subset in listings), writes no {@code api_key_access}
 * row in either organization, does not move the key's last access date and logs nothing. A key of the organization
 * it reads is unchanged, and the foreign key is still a credential on its own surface.
 *
 * <p>The rig gives every request its own X-Forwarded-For address (design 5.4 fact (a)), so the hourly dedupe never
 * joins two requests: a key that exchanges and then reads has two rows, and a count of 1 after a read is the
 * exchange's row alone. Every case mints a fresh key, so every count is independent of the rest of the run.
 */
public class TeaSurfaceForeignKeyIntegrationTest extends TeaSurfaceIntegrationTestBase {

	@Autowired ApiKeyAccessService apiKeyAccessService;
	@Autowired ApiKeyAccessRepository apiKeyAccessRepository;

	/** The organization of {@code w}'s foreign published release. */
	UUID foreignOrg(TeaWorld w) {
		return teaPublicationService.getPublication(w.foreignHead()).orElseThrow().getOrg();
	}

	/** The TEA 1.0.0 base of {@code o}. */
	String foreignBase(UUID o) {
		return root() + "/tea/" + getOrganizationService.getOrganizationData(o).orElseThrow().getTeaUuid() + "/v1.0.0";
	}

	/** A fresh EXTERNAL key of {@code o} holding ORGANIZATION-scope TEA_READ on {@code o}. */
	ApiKey externalKeyOf(UUID o, String name) throws Exception {
		ApiKey k = externalApiKeyService.create(o, name + "-" + tag(), null, WU);
		externalApiKeyService.setGrants(k.getUuid(), List.of(new PermissionDto(o, PermissionScope.ORGANIZATION, o,
				PermissionType.READ_ONLY, Set.of(PermissionFunction.TEA_READ), List.of())), WU);
		return k;
	}

	int count(ApiKey k) {
		return apiKeyAccessRepository.countKeyAccessByKeyId(k.getUuid());
	}

	/** The rows of {@code o} for {@code k} (the latest per key: zero or one). */
	List<ApiKeyAccess> rowsOf(UUID o, ApiKey k) {
		return apiKeyAccessService.listKeyAccessByOrg(o).stream().filter(a -> k.getUuid().equals(a.getApiKeyUuid())).toList();
	}

	ZonedDateTime lastAccess(ApiKey k) {
		apiKeyAccessService.flushPendingLastAccessTouches();
		return apiKeyService.getApiKey(k.getUuid()).orElseThrow().getLastAccessDate();
	}

	@Test
	void aForeignKeysPublicReadIsAnonymousAndLeavesNoRowInEitherOrganization() throws Exception {
		TeaWorld w = world();
		UUID foreignOrg = foreignOrg(w);
		ApiKey k = externalKeyOf(foreignOrg, "far");
		String bearer = token(k, foreignOrg);
		assertEquals(1, count(k), "the exchange writes one row");
		List<ApiKeyAccess> exchange = rowsOf(foreignOrg, k);
		assertEquals(1, exchange.size());
		ZonedDateTime t0 = lastAccess(k);
		assertNotNull(t0, "the exchange touched the key");
		String url = w.base() + "/component/" + w.pub().teaComponent();

		HttpResponse<String> r;
		try (LogEventCapture auth = LogEventCapture.of(TeaAuthorizationService.class, Level.ALL);
				LogEventCapture requests = LogEventCapture.of(TeaRequests.class, Level.ALL);
				LogEventCapture filter = LogEventCapture.of(TeaAuthenticationFilter.class, Level.ALL)) {
			r = get(url, bearer);
			assertEquals(List.of(), auth.events(), "nothing from TeaAuthorizationService");
			assertEquals(List.of(), requests.events(), "nothing from TeaRequests");
			assertEquals(List.of(), filter.events(), "nothing from TeaAuthenticationFilter");
		}
		assertEquals(200, r.statusCode(), r.body());
		assertServedHeaders(r, false, "the foreign key on PUBLIC is served the anonymous header");
		HttpResponse<String> anon = get(url, null);
		assertEquals(200, anon.statusCode(), anon.body());
		assertEquals(json(anon), json(r), "the same body as the anonymous read");

		assertEquals(1, count(k), "the foreign read recorded nothing");
		assertEquals(List.of(), rowsOf(org.getUuid(), k), "no row for the foreign key in the served organization");
		List<ApiKeyAccess> after = rowsOf(foreignOrg, k);
		assertEquals(1, after.size());
		assertEquals(exchange.get(0).getUuid(), after.get(0).getUuid(), "the key organization keeps only the exchange's row");
		assertEquals(exchange.get(0).getAccessDate(), after.get(0).getAccessDate());
		assertEquals(t0, lastAccess(k), "the foreign read did not touch the key");
	}

	@Test
	void aForeignKeyOnPrivateContentIsRefusedAsAnonymous() throws Exception {
		TeaWorld w = world();
		UUID foreignOrg = foreignOrg(w);
		ApiKey k = externalKeyOf(foreignOrg, "far");
		String bearer = token(k, foreignOrg);
		for (String path : List.of("/component/" + w.priv().teaComponent(), "/componentRelease/" + w.priv().head(),
				"/component/" + w.priv().teaComponent() + "/releases")) {
			assertNoStore401(get(w.base() + path, bearer), false, "the foreign key on PRIVATE " + path);
		}
		assertEquals(1, count(k), "only the exchange's row");
		assertEquals(List.of(), rowsOf(org.getUuid(), k), "no row for the foreign key in the served organization");
	}

	@Test
	void aForeignKeyListsThePublicSubsetUnderTheAnonymousHeader() throws Exception {
		TeaWorld w = world();
		UUID foreignOrg = foreignOrg(w);
		ApiKey k = externalKeyOf(foreignOrg, "far");
		String bearer = token(k, foreignOrg);
		HttpResponse<String> comps = get(w.base() + "/components", bearer);
		assertEquals(200, comps.statusCode(), comps.body());
		assertServedHeaders(comps, false, "the foreign key's /components");
		assertEquals(List.of(w.pub().teaComponent().toString()), uuidsOf(comps));
		assertEquals(uuidsOf(get(w.base() + "/components", null)), uuidsOf(comps), "the anonymous answer");
		HttpResponse<String> rels = get(w.base() + "/componentReleases", bearer);
		assertEquals(200, rels.statusCode(), rels.body());
		assertServedHeaders(rels, false, "the foreign key's /componentReleases");
		assertEquals(List.of(w.pub().head().toString()), uuidsOf(rels));
		assertEquals(uuidsOf(get(w.base() + "/componentReleases", null)), uuidsOf(rels), "the anonymous answer");
		assertEquals(1, count(k), "only the exchange's row");
	}

	@Test
	void aSameOrganizationKeyIsUnchanged() throws Exception {
		TeaWorld w = world();
		UUID foreignOrg = foreignOrg(w);
		ApiKey s = externalKeyOf(org.getUuid(), "own");
		String bearer = token(s);
		List<ApiKeyAccess> exchange = rowsOf(org.getUuid(), s);
		assertEquals(1, exchange.size(), "the exchange's row, in the key's organization");
		ApiKeyAccess exchangeRow = exchange.get(0);
		assertEquals(1, count(s));

		HttpResponse<String> r = get(w.base() + "/component/" + w.pub().teaComponent(), bearer);
		assertEquals(200, r.statusCode(), r.body());
		assertServedHeaders(r, true, "a key of the organization on PUBLIC keeps the keyed header");
		assertEquals(2, count(s), "the exchange's row and the read's, at two forwarded addresses");
		List<ApiKeyAccess> latest = rowsOf(org.getUuid(), s);
		assertEquals(1, latest.size());
		assertEquals(org.getUuid(), latest.get(0).getOrg());
		assertNotEquals(exchangeRow.getUuid(), latest.get(0).getUuid(), "the latest row is the read's");
		assertNotEquals(exchangeRow.getIpAddress(), latest.get(0).getIpAddress(), "at the read's own forwarded address");
		assertFalse(latest.get(0).getAccessDate().isBefore(exchangeRow.getAccessDate()));
		assertEquals(List.of(), rowsOf(foreignOrg, s), "nothing in the other organization");

		HttpResponse<String> priv = get(w.base() + "/component/" + w.priv().teaComponent(), bearer);
		assertEquals(200, priv.statusCode(), priv.body());
		assertServedHeaders(priv, true, "a key of the organization is still a credential on PRIVATE");
	}

	@Test
	void theForeignKeyIsStillACredentialOnItsOwnSurface() throws Exception {
		TeaWorld w = world();
		UUID foreignOrg = foreignOrg(w);
		ApiKey k = externalKeyOf(foreignOrg, "far");
		String bearer = token(k, foreignOrg);
		List<ApiKeyAccess> exchange = rowsOf(foreignOrg, k);
		assertEquals(1, count(k));
		assertEquals(1, exchange.size());

		HttpResponse<String> r = get(foreignBase(foreignOrg) + "/componentRelease/" + w.foreignHead(), bearer);
		assertEquals(200, r.statusCode(), r.body());
		assertServedHeaders(r, true, "the keyed header on its own organization");
		assertEquals(2, count(k), "the exchange's row and the read's");
		List<ApiKeyAccess> latest = rowsOf(foreignOrg, k);
		assertEquals(1, latest.size());
		assertEquals(foreignOrg, latest.get(0).getOrg());
		assertNotEquals(exchange.get(0).getUuid(), latest.get(0).getUuid(), "the newest row is the read's");
		assertNotEquals(exchange.get(0).getIpAddress(), latest.get(0).getIpAddress());
		assertEquals(List.of(), rowsOf(org.getUuid(), k), "nothing in the other organization");
	}

	@Test
	void anAnonymousReadRecordsNothing() throws Exception {
		TeaWorld w = world();
		int before = apiKeyAccessService.listKeyAccessByOrg(org.getUuid()).size();
		HttpResponse<String> r = get(w.base() + "/component/" + w.pub().teaComponent(), null);
		assertEquals(200, r.statusCode(), r.body());
		assertServedHeaders(r, false, "anonymous PUBLIC read");
		assertEquals(before, apiKeyAccessService.listKeyAccessByOrg(org.getUuid()).size());
	}
}
