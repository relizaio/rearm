/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.reliza.common.Utils;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.TeaAuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaAccess;

/** Task TEA-10, design 4.2 case 9: every TEA error body presets its JSON content type; the 401s carry none. */
class TeaRefusalTest {

	@Test
	void everyBodyCarryingRefusalPresetsJson() {
		List<TeaRefusal> refusals = List.of(TeaRefusal.of(TeaAccess.CONCEAL), TeaRefusal.objectUnknown(),
				TeaRefusal.objectUnknown("m"), TeaRefusal.notImplemented("m"), TeaRefusal.invalidRequest("m"),
				TeaRefusal.invalidPageToken(), TeaRefusal.noAcceptableFormat("m"), TeaRefusal.signatureNotFound());
		for (TeaRefusal r : refusals) {
			assertEquals(MediaType.APPLICATION_JSON, r.response().getHeaders().getContentType(), String.valueOf(r.response()));
			assertEquals("no-store", r.response().getHeaders().getCacheControl());
		}
		ResponseEntity<TeaErrorResponse> concealed = TeaAuthorizationService.denial(TeaAccess.CONCEAL);
		assertEquals(MediaType.APPLICATION_JSON, concealed.getHeaders().getContentType());
		for (TeaAccess a : List.of(TeaAccess.UNAUTHENTICATED, TeaAccess.INVALID_TOKEN)) {
			ResponseEntity<TeaErrorResponse> d = TeaAuthorizationService.denial(a);
			assertNull(d.getBody(), a.name());
			assertNull(d.getHeaders().getContentType(), a.name());
		}
	}

	@Test
	void theTwoArtifactRefusals() {
		ResponseEntity<TeaErrorResponse> nf = TeaRefusal.noAcceptableFormat("m").response();
		assertEquals(406, nf.getStatusCode().value());
		assertEquals(TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, nf.getBody().getError());
		assertEquals(Map.of("error", "NO_ACCEPTABLE_FORMAT", "message", "m"),
				Utils.OM.readValue(TeaJson.MAPPER.writeValueAsString(nf.getBody()), Map.class));
		ResponseEntity<TeaErrorResponse> sig = TeaRefusal.signatureNotFound().response();
		assertEquals(404, sig.getStatusCode().value());
		assertEquals(Map.of("error", "SIGNATURE_NOT_FOUND"),
				Utils.OM.readValue(TeaJson.MAPPER.writeValueAsString(sig.getBody()), Map.class));
	}
}
