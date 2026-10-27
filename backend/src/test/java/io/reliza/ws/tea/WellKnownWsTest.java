/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.WellKnownAnswer;

/** Task TEA-6, design 4.6 case 22: the instance discovery document and its refusals. */
class WellKnownWsTest {

	@Test
	void theDocumentIsPubliclyCacheableAndTheRefusalsAreNot() {
		WellKnownWs ws = new WellKnownWs();
		TeaSurfaceService surface = mock(TeaSurfaceService.class);
		ReflectionTestUtils.setField(ws, "teaSurfaceService", surface);
		String doc = "{\"schemaVersion\":1,\"endpoints\":[{\"url\":\"https://x.example.com/tea/1\",\"versions\":[\"1.0.0\"],\"priority\":1}]}";
		when(surface.wellKnown()).thenReturn(new WellKnownAnswer(doc, null, null));
		ResponseEntity<String> r = ws.teaWellKnown();
		assertEquals(200, r.getStatusCode().value());
		assertEquals(doc, r.getBody());
		assertEquals("public, max-age=300", r.getHeaders().getCacheControl());
		assertEquals(MediaType.APPLICATION_JSON, r.getHeaders().getContentType());

		when(surface.wellKnown()).thenReturn(new WellKnownAnswer(null, TeaUnknownErrorType.NOT_IMPLEMENTED, "elsewhere"));
		TeaRefusal ni = assertThrows(TeaRefusal.class, ws::teaWellKnown);
		assertEquals(TeaUnknownErrorType.NOT_IMPLEMENTED, ni.response().getBody().getError());
		assertEquals("no-store", ni.response().getHeaders().getCacheControl());
		when(surface.wellKnown()).thenReturn(new WellKnownAnswer(null, TeaUnknownErrorType.OBJECT_UNKNOWN, "none yet"));
		TeaRefusal ou = assertThrows(TeaRefusal.class, ws::teaWellKnown);
		assertEquals(404, ou.response().getStatusCode().value());
		assertEquals("none yet", ou.response().getBody().getMessage());
		assertEquals("no-store", ou.response().getHeaders().getCacheControl());
	}
}
