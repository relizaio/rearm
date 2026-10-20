/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import tools.jackson.databind.JsonNode;

/**
 * A NUL character on the real programmatic endpoint (task TPF-47, architecture-5 section 4 item 3):
 * the JSON escape every client sends is refused by its argument path and writes nothing; a raw 0x00
 * byte never reaches the executor (pinned as today's /error dispatch answer); ordinary text creates
 * the component; a query argument is refused too.
 */
public class NulInputHttpIntegrationTest extends ProgrammaticArtifactDownloadTestBase {

	/** createComponentProgrammatic with one PURL identifier; {@code idValue} is spliced into the JSON as is. */
	private static String body(String name, String idValue) {
		return "{\"query\":\"mutation c($c: CreateComponentInput!) { createComponentProgrammatic(component: $c) { uuid } }\","
				+ "\"variables\":{\"c\":{\"name\":\"" + name + "\",\"type\":\"COMPONENT\",\"versionSchema\":\"semver\","
				+ "\"featureBranchVersioning\":\"Branch.Micro\",\"identifiers\":[{\"idType\":\"PURL\",\"idValue\":\""
				+ idValue + "\"}]}}}";
	}

	private Optional<ComponentData> lookup(String name) {
		return componentService.findComponentDataByOrgNameType(w.org().getUuid(), name, ComponentType.COMPONENT);
	}

	// item 3 row 1: the JSON escape, as every client encodes the character
	@Test
	void theJsonEscapeIsRefusedByItsPath() throws Exception {
		String name = "tpf47-http-" + UUID.randomUUID();
		HttpResponse<String> r = post(ProgrammaticGraphQlConfig.PATH, body(name, "pkg:npm/a\\u0000b"),
				bearer(exchanged(w.adminKey())));
		assertStatus(200, r);
		JsonNode json = Utils.OM.readTree(r.body());
		assertEquals(1, json.get("errors").size(), r.body());
		JsonNode error = json.get("errors").get(0);
		assertEquals("component.identifiers[0].idValue: must not contain U+0000", error.get("message").asText());
		assertEquals("BAD_REQUEST", error.get("extensions").get("classification").asText());
		assertTrue(json.get("data").get("createComponentProgrammatic").isNull(), r.body());
		assertTrue(lookup(name).isEmpty());
	}

	// item 3 row 2: the raw byte, a pin of today's wire behaviour rather than of this change
	@Test
	void theRawByteNeverReachesTheExecutor() throws Exception {
		String name = "tpf47-raw-" + UUID.randomUUID();
		HttpResponse<String> r = post(ProgrammaticGraphQlConfig.PATH, body(name, "pkg:npm/a\u0000b"),
				bearer(exchanged(w.adminKey())));
		assertStatus(401, r);
		String challenge = header(r, HttpHeaders.WWW_AUTHENTICATE);
		assertNotNull(challenge);
		assertTrue(challenge.startsWith("Bearer"), challenge);
		assertFalse(r.body().contains("\"errors\""), r.body());
		assertFalse(r.body().contains("\"data\""), r.body());
		assertTrue(lookup(name).isEmpty());
	}

	// item 3 row 3: control
	@Test
	void ordinaryTextCreatesTheComponent() throws Exception {
		String name = "tpf47-ok-" + UUID.randomUUID();
		HttpResponse<String> r = post(ProgrammaticGraphQlConfig.PATH, body(name, "pkg:npm/ok"),
				bearer(exchanged(w.adminKey())));
		assertStatus(200, r);
		JsonNode json = Utils.OM.readTree(r.body());
		assertTrue(null == json.get("errors") || json.get("errors").isEmpty(), r.body());
		assertFalse(json.get("data").get("createComponentProgrammatic").get("uuid").isNull(), r.body());
		ComponentData c = lookup(name).orElseThrow();
		assertEquals("pkg:npm/ok", c.getIdentifiers().get(0).getIdValue());
	}

	// item 3 row 4: a query on the real programmatic endpoint (T-2)
	@Test
	void aQueryArgumentIsRefusedByItsPath() throws Exception {
		String body = "{\"query\":\"query q($k: String!) { agentTaskByKeyProgrammatic(key: $k) { uuid } }\","
				+ "\"variables\":{\"k\":\"TPF-1\\u0000\"}}";
		HttpResponse<String> r = post(ProgrammaticGraphQlConfig.PATH, body, bearer(exchanged(w.adminKey())));
		assertStatus(200, r);
		JsonNode json = Utils.OM.readTree(r.body());
		assertEquals(1, json.get("errors").size(), r.body());
		JsonNode error = json.get("errors").get(0);
		assertEquals("key: must not contain U+0000", error.get("message").asText());
		assertEquals("BAD_REQUEST", error.get("extensions").get("classification").asText());
		assertTrue(json.get("data").get("agentTaskByKeyProgrammatic").isNull(), r.body());
	}
}
