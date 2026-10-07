/**
 * Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
 */
package io.reliza.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Public discovery endpoint for AI-agent runtimes.
 *
 * <p>Serves the orientation (under
 * {@code backend/src/main/resources/static/agents/orientation/}): the whole document at
 * {@code GET /api/agents/orientation.md} — the canonical URL cited
 * from the user-facing "bootstrap your agent" docs and from the
 * agent's first prompt — and, since task RD3-10, its core and each
 * section alone at {@code GET /api/agents/orientation/<section>.md}.
 *
 * <p>The endpoint is unauthenticated by design: a fresh agent fetches
 * this doc before any auth is configured, and the contents are
 * already public information (no secrets, no tenant data — only
 * the API contract).
 *
 * <p>The doc version is pinned to the backend's deployed version:
 * the markdown ships inside the backend image, so every release
 * carries a matching orientation. The doc's front-matter declares
 * the {@code rearm_api_version} + {@code rearm_cli_min} an agent
 * should self-check against on startup.
 */
@Controller
public class AgentDocsController {

	private static final MediaType TEXT_MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

	/** The whole orientation: the core, then every section in the core's order (task RD3-10). */
	@GetMapping("/api/agents/orientation.md")
	public ResponseEntity<byte[]> orientation() throws IOException {
		return markdown(AgentOrientation.full(), 200);
	}

	/**
	 * The core, or one section by the name the core's "Where to read what" table gives it (task RD3-10).
	 * An unknown name answers 404 with the names there are.
	 */
	@GetMapping("/api/agents/orientation/{section}.md")
	public ResponseEntity<byte[]> orientationSection(@PathVariable("section") String section) throws IOException {
		Optional<String> doc = AgentOrientation.section(section);
		if (doc.isPresent()) return markdown(doc.get(), 200);
		String names = AgentOrientation.sections().stream().map(AgentOrientation.Section::key)
				.collect(Collectors.joining(", "));
		return markdown("No orientation section named '" + section + "'. The sections are: " + AgentOrientation.CORE + ", "
				+ names + ".\n", 404);
	}

	/**
	 * Defensive: some callers (and future curl-from-shell muscle
	 * memory) reach for the directory path without the file name.
	 * Redirect rather than 404.
	 */
	@GetMapping("/api/agents")
	public ResponseEntity<byte[]> agentsRoot() throws IOException {
		return orientation();
	}

	private static ResponseEntity<byte[]> markdown(String text, int status) {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(TEXT_MARKDOWN);
		// Short cache so a fresh agent picks up the latest doc on
		// each bootstrap. Long enough to keep load off the backend
		// during a session's polling lifetime; short enough that an
		// operator can roll the doc and have agents see it on the
		// next bootstrap.
		headers.setCacheControl("public, max-age=300");
		headers.setContentLength(body.length);
		return new ResponseEntity<>(body, headers, status);
	}
}
