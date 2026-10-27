/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.saas.ApiKeyOrgService;

/**
 * Task TEA-22, design section 5: an organization is resolved by its TEA id only while it is not ARCHIVED, in
 * the one query FIND_ORGANIZATION_BY_TEA_UUID, so the TEA surface, the token endpoint and the external key id
 * answer an archived organization exactly as an id never minted. The rig's own organization is never archived;
 * every case archives a fresh second organization.
 */
public class TeaArchivedOrganizationIntegrationTest extends TeaSurfaceIntegrationTestBase {

	private static final String IP = "203.0.113.9";

	@Autowired OrganizationService organizationService;
	@Autowired OrganizationRepository organizationRepository;
	@Autowired ApiKeyOrgService apiKeyOrgService;
	@Autowired JdbcTemplate jdbcTemplate;

	/** The second organization of a case, its TEA id and one EXTERNAL key with a secret. */
	private record Arch(UUID o2, UUID tea2, ApiKey k2, String secret2, String keyId2) {
		String base() {
			return "/tea/" + tea2 + "/v1.0.0";
		}
	}

	/** The rig organization's TEA id, minted on first use. */
	private UUID teaBase() throws Exception {
		return organizationService.ensureTeaUuid(org.getUuid(), WU);
	}

	private Arch arch() throws Exception {
		UUID o2 = testInitializer.obtainOrganization().getUuid();
		UUID tea2 = organizationService.ensureTeaUuid(o2, WU);
		ApiKey k2 = externalApiKeyService.create(o2, "arch-" + tag(), null, WU);
		String secret2 = apiKeyService.addApiKeySecret(k2.getUuid(), null, WU);
		String keyId2 = ApiKeyService.keyIdOf(apiKeyService.getApiKey(k2.getUuid()).orElseThrow());
		return new Arch(o2, tea2, k2, secret2, keyId2);
	}

	/** The token endpoint POST of token(ApiKey), returned as is so a refusal can be asserted. */
	private HttpResponse<String> exchange(UUID teaOrg, String keyId, String secret) throws Exception {
		String basic = "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8));
		return http.send(HttpRequest.newBuilder(URI.create(root() + "/tea/" + teaOrg + "/v1.0.0/token"))
				.header("Authorization", basic).header("X-Forwarded-For", randomIp())
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(), HttpResponse.BodyHandlers.ofString());
	}

	private long accessRows(UUID key) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.api_key_access WHERE api_key_uuid = ?", Long.class, key);
	}

	private static List<Object> answer(HttpResponse<String> r) {
		return List.of(r.statusCode(), r.body(), r.headers().firstValue("Cache-Control").orElse(""));
	}

	private OrganizationData orgRecord(UUID o) {
		return OrganizationData.orgDataFromDbRecord(organizationRepository.findById(o).orElseThrow());
	}

	/** The token endpoint's unknown-base answer: 404 OBJECT_UNKNOWN, no-store, json, nothing of invalid_client. */
	private static void assertTokenObjectUnknown(HttpResponse<String> r, String what) {
		assertEquals(404, r.statusCode(), what + ": " + r.body());
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null), what);
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), what);
		assertEquals("OBJECT_UNKNOWN", json(r).get("error"), what);
		assertFalse(json(r).containsKey("error_description"), what + ": not invalid_client");
		assertTrue(r.headers().firstValue("WWW-Authenticate").isEmpty(), what + ": no challenge");
	}

	// 1
	@Test
	void theQueryHidesAnArchivedOrganizationAndKeepsItsTeaId() throws Exception {
		UUID rigTea = teaBase();
		Arch a = arch();
		Optional<Organization> before = organizationRepository.findByTeaUuid(a.tea2().toString());
		assertTrue(before.isPresent());
		assertEquals(a.o2(), before.get().getUuid());

		assertTrue(apiKeyOrgService.archiveOrganization(a.o2(), WU));

		assertTrue(organizationRepository.findByTeaUuid(a.tea2().toString()).isEmpty(), "an archived organization reads as absent");
		OrganizationData od = orgRecord(a.o2());
		assertEquals(a.tea2(), od.getTeaUuid(), "the TEA id is hidden, not cleared");
		assertEquals(StatusEnum.ARCHIVED, od.getStatus());
		assertEquals(Optional.of(org.getUuid()), organizationRepository.findByTeaUuid(rigTea.toString()).map(Organization::getUuid),
				"a live organization is not affected");
	}

	// 2
	@Test
	void aRowWithNoStatusKeyReadsLive() throws Exception {
		UUID o3 = testInitializer.obtainOrganization().getUuid();
		UUID tea3 = organizationService.ensureTeaUuid(o3, WU);
		jdbcTemplate.update("UPDATE rearm.organizations SET record_data = record_data - 'status' WHERE uuid = ?", o3);
		assertEquals(null, orgRecord(o3).getStatus(), "the row carries no status");
		assertEquals(Optional.of(o3), organizationRepository.findByTeaUuid(tea3.toString()).map(Organization::getUuid));
	}

	// 3
	@Test
	void theSurfaceAnswersAnArchivedBaseAsAnUnknownOne() throws Exception {
		Arch a = arch();
		String rel = root() + a.base() + "/componentReleases";
		HttpResponse<String> live = get(rel, null);
		assertEquals(200, live.statusCode(), live.body());

		assertTrue(apiKeyOrgService.archiveOrganization(a.o2(), WU));

		HttpResponse<String> archived = get(rel, null);
		assertObjectUnknown(archived, "archived base");
		HttpResponse<String> unknown = get(root() + "/tea/" + UUID.randomUUID() + "/v1.0.0/componentReleases", null);
		assertEquals(answer(unknown), answer(archived), "archived equals unknown");
	}

	// 4
	@Test
	void theTokenEndpointAnswersAnArchivedBaseAsAnUnknownOne() throws Exception {
		Arch a = arch();
		HttpResponse<String> ok = exchange(a.tea2(), a.keyId2(), a.secret2());
		assertEquals(200, ok.statusCode(), ok.body());
		assertEquals(1L, accessRows(a.k2().getUuid()));

		assertTrue(apiKeyOrgService.archiveOrganization(a.o2(), WU));

		HttpResponse<String> archived = exchange(a.tea2(), a.keyId2(), a.secret2());
		assertTokenObjectUnknown(archived, "archived token endpoint");
		HttpResponse<String> unknown = exchange(UUID.randomUUID(), a.keyId2(), a.secret2());
		assertEquals(answer(unknown), answer(archived), "archived equals unknown");
		assertEquals(1L, accessRows(a.k2().getUuid()), "the refused exchange records no access");
	}

	// 5: also holds without the change (the archive sweep revokes k2); row 8 proves the lookup change
	@Test
	void theKeyLookupsAnswerEmptyAfterTheArchive() throws Exception {
		Arch a = arch();
		assertTrue(externalApiKeyService.findByPrintedId(a.keyId2()).isPresent());
		assertTrue(externalApiKeyService.authenticate(a.keyId2(), a.secret2(), a.o2(), IP).isPresent());
		assertTrue(apiKeyService.resolveApiKeyReference(a.keyId2()).isPresent());

		assertTrue(apiKeyOrgService.archiveOrganization(a.o2(), WU));

		assertTrue(externalApiKeyService.findByPrintedId(a.keyId2()).isEmpty());
		assertTrue(externalApiKeyService.authenticate(a.keyId2(), a.secret2(), a.o2(), IP).isEmpty());
		assertTrue(apiKeyService.resolveApiKeyReference(a.keyId2()).isEmpty());
	}

	// 6
	@Test
	void aTokenIssuedBeforeTheArchiveIsRefusedEverywhere() throws Exception {
		UUID rigTea = teaBase();
		Arch a = arch();
		String t2 = token(a.k2(), a.o2());

		assertTrue(apiKeyOrgService.archiveOrganization(a.o2(), WU));

		assertObjectUnknown(get(root() + a.base() + "/componentReleases", t2), "the archived base is unknown first");
		assertNoStore401(get(root() + "/tea/" + rigTea + "/v1.0.0/componentReleases", t2), true, "a live base refuses the token");
		assertTrue(apiKeyService.getApiKey(a.k2().getUuid()).isEmpty(), "the key row is not live");
	}

	// 8: a key still live on an archived organization (no production path makes one; written by hand)
	@Test
	void aKeyLeftLiveOnAnArchivedOrganizationIsNeverResolved() throws Exception {
		Arch a = arch();
		assertEquals(Optional.of(a.k2().getUuid()), externalApiKeyService.findByPrintedId(a.keyId2()).map(ApiKey::getUuid));
		assertTrue(externalApiKeyService.authenticate(a.keyId2(), a.secret2(), a.o2(), IP).isPresent());
		assertTrue(apiKeyService.resolveApiKeyReference(a.keyId2()).isPresent());
		HttpResponse<String> ok = exchange(a.tea2(), a.keyId2(), a.secret2());
		assertEquals(200, ok.statusCode(), ok.body());
		assertEquals(1L, accessRows(a.k2().getUuid()));

		jdbcTemplate.update("UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{status}', '\"ARCHIVED\"') WHERE uuid = ?",
				a.o2());

		ApiKey live = apiKeyService.getApiKey(a.k2().getUuid()).orElseThrow();
		assertEquals(ApiKeyStatus.ACTIVE, ApiKeyData.dataFromRecord(live).getStatus(), "the key is still live");
		assertEquals(StatusEnum.ARCHIVED, orgRecord(a.o2()).getStatus(), "on an archived organization");
		assertTrue(organizationRepository.findByTeaUuid(a.tea2().toString()).isEmpty(), "the query rule alone hides it");
		assertTrue(externalApiKeyService.findByPrintedId(a.keyId2()).isEmpty());
		assertTrue(externalApiKeyService.authenticate(a.keyId2(), a.secret2(), a.o2(), IP).isEmpty());
		assertTrue(apiKeyService.resolveApiKeyReference(a.keyId2()).isEmpty());
		HttpResponse<String> archived = exchange(a.tea2(), a.keyId2(), a.secret2());
		assertTokenObjectUnknown(archived, "archived token endpoint, live key");
		HttpResponse<String> unknown = exchange(UUID.randomUUID(), a.keyId2(), a.secret2());
		assertEquals(answer(unknown), answer(archived), "archived equals unknown");
		assertEquals(1L, accessRows(a.k2().getUuid()), "the refused exchange records no access");
	}
}
