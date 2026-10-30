/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.service.ApiTokenService.TokenClaims;

/** Task TEA-3, design 4.1: the EXTERNAL key in ApiKeyService -- the printed id, the row, the live-secret test. */
class ApiKeyServiceExternalTest {

	private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
	private final ApiKeyService service = new ApiKeyService(repository);
	private final ExternalApiKeyService external = mock(ExternalApiKeyService.class);
	private final UUID org = UUID.randomUUID();
	private final UUID teaOrg = UUID.randomUUID();
	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	ApiKeyServiceExternalTest() {
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "getComponentService", mock(GetComponentService.class));
		ReflectionTestUtils.setField(service, "externalApiKeyService", external);
		// main's saveApiKey reads the row's liveness under its lock, and the persistence context for a new row
		ReflectionTestUtils.setField(service, "entityManager", mock(jakarta.persistence.EntityManager.class));
		when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
		when(repository.findApiKeyIncludingRevokedByUuidAndType(any(), anyString(), any())).thenReturn(List.of());
	}

	private ApiKey row(ApiTypeEnum type, UUID object, String order, ApiKeyData akd) {
		ApiKey ak = new ApiKey();
		ak.setObjectType(type);
		ak.setObjectUuid(object);
		ak.setOrg(org);
		ak.setKeyOrder(order);
		if (akd != null) ak.setRecordData(Utils.dataToRecord(akd));
		return ak;
	}

	private ApiKeyData externalData() {
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(org);
		akd.setTeaOrg(teaOrg);
		akd.setHolderName("Acme Customer");
		return akd;
	}

	@Test
	void theExternalIdCarriesTheTeaOrgIdAndTheOthersAreUnchanged() {
		String order = UUID.randomUUID().toString();
		assertEquals("EXTERNAL__" + teaOrg + "__ord__" + order, ApiKeyService.keyIdOf(row(ApiTypeEnum.EXTERNAL, org, order, externalData())));
		assertEquals("FREEFORM__" + org + "__ord__" + order, ApiKeyService.keyIdOf(row(ApiTypeEnum.FREEFORM, org, order, null)));
		UUID user = UUID.randomUUID();
		assertEquals("USER__" + user + "__ord__" + order, ApiKeyService.keyIdOf(row(ApiTypeEnum.USER, user, order, null)));
		assertEquals("ORGANIZATION__" + org, ApiKeyService.keyIdOf(row(ApiTypeEnum.ORGANIZATION, org, null, null)));
		UUID component = UUID.randomUUID();
		assertEquals("COMPONENT__" + component, ApiKeyService.keyIdOf(row(ApiTypeEnum.COMPONENT, component, null, null)));
	}

	@Test
	void theExternalRowIsCreatedWithoutASecretOrAGrant() {
		ApiKey ak = service.createExternalKeyRow(org, teaOrg, "Acme Customer", "for the audit", WU);
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		assertEquals(ApiTypeEnum.EXTERNAL, ak.getObjectType());
		assertEquals(org, ak.getObjectUuid());
		assertEquals(org, ak.getOrg(), "the org switch of ensureObjectApiKeyRow resolves the org for EXTERNAL");
		UUID.fromString(ak.getKeyOrder());
		assertEquals("Acme Customer", akd.getHolderName());
		assertEquals(teaOrg, akd.getTeaOrg());
		assertEquals("for the audit", akd.getNotes());
		assertEquals(ApiKeyStatus.ACTIVE, akd.getStatus());
		assertTrue(akd.getSecrets().isEmpty());
		assertNull(ak.getApiKey());
		assertNull(akd.getHolder());
		assertTrue(akd.getPermissions(org).getOrgPermissionsAsSet(org).isEmpty());
		ApiKey second = service.createExternalKeyRow(org, teaOrg, "Acme Customer", null, WU);
		assertNotEquals(ak.getKeyOrder(), second.getKeyOrder(), "a second call makes a second row");
		assertNotEquals(ak.getUuid(), second.getUuid());
	}

	private TokenClaims claimsFor(ApiKeySecret sec) {
		return new TokenClaims(UUID.randomUUID(), org, ApiTokenService.AUD_TEA, ApiTokenService.fingerprint(sec), sec.getSlot(), "j", null);
	}

	@Test
	void aTokenSecretIsLiveOnlyWhileItIsUsableAndUnchanged() {
		ApiKeySecret sec = new ApiKeySecret(1, "hash-1", true, ZonedDateTime.now(), null);
		ApiKeyData akd = externalData();
		akd.setSecrets(new LinkedList<>(List.of(sec)));
		ApiKey ak = row(ApiTypeEnum.EXTERNAL, org, "o", akd);
		TokenClaims claims = claimsFor(sec);
		assertTrue(ApiKeyService.isTokenSecretLive(ak, akd, claims));

		akd.setSecrets(new LinkedList<>(List.of(new ApiKeySecret(1, "hash-regenerated", true, ZonedDateTime.now(), null))));
		assertFalse(ApiKeyService.isTokenSecretLive(ak, akd, claims), "regenerated");
		akd.setSecrets(new LinkedList<>(List.of(new ApiKeySecret(1, "hash-1", false, ZonedDateTime.now(), null))));
		assertFalse(ApiKeyService.isTokenSecretLive(ak, akd, claims), "retired");
		akd.setSecrets(new LinkedList<>(List.of(new ApiKeySecret(1, "hash-1", true, ZonedDateTime.now(), null, ZonedDateTime.now().minusMinutes(1)))));
		assertFalse(ApiKeyService.isTokenSecretLive(ak, akd, claims), "expired");
		akd.setSecrets(new LinkedList<>());
		assertFalse(ApiKeyService.isTokenSecretLive(ak, akd, claims), "deleted");
	}

	@Test
	void anExternalReferenceIsResolvedThroughTheExternalService() {
		String id = "EXTERNAL__" + teaOrg + "__ord__" + UUID.randomUUID();
		ApiKey ak = row(ApiTypeEnum.EXTERNAL, org, "o", externalData());
		when(external.findByPrintedId(id)).thenReturn(Optional.of(ak));
		assertEquals(Optional.of(ak), service.resolveApiKeyReference(id));
		verify(repository, never()).findApiKeyByUuidAndTypeOnly(any(), eq("EXTERNAL"), any());
		UUID uuid = UUID.randomUUID();
		when(repository.findByUUID(uuid)).thenReturn(Optional.of(ak));
		assertEquals(Optional.of(ak), service.resolveApiKeyReference(uuid.toString()), "a uuid still resolves by uuid");
	}

	@Test
	void anExternalKeyTakesNoUserHolder() {
		ApiKey ak = row(ApiTypeEnum.EXTERNAL, org, "o", externalData());
		when(repository.findByUUID(ak.getUuid())).thenReturn(Optional.of(ak));
		RelizaException e = assertThrows(RelizaException.class, () -> service.setApiKeyHolder(ak.getUuid(), UUID.randomUUID(), WU));
		assertEquals("an external key is held by an outside party; set its name", e.getMessage());
		verify(repository, never()).save(any());
	}
}
