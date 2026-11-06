/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.UUID;

/**
 * Who calls {@code ReleaseSbomService.ensureVersion} and with what recipe (FDAW-7 3.2): a TEA
 * publication (with its publication uuid) or a package act (with none). {@code recipe} is the uuid of
 * the SBOM profile the content was merged with, null for the built-in default, and
 * {@code recipeRevision} its revision (null with a null recipe). The identity service builds every
 * upload's tags from it; a caller never passes a tag.
 */
public record ReleaseSbomWriter(Kind kind, UUID publication, UUID recipe, Integer recipeRevision) {

	public enum Kind { TEA, PACKAGE }

	public ReleaseSbomWriter {
		if (null == kind) throw new IllegalArgumentException("a release SBOM writer needs a kind");
		if (Kind.TEA == kind && null == publication) {
			throw new IllegalArgumentException("a TEA writer needs the publication uuid");
		}
		if (Kind.PACKAGE == kind && null != publication) {
			throw new IllegalArgumentException("a PACKAGE writer carries no publication uuid");
		}
		if (null == recipe) recipeRevision = null;
	}

	public static ReleaseSbomWriter tea(UUID publication, SbomProfileData recipe) {
		return new ReleaseSbomWriter(Kind.TEA, publication, null == recipe ? null : recipe.getUuid(),
				null == recipe || null == recipe.getUuid() ? null : recipe.getRevision());
	}

	public static ReleaseSbomWriter pkg(SbomProfileData recipe) {
		return new ReleaseSbomWriter(Kind.PACKAGE, null, null == recipe ? null : recipe.getUuid(),
				null == recipe || null == recipe.getUuid() ? null : recipe.getRevision());
	}
}
