/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.reliza.common.Utils;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.model.tea.publication.TeaArtifactDocument;
import io.reliza.model.tea.publication.TeaCollectionDocument;
import io.reliza.model.tea.publication.TeaUpdateReasonDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The pure parts of a TEA publication (task TEA-5): the content digest that decides whether an
 * aggregate changed, the classification of one collection version against the previous one, the
 * update reason and its comment, and the canonical collection document with its sha256. No state,
 * no Spring: every method is a function of its arguments.
 */
public final class TeaCollectionAssembler {

	private TeaCollectionAssembler() {}

	/** Writes JSON with every object's properties sorted, so property order never moves a digest. */
	private static final ObjectWriter SORTED_WRITER = Utils.OM.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED);

	/**
	 * The digest of an aggregate's content: a deep copy without {@code serialNumber},
	 * {@code version} and {@code metadata.timestamp}, the three fields every upload rewrites,
	 * written with properties sorted and hashed with sha256 (lowercase hex). Equal digests mean
	 * the content did not change.
	 */
	public static String contentDigest(JsonNode bom) {
		JsonNode copy = bom.deepCopy();
		if (copy instanceof ObjectNode on) {
			on.remove("serialNumber");
			on.remove("version");
			JsonNode metadata = on.get("metadata");
			if (metadata instanceof ObjectNode mn) mn.remove("timestamp");
		}
		byte[] bytes = SORTED_WRITER.writeValueAsBytes(copy);
		return Utils.bytesToHexSha256(bytes);
	}

	/**
	 * The comment rebom writes on the external reference that points a processed document back at
	 * the document it was derived from (rebom-backend bomProcessingService,
	 * PRODUCER_BOM_REFERENCE_COMMENT). On a merged document it names the merge's own internal
	 * serial, which every re-merge mints afresh.
	 */
	public static final String REBOM_PRODUCER_LINK_COMMENT = "Source document as uploaded by the producer";

	/**
	 * Removes rebom's producer BOM-Link from a merged document before it is frozen: the link names an
	 * internal rebom document no TEA client can resolve, and it changes on every merge, so keeping it
	 * would make every re-publish a new revision. Other external references stay. Mutates and returns
	 * {@code bom}.
	 */
	public static JsonNode stripRebomProducerLink(JsonNode bom) {
		if (!(bom instanceof ObjectNode on)) return bom;
		JsonNode refs = on.get("externalReferences");
		if (!(refs instanceof ArrayNode arr)) return bom;
		for (int i = arr.size() - 1; i >= 0; i--) {
			JsonNode r = arr.get(i);
			if (null != r && "bom".equals(r.path("type").asString(null))
					&& REBOM_PRODUCER_LINK_COMMENT.equals(r.path("comment").asString(null))) {
				arr.remove(i);
			}
		}
		if (arr.isEmpty()) on.remove("externalReferences");
		return bom;
	}

	/** An entry present in both versions whose TEA revision moved. */
	public record Revised(TeaPublishedEntry before, TeaPublishedEntry after) {}

	/**
	 * What changed between two entry lists, keyed by TEA id: added, removed, and revised (present in
	 * both with a different revision). {@code first} is true when there was no previous version.
	 */
	public record Changes(boolean first, List<TeaPublishedEntry> added, List<TeaPublishedEntry> removed,
			List<Revised> revised) {

		public boolean isEmpty() {
			return !first && added.isEmpty() && removed.isEmpty() && revised.isEmpty();
		}
	}

	/** Classifies {@code entries} against {@code previous}; a null {@code previous} is the first version. */
	public static Changes classify(List<TeaPublishedEntry> previous, List<TeaPublishedEntry> entries) {
		if (null == previous) return new Changes(true, List.copyOf(entries), List.of(), List.of());
		Map<UUID, TeaPublishedEntry> before = byTeaUuid(previous);
		Map<UUID, TeaPublishedEntry> after = byTeaUuid(entries);
		List<TeaPublishedEntry> added = new ArrayList<>();
		List<Revised> revised = new ArrayList<>();
		for (TeaPublishedEntry e : entries) {
			TeaPublishedEntry old = before.get(e.teaUuid());
			if (null == old) {
				added.add(e);
			} else if (old.revision() != e.revision()) {
				revised.add(new Revised(old, e));
			}
		}
		List<TeaPublishedEntry> removed = previous.stream().filter(e -> !after.containsKey(e.teaUuid())).toList();
		return new Changes(false, added, removed, revised);
	}

	private static Map<UUID, TeaPublishedEntry> byTeaUuid(List<TeaPublishedEntry> entries) {
		Map<UUID, TeaPublishedEntry> m = new LinkedHashMap<>();
		entries.forEach(e -> m.putIfAbsent(e.teaUuid(), e));
		return m;
	}

	/**
	 * The update reason of a version: INITIAL_RELEASE for the first, else the first applicable of
	 * VEX_UPDATED (an added or revised VULNERABILITIES entry), ARTIFACT_REMOVED, ARTIFACT_ADDED,
	 * ARTIFACT_UPDATED, the precedence rule of the spec's collection-update-reason-type. Empty when
	 * nothing changed.
	 */
	public static Optional<TeaCollectionUpdateReasonType> updateReason(Changes c) {
		if (c.first()) return Optional.of(TeaCollectionUpdateReasonType.INITIAL_RELEASE);
		boolean vex = c.added().stream().anyMatch(e -> TeaArtifactType.VULNERABILITIES == e.teaType())
				|| c.revised().stream().anyMatch(r -> TeaArtifactType.VULNERABILITIES == r.after().teaType());
		if (vex) return Optional.of(TeaCollectionUpdateReasonType.VEX_UPDATED);
		if (!c.removed().isEmpty()) return Optional.of(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED);
		if (!c.added().isEmpty()) return Optional.of(TeaCollectionUpdateReasonType.ARTIFACT_ADDED);
		if (!c.revised().isEmpty()) return Optional.of(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED);
		return Optional.empty();
	}

	/**
	 * The ASCII comment listing every change, then the caller's comment when given: "aggregate
	 * SBOM default regenerated: revision 1 -> 2; added: name (tea uuid); removed: ...; revised: ...".
	 */
	public static String updateComment(Changes c, String callerComment) {
		List<String> parts = new ArrayList<>();
		if (c.first()) {
			parts.add("initial publication: " + c.added().size() + (c.added().size() == 1 ? " artifact" : " artifacts"));
		} else {
			for (Revised r : c.revised()) {
				if (TeaArtifactOrigin.SYNTHETIC == r.after().origin()) {
					// task TEA-28: the revised aggregate's name, the profile name for every revision created since
					String name = null == r.after().name() ? "" : r.after().name() + " ";
					parts.add("aggregate SBOM " + name + "regenerated: revision " + r.before().revision() + " -> "
							+ r.after().revision());
				}
			}
			List<Revised> realRevised = c.revised().stream().filter(r -> TeaArtifactOrigin.SYNTHETIC != r.after().origin())
					.toList();
			if (!c.added().isEmpty()) parts.add("added: " + c.added().stream().map(TeaCollectionAssembler::label)
					.collect(Collectors.joining(", ")));
			if (!c.removed().isEmpty()) parts.add("removed: " + c.removed().stream().map(TeaCollectionAssembler::label)
					.collect(Collectors.joining(", ")));
			if (!realRevised.isEmpty()) parts.add("revised: " + realRevised.stream()
					.map(r -> label(r.after()) + " revision " + r.before().revision() + " -> " + r.after().revision())
					.collect(Collectors.joining(", ")));
		}
		if (StringUtils.isNotBlank(callerComment)) parts.add(callerComment.strip());
		return asciiOnly(String.join("; ", parts));
	}

	private static String label(TeaPublishedEntry e) {
		return (null == e.name() ? "artifact" : e.name()) + " (" + e.teaUuid() + ")";
	}

	/** Non-ASCII characters become '?', so the stored comment is one ASCII line. */
	static String asciiOnly(String s) {
		if (null == s) return null;
		StringBuilder b = new StringBuilder(s.length());
		s.codePoints().forEach(cp -> b.append(cp >= 0x20 && cp < 0x7f ? (char) cp : (cp == '\n' || cp == '\r' || cp == '\t' ? ' ' : '?')));
		return b.toString();
	}

	/** The canonical collection document of one version, serialized once. */
	public static String document(UUID publication, int version, String createdDate, TeaCollectionBelongsToType belongsTo,
			TeaCollectionUpdateReasonType reason, String comment, List<TeaPublishedEntry> entries) {
		List<TeaArtifactDocument> artifacts = entries.stream().map(TeaPublishedEntry::frozen).toList();
		TeaCollectionDocument doc = new TeaCollectionDocument(publication, version, createdDate, belongsTo,
				new TeaUpdateReasonDocument(reason, StringUtils.isEmpty(comment) ? null : comment), artifacts);
		return Utils.OM.writeValueAsString(doc);
	}

	/** The sha256 of a stored document, lowercase hex; the ETag part 5 serves. */
	public static String sha256(String document) {
		return Utils.bytesToHexSha256(document.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Entries as they go into a new version: an entry whose TEA id and revision stand unchanged in
	 * the previous version keeps that version's frozen entry verbatim, so an artifact object never
	 * changes for a revision it was already published at (a row's updatedDate, which the fresh
	 * entry's createdDate is read from, moves on saves that are no content change).
	 */
	public static List<TeaPublishedEntry> carryForward(List<TeaPublishedEntry> previous, List<TeaPublishedEntry> entries) {
		return carryForward(previous, entries, e -> Optional.empty());
	}

	/**
	 * {@link #carryForward(List, List)} with the history carry (task TEA-28, D13): an entry the previous
	 * version carries at the same revision is taken from it and {@code history} is not asked; every other
	 * entry (its TEA id absent from {@code previous}, or present at another revision) is asked of
	 * {@code history} once, and a hit, the frozen entry of an earlier version naming that TEA id and
	 * revision, replaces the fresh one verbatim. A null {@code previous} is a first version: the entries
	 * as given, nothing asked.
	 */
	public static List<TeaPublishedEntry> carryForward(List<TeaPublishedEntry> previous, List<TeaPublishedEntry> entries,
			Function<TeaPublishedEntry, Optional<TeaPublishedEntry>> history) {
		if (null == previous) return entries;
		Map<UUID, TeaPublishedEntry> before = byTeaUuid(previous);
		List<TeaPublishedEntry> out = new ArrayList<>(entries.size());
		for (TeaPublishedEntry e : entries) {
			TeaPublishedEntry old = before.get(e.teaUuid());
			if (null != old && old.revision() == e.revision()) {
				out.add(old);
			} else {
				out.add(history.apply(e).orElse(e));
			}
		}
		return out;
	}
}
