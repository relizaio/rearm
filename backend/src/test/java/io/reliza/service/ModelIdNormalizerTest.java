/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ModelVariants;
import io.reliza.model.PricingEntry.PricingSelector.ServiceTier;
import io.reliza.model.SessionUsageHosting;
import io.reliza.service.ModelIdNormalizer.Normalized;

/**
 * The peel table is the part of the catalogue most likely to be wrong on a string nobody
 * anticipated, and the one place where being wrong is expensive: a bad peel either mints a
 * duplicate row or folds two models into one.
 */
class ModelIdNormalizerTest {

	@Test
	void theSameModelWrittenThreeWaysIsOneString() {
		assertEquals("opus47", ModelIdNormalizer.normalize("opus-4.7").base());
		assertEquals("opus47", ModelIdNormalizer.normalize("opus4.7").base());
		assertEquals("opus47", ModelIdNormalizer.normalize("Opus 4.7").base());
	}

	@Test
	void nameAndVersionJoinBeforeFlattening() {
		// (claude-opus, 4.7) and claude-opus-4.7 are the same declaration written two ways.
		assertEquals(ModelIdNormalizer.normalize("claude-opus-4.7").base(),
				ModelIdNormalizer.normalize("claude-opus", "4.7").base());
	}

	@Test
	void differentVersionsNeverCollapse() {
		assertNotEquals(ModelIdNormalizer.normalize("claude-haiku-4.5").base(),
				ModelIdNormalizer.normalize("claude-haiku-4.7").base());
	}

	@Test
	void longContextIsAnAttributeNotAModel() {
		Normalized n = ModelIdNormalizer.normalize("claude-opus-4-7[1m]");
		assertEquals("claudeopus47", n.base());
		assertEquals("1M", n.variants().contextVariant());
		// and the plain form lands on the same row
		assertEquals(n.base(), ModelIdNormalizer.normalize("claude-opus-4-7").base());
	}

	@Test
	void aDatedSnapshotPeelsToTheBaseModel() {
		Normalized n = ModelIdNormalizer.normalize("claude-3-5-sonnet-20240620");
		assertEquals("claude35sonnet", n.base());
		assertEquals("20240620", n.variants().snapshot());
		// two snapshots of one model are one row, which is what makes the fold safe
		assertEquals(n.base(), ModelIdNormalizer.normalize("claude-3-5-sonnet-20241022").base());
	}

	@Test
	void bedrockFormResolvesToTheModelAndRecordsHosting() {
		Normalized n = ModelIdNormalizer.normalize("us.anthropic.claude-fable-5-1-v1:0");
		assertEquals("claudefable51", n.base());
		assertEquals(SessionUsageHosting.BEDROCK, n.hosting());
		assertEquals(ModelIdNormalizer.normalize("claude-fable-5-1").base(), n.base());
	}

	@Test
	void vertexAndAzureFormsDoTheSame() {
		Normalized v = ModelIdNormalizer.normalize("publishers/anthropic/models/claude-fable-5-1");
		assertEquals("claudefable51", v.base());
		assertEquals(SessionUsageHosting.VERTEX, v.hosting());

		Normalized a = ModelIdNormalizer.normalize(
				"https://x.openai.azure.com/openai/deployments/gpt-5-1");
		assertEquals("gpt51", a.base());
		assertEquals(SessionUsageHosting.AZURE, a.hosting());
	}

	@Test
	void serviceTierAndLatestPeelOff() {
		Normalized b = ModelIdNormalizer.normalize("claude-opus-4-7-batch");
		assertEquals("claudeopus47", b.base());
		assertEquals(ServiceTier.BATCH, b.variants().serviceTier());

		// -latest names whatever was current when the string was written; it says nothing about
		// identity, so it is dropped rather than recorded.
		Normalized l = ModelIdNormalizer.normalize("claude-opus-4-7-latest");
		assertEquals("claudeopus47", l.base());
		assertTrue(l.variants().isEmpty());
	}

	@Test
	void aPlainStringPeelsNothingAndStatesNoHosting() {
		Normalized n = ModelIdNormalizer.normalize("claude-fable-5-1");
		assertEquals("claudefable51", n.base());
		assertTrue(n.variants().isEmpty());
		assertNull(n.hosting());
	}

	@Test
	void severalAttributesPeelTogether() {
		Normalized n = ModelIdNormalizer.normalize("eu.anthropic.claude-opus-4-7-20250805-v1:0");
		assertEquals("claudeopus47", n.base());
		assertEquals(SessionUsageHosting.BEDROCK, n.hosting());
		assertEquals("20250805", n.variants().snapshot());
	}

	@Test
	void emptyAndNullAreNotExceptions() {
		assertEquals("", ModelIdNormalizer.normalize(null).base());
		assertEquals("", ModelIdNormalizer.normalize("   ").base());
	}

	@Test
	void thePlaceholderVersionIsNotPartOfTheIdentity() {
		// Auto-registration writes "unknown" when an agent declares no version, and the name then
		// usually carries the version instead. Treating the placeholder as a version string is how
		// (claude-opus-4-7, unknown) and (claude-opus, 4.7) end up as two rows for one model.
		assertEquals("claudeopus47", ModelIdNormalizer.normalize("claude-opus-4-7", "unknown").base());
		assertEquals(ModelIdNormalizer.normalize("claude-opus", "4.7").base(),
				ModelIdNormalizer.normalize("claude-opus-4-7", "unknown").base());
		assertEquals(ModelIdNormalizer.normalize("claude-opus", "4.7").base(),
				ModelIdNormalizer.normalize("Claude Opus 4.7", "UNKNOWN").base());
	}

	@Test
	void qualifiersPeelWhateverOrderTheClientWroteThemIn() {
		// Every qualifier pattern is end-anchored, so whichever one is outermost blocks the rest.
		// A single fixed-order pass left the context marker on "...[1m]-batch", which flattened to
		// claudeopus471m -- a SEPARATE catalogue row from claudeopus47, with the variant lost
		// instead of recorded. The CLI appends the tier after the model string, so this is the
		// shape a long-context batch request actually arrives in.
		var both = ModelIdNormalizer.normalize("claude-opus-4-7[1m]-batch");
		assertEquals("claudeopus47", both.base());
		assertEquals("1M", both.variants().contextVariant());
		assertEquals(ServiceTier.BATCH, both.variants().serviceTier());

		// Same failure one marker over: a dated snapshot followed by a tier.
		var dated = ModelIdNormalizer.normalize("claude-opus-4-7-20260101-batch");
		assertEquals("claudeopus47", dated.base());
		assertEquals("20260101", dated.variants().snapshot());
		assertEquals(ServiceTier.BATCH, dated.variants().serviceTier());

		// And all three at once, which no single-pass ordering could have handled.
		var all = ModelIdNormalizer.normalize("claude-opus-4-7-20260101[1m]-batch-latest");
		assertEquals("claudeopus47", all.base());
		assertEquals("20260101", all.variants().snapshot());
		assertEquals("1M", all.variants().contextVariant());
		assertEquals(ServiceTier.BATCH, all.variants().serviceTier());
	}

	@Test
	void everyWayOfWritingOneRequestResolvesToOneBase() {
		// The property that matters: these are all the same model, so they must not become
		// several catalogue rows.
		String expected = ModelIdNormalizer.normalize("claude-opus-4-7").base();
		for (String form : new String[] {
			"claude-opus-4-7",
			"claude-opus-4-7-batch",
			"claude-opus-4-7[1m]",
			"claude-opus-4-7[1m]-batch",
			"claude-opus-4-7-20260101",
			"claude-opus-4-7-20260101-batch",
			"claude-opus-4-7-latest",
		}) {
			assertEquals(expected, ModelIdNormalizer.normalize(form).base(),
					form + " must resolve to the same catalogue row");
		}
	}

	@Test
	void aRowStoredAsTheOldMapStillReads() {
		// What a usage row written before the record holds: four known keys in a JSON object.
		// If this stopped reading, every such row would price as though its model string named
		// no tier -- a silent repricing of history, not a visible failure.
		String stored = """
				{"hostingForm":"bedrock","snapshot":"20250805","contextVariant":"1M",
				 "serviceTier":"BATCH"}""";
		ModelVariants v = Utils.OM.readValue(stored, ModelVariants.class);
		assertEquals("bedrock", v.hostingForm());
		assertEquals("20250805", v.snapshot());
		assertEquals("1M", v.contextVariant());
		assertEquals(ServiceTier.BATCH, v.serviceTier());
		assertFalse(v.isEmpty());
	}

	@Test
	void anUnknownKeyInAStoredRowIsIgnoredRatherThanFatal() {
		ModelVariants v = Utils.OM.readValue("{\"snapshot\":\"20250805\",\"whatIsThis\":\"x\"}",
				ModelVariants.class);
		assertEquals("20250805", v.snapshot());
	}

	@Test
	void aStringThatNamesNoTierPricesAsStandard() {
		ModelVariants none = ModelIdNormalizer.normalize("claude-opus-4-7").variants();
		assertTrue(none.isEmpty());
		assertNull(none.serviceTier(), "the string named none");
		assertEquals(ServiceTier.STANDARD, none.effectiveServiceTier(),
				"and an entry qualified to BATCH must not match it");
	}

	// ---------- task RD2-26 ----------

	@Test
	void theJoinComesFirstAndTheBareNameOnlyWhenTheVersionAddsNothing() {
		assertEquals(java.util.List.of("claudefable5151", "claudefable51"),
				ModelIdNormalizer.candidates("claude-fable-5-1", "5.1"), "the version repeated in the name");
		assertEquals(java.util.List.of("claudeopus551", "claudeopus55"),
				ModelIdNormalizer.candidates("claude-opus-5-5", "1"), "a name that carries its own version");
		assertEquals(java.util.List.of("claudefable"), ModelIdNormalizer.candidates("claude-fable", null));
		assertEquals(java.util.List.of("claudefable"), ModelIdNormalizer.candidates("claude-fable", "unknown"));
		assertEquals(java.util.List.of("claudehaiku47"), ModelIdNormalizer.candidates("claude-haiku", "4.7"),
				"a version that tells models apart: never the bare name, or 4.7 would land on 4.5");
		assertEquals(java.util.List.of("gpt52026", "gpt5"), ModelIdNormalizer.candidates("gpt-5", "2026"),
				"a versioned family: the join first");
	}

	@Test
	void placeholdersAreNoModel() {
		for (String p : new String[] {"<synthetic>", " <SYNTHETIC> ", "", "   ", null, "unknown", "Unknown"}) {
			assertTrue(ModelIdNormalizer.isPlaceholder(p), String.valueOf(p));
		}
		for (String m : new String[] {"claude-fable", "synthetic-data-llm", "unknown-model"}) {
			assertFalse(ModelIdNormalizer.isPlaceholder(m), m);
		}
	}
}
