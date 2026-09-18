/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the programmatic API contract (see {@link ProgrammaticSchemaRegistry#bundleSdl()}) as
 * SDL. Public like the orientation doc: it is the API surface, not data. Client generators
 * fetch it from a running instance and check the result in.
 */
@RestController
public class ProgrammaticSchemaController {

	public static final String PATH = "/api/programmatic/schema";

	private final ProgrammaticSchemaRegistry registry;

	public ProgrammaticSchemaController(ProgrammaticSchemaRegistry registry) {
		this.registry = registry;
	}

	@GetMapping(value = PATH, produces = "application/graphql+sdl;charset=UTF-8")
	public ResponseEntity<String> schema() {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/graphql+sdl;charset=UTF-8")).body(registry.bundleSdl());
	}
}
