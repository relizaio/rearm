/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisScope;
import io.reliza.model.ImportMode;
import io.reliza.model.dto.ArtifactDto;

/**
 * addArtifactProgrammatic builds its DTOs with {@link ArtifactService#uploadDtoOf}, which
 * resolves the deprecated import aliases, generic winning.
 */
class ArtifactServiceUploadDtoImportAliasesTest {

	private static Map<String, Object> vexEntry() {
		Map<String, Object> m = new HashMap<>();
		m.put("type", "VEX");
		return m;
	}

	@Test
	void deprecatedFieldsOnlyFillTheGenericOnes() {
		Map<String, Object> m = vexEntry();
		m.put("vexScope", "RELEASE");
		m.put("vexImportMode", "STAGE");
		ArtifactDto dto = ArtifactService.uploadDtoOf(m);
		assertEquals(AnalysisScope.RELEASE, dto.getImportScope());
		assertEquals(ImportMode.STAGE, dto.getImportMode());
	}

	@Test
	void genericFieldsOnlyAreKept() {
		Map<String, Object> m = vexEntry();
		m.put("importScope", "BRANCH");
		m.put("importMode", "REJECT");
		ArtifactDto dto = ArtifactService.uploadDtoOf(m);
		assertEquals(AnalysisScope.BRANCH, dto.getImportScope());
		assertEquals(ImportMode.REJECT, dto.getImportMode());
	}

	@Test
	void genericFieldsWinWhenBothAreSent() {
		Map<String, Object> m = vexEntry();
		m.put("importScope", "ORG");
		m.put("importMode", "AUTO_ACCEPT");
		m.put("vexScope", "RELEASE");
		m.put("vexImportMode", "REJECT");
		ArtifactDto dto = ArtifactService.uploadDtoOf(m);
		assertEquals(AnalysisScope.ORG, dto.getImportScope());
		assertEquals(ImportMode.AUTO_ACCEPT, dto.getImportMode());
	}
}
