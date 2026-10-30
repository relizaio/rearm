/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey.ApiTypeEnum;

/** Task TEA-3, design 4.3: the Basic boundary -- no Basic credential path ever sees an EXTERNAL key. */
class AuthHeaderParseExternalTest {

	private static HttpHeaders basic(String id, String secret) {
		HttpHeaders h = new HttpHeaders();
		h.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		return h;
	}

	@Test
	void anExternalIdParsesToTheEmptyPrincipal() {
		String order = UUID.randomUUID().toString();
		AuthHeaderParse ahp = AuthHeaderParse.parseAuthHeader(basic("EXTERNAL__" + UUID.randomUUID() + "__ord__" + order, "secret"), "10.0.0.1");
		assertNull(ahp.getType());
		assertNull(ahp.getApiKey());
		assertNull(ahp.getApiKeyId());
		assertNull(ahp.getObjUuid());
	}

	@Test
	void aFreeformIdStillParses() {
		UUID org = UUID.randomUUID();
		String order = UUID.randomUUID().toString();
		AuthHeaderParse ahp = AuthHeaderParse.parseAuthHeader(basic("FREEFORM__" + org + "__ord__" + order, "secret"), "10.0.0.1");
		assertEquals(ApiTypeEnum.FREEFORM, ahp.getType());
		assertEquals(org, ahp.getObjUuid());
		assertEquals(order, ahp.getKeyOrder());
		assertEquals("secret", ahp.getApiKey());
	}
}
