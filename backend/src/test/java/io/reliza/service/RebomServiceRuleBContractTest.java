/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.RebomService.MalformedRebomAnswerException;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-52 T-25 and T-29 (g), (h) at the transport: {@link RebomService} against a stub rebom. No
 * request rearm-saas sends rebom carries an {@code ownerComponent} key (the CycloneDX and the SPDX
 * upload, the merge), and {@code latestStoredBomVersion} asks {@code bomMetaBySerialNumber} for the
 * text it is given, reads an empty list as no row, and answers an unreadable answer as
 * {@link MalformedRebomAnswerException}, a rebom error as the error itself.
 */
class RebomServiceRuleBContractTest {

	private static final UUID ORG = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000052a1");
	private static final UUID SERIAL = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000052a2");

	private HttpServer server;
	private final List<byte[]> requests = Collections.synchronizedList(new ArrayList<>());
	private volatile String answer;
	private RebomService service;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(in.readAllBytes());
			}
			byte[] out = answer.getBytes(StandardCharsets.UTF_8);
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

	private JsonNode onlyRequest() {
		assertEquals(1, requests.size());
		return Utils.OM.readTree(requests.get(0));
	}

	private static String uploadAnswer() {
		return "{\"data\":{\"addBom\":{\"uuid\":\"" + UUID.randomUUID() + "\",\"meta\":{\"serialNumber\":\"urn:uuid:"
				+ SERIAL + "\",\"bomVersion\":\"1\"},\"bom\":{},\"duplicate\":false}}}";
	}

	private static RebomOptions options() {
		return new RebomOptions("api", "Example Org", "1.0.0", ArtifactBelongsTo.RELEASE, null, StripBom.FALSE, null);
	}

	@Test
	void noUploadOrMergeRequestCarriesAnOwnerComponent() throws Exception {
		answer = uploadAnswer();
		JsonNode cdx = Utils.OM.readTree("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":"
				+ "\"urn:uuid:" + SERIAL + "\",\"version\":1,\"components\":[]}");
		service.uploadSbom(cdx, options(), BomFormat.CYCLONEDX, ORG);
		JsonNode sent = onlyRequest();
		assertTrue(sent.get("query").asString().contains("addBom"), sent.toString());
		assertNotNull(sent.get("variables").get("bomInput").get("rebomOptions"), sent.toString());
		assertFalse(new String(requests.get(0), StandardCharsets.UTF_8).contains("ownerComponent"));

		requests.clear();
		JsonNode spdx = Utils.OM.readTree("{\"spdxVersion\":\"SPDX-2.3\",\"SPDXID\":\"SPDXRef-DOCUMENT\"}");
		service.uploadSbom(spdx, options(), BomFormat.SPDX, ORG, SERIAL);
		onlyRequest();
		assertFalse(new String(requests.get(0), StandardCharsets.UTF_8).contains("ownerComponent"));

		requests.clear();
		answer = "{\"data\":{\"mergeAndStoreBoms\":{\"uuid\":\"" + SERIAL + "\",\"meta\":{\"serialNumber\":\"urn:uuid:"
				+ SERIAL + "\"}}}}";
		service.mergeAndStoreBoms(List.of(UUID.randomUUID(), UUID.randomUUID()),
				new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT)
						.withMergeVersion(ReleaseService.MERGE_VERSION), ORG);
		onlyRequest();
		assertFalse(new String(requests.get(0), StandardCharsets.UTF_8).contains("ownerComponent"));
		assertFalse(Utils.OM.valueToTree(options()).has("ownerComponent"));
	}

	@Test
	void theVersionReadSendsTheTextItIsGivenAndReadsOneRowOrNone() throws Exception {
		answer = "{\"data\":{\"bomMetaBySerialNumber\":[{\"bomVersion\":\"3\"}]}}";
		String padded = "urn:uuid:" + SERIAL;
		assertEquals(OptionalInt.of(3), service.latestStoredBomVersion(ORG, padded));
		JsonNode sent = onlyRequest();
		assertTrue(sent.get("query").asString().contains("bomMetaBySerialNumber"), sent.toString());
		assertEquals(padded, sent.get("variables").get("serialNumber").asString());
		assertEquals(ORG.toString(), sent.get("variables").get("org").asString());

		answer = "{\"data\":{\"bomMetaBySerialNumber\":[]}}";
		assertEquals(OptionalInt.empty(), service.latestStoredBomVersion(ORG, padded));
	}

	@Test
	void anUnreadableAnswerIsMalformedAndARebomErrorIsItself() {
		for (String bad : List.of("{\"data\":{\"bomMetaBySerialNumber\":[{\"bomVersion\":\"x\"}]}}",
				"{\"data\":{\"bomMetaBySerialNumber\":[{\"bomVersion\":\"2.0\"}]}}",
				"{\"data\":{\"bomMetaBySerialNumber\":[{}]}}",
				"{\"data\":{\"bomMetaBySerialNumber\":[{\"bomVersion\":\"1\"},{\"bomVersion\":\"2\"}]}}",
				"{\"data\":{\"bomMetaBySerialNumber\":null}}",
				"{\"data\":{\"bomMetaBySerialNumber\":{\"bomVersion\":\"1\"}}}")) {
			answer = bad;
			assertThrows(MalformedRebomAnswerException.class, () -> service.latestStoredBomVersion(ORG, SERIAL.toString()),
					bad);
		}
		answer = "{\"errors\":[{\"message\":\"rebom unavailable (stub)\"}]}";
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.latestStoredBomVersion(ORG, SERIAL.toString()));
		assertFalse(e instanceof MalformedRebomAnswerException, e.toString());
	}
}
