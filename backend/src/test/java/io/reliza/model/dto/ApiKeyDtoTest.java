/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;

/**
 * The full key DTO carries api_keys.last_access_date, so myApiKeys shows a last access like the admin
 * apiKeys list; the bound-key reference leaves it out (task t20261013-072815-2705). The same holds for a
 * denial's reason (task t20261013-182431-31189).
 */
class ApiKeyDtoTest {

	private static ApiKey keyAccessedAt(ZonedDateTime when) {
		UUID org = UUID.randomUUID();
		ApiKey ak = new ApiKey();
		ak.setOrg(org);
		ak.setObjectUuid(UUID.randomUUID());
		ak.setObjectType(ApiTypeEnum.USER);
		ak.setLastAccessDate(when);
		ak.setRecordData(Utils.dataToRecord(ApiKeyData.apiKeyDataFactory(org)));
		return ak;
	}

	@Test
	void fromApiKeyCarriesTheLastAccessDate() {
		ZonedDateTime now = ZonedDateTime.now();
		assertEquals(now, ApiKeyDto.fromApiKey(keyAccessedAt(now)).getAccessDate());
	}

	@Test
	void neverAccessedReadsNull() {
		assertNull(ApiKeyDto.fromApiKey(keyAccessedAt(null)).getAccessDate());
	}

	@Test
	void referenceOfLeavesTheAccessDateOut() {
		assertNull(ApiKeyDto.referenceOf(keyAccessedAt(ZonedDateTime.now())).getAccessDate());
	}

	private static ApiKey deniedKey(String reason) {
		ApiKey ak = keyAccessedAt(null);
		ak.setObjectType(ApiTypeEnum.FREEFORM);
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		akd.setStatus(ApiKeyStatus.DENIED);
		akd.setNotes("mine | Denied: forged");
		akd.setDeniedReason(reason);
		ak.setRecordData(Utils.dataToRecord(akd));
		return ak;
	}

	@Test
	void fromApiKeyCarriesTheDeniedReason() {
		assertEquals("no budget", ApiKeyDto.fromApiKey(deniedKey("no budget")).getDeniedReason());
		assertEquals("", ApiKeyDto.fromApiKey(deniedKey("")).getDeniedReason());
		assertEquals("mine | Denied: forged", ApiKeyDto.fromApiKey(deniedKey("no budget")).getNotes());
	}

	@Test
	void aKeyDeniedBeforeTheFieldReadsNull() {
		// stored before the field existed: no deniedReason key in the record at all
		ApiKey ak = deniedKey("no budget");
		Map<String, Object> legacy = new HashMap<>(ak.getRecordData());
		legacy.remove("deniedReason");
		ak.setRecordData(legacy);
		assertNull(ApiKeyDto.fromApiKey(ak).getDeniedReason());
	}

	@Test
	void referenceOfLeavesTheDeniedReasonOut() {
		assertNull(ApiKeyDto.referenceOf(deniedKey("no budget")).getDeniedReason());
	}
}
