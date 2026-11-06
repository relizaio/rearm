/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

/** Task FDAW-4, design T13: every package kind carries the label refusal texts name it by. */
class PackageKindTest {

	@Test
	void everyKindHasALabel() {
		for (PackageKind kind : PackageKind.values()) {
			assertFalse(StringUtils.isBlank(kind.label()), kind.name());
		}
	}

	@Test
	void fdaPremarketReadsAsTheUiNamesIt() {
		assertEquals("FDA premarket", PackageKind.FDA_PREMARKET.label());
	}
}
