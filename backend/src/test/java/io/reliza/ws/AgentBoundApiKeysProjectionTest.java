/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.Utils;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.ApiKeyService;

/**
 * Agent.boundApiKeys hangs off READ-gated agent queries, so it must hand
 * out only the key reference (uuid, org, type, declared name) and never
 * the full key record: notes, permissions, keyOrder, holder and secret
 * metadata stay null (task t20261013-072815-22413).
 */
class AgentBoundApiKeysProjectionTest {

	private AgentIdentityService agentIdentityService;
	private ApiKeyService apiKeyService;
	private AgentDataFetcher fetcher;

	private final UUID org = UUID.randomUUID();
	private final UUID identity = UUID.randomUUID();

	@BeforeEach
	void wireMocks() throws Exception {
		agentIdentityService = mock(AgentIdentityService.class);
		apiKeyService = mock(ApiKeyService.class);
		fetcher = new AgentDataFetcher();
		inject("agentIdentityService", agentIdentityService);
		inject("apiKeyService", apiKeyService);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = AgentDataFetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	private DgsDataFetchingEnvironment envFor(AgentData ad) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(ad);
		return dfe;
	}

	private AgentData boundAgent() {
		AgentData ad = new AgentData();
		ad.setOrg(org);
		ad.setAgentIdentity(identity);
		return ad;
	}

	private AgentIdentityCredential credential(String value) {
		AgentIdentityCredential cred = new AgentIdentityCredential();
		cred.setAgentIdentityUuid(identity);
		cred.setIdentityType(IdentityType.REARM_API_KEY.name());
		cred.setIdentityValue(value);
		return cred;
	}

	/** A FREEFORM key whose record carries everything a reference must not leak. */
	private ApiKey fullKey() {
		ApiKey ak = new ApiKey();
		ak.setOrg(org);
		ak.setObjectUuid(org);
		ak.setObjectType(ApiTypeEnum.FREEFORM);
		ak.setKeyOrder(UUID.randomUUID().toString());
		ak.setLastAccessDate(ZonedDateTime.now());
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(org);
		akd.setNotes("ci pipeline deploy key");
		akd.setHolder(UUID.randomUUID());
		akd.setDeclaredName("ci-pipeline");
		akd.setPermission(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, List.of());
		akd.setSecrets(List.of(new ApiKeySecret(1, "hash", true, ZonedDateTime.now(), null)));
		ak.setRecordData(Utils.dataToRecord(akd));
		return ak;
	}

	@Test
	void boundKeyIsReturnedAsReferenceOnly() {
		ApiKey ak = fullKey();
		when(agentIdentityService.listCredentials(identity))
				.thenReturn(List.of(credential(ak.getUuid().toString())));
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));

		List<ApiKeyDto> keys = fetcher.agentBoundApiKeys(envFor(boundAgent()));

		assertEquals(1, keys.size());
		ApiKeyDto dto = keys.get(0);
		assertNull(dto.getNotes());
		assertNull(dto.getPermissions());
		assertNull(dto.getHolder());
		assertNull(dto.getSecrets());
		assertNull(dto.getAdminDisabled());
		// every field beyond the four is null: equality with a DTO built from those four alone
		ApiKeyDto expected = ApiKeyDto.builder()
				.uuid(ak.getUuid())
				.org(org)
				.type(ApiTypeEnum.FREEFORM)
				.declaredName("ci-pipeline")
				.build();
		assertEquals(expected, dto);
		verify(apiKeyService, never()).getApiKeyDto(any());
	}

	@Test
	void keyOfAnotherOrgIsSkipped() {
		ApiKey ak = fullKey();
		ak.setOrg(UUID.randomUUID());
		when(agentIdentityService.listCredentials(identity))
				.thenReturn(List.of(credential(ak.getUuid().toString())));
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));

		assertTrue(fetcher.agentBoundApiKeys(envFor(boundAgent())).isEmpty());
	}

	@Test
	void fullRecordStillCarriesWhatTheReferenceDrops() {
		// guards the test fixture: the nulls above are the projection's doing
		ApiKeyDto full = ApiKeyDto.fromApiKey(fullKey());
		assertNotNull(full.getNotes());
		assertNotNull(full.getPermissions());
		assertNotNull(full.getKeyOrder());
		assertNotNull(full.getHolder());
		assertNotNull(full.getSecrets());
		assertNotNull(full.getAdminDisabled());
		assertNotNull(full.getCreatedDate());
		assertNotNull(full.getAccessDate());
	}

	@Test
	void nonUuidCredentialIsSkipped() {
		when(agentIdentityService.listCredentials(identity))
				.thenReturn(List.of(credential("not-a-uuid")));

		List<ApiKeyDto> keys = fetcher.agentBoundApiKeys(envFor(boundAgent()));

		assertTrue(keys.isEmpty());
		verify(apiKeyService, never()).getApiKey(any());
		verify(apiKeyService, never()).getApiKeyDto(any());
	}
}
