/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.model.PricingEntry.PricingSelector.ServiceTier;

/**
 * What the normaliser peeled off a declared model string before flattening it.
 *
 * <p>These four attributes are not models: {@code claude-opus-4-7-batch} and
 * {@code claude-opus-4-7} are one catalogue row with a different tier. The base string identifies
 * the row; these ride on the usage row and can select a price.
 *
 * <p>A record rather than the {@code Map<String, Object>} it was, because the key set has always
 * been fixed and every reader was a string lookup with a cast -- and one of them, the service tier,
 * then re-parsed its value into the enum the selector states. Four known keys read through
 * {@code get(String)} is a map pretending to be a type.
 *
 * @param hostingForm which provider form the string was written in ("vertex", "azure", "bedrock"),
 *        null when it named none. Distinct from the hosting the client reports, which wins.
 * @param snapshot a dated build, {@code 20250805}; an attribute of one build, never a model
 * @param contextVariant long-context marker as written, upper case ({@code 1M})
 * @param serviceTier the tier the string named; null when it named none, which prices as STANDARD
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelVariants(
		@JsonProperty("hostingForm") String hostingForm,
		@JsonProperty("snapshot") String snapshot,
		@JsonProperty("contextVariant") String contextVariant,
		@JsonProperty("serviceTier") ServiceTier serviceTier) implements Serializable {

	private static final long serialVersionUID = 20260920L;

	public static final ModelVariants NONE = new ModelVariants(null, null, null, null);

	/** True when the string carried no attributes at all, which is the common case. */
	public boolean isEmpty() {
		return StringUtils.isBlank(hostingForm) && StringUtils.isBlank(snapshot)
				&& StringUtils.isBlank(contextVariant) && null == serviceTier;
	}

	public ModelVariants withHostingForm(String form) {
		return new ModelVariants(form, snapshot, contextVariant, serviceTier);
	}

	public ModelVariants withSnapshot(String snap) {
		return new ModelVariants(hostingForm, snap, contextVariant, serviceTier);
	}

	public ModelVariants withContextVariant(String variant) {
		return new ModelVariants(hostingForm, snapshot, variant, serviceTier);
	}

	public ModelVariants withServiceTier(ServiceTier tier) {
		return new ModelVariants(hostingForm, snapshot, contextVariant, tier);
	}

	/**
	 * The tier for pricing: STANDARD when the string named none.
	 *
	 * <p>Defaulting this way is deliberate. An entry qualified to BATCH must not match a row whose
	 * tier we could not determine; defaulting the other way would price ordinary traffic at a
	 * discount rate.
	 */
	public ServiceTier effectiveServiceTier() {
		return null == serviceTier ? ServiceTier.STANDARD : serviceTier;
	}
}
