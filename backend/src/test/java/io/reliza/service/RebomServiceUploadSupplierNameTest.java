/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import io.reliza.common.Utils.StripBom;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Rebom.RebomOptions;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-30 (T-13, ADR-12): {@link RebomService#uploadSbom} against a stub rebom. The supplier ReARM
 * names for an upload's root travels as {@code rebomOptions.supplierName}; an upload without one
 * sends no such key, and a blank one is no supplier.
 */
class RebomServiceUploadSupplierNameTest {

	private static final UUID STORED = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e1");
	private static final UUID ORG = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e2");
	private static final String BOM = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[]}";

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
			byte[] out = ("{\"data\":{\"addBom\":{\"uuid\":\"" + STORED + "\",\"meta\":{\"name\":\"api\"},"
					+ "\"bom\":null,\"duplicate\":false}}}").getBytes(StandardCharsets.UTF_8);
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

	private static RebomOptions upload() {
		return new RebomOptions("api", "Example Org", "1.0.0", ArtifactBelongsTo.RELEASE, null, StripBom.FALSE, null);
	}

	private JsonNode sentOptions() throws Exception {
		assertEquals(1, requests.size());
		return Utils.OM.readTree(requests.get(0)).get("variables").get("bomInput").get("rebomOptions");
	}

	@Test
	void theSupplierNameTravelsInTheUploadRequest() throws Exception {
		assertEquals(STORED, service.uploadSbom(Utils.OM.readTree(BOM), upload().withSupplierName("Example Org"),
				BomFormat.CYCLONEDX, ORG).uuid());

		assertEquals("Example Org", sentOptions().get("supplierName").asString());
		assertEquals("Example Org", sentOptions().get("group").asString());
	}

	@Test
	void anUploadWithoutASupplierNameSendsNoSuchKey() throws Exception {
		service.uploadSbom(Utils.OM.readTree(BOM), upload(), BomFormat.CYCLONEDX, ORG);

		assertFalse(sentOptions().has("supplierName"), sentOptions().toString());
	}

	@Test
	void aBlankSupplierNameIsNoSupplier() throws Exception {
		assertNull(upload().withSupplierName(" ").supplierName());
		assertNull(upload().withSupplierName("").supplierName());
		assertNull(upload().withSupplierName(null).supplierName());
		assertEquals(upload(), upload().withSupplierName(" "));

		service.uploadSbom(Utils.OM.readTree(BOM), upload().withSupplierName(" "), BomFormat.CYCLONEDX, ORG);

		assertFalse(sentOptions().has("supplierName"), sentOptions().toString());
	}

	@Test
	void theOtherWithersKeepTheSupplierName() {
		RebomOptions named = upload().withSupplierName("Example Org");

		assertEquals("Example Org", named.withMergeVersion(ReleaseService.MERGE_VERSION).supplierName());
		assertEquals("Example Org", named.withMissingSbomComponents(List.of()).supplierName());
		assertEquals("Example Org", named.withReleaseNode(null).supplierName());
	}
}
