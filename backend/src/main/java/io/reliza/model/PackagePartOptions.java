/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.ArtifactData.ArtifactType;

/**
 * The options of one part of a regulatory package (tasks TEA-29, TEA-33). Every component is nullable:
 * a null means the part's default, which {@link #filled} supplies; which component applies to which
 * part is {@link #applicableTo}, the one table the settings save validates against. Shared, because
 * the schema carries it; it imports nothing from a Pro package.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackagePartOptions(PackagePartSelection selection, SuppressedFindings suppressedFindings,
		InTriageFindings inTriageFindings, VexDocumentFormat vexFormat, List<ArtifactType> artifactTypes) {

	/** The option fields, each with its name in the GraphQL input. */
	public enum Field {
		SELECTION("selection"),
		SUPPRESSED_FINDINGS("suppressedFindings"),
		IN_TRIAGE_FINDINGS("inTriageFindings"),
		VEX_FORMAT("vexFormat"),
		ARTIFACT_TYPES("artifactTypes");

		private final String inputName;

		Field(String inputName) {
			this.inputName = inputName;
		}

		public String inputName() {
			return inputName;
		}
	}

	/** {@code artifactTypes} is kept as an unmodifiable copy in input order, duplicates dropped. */
	public PackagePartOptions {
		if (null != artifactTypes) {
			artifactTypes = List.copyOf(new LinkedHashSet<>(artifactTypes.stream().filter(Objects::nonNull).toList()));
		}
	}

	/** Every component null: every default applies. */
	public static PackagePartOptions empty() {
		return new PackagePartOptions(null, null, null, null, null);
	}

	/**
	 * The fields that apply to a part: SELECTION to every part; SUPPRESSED_FINDINGS to VDR and VEX;
	 * IN_TRIAGE_FINDINGS and VEX_FORMAT to VEX; ARTIFACT_TYPES to RAW_ARTIFACTS.
	 */
	public static Set<Field> applicableTo(PackagePart part) {
		Set<Field> out = EnumSet.of(Field.SELECTION);
		if (null == part) return out;
		switch (part) {
			case VDR -> out.add(Field.SUPPRESSED_FINDINGS);
			case VEX -> {
				out.add(Field.SUPPRESSED_FINDINGS);
				out.add(Field.IN_TRIAGE_FINDINGS);
				out.add(Field.VEX_FORMAT);
			}
			case RAW_ARTIFACTS -> out.add(Field.ARTIFACT_TYPES);
			default -> {
				// SELECTION only
			}
		}
		return out;
	}

	/** The non-null components, in field order. */
	public Set<Field> setFields() {
		Set<Field> out = EnumSet.noneOf(Field.class);
		if (null != selection) out.add(Field.SELECTION);
		if (null != suppressedFindings) out.add(Field.SUPPRESSED_FINDINGS);
		if (null != inTriageFindings) out.add(Field.IN_TRIAGE_FINDINGS);
		if (null != vexFormat) out.add(Field.VEX_FORMAT);
		if (null != artifactTypes) out.add(Field.ARTIFACT_TYPES);
		return out;
	}

	/**
	 * Every null replaced by its default: the selection by {@code defaultSelection}, suppressed and
	 * in-triage findings EXCLUDE, the VEX format CYCLONEDX_VEX, the artifact types an empty list.
	 */
	public PackagePartOptions filled(PackagePartSelection defaultSelection) {
		return new PackagePartOptions(null != selection ? selection : defaultSelection,
				null != suppressedFindings ? suppressedFindings : SuppressedFindings.EXCLUDE,
				null != inTriageFindings ? inTriageFindings : InTriageFindings.EXCLUDE,
				null != vexFormat ? vexFormat : VexDocumentFormat.CYCLONEDX_VEX,
				null != artifactTypes ? artifactTypes : List.of());
	}
}
