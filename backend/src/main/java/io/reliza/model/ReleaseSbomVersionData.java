/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.reliza.model.ReleaseSbomWriter.Kind;
import lombok.Data;

/**
 * One immutable release SBOM version as the service reads it (FDAW-1 D1). The record carries who
 * wrote it and with what recipe (FDAW-7 3.2, functional F-4): {@code writer}, {@code recipe} (the SBOM
 * profile uuid, null for the built-in default) and {@code recipeRevision}; a version stored without
 * them reads them as null.
 */
@Data
public class ReleaseSbomVersionData {

	public static final String WRITER_KEY = "writer";
	public static final String RECIPE_KEY = "recipe";
	public static final String RECIPE_REVISION_KEY = "recipeRevision";

	private UUID uuid;
	private int version;
	private UUID org;
	private UUID release;
	private String profileName;
	private UUID artifact;
	private String sha256;
	private String contentDigest;
	private ZonedDateTime createdDate;
	private UUID createdBy;
	private Kind writer;
	private UUID recipe;
	private Integer recipeRevision;

	/** The pin this version is. */
	public ReleaseSbomRef toRef() {
		return new ReleaseSbomRef(uuid, version, sha256, artifact);
	}

	/** The JSONB record: the three writer keys, a null one absent. */
	public Map<String, Object> toRecordData() {
		Map<String, Object> m = new LinkedHashMap<>();
		if (null != writer) m.put(WRITER_KEY, writer.name());
		if (null != recipe) m.put(RECIPE_KEY, recipe.toString());
		if (null != recipeRevision) m.put(RECIPE_REVISION_KEY, recipeRevision);
		return m;
	}

	public static ReleaseSbomVersionData dataFromRecord(ReleaseSbomVersion v) {
		if (v.getSchemaVersion() != 0) {
			throw new IllegalStateException("release SBOM version schema version is " + v.getSchemaVersion()
					+ ", which is not currently supported");
		}
		ReleaseSbomVersionData d = new ReleaseSbomVersionData();
		d.setUuid(v.getUuid());
		d.setVersion(v.getVersion());
		d.setOrg(v.getOrg());
		d.setRelease(v.getRelease());
		d.setProfileName(v.getProfileName());
		d.setArtifact(v.getArtifact());
		d.setSha256(v.getSha256());
		d.setContentDigest(v.getContentDigest());
		d.setCreatedDate(v.getCreatedDate());
		d.setCreatedBy(v.getCreatedBy());
		Map<String, Object> rd = null == v.getRecordData() ? Map.of() : v.getRecordData();
		Object w = rd.get(WRITER_KEY);
		if (null != w) d.setWriter(Kind.valueOf(w.toString()));
		Object r = rd.get(RECIPE_KEY);
		if (null != r) d.setRecipe(UUID.fromString(r.toString()));
		Object rr = rd.get(RECIPE_REVISION_KEY);
		if (rr instanceof Number n) d.setRecipeRevision(n.intValue());
		return d;
	}
}
