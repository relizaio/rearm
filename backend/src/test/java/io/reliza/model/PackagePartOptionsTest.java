/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.PackagePartOptions.Field;

/** Task TEA-33, design 4.1 case 3: the shared part options record. */
class PackagePartOptionsTest {

	@Test
	void filledReplacesEveryNullWithItsDefault() {
		PackagePartOptions f = PackagePartOptions.empty().filled(PackagePartSelection.EXCLUDE);
		assertEquals(new PackagePartOptions(PackagePartSelection.EXCLUDE, SuppressedFindings.EXCLUDE,
				InTriageFindings.EXCLUDE, VexDocumentFormat.CYCLONEDX_VEX, List.of()), f);
		assertEquals(PackagePartSelection.INCLUDE,
				PackagePartOptions.empty().filled(PackagePartSelection.INCLUDE).selection());
	}

	@Test
	void filledKeepsEverySetValue() {
		PackagePartOptions set = new PackagePartOptions(PackagePartSelection.INCLUDE, SuppressedFindings.INCLUDE,
				InTriageFindings.INCLUDE, VexDocumentFormat.OPENVEX, List.of(ArtifactType.BOM));
		assertEquals(set, set.filled(PackagePartSelection.EXCLUDE));
	}

	@Test
	void applicableToIsTheTableForAllEightParts() {
		Map<PackagePart, EnumSet<Field>> expected = Map.of(
				PackagePart.AGGREGATE_SBOM, EnumSet.of(Field.SELECTION),
				PackagePart.VDR, EnumSet.of(Field.SELECTION, Field.SUPPRESSED_FINDINGS),
				PackagePart.VEX, EnumSet.of(Field.SELECTION, Field.SUPPRESSED_FINDINGS, Field.IN_TRIAGE_FINDINGS,
						Field.VEX_FORMAT),
				PackagePart.SUPPORT_ADDENDUM, EnumSet.of(Field.SELECTION),
				PackagePart.DEVICE_SUPPORT_STATEMENT, EnumSet.of(Field.SELECTION),
				PackagePart.NARRATIVE, EnumSet.of(Field.SELECTION),
				PackagePart.CLE, EnumSet.of(Field.SELECTION),
				PackagePart.RAW_ARTIFACTS, EnumSet.of(Field.SELECTION, Field.ARTIFACT_TYPES));
		assertEquals(PackagePart.values().length, expected.size());
		for (PackagePart p : PackagePart.values()) {
			assertEquals(expected.get(p), PackagePartOptions.applicableTo(p), p.name());
		}
	}

	@Test
	void setFieldsNamesTheNonNullComponents() {
		assertTrue(PackagePartOptions.empty().setFields().isEmpty());
		assertEquals(EnumSet.of(Field.VEX_FORMAT, Field.ARTIFACT_TYPES),
				new PackagePartOptions(null, null, null, VexDocumentFormat.OPENVEX, List.of()).setFields());
		assertEquals(EnumSet.allOf(Field.class), new PackagePartOptions(PackagePartSelection.INCLUDE,
				SuppressedFindings.EXCLUDE, InTriageFindings.EXCLUDE, VexDocumentFormat.CYCLONEDX_VEX,
				List.of(ArtifactType.VEX)).setFields());
	}

	@Test
	void theInputNamesAreTheSchemaFieldNames() {
		assertEquals("selection", Field.SELECTION.inputName());
		assertEquals("suppressedFindings", Field.SUPPRESSED_FINDINGS.inputName());
		assertEquals("inTriageFindings", Field.IN_TRIAGE_FINDINGS.inputName());
		assertEquals("vexFormat", Field.VEX_FORMAT.inputName());
		assertEquals("artifactTypes", Field.ARTIFACT_TYPES.inputName());
	}

	@Test
	void artifactTypesIsAnUnmodifiableCopyInInputOrderWithoutDuplicates() {
		List<ArtifactType> in = new ArrayList<>(List.of(ArtifactType.VEX, ArtifactType.BOM, ArtifactType.VEX,
				ArtifactType.RELEASE_NOTES, ArtifactType.BOM));
		PackagePartOptions o = new PackagePartOptions(null, null, null, null, in);
		assertEquals(List.of(ArtifactType.VEX, ArtifactType.BOM, ArtifactType.RELEASE_NOTES), o.artifactTypes());
		in.clear();
		assertEquals(3, o.artifactTypes().size());
		assertThrows(UnsupportedOperationException.class, () -> o.artifactTypes().add(ArtifactType.OTHER));
		assertNull(PackagePartOptions.empty().artifactTypes());
	}

	@Test
	void theRecordRoundTripsThroughTheSharedMapper() {
		PackagePartOptions o = new PackagePartOptions(PackagePartSelection.INCLUDE, SuppressedFindings.INCLUDE,
				InTriageFindings.EXCLUDE, VexDocumentFormat.OPENVEX, List.of(ArtifactType.BOM, ArtifactType.VEX));
		@SuppressWarnings("unchecked")
		Map<String, Object> asMap = Utils.OM.convertValue(o, Map.class);
		assertEquals(o, Utils.OM.convertValue(asMap, PackagePartOptions.class));
		assertEquals(PackagePartOptions.empty(),
				Utils.OM.convertValue(Utils.OM.convertValue(PackagePartOptions.empty(), Map.class), PackagePartOptions.class));
		assertTrue(!asMap.containsKey("setFields") && !asMap.containsKey("fields"), asMap.toString());
	}
}
