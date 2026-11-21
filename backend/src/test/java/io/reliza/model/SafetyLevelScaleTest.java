/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.reliza.model.SafetyContextData.LevelNote;
import io.reliza.model.cdx.v20.Cdx20SafetyScheme;

class SafetyLevelScaleTest {

	@ParameterizedTest
	@CsvSource({
		"ISO_26262, QM, QM, 0", "ISO_26262, ASIL QM, QM, 0", "ISO_26262, ASIL-B, ASIL B, 2",
		"ISO_26262, asil d, ASIL D, 4", "ISO_26262, A, ASIL A, 1",
		"IEC_61508, SIL 1, SIL 1, 1", "IEC_61508, sil4, SIL 4, 4", "IEC_61508, none, SIL 0, 0",
		// DO-178C letters run opposite to the rank: A is the highest.
		"DO_178C, DAL E, DAL E, 0", "DO_178C, Level A, DAL A, 4", "DO_178C, B, DAL B, 3",
		"IEC_62304, class-c, Class C, 2", "IEC_62304, Class A, Class A, 0", "IEC_62304, b, Class B, 1",
		"EN_50128, SIL 0, SIL 0, 0", "EN_50128, SIL-3, SIL 3, 3"
	})
	void ranksPredefinedLevels(Cdx20SafetyScheme scheme, String raw, String expectedLabel, int expectedRank) {
		SafetyLevelScale.Level level = SafetyLevelScale.classify(scheme, raw);
		assertEquals(LevelNote.OK, level.note());
		assertEquals(expectedRank, level.rank());
		assertEquals(expectedLabel, level.normalized());
	}

	@ParameterizedTest
	@CsvSource({ "ISO_26262, ASIL B(D)", "ISO_26262, b(d)" })
	void decompositionIsUnranked(Cdx20SafetyScheme scheme, String raw) {
		SafetyLevelScale.Level level = SafetyLevelScale.classify(scheme, raw);
		assertEquals(LevelNote.DECOMPOSITION, level.note());
		assertNull(level.rank());
	}

	@ParameterizedTest
	@CsvSource({ "ISO_26262, ASIL E", "IEC_61508, SIL 5", "DO_178C, F", "IEC_62304, D", "EN_50128, tier-2" })
	void unknownLevelsAreUnmapped(Cdx20SafetyScheme scheme, String raw) {
		SafetyLevelScale.Level level = SafetyLevelScale.classify(scheme, raw);
		assertEquals(LevelNote.UNMAPPED, level.note());
		assertNull(level.rank());
	}

	@Test
	void customSchemesAreUnranked() {
		assertEquals(LevelNote.CUSTOM_SCHEME, SafetyLevelScale.classify(null, "tier-2").note());
	}
}
