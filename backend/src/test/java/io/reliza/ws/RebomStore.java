/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.dto.ArtifactUploadResponseDto;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.RebomResponse;
import io.reliza.service.RebomService;
import tools.jackson.databind.JsonNode;

/**
 * An in-memory rebom for the rule B tests (SCORE-52): it keeps every stored version of every
 * serialNumber, as rebom does, and answers an upload as rebom's addBom does. A CycloneDX upload of
 * identical content is deduplicated (the stored row answered, nothing new stored), a different
 * document at a version not above the newest is refused with a version conflict, and a higher
 * version is stored. An SPDX update gets the next version; an SPDX first upload whose namespace is
 * already stored with the same content answers the stored row, as rebom's namespace deduplication
 * does. {@code bomMetaBySerialNumber} ({@code latestStoredBomVersion}) answers from the same state.
 * A null argument, which only Mockito's own stubbing calls pass, answers nothing.
 */
final class RebomStore {

	static final String VERSION_CONFLICT = "BOM version conflict (rebom stub)";

	private record Stored(int version, String content) {}

	private final Map<UUID, List<Stored>> bySerial = new HashMap<>();
	private final Map<String, UUID> spdxNamespaces = new HashMap<>();
	private final Map<UUID, Integer> addBomCalls = new HashMap<>();
	private final Map<UUID, Integer> versionsStored = new HashMap<>();
	private final List<String> latestVersionReads = new ArrayList<>();

	/** Answers the upload, the version read and the BOM read of {@code rebom} from this store. */
	void install(RebomService rebom) throws RelizaException {
		when(rebom.uploadSbom(any(), any(), any(), any(), any())).thenAnswer(inv -> upload(inv.getArgument(0),
				inv.getArgument(1), inv.getArgument(2), inv.getArgument(4)));
		when(rebom.latestStoredBomVersion(any(), any())).thenAnswer(inv -> latest(inv.getArgument(1)));
		when(rebom.findBomByIdJson(any(), any())).thenAnswer(inv -> newestDocument(inv.getArgument(0)));
		when(rebom.findBomByVersion(any(), any(), anyInt())).thenAnswer(inv -> newestDocument(inv.getArgument(0)));
	}

	/** rebom already holds {@code serial} at {@code version}, with no artifact recording it (a merge, a rollback). */
	void storeWithoutArtifact(UUID serial, int version, String content) {
		bySerial.computeIfAbsent(serial, s -> new ArrayList<>())
				.add(new Stored(version, Utils.OM.readTree(content).toString()));
	}

	/** rebom already holds the SPDX document {@code content} of {@code namespace} as {@code serial} at version 1. */
	void storeSpdx(String namespace, UUID serial, String content) {
		spdxNamespaces.put(namespace, serial);
		storeWithoutArtifact(serial, 1, content);
	}

	/** How many uploads of {@code serial} reached rebom's addBom. */
	int addBomCalls(UUID serial) {
		return addBomCalls.getOrDefault(serial, 0);
	}

	/** How many new versions of {@code serial} rebom stored through addBom. */
	int versionsStored(UUID serial) {
		return versionsStored.getOrDefault(serial, 0);
	}

	/** The text of every {@code latestStoredBomVersion} read, in order. */
	List<String> latestVersionReads() {
		return latestVersionReads;
	}

	private OptionalInt latest(String serialKeyText) {
		if (null == serialKeyText) {
			return OptionalInt.empty();
		}
		latestVersionReads.add(serialKeyText);
		UUID serial = keyOf(serialKeyText);
		List<Stored> rows = null == serial ? null : bySerial.get(serial);
		return null == rows || rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(newest(rows).version());
	}

	private JsonNode newestDocument(UUID serial) {
		List<Stored> rows = null == serial ? null : bySerial.get(serial);
		return null == rows || rows.isEmpty() ? null : Utils.OM.readTree(newest(rows).content());
	}

	private RebomResponse upload(JsonNode bom, RebomOptions options, BomFormat format, UUID existingSerial)
			throws RelizaException {
		if (null == bom) {
			return null;
		}
		String content = bom.toString();
		if (BomFormat.SPDX == format) {
			return uploadSpdx(bom, options, content, existingSerial);
		}
		JsonNode serialNode = bom.get("serialNumber");
		UUID serial = null == serialNode ? UUID.randomUUID() : keyOf(serialNode.asText().strip());
		if (null == serial) {
			serial = UUID.randomUUID();
		}
		int version = null == bom.get("version") ? 1 : bom.get("version").asInt();
		addBomCalls.merge(serial, 1, Integer::sum);
		List<Stored> rows = bySerial.computeIfAbsent(serial, s -> new ArrayList<>());
		for (Stored row : rows) {
			if (row.content().equals(content)) {
				return response(serial, row.version(), options, true);
			}
		}
		if (!rows.isEmpty() && version <= newest(rows).version()) {
			throw new RelizaException(VERSION_CONFLICT);
		}
		rows.add(new Stored(version, content));
		versionsStored.merge(serial, 1, Integer::sum);
		return response(serial, version, options, false);
	}

	private RebomResponse uploadSpdx(JsonNode bom, RebomOptions options, String content, UUID existingSerial)
			throws RelizaException {
		UUID serial = existingSerial;
		if (null == serial) {
			String namespace = null == bom.get("documentNamespace") ? null : bom.get("documentNamespace").asText();
			serial = null == namespace ? null : spdxNamespaces.get(namespace);
			if (null != serial) {
				addBomCalls.merge(serial, 1, Integer::sum);
				for (Stored row : bySerial.getOrDefault(serial, List.of())) {
					if (row.content().equals(content)) {
						return response(serial, row.version(), options, true);
					}
				}
				throw new RelizaException("BOM validation failed: namespace already stored (rebom stub)");
			}
			serial = UUID.randomUUID();
			if (null != namespace) {
				spdxNamespaces.put(namespace, serial);
			}
		}
		addBomCalls.merge(serial, 1, Integer::sum);
		List<Stored> rows = bySerial.computeIfAbsent(serial, s -> new ArrayList<>());
		int version = rows.isEmpty() ? 1 : newest(rows).version() + 1;
		rows.add(new Stored(version, content));
		versionsStored.merge(serial, 1, Integer::sum);
		return response(serial, version, options, false);
	}

	private static Stored newest(List<Stored> rows) {
		return rows.stream().max((l, r) -> Integer.compare(l.version(), r.version())).orElseThrow();
	}

	/** rebom's key of a serialNumber: the uuid with or without a lower-case urn:uuid: prefix. */
	private static UUID keyOf(String text) {
		String t = text.startsWith("urn:uuid:") ? text.substring("urn:uuid:".length()) : text;
		try {
			UUID id = UUID.fromString(t);
			return id.toString().equals(t) ? id : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static RebomResponse response(UUID serial, int version, RebomOptions options, boolean duplicate) {
		ArtifactUploadResponseDto oci = new ArtifactUploadResponseDto();
		oci.setDigest("sha256:rebom-stub-" + serial + "-" + version);
		oci.setSize("100");
		oci.setMediaType("application/json");
		OASResponseDto bom = new OASResponseDto();
		bom.setOciResponse(oci);
		bom.setFileSHA256Digest("rebom-stub-" + serial + "-" + version);
		RebomOptions meta = new RebomOptions(null, null, null, null == options ? null : options.belongsTo(), null,
				false, false, null, null, null, null, null, null, null, null, null, null, "urn:uuid:" + serial,
				"rebom-stub-digest-" + serial + "-" + version, null, null, null, null, null, String.valueOf(version),
				null, null);
		return new RebomResponse(UUID.randomUUID(), bom, meta, duplicate);
	}
}
