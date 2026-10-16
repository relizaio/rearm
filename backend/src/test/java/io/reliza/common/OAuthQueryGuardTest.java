/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.common;

import static io.reliza.common.OAuthQueryGuard.REVOKE_REQUEST_PARAMETERS;
import static io.reliza.common.OAuthQueryGuard.TOKEN_REQUEST_PARAMETERS;
import static io.reliza.common.OAuthQueryGuard.namesInQuery;
import static io.reliza.common.OAuthQueryGuard.namesRepeated;
import static io.reliza.common.OAuthQueryGuard.single;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The query-string parser behind the token and revoke endpoints' body-only rule (task TPF-3), and the
 * repeated-parameter check and exact-name read of the token endpoint and the rate limiter (task TPF-26).
 */
public class OAuthQueryGuardTest {

	@Test
	public void nothingOrUnrelatedParametersCarryNothing() {
		assertTrue(namesInQuery(null, TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesInQuery("", TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesInQuery("scope=x&foo=bar", TOKEN_REQUEST_PARAMETERS).isEmpty());
	}

	@Test
	public void namedParametersAreFoundInQueryOrder() {
		assertEquals(List.of("client_id"), namesInQuery("client_id=a", TOKEN_REQUEST_PARAMETERS));
		assertEquals(List.of("grant_type", "client_id", "client_secret"),
				namesInQuery("grant_type=client_credentials&client_id=a&client_secret=b", TOKEN_REQUEST_PARAMETERS));
		assertEquals(List.of("client_secret", "grant_type"),
				namesInQuery("client_secret=b&scope=x&grant_type=client_credentials", TOKEN_REQUEST_PARAMETERS));
		assertEquals(List.of("device_code", "refresh_token", "assertion"),
				namesInQuery("device_code=1&refresh_token=2&assertion=3", TOKEN_REQUEST_PARAMETERS));
	}

	@Test
	public void aBareNameWithoutValueCounts() {
		assertEquals(List.of("client_secret"), namesInQuery("client_secret", TOKEN_REQUEST_PARAMETERS));
		assertEquals(List.of("client_secret"), namesInQuery("client_secret=", TOKEN_REQUEST_PARAMETERS));
	}

	@Test
	public void aPercentEncodedNameIsDecoded() {
		assertEquals(List.of("client_secret"), namesInQuery("client%5Fsecret=s", TOKEN_REQUEST_PARAMETERS));
		assertEquals(List.of("client_id"), namesInQuery("%63lient_id=a", TOKEN_REQUEST_PARAMETERS));
	}

	@Test
	public void matchingIsCaseSensitive() {
		assertTrue(namesInQuery("Client_Id=a", TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesInQuery("CLIENT_SECRET=a", TOKEN_REQUEST_PARAMETERS).isEmpty());
	}

	@Test
	public void valuesAreNeverMatched() {
		assertTrue(namesInQuery("scope=client_secret", TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesInQuery("scope=a%26client_secret%3Db", TOKEN_REQUEST_PARAMETERS).isEmpty());
	}

	@Test
	public void anUndecodableNameCarriesNothingAndThrowsNothing() {
		assertTrue(namesInQuery("%E0%A4%A=x", TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesInQuery("client_secret%=x", TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesInQuery("%ZZ", TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertEquals(List.of("client_id"), namesInQuery("%E0%A4%A=x&client_id=a", TOKEN_REQUEST_PARAMETERS),
				"a bad part does not stop the scan");
	}

	@Test
	public void aRepeatedNameIsListedOnce() {
		assertEquals(List.of("client_id"), namesInQuery("client_id=a&client_id=b", TOKEN_REQUEST_PARAMETERS));
	}

	@Test
	public void revokeChecksOnlyToken() {
		assertEquals(List.of("token"), namesInQuery("token=x", REVOKE_REQUEST_PARAMETERS));
		assertTrue(namesInQuery("token=x", TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesInQuery("client_secret=x&refresh_token=y", REVOKE_REQUEST_PARAMETERS).isEmpty());
	}

	// TPF-26 4.3 row 6: namesRepeated
	@Test
	public void namesRepeatedListsTheKnownNamesSentMoreThanOnceSorted() {
		assertTrue(namesRepeated(Map.of(), TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesRepeated(null, TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertTrue(namesRepeated(Map.of("grant_type", new String[] {"client_credentials"}, "client_id", new String[] {"a"},
				"client_secret", new String[] {"b"}, "device_code", new String[] {"c"}, "refresh_token", new String[] {"d"},
				"assertion", new String[] {"e"}), TOKEN_REQUEST_PARAMETERS).isEmpty());
		assertEquals(List.of("client_id"), namesRepeated(Map.of("client_id", new String[] {"a", "b"}), TOKEN_REQUEST_PARAMETERS));
		assertEquals(List.of("client_id", "client_secret"), namesRepeated(Map.of("client_secret", new String[] {"a", "b", "c"},
				"client_id", new String[] {"a", "b"}), TOKEN_REQUEST_PARAMETERS));
		assertTrue(namesRepeated(Map.of("scope", new String[] {"a", "b"}), TOKEN_REQUEST_PARAMETERS).isEmpty(), "a name outside the set");
		assertTrue(namesRepeated(Map.of("client_id", new String[] {"a,b"}), TOKEN_REQUEST_PARAMETERS).isEmpty(), "a comma in one value is no repeat");
	}

	// TPF-26 4.3 row 9: single
	@Test
	public void singleReadsTheFirstValueUnderTheExactNameOnly() {
		assertNull(single(Map.of(), "client_id"));
		assertNull(single(null, "client_id"));
		assertNull(single(Map.of("grant_type", new String[] {"x"}), "client_id"));
		assertNull(single(Map.of("client_id", new String[] {}), "client_id"));
		assertEquals("a", single(Map.of("client_id", new String[] {"a"}), "client_id"));
		assertEquals("a", single(Map.of("client_id", new String[] {"a", "b"}), "client_id"));
		assertNull(single(Map.of("client_id[]", new String[] {"a"}), "client_id"), "exact names only");
	}
}
