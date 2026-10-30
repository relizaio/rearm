/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * TEA-19 (architecture 5.3 row 32) against the test database, on the publication rig of
 * {@link TeaPublicationIntegrationTestBase}: an external ride-along whose download link is not an absolute http(s)
 * url is skipped on the wire with its detail and never frozen into the collection document.
 */
public class TeaPublicationExternalUrlIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final String GOOD_URL = "https://files.example/notes/1.0.html";
	private static final String BAD_URL = "/relative/notes.html";

	@Test
	@SuppressWarnings("unchecked")
	void aNonConformingExternalLinkIsSkippedOnTheWireAndNeverFrozen() throws Exception {
		saveOrgProfile(TeaRawArtifacts.ALL, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/notes@1.0.0");
		UUID good = seedExternal(org, "notes-1.0.html", GOOD_URL, "ab".repeat(32));
		UUID bad = seedExternal(org, "notes-relative.html", BAD_URL, "cd".repeat(32));
		releaseService.addArtifact(good, w.release(), WU);
		releaseService.addArtifact(bad, w.release(), WU);
		UserData admin = admin();

		Map<String, Object> published = data(asUser(admin, PUBLISH_WITH_ARTIFACTS, vars("r", w.release().toString())),
				"publishReleaseOnTea");

		assertEquals("PUBLISHED", published.get("outcome"));
		List<Map<String, Object>> artifacts = (List<Map<String, Object>>) published.get("artifacts");
		assertEquals(3, artifacts.size(), artifacts.toString());
		Map<String, Object> inventoryRow = rowOf(artifacts, w.inventory());
		assertEquals("PUBLISHED", inventoryRow.get("status"));
		Map<String, Object> goodRow = rowOf(artifacts, good);
		assertEquals("PUBLISHED", goodRow.get("status"));
		assertNull(goodRow.get("detail"));
		Map<String, Object> badRow = rowOf(artifacts, bad);
		assertEquals("SKIPPED_LINK_NOT_HTTP_URL", badRow.get("status"));
		assertEquals("download link /relative/notes.html: is relative; an absolute http or https url is required",
				badRow.get("detail"));

		Object publication = ((Map<String, Object>) published.get("publication")).get("uuid");
		List<Map<String, Object>> collections = data(asUser(admin, COLLECTIONS, vars("p", publication)),
				"teaPublicationCollections");
		assertEquals(1, collections.size());
		Map<String, Object> v1 = collections.get(0);
		assertEquals(1, v1.get("version"));
		List<Object> entryArtifacts = ((List<Map<String, Object>>) v1.get("entries")).stream()
				.map(e -> e.get("artifact")).toList();
		assertTrue(entryArtifacts.contains(good.toString()), entryArtifacts.toString());
		assertFalse(entryArtifacts.contains(bad.toString()), entryArtifacts.toString());
		String document = (String) v1.get("document");
		assertFalse(document.contains(BAD_URL), document);
		List<String> urls = new ArrayList<>();
		for (JsonNode artifact : Utils.OM.readTree(document).get("artifacts")) {
			for (JsonNode format : artifact.get("formats")) {
				if (format.has("url")) urls.add(format.get("url").asText());
			}
		}
		assertEquals(List.of(GOOD_URL), urls);

		Map<String, Object> type = data(asUser(admin, "{ __type(name: \"TeaRideAlongStatus\") { enumValues { name } } }",
				Map.of()), "__type");
		List<Object> names = ((List<Map<String, Object>>) type.get("enumValues")).stream().map(e -> e.get("name")).toList();
		assertEquals(7, names.size(), names.toString());
		assertTrue(names.contains("SKIPPED_LINK_NOT_HTTP_URL"), names.toString());
	}

	private static Map<String, Object> rowOf(List<Map<String, Object>> artifacts, UUID artifact) {
		List<Map<String, Object>> rows = artifacts.stream().filter(a -> artifact.toString().equals(a.get("artifact")))
				.toList();
		assertEquals(1, rows.size(), artifacts.toString());
		return rows.get(0);
	}
}
