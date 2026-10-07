/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentSessionUsage;
import io.reliza.model.ModelOntologyData;
import io.reliza.common.Utils;
import io.reliza.model.ModelVariants;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingSelector.ServiceTier;
import io.reliza.model.PricingEntry.PricingSelector.ReasoningMatch;
import io.reliza.model.SessionUsageHosting;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns tokens into money, or says why it cannot.
 *
 * <p>Tokens never change; prices do. So a usage row records facts and this derives cost at read
 * time from a dated entry, and every rollup names the price version it used. A model with no
 * entry covering the window yields no cost and marks the rollup incomplete -- never a guess, and
 * never a zero that reads like "free".
 */
@Slf4j
@Service
public class ModelPricingService {

	private static final long MICROS_PER_MILLION = 1_000_000L;

	/**
	 * @param costMicros null when no entry applied
	 * @param priceVersion the entry used, for the rollup to record
	 * @param straddle the row spans a pricing threshold: priced under the higher entry, which
	 *        bounds the cost from above, and flagged so the client's band table can be corrected
	 */
	public record DerivedCost(Long costMicros, java.util.UUID priceVersion, boolean straddle) {}

	/**
	 * Choose the entry for one usage row.
	 *
	 * <p>Candidates are the entries whose dated window contains the row and whose every stated
	 * selector matches it; the winner is the most specific of them. That ordering is what lets a
	 * base rate and a long-context rate coexist on one model without an explicit priority.
	 *
	 * <p>Context is compared against the row's largest single-request context -- input plus cache
	 * read plus cache write -- not its input tokens. With prompt caching, input tokens count only
	 * the uncached remainder, which on a long cached session stays in the low thousands while the
	 * real context sits near the window limit. Comparing that against a 200k threshold would
	 * price every long-context request at the base rate.
	 */
	public Optional<PricingEntry> selectEntry(ModelOntologyData model, AgentSessionUsage row) {
		if (null == model || null == model.getPricing() || model.getPricing().isEmpty()) return Optional.empty();
		ZonedDateTime at = null != row.getReportedAt() ? row.getReportedAt() : ZonedDateTime.now();
		List<PricingEntry> candidates = new ArrayList<>();
		for (PricingEntry e : model.getPricing()) {
			if (!coversInstant(e, at)) continue;
			if (!selectorMatches(e.appliesTo(), row)) continue;
			candidates.add(e);
		}
		if (candidates.isEmpty()) return Optional.empty();
		candidates.sort(Comparator.comparingInt(
				(PricingEntry e) -> null == e.appliesTo() ? 0 : e.appliesTo().specificity()).reversed());
		if (candidates.size() > 1) {
			int top = specificity(candidates.get(0));
			long ties = candidates.stream().filter(e -> specificity(e) == top).count();
			if (ties > 1) {
				// Two entries equally specific and both applicable is a data error in the price
				// sheet, not something to resolve by picking one silently.
				log.error("Model {} has {} equally specific pricing entries applying to usage row {}; "
						+ "cost not derived", model.getUuid(), ties, row.getUuid());
				return Optional.empty();
			}
		}
		return Optional.of(candidates.get(0));
	}

	private static int specificity(PricingEntry e) {
		return null == e.appliesTo() ? 0 : e.appliesTo().specificity();
	}

	private static boolean coversInstant(PricingEntry e, ZonedDateTime at) {
		if (null == e.effectiveFrom() || at.isBefore(e.effectiveFrom())) return false;
		return null == e.effectiveTo() || at.isBefore(e.effectiveTo());
	}

	private boolean selectorMatches(PricingEntry.PricingSelector sel, AgentSessionUsage row) {
		if (null == sel) return true;
		if (null != sel.contextAboveTokens()
				&& row.getMaxRequestContextTokens() <= sel.contextAboveTokens()) return false;
		if (null != sel.hosting() && sel.hosting() != row.getHosting()) return false;
		ModelVariants variants = variantsOf(row);
		if (null != sel.contextVariant()
				&& !sel.contextVariant().equalsIgnoreCase(variants.contextVariant())) return false;
		// The row's tier is the one the normaliser peeled out of the model string, parsed into the
		// same enum the selector states. Comparing a typed selector against a raw variant string
		// would leave half the comparison untyped, which is how "BATCH" and "batch" become two
		// different tiers.
		if (null != sel.serviceTier() && sel.serviceTier() != variants.effectiveServiceTier()) return false;
		if (null != sel.reasoning() && ReasoningMatch.ANY != sel.reasoning()) {
			String level = recordString(row, "reasoningLevel");
			boolean on = StringUtils.isNotBlank(level) && !"OFF".equalsIgnoreCase(level);
			if ((ReasoningMatch.ON == sel.reasoning()) != on) return false;
		}
		return true;
	}

	/**
	 * The attributes the normaliser peeled off this row's model string.
	 *
	 * <p>Absent reads as {@link ModelVariants#NONE} rather than null: a row written from a plain
	 * model string stores nothing, and every caller would otherwise null-check before asking.
	 * A stored shape that cannot be read is logged and treated as absent, because a pricing
	 * comparison must not take down the cost view over one malformed row.
	 */
	private ModelVariants variantsOf(AgentSessionUsage row) {
		Object stored = null == row.getRecordData() ? null : row.getRecordData().get("variants");
		if (null == stored) return ModelVariants.NONE;
		try {
			return Utils.OM.convertValue(stored, ModelVariants.class);
		} catch (RuntimeException e) {
			log.error("Unreadable variants on usage row {}; pricing it as though the model string"
					+ " named none", row.getUuid(), e);
			return ModelVariants.NONE;
		}
	}


	private String recordString(AgentSessionUsage row, String key) {
		Object v = null == row.getRecordData() ? null : row.getRecordData().get(key);
		return null == v ? null : String.valueOf(v);
	}

	/**
	 * Cost of one row, in USD micros.
	 *
	 * <p>Integer arithmetic throughout: a rate is micros per million tokens, so a term is
	 * {@code tokens * rate / 1_000_000} rounded half up, and the terms are summed. Nothing here
	 * is a double, so nothing drifts when a year of rows is summed.
	 */
	public DerivedCost derive(ModelOntologyData model, AgentSessionUsage row) {
		// A placeholder's usage (task RD2-26) is no model's work: it prices at zero, a complete cost,
		// rather than as an unpriced model that would mark every total it joins incomplete.
		if (null != model && model.getResolution() == ModelOntologyData.ModelResolution.SYNTHETIC) {
			return new DerivedCost(0L, null, false);
		}
		Optional<PricingEntry> chosen = selectEntry(model, row);
		if (chosen.isEmpty()) return new DerivedCost(null, null, false);
		PricingEntry e = chosen.get();
		long micros = 0;
		micros += term(row.getInputTokens(), e.inputMicros());
		micros += term(row.getOutputTokens(), e.outputMicros());
		micros += term(row.getCacheReadTokens(), e.cacheReadMicros());
		micros += term(row.getCacheWriteTokens(), e.cacheWriteMicros());
		Object reasoning = null == row.getRecordData() ? null : row.getRecordData().get("reasoningTokens");
		if (null != e.reasoningMicros() && reasoning instanceof Number rn) {
			micros += term(rn.longValue(), e.reasoningMicros());
		}
		boolean straddle = null != e.appliesTo() && null != e.appliesTo().contextAboveTokens()
				&& row.getMinRequestContextTokens() <= e.appliesTo().contextAboveTokens();
		if (straddle) {
			// The row mixes requests either side of a threshold the client's band table did not
			// know about. Priced under the higher entry -- an over-estimate rather than an
			// under-estimate -- and flagged so the band table can be corrected.
			log.info("Usage row {} straddles the {} threshold (min {}, max {}); priced under the "
					+ "higher entry", row.getUuid(), e.appliesTo().contextAboveTokens(),
					row.getMinRequestContextTokens(), row.getMaxRequestContextTokens());
		}
		return new DerivedCost(micros, e.uuid(), straddle);
	}

	private static long term(long tokens, Long rateMicrosPerMillion) {
		if (tokens <= 0 || null == rateMicrosPerMillion || rateMicrosPerMillion <= 0) return 0;
		return roundedDiv(Math.multiplyExact(tokens, rateMicrosPerMillion), MICROS_PER_MILLION);
	}

	private static long roundedDiv(long numerator, long denominator) {
		return (numerator + denominator / 2) / denominator;
	}
}
