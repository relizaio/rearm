/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;

/**
 * TPF-24 (architecture-6 5.3, 6.5 item 16): what a refused key check logs is the parse's canonical identity plus the
 * row uuid when a row was found, never the presented id text. Plain JUnit over the package-private helper, no Spring
 * and no captured log output.
 */
public class ApiKeyServiceRefusalIdentityTest {

	private static AuthHeaderParse parse(String credentials) {
		HttpHeaders h = new HttpHeaders();
		h.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
		return AuthHeaderParse.parseAuthHeader(h, "198.51.100.31");
	}

	@Test
	public void aLineBreakOrderRendersAsQuestionMarkWithNoRow() {
		UUID u = UUID.randomUUID();
		String rendered = ApiKeyService.refusedIdentity(parse("FREEFORM__" + u + "__ord__abc\nFORGED LINE:x"), Optional.empty());
		assertEquals("FREEFORM__" + u + "__ord__? no row", rendered);
		assertFalse(rendered.contains("FORGED LINE"), rendered);
		assertFalse(rendered.contains("\n"), rendered);
	}

	@Test
	public void aWrongSecretOnARealKeyNamesTheRow() {
		UUID u = UUID.randomUUID();
		String order = UUID.randomUUID().toString();
		ApiKey row = new ApiKey();
		row.setUuid(UUID.randomUUID());
		assertEquals("FREEFORM__" + u + "__ord__" + order + " row " + row.getUuid(),
				ApiKeyService.refusedIdentity(parse("FREEFORM__" + u + "__ord__" + order + ":x"), Optional.of(row)));
	}

	@Test
	public void anUpperCaseObjectRendersCanonicalWithNoOrder() {
		UUID u = UUID.randomUUID();
		assertEquals("ORGANIZATION_RW__" + u + " no row",
				ApiKeyService.refusedIdentity(parse("ORGANIZATION_RW__" + u.toString().toUpperCase(Locale.ROOT) + ":x"), Optional.empty()));
	}

	@Test
	public void aMalformedIdRendersNoIdentity() {
		UUID u = UUID.randomUUID();
		for (String m : List.of("nocolon", ":x", "FOO__" + u + ":x", "FREEFORM__notauuid__ord__1:x")) {
			assertEquals(" no row", ApiKeyService.refusedIdentity(parse(m), Optional.empty()), m);
		}
		assertEquals(" no row", ApiKeyService.refusedIdentity(null, Optional.empty()));
	}
}
