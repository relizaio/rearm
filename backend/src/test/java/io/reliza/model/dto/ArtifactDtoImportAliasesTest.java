/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.InputConversion;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ImportMode;
import io.reliza.model.VexImportMode;

/**
 * The deprecated {@code vexScope} / {@code vexImportMode} upload fields fold into
 * {@code importScope} / {@code importMode}, generic winning, through the conversion
 * addArtifactManual uses ({@link InputConversion}) plus {@link ArtifactDto#resolveImportAliases}.
 * The programmatic path's {@code ArtifactService.uploadDtoOf} is pinned in
 * {@code ArtifactServiceUploadDtoImportAliasesTest}.
 */
class ArtifactDtoImportAliasesTest {

	private static ArtifactDto convert(Map<String, Object> input) {
		ArtifactDto dto = InputConversion.convert(input, ArtifactDto.class, "artifact");
		dto.resolveImportAliases();
		return dto;
	}

	private static Map<String, Object> vexInput() {
		Map<String, Object> m = new HashMap<>();
		m.put("type", "VEX");
		return m;
	}

	@Test
	void deprecatedFieldsOnlyFillTheGenericOnes() {
		Map<String, Object> m = vexInput();
		m.put("vexScope", "RELEASE");
		m.put("vexImportMode", "STAGE");
		ArtifactDto dto = convert(m);
		assertEquals(AnalysisScope.RELEASE, dto.getImportScope());
		assertEquals(ImportMode.STAGE, dto.getImportMode());
	}

	@Test
	void genericFieldsOnlyAreKept() {
		Map<String, Object> m = vexInput();
		m.put("importScope", "BRANCH");
		m.put("importMode", "REJECT");
		ArtifactDto dto = convert(m);
		assertEquals(AnalysisScope.BRANCH, dto.getImportScope());
		assertEquals(ImportMode.REJECT, dto.getImportMode());
	}

	@Test
	void genericFieldsWinWhenBothAreSent() {
		Map<String, Object> m = vexInput();
		m.put("importScope", "ORG");
		m.put("importMode", "AUTO_ACCEPT");
		m.put("vexScope", "RELEASE");
		m.put("vexImportMode", "REJECT");
		ArtifactDto dto = convert(m);
		assertEquals(AnalysisScope.ORG, dto.getImportScope());
		assertEquals(ImportMode.AUTO_ACCEPT, dto.getImportMode());
	}

	@Test
	@SuppressWarnings("deprecation")
	void deprecatedModeHasTheSameValuesAsTheGenericOne() {
		assertEquals(Arrays.stream(ImportMode.values()).map(Enum::name).toList(),
				Arrays.stream(VexImportMode.values()).map(Enum::name).toList());
	}

	@Test
	void neitherSentLeavesThemUnsetForTheHandlerDefaults() {
		ArtifactDto dto = convert(vexInput());
		assertNull(dto.getImportScope());
		assertNull(dto.getImportMode());
	}
}
