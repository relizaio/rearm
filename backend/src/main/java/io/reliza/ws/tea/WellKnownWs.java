/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.WellKnownAnswer;
import io.reliza.model.tea.TeaUnknownErrorType;

/**
 * The instance's TEA discovery document (task TEA-6). On the single-organization OSS installation
 * it lists the API base of every organization with a TEA id; elsewhere each organization hosts its
 * own document on its TEI domain. Public, on the TEA chain; a presented bearer is ignored.
 */
@RestController
public class WellKnownWs {

	static final String CACHE_CONTROL = "public, max-age=300";

	@Autowired
	private TeaSurfaceService teaSurfaceService;

	@GetMapping(value = "/.well-known/tea", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> teaWellKnown() {
		WellKnownAnswer a = teaSurfaceService.wellKnown();
		if (null == a.document()) {
			throw TeaUnknownErrorType.NOT_IMPLEMENTED == a.error() ? TeaRefusal.notImplemented(a.message())
					: TeaRefusal.objectUnknown(a.message());
		}
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
				.body(a.document());
	}
}
