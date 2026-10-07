/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

	/**
	 * The provider's own API model id, e.g. {@code claude-fable-5-1}. Set from the bundled
	 * catalogue when a string resolves onto a known model, or by an admin; null on a row that
	 * auto-registered from a string nobody has mapped.
	 */
	@JsonProperty
	private String canonicalId;

	/**
	 * Normalised strings that resolve to this row: short names, dated snapshots, and the
	 * provider-qualified forms once their prefix is peeled. Unique within the org across rows --
	 * the service refuses a duplicate rather than letting two rows claim one string.
	 */
	@JsonProperty
	private List<String> aliases = new ArrayList<>();

	/**
	 * What is true of the model regardless of who serves it: {@code contextWindow},
	 * {@code maxOutputTokens}, {@code modalities}, {@code hostingKind}, {@code releaseDate},
	 * {@code deprecatedAt}, {@code knowledgeCutoff}. Free-form because the set grows with what
	 * providers publish; nothing here participates in identity.
	 */
	@JsonProperty
	private ModelFacts facts;

	/**
	 * ReARM-native banding. Admin-set; a later routing floor reads it, which is why it is an enum
	 * rather than a label: a floor comparing against a typo would silently admit every model.
	 */
	@JsonProperty
	private ModelTier tier;

	/**
	 * How capable this model is, on a scale the operator defines; null means unrated.
	 *
	 * <p>Operator-set and never declared by an agent: a session says which model it runs, and what
	 * that model is worth is the organization's judgment, not the caller's claim. A role requires
	 * a number on this scale, so the bundled defaults (Fable 5, Opus 4, Sonnet 3, Haiku 2) matter
	 * only as a starting point an operator is expected to edit.
	 *
	 * <p>Unrated is not weak: it is unknown. A model with no strength is eligible only for roles
	 * that require none, which surfaces an unmapped model at the first poll rather than letting it
	 * take work a role wanted a floor for.
	 */
	@JsonProperty
	private Double strength;

	/**
	 * The kinds of work a model's strength can differ by. A board's roles are free-form, so each
	 * maps to one of these (see {@link AgentTaskRoleConfigData#getStrengthCategory()}); a board
	 * PLANNER can read its models' ARCHITECT strength.
	 */
	public enum RoleCategory { ARCHITECT, CODER, QA, REVIEWER }

	/** A model's strength for one kind of work, overriding {@link #strength} for it. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record RoleStrength(RoleCategory category, Double strength) implements Serializable {}

	/**
	 * Per-category strengths, each overriding the base {@link #strength} for roles of that
	 * category. Empty means the base applies to every role. Operator-set; the bundled catalogue
	 * ships base strengths only, since per-category numbers from us would be guesses.
	 */
	@JsonProperty
	private List<RoleStrength> strengthByRole = new ArrayList<>();

	/** This model's strength for work of {@code category}: its value for it, else the base. */
	public Double strengthFor(RoleCategory category) {
		if (null != category && null != strengthByRole) {
			for (RoleStrength rs : strengthByRole) {
				if (category == rs.category()) return rs.strength();
			}
		}
		return strength;
	}

	/**
	 * Where a model comes from: who built it and whether its weights are open.
	 *
	 * <p>Provenance, not capability. Capability lives in {@link ModelOntologyData#strength}, which
	 * an operator sets and a role requires; a tier that meant "how good" could not also say "whose
	 * weights are these", and the two questions are asked by different people for different
	 * reasons -- routing asks the first, procurement and compliance the second.
	 *
	 * <p>Parsed case-insensitively, like every other catalogue vocabulary. These values are
	 * operator-entered and were a free-text String until recently, so a stored {@code frontier}
	 * would otherwise make the whole ontology row fail to deserialise -- losing the model, not
	 * just its tier.
	 */
	public enum ModelTier {
		/** Closed model from a frontier lab. */
		FRONTIER,
		/** Open weights, as released. */
		OPEN_WEIGHT_STOCK,
		/** Open weights tuned by this organization or a vendor. */
		OPEN_WEIGHT_FINE_TUNED,
		/** Closed model from any other vendor. */
		THIRD_PARTY_PROPRIETARY,
		/** This organization's own closed model. */
		FIRST_PARTY_PROPRIETARY;

		@com.fasterxml.jackson.annotation.JsonCreator
		public static ModelTier fromValue(String v) {
			return null == v ? null : valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
		}
	}

	/**
	 * Dated rates. Never edited in place: a correction is a new entry plus an expiry on the old
	 * one, so a recorded price version always means the same numbers.
	 */
	@JsonProperty
	private List<PricingEntry> pricing = new ArrayList<>();

	/**
	 * {@code RESOLVED} when the row is linked to a bundled catalogue entry or confirmed by an
	 * admin; {@code UNRESOLVED} when it auto-created from a string nobody mapped. Unresolved rows
	 * price nothing and are what the merge candidates surface.
	 */
	@JsonProperty
	private ModelResolution resolution;

	/** Whether a catalogue row is trusted to describe a real model. Case-insensitive on read. */
	/**
	 * RESOLVED: the bundled catalogue knows the model. UNRESOLVED: nobody has mapped it yet. SYNTHETIC:
	 * the one pseudo-model per organization that placeholders ({@code <synthetic>}, blank, unknown)
	 * report against (task RD2-26); never priced, not counted as unresolved.
	 */
	public enum ModelResolution {
		RESOLVED, UNRESOLVED, SYNTHETIC;

		@com.fasterxml.jackson.annotation.JsonCreator
		public static ModelResolution fromValue(String v) {
			return null == v ? null : valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
		}
	}

	/**
	 * Which CycloneDX spec the stored model card is written in. Absent means 1.6: the card
	 * predates the converter.
	 */
	@JsonProperty
	private ModelCardSpecVersion modelCardSpecVersion;

	/**
	 * CycloneDX spec version of a model card.
	 *
	 * <p>{@code @JsonValue} and {@code @JsonCreator} because the wire form is {@code "1.6"} and
	 * {@code "2.0"} -- not valid Java identifiers, and the values already written to stored rows.
	 */
	public enum ModelCardSpecVersion {
		V1_6("1.6"),
		V2_0("2.0");

		private final String wire;

		ModelCardSpecVersion(String wire) { this.wire = wire; }

		@com.fasterxml.jackson.annotation.JsonValue
		public String getWire() { return wire; }

		@com.fasterxml.jackson.annotation.JsonCreator
		public static ModelCardSpecVersion fromWire(String value) {
			if (null == value) return null;
			for (ModelCardSpecVersion v : values()) {
				if (v.wire.equals(value)) return v;
			}
			throw new IllegalArgumentException("Unknown model card spec version: " + value);
		}
	}

	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes;

	/**
	 * The version the last declaration that resolved onto this row gave, as information (task
	 * RD2-26): never identity -- a declaration repeating the version in its name, or a canonical name
	 * with a stray version, lands on the one row, and this records what it said.
	 */
	@JsonProperty
	private String declaredVersion;

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
