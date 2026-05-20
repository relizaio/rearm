/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * Describes one AI/ML model an {@link Agent} runs. Identity =
 * ({@link #org}, case-insensitive {@link #name}, {@link #version}).
 *
 * The {@link #modelCard} field stores the CycloneDX ML-BOM
 * {@code component} object (type {@code machine-learning-model}) for
 * the model — opaque to ReARM, stored as jsonb. See the
 * <a href="https://github.com/CycloneDX/guides/tree/main/ML-BOM/en">
 * CycloneDX ML-BOM guide</a> for the model-card structure. Auto-
 * created on session initialize with an empty {@code modelCard}; the
 * user can attach a fuller card later via
 * {@code setModelOntologyModelCardProgrammatic}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelOntologyData extends RelizaDataParent implements RelizaObject {

	/**
	 * Default version string used on auto-registration when the agent
	 * doesn't supply one. Kept stable so the {@code (org, name, version)}
	 * unique-key lookup is deterministic.
	 */
	public static final String UNKNOWN_VERSION = "unknown";

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/**
	 * Model name, e.g. {@code "claude-sonnet"}. Unique per
	 * {@code (org, version)} (case-insensitive on name).
	 */
	@JsonProperty
	private String name;

	/**
	 * Model version, e.g. {@code "4.5"}. Defaults to
	 * {@link #UNKNOWN_VERSION} when the agent omits it on registration.
	 */
	@JsonProperty
	private String version;

	/**
	 * Publisher / vendor (e.g. {@code "Anthropic"}). Display-only —
	 * does not participate in the natural-key uniqueness.
	 */
	@JsonProperty
	private String publisher;

	/**
	 * One-line summary shown on the dashboard tooltip.
	 */
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;

	/**
	 * Optional canonical id (e.g.
	 * {@code "pkg:huggingface/anthropic/claude-sonnet@4.5"}).
	 */
	@JsonProperty
	private String purl;

	/**
	 * The CycloneDX ML-BOM {@code component} object for the model —
	 * opaque to ReARM, stored as-is. Empty on auto-registration;
	 * filled in by a later {@code setModelOntologyModelCard} call.
	 * See {@link io.reliza.model.ModelOntologyData class javadoc} for
	 * the expected shape.
	 */
	@JsonProperty
	private Map<String, Object> modelCard = new HashMap<>();

	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes;

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static ModelOntologyData dataFromRecord(ModelOntology mo) {
		if (mo.getSchemaVersion() != 0) {
			throw new IllegalStateException("ModelOntology schema version is " + mo.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = mo.getRecordData();
		ModelOntologyData mod = Utils.OM.convertValue(recordData, ModelOntologyData.class);
		mod.setUuid(mo.getUuid());
		mod.setCreatedDate(mo.getCreatedDate());
		return mod;
	}
}
