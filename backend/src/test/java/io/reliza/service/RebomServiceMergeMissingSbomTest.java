/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.MissingSbomReason;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-15 (T-6 wire half, ADR-1, ADR-7): {@link RebomService#mergeAndStoreBoms} against a stub
 * rebom. The list of releases without a BOM travels in the GraphQL variables as rebom's
 * MissingSbomComponentInput shape, and a merge without one sends no such key.
 */
class RebomServiceMergeMissingSbomTest {

	private static final UUID MERGED = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e1");
	private static final UUID ORG = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e2");
	private static final List<UUID> IDS = List.of(UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e3"),
			UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e4"));

	private HttpServer server;
	private final List<byte[]> requests = Collections.synchronizedList(new ArrayList<>());
	private RebomService service;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(in.readAllBytes());
			}
			byte[] out = ("{\"data\":{\"mergeAndStoreBoms\":{\"uuid\":\"" + MERGED + "\",\"meta\":{\"serialNumber\":\"urn:uuid:"
					+ MERGED + "\"}}}}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			try {
				exchange.sendResponseHeaders(200, out.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(out);
				}
			} catch (IOException e) {
				// the client went away; nothing to answer
			}
		});
		server.start();
		service = new RebomService("http://127.0.0.1:" + server.getAddress().getPort() + "/");
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private static RebomOptions merge() {
		return new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT).withMergeVersion(4);
	}

	private JsonNode sentOptions() throws Exception {
		assertEquals(1, requests.size());
		return Utils.OM.readTree(requests.get(0)).get("variables").get("rebomOptions");
	}

	@Test
	void theListTravelsInTheMergeRequestAsRebomsInputShape() throws Exception {
		MissingSbomComponent httpd = new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000e5", "httpd", "2.4.68",
				"container", "Example Org", "Example Org", null, null, null, null, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertEquals(MERGED, service.mergeAndStoreBoms(IDS, merge().withMissingSbomComponents(List.of(httpd)), ORG));

		assertEquals(Utils.OM.readTree("[{\"releaseUuid\":\"0b0f4a52-6c55-4d1f-9a8e-0000000000e5\",\"name\":\"httpd\","
				+ "\"version\":\"2.4.68\",\"type\":\"container\",\"supplierName\":\"Example Org\",\"group\":\"Example Org\","
				+ "\"purl\":null,\"reason\":\"NO_SBOM_ARTIFACT\"}]"), sentOptions().get("missingSbomComponents"));
		assertEquals(4, sentOptions().get("mergeVersion").asInt());
	}

	@Test
	void aMergeWithoutTheListSendsNoSuchKey() throws Exception {
		service.mergeAndStoreBoms(IDS, merge(), ORG);

		assertFalse(sentOptions().has("missingSbomComponents"), sentOptions().toString());
	}

	// TEA-15 (4.1 case 11): the missing-SBOM mode and the facts key belong to the stored row only
	@Test
	void theModeNeverTravelsInTheMergeRequest() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseNodeFactsKey("0".repeat(64))
				.withMissingSbomMode(MissingSbomMode.OMIT), ORG);

		assertFalse(sentOptions().has("missingSbomMode"), sentOptions().toString());
		assertFalse(sentOptions().has("releaseNodeFactsKey"), sentOptions().toString());
		assertFalse(sentOptions().has("missingSbomComponents"), sentOptions().toString());
	}
}
