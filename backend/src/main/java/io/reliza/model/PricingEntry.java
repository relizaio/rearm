/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One dated rate for one model, in micros per million tokens.
 *
 * <p>Money is integers throughout: a rate is micros per million tokens and a cost is micros, so
 * nothing rounds twice and nothing drifts. The currency field exists for a later non-USD rate; v1
 * writes USD.
 *
 * <p>Entries are immutable. A correction is a new entry plus an expiry on the old one, which is
 * what lets a rollup record the price version it used and still mean the same numbers a year
 * later.
 *
 * @param uuid the price version recorded on rollups
 * @param effectiveFrom inclusive
 * @param effectiveTo exclusive; null is open-ended
 * @param appliesTo optional selector; absent means this is the model's base rate
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingEntry(
		UUID uuid,
		ZonedDateTime effectiveFrom,
		ZonedDateTime effectiveTo,
		String currency,
		PricingUnit unit,
		Long inputMicros,
		Long outputMicros,
		Long cacheReadMicros,
		Long cacheWriteMicros,
		Long reasoningMicros,
		PricingSelector appliesTo,
		String source,
		String note,
		UUID createdBy,
		ZonedDateTime createdAt) implements java.io.Serializable {

	/**
	 * What the rates are per. An enum because it decides the arithmetic: reading it wrong is a
	 * cost off by six orders of magnitude, and a string could hold anything.
	 *
	 * <p>{@code currency} beside it stays a String: ISO 4217 is an open set maintained by someone
	 * else, and nothing in ReARM branches on it.
	 */
	public enum PricingUnit {
		PER_MILLION_TOKENS;

		/** Case-insensitive on read, like every other catalogue vocabulary. */
		@com.fasterxml.jackson.annotation.JsonCreator
		public static PricingUnit fromValue(String v) {
			return null == v ? null : valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
		}
	}

	public static final String CURRENCY_USD = "USD";

	/**
	 * Which rows an entry applies to. Every field stated must match; an absent field matches
	 * anything. The entry with the most fields stated wins, so a base rate and a qualified rate
	 * coexist without ordering rules.
	 *
	 * @param contextAboveTokens matches when a request's FULL context -- input plus cache read
	 *        plus cache write -- exceeds this. Not {@code inputTokens}, which with prompt caching
	 *        is only the uncached part and on a cached session never approaches a threshold.
	 * @param contextVariant matches a peeled variant such as {@code 1M}
	 * @param serviceTier which provider tier the request ran on
	 * @param hosting where the model was served from; the same model costs differently on Bedrock
	 * @param reasoning whether the entry applies to requests that used extended thinking
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PricingSelector(
			Long contextAboveTokens,
			String contextVariant,
			ServiceTier serviceTier,
			SessionUsageHosting hosting,
			ReasoningMatch reasoning) implements java.io.Serializable {

		/**
		 * Provider tier a request ran on. Mirrors what the normaliser peels out of a model string,
		 * so a selector and a usage row talk about the same thing rather than two spellings of it.
		 */
		public enum ServiceTier {
			STANDARD, BATCH, PRIORITY;

			/**
			 * Case-insensitive on read. These are operator-entered through a form and were a
			 * free-text String until recently, so a stored {@code batch} would otherwise make the
			 * whole ontology row unreadable -- losing every pricing entry on it, not just this one.
			 */
			@com.fasterxml.jackson.annotation.JsonCreator
			public static ServiceTier fromValue(String v) {
				return null == v ? null : valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
			}
		}

		/**
		 * Whether an entry applies to requests that used extended thinking.
		 *
		 * <p>{@code ANY} is not "unset": an entry stating ANY deliberately covers both, and
		 * counts toward specificity, whereas an absent field simply does not discriminate.
		 */
		public enum ReasoningMatch {
			ON, OFF, ANY;

			/** Case-insensitive on read, for the same reason as {@link ServiceTier}. */
			@com.fasterxml.jackson.annotation.JsonCreator
			public static ReasoningMatch fromValue(String v) {
				return null == v ? null : valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
			}
		}

		/** How many fields this selector states; the tie-break for entry choice. */
		public int specificity() {
			int n = 0;
			if (null != contextAboveTokens) n++;
			if (null != contextVariant) n++;
			if (null != serviceTier) n++;
			if (null != hosting) n++;
			// ANY states that the entry covers both, which does not narrow it; it is
			// deliberately not counted, so a base entry and an ANY entry tie rather than
			// the latter winning for having said nothing extra.
			if (null != reasoning && ReasoningMatch.ANY != reasoning) n++;
			return n;
		}
	}
}
