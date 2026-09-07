/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.ValidationResult;

/**
 * {@code ReleaseData.validateReleaseData} accumulates errors from independent
 * checks (tags, device support window) into one {@link ValidationResult}. Pins
 * a real regression from review: the tags branch replaces {@code errors} with
 * an IMMUTABLE {@code List.of(...)}, so a later check that assumed it could
 * call {@code .add()} on the existing list would throw {@code
 * UnsupportedOperationException} the moment both checks fail on the same
 * release, instead of reporting both problems.
 */
class ReleaseDataValidationTest {

	private static ReleaseData releaseWith(List<TagRecord> tags, LocalDate eos, LocalDate eol) {
		ReleaseData rd = new ReleaseData();
		rd.setTags(tags);
		rd.setEos(eos);
		rd.setEol(eol);
		return rd;
	}

	@Test
	void bothChecksFailingTogetherReportsBothNotJustOne() {
		List<TagRecord> duplicateKeyTags = List.of(
				new TagRecord("env", "prod", Removable.YES),
				new TagRecord("env", "staging", Removable.YES));

		ValidationResult vr = ReleaseData.validateReleaseData(
				releaseWith(duplicateKeyTags, LocalDate.parse("2035-01-01"), LocalDate.parse("2030-01-01")));

		Assertions.assertFalse(vr.isValid());
		Assertions.assertEquals(2, vr.getNumErrors(),
				"a release failing two independent checks must report both, not lose one to a replace");
	}

	@Test
	void eosAfterEolAloneIsReported() {
		ValidationResult vr = ReleaseData.validateReleaseData(
				releaseWith(List.of(), LocalDate.parse("2035-01-01"), LocalDate.parse("2030-01-01")));
		Assertions.assertFalse(vr.isValid());
		Assertions.assertEquals(1, vr.getNumErrors());
	}

	@Test
	void aCoherentWindowIsValid() {
		ValidationResult vr = ReleaseData.validateReleaseData(
				releaseWith(List.of(), LocalDate.parse("2030-01-01"), LocalDate.parse("2035-01-01")));
		Assertions.assertTrue(vr.isValid());
	}
}
