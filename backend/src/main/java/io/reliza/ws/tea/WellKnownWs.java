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

import jakarta.servlet.http.HttpServletRequest;

import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.WellKnownAnswer;
import io.reliza.model.tea.TeaUnknownErrorType;

/**
 * The instance's TEA discovery document (task TEA-6), per request host (TPF-142): on an assigned
 * domain whose organization serves on DOMAIN with hosting DOMAIN, the document naming that domain's
 * base; on any other custom host NOT_IMPLEMENTED; elsewhere, on the single-organization OSS
 * installation, the API base of every serving organization, and on every other installation type
 * NOT_IMPLEMENTED (each organization hosts its own document on its TEI domain). Public, on the TEA
 * chain; a presented bearer is ignored.
 */
@RestController
public class WellKnownWs {

	static final String CACHE_CONTROL = "public, max-age=300";

	@Autowired
	private TeaSurfaceService teaSurfaceService;

	@GetMapping(value = "/.well-known/tea", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> teaWellKnown(HttpServletRequest request) {
		WellKnownAnswer a = teaSurfaceService.wellKnown(TeaHosts.hostOf(request));
		if (null == a.document()) {
			throw TeaUnknownErrorType.NOT_IMPLEMENTED == a.error() ? TeaRefusal.notImplemented(a.message())
					: TeaRefusal.objectUnknown(a.message());
		}
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
				.body(a.document());
	}
}
