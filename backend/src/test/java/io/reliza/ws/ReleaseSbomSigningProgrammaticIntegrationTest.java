/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.Utils;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.service.JsfVerifier;
import io.reliza.service.JsfVerifier.Outcome;
import io.reliza.service.SbomSigningKeyService;

/**
 * Signing at egress on the programmatic endpoint, as the CLI calls it (SCORE-33 T-15):
 * {@code releaseSbomExportProgrammatic} and {@code releaseSbomScoreProgrammatic} serve and score the
 * document signed with the organization's CURRENT key; CSV stays unsigned.
 */
class ReleaseSbomSigningProgrammaticIntegrationTest extends ProgrammaticReleaseSbomTestBase {

	@Autowired private SbomSigningKeyService sbomSigningKeyService;

	private SbomSigningKeyData key;

	@BeforeEach
	void orgHasAKey() throws Exception {
		key = sbomSigningKeyService.generate(w.org().getUuid(), WU);
	}

	@AfterEach
	void revokeIt() throws Exception {
		sbomSigningKeyService.revoke(key.getUuid(), WU);
	}

	@Test
	void releaseSbomExportProgrammaticAndScoreProgrammaticCarryTheSignature() throws Exception {
		SbomSigningKeyData current = sbomSigningKeyService.currentKey(w.org().getUuid()).orElseThrow();
		assertEquals(key.getKeyId(), current.getKeyId());

		String exported = value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered())), "releaseSbomExportProgrammatic");
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(exported, JsfVerifier.keyFromJwk(current.getPublicKeyJwk())));
		assertEquals(current.getKeyId(), Utils.OM.readTree(exported).get("signature").get("keyId").asString());

		clearInvocations(rebomService);
		List<String> profiles = List.of("cisa-2026");
		Map<String, Object> vars = byUuid(r.covered());
		vars.put("p", profiles);
		assertEquals(REPORT, value(gql(basic(w.orgKey()), SCORE, vars), "releaseSbomScoreProgrammatic"));
		ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
		verify(rebomService).scoreBom(scored.capture(), eq(profiles));
		assertEquals(exported, scored.getValue(), "the score scored the signed export");

		String csv = value(gql(basic(w.orgKey()), EXPORT, byUuid(r.covered(), "m", "CSV")),
				"releaseSbomExportProgrammatic");
		assertEquals(CSV, csv);
		assertFalse(csv.contains("reliza:export:signed"));
	}
}
