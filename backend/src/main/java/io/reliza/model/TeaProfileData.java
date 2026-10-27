/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The TEA publishing settings of a scope (a TEA profile, task TEA-2; trimmed by task TEA-27): the
 * channel fields and the names of the SBOM profiles it publishes; a recipe lives in an SBOM profile
 * ({@link SbomProfileData}). A lower scope replaces its parent as a whole, never per field;
 * {@code TeaProfileResolver} decides which row a component resolves to.
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

	/** Which existing release artifacts ride along with a publication (part 4). */
	public enum TeaRawArtifacts { NONE, NON_BOM, ALL }

	/** Read only from the profile a product cascade starts from (part 4). */
	public enum TeaProductComponents { PUBLISH_WITH_PRODUCT, PRODUCT_ONLY }

	/** UUID stamps tei://&lt;teiDomain&gt;/uuid/&lt;TEA release uuid&gt; onto the release identifiers at publish (part 4). */
	public enum TeaTei { DISABLED, UUID }

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
	private TeaRawArtifacts rawArtifacts;
	@JsonProperty
	private TeaProductComponents productComponents;
	@JsonProperty
	private ReleaseLifecycle minimumLifecycle;
	@JsonProperty
	private TeaTei tei;
	@JsonProperty
	private String teiDomain;
	/**
	 * The names of the SBOM profiles this row publishes, in the order given: non-empty on every
	 * OVERRIDE, PERSPECTIVE and ORGANIZATION row, null on a FOLLOW_PERSPECTIVE row.
	 */
	@JsonProperty
	private List<String> profiles;

	/** The row's revision, from the entity; not part of the record. */
	@JsonIgnore
	private int revision;

	/** A detached list, order kept, so no caller shares the row's own list. */
	public void setProfiles(List<String> profiles) {
		this.profiles = null == profiles ? null : new ArrayList<>(profiles);
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
		d.setRawArtifacts(TeaRawArtifacts.NONE);
		d.setProductComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		d.setMinimumLifecycle(ReleaseLifecycle.ASSEMBLED);
		d.setTei(TeaTei.DISABLED);
		d.setProfiles(List.of(SbomProfileData.DEFAULT_NAME));
		return d;
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
