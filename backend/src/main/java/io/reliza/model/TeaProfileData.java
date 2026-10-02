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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.RebomService.BomStructureType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A TEA profile (task TEA-2): the wholesale set of settings that says whether, and how, the
 * releases resolving to a scope are published on the Transparency Exchange API. A lower scope
 * replaces its parent as a whole, never per field; {@code TeaProfileResolver} decides which row a
 * component resolves to.
 *
 * <p>Every setting is an enum or a set of enums, never a boolean: each domain can grow a value
 * without every reader growing a ternary.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeaProfileData extends RelizaDataParent implements RelizaObject {

	public enum TeaProfileScope { ORGANIZATION, PERSPECTIVE, COMPONENT }

	/** What a COMPONENT-scope row does: carry its own profile, or follow one perspective's. */
	public enum TeaComponentProfileMode { OVERRIDE, FOLLOW_PERSPECTIVE }

	/** DISABLED at any scope conceals everything resolving there and blocks new publishes. */
	public enum TeaPublishing { ENABLED, DISABLED }

	/** Resolved at request time: switching a profile changes who can read what is already published. */
	public enum TeaVisibility { PRIVATE, PUBLIC }

	public enum TeaDependencyDepth { FULL, TOP_LEVEL_ONLY }

	public enum TeaOptionalDependencies { INCLUDE, EXCLUDE }

	public enum TeaSbomSource { DELIVERABLE, RELEASE, SOURCE_CODE }

	public enum TeaSupportMetadata { INCLUDE, EXCLUDE }

	public enum TeaInternalMetadata { INCLUDE, EXCLUDE }

	/** Which existing release artifacts ride along with a publication (part 4). */
	public enum TeaRawArtifacts { NONE, NON_BOM, ALL }

	/** Read only from the profile a product cascade starts from (part 4). */
	public enum TeaProductComponents { PUBLISH_WITH_PRODUCT, PRODUCT_ONLY }

	/** UUID stamps tei://&lt;teiDomain&gt;/uuid/&lt;TEA release uuid&gt; onto the release identifiers at publish (part 4). */
	public enum TeaTei { DISABLED, UUID }

	/** Reserved: vulnerability documents are not published yet. */
	public enum TeaVulnerabilityDocuments { NONE }

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private TeaProfileScope scope;
	@JsonProperty
	private UUID object;

	/** COMPONENT scope only; null elsewhere. */
	@JsonProperty
	private TeaComponentProfileMode mode;
	/** FOLLOW_PERSPECTIVE only: the perspective whose row this component follows. */
	@JsonProperty
	private UUID followedPerspective;

	@JsonProperty
	private TeaPublishing publishing;
	@JsonProperty
	private TeaVisibility visibility;
	@JsonProperty
	private TeaDependencyDepth dependencyDepth;
	@JsonProperty
	private TeaOptionalDependencies optionalDependencies;
	@JsonProperty
	private BomStructureType structure;
	@JsonProperty
	private Set<TeaSbomSource> sources;
	@JsonProperty
	private Set<ArtifactCoverageType> excludedCoverage;
	@JsonProperty
	private TeaSupportMetadata supportMetadata;
	@JsonProperty
	private TeaInternalMetadata internalMetadata;
	@JsonProperty
	private TeaRawArtifacts rawArtifacts;
	@JsonProperty
	private TeaProductComponents productComponents;
	@JsonProperty
	private ReleaseLifecycle minimumLifecycle;
	@JsonProperty
	private TeaTei tei;
	@JsonProperty
	private String teiDomain;
	@JsonProperty
	private TeaVulnerabilityDocuments vulnerabilityDocuments;

	/** The row's revision, from the entity; not part of the record. */
	@JsonIgnore
	private int revision;

	/** Kept in enum order so every reader, the API included, sees one stable order. */
	public void setSources(Set<TeaSbomSource> sources) {
		this.sources = null == sources ? null
				: (sources.isEmpty() ? EnumSet.noneOf(TeaSbomSource.class) : EnumSet.copyOf(sources));
	}

	public void setExcludedCoverage(Set<ArtifactCoverageType> excludedCoverage) {
		this.excludedCoverage = null == excludedCoverage ? null
				: (excludedCoverage.isEmpty() ? EnumSet.noneOf(ArtifactCoverageType.class)
						: EnumSet.copyOf(excludedCoverage));
	}

	/**
	 * The profile every organization has before it saves one: the decided defaults, scope
	 * ORGANIZATION, no uuid and no object.
	 */
	public static TeaProfileData builtInDefault(UUID org) {
		TeaProfileData d = new TeaProfileData();
		d.setOrg(org);
		d.setScope(TeaProfileScope.ORGANIZATION);
		d.setPublishing(TeaPublishing.DISABLED);
		d.setVisibility(TeaVisibility.PRIVATE);
		d.setDependencyDepth(TeaDependencyDepth.FULL);
		d.setOptionalDependencies(TeaOptionalDependencies.INCLUDE);
		d.setStructure(BomStructureType.FLAT);
		d.setSources(EnumSet.allOf(TeaSbomSource.class));
		d.setExcludedCoverage(EnumSet.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST));
		d.setSupportMetadata(TeaSupportMetadata.EXCLUDE);
		d.setInternalMetadata(TeaInternalMetadata.EXCLUDE);
		d.setRawArtifacts(TeaRawArtifacts.NONE);
		d.setProductComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		d.setMinimumLifecycle(ReleaseLifecycle.ASSEMBLED);
		d.setTei(TeaTei.DISABLED);
		d.setVulnerabilityDocuments(TeaVulnerabilityDocuments.NONE);
		return d;
	}

	/**
	 * The merge path's belongsTo filter. SOURCE_CODE is SCE there; the full set means no filter
	 * and answers the empty set. Today's {@code RebomOptions.belongsTo} holds one value, so only
	 * the full set and a singleton map onto it; a two-element subset needs the set filter part 4
	 * adds to {@code collectOwnBomIds}.
	 */
	public Set<ArtifactBelongsTo> belongsToFilter() {
		Set<ArtifactBelongsTo> out = EnumSet.noneOf(ArtifactBelongsTo.class);
		if (null == sources || sources.containsAll(EnumSet.allOf(TeaSbomSource.class))) return out;
		for (TeaSbomSource s : sources) {
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
		return TeaDependencyDepth.TOP_LEVEL_ONLY == dependencyDepth;
	}

	/** {@code ignoreDev} of the merge path. */
	public boolean ignoreDev() {
		return TeaOptionalDependencies.EXCLUDE == optionalDependencies;
	}

	/** {@code excludeCoverageTypes} of the merge path, in enum order. */
	public List<ArtifactCoverageType> excludeCoverageTypes() {
		return null == excludedCoverage ? new LinkedList<>() : new LinkedList<>(excludedCoverage);
	}

	/** A detached copy, so a reader may normalize the effective value without touching what is stored. */
	public TeaProfileData copy() {
		TeaProfileData c = Utils.OM.convertValue(toRecordData(), TeaProfileData.class);
		c.setRevision(revision);
		return c;
	}

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}

	public static TeaProfileData dataFromRecord(TeaProfile p) {
		if (p.getSchemaVersion() != 0) {
			throw new IllegalStateException("TEA profile schema version is " + p.getSchemaVersion()
					+ ", which is not currently supported");
		}
		TeaProfileData d = Utils.OM.convertValue(p.getRecordData(), TeaProfileData.class);
		d.setUuid(p.getUuid());
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
