/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.model.tea.publication.TeaArtifactDocument;
import io.reliza.model.tea.publication.TeaChecksumDocument;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * One immutable TEA collection version (task TEA-5): the frozen entries beside the canonical
 * document they were rendered into. Keeping the parsed entry fields next to the frozen artifact
 * object is what lets the page and the change classifier work without re-parsing the document.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeaPublishedCollectionData extends RelizaDataParent implements RelizaObject {

	/** Where a published TEA artifact came from: an uploaded release artifact, or one ReARM generated. */
	public enum TeaArtifactOrigin { REAL, SYNTHETIC }

	/**
	 * One frozen artifact: the artifact row, its TEA id and TEA revision, its origin, and the exact
	 * TEA artifact object as it stands in the document. {@code sbomProfileName}, {@code sbomProfileUuid}
	 * and {@code sbomProfileRevision} name the SBOM profile a generated revision was shaped by (task
	 * TEA-28: the resolved row's name, uuid and revision; a null uuid and revision 0 for the built-in
	 * default); null on an uploaded artifact, and uuid and revision null on a revision published before
	 * TEA-28. They are not the collection's {@code profileRevision}, which is the TEA settings row's.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TeaPublishedEntry(UUID artifact, UUID teaUuid, int revision, TeaArtifactOrigin origin,
			TeaArtifactType teaType, String name, String mediaType, List<TeaChecksumDocument> checksums, String url,
			UUID signatureArtifact, Integer signatureRevision, TeaArtifactDocument frozen, String sbomProfileName,
			UUID sbomProfileUuid, Integer sbomProfileRevision) implements Serializable {

		/** An entry with no SBOM profile: every uploaded artifact, and the fixtures written before TEA-28. */
		public TeaPublishedEntry(UUID artifact, UUID teaUuid, int revision, TeaArtifactOrigin origin,
				TeaArtifactType teaType, String name, String mediaType, List<TeaChecksumDocument> checksums, String url,
				UUID signatureArtifact, Integer signatureRevision, TeaArtifactDocument frozen) {
			this(artifact, teaUuid, revision, origin, teaType, name, mediaType, checksums, url, signatureArtifact,
					signatureRevision, frozen, null, null, null);
		}

		/** The GraphQL {@code type} field. */
		@JsonIgnore
		public TeaArtifactType type() {
			return teaType;
		}
	}

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private int version;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID release;
	@JsonProperty
	private TeaCollectionBelongsToType belongsTo;
	@JsonProperty
	private TeaCollectionUpdateReasonType updateReasonType;
	@JsonProperty
	private String updateReasonComment;
	/** The profile revision applied to this version. */
	@JsonProperty
	private int profileRevision;
	/** The frozen artifacts, in document order. */
	@JsonProperty
	private List<TeaPublishedEntry> entries = new ArrayList<>();

	/** From the columns; not part of the record. */
	@JsonIgnore
	private String documentSha256;
	@JsonIgnore
	private UUID createdBy;
	@JsonIgnore
	private String document;

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}

	public static TeaPublishedCollectionData dataFromRecord(TeaPublishedCollection c) {
		if (c.getSchemaVersion() != 0) {
			throw new IllegalStateException("TEA collection schema version is " + c.getSchemaVersion()
					+ ", which is not currently supported");
		}
		TeaPublishedCollectionData d = Utils.OM.convertValue(c.getRecordData(), TeaPublishedCollectionData.class);
		d.setUuid(c.getUuid());
		d.setVersion(c.getVersion());
		d.setOrg(c.getOrg());
		d.setRelease(c.getRelease());
		d.setCreatedDate(c.getCreatedDate());
		d.setCreatedBy(c.getCreatedBy());
		d.setDocumentSha256(c.getDocumentSha256());
		d.setDocument(c.getDocument());
		return d;
	}

	@Override
	@JsonIgnore
	public UUID getResourceGroup() {
		return null;
	}
}
