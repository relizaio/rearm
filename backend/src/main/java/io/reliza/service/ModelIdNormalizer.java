/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import io.reliza.model.ModelVariants;
import io.reliza.model.PricingEntry.PricingSelector.ServiceTier;
import io.reliza.model.SessionUsageHosting;

/**
 * Turns the many strings that name one model into one base string plus the attributes that were
 * hiding inside it.
 *
 * <p>Agents declare a model however their client spells it: {@code claude-opus-4.7},
 * {@code opus4.7}, {@code Opus 4.7}, {@code claude-opus-4-7-latest},
 * {@code us.anthropic.claude-opus-4-7-v1:0}, {@code claude-opus-4-7[1m]}. Under the old rules each
 * of those could mint its own catalogue row. They are one model with attributes attached: a
 * context variant, a snapshot date, a service tier, a hosting provider.
 *
 * <p>So this peels the attributes off <em>before</em> flattening, because they live in the
 * punctuation that flattening destroys, and returns them separately. The variants ride on the
 * usage row and can select a price; the base string identifies the row.
 *
 * <p>Pure and static on purpose: the peel table is the part most likely to be wrong on a string
 * nobody anticipated, and a pure function is the part that can be exhaustively tested without a
 * database. The caller keeps the raw string, so a bad peel is always recoverable.
 */
public final class ModelIdNormalizer {

	private ModelIdNormalizer() {}

	/** Bedrock: {@code [region.]anthropic.claude-opus-4-7-v1:0}, vendor prefix and version suffix. */
	private static final Pattern BEDROCK = Pattern.compile(
			"^(?:[a-z]{2}(?:-[a-z]+)*\\.)?(?:anthropic|amazon|meta|mistral|cohere|ai21)\\.(.+?)(?:-v\\d+:\\d+)?$");

	/** Vertex: {@code publishers/anthropic/models/claude-opus-4-7}. */
	private static final Pattern VERTEX_PATH = Pattern.compile(
			"^publishers/[^/]+/models/(.+)$");

	/** Azure deployment paths that still carry the model id: {@code .../deployments/<id>}. */
	private static final Pattern AZURE_PATH = Pattern.compile(
			"^(?:.*/)?deployments/([^/]+)$");

	/** A dated snapshot, either trailing ({@code -20250805}) or Vertex's {@code @20250805}. */
	private static final Pattern SNAPSHOT = Pattern.compile("[-@](\\d{8})$");

	/** Long-context markers, in the forms clients actually write them. */
	private static final Pattern CONTEXT_VARIANT = Pattern.compile("(?:\\[(\\d+m)\\]|[-_](\\d+m))$");

	private static final Map<String, ServiceTier> SERVICE_TIER_SUFFIXES = Map.of(
			"batch", ServiceTier.BATCH, "priority", ServiceTier.PRIORITY,
			"standard", ServiceTier.STANDARD);

	/**
	 * @param base flattened identity string: lower case, no punctuation, no whitespace
	 * @param variants what was peeled off; {@link ModelVariants#NONE} when the string was plain
	 * @param hosting where the string says the model was served from, or null when it does not say.
	 *        A hosting stated by the client always wins over this.
	 */
	public record Normalized(String base, ModelVariants variants, SessionUsageHosting hosting) {}

	/**
	 * @param name model name as declared
	 * @param version model version as declared, joined onto the name when both are present, which
	 *        is what makes {@code (claude-opus, 4.7)} and {@code claude-opus-4.7} one string
	 */
	/** The placeholder auto-registration writes when an agent declares no version. */
	private static final String UNKNOWN_VERSION = "unknown";

	public static Normalized normalize(String name, String version) {
		// "unknown" is the absence of a version, not a version. Joining it would put the
		// placeholder into the identity string, so (claude-opus-4-7, unknown) would normalise to
		// claudeopus47unknown and never meet (claude-opus, 4.7) -- which is precisely the pair
		// the catalogue exists to unite, and the commonest shape in rows written before it.
		String effectiveVersion = StringUtils.isNotBlank(version)
				&& !UNKNOWN_VERSION.equalsIgnoreCase(version.trim()) ? version.trim() : null;
		String joined = null != effectiveVersion && StringUtils.isNotBlank(name)
				? name.trim() + "-" + effectiveVersion
				: StringUtils.defaultString(StringUtils.isNotBlank(name) ? name : effectiveVersion);
		return normalize(joined);
	}

	/**
	 * Whether a declared model is no model at all (task RD2-26): blank, {@code unknown}, or the
	 * {@code <synthetic>} a client writes on a turn no model ran. Its usage goes to one pseudo-model per
	 * organization rather than minting a catalogue row per spelling.
	 */
	public static boolean isPlaceholder(String name) {
		if (StringUtils.isBlank(name)) return true;
		String n = name.strip().toLowerCase(Locale.ROOT);
		return n.equals(UNKNOWN_VERSION) || n.equals("<synthetic>");
	}

	/**
	 * The strings a declaration may be known by, in the order resolution tries them (task RD2-26):
	 * the name joined with the version, then -- only when the version adds nothing the name does not
	 * already say -- the bare name.
	 *
	 * <p>The version adds nothing when it is absent, when the name already ends with it
	 * ({@code (claude-fable-5-1, 5.1)}, joined claudefable5151, resolves by claudefable51), or when
	 * the name carries a version of its own ({@code (claude-opus-5-5, 1)}). Otherwise the version is
	 * what tells models apart and the bare name is not tried: {@code (claude-haiku, 4.7)} must not
	 * land on the bundle's claude-haiku 4.5, nor {@code (claude-opus, 4.7)} on claude-opus 5. A real
	 * versioned family ({@code gpt-5} + {@code 2026-08}) resolves by the join, which comes first.
	 */
	public static java.util.List<String> candidates(String name, String version) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		out.add(normalize(name, version).base());
		String bare = normalize(name).base();
		String v = null == version || UNKNOWN_VERSION.equalsIgnoreCase(version.strip()) ? ""
				: version.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		boolean redundant = v.isEmpty() || (bare.length() > v.length() && bare.endsWith(v))
				|| (!bare.isEmpty() && Character.isDigit(bare.charAt(bare.length() - 1)));
		if (redundant) out.add(bare);
		out.removeIf(String::isEmpty);
		return new java.util.ArrayList<>(out);
	}

	public static Normalized normalize(String declared) {
		ModelVariants variants = ModelVariants.NONE;
		SessionUsageHosting hosting = null;
		String s = StringUtils.defaultString(declared).trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty()) return new Normalized("", variants, null);

		// Provider forms first: they are the only rules that read '/', ':' and the vendor dot,
		// all of which the flattening below removes.
		Matcher m = VERTEX_PATH.matcher(s);
		if (m.matches()) {
			s = m.group(1);
			hosting = SessionUsageHosting.VERTEX;
			variants = variants.withHostingForm("vertex");
		} else if ((m = AZURE_PATH.matcher(s)).matches()) {
			s = m.group(1);
			hosting = SessionUsageHosting.AZURE;
			variants = variants.withHostingForm("azure");
		} else if ((m = BEDROCK.matcher(s)).matches()) {
			s = m.group(1);
			hosting = SessionUsageHosting.BEDROCK;
			variants = variants.withHostingForm("bedrock");
		}

		// Trailing qualifiers, peeled in a loop until nothing more comes off.
		//
		// THE ORDER THEY APPEAR IN IS NOT THE ORDER THEY ARE WRITTEN. Every pattern below is
		// anchored to the end of the string, so whichever qualifier happens to be outermost blocks
		// the others: a single pass in a fixed order left "claude-opus-4-7[1m]-batch" with its
		// context marker unpeeled, flattening to claudeopus471m -- a separate catalogue row from
		// claudeopus47, with the variant lost rather than recorded. The same held for a snapshot
		// date followed by a tier. Looping until a pass changes nothing makes the result
		// independent of the order the client wrote them in, which is the only way this can be
		// correct given clients write them in any order at all.
		boolean peeled = true;
		while (peeled) {
			peeled = false;

			// A dated snapshot is an attribute of one build of the model, never a model of its own.
			Matcher snap = SNAPSHOT.matcher(s);
			if (snap.find()) {
				variants = variants.withSnapshot(snap.group(1));
				s = s.substring(0, snap.start());
				peeled = true;
			}

			// Long context is a pricing attribute; the model is the same model.
			Matcher ctx = CONTEXT_VARIANT.matcher(s);
			if (ctx.find()) {
				String marker = StringUtils.defaultString(ctx.group(1), ctx.group(2));
				variants = variants.withContextVariant(marker.toUpperCase(Locale.ROOT));
				s = s.substring(0, ctx.start());
				peeled = true;
			}

			for (Map.Entry<String, ServiceTier> e : SERVICE_TIER_SUFFIXES.entrySet()) {
				String suffix = "-" + e.getKey();
				if (s.endsWith(suffix)) {
					variants = variants.withServiceTier(e.getValue());
					s = s.substring(0, s.length() - suffix.length());
					peeled = true;
				}
			}

			// "-latest" says nothing about identity and is dropped rather than recorded: it names
			// whatever was current when the string was written.
			if (s.endsWith("-latest")) {
				s = s.substring(0, s.length() - "-latest".length());
				peeled = true;
			}
		}

		// Flatten what is left. Everything non-alphanumeric goes, so "opus-4.7", "opus4.7" and
		// "Opus 4.7" are one string.
		String base = s.replaceAll("[^a-z0-9]", "");
		return new Normalized(base, variants, hosting);
	}
}
