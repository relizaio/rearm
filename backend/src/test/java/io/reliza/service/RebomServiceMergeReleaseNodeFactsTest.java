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
import io.reliza.model.Rebom.BomHash;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.MissingSbomReason;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.service.RebomService.BomStructureType;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-30 (T-13, ADR-7): {@link RebomService#mergeAndStoreBoms} against a stub rebom. The release
 * facts travel in the GraphQL variables as rebom's ReleaseNodeFactsInput shape and a placeholder's
 * hashes as BomHashInput entries; a request without them sends no such keys. SCORE-39 (T-12):
 * the declared licence travels the same way, and a stored row's facts key never. SCORE-63 (T-12):
 * {@code attachedRootIds} travels beside {@code releaseRootIds} and survives every copy.
 */
class RebomServiceMergeReleaseNodeFactsTest {

	private static final UUID MERGED = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f1");
	private static final UUID ORG = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f2");
	private static final List<UUID> IDS = List.of(UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f3"),
			UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f4"));
	private static final String SHA_256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	private static final String SHA_1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";

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
		return new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT)
				.withMergeVersion(ReleaseService.MERGE_VERSION);
	}

	private JsonNode sentOptions() throws Exception {
		assertEquals(1, requests.size());
		return Utils.OM.readTree(requests.get(0)).get("variables").get("rebomOptions");
	}

	@Test
	void theFactsTravelInTheMergeRequestAsRebomsInputShape() throws Exception {
		RebomOptions options = merge().withReleaseNode(new ReleaseNodeFacts("Example Org",
				List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-1", SHA_1)), null, null));

		assertEquals(MERGED, service.mergeAndStoreBoms(IDS, options, ORG));

		assertEquals(Utils.OM.readTree("{\"supplierName\":\"Example Org\",\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\""
				+ SHA_256 + "\"},{\"alg\":\"SHA-1\",\"content\":\"" + SHA_1 + "\"}]}"), sentOptions().get("releaseNode"));
	}

	// SCORE-57 T-20: the release's own RELEASE-attached BOM ids travel beside the facts, as sent
	@Test
	void theReleaseRootIdsTravelAsAnArrayOfUuidStringsInOrderBesideTheFacts() throws Exception {
		UUID second = IDS.get(1);
		UUID first = IDS.get(0);
		RebomOptions options = merge().withReleaseNodeFactsKey("0".repeat(64))
				.withReleaseNode(new ReleaseNodeFacts("Example Org", null, null, null))
				.withReleaseRootIds(List.of(second, first));

		service.mergeAndStoreBoms(IDS, options, ORG);

		assertEquals(Utils.OM.readTree("[\"" + second + "\",\"" + first + "\"]"), sentOptions().get("releaseRootIds"));
		assertFalse(sentOptions().has("releaseNodeFactsKey"), sentOptions().toString());
	}

	// SCORE-57 T-20
	@Test
	void aRequestWithoutReleaseRootIdsSendsNoSuchKey() throws Exception {
		service.mergeAndStoreBoms(IDS, merge(), ORG);

		assertFalse(sentOptions().has("releaseRootIds"), sentOptions().toString());
	}

	// SCORE-57 T-20: an empty or null list is carried as none
	@Test
	void anEmptyOrNullListOfReleaseRootIdsSendsNoSuchKey() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseRootIds(List.of()), ORG);
		service.mergeAndStoreBoms(IDS, merge().withReleaseRootIds(null), ORG);

		assertEquals(2, requests.size());
		for (byte[] request : requests) {
			JsonNode sent = Utils.OM.readTree(request).get("variables").get("rebomOptions");
			assertFalse(sent.has("releaseRootIds"), sent.toString());
		}
	}

	// SCORE-62 T-20b: the send path drops missingSbomMode with withMissingSbomMode(null); the list survives it
	@Test
	void theReleaseRootIdsSurviveTheMissingSbomModeAndBelongsToCopies() throws Exception {
		RebomOptions options = merge().withReleaseRootIds(IDS).withMissingSbomMode(MissingSbomMode.OMIT);
		assertEquals(IDS, options.withBelongsTo(ArtifactBelongsTo.RELEASE).releaseRootIds());
		RebomOptions listed = merge().withReleaseRootIds(IDS);
		for (RebomOptions copy : List.of(listed.withMergeVersion(ReleaseService.MERGE_VERSION),
				listed.withMissingSbomComponents(List.of()), listed.withReleaseNode(null), listed.withSupplierName("s"),
				listed.withReleaseNodeFactsKey("k"))) {
			assertEquals(IDS, copy.releaseRootIds(), copy.toString());
		}

		service.mergeAndStoreBoms(IDS, options, ORG);

		assertEquals(Utils.OM.readTree("[\"" + IDS.get(0) + "\",\"" + IDS.get(1) + "\"]"),
				sentOptions().get("releaseRootIds"));
		assertFalse(sentOptions().has("missingSbomMode"), sentOptions().toString());
	}

	// SCORE-63 T-12: the attached uploads' ids travel beside releaseRootIds and the facts, as sent
	@Test
	void theAttachedRootIdsTravelAsAnArrayOfUuidStringsInOrderBesideTheReleaseRootIdsAndTheFacts() throws Exception {
		UUID second = IDS.get(1);
		UUID first = IDS.get(0);
		RebomOptions options = merge().withReleaseNodeFactsKey("0".repeat(64))
				.withReleaseNode(new ReleaseNodeFacts("Example Org", null, "MIT", null))
				.withReleaseRootIds(List.of(first))
				.withAttachedRootIds(List.of(second, first));

		service.mergeAndStoreBoms(IDS, options, ORG);

		assertEquals(Utils.OM.readTree("[\"" + second + "\",\"" + first + "\"]"), sentOptions().get("attachedRootIds"));
		assertEquals(Utils.OM.readTree("[\"" + first + "\"]"), sentOptions().get("releaseRootIds"));
		assertEquals("MIT", sentOptions().get("releaseNode").get("licenseExpression").asText());
		assertFalse(sentOptions().has("releaseNodeFactsKey"), sentOptions().toString());
	}

	// SCORE-63 T-12
	@Test
	void aRequestWithoutAttachedRootIdsSendsNoSuchKey() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseRootIds(IDS), ORG);

		assertFalse(sentOptions().has("attachedRootIds"), sentOptions().toString());
	}

	// SCORE-63 T-12: an empty or null list is carried as none
	@Test
	void anEmptyOrNullListOfAttachedRootIdsSendsNoSuchKey() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withAttachedRootIds(List.of()), ORG);
		service.mergeAndStoreBoms(IDS, merge().withAttachedRootIds(null), ORG);

		assertEquals(2, requests.size());
		for (byte[] request : requests) {
			JsonNode sent = Utils.OM.readTree(request).get("variables").get("rebomOptions");
			assertFalse(sent.has("attachedRootIds"), sent.toString());
		}
	}

	// SCORE-63 T-12 (the SCORE-62 T-20b shape): every copy carries the list, and the send path keeps it
	@Test
	void theAttachedRootIdsSurviveEveryCopy() throws Exception {
		RebomOptions options = merge().withAttachedRootIds(IDS).withMissingSbomMode(MissingSbomMode.OMIT);
		assertEquals(IDS, options.withBelongsTo(ArtifactBelongsTo.RELEASE).attachedRootIds());
		RebomOptions listed = merge().withAttachedRootIds(IDS);
		for (RebomOptions copy : List.of(listed.withMergeVersion(ReleaseService.MERGE_VERSION),
				listed.withMissingSbomComponents(List.of()), listed.withReleaseNode(null), listed.withSupplierName("s"),
				listed.withReleaseNodeFactsKey("k"), listed.withReleaseRootIds(List.of(IDS.get(0))),
				listed.withBelongsTo(ArtifactBelongsTo.SCE), listed.withMissingSbomMode(MissingSbomMode.OMIT))) {
			assertEquals(IDS, copy.attachedRootIds(), copy.toString());
		}
		// and withAttachedRootIds leaves releaseRootIds as it was
		assertEquals(List.of(IDS.get(1)), merge().withReleaseRootIds(List.of(IDS.get(1))).withAttachedRootIds(IDS).releaseRootIds());

		service.mergeAndStoreBoms(IDS, options.withReleaseNodeFactsKey("0".repeat(64)), ORG);

		assertEquals(Utils.OM.readTree("[\"" + IDS.get(0) + "\",\"" + IDS.get(1) + "\"]"),
				sentOptions().get("attachedRootIds"));
		assertFalse(sentOptions().has("missingSbomMode"), sentOptions().toString());
		assertFalse(sentOptions().has("releaseNodeFactsKey"), sentOptions().toString());
	}

	@Test
	void factsWithoutHashesSendTheSupplierAlone() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseNode(new ReleaseNodeFacts("Example Org", List.of(), null, null)), ORG);

		assertEquals(Utils.OM.readTree("{\"supplierName\":\"Example Org\"}"), sentOptions().get("releaseNode"));
	}

	@Test
	void factsWithoutASupplierSendTheHashesAlone() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseNode(new ReleaseNodeFacts(null,
				List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-1", SHA_1)), null, null)), ORG);

		assertEquals(Utils.OM.readTree("{\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"" + SHA_256 + "\"},"
				+ "{\"alg\":\"SHA-1\",\"content\":\"" + SHA_1 + "\"}]}"), sentOptions().get("releaseNode"));
	}

	@Test
	void aPlaceholdersHashesTravelWithItAndOneWithoutSendsNoSuchKey() throws Exception {
		MissingSbomComponent hashed = new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000f5", "httpd", "2.4.68",
				"container", "Example Org", "Example Org", null,
				List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-1", SHA_1)), null, null, MissingSbomReason.NO_SBOM_ARTIFACT);
		MissingSbomComponent bare = new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000f6", "flyway", "11.20.3",
				"library", "Example Org", "Example Org", null, null, null, null, MissingSbomReason.NO_SBOM_ARTIFACT);

		service.mergeAndStoreBoms(IDS, merge().withMissingSbomComponents(List.of(hashed, bare)), ORG);

		JsonNode missing = sentOptions().get("missingSbomComponents");
		assertEquals(Utils.OM.readTree("[{\"alg\":\"SHA-256\",\"content\":\"" + SHA_256 + "\"},"
				+ "{\"alg\":\"SHA-1\",\"content\":\"" + SHA_1 + "\"}]"), missing.get(0).get("hashes"));
		assertFalse(missing.get(1).has("hashes"), missing.toString());
	}

	@Test
	void aMergeWithoutFactsSendsNoSuchKey() throws Exception {
		service.mergeAndStoreBoms(IDS, merge(), ORG);

		assertFalse(sentOptions().has("releaseNode"), sentOptions().toString());
		assertEquals(ReleaseService.MERGE_VERSION, sentOptions().get("mergeVersion").asInt());
	}

	// SCORE-39 T-12: the declared licence travels under rebom's input names, and the facts key never
	@Test
	void theDeclaredLicenceTravelsOnTheReleaseNodeUnderRebomsNames() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseNode(new ReleaseNodeFacts("Acme Corp", List.of(),
				"LicenseRef-Acme-Commercial", "https://acme.example/licence")), ORG);

		assertEquals(Utils.OM.readTree("{\"supplierName\":\"Acme Corp\",\"licenseExpression\":\"LicenseRef-Acme-Commercial\","
				+ "\"licenseUrl\":\"https://acme.example/licence\"}"), sentOptions().get("releaseNode"));
	}

	@Test
	void blankLicenceFieldsAreNotSent() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseNode(new ReleaseNodeFacts("Acme Corp", null, " ", "")), ORG);

		assertEquals(Utils.OM.readTree("{\"supplierName\":\"Acme Corp\"}"), sentOptions().get("releaseNode"));
	}

	@Test
	void anExpressionWithoutAUrlSendsNoUrlKey() throws Exception {
		service.mergeAndStoreBoms(IDS, merge().withReleaseNode(new ReleaseNodeFacts(null, null, "MIT", null)), ORG);

		assertEquals(Utils.OM.readTree("{\"licenseExpression\":\"MIT\"}"), sentOptions().get("releaseNode"));
	}

	@Test
	void aPlaceholdersLicenceTravelsWithItAndOneWithoutSendsNoSuchKeys() throws Exception {
		MissingSbomComponent declared = new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000f7", "httpd", "2.4.68",
				"container", "Acme Corp", "Example Org", null, null, "LicenseRef-Acme-Commercial",
				"https://acme.example/licence", MissingSbomReason.NO_SBOM_ARTIFACT);
		MissingSbomComponent bare = new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000f8", "flyway", "11.20.3",
				"library", "Example Org", "Example Org", null, null, null, null, MissingSbomReason.NO_SBOM_ARTIFACT);

		service.mergeAndStoreBoms(IDS, merge().withMissingSbomComponents(List.of(declared, bare)), ORG);

		JsonNode missing = sentOptions().get("missingSbomComponents");
		assertEquals("LicenseRef-Acme-Commercial", missing.get(0).get("licenseExpression").asString());
		assertEquals("https://acme.example/licence", missing.get(0).get("licenseUrl").asString());
		assertFalse(missing.get(1).has("licenseExpression"), missing.toString());
		assertFalse(missing.get(1).has("licenseUrl"), missing.toString());
	}

	@Test
	void theFactsKeyOfAStoredRowNeverReachesRebom() throws Exception {
		RebomOptions keyed = merge().withReleaseNodeFactsKey("0".repeat(64))
				.withReleaseNode(new ReleaseNodeFacts("Acme Corp", null, "MIT", null));

		service.mergeAndStoreBoms(IDS, keyed, ORG);

		String body = new String(requests.get(0), StandardCharsets.UTF_8);
		assertFalse(body.contains("releaseNodeFactsKey"), body);
		assertFalse(sentOptions().has("releaseNodeFactsKey"), sentOptions().toString());
	}
}
