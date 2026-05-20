/**
 * Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
 */
package io.reliza.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Public discovery endpoint for AI-agent runtimes.
 *
 * <p>Serves {@code orientation.md} (under
 * {@code backend/src/main/resources/static/agents/}) at
 * {@code GET /api/agents/orientation.md} — the canonical URL cited
 * from the user-facing "bootstrap your agent" docs and from the
 * agent's first prompt.
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

	private static final String ORIENTATION_RESOURCE = "static/agents/orientation.md";
	private static final MediaType TEXT_MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

	@GetMapping("/api/agents/orientation.md")
	public ResponseEntity<byte[]> orientation() throws IOException {
		Resource resource = new ClassPathResource(ORIENTATION_RESOURCE);
		byte[] body = resource.getInputStream().readAllBytes();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(TEXT_MARKDOWN);
		// Short cache so a fresh agent picks up the latest doc on
		// each bootstrap. Long enough to keep load off the backend
		// during a session's polling lifetime; short enough that an
		// operator can roll the doc and have agents see it on the
		// next bootstrap.
		headers.setCacheControl("public, max-age=300");
		headers.setContentLength(body.length);
		return new ResponseEntity<>(body, headers, 200);
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

	private static String asUtf8(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
