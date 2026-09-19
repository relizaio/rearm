package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey.ApiTypeEnum;

/** USER key ids parse like FREEFORM ones and both count as RBAC keys; single-object keys do not. */
public class UserKeyIdParseTest {

	private static AuthHeaderParse parse(String keyId) {
		HttpHeaders h = new HttpHeaders();
		h.setBasicAuth(HttpHeaders.encodeBasicAuth(keyId, "secret", StandardCharsets.UTF_8));
		return AuthHeaderParse.parseAuthHeader(h, "127.0.0.1");
	}

	@Test
	void userKeyIdParses() {
		UUID owner = UUID.randomUUID();
		AuthHeaderParse ahp = parse("USER__" + owner + "__ord__" + "k1");
		assertEquals(ApiTypeEnum.USER, ahp.getType());
		assertEquals(owner, ahp.getObjUuid());
		assertEquals("k1", ahp.getKeyOrder());
		assertTrue(ahp.isRbacKey());
	}

	@Test
	void freeformIsRbacButComponentIsNot() {
		UUID org = UUID.randomUUID();
		assertTrue(parse("FREEFORM__" + org + "__ord__" + UUID.randomUUID()).isRbacKey());
		assertFalse(parse("COMPONENT__" + UUID.randomUUID()).isRbacKey());
	}
}
