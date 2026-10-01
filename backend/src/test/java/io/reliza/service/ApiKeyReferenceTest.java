/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ApiKey;
import io.reliza.repositories.ApiKeyRepository;

/**
 * Naming a key to revoke it.
 *
 * <p>deleteApiKey only took the key's uuid. Someone scripting a cleanup has the key id it was issued
 * with, whose trailing uuid is the key order -- passing that, or the whole id, got an opaque
 * SERVICE_ERROR, so an org key could not be revoked through the API.
 */
class ApiKeyReferenceTest {

	private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
	private final ApiKeyService service = new ApiKeyService(repository);
	private final UUID org = UUID.randomUUID();

	private ApiKey key() {
		return mock(ApiKey.class);
	}

	@Test
	void theKeysOwnUuidNamesIt() {
		UUID uuid = UUID.randomUUID();
		ApiKey ak = key();
		when(repository.findByUUID(uuid)).thenReturn(Optional.of(ak));
		assertEquals(Optional.of(ak), service.resolveApiKeyReference(uuid.toString()));
	}

	@Test
	void theKeyIdItWasIssuedWithNamesIt() {
		String order = UUID.randomUUID().toString();
		ApiKey ak = key();
		when(repository.findApiKeyByUuidAndTypeOnly(org, "FREEFORM", order)).thenReturn(Optional.of(ak));
		assertEquals(Optional.of(ak), service.resolveApiKeyReference("FREEFORM__" + org + "__ord__" + order));
	}

	@Test
	void aKeyIdWithoutAnOrderNamesTheObjectsKey() {
		UUID component = UUID.randomUUID();
		ApiKey ak = key();
		when(repository.findApiKeyByUuidAndTypeWithoutOrder(component, "COMPONENT")).thenReturn(Optional.of(ak));
		assertEquals(Optional.of(ak), service.resolveApiKeyReference(" COMPONENT__" + component + " "));
	}

	@Test
	void anythingElseNamesNothing() {
		when(repository.findByUUID(any())).thenReturn(Optional.empty());
		assertTrue(service.resolveApiKeyReference(UUID.randomUUID().toString()).isEmpty(), "unknown uuid");
		assertTrue(service.resolveApiKeyReference("").isEmpty());
		assertTrue(service.resolveApiKeyReference("NOT_A_TYPE__" + org).isEmpty());
		assertTrue(service.resolveApiKeyReference("FREEFORM__not-a-uuid").isEmpty());
		assertTrue(service.resolveApiKeyReference("FREEFORM__" + org + "__x__y").isEmpty());
		assertTrue(service.resolveApiKeyReference("FREEFORM__" + org + "__ord__").isEmpty(), "empty order");
	}

	@Test
	void aKeyOrderMayItselfContainTheSeparator() {
		// A federated key's order is provider:owner/repo, and a repo name may contain "__".
		ApiKey ak = key();
		when(repository.findApiKeyByUuidAndTypeOnly(org, "FEDERATED", "github:owner/a__b")).thenReturn(Optional.of(ak));
		assertEquals(Optional.of(ak), service.resolveApiKeyReference("FEDERATED__" + org + "__ord__github:owner/a__b"));
	}

	@Test
	void anIdWithoutAnOrderNeverNamesAnOrderedKey() {
		// ORGANIZATION__<org> was never issued for an ordered key; it must not revoke the org's key.
		when(repository.findApiKeyByUuidAndTypeWithoutOrder(org, "ORGANIZATION")).thenReturn(Optional.empty());
		assertTrue(service.resolveApiKeyReference("ORGANIZATION__" + org).isEmpty());
		verify(repository, never()).findApiKeyByUuidAndTypeOnly(any(), any(), any());
	}
}
