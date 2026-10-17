/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentSessionUsage;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.PricingEntry.PricingSelector;
import io.reliza.model.SessionUsageHosting;

/**
 * Pricing is where a wrong assumption becomes a wrong invoice, so these pin the selector rather
 * than the arithmetic.
 */
class ModelPricingServiceTest {

	private final ModelPricingService service = new ModelPricingService();

	private static final ZonedDateTime NOW = ZonedDateTime.now();

	private ModelOntologyData modelWith(PricingEntry... entries) {
		ModelOntologyData m = new ModelOntologyData();
		m.setPricing(new ArrayList<>(List.of(entries)));
		return m;
	}

	private PricingEntry entry(long inputMicros, long outputMicros, long cacheReadMicros,
			PricingSelector selector) {
		return new PricingEntry(UUID.randomUUID(), NOW.minusDays(30), null, "USD",
				PricingUnit.PER_MILLION_TOKENS, inputMicros, outputMicros, cacheReadMicros,
				0L, null, selector, null, null, null, NOW.minusDays(30));
	}

	private AgentSessionUsage row(long input, long output, long cacheRead, long maxContext) {
		AgentSessionUsage r = new AgentSessionUsage();
		r.setReportedAt(NOW);
		r.setInputTokens(input);
		r.setOutputTokens(output);
		r.setCacheReadTokens(cacheRead);
		r.setMaxRequestContextTokens(maxContext);
		r.setMinRequestContextTokens(maxContext);
		r.setHosting(SessionUsageHosting.DIRECT);
		r.setRecordData(Map.of());
		return r;
	}

	@Test
	void costIsIntegerArithmeticInMicros() {
		// 3 USD per million input, 15 per million output: 1M input + 100k output = 3.0 + 1.5
		ModelOntologyData model = modelWith(entry(3_000_000L, 15_000_000L, 300_000L, null));
		var derived = service.derive(model, row(1_000_000, 100_000, 0, 50_000));
		assertEquals(3_000_000L + 1_500_000L, derived.costMicros());
	}

	@Test
	void noEntryMeansNoCostRatherThanZero() {
		// A zero would read as "this was free", which is a different claim from "we cannot say".
		var derived = service.derive(modelWith(), row(1_000, 1_000, 0, 1_000));
		assertNull(derived.costMicros());
	}

	@Test
	void longContextIsChosenOnFullContextNotOnInputTokens() {
		// The bug this test exists for: with prompt caching, input_tokens counts only the
		// uncached remainder. A cached long-context request shows ~10k input against ~900k real
		// context, so a selector reading input_tokens would price it at the base rate forever.
		PricingEntry base = entry(3_000_000L, 15_000_000L, 300_000L, null);
		PricingEntry long_ = entry(6_000_000L, 22_500_000L, 600_000L,
				new PricingSelector(200_000L, null, null, null, null));
		ModelOntologyData model = modelWith(base, long_);

		AgentSessionUsage cachedLongRequest = row(10_000, 5_000, 900_000, 915_000);
		assertEquals(long_.uuid(), service.selectEntry(model, cachedLongRequest).orElseThrow().uuid());

		AgentSessionUsage shortRequest = row(10_000, 5_000, 50_000, 65_000);
		assertEquals(base.uuid(), service.selectEntry(model, shortRequest).orElseThrow().uuid());
	}

	@Test
	void hostingSelectsItsOwnRate() {
		// Bedrock and direct are one catalogue row -- one model -- and two prices.
		PricingEntry direct = entry(3_000_000L, 15_000_000L, 300_000L, null);
		PricingEntry bedrock = entry(3_300_000L, 16_500_000L, 330_000L,
				new PricingSelector(null, null, null, SessionUsageHosting.BEDROCK, null));
		ModelOntologyData model = modelWith(direct, bedrock);

		AgentSessionUsage onBedrock = row(1_000_000, 0, 0, 10_000);
		onBedrock.setHosting(SessionUsageHosting.BEDROCK);
		assertEquals(bedrock.uuid(), service.selectEntry(model, onBedrock).orElseThrow().uuid());
		assertEquals(direct.uuid(), service.selectEntry(model, row(1_000_000, 0, 0, 10_000))
				.orElseThrow().uuid());
	}

	@Test
	void theMostSpecificEntryWins() {
		PricingEntry base = entry(3_000_000L, 15_000_000L, 300_000L, null);
		PricingEntry longContext = entry(6_000_000L, 22_500_000L, 600_000L,
				new PricingSelector(200_000L, null, null, null, null));
		PricingEntry longOnBedrock = entry(6_600_000L, 24_750_000L, 660_000L,
				new PricingSelector(200_000L, null, null, SessionUsageHosting.BEDROCK, null));
		ModelOntologyData model = modelWith(base, longContext, longOnBedrock);

		AgentSessionUsage r = row(10_000, 0, 900_000, 915_000);
		r.setHosting(SessionUsageHosting.BEDROCK);
		assertEquals(longOnBedrock.uuid(), service.selectEntry(model, r).orElseThrow().uuid());
	}

	@Test
	void twoEquallySpecificEntriesAreADataErrorNotACoinToss() {
		PricingEntry a = entry(3_000_000L, 15_000_000L, 300_000L,
				new PricingSelector(null, null, null, SessionUsageHosting.DIRECT, null));
		PricingEntry b = entry(4_000_000L, 16_000_000L, 400_000L,
				new PricingSelector(null, null, null, SessionUsageHosting.DIRECT, null));
		assertTrue(service.selectEntry(modelWith(a, b), row(1_000, 0, 0, 1_000)).isEmpty());
	}

	@Test
	void anExpiredEntryDoesNotPrice() {
		PricingEntry expired = new PricingEntry(UUID.randomUUID(), NOW.minusDays(60),
				NOW.minusDays(30), "USD", PricingUnit.PER_MILLION_TOKENS, 3_000_000L,
				15_000_000L, 300_000L, 0L, null, null, null, null, null, NOW.minusDays(60));
		assertNull(service.derive(modelWith(expired), row(1_000_000, 0, 0, 1_000)).costMicros());
	}

	@Test
	void aRowStraddlingAThresholdPricesHighAndSaysSo() {
		// The band table in the client did not know about this threshold, so one row mixes
		// requests either side of it. Over-estimating is the safe direction, and the flag is how
		// the band table gets fixed.
		PricingEntry base = entry(3_000_000L, 15_000_000L, 300_000L, null);
		PricingEntry long_ = entry(6_000_000L, 22_500_000L, 600_000L,
				new PricingSelector(200_000L, null, null, null, null));
		AgentSessionUsage straddling = row(10_000, 0, 900_000, 915_000);
		straddling.setMinRequestContextTokens(50_000);

		var derived = service.derive(modelWith(base, long_), straddling);
		assertEquals(long_.uuid(), derived.priceVersion());
		assertTrue(derived.straddle());
	}
}
