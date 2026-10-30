/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import io.reliza.common.Utils;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.repositories.TeaPublishedCollectionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task TEA-10, design round 5 decisions 18 and 19 (test-only): one entry of a stored collection version
 * rewritten past the engine, so a case can read an entry frozen before the TEA-19 url rule or the TEA-20
 * signatureUrl, which the engine no longer produces. The entry of {@code record_data} and its element of
 * the document text change together, and {@code document_sha256} is recomputed with the engine's function.
 */
final class TeaFrozenEntries {

	private TeaFrozenEntries() {}

	/** The format url of that entry, in its record, its frozen object and the document. */
	static void freezeUrl(TeaPublishedCollectionRepository repository, UUID publication, int version, UUID teaUuid,
			String url) {
		rewrite(repository, publication, version, teaUuid, e -> e.put("url", url), f -> f.put("url", url));
	}

	/**
	 * The entry's signature row and revision, and the format's signatureUrl set ({@code signatureUrl} not
	 * null) or removed (null: a pre-rule entry whose signature is the row's live link).
	 */
	static void freezeSignature(TeaPublishedCollectionRepository repository, UUID publication, int version, UUID teaUuid,
			UUID signatureArtifact, Integer signatureRevision, String signatureUrl) {
		rewrite(repository, publication, version, teaUuid, e -> {
			if (null == signatureArtifact) e.putNull("signatureArtifact");
			else e.put("signatureArtifact", signatureArtifact.toString());
			if (null == signatureRevision) e.putNull("signatureRevision");
			else e.put("signatureRevision", signatureRevision.intValue());
		}, f -> {
			if (null == signatureUrl) f.remove("signatureUrl");
			else f.put("signatureUrl", signatureUrl);
		});
	}

	/**
	 * Task TEA-28 (test-only): the entry's name, in its record, its frozen object and its document element, as a
	 * TEA-5 to TEA-27 aggregate carried it (the file-style display identifier), so a case reads a legacy revision.
	 */
	static void freezeName(TeaPublishedCollectionRepository repository, UUID publication, int version, UUID teaUuid,
			String name) {
		TeaPublishedCollection row = repository.findVersion(publication, version).orElseThrow();
		String tea = teaUuid.toString();
		int found = 0;
		ObjectNode document = (ObjectNode) Utils.OM.readTree(row.getDocument());
		for (JsonNode a : document.get("artifacts")) {
			if (!tea.equals(a.get("uuid").asString())) continue;
			((ObjectNode) a).put("name", name);
			found++;
		}
		ObjectNode record = Utils.OM.valueToTree(row.getRecordData());
		for (JsonNode e : record.get("entries")) {
			if (!tea.equals(e.get("teaUuid").asString())) continue;
			((ObjectNode) e).put("name", name);
			((ObjectNode) e.get("frozen")).put("name", name);
			found++;
		}
		assertEquals(2, found, "one entry and one document element of " + tea + " in " + publication + " version " + version);
		String text = Utils.OM.writeValueAsString(document);
		row.setDocument(text);
		row.setDocumentSha256(TeaCollectionAssembler.sha256(text));
		row.setRecordData(Utils.OM.convertValue(record, Map.class));
		repository.save(row);
	}

	@SuppressWarnings("unchecked")
	private static void rewrite(TeaPublishedCollectionRepository repository, UUID publication, int version, UUID teaUuid,
			Consumer<ObjectNode> onEntry, Consumer<ObjectNode> onFormat) {
		TeaPublishedCollection row = repository.findVersion(publication, version).orElseThrow();
		String tea = teaUuid.toString();
		int found = 0;
		ObjectNode document = (ObjectNode) Utils.OM.readTree(row.getDocument());
		for (JsonNode a : document.get("artifacts")) {
			if (!tea.equals(a.get("uuid").asString())) continue;
			a.get("formats").forEach(f -> onFormat.accept((ObjectNode) f));
			found++;
		}
		ObjectNode record = Utils.OM.valueToTree(row.getRecordData());
		for (JsonNode e : record.get("entries")) {
			if (!tea.equals(e.get("teaUuid").asString())) continue;
			onEntry.accept((ObjectNode) e);
			e.get("frozen").get("formats").forEach(f -> onFormat.accept((ObjectNode) f));
			found++;
		}
		assertEquals(2, found, "one entry and one document element of " + tea + " in " + publication + " version " + version);
		String text = Utils.OM.writeValueAsString(document);
		row.setDocument(text);
		row.setDocumentSha256(TeaCollectionAssembler.sha256(text));
		row.setRecordData(Utils.OM.convertValue(record, Map.class));
		repository.save(row);
	}
}
