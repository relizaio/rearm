/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.service.RebomService.BomStructureType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A named SBOM profile (task TEA-27): the recipe of an aggregated SBOM at a scope. Channels (TEA
 * publishing now, regulatory packages later) name profiles; a lower scope overrides a higher one by
 * defining the same name, and a scope may define a name no higher scope knows. A profile is
 * channel-independent: it carries no visibility, no TEI, no cascade and no publishing switch.
 *
 * <p>Every setting is an enum or a set of enums, never a boolean. The recipe enums and the four
 * merge adapters moved here from the TEA profile of TEA-2, constants and behaviour unchanged.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SbomProfileData extends RelizaDataParent implements RelizaObject {

	/** The name a TEA row names by default and every organization resolves before its first save. */
	public static final String DEFAULT_NAME = "default";

	/** ASCII, no whitespace, at most 64 characters, compared exactly (case-sensitive). */
	public static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

	/** The most a description may hold; it is stored and returned only. */
	public static final int MAX_DESCRIPTION_LENGTH = 1024;

	public enum SbomDependencyDepth { FULL, TOP_LEVEL_ONLY }

	public enum SbomOptionalDependencies { INCLUDE, EXCLUDE }

	public enum SbomSourceKind { DELIVERABLE, RELEASE, SOURCE_CODE }

	public enum SbomSupportMetadata { INCLUDE, EXCLUDE }

	public enum SbomInternalMetadata { INCLUDE, EXCLUDE }

	/** Reserved: vulnerability documents are not part of a profile yet. */
	public enum SbomVulnerabilityDocuments { NONE }

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private SettingsScope scope;
	@JsonProperty
	private UUID object;
	@JsonProperty
	private String name;

	/** COMPONENT scope only; null elsewhere. */
	@JsonProperty
	private ScopedSettingsMode mode;
	/** FOLLOW_PERSPECTIVE only: the perspective whose row of the same name this component follows. */
	@JsonProperty
	private UUID followedPerspective;

	@JsonProperty
	private String description;
	@JsonProperty
	private SbomDependencyDepth dependencyDepth;
	@JsonProperty
	private SbomOptionalDependencies optionalDependencies;
	@JsonProperty
	private BomStructureType structure;
	@JsonProperty
	private Set<SbomSourceKind> sources;
	@JsonProperty
	private Set<ArtifactCoverageType> excludedCoverage;
	@JsonProperty
	private SbomSupportMetadata supportMetadata;
	@JsonProperty
	private SbomInternalMetadata internalMetadata;
	@JsonProperty
	private SbomVulnerabilityDocuments vulnerabilityDocuments;

	/** The row's revision, from the entity; not part of the record. */
	@JsonIgnore
	private int revision;

	/** Kept in enum order so every reader, the API included, sees one stable order. */
	public void setSources(Set<SbomSourceKind> sources) {
		this.sources = null == sources ? null
				: (sources.isEmpty() ? EnumSet.noneOf(SbomSourceKind.class) : EnumSet.copyOf(sources));
	}

	public void setExcludedCoverage(Set<ArtifactCoverageType> excludedCoverage) {
		this.excludedCoverage = null == excludedCoverage ? null
				: (excludedCoverage.isEmpty() ? EnumSet.noneOf(ArtifactCoverageType.class)
						: EnumSet.copyOf(excludedCoverage));
	}

	/**
	 * The built-in {@code default} profile: scope ORGANIZATION, no uuid, no object, the decided
	 * defaults (the recipe half of the TEA-2 built-in TEA profile). It answers the name
	 * {@code default} at every scope where no row defines it; no other name has a built-in.
	 */
	public static SbomProfileData builtInDefault(UUID org) {
		SbomProfileData d = new SbomProfileData();
		d.setOrg(org);
		d.setScope(SettingsScope.ORGANIZATION);
		d.setName(DEFAULT_NAME);
		d.setDescription("Built-in defaults");
		d.setDependencyDepth(SbomDependencyDepth.FULL);
		d.setOptionalDependencies(SbomOptionalDependencies.INCLUDE);
		d.setStructure(BomStructureType.FLAT);
		d.setSources(EnumSet.allOf(SbomSourceKind.class));
		d.setExcludedCoverage(EnumSet.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST));
		d.setSupportMetadata(SbomSupportMetadata.EXCLUDE);
		d.setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		d.setVulnerabilityDocuments(SbomVulnerabilityDocuments.NONE);
		return d;
	}

	/** Whether {@code name} is a valid profile name: {@link #NAME_PATTERN}, compared exactly. */
	public static boolean isValidName(String name) {
		return null != name && NAME_PATTERN.matcher(name).matches();
	}

	/**
	 * The merge path's belongsTo filter. SOURCE_CODE is SCE there; the full set means no filter
	 * and answers the empty set. Today's {@code RebomOptions.belongsTo} holds one value, so only
	 * the full set and a singleton map onto it; a two-element subset needs the set filter part 4
	 * adds to {@code collectOwnBomIds}.
	 */
	public Set<ArtifactBelongsTo> belongsToFilter() {
		Set<ArtifactBelongsTo> out = EnumSet.noneOf(ArtifactBelongsTo.class);
		if (null == sources || sources.containsAll(EnumSet.allOf(SbomSourceKind.class))) return out;
		for (SbomSourceKind s : sources) {
			out.add(switch (s) {
				case DELIVERABLE -> ArtifactBelongsTo.DELIVERABLE;
				case RELEASE -> ArtifactBelongsTo.RELEASE;
				case SOURCE_CODE -> ArtifactBelongsTo.SCE;
			});
		}
		return out;
	}

	/** {@code tldOnly} of the merge path. */
	public boolean tldOnly() {
		return SbomDependencyDepth.TOP_LEVEL_ONLY == dependencyDepth;
	}

	/** {@code ignoreDev} of the merge path. */
	public boolean ignoreDev() {
		return SbomOptionalDependencies.EXCLUDE == optionalDependencies;
	}

	/** {@code excludeCoverageTypes} of the merge path, in enum order. */
	public List<ArtifactCoverageType> excludeCoverageTypes() {
		return null == excludedCoverage ? new LinkedList<>() : new LinkedList<>(excludedCoverage);
	}

	/** A detached copy, so a reader may normalize the effective value without touching what is stored. */
	public SbomProfileData copy() {
		SbomProfileData c = Utils.OM.convertValue(toRecordData(), SbomProfileData.class);
		c.setRevision(revision);
		return c;
	}

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}

	/**
	 * The row as data. The key ({@code uuid}, {@code org}, {@code scope}, {@code object},
	 * {@code name}), the revision and the created date come from the entity's columns, overriding
	 * whatever the record carries: the columns are the key and the record mirrors them, so a row
	 * never reads back keyless, seeded by a migration or saved (TEA-27 F-2).
	 */
	public static SbomProfileData dataFromRecord(SbomProfile p) {
		if (p.getSchemaVersion() != 0) {
			throw new IllegalStateException("SBOM profile schema version is " + p.getSchemaVersion()
					+ ", which is not currently supported");
		}
		SbomProfileData d = Utils.OM.convertValue(p.getRecordData(), SbomProfileData.class);
		d.setUuid(p.getUuid());
		d.setOrg(p.getOrg());
		d.setScope(p.getScope());
		d.setObject(p.getObjectUuid());
		d.setName(p.getName());
		d.setRevision(p.getRevision());
		d.setCreatedDate(p.getCreatedDate());
		return d;
	}

	@Override
	@JsonIgnore
	public UUID getResourceGroup() {
		return null;
	}
}
