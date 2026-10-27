/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.UserData;
import tools.jackson.databind.JsonNode;

/**
 * TEA-20 (architecture 5.2 row 11) against the test database, on the publication rig of
 * {@link TeaPublicationIntegrationTestBase}: an externally stored signature whose link passes the TEA-19 url rule is
 * frozen as its format's {@code signatureUrl}; one whose link fails it is skipped on the wire with its own row and
 * the format publishes unsigned.
 */
public class TeaPublicationExternalSignatureIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final String GOOD_SIG_URL = "https://files.example/signed/1.html.sig";
	private static final String BAD_URL = "https://files.example/signed/2.html";

	@Test
	@SuppressWarnings("unchecked")
	void anExternalSignatureIsPublishedAsSignatureUrlOnTheWireOrSkipped() throws Exception {
		saveOrgProfile(TeaRawArtifacts.ALL, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/signed@1.0.0");
		UUID good = seedExternal(org, "signed-1.html", "https://files.example/signed/1.html", "ab".repeat(32));
		UUID goodSig = attachExternalSignature(org, good, "signed-1.html.sig", GOOD_SIG_URL);
		UUID bad = seedExternal(org, "signed-2.html", BAD_URL, "cd".repeat(32));
		UUID badSig = attachExternalSignature(org, bad, "signed-2.html.sig", "/relative/2.html.sig");
		releaseService.addArtifact(good, w.release(), WU);
		releaseService.addArtifact(bad, w.release(), WU);
		UserData admin = admin();

		Map<String, Object> published = data(asUser(admin, PUBLISH_WITH_ARTIFACTS, vars("r", w.release().toString())),
				"publishReleaseOnTea");

		assertEquals("PUBLISHED", published.get("outcome"));
		List<Map<String, Object>> artifacts = (List<Map<String, Object>>) published.get("artifacts");
		assertEquals(4, artifacts.size(), artifacts.toString());
		assertEquals("PUBLISHED", rowOf(artifacts, w.inventory()).get("status"));
		Map<String, Object> goodRow = rowOf(artifacts, good);
		assertEquals("PUBLISHED", goodRow.get("status"));
		assertNull(goodRow.get("detail"));
		Map<String, Object> badRow = rowOf(artifacts, bad);
		assertEquals("PUBLISHED", badRow.get("status"));
		assertNull(badRow.get("detail"));
		Map<String, Object> badSigRow = rowOf(artifacts, badSig);
		assertEquals("SKIPPED_LINK_NOT_HTTP_URL", badSigRow.get("status"));
		assertEquals("signature signed-2.html.sig (" + badSig + "): download link /relative/2.html.sig: is relative; "
				+ "an absolute http or https url is required", badSigRow.get("detail"));
		assertEquals(artifacts.indexOf(badSigRow) + 1, artifacts.indexOf(badRow), "the signature's row precedes the format's");
		assertFalse(artifacts.stream().anyMatch(a -> goodSig.toString().equals(a.get("artifact"))), artifacts.toString());

		Object publication = ((Map<String, Object>) published.get("publication")).get("uuid");
		List<Map<String, Object>> collections = data(asUser(admin, COLLECTIONS_WITH_SIGNATURES, vars("p", publication)),
				"teaPublicationCollections");
		assertEquals(1, collections.size());
		Map<String, Object> v1 = collections.get(0);
		assertEquals(1, v1.get("version"));
		List<Map<String, Object>> entries = (List<Map<String, Object>>) v1.get("entries");
		Map<String, Object> goodEntry = entryOf(entries, good);
		assertEquals(goodSig.toString(), goodEntry.get("signatureArtifact"));
		assertEquals(1, goodEntry.get("signatureRevision"));
		Map<String, Object> badEntry = entryOf(entries, bad);
		assertNull(badEntry.get("signatureArtifact"));
		assertNull(badEntry.get("signatureRevision"));

		String document = (String) v1.get("document");
		assertFalse(document.contains("/relative/"), document);
		List<JsonNode> signed = new ArrayList<>();
		JsonNode badFormat = null;
		for (JsonNode artifact : Utils.OM.readTree(document).get("artifacts")) {
			for (JsonNode format : artifact.get("formats")) {
				if (format.has("signatureUrl")) signed.add(format);
				if (format.has("url") && BAD_URL.equals(format.get("url").asText())) badFormat = format;
			}
		}
		assertEquals(1, signed.size(), document);
		assertEquals(GOOD_SIG_URL, signed.get(0).get("signatureUrl").asText());
		assertEquals(List.of("mediaType", "url", "signatureUrl", "checksums"), new ArrayList<>(signed.get(0).propertyNames()));
		assertFalse(null == badFormat, document);
		assertFalse(badFormat.has("signatureUrl"), document);
	}

	private static Map<String, Object> rowOf(List<Map<String, Object>> artifacts, UUID artifact) {
		List<Map<String, Object>> rows = artifacts.stream().filter(a -> artifact.toString().equals(a.get("artifact")))
				.toList();
		assertEquals(1, rows.size(), artifacts.toString());
		return rows.get(0);
	}

	private static Map<String, Object> entryOf(List<Map<String, Object>> entries, UUID artifact) {
		return rowOf(entries, artifact);
	}
}
