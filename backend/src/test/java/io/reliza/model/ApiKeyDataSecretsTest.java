package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;

/** Legacy rows and the two-secret model: what the matcher and the token filter see. */
public class ApiKeyDataSecretsTest {

	@Test
	void legacyRowMapsItsColumnToSlotOne() {
		ApiKeyData akd = new ApiKeyData();
		List<ApiKeySecret> eff = akd.effectiveSecrets("argon2-legacy");
		assertEquals(1, eff.size());
		assertEquals(1, eff.get(0).getSlot());
		assertEquals("argon2-legacy", eff.get(0).getHash());
		assertTrue(eff.get(0).isActive());
		assertEquals(ApiKeyStatus.ACTIVE, akd.getStatus(), "rows without a status are active");
	}

	@Test
	void revokedLegacyRowHasNoSecrets() {
		assertTrue(new ApiKeyData().effectiveSecrets(null).isEmpty());
	}

	@Test
	void keyCreatedWithoutSecretIsActiveButUnusable() {
		// a fresh key id: no column hash, no secrets, status ACTIVE (visible, refuses every credential)
		ApiKeyData akd = new ApiKeyData();
		akd.setStatus(ApiKeyStatus.ACTIVE);
		assertTrue(akd.effectiveSecrets(null).isEmpty());
		assertEquals(ApiKeyStatus.ACTIVE, akd.getStatus());
		// a delete tombstone is told apart by status, not by the empty column
		akd.setStatus(ApiKeyStatus.REVOKED);
		assertEquals(ApiKeyStatus.REVOKED, akd.getStatus());
	}

	@Test
	void expiredSecretIsNotUsable() {
		ApiKeySecret live = new ApiKeySecret(1, "h", true, null, null, ZonedDateTime.now().plusHours(1));
		ApiKeySecret gone = new ApiKeySecret(2, "h", true, null, null, ZonedDateTime.now().minusSeconds(1));
		ApiKeySecret retired = new ApiKeySecret(1, "h", false, null, null, null);
		assertTrue(live.isUsable());
		assertFalse(gone.isUsable(), "past its expiry a secret authenticates nothing");
		assertFalse(retired.isUsable());
		assertFalse(new ApiKeySecret(1, "h", true, null, null).isExpired(), "no expiry means never expires");
	}

	@Test
	void materialisedListWinsOverTheColumn() {
		ApiKeyData akd = new ApiKeyData();
		akd.setSecrets(List.of(new ApiKeySecret(1, "h1", false, null, null), new ApiKeySecret(2, "h2", true, null, null)));
		List<ApiKeySecret> eff = akd.effectiveSecrets("stale-column");
		assertEquals(2, eff.size());
		assertEquals("h2", akd.secret(2).orElseThrow().getHash());
		assertTrue(akd.secret(3).isEmpty());
	}
}
