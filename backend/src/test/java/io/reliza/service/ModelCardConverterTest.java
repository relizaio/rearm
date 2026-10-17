/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The 1.6 to 2.0 model card conversion, exercised directly: the mapping is pure, and routing these
 * through the sweep would spend a Spring context to test a data transformation.
 */
class ModelCardConverterTest {

	/** A 1.6 card with something in every area the mapping table covers. */
	private static Map<String, Object> legacyCard() {
		Map<String, Object> card = new LinkedHashMap<>();
		card.put("bom-ref", "model-1");
		Map<String, Object> mp = new LinkedHashMap<>();
		mp.put("approach", Map.of("type", "supervised"));
		mp.put("task", "text-generation");
		mp.put("architectureFamily", "transformer");
		mp.put("modelArchitecture", "claude");
		mp.put("inputs", List.of(Map.of("format", "text/plain")));
		mp.put("outputs", List.of(Map.of("format", "image/png")));
		mp.put("datasets", List.of(Map.of("ref", "dataset-1"), Map.of("name", "inline-set")));
		card.put("modelParameters", mp);
		Map<String, Object> qa = new LinkedHashMap<>();
		qa.put("performanceMetrics", List.of(new LinkedHashMap<>(Map.of(
				"type", "accuracy", "value", "0.91", "slice", "en"))));
		qa.put("graphics", Map.of("description", "a chart"));
		card.put("quantitativeAnalysis", qa);
		card.put("considerations", Map.of("ethicalConsiderations", List.of(Map.of("name", "bias"))));
		card.put("properties", List.of(Map.of("name", "cdx:ai-ml:model:contextWindow", "value", "200000")));
		return card;
	}

	@Test
	@SuppressWarnings("unchecked")
	void theMappedAreasLandInTheirTwoPointZeroHomes() {
		Map<String, Object> out = ModelCardConverter.convert(legacyCard());
		Map<String, Object> props = (Map<String, Object>) out.get("modelProperties");

		// Single values widen to lists.
		assertEquals(List.of("supervised"), props.get("learningTypes"));
		assertEquals(List.of("text-generation"), props.get("tasks"));

		assertEquals(Map.of("family", "transformer", "name", "claude"), props.get("architecture"));

		// measurements[] nests the number under measure.
		List<Map<String, Object>> measurements = (List<Map<String, Object>>) out.get("measurements");
		assertEquals(1, measurements.size());
		assertEquals("accuracy", measurements.get(0).get("type"));
		assertEquals(Map.of("value", "0.91"), measurements.get(0).get("measure"));
		assertEquals("en", measurements.get(0).get("slice"));

		assertEquals(List.of(Map.of("name", "cdx:ai-ml:model:contextWindow", "value", "200000")),
				props.get("properties"));

		// Keys 2.0 did not move are copied through.
		assertEquals("model-1", out.get("bom-ref"));
		// And the 1.6 containers are gone.
		assertFalse(out.containsKey("modelParameters"), "1.6 modelParameters must not survive");
		assertFalse(out.containsKey("quantitativeAnalysis"), "1.6 quantitativeAnalysis must not survive");
	}

	@Test
	@SuppressWarnings("unchecked")
	void formatBecomesDataTypePlusAnInferredModality() {
		Map<String, Object> out = ModelCardConverter.convert(legacyCard());
		Map<String, Object> props = (Map<String, Object>) out.get("modelProperties");
		Map<String, Object> in = ((List<Map<String, Object>>) props.get("inputs")).get(0);
		// The format string survives verbatim as the dataType; only the modality is inferred.
		assertEquals("text/plain", in.get("dataType"));
		assertEquals("text", in.get("modality"));
		assertFalse(in.containsKey("format"), "the 1.6 key must not remain beside the 2.0 one");
		Map<String, Object> outp = ((List<Map<String, Object>>) props.get("outputs")).get(0);
		assertEquals("image", outp.get("modality"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void anUnrecognisedFormatIsCustomRatherThanAGuess() {
		Map<String, Object> card = Map.of("modelParameters",
				Map.of("inputs", List.of(Map.of("format", "application/x-something"))));
		Map<String, Object> out = ModelCardConverter.convert(card);
		Map<String, Object> props = (Map<String, Object>) out.get("modelProperties");
		Map<String, Object> in = ((List<Map<String, Object>>) props.get("inputs")).get(0);
		assertEquals("custom", in.get("modality"));
		assertEquals("application/x-something", in.get("dataType"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void referencedDatasetsCarryOverAndInlineOnesArePreserved() {
		Map<String, Object> out = ModelCardConverter.convert(legacyCard());
		Map<String, Object> props = (Map<String, Object>) out.get("modelProperties");
		Map<String, Object> training = (Map<String, Object>) props.get("training");
		// A bom-ref reference is a reference in 2.0 as well.
		assertEquals(List.of(Map.of("ref", "dataset-1")), training.get("datasets"));
		// The inline object cannot become a component here, so it is kept rather than flattened
		// into something that would read as a reference but resolve to nothing.
		String legacy = (String) out.get(ModelCardConverter.LEGACY_PREFIX + "Datasets");
		assertTrue(legacy.contains("inline-set"), "inline dataset must survive, got: " + legacy);
	}

	@Test
	void whatTwoPointZeroCannotHoldIsKeptAsLegacyTextNotDropped() {
		Map<String, Object> out = ModelCardConverter.convert(legacyCard());
		String considerations = (String) out.get(ModelCardConverter.LEGACY_PREFIX + "Considerations");
		String graphics = (String) out.get(ModelCardConverter.LEGACY_PREFIX + "Graphics");
		assertTrue(considerations.contains("bias"), "considerations must survive: " + considerations);
		assertTrue(graphics.contains("a chart"), "graphics must survive: " + graphics);
		// Text, not a nested object: these keys are outside the 2.0 schema.
		assertEquals(String.class, out.get(ModelCardConverter.LEGACY_PREFIX + "Considerations").getClass());
	}

	@Test
	void anEmptyOrNullCardConvertsToAnEmptyCard() {
		assertTrue(ModelCardConverter.convert(null).isEmpty());
		assertTrue(ModelCardConverter.convert(Map.of()).isEmpty());
	}

	@Test
	void convertingAnAlreadyConvertedCardChangesNothing() {
		// The sweep skips rows marked 2.0, so this cannot normally happen -- but a converter whose
		// second pass mangles its own output is a landmine for any future re-run, and the check
		// costs one assertion.
		Map<String, Object> once = ModelCardConverter.convert(legacyCard());
		assertEquals(once, ModelCardConverter.convert(once));
	}
}
